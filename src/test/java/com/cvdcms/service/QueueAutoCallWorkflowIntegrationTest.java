package com.cvdcms.service;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.security.UrlIdCodec;
import com.cvdcms.service.ClinicQueueCoordinatorService;
import com.cvdcms.service.DiagnosisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:queue_autocall_e2e_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QueueAutoCallWorkflowIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UrlIdCodec urlIdCodec;

    @Autowired private ReceptionService receptionService;
    @Autowired private QueueWorkflowService queueWorkflowService;
    @Autowired private ClinicQueueCoordinatorService coordinatorService;
    @Autowired private DiagnosisService diagnosisService;

    @Autowired private BranchRepository branchRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private DentalChairRepository chairRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ClinicalQueueTicketRepository queueTicketRepository;
    @Autowired private SystemAuditLogRepository auditLogRepository;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private Icd10DiseaseRepository icd10DiseaseRepository;
    @Autowired private ExaminationDiagnosisRepository diagnosisRepository;

    private MockMvc mockMvc;

    private Branch branch;
    private Room room;
    private DentalChair chair1;
    private DentalChair chair2;
    private User receptionUser;
    private StaffProfile receptionStaff;
    private User doctor1User;
    private StaffProfile doctor1Staff;
    private User doctor2User;
    private StaffProfile doctor2Staff;
    private ServiceCategory serviceCategory;
    private com.cvdcms.entity.Service examService;
    private Icd10Disease icdDisease;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        String uid = UUID.randomUUID().toString().substring(0, 6);

        branch = new Branch();
        branch.setBranchCode("CN-" + uid);
        branch.setBranchName("Chi nhánh Test " + uid);
        branch.setAddress("123 Cầu Giấy, Hà Nội");
        branch.setWorkingDays("2,3,4,5,6,7,CN");
        branch.setIsActive(true);
        branch.setOpeningTime(LocalTime.of(0, 0));
        branch.setClosingTime(LocalTime.of(23, 59));
        branch = branchRepository.saveAndFlush(branch);

        room = new Room();
        room.setBranch(branch);
        room.setRoomCode("R-" + uid);
        room.setRoomName("Phòng Khám 1");
        room.setRoomType(RoomType.TREATMENT);
        room.setStatus(RoomStatus.ACTIVE);
        room = roomRepository.saveAndFlush(room);

        chair1 = new DentalChair();
        chair1.setBranch(branch);
        chair1.setRoom(room);
        chair1.setChairCode("G1-" + uid);
        chair1.setChairName("Ghế 01");
        chair1.setStatus(ChairStatus.AVAILABLE);
        chair1 = chairRepository.saveAndFlush(chair1);

        chair2 = new DentalChair();
        chair2.setBranch(branch);
        chair2.setRoom(room);
        chair2.setChairCode("G2-" + uid);
        chair2.setChairName("Ghế 02");
        chair2.setStatus(ChairStatus.AVAILABLE);
        chair2 = chairRepository.saveAndFlush(chair2);

        List<Permission> perms = new ArrayList<>();
        for (String code : List.of(
                "RECEPTION_CHECKIN", "RECEPTION_DISPATCH", "APPOINTMENT_MANAGE", "APPOINTMENT_VIEW",
                "CLINICAL_DAILY_VISITS", "CLINICAL_WORKFLOWS", "MEDICAL_RECORD_VIEW", "PATIENT_PROFILE_MANAGE")) {
            perms.add(ensurePermission(code));
        }

        Role recRole = ensureRole("LE_TAN", perms);
        Role docRole = ensureRole("BAC_SI", perms);

        receptionUser = createUser("rec_" + uid, UserType.STAFF, branch, recRole);
        receptionStaff = createStaff(receptionUser, "REC-" + uid, "Lễ tân Test " + uid, branch, recRole);

        doctor1User = createUser("doc1_" + uid, UserType.STAFF, branch, docRole);
        doctor1Staff = createStaff(doctor1User, "BS1-" + uid, "BS. Một " + uid, branch, docRole);

        doctor2User = createUser("doc2_" + uid, UserType.STAFF, branch, docRole);
        doctor2Staff = createStaff(doctor2User, "BS2-" + uid, "BS. Hai " + uid, branch, docRole);

        WorkShift shift = new WorkShift();
        shift.setBranch(branch);
        shift.setShiftCode("CA-" + uid);
        shift.setShiftName("Cả ngày");
        shift.setStartTime(LocalTime.of(0, 0));
        shift.setEndTime(LocalTime.of(23, 59));
        shift.setIsActive(true);
        shift = workShiftRepository.saveAndFlush(shift);

        LocalDate today = LocalDate.now(ZONE);
        for (StaffProfile doc : List.of(doctor1Staff, doctor2Staff)) {
            StaffSchedule sch = new StaffSchedule();
            sch.setBranch(branch);
            sch.setStaff(doc);
            sch.setShift(shift);
            sch.setWorkDate(today);
            sch.setStatus(ScheduleStatus.SCHEDULED);
            staffScheduleRepository.saveAndFlush(sch);
        }

        serviceCategory = new ServiceCategory();
        serviceCategory.setCategoryCode("CAT-" + uid);
        serviceCategory.setCategoryName("Khám tổng quát");
        serviceCategory.setCategoryType(ServiceType.PROCEDURE);
        serviceCategory.setIsActive(true);
        serviceCategory = serviceCategoryRepository.saveAndFlush(serviceCategory);

        examService = new com.cvdcms.entity.Service();
        examService.setServiceCode("SV-" + uid);
        examService.setServiceName("Khám & Tư vấn răng");
        examService.setCategory(serviceCategory);
        examService.setServiceType(ServiceType.PROCEDURE);
        examService.setBasePrice(new BigDecimal("100000"));
        examService.setUnit("Lần");
        examService.setStatus(ServiceStatus.ACTIVE);
        examService = serviceRepository.saveAndFlush(examService);

        String icdCode = "K02." + uid.substring(0, 4);
        icdDisease = icd10DiseaseRepository.findByIcd10Code(icdCode).orElseGet(() -> {
            Icd10Disease d = new Icd10Disease();
            d.setIcd10Code(icdCode);
            d.setDiseaseNameVi("Sâu răng test");
            d.setDiseaseNameEn("Dental caries test");
            d.setIsDentalCommon(true);
            d.setIsActive(true);
            return icd10DiseaseRepository.saveAndFlush(d);
        });
    }

    @Test
    @DisplayName("P-01 & P-02 cùng ưu tiên, cùng bàn: P-01 tự gọi trước, chặn mở khám P-02, hoàn tất P-01 tự gọi P-02")
    void p01AndP02SamePrioritySameDesk_AutoCallAndBlockDirectOpen() throws Exception {
        MockHttpSession recSession = sessionWith(receptionUser);
        MockHttpSession docSession = sessionWith(doctor1User);

        PatientProfile p1 = createPatient("Trần Đức Minh", "0911000001");
        PatientProfile p2 = createPatient("Lê Hoàng Phương Thảo", "0911000002");

        Appointment a1 = createConfirmedOnTimeAppointment(p1, doctor1Staff, "Đau răng số 6");
        Appointment a2 = createConfirmedOnTimeAppointment(p2, doctor1Staff, "Ê buốt răng cửa");

        // 1. Check-in P-01 -> tự cấp P-01 & tự chuyển sang Đang gọi (CALLED)
        var r1 = receptionService.checkInAppointment(recSession, a1.getAppointmentId(), "Đau răng số 6", UUID.randomUUID().toString());
        QueueTicket t1 = latestExamTicket(r1.id());
        assertEquals("P-01", t1.getDisplayNumber());
        assertEquals(QueueStatus.CALLED, t1.getStatus());

        // Check-in lại với cùng requestKey hoặc gọi cấp số lặp -> không sinh số mới
        var r1Dup = receptionService.checkInAppointment(recSession, a1.getAppointmentId(), "Đau răng số 6", UUID.randomUUID().toString());
        assertEquals(r1.id(), r1Dup.id());
        assertEquals(1, queueTicketRepository.findByVisitVisitId(r1.id()).size());

        // 2. Check-in P-02 -> tự cấp P-02 nhưng vẫn Đang chờ (WAITING) vì P-01 đang CALLED
        var r2 = receptionService.checkInAppointment(recSession, a2.getAppointmentId(), "Ê buốt răng cửa", UUID.randomUUID().toString());
        QueueTicket t2 = latestExamTicket(r2.id());
        assertEquals("P-02", t2.getDisplayNumber());
        assertEquals(QueueStatus.WAITING, t2.getStatus());

        // 3. Backend phải từ chối thao tác gọi thủ công của lễ tân qua endpoint cũ & service
        mockMvc.perform(post("/reception/queue/doctors/" + doctor1Staff.getStaffId() + "/call-next")
                        .session(recSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/reception/queue/" + t2.getTicketId() + "/CALL")
                        .session(recSession)
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .param("reason", "Cố tình gọi vượt thứ tự"))
                .andExpect(status().isForbidden());

        assertThrows(ResponseStatusException.class, () ->
                queueWorkflowService.callNext(recSession, branch.getBranchId(), doctor1Staff.getStaffId()));
        assertThrows(ResponseStatusException.class, () ->
                queueWorkflowService.transition(recSession, t2.getTicketId(), QueueWorkflowService.Action.CALL, null, "Cố tình gọi vượt"));

        // 4. Mở màn hình / lấy danh sách là read-only: không tự đổi trạng thái, sắp xếp P-01 trước P-02
        MvcResult dvRes = mockMvc.perform(get("/api/doctor/patients/daily-visits")
                        .session(docSession)
                        .param("date", "ALL")
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listJson = objectMapper.readTree(dvRes.getResponse().getContentAsString());
        assertEquals(2, listJson.size());
        assertEquals("P-01", listJson.get(0).get("ticketDisplayNumber").asText());
        assertEquals("CALLED", listJson.get(0).get("queueStatus").asText());
        assertEquals("P-02", listJson.get(1).get("ticketDisplayNumber").asText());
        assertEquals("WAITING", listJson.get(1).get("queueStatus").asText());

        mockMvc.perform(get("/api/doctor/visits/queue-status")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bannerText").value("Đang gọi P-01 – Trần Đức Minh"))
                .andExpect(jsonPath("$.calledVisit.visitId").value(r1.id()));

        // 5. Bác sĩ KHÔNG thể mở khám hay lập phác đồ / ghi điều trị cho P-02 còn Đang chờ
        String p2Token = urlIdCodec.encodeVisit(r2.id());
        String p2PatientToken = urlIdCodec.encodePatient(p2.getProfileId());

        mockMvc.perform(get("/doctor/examination/" + p2Token + "/diagnoses").session(docSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/doctor/daily-visits"));

        mockMvc.perform(get("/doctor/examination/" + p2Token + "/treatment-plans/create").session(docSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/doctor/daily-visits"));

        mockMvc.perform(get("/doctor/examination/patient/" + p2PatientToken).session(docSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/doctor/daily-visits"));

        mockMvc.perform(post("/api/doctor/patients/" + p2.getProfileId() + "/open-visit")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isConflict());

        DiagnosisRequestDTO blockedDiag = DiagnosisRequestDTO.builder()
                .visitId(r2.id()).icd10Code(icdDisease.getIcd10Code()).diagnosisType(DiagnosisType.MAIN).build();
        mockMvc.perform(post("/api/v1/diagnoses").session(docSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blockedDiag)))
                .andExpect(status().isConflict());

        TreatmentPlanRequestDTO blockedPlan = TreatmentPlanRequestDTO.builder()
                .patientId(p2.getProfileId()).branchId(branch.getBranchId()).visitId(r2.id())
                .planName("Phác đồ lậu").stages(List.of()).build();
        mockMvc.perform(post("/api/doctor/treatment-plans").session(docSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blockedPlan)))
                .andExpect(status().isConflict());

        // Kiểm tra P-02 vẫn còn WAITING sau tất cả nỗ lực mở vượt
        assertEquals(VisitStatus.WAITING, visitRepository.findById(r2.id()).orElseThrow().getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(r2.id()).getStatus());

        // 6. Bác sĩ bấm nút "Vào khám" chung -> mở đúng P-01 (dù UI có đang tìm kiếm P-02)
        mockMvc.perform(post("/api/doctor/visits/enter-exam")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitId").value(r1.id()))
                .andExpect(jsonPath("$.displayNumber").value("P-01"));

        assertEquals(VisitStatus.IN_EXAM, visitRepository.findById(r1.id()).orElseThrow().getStatus());
        assertEquals(QueueStatus.IN_SERVICE, latestExamTicket(r1.id()).getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(r2.id()).getStatus());

        // Bấm lại / reload khi đang có ca IN_SERVICE -> vẫn mở đúng P-01, không mở ca mới
        mockMvc.perform(post("/api/doctor/visits/enter-exam")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitId").value(r1.id()))
                .andExpect(jsonPath("$.reopenedActiveSession").value(true));

        // 7. Hoàn tất P-01 (chưa thanh toán, status = WAITING_PAYMENT) -> P-02 tự chuyển sang CALLED
        addMinimumDiagnosisAndServiceForCompletion(docSession, r1.id(), doctor1Staff.getStaffId());
        mockMvc.perform(post("/api/doctor/visits/" + r1.id() + "/complete-clinical")
                        .session(docSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doctorNotes\":\"Đã khám xong P-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("WAITING_PAYMENT"));

        assertEquals(QueueStatus.DONE, latestExamTicket(r1.id()).getStatus());
        assertEquals(QueueStatus.CALLED, latestExamTicket(r2.id()).getStatus());
        assertEquals(VisitStatus.WAITING, visitRepository.findById(r2.id()).orElseThrow().getStatus());

        mockMvc.perform(get("/api/doctor/visits/queue-status")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bannerText").value("Đang gọi P-02 – Lê Hoàng Phương Thảo"));
    }

    @Test
    @DisplayName("Vắng mặt, hết hàng chờ, và bệnh nhân mới check-in sau khi hàng chờ trống được tự động gọi ngay")
    void absentFlowEmptyQueueAndCheckInAfterEmpty() throws Exception {
        MockHttpSession recSession = sessionWith(receptionUser);
        MockHttpSession docSession = sessionWith(doctor1User);

        PatientProfile p1 = createPatient("Bệnh nhân Vắng", "0922000001");
        PatientProfile p2 = createPatient("Bệnh nhân Kế Tiếp", "0922000002");

        var a1 = createConfirmedOnTimeAppointment(p1, doctor1Staff, "Khám sâu răng");
        var a2 = createConfirmedOnTimeAppointment(p2, doctor1Staff, "Nhổ răng sữa");

        var r1 = receptionService.checkInAppointment(recSession, a1.getAppointmentId(), "Khám sâu răng", UUID.randomUUID().toString());
        var r2 = receptionService.checkInAppointment(recSession, a2.getAppointmentId(), "Nhổ răng sữa", UUID.randomUUID().toString());
        QueueTicket t1 = latestExamTicket(r1.id());
        QueueTicket t2 = latestExamTicket(r2.id());
        assertEquals(QueueStatus.CALLED, t1.getStatus());
        assertEquals(QueueStatus.WAITING, t2.getStatus());

        // Bác sĩ ghi nhận Vắng mặt lượt P-01 -> lưu audit log & tự gọi P-02
        mockMvc.perform(post("/api/doctor/visits/call-absent")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":" + t1.getTicketId() + ",\"reason\":\"Đã gọi loa 3 lần không có mặt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.nextCalledDisplayNumber").value("P-02"));

        assertEquals(QueueStatus.PAUSED, latestExamTicket(r1.id()).getStatus());
        assertEquals(QueueStatus.CALLED, latestExamTicket(r2.id()).getStatus());
        assertFalse(auditLogRepository.findRecentBranchActions(branch.getBranchId(), "QUEUE_MARK_ABSENT", LocalDate.now(ZONE).atStartOfDay()).isEmpty());

        // Ghi nhận vắng mặt P-02 khi không còn ai chờ -> hiển thị "Không có bệnh nhân đang chờ"
        mockMvc.perform(post("/api/doctor/visits/call-absent")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":" + t2.getTicketId() + ",\"reason\":\"Vắng mặt tại phòng chờ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deskStatus.bannerText").value("Không có bệnh nhân đang chờ"));

        // Có người mới check-in sau khoảng hàng chờ trống -> tự gọi ngay
        PatientProfile p3 = createPatient("Bệnh nhân Đến Sau", "0922000003");
        var a3 = createConfirmedOnTimeAppointment(p3, doctor1Staff, "Kiểm tra định kỳ");
        var r3 = receptionService.checkInAppointment(recSession, a3.getAppointmentId(), "Kiểm tra định kỳ", UUID.randomUUID().toString());
        assertEquals(QueueStatus.CALLED, latestExamTicket(r3.id()).getStatus());
    }

    @Test
    @DisplayName("Báo cấp cứu khi bàn đang bận: lưu người báo/thời điểm/lý do, gửi cảnh báo, không ngắt ca đang khám")
    void emergencyReportWhileDeskBusy_AlertsDeskWithoutInterruptingOngoingExam() throws Exception {
        MockHttpSession recSession = sessionWith(receptionUser);
        MockHttpSession docSession = sessionWith(doctor1User);

        PatientProfile pBusy = createPatient("Nguyễn Đang Khám", "0933000001");
        PatientProfile pNormal = createPatient("Trần Đang Chờ", "0933000002");
        PatientProfile pEmerTarget = createPatient("Lê Đột Xuất", "0933000003");

        var a1 = createConfirmedOnTimeAppointment(pBusy, doctor1Staff, "Trám răng sâu");
        var a2 = createConfirmedOnTimeAppointment(pNormal, doctor1Staff, "Cạo vôi răng");
        var a3 = createConfirmedOnTimeAppointment(pEmerTarget, doctor1Staff, "Đau nhức răng");

        var rBusy = receptionService.checkInAppointment(recSession, a1.getAppointmentId(), "Trám răng sâu", UUID.randomUUID().toString());
        var rNormal = receptionService.checkInAppointment(recSession, a2.getAppointmentId(), "Cạo vôi răng", UUID.randomUUID().toString());
        var rEmer = receptionService.checkInAppointment(recSession, a3.getAppointmentId(), "Đau nhức răng", UUID.randomUUID().toString());

        // Bác sĩ đang khám P-01
        coordinatorService.enterExaminationForDoctor(doctor1User, branch.getBranchId());
        assertEquals(QueueStatus.IN_SERVICE, latestExamTicket(rBusy.id()).getStatus());

        // Lễ tân bấm Báo cấp cứu cho lượt thứ 3 (P-03)
        QueueTicket emerTicket = latestExamTicket(rEmer.id());
        mockMvc.perform(post("/reception/queue/" + emerTicket.getTicketId() + "/EMERGENCY")
                        .session(recSession)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .param("reason", "Bệnh nhân chảy máu chân răng cấp cần xử lý ưu tiên"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Ca đang khám KHÔNG bị kết thúc, ca cấp cứu KHÔNG bị gọi vượt ngay lúc bàn bận
        assertEquals(VisitStatus.IN_EXAM, visitRepository.findById(rBusy.id()).orElseThrow().getStatus());
        assertEquals(QueueStatus.IN_SERVICE, latestExamTicket(rBusy.id()).getStatus());
        QueueTicket updatedEmer = latestExamTicket(rEmer.id());
        assertTrue(Boolean.TRUE.equals(updatedEmer.getIsEmergency()));
        assertEquals(QueueStatus.WAITING, updatedEmer.getStatus());

        // Bàn bác sĩ nhận cảnh báo kèm người báo, thời điểm, lý do ngay cả khi đang khám
        mockMvc.perform(get("/api/doctor/visits/queue-status")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emergencyAlerts[0].displayNumber").value("P-03"))
                .andExpect(jsonPath("$.emergencyAlerts[0].reportedBy").value(receptionStaff.getFullName()))
                .andExpect(jsonPath("$.emergencyAlerts[0].reason").value("Bệnh nhân chảy máu chân răng cấp cần xử lý ưu tiên"));

        // Khi bác sĩ hoàn thành ca P-01 -> ca cấp cứu P-03 (Emergency) phải được gọi trước P-02
        addMinimumDiagnosisAndServiceForCompletion(docSession, rBusy.id(), doctor1Staff.getStaffId());
        diagnosisService.completeClinicalExamination(rBusy.id(), "Hoàn tất ca đang khám");

        assertEquals(QueueStatus.CALLED, latestExamTicket(rEmer.id()).getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(rNormal.id()).getStatus());
    }

    @Test
    @DisplayName("Hai tab đồng thời, cô lập 2 bác sĩ, và mở lại ca cũ trước thanh toán không mất lượt đang gọi")
    void concurrentTabsDoctorIsolationAndReopenBeforePayment() throws Exception {
        MockHttpSession recSession = sessionWith(receptionUser);
        MockHttpSession doc1Session = sessionWith(doctor1User);
        MockHttpSession doc2Session = sessionWith(doctor2User);

        PatientProfile d1p1 = createPatient("BS1 Bệnh nhân 1", "0944000001");
        PatientProfile d1p2 = createPatient("BS1 Bệnh nhân 2", "0944000002");
        PatientProfile d1p3 = createPatient("BS1 Bệnh nhân 3", "0944000003");
        PatientProfile d2p1 = createPatient("BS2 Bệnh nhân 1", "0944000004");

        var rD1P1 = receptionService.checkInAppointment(recSession,
                createConfirmedOnTimeAppointment(d1p1, doctor1Staff, "Đau răng 1").getAppointmentId(), "Đau răng 1", UUID.randomUUID().toString());
        var rD1P2 = receptionService.checkInAppointment(recSession,
                createConfirmedOnTimeAppointment(d1p2, doctor1Staff, "Đau răng 2").getAppointmentId(), "Đau răng 2", UUID.randomUUID().toString());
        var rD1P3 = receptionService.checkInAppointment(recSession,
                createConfirmedOnTimeAppointment(d1p3, doctor1Staff, "Đau răng 3").getAppointmentId(), "Đau răng 3", UUID.randomUUID().toString());
        var rD2P1 = receptionService.checkInAppointment(recSession,
                createConfirmedOnTimeAppointment(d2p1, doctor2Staff, "BS2 khám răng").getAppointmentId(), "BS2 khám răng", UUID.randomUUID().toString());

        // Cô lập 2 bác sĩ: BS2 vào khám chỉ mở đúng lượt của BS2, không đụng hàng chờ BS1
        mockMvc.perform(post("/api/doctor/visits/enter-exam").session(doc2Session)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitId").value(rD2P1.id()));
        assertEquals(QueueStatus.CALLED, latestExamTicket(rD1P1.id()).getStatus());

        // Hai tab đồng thời bấm "Vào khám" trên BS1 -> cả hai trả về đúng 1 ca d1p1, d1p2 vẫn WAITING
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Long> enterCall = () -> coordinatorService.enterExaminationForDoctor(doctor1User, branch.getBranchId()).visit().getVisitId();
            Future<Long> f1 = pool.submit(enterCall);
            Future<Long> f2 = pool.submit(enterCall);
            Long open1 = f1.get(10, TimeUnit.SECONDS);
            Long open2 = f2.get(10, TimeUnit.SECONDS);
            assertEquals(rD1P1.id(), open1);
            assertEquals(rD1P1.id(), open2);
            assertEquals(QueueStatus.WAITING, latestExamTicket(rD1P2.id()).getStatus());

            // Hai tab đồng thời bấm Hoàn tất khám d1p1 -> chỉ d1p2 được gọi, d1p3 không bị nhảy/gọi trùng
            addMinimumDiagnosisAndServiceForCompletion(doc1Session, rD1P1.id(), doctor1Staff.getStaffId());
            Callable<Boolean> completeCall = () -> {
                try {
                    diagnosisService.completeClinicalExamination(rD1P1.id(), "Hoàn tất đồng thời");
                    return true;
                } catch (RuntimeException ex) {
                    return false;
                }
            };
            Future<Boolean> c1 = pool.submit(completeCall);
            Future<Boolean> c2 = pool.submit(completeCall);
            c1.get(10, TimeUnit.SECONDS);
            c2.get(10, TimeUnit.SECONDS);
            assertEquals(QueueStatus.CALLED, latestExamTicket(rD1P2.id()).getStatus());
            assertEquals(QueueStatus.WAITING, latestExamTicket(rD1P3.id()).getStatus());
        } finally {
            pool.shutdownNow();
        }

        // Mở lại ca d1p1 trước thanh toán -> giữ nguyên d1p2 đang CALLED và không gọi thêm d1p3
        mockMvc.perform(post("/api/doctor/visits/" + rD1P1.id() + "/reopen-clinical")
                        .session(doc1Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Bổ sung ghi chú trước thanh toán\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_EXAM"));

        assertEquals(QueueStatus.CALLED, latestExamTicket(rD1P2.id()).getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(rD1P3.id()).getStatus());

        // Hoàn tất lại d1p1 -> d1p2 vẫn là CALLED (không mất lượt, không nhảy sang d1p3)
        mockMvc.perform(post("/api/doctor/visits/" + rD1P1.id() + "/complete-clinical")
                        .session(doc1Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doctorNotes\":\"Hoàn tất lại sau bổ sung\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_PAYMENT"));

        assertEquals(QueueStatus.CALLED, latestExamTicket(rD1P2.id()).getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(rD1P3.id()).getStatus());
    }

    @Test
    @DisplayName("Tình huống 1: Chặn lễ tân PAUSE/PRIORITIZE thay gọi vượt & tab cũ Vắng mặt không tác động lượt mới")
    void situation1_receptionPauseBlockedAndStaleTabAbsentDoesNotAffectNewlyCalledP02() throws Exception {
        MockHttpSession recSession = sessionWith(receptionUser);
        MockHttpSession docSession = sessionWith(doctor1User);

        PatientProfile p1 = createPatient("TH1 Bệnh nhân P01", "0955000001");
        PatientProfile p2 = createPatient("TH1 Bệnh nhân P02", "0955000002");

        var r1 = receptionService.checkInAppointment(recSession,
                createConfirmedOnTimeAppointment(p1, doctor1Staff, "Khám răng 1").getAppointmentId(), "Khám răng 1", UUID.randomUUID().toString());
        var r2 = receptionService.checkInAppointment(recSession,
                createConfirmedOnTimeAppointment(p2, doctor1Staff, "Khám răng 2").getAppointmentId(), "Khám răng 2", UUID.randomUUID().toString());

        QueueTicket t1 = latestExamTicket(r1.id());
        QueueTicket t2 = latestExamTicket(r2.id());
        assertEquals(QueueStatus.CALLED, t1.getStatus());
        assertEquals(QueueStatus.WAITING, t2.getStatus());

        // Lễ tân cố tình PAUSE P-01 (đang CALLED) hoặc PAUSE/PRIORITIZE P-02 (đang WAITING) đều bị chặn 403
        mockMvc.perform(post("/reception/queue/" + t1.getTicketId() + "/PAUSE")
                        .session(recSession)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .param("reason", "Lễ tân thử tạm dừng P-01 để gọi P-02"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/reception/queue/" + t2.getTicketId() + "/PAUSE")
                        .session(recSession)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .param("reason", "Lễ tân thử tạm dừng lượt đang chờ"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/reception/queue/" + t2.getTicketId() + "/PRIORITIZE")
                        .session(recSession)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .param("reason", "Lễ tân thử tự nâng ưu tiên P-02"))
                .andExpect(status().isForbidden());

        // Ngay cả Admin cũng không được PAUSE lượt đang CALLED để né luồng Vắng mặt của bác sĩ
        Role adminRole = ensureRole("ADMIN", List.of());
        User adminUser = createUser("adm_" + UUID.randomUUID().toString().substring(0, 6), UserType.STAFF, branch, adminRole);
        MockHttpSession adminSession = sessionWith(adminUser);
        ResponseStatusException adminPauseCalledEx = assertThrows(ResponseStatusException.class,
                () -> queueWorkflowService.transition(adminSession, t1.getTicketId(), QueueWorkflowService.Action.PAUSE, null,
                        "Admin thử tạm dừng lượt đang CALLED"));
        assertEquals(403, adminPauseCalledEx.getStatusCode().value());

        assertEquals(QueueStatus.CALLED, latestExamTicket(r1.id()).getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(r2.id()).getStatus());

        // Bác sĩ ghi nhận Vắng mặt đúng P-01 -> P-01 sang PAUSED, P-02 tự chuyển sang CALLED
        mockMvc.perform(post("/api/doctor/visits/call-absent")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":" + t1.getTicketId() + ",\"reason\":\"Không có mặt tại ghế chờ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCalledDisplayNumber").value(t2.getDisplayNumber()));

        assertEquals(QueueStatus.PAUSED, latestExamTicket(r1.id()).getStatus());
        assertEquals(QueueStatus.CALLED, latestExamTicket(r2.id()).getStatus());

        // Tab cũ của bác sĩ vẫn giữ ticketId của P-01 và bấm Vắng mặt lần nữa -> 409 CONFLICT, không đụng P-02
        mockMvc.perform(post("/api/doctor/visits/call-absent")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":" + t1.getTicketId() + ",\"reason\":\"Bấm từ tab cũ\"}"))
                .andExpect(status().isConflict());

        // Request thiếu ticketId cũng phải bị từ chối (409), P-02 vẫn an toàn giữ CALLED
        mockMvc.perform(post("/api/doctor/visits/call-absent")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Thiếu mã lượt\"}"))
                .andExpect(status().isConflict());

        assertEquals(QueueStatus.CALLED, latestExamTicket(r2.id()).getStatus());

        // Lễ tân đưa P-01 trở lại hàng chờ (RESUME): chỉ về WAITING, không cướp CALLED của P-02
        mockMvc.perform(post("/reception/queue/" + t1.getTicketId() + "/RESUME")
                        .session(recSession)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk());

        assertEquals(QueueStatus.WAITING, latestExamTicket(r1.id()).getStatus());
        assertEquals(QueueStatus.CALLED, latestExamTicket(r2.id()).getStatus());
    }

    @Test
    @DisplayName("Tình huống 2: Mở lại P-01 trước thanh toán khi P-02 đang CALLED -> chặn mở 2 ca, giữ P-02 CALLED, hoàn tất lại không gọi P-03")
    void situation2_reopenPrePaymentSingleExamAtDeskAndKeepCalledP02WithoutCallingP03() throws Exception {
        MockHttpSession recSession = sessionWith(receptionUser);
        MockHttpSession docSession = sessionWith(doctor1User);

        PatientProfile p1 = createPatient("TH2 Bệnh nhân P01", "0955100001");
        PatientProfile p2 = createPatient("TH2 Bệnh nhân P02", "0955100002");
        PatientProfile p3 = createPatient("TH2 Bệnh nhân P03", "0955100003");

        var r1 = receptionService.checkInAppointment(recSession,
                createConfirmedOnTimeAppointment(p1, doctor1Staff, "Điều trị P1").getAppointmentId(), "Điều trị P1", UUID.randomUUID().toString());
        var r2 = receptionService.checkInAppointment(recSession,
                createConfirmedOnTimeAppointment(p2, doctor1Staff, "Điều trị P2").getAppointmentId(), "Điều trị P2", UUID.randomUUID().toString());
        var r3 = receptionService.checkInAppointment(recSession,
                createConfirmedOnTimeAppointment(p3, doctor1Staff, "Điều trị P3").getAppointmentId(), "Điều trị P3", UUID.randomUUID().toString());

        // Bác sĩ vào khám P-01 và hoàn tất lần 1 -> P-01 WAITING_PAYMENT (ticket DONE), P-02 CALLED, P-03 WAITING
        mockMvc.perform(post("/api/doctor/visits/enter-exam")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitId").value(r1.id()));

        addMinimumDiagnosisAndServiceForCompletion(docSession, r1.id(), doctor1Staff.getStaffId());
        mockMvc.perform(post("/api/doctor/visits/" + r1.id() + "/complete-clinical")
                        .session(docSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doctorNotes\":\"Hoàn tất P-01 lần 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_PAYMENT"));

        QueueTicket t2 = latestExamTicket(r2.id());
        assertEquals(QueueStatus.DONE, latestExamTicket(r1.id()).getStatus());
        assertEquals(QueueStatus.CALLED, t2.getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(r3.id()).getStatus());

        // Mở lại P-01 trước thanh toán để bổ sung thuốc/dịch vụ
        mockMvc.perform(post("/api/doctor/visits/" + r1.id() + "/reopen-clinical")
                        .session(docSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Bổ sung đơn thuốc và dịch vụ trước thanh toán\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_EXAM"));

        // Tab khác thử bấm Vào khám khi bàn đang có P-01 mở lại -> phải bị chặn 409 CONFLICT
        mockMvc.perform(post("/api/doctor/visits/enter-exam")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        // Kiểm tra bất biến: bàn chỉ có đúng 1 ca đang khám (P-01), P-02 vẫn CALLED, P-03 vẫn WAITING
        List<Visit> activeDeskVisits = visitRepository.findActiveExamsForDoctorAtBranch(
                branch.getBranchId(), doctor1Staff.getStaffId(), List.of(VisitStatus.IN_EXAM, VisitStatus.IN_TREATMENT));
        assertEquals(1, activeDeskVisits.size());
        assertEquals(r1.id(), activeDeskVisits.get(0).getVisitId());
        assertEquals(QueueStatus.CALLED, latestExamTicket(r2.id()).getStatus());
        assertEquals(VisitStatus.WAITING, visitRepository.findById(r2.id()).orElseThrow().getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(r3.id()).getStatus());

        mockMvc.perform(get("/api/doctor/visits/queue-status")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEnterExam").value(false))
                .andExpect(jsonPath("$.calledVisit.displayNumber").value(t2.getDisplayNumber()));

        // Hoàn tất lại P-01 -> trở về WAITING_PAYMENT, P-02 giữ lượt CALLED, không gọi thêm P-03
        mockMvc.perform(post("/api/doctor/visits/" + r1.id() + "/complete-clinical")
                        .session(docSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doctorNotes\":\"Hoàn tất lại P-01 sau khi bổ sung\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_PAYMENT"));

        assertEquals(QueueStatus.CALLED, latestExamTicket(r2.id()).getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(r3.id()).getStatus());

        // Ngược lại: sau khi bác sĩ vào khám P-02 (đang IN_EXAM), không cho mở lại P-01 (cùng bàn chỉ tối đa 1 ca khám)
        mockMvc.perform(post("/api/doctor/visits/enter-exam")
                        .session(docSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitId").value(r2.id()));

        mockMvc.perform(post("/api/doctor/visits/" + r1.id() + "/reopen-clinical")
                        .session(docSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Thử mở lại khi bàn đang khám P-02\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        assertEquals(VisitStatus.WAITING_PAYMENT, visitRepository.findById(r1.id()).orElseThrow().getStatus());
        assertEquals(VisitStatus.IN_EXAM, visitRepository.findById(r2.id()).orElseThrow().getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(r3.id()).getStatus());
    }

    @Test
    @DisplayName("Khởi động lại server đồng bộ tự gọi số WAITING có sẵn trên bàn rảnh và resolve đúng chi nhánh khi branchId null")
    void startupReconcileCallsStrandedWaitingTicketAndResolvesDoctorBranchWhenBranchIdNull() throws Exception {
        PatientProfile p1 = createPatient("BN Có Sẵn 1", "0966000001");
        PatientProfile p2 = createPatient("BN Có Sẵn 2", "0966000002");

        Visit v1 = new Visit();
        v1.setVisitCode("VST-" + UUID.randomUUID().toString().substring(0, 6));
        v1.setBranch(branch);
        v1.setPatientProfile(p1);
        v1.setPrimaryDoctor(doctor1Staff);
        v1.setVisitType(VisitType.APPOINTMENT);
        v1.setIsBookedAndOnTime(true);
        v1.setIsEmergency(false);
        v1.setStatus(VisitStatus.WAITING);
        v1.setCheckInAt(LocalDateTime.now(ZONE).minusMinutes(10));
        v1 = visitRepository.saveAndFlush(v1);

        Visit v2 = new Visit();
        v2.setVisitCode("VST-" + UUID.randomUUID().toString().substring(0, 6));
        v2.setBranch(branch);
        v2.setPatientProfile(p2);
        v2.setPrimaryDoctor(doctor1Staff);
        v2.setVisitType(VisitType.APPOINTMENT);
        v2.setIsBookedAndOnTime(true);
        v2.setIsEmergency(false);
        v2.setStatus(VisitStatus.WAITING);
        v2.setCheckInAt(LocalDateTime.now(ZONE).minusMinutes(9));
        v2 = visitRepository.saveAndFlush(v2);

        QueueTicket t1 = new QueueTicket();
        t1.setVisit(v1);
        t1.setBranch(branch);
        t1.setQueueDate(LocalDate.now(ZONE));
        t1.setQueueType(QueueType.EXAM);
        t1.setTicketNumber(1);
        t1.setDisplayNumber("P-01");
        t1.setStatus(QueueStatus.WAITING);
        t1.setIsPriority(true);
        t1.setIsEmergency(false);
        t1.setPriorityScore(75);
        t1.setIssuedAt(LocalDateTime.now(ZONE).minusMinutes(10));
        queueTicketRepository.saveAndFlush(t1);

        QueueTicket t2 = new QueueTicket();
        t2.setVisit(v2);
        t2.setBranch(branch);
        t2.setQueueDate(LocalDate.now(ZONE));
        t2.setQueueType(QueueType.EXAM);
        t2.setTicketNumber(2);
        t2.setDisplayNumber("P-02");
        t2.setStatus(QueueStatus.WAITING);
        t2.setIsPriority(true);
        t2.setIsEmergency(false);
        t2.setPriorityScore(75);
        t2.setIssuedAt(LocalDateTime.now(ZONE).minusMinutes(9));
        queueTicketRepository.saveAndFlush(t2);

        // Chạy đồng bộ khởi động -> P-01 tự chuyển sang CALLED, P-02 vẫn WAITING
        coordinatorService.reconcileTodayWaitingQueuesOnStartup();

        assertEquals(QueueStatus.CALLED, latestExamTicket(v1.getVisitId()).getStatus());
        assertEquals(QueueStatus.WAITING, latestExamTicket(v2.getVisitId()).getStatus());

        // Gọi /queue-status KHÔNG truyền branchId -> vẫn tự định vị đúng chi nhánh đang có lượt và cho phép Vào khám
        MockHttpSession docSession = sessionWith(doctor1User);
        mockMvc.perform(get("/api/doctor/visits/queue-status").session(docSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEnterExam").value(true))
                .andExpect(jsonPath("$.calledVisit.displayNumber").value("P-01"));

        // Tạm thời cho phép tài khoản ADMIN vào ca khám để test nhanh
        Role adminRole = ensureRole("ADMIN", List.of());
        User adminUser = createUser("adm_test_" + UUID.randomUUID().toString().substring(0, 6), UserType.STAFF, branch, adminRole);
        MockHttpSession adminSession = sessionWith(adminUser);

        mockMvc.perform(get("/api/doctor/visits/queue-status").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEnterExam").value(true))
                .andExpect(jsonPath("$.calledVisit.displayNumber").value("P-01"));

        mockMvc.perform(post("/api/doctor/visits/enter-exam").session(adminSession)
                        .param("branchId", String.valueOf(branch.getBranchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitId").value(v1.getVisitId()));

        assertEquals(VisitStatus.IN_EXAM, visitRepository.findById(v1.getVisitId()).orElseThrow().getStatus());
        assertEquals(QueueStatus.IN_SERVICE, latestExamTicket(v1.getVisitId()).getStatus());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void addMinimumDiagnosisAndServiceForCompletion(MockHttpSession docSession, Long visitId, Long doctorId) throws Exception {
        DiagnosisRequestDTO diagReq = DiagnosisRequestDTO.builder()
                .visitId(visitId)
                .icd10Code(icdDisease.getIcd10Code())
                .diagnosisType(DiagnosisType.MAIN)
                .toothNumbers("46")
                .notes("Chẩn đoán chính")
                .build();
        mockMvc.perform(post("/api/v1/diagnoses").session(docSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(diagReq)))
                .andExpect(status().isCreated());

        PerformedServiceRequestDTO svcReq = PerformedServiceRequestDTO.builder()
                .visitId(visitId)
                .serviceId(examService.getServiceId())
                .doctorId(doctorId)
                .toothNumbers("46")
                .quantity(BigDecimal.ONE)
                .unitPrice(examService.getBasePrice())
                .discountAmount(BigDecimal.ZERO)
                .finalPrice(examService.getBasePrice())
                .build();
        mockMvc.perform(post("/api/doctor/performed-services").session(docSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(svcReq)))
                .andExpect(status().isCreated());
    }

    private QueueTicket latestExamTicket(Long visitId) {
        return queueTicketRepository.findByVisitIdAndTypeOrderByDesc(visitId, QueueType.EXAM)
                .stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Không tìm thấy QueueTicket cho visitId=" + visitId));
    }

    private MockHttpSession sessionWith(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
        session.setAttribute("currentBranchId", branch.getBranchId());
        return session;
    }

    private Permission ensurePermission(String code) {
        return permissionRepository.findByPermissionCode(code).orElseGet(() -> {
            Permission p = new Permission();
            p.setPermissionCode(code);
            p.setModule("CLINICAL");
            p.setDescription(code);
            return permissionRepository.saveAndFlush(p);
        });
    }

    private Role ensureRole(String roleName, List<Permission> permissions) {
        return roleRepository.findAllWithPermissions().stream()
                .filter(r -> roleName.equalsIgnoreCase(r.getRoleName()))
                .findFirst()
                .map(r -> {
                    if (!permissions.isEmpty()) {
                        Set<Permission> merged = new HashSet<>(r.getPermissions() != null ? r.getPermissions() : Set.of());
                        merged.addAll(permissions);
                        r.setPermissions(merged);
                        return roleRepository.saveAndFlush(r);
                    }
                    return r;
                })
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName(roleName);
                    r.setDescription(roleName);
                    r.setIsActive(true);
                    r.setPermissions(new HashSet<>(permissions));
                    return roleRepository.saveAndFlush(r);
                });
    }

    private User createUser(String username, UserType type, Branch branch, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("secret");
        u.setEmail(username + "@clinic.local");
        u.setPhoneNumber("09" + UUID.randomUUID().toString().replaceAll("[^0-9]", "7").substring(0, 8));
        u.setUserType(type);
        u.setBranch(branch);
        u.setStatus("ACTIVE");
        u.setIsActive(true);
        u.setRoles(new HashSet<>(List.of(role)));
        return userRepository.saveAndFlush(u);
    }

    private StaffProfile createStaff(User user, String staffCode, String fullName, Branch branch, Role role) {
        StaffProfile s = new StaffProfile();
        s.setUser(user);
        s.setStaffCode(staffCode);
        s.setFullName(fullName);
        s.setBaseSalary(new BigDecimal("20000000"));
        s.setStatus(StaffStatus.ACTIVE);
        s = staffProfileRepository.saveAndFlush(s);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(s);
        sba.setBranch(branch);
        sba.setRole(role);
        sba.setStartDate(LocalDate.now(ZONE).minusDays(1));
        sba.setIsPrimary(true);
        sba.setStatus(AssignmentStatus.ACTIVE);
        branchAssignmentRepository.saveAndFlush(sba);
        return s;
    }

    private PatientProfile createPatient(String name, String phone) {
        Role patRole = ensureRole("BENH_NHAN", List.of());
        User pu = createUser("pat_" + UUID.randomUUID().toString().substring(0, 6), UserType.PATIENT, branch, patRole);
        PatientProfile p = new PatientProfile();
        p.setUser(pu);
        p.setPatientCode("BN-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setFullName(name);
        p.setPhoneNumber(pu.getPhoneNumber());
        p.setAddress("123 Cầu Giấy, Hà Nội");
        p.setGender("Nam");
        p.setDob(LocalDate.of(1992, 5, 15));
        p.setPreferredBranch(branch);
        p.setStatus("ACTIVE");
        p.setDepositBalance(BigDecimal.ZERO);
        return patientProfileRepository.saveAndFlush(p);
    }

    private Appointment createConfirmedOnTimeAppointment(PatientProfile patient, StaffProfile doctor, String symptoms) {
        Appointment a = new Appointment();
        a.setAppointmentCode("LH-" + UUID.randomUUID().toString().substring(0, 8));
        a.setPatientProfile(patient);
        a.setBranch(branch);
        a.setDoctor(doctor);
        a.setAppointmentDate(LocalDate.now(ZONE));
        a.setPreferredSession(PreferredSession.ANY);
        a.setExpectedTime(LocalTime.now(ZONE).plusMinutes(10));
        a.setSource(AppointmentSource.ONLINE);
        a.setStatus(AppointmentStatus.CONFIRMED);
        a.setInitialSymptoms(symptoms);
        return appointmentRepository.saveAndFlush(a);
    }
}
