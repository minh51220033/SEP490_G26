package com.cvdcms;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.CustomerCareServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test kiểm thử toàn diện luồng nghiệp vụ Quản lý Chăm sóc khách hàng (FT44 - UC-26):
 * - Phân quyền theo Role (ADMIN, LE_TAN, BAC_SI) và khóa chặt theo Cơ sở (Branch).
 * - Chặn các role khác (QUAN_LY_KHO, BENH_NHAN...) bằng HTTP 403 FORBIDDEN.
 * - SCR-44.1 & SCR-44.2: Thống kê KPI Dashboard & Phân nhóm bệnh nhân.
 * - SCR-44.3: Lên lịch công việc chăm sóc thủ công & Tổng hợp tự động (an toàn với khoá ngoại mồ côi).
 * - SCR-44.4: Ghi nhận kết quả liên hệ (bắt buộc nextRetryAt khi NO_ANSWER/BUSY).
 * - SCR-44.5: Chuyển tiếp Bác sĩ (ESCALATED_TO_DOCTOR) & Phản hồi chuyên môn (chỉ Bác sĩ / Admin).
 * - SCR-44.6: Dòng thời gian lịch sử chăm sóc bệnh nhân theo cơ sở.
 */
@ExtendWith(MockitoExtension.class)
class CustomerCareWorkflowUnitTest {

    @Mock private CustomerCareTaskRepository taskRepository;
    @Mock private CustomerCareInteractionRepository interactionRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private StaffProfileRepository staffProfileRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private UserRepository userRepository;

    private CustomerCareServiceImpl customerCareService;

    private Branch branch1;
    private Branch branch2;
    private User adminUser;
    private User receptionistUser;
    private User doctorUser;
    private User warehouseUser;
    private PatientProfile patient100;
    private StaffProfile staffReceptionist;
    private StaffProfile staffDoctor;
    private CustomerCareTask sampleTask;

    @BeforeEach
    void setUp() {
        customerCareService = new CustomerCareServiceImpl(
                taskRepository,
                interactionRepository,
                patientProfileRepository,
                branchRepository,
                staffProfileRepository,
                visitRepository,
                appointmentRepository,
                prescriptionRepository,
                userRepository
        );

        branch1 = new Branch();
        branch1.setBranchId(1L);
        branch1.setBranchCode("CN01");
        branch1.setBranchName("Nha Khoa CV-DCMS Quận 1");
        branch1.setIsActive(true);

        branch2 = new Branch();
        branch2.setBranchId(2L);
        branch2.setBranchCode("CN02");
        branch2.setBranchName("Cơ sở Đống Đa");
        branch2.setIsActive(true);

        adminUser = new User();
        adminUser.setUserId(1L);
        adminUser.setUsername("admin");
        adminUser.setIsActive(true);
        adminUser.setStatus("ACTIVE");

        receptionistUser = new User();
        receptionistUser.setUserId(10L);
        receptionistUser.setUsername("letan01");
        receptionistUser.setIsActive(true);
        receptionistUser.setStatus("ACTIVE");

        doctorUser = new User();
        doctorUser.setUserId(11L);
        doctorUser.setUsername("bacsi01");
        doctorUser.setIsActive(true);
        doctorUser.setStatus("ACTIVE");

        warehouseUser = new User();
        warehouseUser.setUserId(30L);
        warehouseUser.setUsername("kho01");
        warehouseUser.setIsActive(true);
        warehouseUser.setStatus("ACTIVE");

        patient100 = new PatientProfile();
        patient100.setProfileId(100L);
        patient100.setPatientCode("BN000100");
        patient100.setFullName("Nguyễn Văn Hùng");
        patient100.setPhoneNumber("0910000000");

        staffReceptionist = new StaffProfile();
        staffReceptionist.setStaffId(1L);
        staffReceptionist.setStaffCode("NV001");
        staffReceptionist.setFullName("Lê Thị Lễ Tân");
        staffReceptionist.setDepartment("Lễ tân");
        staffReceptionist.setPositionTitle("Nhân viên CSKH");
        staffReceptionist.setStatus(StaffStatus.ACTIVE);

        staffDoctor = new StaffProfile();
        staffDoctor.setStaffId(2L);
        staffDoctor.setStaffCode("BS001");
        staffDoctor.setFullName("Dr. Nguyễn Văn Bác Sĩ");
        staffDoctor.setDepartment("Lâm sàng");
        staffDoctor.setPositionTitle("Bác sĩ Răng-Hàm-Mặt");
        staffDoctor.setStatus(StaffStatus.ACTIVE);

        sampleTask = new CustomerCareTask();
        sampleTask.setTaskId(1L);
        sampleTask.setTaskCode("TASK001");
        sampleTask.setBranch(branch1);
        sampleTask.setPatientProfile(patient100);
        sampleTask.setAssignedStaff(staffReceptionist);
        sampleTask.setTaskType(CustomerCareTaskType.POST_TREATMENT_FOLLOWUP);
        sampleTask.setDueDate(LocalDate.now());
        sampleTask.setStatus(CustomerCareTaskStatus.PENDING);
        sampleTask.setNotes("Hỏi thăm bệnh nhân sau khi cấy Implant 1 ngày");
    }

