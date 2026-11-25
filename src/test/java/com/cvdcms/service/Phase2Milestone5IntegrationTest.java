package com.cvdcms.service;

import com.cvdcms.DTO.ReExaminationRequestDTO;
import com.cvdcms.DTO.availability.ResourceBookingRequest;
import com.cvdcms.DTO.availability.ResourceBookingResult;
import com.cvdcms.DTO.availability.ResourceRescheduleRequest;
import com.cvdcms.controller.PublicBookingRestController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.service.PortalAppointmentService;
import com.cvdcms.service.QueueWorkflowService;
import com.cvdcms.service.ReceptionConsultationService;
import com.cvdcms.service.ReceptionService;
import com.cvdcms.service.ReceptionAppointmentService;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.ReExaminationServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("v118test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase2Milestone5IntegrationTest {

    private static final LocalDate D1 = LocalDate.now().plusDays(2);
    private static final LocalDate D2 = LocalDate.now().plusDays(3);
    private static final LocalDate D3 = LocalDate.now().plusDays(4);
    private static final LocalDate D_IDEMP = LocalDate.now().plusDays(5);
    private static final LocalDate TODAY = LocalDate.now();

    static {
        prepareIsolatedVerifySchema();
    }

    @Autowired private ResourceBookingTransactionService bookingTxService;
    @Autowired private PublicBookingRestController publicBookingRestController;
    @Autowired private PortalAppointmentService portalAppointmentService;
    @Autowired private ReceptionAppointmentService receptionAppointmentService;
    @Autowired private ReceptionConsultationService receptionConsultationService;
    @Autowired private ReExaminationServiceImpl reExaminationService;
    @Autowired private ReceptionService receptionService;
    @Autowired private QueueWorkflowService queueWorkflowService;
    @Autowired private DoctorShiftAssignmentService doctorShiftAssignmentService;

    @Autowired private UserRepository userRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private AppointmentResourceReservationRepository reservationRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private DentalChairRepository dentalChairRepository;
    @Autowired private ClinicalQueueTicketRepository queueTicketRepository;
    @Autowired private VisitRepository visitRepository;

    private static void prepareIsolatedVerifySchema() {
        try (Connection conn = IsolatedVerifySchemaHelper.openVerifiedConnection();
             Statement stmt = conn.createStatement()) {
            IsolatedVerifySchemaHelper.resetAllTablesInVerifySchema(stmt);
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "sql/v118_test_baseline_ddl.sql", ";");
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V117__resource_scheduling_master_data.sql", "$$");
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V118__appointment_reservations_and_snapshots.sql", "$$");
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V119__doctor_shift_assignments_and_replacements.sql", "$$");

            // Branch 1: resource_engine_enabled = 1 (test engine); Branch 2: resource_engine_enabled = 0 (legacy)
            stmt.execute("INSERT INTO branches (branch_id, branch_code, branch_name, address, opening_time, closing_time, working_days, is_active, resource_engine_enabled, created_at, updated_at) VALUES "
                    + "(1, 'CS1', 'Chi nhanh M5 Engine', '100 Duong M5', '08:00:00', '23:30:00', '2,3,4,5,6,7,CN', b'1', b'1', NOW(), NOW()),"
                    + "(2, 'CS2', 'Chi nhanh M5 Legacy', '200 Duong M5', '08:00:00', '23:30:00', '2,3,4,5,6,7,CN', b'1', b'0', NOW(), NOW())");

            stmt.execute("INSERT INTO roles (role_id, role_name, display_name, is_active, is_system) VALUES "
                    + "(1, 'ADMIN', 'Admin', b'1', b'1'), (2, 'DOCTOR', 'Doctor', b'1', b'1'), (3, 'RECEPTIONIST', 'Reception', b'1', b'1'), (4, 'PATIENT', 'Patient', b'1', b'1')");

            stmt.execute("INSERT INTO users (user_id, username, password, email, phone_number, user_type, branch_id, is_active, status, custom_permissions_enabled, created_date, updated_at) VALUES "
                    + "(10, 'admin_m5', '$2a$10$dummyhash', 'admin.m5@cvdcms.local', '0900005010', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(11, 'doc1_m5',  '$2a$10$dummyhash', 'doc1.m5@cvdcms.local',  '0900005011', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(12, 'doc2_m5',  '$2a$10$dummyhash', 'doc2.m5@cvdcms.local',  '0900005012', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(13, 'rec_m5',   '$2a$10$dummyhash', 'rec.m5@cvdcms.local',   '0900005013', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(14, 'pat1_m5',  '$2a$10$dummyhash', 'pat1.m5@cvdcms.local',  '0900005014', 'PATIENT', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(15, 'pat2_m5',  '$2a$10$dummyhash', 'pat2.m5@cvdcms.local',  '0900005015', 'PATIENT', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(16, 'doc3_m5',  '$2a$10$dummyhash', 'doc3.m5@cvdcms.local',  '0900005016', 'STAFF', 2, b'1', 'ACTIVE', b'0', NOW(), NOW())");

            stmt.execute("INSERT INTO user_roles (user_id, role_id) VALUES (10,1),(11,2),(12,2),(13,3),(14,4),(15,4),(16,2)");

            stmt.execute("INSERT INTO staff_profiles (staff_id, user_id, staff_code, full_name, specialty, status, contract_type, base_salary, allowance_amount, insurance_salary, dependent_count, has_personal_deduction, has_tax_commitment, is_tax_resident, insurance_participation_status, created_at, updated_at) VALUES "
                    + "(100, 10, 'ADM10', 'Admin M5',  'Quan tri',  'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(101, 11, 'BS101', 'BS M5 Mot', 'Tong quat', 'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(102, 12, 'BS102', 'BS M5 Hai', 'Tong quat', 'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(103, 13, 'LT103', 'Le Tan M5', 'Le tan',    'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(104, 16, 'BS104', 'BS M5 Legacy', 'Tong quat', 'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW())");

            stmt.execute("INSERT INTO staff_branch_assignments (branch_id, staff_id, role_id, is_primary, start_date, status, created_at) VALUES "
                    + "(1, 101, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(2, 104, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(1, 102, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(1, 100, 1, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(2, 100, 1, b'0', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(1, 103, 3, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW())");

            stmt.execute("INSERT INTO patient_profiles (profile_id, user_id, patient_code, full_name, gender, address, phone_number, deposit_balance, is_default, medical_history_completed, status, preferred_branch_id, created_at, updated_at) VALUES "
                    + "(1001, 14, 'BN1001', 'Benh Nhan Mot', 'MALE', 'Ha Noi', '0901111001', 0.00, b'1', b'0', 'ACTIVE', 1, NOW(), NOW()),"
                    + "(1002, 15, 'BN1002', 'Benh Nhan Hai', 'FEMALE', 'Ha Noi', '0901111002', 0.00, b'1', b'0', 'ACTIVE', 1, NOW(), NOW()),"
                    + "(1003, 14, 'BN1003', 'Benh Nhan Ba', 'MALE', 'Ha Noi', '0901111003', 0.00, b'0', b'0', 'ACTIVE', 1, NOW(), NOW())");

            stmt.execute("INSERT INTO service_categories (category_id, category_code, category_name, category_type, display_order, is_active) VALUES "
                    + "(1, 'CAT-TQ', 'Kham tong quat', 'PROCEDURE', 1, b'1')");

            stmt.execute("INSERT INTO services (service_id, category_id, service_code, service_name, service_type, unit, base_price, surcharge_amount, estimated_duration_minutes, "
                    + "preparation_duration_minutes, procedure_duration_minutes, turnover_duration_minutes, "
                    + "is_room_exclusive, requires_chair, doctor_required_in_prep, doctor_required_in_turnover, "
                    + "is_default_consultation, is_bookable_online, is_paraclinical, requires_tooth, status, created_at) VALUES "
                    + "(1, 1, 'DV-KHAM-TQ', 'Kham tong quat', 'CONSULTATION', 'Lan', 100000, 0, 30, 5, 20, 5, b'0', b'1', b'0', b'0', b'1', b'1', b'0', b'0', 'ACTIVE', NOW()),"
                    + "(2, 1, 'DV-CBCT', 'Chup CBCT', 'XRAY', 'Lan', 500000, 0, 30, 5, 20, 5, b'1', b'0', b'0', b'0', b'0', b'1', b'1', b'0', 'ACTIVE', NOW())");

            stmt.execute("INSERT INTO rooms (room_id, branch_id, room_code, room_name, room_type, status, floor_label, zone_label, floor_order) VALUES "
                    + "(1, 1, 'P101', 'Phong 101 (1 ghe)', 'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu A', 1),"
                    + "(2, 1, 'P104', 'Phong XQ', 'XRAY', 'ACTIVE', 'Tang 1', 'Khu B', 1),"
                    + "(3, 2, 'P201', 'Phong Legacy', 'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu A', 1)");

            stmt.execute("INSERT INTO dental_chairs (chair_id, branch_id, room_id, chair_code, chair_name, status) VALUES "
                    + "(11, 1, 1, 'G101-A', 'Ghe 101 A', 'AVAILABLE'),"
                    + "(21, 2, 3, 'G201-A', 'Ghe 201 A', 'AVAILABLE')");

            stmt.execute("INSERT INTO service_resource_requirements (requirement_id, service_id, resource_kind, room_type, equipment_type, usage_phase, is_required) VALUES "
                    + "(1, 1, 'ROOM_TYPE', 'TREATMENT', NULL, 'ALL', b'1'),"
                    + "(2, 2, 'ROOM_TYPE', 'XRAY', NULL, 'ALL', b'1'),"
                    + "(3, 2, 'EQUIPMENT_TYPE', NULL, 'CONE_BEAM', 'PROCEDURE', b'1')");

            stmt.execute("INSERT INTO work_shifts (shift_id, branch_id, shift_code, shift_name, start_time, end_time, is_active) VALUES "
                    + "(1, 1, 'SANG', 'Ca Sang', '08:00:00', '12:00:00', b'1'),"
                    + "(2, 1, 'CA-ALL', 'Ca Ca Ngay', '07:00:00', '23:30:00', b'1'),"
                    + "(3, 2, 'SANG2', 'Ca Sang CN2', '08:00:00', '12:00:00', b'1')");

            for (LocalDate d : List.of(TODAY, D1, D2, D3, D_IDEMP)) {
                long s1 = d.toEpochDay() * 10 + 1;
                long s2 = d.toEpochDay() * 10 + 2;
                long s3 = d.toEpochDay() * 10 + 3;
                long sh = d.equals(TODAY) ? 2L : 1L;
                stmt.execute("INSERT INTO staff_schedules (schedule_id, staff_id, branch_id, shift_id, work_date, status, created_at) VALUES "
                        + "(" + s1 + ", 101, 1, " + sh + ", '" + d + "', 'SCHEDULED', NOW()),"
                        + "(" + s2 + ", 102, 1, " + sh + ", '" + d + "', 'SCHEDULED', NOW()),"
                        + "(" + s3 + ", 104, 2, 3, '" + d + "', 'SCHEDULED', NOW())");
            }

            stmt.execute("INSERT INTO doctor_room_assignments (assignment_id, branch_id, staff_id, room_id, is_primary, effective_from, status) VALUES "
                    + "(1, 1, 101, 1, b'1', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(2, 1, 102, 1, b'0', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(3, 2, 104, 3, b'1', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE')");

            stmt.execute("INSERT INTO doctor_service_permissions (permission_id, staff_id, service_id, branch_id, effective_from, status) VALUES "
                    + "(1, 101, 1, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(2, 102, 1, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(3, 104, 1, 2, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE')");

            stmt.execute("INSERT INTO booking_consultation_requests (request_id, request_code, request_key, branch_id, service_id, guest_name, guest_phone, "
                    + "preferred_date, preferred_session, preferred_time, status, version, created_at, updated_at) VALUES "
                    + "(501, 'TV501', 'consult-key-501', 1, 1, 'Khach Tu Van 501', '0909999501', '" + D1 + "', 'MORNING', '10:00:00', 'CONTACTED', 0, NOW(), NOW())");
        } catch (Exception e) {
            throw new IllegalStateException("Không thể khởi tạo schema test M5", e);
        }
    }

    private static boolean shiftsSeeded = false;

    @BeforeEach
    void ensureShiftsForD1D2D3() {
        if (shiftsSeeded) {
            return;
        }
        for (LocalDate d : List.of(TODAY, D1, D2, D3, D_IDEMP)) {
            long sh = d.equals(TODAY) ? 2L : 1L;
            doctorShiftAssignmentService.assignDoctorsToRoom(
                    1L, d, sh, 1L, List.of(101L), DoctorShiftAssignmentRole.PRIMARY, "M5 test", 10L);
        }
        shiftsSeeded = true;
    }

    private MockHttpSession sessionFor(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", userRepository.findById(userId).orElseThrow());
        return session;
    }

    @Test
    @Order(1)
    @DisplayName("1. Idempotency: cùng key+payload replay; khác patient/branch/payload bị từ chối 409; thiếu tài nguyên rollback")
    void idempotencySecurityAndMissingResourceRollback() {
        String key = "M5-IDEMP-" + UUID.randomUUID();
        ResourceBookingRequest req = ResourceBookingRequest.newBooking(
                1L, 1001L, 1L, D_IDEMP, LocalTime.of(8, 0), 101L, false, key);
        ResourceBookingResult first = bookingTxService.bookWithRetry(req, AppointmentStatus.PENDING);
        assertFalse(first.idempotentReplay());

        ResourceBookingResult replay = bookingTxService.bookWithRetry(req, AppointmentStatus.PENDING);
        assertTrue(replay.idempotentReplay());
        assertEquals(first.appointmentId(), replay.appointmentId());

        // Khác patient -> 409 CONFLICT
        ResourceBookingRequest diffPatient = ResourceBookingRequest.newBooking(
                1L, 1002L, 1L, D_IDEMP, LocalTime.of(8, 0), 101L, false, key);
        ResponseStatusException exPat = assertThrows(ResponseStatusException.class,
                () -> bookingTxService.bookWithRetry(diffPatient, AppointmentStatus.PENDING));
        assertEquals(HttpStatus.CONFLICT, exPat.getStatusCode());

        // Khác branch -> 409 CONFLICT
        ResourceBookingRequest diffBranch = ResourceBookingRequest.newBooking(
                2L, 1001L, 1L, D_IDEMP, LocalTime.of(8, 0), 101L, false, key);
        ResponseStatusException exBr = assertThrows(ResponseStatusException.class,
                () -> bookingTxService.bookWithRetry(diffBranch, AppointmentStatus.PENDING));
        assertEquals(HttpStatus.CONFLICT, exBr.getStatusCode());

        // Khác payload (khác giờ) -> 409 CONFLICT
        ResourceBookingRequest diffTime = ResourceBookingRequest.newBooking(
                1L, 1001L, 1L, D_IDEMP, LocalTime.of(8, 30), 101L, false, key);
        ResponseStatusException exPayload = assertThrows(ResponseStatusException.class,
                () -> bookingTxService.bookWithRetry(diffTime, AppointmentStatus.PENDING));
        assertEquals(HttpStatus.CONFLICT, exPayload.getStatusCode());

        // Thiếu tài nguyên (service 2 CBCT không có máy CONE_BEAM) -> rollback toàn bộ
        long apptBefore = appointmentRepository.count();
        long resBefore = reservationRepository.count();
        ResourceBookingRequest missingEquip = ResourceBookingRequest.newBooking(
                1L, 1002L, 2L, D_IDEMP, LocalTime.of(9, 0), 101L, false, "M5-ROLLBACK-" + UUID.randomUUID());
        ResponseStatusException exMissing = assertThrows(ResponseStatusException.class,
                () -> bookingTxService.bookWithRetry(missingEquip, AppointmentStatus.CONFIRMED));
        assertEquals(HttpStatus.CONFLICT, exMissing.getStatusCode());
        assertEquals(apptBefore, appointmentRepository.count());
        assertEquals(resBefore, reservationRepository.count());
    }

    @Test
    @Order(2)
    @DisplayName("2. Tất cả Entry Point thật (Public, Portal, Reception, Convert, ReExam) đi qua Resource Engine & giữ snapshot khi reschedule")
    void allEntryPointsUseEngineAndReschedulePreservesSnapshot() {
        // A. PublicBookingRestController (logged-in patient)
        MockHttpSession patSession = sessionFor(14L);
        PublicBookingRestController.QuickBookingForm formNoSvc = new PublicBookingRestController.QuickBookingForm();
        formNoSvc.setBranchId(1L);
        formNoSvc.setProfileId(1001L);
        formNoSvc.setAppointmentDate(D1.toString());
        formNoSvc.setPreferredSession("MORNING");
        formNoSvc.setSlotTime("08:30");
        ResponseEntity<Map<String, Object>> badResp = publicBookingRestController.submitBooking(patSession, new MockHttpServletRequest(), formNoSvc);
        assertEquals(HttpStatus.BAD_REQUEST, badResp.getStatusCode());

        PublicBookingRestController.QuickBookingForm pubForm = new PublicBookingRestController.QuickBookingForm();
        pubForm.setBranchId(1L);
        pubForm.setProfileId(1001L);
        pubForm.setServiceId(1L);
        pubForm.setDoctorId(101L);
        pubForm.setAppointmentDate(D1.toString());
        pubForm.setPreferredSession("MORNING");
        pubForm.setSlotTime("08:30");
        pubForm.setRequestKey(UUID.randomUUID().toString());
        ResponseEntity<Map<String, Object>> pubOk = publicBookingRestController.submitBooking(patSession, new MockHttpServletRequest(), pubForm);
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        assertEquals(HttpStatus.OK, pubOk.getStatusCode());
        Long pubApptId = ((Number) pubOk.getBody().get("appointmentId")).longValue();
        assertFalse(reservationRepository.findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(
                pubApptId, ReservationStatus.RESERVED).isEmpty());

        // B. PortalAppointmentService book + reschedule (giữ snapshot dù Service bị sửa thời lượng) + failed reschedule rollback
        var portalView = portalAppointmentService.book(patSession, new PortalAppointmentService.BookingRequest(
                1003L, 1L, D1, PreferredSession.MORNING, 101L, 1L, "Kham rang", UUID.randomUUID().toString(), LocalTime.of(9, 0)));
        List<AppointmentResourceReservation> portalResBefore = reservationRepository
                .findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(portalView.id(), ReservationStatus.RESERVED);
        assertEquals(3, portalResBefore.size()); // DOCTOR + ROOM + CHAIR

        // Sửa duration của Service 1 trong DB thành 60p nhưng reschedule Portal phải giữ nguyên snapshot cũ 30p (5+20+5)
        com.cvdcms.entity.Service svc = serviceRepository.findById(1L).orElseThrow();
        int origProc = svc.getProcedureDurationMinutes();
        svc.setProcedureDurationMinutes(50);
        serviceRepository.saveAndFlush(svc);
        try {
            // Reschedule thất bại vì trùng 08:30 của pubApptId -> phải giữ nguyên 09:00 và reservation cũ!
            assertThrows(ResponseStatusException.class, () -> portalAppointmentService.reschedule(
                    patSession, portalView.id(),
                    new PortalAppointmentService.RescheduleRequest(D1, PreferredSession.MORNING, 101L, "Doi gio trung", LocalTime.of(8, 30))));
            Appointment afterFail = appointmentRepository.findById(portalView.id()).orElseThrow();
            assertEquals(LocalTime.of(9, 0), afterFail.getExpectedTime());
            assertEquals(portalResBefore.stream().map(AppointmentResourceReservation::getReservationId).toList(),
                    reservationRepository.findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(
                            portalView.id(), ReservationStatus.RESERVED).stream().map(AppointmentResourceReservation::getReservationId).toList());

            // Reschedule thành công sang 09:30 -> giữ snapshot 30p (t3 = 10:00)
            var reschedView = portalAppointmentService.reschedule(
                    patSession, portalView.id(),
                    new PortalAppointmentService.RescheduleRequest(D1, PreferredSession.MORNING, 101L, "Doi gio hop le", LocalTime.of(9, 30)));
            Appointment afterOk = appointmentRepository.findById(reschedView.id()).orElseThrow();
            assertEquals(LocalTime.of(9, 30), afterOk.getExpectedTime());
            assertEquals(20, afterOk.getProcedureDurationMinutes());
            assertEquals(LocalTime.of(10, 0), afterOk.getExpectedEndTime());
        } finally {
            svc.setProcedureDurationMinutes(origProc);
            serviceRepository.saveAndFlush(svc);
        }

        // C. ReceptionConsultationService.convertToAppointment -> qua ReceptionAppointmentService.create -> Engine
        MockHttpSession adminSession = sessionFor(10L);
        var convResult = receptionConsultationService.convertToAppointment(
                adminSession, 501L,
                new ReceptionConsultationService.ConvertRequest(
                        "conv-" + UUID.randomUUID(),
                        1L, 1002L, false,
                        null, null, null, null, null, null, null, null, null, false,
                        D1, PreferredSession.MORNING, LocalTime.of(10, 0), false,
                        1L, false, 101L, "Tu van chuyen lich", false, null),
                new MockHttpServletRequest());
        assertFalse(reservationRepository.findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(
                convResult.appointment().id(), ReservationStatus.RESERVED).isEmpty());

        // D. ReExaminationServiceImpl -> bắt buộc service, tạo reservation, reschedule giữ snapshot
        ReExaminationRequestDTO reDto = new ReExaminationRequestDTO();
        reDto.setPatientProfileId(1002L);
        reDto.setBranchId(1L);
        reDto.setDoctorId(101L);
        reDto.setServiceId(1L);
        reDto.setAppointmentDate(D2);
        reDto.setPreferredSession(PreferredSession.MORNING);
        reDto.setExpectedTime("08:00");
        var reCreated = reExaminationService.createReExamination(reDto);
        assertFalse(reservationRepository.findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(
                reCreated.getAppointmentId(), ReservationStatus.RESERVED).isEmpty());

        // E. Branch 2 (resource_engine_enabled = false) vẫn chạy legacy flow
        var legacyAppt = receptionAppointmentService.create(
                adminSession,
                new ReceptionAppointmentService.CreateRequest(
                        2L, 1001L, D2, PreferredSession.MORNING, 104L, 1L, "Legacy branch", LocalTime.of(8, 0)));
        assertTrue(reservationRepository.findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(
                legacyAppt.id(), ReservationStatus.RESERVED).isEmpty());
    }

    @Test
    @Order(3)
    @DisplayName("3. Hai request đồng thời từ hai Entry Point khác nhau tranh cùng ghế tại D3 -> 1 thắng, 1 bị từ chối")
    void concurrentRequestsAcrossTwoEntryPointsContendSameChair() throws Exception {
        MockHttpSession patSession = sessionFor(14L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        Callable<Void> portalTask = () -> {
            startGate.await(5, TimeUnit.SECONDS);
            try {
                portalAppointmentService.book(patSession, new PortalAppointmentService.BookingRequest(
                        1001L, 1L, D3, PreferredSession.MORNING, 101L, 1L, "Portal race", UUID.randomUUID().toString(), LocalTime.of(8, 0)));
                success.incrementAndGet();
            } catch (Exception ex) {
                conflict.incrementAndGet();
            }
            return null;
        };

        Callable<Void> reExamTask = () -> {
            startGate.await(5, TimeUnit.SECONDS);
            try {
                ReExaminationRequestDTO reDto = new ReExaminationRequestDTO();
                reDto.setPatientProfileId(1002L);
                reDto.setBranchId(1L);
                reDto.setDoctorId(101L);
                reDto.setServiceId(1L);
                reDto.setAppointmentDate(D3);
                reDto.setPreferredSession(PreferredSession.MORNING);
                reDto.setExpectedTime("08:00");
                reExaminationService.createReExamination(reDto);
                success.incrementAndGet();
            } catch (Exception ex) {
                conflict.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = pool.submit(portalTask);
        Future<Void> f2 = pool.submit(reExamTask);
        startGate.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, success.get(), "Chỉ đúng 1 entry point được thắng khi tranh cùng ghế");
        assertEquals(1, conflict.get(), "Entry point còn lại phải bị từ chối");
    }

    @Test
    @Order(4)
    @DisplayName("4. Check-in, Cancel với Visit active bị chặn, Dispatch START -> DONE (TURNOVER) -> completeChairTurnover")
    void checkInDoneTurnoverAndActiveVisitCancelProtection() {
        MockHttpSession adminSession = sessionFor(10L);
        LocalTime slot = LocalTime.of(10, 0);
        var appt = bookingTxService.bookWithRetry(
                ResourceBookingRequest.newBooking(1L, 1002L, 1L, TODAY, slot, 101L, false, "M5-LC-" + UUID.randomUUID()),
                AppointmentStatus.CONFIRMED);

        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        receptionService.setClock(java.time.Clock.fixed(TODAY.atTime(slot).atZone(zone).toInstant(), zone));
        try {
            // Check-in đồng bộ room/chair vào Visit và không giải phóng reservation
            var visitResult = receptionService.checkInAppointment(
                    adminSession, appt.appointmentId(), "Check-in lifecycle", UUID.randomUUID().toString());
            Visit visit = visitRepository.findById(visitResult.id()).orElseThrow();
            assertEquals(1L, visit.getRoomId());
            assertEquals(11L, visit.getChairId());
            assertEquals(3, reservationRepository.findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(
                    appt.appointmentId(), ReservationStatus.RESERVED).size());

            // Active Visit tồn tại -> releaseReservationsOnCancelOrNoShow phải từ chối 409
            ResponseStatusException cancelBlocked = assertThrows(ResponseStatusException.class,
                    () -> bookingTxService.releaseReservationsOnCancelOrNoShow(
                            appt.appointmentId(), ReservationStatus.CANCELLED, "CANCEL_TEST", LocalDateTime.now()));
            assertEquals(HttpStatus.CONFLICT, cancelBlocked.getStatusCode());

            // Dispatch: ticket đã được auto-called khi check-in -> START -> DONE
            QueueTicket ticket = queueTicketRepository.findByVisitVisitId(visit.getVisitId()).stream().findFirst().orElseThrow();
            queueWorkflowService.transition(adminSession, ticket.getTicketId(), QueueWorkflowService.Action.START, 11L, "Start");
            assertEquals(ChairStatus.IN_USE, dentalChairRepository.findById(11L).orElseThrow().getStatus());

            queueWorkflowService.transition(adminSession, ticket.getTicketId(), QueueWorkflowService.Action.DONE, null, "Done");
            // Engine bật: ghế chuyển TURNOVER (KHÔNG AVAILABLE), DOCTOR release, ROOM & CHAIR vẫn RESERVED!
            assertEquals(ChairStatus.TURNOVER, dentalChairRepository.findById(11L).orElseThrow().getStatus());
            List<AppointmentResourceReservation> afterDone = reservationRepository
                    .findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(appt.appointmentId(), ReservationStatus.RESERVED);
            assertEquals(2, afterDone.size());
            assertTrue(afterDone.stream().noneMatch(r -> r.getResourceType() == ReservationResourceType.DOCTOR));
            assertTrue(afterDone.stream().anyMatch(r -> r.getResourceType() == ReservationResourceType.ROOM));
            assertTrue(afterDone.stream().anyMatch(r -> r.getResourceType() == ReservationResourceType.CHAIR));

            // Hoàn tất vệ sinh ghế -> ghế AVAILABLE + release ROOM & CHAIR
            queueWorkflowService.completeChairTurnover(adminSession, 11L, appt.appointmentId());
            assertEquals(ChairStatus.AVAILABLE, dentalChairRepository.findById(11L).orElseThrow().getStatus());
            assertTrue(reservationRepository.findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(
                    appt.appointmentId(), ReservationStatus.RESERVED).isEmpty());
        } finally {
            receptionService.setClock(java.time.Clock.system(zone));
        }
    }
}
