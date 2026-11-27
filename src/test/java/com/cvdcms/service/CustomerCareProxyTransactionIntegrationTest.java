package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cskh_proxy_tx_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomerCareProxyTransactionIntegrationTest {

    @Autowired private TreatmentPlanService treatmentPlanService;
    @Autowired private LeaveRequestService leaveRequestService;
    @MockitoSpyBean private CustomerCareAutomationService automationService;

    @Autowired private BranchRepository branchRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffRepository;
    @Autowired private PatientProfileRepository patientRepository;
    @Autowired private TreatmentPlanRepository planRepository;
    @Autowired private TreatmentPlanInstallmentRepository installmentRepository;
    @Autowired private CustomerCareTaskRepository taskRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private AppointmentRepository appointmentRepository;

    @Test
    void proxyFactory_commitsPaymentEvenIfCskhFails_andReconcilesOnNextRun() {
        assertTrue(AopUtils.isAopProxy(treatmentPlanService));
        assertInstanceOf(InstallmentPaymentSyncPostProcessor.CskhWrappedTreatmentPlanService.class, treatmentPlanService);

        Branch branch = new Branch();
        branch.setBranchCode("CN_TX1");
        branch.setBranchName("Chi nhánh TX");
        branch.setAddress("123 Test");
        branch.setPhone("0241111111");
        branch.setIsActive(true);
        branch = branchRepository.save(branch);

        User docUser = new User();
        docUser.setUsername("doc_tx1");
        docUser.setPassword("hash");
        docUser.setPhoneNumber("0900000101");
        docUser.setEmail("doc_tx1@cvdcms.local");
        docUser.setIsActive(true);
        docUser.setStatus("ACTIVE");
        docUser.setBranch(branch);
        docUser = userRepository.save(docUser);

        StaffProfile doc = new StaffProfile();
        doc.setStaffCode("BS_TX1");
        doc.setFullName("BS TX");
        doc.setPositionTitle("Bác sĩ");
        doc.setStatus(StaffStatus.ACTIVE);
        doc.setUser(docUser);
        doc = staffRepository.save(doc);

        PatientProfile pat = new PatientProfile();
        pat.setUser(docUser);
        pat.setPatientCode("BN_TX1");
        pat.setFullName("[CSKH-TEST-2026] BN TX");
        pat.setGender("Nam");
        pat.setAddress("Hà Nội");
        pat.setPhoneNumber("0911111199");
        pat.setPreferredBranch(branch);
        pat = patientRepository.save(pat);

        TreatmentPlan plan = new TreatmentPlan();
        plan.setPlanCode("KHDT_TX1");
        plan.setPlanName("Niềng răng TX");
        plan.setBranch(branch);
        plan.setDoctor(doc);
        plan.setPatientProfile(pat);
        plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
        plan.setIsInstallment(true);
        plan.setTotalEstimatedCost(new BigDecimal("10000000"));
        plan.setFinalEstimatedCost(new BigDecimal("10000000"));
        plan.setTotalPaidAmount(BigDecimal.ZERO);
        plan = planRepository.save(plan);

        TreatmentPlanInstallment inst = new TreatmentPlanInstallment();
        inst.setPlan(plan);
        inst.setInstallmentOrder(1);
        inst.setPhaseName("Đợt 1");
        inst.setExpectedDate(LocalDate.now());
        inst.setExpectedAmount(new BigDecimal("10000000"));
        inst.setPaidAmount(BigDecimal.ZERO);
        inst.setStatus(TreatmentPlanInstallmentStatus.PENDING);
        inst = installmentRepository.save(inst);

        // Scheduler creates T_ZERO task
        automationService.reconcileDueInstallments();
        CustomerCareTask created = taskRepository.findFirstByInstallmentIdAndTaskType(
                inst.getInstallmentId(), CustomerCareTaskType.PAYMENT_REMINDER).orElseThrow();
        assertEquals("T_ZERO", created.getReminderStage());
        assertEquals(CustomerCareTaskStatus.PENDING, created.getStatus());

        // Simulate CSKH listener failure during payment -> payment MUST STILL COMMIT!
        doThrow(new RuntimeException("Simulated CSKH failure"))
                .when(automationService).reconcileInstallmentsForPlan(plan.getPlanId());

        treatmentPlanService.recordInstallmentPayment(plan.getPlanId(), new BigDecimal("10000000"), null);

        TreatmentPlan reloadedPlan = planRepository.findById(plan.getPlanId()).orElseThrow();
        TreatmentPlanInstallment reloadedInst = installmentRepository.findById(inst.getInstallmentId()).orElseThrow();
        assertEquals(0, new BigDecimal("10000000").compareTo(reloadedPlan.getTotalPaidAmount()));
        assertEquals(TreatmentPlanInstallmentStatus.PAID, reloadedInst.getStatus());

        // Scheduler fallback recovers and closes the CSKH task!
        automationService.reconcileDueInstallments();
        CustomerCareTask closed = taskRepository.findById(created.getTaskId()).orElseThrow();
        assertEquals(CustomerCareTaskStatus.COMPLETED, closed.getStatus());
    }

    @Test
    void leaveApproval_createsTaskAfterCommit_andDoesNotDuplicateOrModifyAppointment() {
        Branch branch = new Branch();
        branch.setBranchCode("CN_LV1");
        branch.setBranchName("Chi nhánh Leave");
        branch.setAddress("456 Test");
        branch.setPhone("0242222222");
        branch.setIsActive(true);
        branch = branchRepository.save(branch);

        User adminUser = new User();
        adminUser.setUsername("adm_lv1");
        adminUser.setPassword("hash");
        adminUser.setPhoneNumber("0900000102");
        adminUser.setEmail("adm_lv1@cvdcms.local");
        adminUser.setIsActive(true);
        adminUser.setStatus("ACTIVE");
        adminUser.setBranch(branch);
        adminUser = userRepository.save(adminUser);

        User docUser = new User();
        docUser.setUsername("doc_lv1");
        docUser.setPassword("hash");
        docUser.setPhoneNumber("0900000103");
        docUser.setEmail("doc_lv1@cvdcms.local");
        docUser.setIsActive(true);
        docUser.setStatus("ACTIVE");
        docUser.setBranch(branch);
        docUser = userRepository.save(docUser);

        StaffProfile doc = new StaffProfile();
        doc.setStaffCode("BS_LV1");
        doc.setFullName("BS Leave");
        doc.setPositionTitle("Bác sĩ");
        doc.setStatus(StaffStatus.ACTIVE);
        doc.setUser(docUser);
        doc = staffRepository.save(doc);

        PatientProfile pat = new PatientProfile();
        pat.setUser(docUser);
        pat.setPatientCode("BN_LV1");
        pat.setFullName("[CSKH-TEST-2026] BN Leave");
        pat.setGender("Nữ");
        pat.setAddress("Hà Nội");
        pat.setPhoneNumber("0922222299");
        pat.setPreferredBranch(branch);
        pat = patientRepository.save(pat);

        LocalDate targetDate = LocalDate.now().plusDays(2);
        Appointment appt = new Appointment();
        appt.setAppointmentCode("LH_LV1");
        appt.setBranch(branch);
        appt.setDoctor(doc);
        appt.setPatientProfile(pat);
        appt.setAppointmentDate(targetDate);
        appt.setExpectedTime(LocalTime.of(9, 0));
        appt.setExpectedEndTime(LocalTime.of(9, 30));
        appt.setStatus(AppointmentStatus.CONFIRMED);
        appt = appointmentRepository.save(appt);

        LeaveRequest leave = new LeaveRequest();
        leave.setStaff(doc);
        leave.setBranch(branch);
        leave.setLeaveType(LeaveType.PERSONAL);
        leave.setStartDate(targetDate);
        leave.setEndDate(targetDate);
        leave.setReason("Nghỉ phép kiểm thử");
        leave.setStatus(LeaveStatus.PENDING);
        leave = leaveRequestRepository.save(leave);

        leaveRequestService.approveLeaveRequest(leave.getLeaveId(), adminUser.getUserId(), "OK");

        assertTrue(taskRepository.existsByLeaveRequestIdAndAppointment_AppointmentIdAndTaskType(
                leave.getLeaveId(), appt.getAppointmentId(), CustomerCareTaskType.DOCTOR_LEAVE_RESCHEDULE));

        // Appointment must NOT be cancelled or reassigned automatically
        Appointment reloadedAppt = appointmentRepository.findById(appt.getAppointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.CONFIRMED, reloadedAppt.getStatus());
        assertEquals(doc.getStaffId(), reloadedAppt.getDoctor().getStaffId());

        // Second scheduler pass must not duplicate
        assertEquals(0, automationService.reconcileAllDoctorLeaves());
    }
}