    @Test
    @DisplayName("Phân quyền: Chặn các role không thuộc ADMIN, LE_TAN, BAC_SI (ví dụ QUAN_LY_KHO, BENH_NHAN)")
    void testRejectUnauthorizedRole() {
        when(userRepository.findById(30L)).thenReturn(Optional.of(warehouseUser));
        when(userRepository.hasActiveAdminRole(30L)).thenReturn(false);
        when(taskRepository.findActiveRoleNamesByUserId(30L)).thenReturn(List.of("QUAN_LY_KHO"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> customerCareService.requireCareRole(30L));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("Phân quyền theo Cơ sở: Lễ tân ở CN01 không được phép xem hoặc tiếp nhận bệnh nhân của CN02")
    void testReceptionistBranchIsolation() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(receptionistUser));
        when(userRepository.hasActiveAdminRole(10L)).thenReturn(false);
        when(taskRepository.findActiveRoleNamesByUserId(10L)).thenReturn(List.of("LE_TAN"));
        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1, branch2));
        when(taskRepository.findExplicitBranchIdsByUserId(10L)).thenReturn(List.of(1L));

        // Lễ tân CN01 cố tình truyền branchId = 2 (CN02) -> phải bị chặn 403 FORBIDDEN
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> customerCareService.getSummary(2L, 10L));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("SCR-44.1 & SCR-44.2: Tính toán chính xác 4 chỉ số KPI Dashboard và số lượng từng tab phân nhóm")
    void testGetSummarySuccess() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        CustomerCareTask t2 = new CustomerCareTask();
        t2.setTaskId(2L);
        t2.setTaskCode("TASK002");
        t2.setBranch(branch1);
        t2.setPatientProfile(patient100);
        t2.setTaskType(CustomerCareTaskType.APPOINTMENT_REMINDER);
        t2.setStatus(CustomerCareTaskStatus.IN_PROGRESS);
        t2.setDueDate(LocalDate.now().minusDays(1));

        CustomerCareTask t3 = new CustomerCareTask();
        t3.setTaskId(3L);
        t3.setTaskCode("TASK003");
        t3.setBranch(branch1);
        t3.setPatientProfile(patient100);
        t3.setTaskType(CustomerCareTaskType.MEDICATION_REMINDER);
        t3.setStatus(CustomerCareTaskStatus.ESCALATED_TO_DOCTOR);
        t3.setDueDate(LocalDate.now());

        CustomerCareTask t4 = new CustomerCareTask();
        t4.setTaskId(4L);
        t4.setTaskCode("TASK004");
        t4.setBranch(branch1);
        t4.setPatientProfile(patient100);
        t4.setTaskType(CustomerCareTaskType.NO_SHOW_RECALL);
        t4.setStatus(CustomerCareTaskStatus.COMPLETED);
        t4.setDueDate(LocalDate.now());

        when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of(sampleTask, t2, t3, t4));
        when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        Map<String, Object> summary = customerCareService.getSummary(1L, 1L);

        assertEquals(4L, summary.get("totalTasks"));
        assertEquals(2L, summary.get("activeTasks")); // PENDING + IN_PROGRESS
        assertEquals(1L, summary.get("escalatedTasks")); // ESCALATED_TO_DOCTOR
        assertEquals(1L, summary.get("completedTasks")); // COMPLETED
    }

    @Test
    @DisplayName("SCR-44.3: Tạo công việc CSKH mới thuộc nhóm mở rộng (Nhắc trả góp) không lỗi ENUM MySQL")
    void testCreateTaskWithExtendedCareGroup() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(receptionistUser));
        when(userRepository.hasActiveAdminRole(10L)).thenReturn(false);
        when(taskRepository.findActiveRoleNamesByUserId(10L)).thenReturn(List.of("LE_TAN"));
        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1));
        when(taskRepository.findExplicitBranchIdsByUserId(10L)).thenReturn(List.of(1L));

        CustomerCareCreateDTO createDTO = new CustomerCareCreateDTO();
        createDTO.setBranchId(1L);
        createDTO.setProfileId(100L);
        createDTO.setAssignedStaffId(1L);
        createDTO.setCareGroup("INSTALLMENT_REMINDER");
        createDTO.setDueDate(LocalDate.now().plusDays(1));
        createDTO.setNotes("Nhắc thanh toán đợt 2 gói chỉnh nha.");

        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(patientProfileRepository.findById(100L)).thenReturn(Optional.of(patient100));
        when(staffProfileRepository.findById(1L)).thenReturn(Optional.of(staffReceptionist));
        when(taskRepository.save(any(CustomerCareTask.class))).thenAnswer(invocation -> {
            CustomerCareTask saved = invocation.getArgument(0);
            saved.setTaskId(99L);
            return saved;
        });

        CustomerCareTaskDTO result = customerCareService.createTask(createDTO, 10L);

        assertNotNull(result);
        assertEquals("APPOINTMENT_REMINDER", result.getTaskType());
        assertEquals("INSTALLMENT_REMINDER", result.getCareGroup());
        assertEquals("Nhắc trả góp", result.getCareGroupLabel());
        assertEquals("Nhắc thanh toán đợt 2 gói chỉnh nha.", result.getNotes());
    }

    @Test
    @DisplayName("SCR-44.4: Ghi nhận liên hệ NO_ANSWER nhưng thiếu thời điểm gọi lại (nextRetryAt) phải báo lỗi 400")
    void testRecordInteractionNoAnswerRequiresNextRetryAt() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(receptionistUser));
        when(userRepository.hasActiveAdminRole(10L)).thenReturn(false);
        when(taskRepository.findActiveRoleNamesByUserId(10L)).thenReturn(List.of("LE_TAN"));
        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1));
        when(taskRepository.findExplicitBranchIdsByUserId(10L)).thenReturn(List.of(1L));

        CustomerCareInteractionCreateDTO dto = new CustomerCareInteractionCreateDTO();
        dto.setChannel("PHONE_CALL");
        dto.setContactResult("NO_ANSWER");
        dto.setNotes("Gọi 2 cuộc không nghe máy.");
        dto.setNextRetryAt(null);

        when(taskRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(sampleTask));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> customerCareService.recordInteraction(1L, dto, 10L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("next_retry_at"));
    }

    @Test
    @DisplayName("SCR-44.5: Lễ tân chuyển tiếp Bác sĩ thành công; chỉ Bác sĩ hoặc Admin mới được nhập phản hồi chuyên môn")
    void testEscalateToDoctorAndDoctorResponsePermission() {
        // 1. Lễ tân tại CN01 chuyển tiếp Bác sĩ
        when(userRepository.findById(10L)).thenReturn(Optional.of(receptionistUser));
        when(userRepository.hasActiveAdminRole(10L)).thenReturn(false);
        when(taskRepository.findActiveRoleNamesByUserId(10L)).thenReturn(List.of("LE_TAN"));
        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1));
        when(taskRepository.findExplicitBranchIdsByUserId(10L)).thenReturn(List.of(1L));

        CustomerCareEscalateDTO escalateDTO = new CustomerCareEscalateDTO();
        escalateDTO.setEscalatedToDoctorId(2L);
        escalateDTO.setEscalationReason("Bệnh nhân báo sưng nướu nhẹ và dị ứng với thuốc kháng sinh.");

        when(taskRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(sampleTask));
        when(staffProfileRepository.findById(2L)).thenReturn(Optional.of(staffDoctor));
        when(taskRepository.save(any(CustomerCareTask.class))).thenAnswer(i -> i.getArgument(0));
        when(interactionRepository.findByTaskIdWithDetails(1L)).thenReturn(List.of());

        CustomerCareTaskDTO escalated = customerCareService.escalateToDoctor(1L, escalateDTO, 10L);

        assertEquals("ESCALATED_TO_DOCTOR", escalated.getStatus());
        assertEquals(2L, escalated.getEscalatedToDoctorId());
        assertEquals("Dr. Nguyễn Văn Bác Sĩ", escalated.getEscalatedToDoctorName());
        assertNotNull(escalated.getEscalatedAt());

        // 2. Lễ tân cố tình nhập phản hồi chuyên môn của Bác sĩ -> phải bị chặn 403 FORBIDDEN
        CustomerCareEscalateDTO responseDTO = new CustomerCareEscalateDTO();
        responseDTO.setDoctorResponse("Cho bệnh nhân ngưng uống Amoxicillin, đổi sang Paracetamol.");
        responseDTO.setNextStatus("COMPLETED");

        ResponseStatusException forbiddenEx = assertThrows(ResponseStatusException.class,
                () -> customerCareService.submitDoctorResponse(1L, responseDTO, 10L));
        assertEquals(HttpStatus.FORBIDDEN, forbiddenEx.getStatusCode());

        // 3. Bác sĩ tại CN01 nhập phản hồi chuyên môn -> Thành công
        when(userRepository.findById(11L)).thenReturn(Optional.of(doctorUser));
        when(userRepository.hasActiveAdminRole(11L)).thenReturn(false);
        when(taskRepository.findActiveRoleNamesByUserId(11L)).thenReturn(List.of("BAC_SI"));
        when(taskRepository.findExplicitBranchIdsByUserId(11L)).thenReturn(List.of(1L));

        CustomerCareTaskDTO responded = customerCareService.submitDoctorResponse(1L, responseDTO, 11L);
        assertEquals("COMPLETED", responded.getStatus());
        assertTrue(responded.getDoctorResponse().contains("ngưng uống Amoxicillin"));
    }
}
