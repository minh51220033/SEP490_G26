package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.AppointmentResourceReservation;
import com.cvdcms.entity.DoctorShiftAssignment;
import com.cvdcms.entity.DoctorShiftReplacementLog;
import com.cvdcms.entity.LeaveRequest;
import com.cvdcms.entity.StaffSchedule;
import com.cvdcms.entity.SystemAuditLog;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AppointmentResourceStatus;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.DoctorShiftAssignmentRole;
import com.cvdcms.entity.enums.DoctorShiftAssignmentStatus;
import com.cvdcms.entity.enums.LeaveStatus;
import com.cvdcms.entity.enums.ReservationResourceType;
import com.cvdcms.entity.enums.ReservationStatus;
import com.cvdcms.entity.enums.ScheduleStatus;
import com.cvdcms.repository.AppointmentRepository;
import com.cvdcms.repository.AppointmentResourceReservationRepository;
import com.cvdcms.repository.DoctorShiftAssignmentRepository;
import com.cvdcms.repository.DoctorShiftReplacementLogRepository;
import com.cvdcms.repository.LeaveRequestRepository;
import com.cvdcms.repository.StaffScheduleRepository;
import com.cvdcms.repository.SystemAuditLogRepository;
import com.cvdcms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("v118test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase2Milestone2DoctorShiftIntegrationTest {

    private static final LocalDate WORK_WEEK_MONDAY = StaffScheduleService.mondayOf(LocalDate.now().plusDays(2));
    private static final LocalDate WORK_DATE_1 = WORK_WEEK_MONDAY.plusDays(1);
    private static final LocalDate WORK_DATE_2 = WORK_WEEK_MONDAY.plusDays(2);
    private static final LocalDate NEXT_WEEK_MONDAY = WORK_WEEK_MONDAY.plusWeeks(1);

    static {
        prepareIsolatedVerifySchemaBeforeSpringBoot();
    }

    @Autowired private WebApplicationContext wac;
    @Autowired private DataSource dataSource;
    @Autowired private DoctorShiftAssignmentService doctorShiftAssignmentService;
    @Autowired private DoctorShiftAssignmentRepository shiftAssignmentRepository;
    @Autowired private DoctorShiftReplacementLogRepository replacementLogRepository;
    @Autowired private StaffScheduleService staffScheduleService;
    @Autowired private StaffScheduleRepository staffScheduleRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private LeaveRequestService leaveRequestService;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private AppointmentResourceReservationRepository reservationRepository;
    @Autowired private SystemAuditLogRepository systemAuditLogRepository;
    @Autowired private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private static void prepareIsolatedVerifySchemaBeforeSpringBoot() {
        try (Connection conn = IsolatedVerifySchemaHelper.openVerifiedConnection();
             Statement stmt = conn.createStatement()) {
            IsolatedVerifySchemaHelper.resetAllTablesInVerifySchema(stmt);

            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "sql/v118_test_baseline_ddl.sql", ";");
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V117__resource_scheduling_master_data.sql", "$$");

            // Seed synthetic fixtures purely for M2 verification
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            stmt.execute("INSERT INTO branches (branch_id, branch_code, branch_name, address, opening_time, closing_time, working_days, is_active, created_at, updated_at) VALUES "
                    + "(1, 'CS1', 'Chi nhanh Test M2-1', '100 Duong Test 1', '08:00:00', '21:00:00', '2,3,4,5,6,7,CN', b'1', NOW(), NOW()),"
                    + "(2, 'CS2', 'Chi nhanh Test M2-2', '200 Duong Test 2', '08:00:00', '21:00:00', '2,3,4,5,6,7,CN', b'1', NOW(), NOW())");

            stmt.execute("INSERT INTO rooms (room_id, branch_id, room_code, room_name, room_type, status, floor_label, zone_label, floor_order) VALUES "
                    + "(1, 1, 'P101', 'Phong Dieu Tri 101', 'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu A', 1),"
                    + "(2, 1, 'P102', 'Phong Dieu Tri 102', 'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu B', 1)");

            stmt.execute("INSERT INTO dental_chairs (chair_id, branch_id, room_id, chair_code, chair_name, status) VALUES "
                    + "(1, 1, 1, 'G01', 'Ghe 101', 'AVAILABLE'),"
                    + "(2, 1, 2, 'G02', 'Ghe 102', 'AVAILABLE')");

            stmt.execute("INSERT INTO work_shifts (shift_id, branch_id, shift_code, shift_name, start_time, end_time, is_active) VALUES "
                    + "(1, 1, 'SANG',  'Ca Sang',  '08:00:00', '12:00:00', b'1'),"
                    + "(2, 1, 'CHIEU', 'Ca Chieu', '12:00:00', '17:00:00', b'1'),"
                    + "(3, 1, 'TOI',   'Ca Toi',   '18:00:00', '21:00:00', b'1'),"
                    + "(4, 2, 'SANG2', 'Ca Sang CN2 (09-13)', '09:00:00', '13:00:00', b'1')");

            stmt.execute("INSERT INTO roles (role_id, role_name, display_name, is_active, is_system) VALUES "
                    + "(1, 'ADMIN',  'Quan tri vien', b'1', b'1'),"
                    + "(2, 'BAC_SI', 'Bac si',        b'1', b'1'),"
                    + "(3, 'LE_TAN', 'Le tan',        b'1', b'1')");

            stmt.execute("INSERT INTO users (user_id, username, password, email, phone_number, user_type, branch_id, is_active, status, custom_permissions_enabled, created_date, updated_at) VALUES "
                    + "(1, 'admin_m2', '$2a$10$dummyhash', 'admin_m2@test.local', '0900001001', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(2, 'bs01_m2',  '$2a$10$dummyhash', 'bs01_m2@test.local',  '0900001002', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(3, 'bs02_m2',  '$2a$10$dummyhash', 'bs02_m2@test.local',  '0900001003', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(4, 'bs03_m2',  '$2a$10$dummyhash', 'bs03_m2@test.local',  '0900001004', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(5, 'bs04_m2',  '$2a$10$dummyhash', 'bs04_m2@test.local',  '0900001005', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(6, 'lt01_m2',  '$2a$10$dummyhash', 'lt01_m2@test.local',  '0900001006', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW())");

            stmt.execute("INSERT INTO user_roles (user_id, role_id) VALUES (1,1),(2,2),(3,2),(4,2),(5,2),(6,3)");

            stmt.execute("INSERT INTO staff_profiles (staff_id, user_id, staff_code, full_name, specialty, status, contract_type, base_salary, allowance_amount, insurance_salary, dependent_count, has_personal_deduction, has_tax_commitment, is_tax_resident, insurance_participation_status, created_at, updated_at) VALUES "
                    + "(1, 2, 'BS01', 'BS Nguyen Van A', 'Nha chu',   'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(2, 3, 'BS02', 'BS Tran Thi B',   'Tong quat', 'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(3, 4, 'BS03', 'BS Le Van C',     'Phuc hinh', 'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(4, 5, 'BS04', 'BS Pham Thi D',   'Tieu phau', 'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(5, 6, 'LT01', 'Le Tan E',        NULL,        'ACTIVE', 'FULL_TIME', 8000000,  0, 8000000,  0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW())");

            stmt.execute("INSERT INTO staff_branch_assignments (branch_id, staff_id, role_id, is_primary, start_date, status, created_at) VALUES "
                    + "(1, 1, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(2, 1, 2, b'0', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(1, 2, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(1, 3, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(1, 4, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(1, 5, 3, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW())");

            // Room permissions: BS01, BS02, BS03 allowed at P101 & P102; BS04 intentionally has NO room permission at P101
            stmt.execute("INSERT INTO doctor_room_assignments (branch_id, staff_id, room_id, is_primary, effective_from, status) VALUES "
                    + "(1, 1, 1, b'1', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 2, 1, b'1', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 3, 1, b'0', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 1, 2, b'0', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 2, 2, b'0', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE')");

            stmt.execute("INSERT INTO patient_profiles (profile_id, patient_code, full_name, gender, address, phone_number, deposit_balance, is_default, medical_history_completed, status, preferred_branch_id, created_at, updated_at) "
                    + "VALUES (1, 'BN-M2-1', 'Benh nhan M2', 'MALE', 'Ha Noi', '0911000001', 0.00, b'1', b'0', 'ACTIVE', 1, NOW(), NOW())");

            stmt.execute("INSERT INTO service_categories (category_id, category_code, category_name, category_type, display_order, is_active) "
                    + "VALUES (1, 'CAT-M2', 'Dieu tri nha khoa', 'PROCEDURE', 1, b'1')");
            stmt.execute("INSERT INTO services (service_id, category_id, service_code, service_name, service_type, unit, base_price, surcharge_amount, estimated_duration_minutes, preparation_duration_minutes, procedure_duration_minutes, turnover_duration_minutes, requires_chair, is_room_exclusive, doctor_required_in_prep, doctor_required_in_turnover, is_default_consultation, is_bookable_online, is_paraclinical, requires_tooth, status, created_at) VALUES "
                    + "(1, 1, 'DV-KHAM', 'Kham tong quat', 'CONSULTATION', 'Lan', 100000, 0, 30, 5, 20, 5, b'1', b'0', b'0', b'0', b'1', b'1', b'0', b'0', 'ACTIVE', NOW()),"
                    + "(2, 1, 'DV-NHO',  'Nho rang khon',  'PROCEDURE',    'Rang', 1500000, 0, 45, 5, 35, 5, b'1', b'1', b'1', b'0', b'0', b'1', b'0', b'1', 'ACTIVE', NOW())");

            // BS04 can perform DV-KHAM (id=1), CANNOT perform DV-NHO (id=2); BS01 can perform both
            stmt.execute("INSERT INTO doctor_service_permissions (staff_id, service_id, branch_id, effective_from, status) VALUES "
                    + "(1, 1, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 2, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(4, 1, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE')");

            // Appointments for WORK_DATE_2 assigned to BS01 (staff 1) in morning shift (09:00 and 10:00)
            stmt.execute("INSERT INTO appointments (appointment_id, appointment_code, branch_id, profile_id, doctor_id, service_id, appointment_date, preferred_session, expected_time, duration_minutes, reschedule_count, source, status, created_at) VALUES "
                    + "(101, 'AP-M2-ELIG',   1, 1, 1, 1, '" + WORK_DATE_2 + "', 'MORNING', '09:00:00', 30, 0, 'ONLINE', 'CONFIRMED', NOW()),"
                    + "(102, 'AP-M2-INELIG', 1, 1, 1, 2, '" + WORK_DATE_2 + "', 'MORNING', '10:00:00', 45, 0, 'ONLINE', 'CONFIRMED', NOW())");

            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V118__appointment_reservations_and_snapshots.sql", "$$");
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V119__doctor_shift_assignments_and_replacements.sql", "$$");
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private MockHttpSession sessionForUser(Long userId, String roleName) {
        User user = userRepository.findById(userId).orElseThrow();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
        session.setAttribute("roleNames", List.of(roleName));
        return session;
    }

    @Test
    @Order(1)
    @DisplayName("1. Cô lập MySQL & xác minh V119 DDL + unique constraint uk_dsa_active_staff_shift")
    void verifyV119DdlAndSchemaIsolation() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '"
                            + IsolatedVerifySchemaHelper.TARGET_VERIFY_SCHEMA
                            + "' AND TABLE_NAME IN ('doctor_shift_assignments', 'doctor_shift_replacement_logs')")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "Phải có 2 bảng V119 trên cv_dcms_v118_verify");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = '"
                            + IsolatedVerifySchemaHelper.TARGET_VERIFY_SCHEMA
                            + "' AND TABLE_NAME = 'doctor_shift_assignments' AND INDEX_NAME = 'uk_dsa_active_staff_shift' AND NON_UNIQUE = 0")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "Phải tồn tại unique key uk_dsa_active_staff_shift");
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("2. Nhiều bác sĩ trong cùng 1 phòng/ca + xóa 1 bác sĩ vẫn giữ nguyên đồng nghiệp trong phòng")
    void multipleDoctorsInSameRoomAndRemovePreservesColleagues() {
        List<DoctorShiftAssignment> created = doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, WORK_DATE_1, 1L, 1L, List.of(1L, 2L, 3L),
                DoctorShiftAssignmentRole.CO_ATTENDING, "Ca sang P101 nhieu bac si", 1L);

        assertEquals(3, created.size());
        assertTrue(created.stream().allMatch(a -> a.getStatus() == DoctorShiftAssignmentStatus.ACTIVE));
        assertTrue(created.stream().allMatch(a -> a.getStaffSchedule() != null));
        assertTrue(created.stream().allMatch(a -> a.getActiveSlotKey() != null
                && a.getActiveSlotKey().endsWith("#" + WORK_DATE_1 + "#1")));

        List<DoctorShiftAssignment> activeInP101 = shiftAssignmentRepository
                .findByBranch_BranchIdAndWorkDateAndShift_ShiftIdAndRoom_RoomIdAndStatusOrderByShiftAssignmentIdAsc(
                        1L, WORK_DATE_1, 1L, 1L, DoctorShiftAssignmentStatus.ACTIVE);
        assertEquals(3, activeInP101.size(), "Phòng 101 ca Sáng phải có 3 bác sĩ ACTIVE cùng lúc");

        // Xóa BS02 khỏi phòng 101
        Long bs02AssignmentId = activeInP101.stream()
                .filter(a -> a.getStaff().getStaffId().equals(2L))
                .findFirst().orElseThrow().getShiftAssignmentId();
        doctorShiftAssignmentService.removeDoctorFromRoom(bs02AssignmentId, 1L);

        DoctorShiftAssignment removed = shiftAssignmentRepository.findById(bs02AssignmentId).orElseThrow();
        assertEquals(DoctorShiftAssignmentStatus.CANCELLED, removed.getStatus());
        assertNull(removed.getActiveSlotKey(), "CANCELLED phải giải phóng active_slot_key");

        List<DoctorShiftAssignment> remainingInP101 = shiftAssignmentRepository
                .findByBranch_BranchIdAndWorkDateAndShift_ShiftIdAndRoom_RoomIdAndStatusOrderByShiftAssignmentIdAsc(
                        1L, WORK_DATE_1, 1L, 1L, DoctorShiftAssignmentStatus.ACTIVE);
        assertEquals(2, remainingInP101.size(), "BS01 và BS03 vẫn phải giữ nguyên ACTIVE trong phòng 101");
    }

    @Test
    @Order(3)
    @DisplayName("3. Chống 1 BS vào 2 phòng cùng ca, chống chồng lấn thời gian giữa các chi nhánh [start, end), cho phép chạm biên [12:00)")
    void rejectSameShiftMultiRoomAndCrossBranchOverlapAndAllowBoundaryTouch() {
        // BS01 đã có ca Sáng (08:00-12:00) tại CN1 P101 trong WORK_DATE_1 (từ Order 2)
        // 3a. Không cho phân BS01 vào P102 cùng ca Sáng CN1
        IllegalArgumentException sameShiftEx = assertThrows(IllegalArgumentException.class, () ->
                doctorShiftAssignmentService.assignDoctorsToRoom(
                        1L, WORK_DATE_1, 1L, 2L, List.of(1L),
                        DoctorShiftAssignmentRole.CO_ATTENDING, "Thu xep trung phong", 1L));
        assertTrue(sameShiftEx.getMessage().contains("đã có ca") || sameShiftEx.getMessage().contains("trùng") || sameShiftEx.getMessage().contains("phòng"),
                "Thông báo lỗi phải chỉ rõ trùng lịch/phòng: " + sameShiftEx.getMessage());

        // 3b. Chồng lấn giữa 2 chi nhánh: CN2 shift 4 (09:00-13:00) giao cắt CN1 shift 1 (08:00-12:00)
        // (CN2 chưa tạo phòng -> thêm 1 phòng ở CN2 hoặc kiểm tra DB constraint không cần phòng ở bước overlap)
        assertDoesNotThrow(() -> {
            // Ca Chiều CN1 (12:00-17:00) chạm biên 12:00 với Ca Sáng (08:00-12:00) => hợp lệ theo [start, end)
            List<DoctorShiftAssignment> afternoon = doctorShiftAssignmentService.assignDoctorsToRoom(
                    1L, WORK_DATE_1, 2L, 2L, List.of(1L),
                    DoctorShiftAssignmentRole.PRIMARY, "Ca chieu cham bien 12:00", 1L);
            assertEquals(1, afternoon.size());
        }, "Khoảng [08:00, 12:00) và [12:00, 17:00) chạm biên phải được phép");

        // Tạo phòng tạm ở CN2 để test overlap giữa CN1 (08:00-12:00) và CN2 (09:00-13:00)
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO rooms (room_id, branch_id, room_code, room_name, room_type, status) VALUES (3, 2, 'P201', 'Phong CN2', 'TREATMENT', 'ACTIVE')");
            stmt.execute("INSERT INTO doctor_room_assignments (branch_id, staff_id, room_id, is_primary, effective_from, status) VALUES (2, 1, 3, b'1', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE')");
        } catch (Exception e) {
            fail(e);
        }

        IllegalArgumentException crossBranchEx = assertThrows(IllegalArgumentException.class, () ->
                doctorShiftAssignmentService.assignDoctorsToRoom(
                        2L, WORK_DATE_1, 4L, 3L, List.of(1L),
                        DoctorShiftAssignmentRole.CO_ATTENDING, "Thu xep chong lan CN2", 1L));
        assertTrue(crossBranchEx.getMessage().contains("chồng lấn") || crossBranchEx.getMessage().contains("08:00"),
                "Phải chặn chồng lấn thời gian liên chi nhánh: " + crossBranchEx.getMessage());

        // 3c. Kiểm tra unique index DB uk_dsa_active_staff_shift ở tầng JPA/MySQL
        assertThrows(DataIntegrityViolationException.class, () -> {
            DoctorShiftAssignment dup = new DoctorShiftAssignment();
            DoctorShiftAssignment existing = shiftAssignmentRepository
                    .findByBranch_BranchIdAndWorkDateAndShift_ShiftIdAndRoom_RoomIdAndStatusOrderByShiftAssignmentIdAsc(
                            1L, WORK_DATE_1, 1L, 1L, DoctorShiftAssignmentStatus.ACTIVE)
                    .get(0);
            dup.setStaffSchedule(existing.getStaffSchedule());
            dup.setBranch(existing.getBranch());
            dup.setWorkDate(WORK_DATE_1);
            dup.setShift(existing.getShift());
            dup.setRoom(existing.getRoom());
            dup.setStaff(existing.getStaff());
            dup.setAssignmentRole(DoctorShiftAssignmentRole.CO_ATTENDING);
            dup.setStatus(DoctorShiftAssignmentStatus.ACTIVE);
            dup.syncActiveSlotKey();
            shiftAssignmentRepository.saveAndFlush(dup);
        });
    }

    @Test
    @Order(4)
    @DisplayName("4. Duyệt nghỉ phép/nghỉ đột xuất: chỉ khóa BS nghỉ (ON_LEAVE), giữ đồng nghiệp trong phòng, đánh dấu lịch hẹn NEEDS_REASSIGNMENT")
    void approveLeaveTransitionsOnlyLeavingDoctorAndPreservesRoomColleaguesAndAppointments() {
        // BS03 vẫn đang ACTIVE trong phòng 101 ca Sáng WORK_DATE_1 (cùng BS01)
        // Tạo 1 đơn nghỉ phép PENDING cho BS01 vào WORK_DATE_1 (shift 1)
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO appointments (appointment_id, appointment_code, branch_id, profile_id, doctor_id, service_id, appointment_date, preferred_session, expected_time, duration_minutes, reschedule_count, source, status, created_at) "
                    + "VALUES (103, 'AP-M2-LEAVE', 1, 1, 1, 1, '" + WORK_DATE_1 + "', 'MORNING', '09:30:00', 30, 0, 'ONLINE', 'CONFIRMED', NOW())");
            stmt.execute("INSERT INTO leave_requests (leave_id, staff_id, branch_id, shift_id, leave_type, is_emergency, start_date, end_date, reason, status, submitted_at) "
                    + "VALUES (1, 1, 1, 1, 'SICK', b'1', '" + WORK_DATE_1 + "', '" + WORK_DATE_1 + "', 'Nghi om dot xuat ca sang', 'PENDING', NOW())");
        } catch (Exception e) {
            fail(e);
        }

        int impactedAppts = leaveRequestService.approveLeaveRequest(1L, 1L, "Da duyet nghi dot xuat");
        assertTrue(impactedAppts >= 1, "Phải ghi nhận ít nhất 1 lịch hẹn bị ảnh hưởng");

        // Kiểm tra BS01 -> ON_LEAVE, BS03 vẫn ACTIVE
        List<DoctorShiftAssignment> allP101 = shiftAssignmentRepository.findByBranchAndDateRangeWithDetails(1L, WORK_DATE_1, WORK_DATE_1)
                .stream()
                .filter(a -> a.getShift().getShiftId().equals(1L) && a.getRoom().getRoomId().equals(1L))
                .toList();
        DoctorShiftAssignment bs01 = allP101.stream().filter(a -> a.getStaff().getStaffId().equals(1L)).findFirst().orElseThrow();
        DoctorShiftAssignment bs03 = allP101.stream().filter(a -> a.getStaff().getStaffId().equals(3L)).findFirst().orElseThrow();

        assertEquals(DoctorShiftAssignmentStatus.ON_LEAVE, bs01.getStatus());
        assertNull(bs01.getActiveSlotKey());
        assertEquals(DoctorShiftAssignmentStatus.ACTIVE, bs03.getStatus(), "Đồng nghiệp BS03 trong cùng phòng phải giữ nguyên ACTIVE");

        Appointment leaveAppt = appointmentRepository.findById(103L).orElseThrow();
        assertNotEquals(AppointmentStatus.CANCELLED, leaveAppt.getStatus(), "Không được tự ý hủy lịch hẹn bệnh nhân");
        assertEquals(AppointmentResourceStatus.NEEDS_REASSIGNMENT, leaveAppt.getResourceStatus());
    }

    @Test
    @Order(5)
    @DisplayName("5. Thay thế bác sĩ: chặn non-ADMIN & lý do <10 ký tự khi override phòng, cho phép ADMIN override + chuyển giao lịch đủ quyền + giữ nguyên phòng/ghế + log lịch sử")
    void replaceDoctorEnforcesPermissionOverrideAuditAndReservationSwap() {
        // Tạo ca WORK_DATE_2 ca Sáng (shift 1) tại P101 cho BS01 và BS02 cùng trực
        List<DoctorShiftAssignment> day2 = doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, WORK_DATE_2, 1L, 1L, List.of(1L, 2L),
                DoctorShiftAssignmentRole.CO_ATTENDING, "Ca sang ngay 2", 1L);
        DoctorShiftAssignment bs01Day2 = day2.stream().filter(a -> a.getStaff().getStaffId().equals(1L)).findFirst().orElseThrow();

        // Seed reservations cho appointment 101 (DV-KHAM: BS04 có quyền) và 102 (DV-NHO: BS04 không có quyền)
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM appointment_resource_reservations WHERE appointment_id = 101");
            stmt.execute("INSERT INTO appointment_resource_reservations "
                    + "(appointment_id, branch_id, resource_type, resource_id, usage_scope, is_room_exclusive, start_at, end_at, status, created_at) VALUES "
                    + "(101, 1, 'DOCTOR', 1, 'FULL_STAY', 0, '" + WORK_DATE_2 + " 09:00:00', '" + WORK_DATE_2 + " 09:30:00', 'RESERVED', NOW()),"
                    + "(101, 1, 'ROOM',   1, 'FULL_STAY', 0, '" + WORK_DATE_2 + " 09:00:00', '" + WORK_DATE_2 + " 09:30:00', 'RESERVED', NOW()),"
                    + "(101, 1, 'CHAIR',  1, 'FULL_STAY', 0, '" + WORK_DATE_2 + " 09:00:00', '" + WORK_DATE_2 + " 09:30:00', 'RESERVED', NOW())");
        } catch (Exception e) {
            fail(e);
        }

        // Preview replacement phải liệt kê 2 lịch hẹn bị ảnh hưởng và BS04 có hasRoomPermission=false, missingServices=['Nho rang khon']
        DoctorShiftAssignmentService.ReplacementPreview preview = doctorShiftAssignmentService.previewReplacement(bs01Day2.getShiftAssignmentId());
        assertEquals(2, preview.affectedAppointments().size());
        DoctorShiftAssignmentService.ReplacementCandidateSummary bs04Cand = preview.candidates().stream()
                .filter(c -> c.staffId().equals(4L)).findFirst().orElseThrow();
        assertFalse(bs04Cand.hasRoomPermission());
        assertFalse(bs04Cand.missingServices().isEmpty());

        // 5a. Non-ADMIN bật overrideRoomPermission => 403 FORBIDDEN
        ResponseStatusException forbiddenEx = assertThrows(ResponseStatusException.class, () ->
                doctorShiftAssignmentService.replaceDoctorInShift(
                        bs01Day2.getShiftAssignmentId(), 4L, true,
                        "Le tan co gang bat ngoai le phong truc", 6L));
        assertEquals(403, forbiddenEx.getStatusCode().value());

        // 5b. ADMIN bật overrideRoomPermission nhưng lý do < 10 ký tự => IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
                doctorShiftAssignmentService.replaceDoctorInShift(
                        bs01Day2.getShiftAssignmentId(), 4L, true,
                        "Ngan", 1L));

        // 5c. ADMIN không bật overrideRoomPermission cho BS04 (chưa có quyền phòng) => IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
                doctorShiftAssignmentService.replaceDoctorInShift(
                        bs01Day2.getShiftAssignmentId(), 4L, false,
                        "Thay the chua bat ngoai le phong", 1L));

        // 5d. ADMIN bật overrideRoomPermission với lý do >= 10 ký tự => Thành công!
        DoctorShiftAssignmentService.ReplacementResult result = doctorShiftAssignmentService.replaceDoctorInShift(
                bs01Day2.getShiftAssignmentId(), 4L, true,
                "BS01 nghi dot xuat, dieu BS04 thay the ngoai le", 1L);

        assertEquals(1, result.transferredAppointments(), "AP-M2-ELIG (DV-KHAM) phải chuyển giao sang BS04");
        assertEquals(1, result.needsReassignmentAppointments(), "AP-M2-INELIG (DV-NHO) giữ nguyên BS & đánh dấu NEEDS_REASSIGNMENT");

        DoctorShiftAssignment oldUpdated = shiftAssignmentRepository.findById(bs01Day2.getShiftAssignmentId()).orElseThrow();
        assertEquals(DoctorShiftAssignmentStatus.REPLACED, oldUpdated.getStatus());
        assertNull(oldUpdated.getActiveSlotKey());

        DoctorShiftAssignment newAssign = shiftAssignmentRepository.findById(result.newShiftAssignmentId()).orElseThrow();
        assertEquals(DoctorShiftAssignmentStatus.ACTIVE, newAssign.getStatus());
        assertEquals(DoctorShiftAssignmentRole.REPLACEMENT, newAssign.getAssignmentRole());
        assertEquals(4L, newAssign.getStaff().getStaffId());
        assertTrue(Boolean.TRUE.equals(newAssign.getRoomPermissionOverridden()));

        // BS02 cùng phòng P101 phải vẫn ACTIVE
        List<DoctorShiftAssignment> activeDay2 = shiftAssignmentRepository
                .findByBranch_BranchIdAndWorkDateAndShift_ShiftIdAndRoom_RoomIdAndStatusOrderByShiftAssignmentIdAsc(
                        1L, WORK_DATE_2, 1L, 1L, DoctorShiftAssignmentStatus.ACTIVE);
        assertEquals(2, activeDay2.size(), "Phòng 101 ngày 2 phải có BS02 và BS04(REPLACEMENT) ACTIVE");

        // Kiểm tra appointment & reservations
        Appointment apElig = appointmentRepository.findById(101L).orElseThrow();
        Appointment apInelig = appointmentRepository.findById(102L).orElseThrow();
        assertEquals(4L, apElig.getDoctor().getStaffId());
        assertEquals(1L, apInelig.getDoctor().getStaffId());
        assertEquals(AppointmentResourceStatus.NEEDS_REASSIGNMENT, apInelig.getResourceStatus());

        List<AppointmentResourceReservation> ap101Res = reservationRepository
                .findByAppointment_AppointmentIdOrderByReservationIdAsc(101L);
        assertTrue(ap101Res.stream().anyMatch(r -> r.getResourceType() == ReservationResourceType.DOCTOR
                && r.getResourceId().equals(4L) && r.getStatus() == ReservationStatus.RESERVED),
                "Reservation DOCTOR mới phải trỏ về BS04");
        assertTrue(ap101Res.stream().anyMatch(r -> r.getResourceType() == ReservationResourceType.DOCTOR
                && r.getResourceId().equals(1L) && r.getStatus() == ReservationStatus.RELEASED),
                "Reservation DOCTOR cũ (BS01) phải được RELEASED");
        assertTrue(ap101Res.stream().anyMatch(r -> r.getResourceType() == ReservationResourceType.ROOM
                && r.getResourceId().equals(1L) && r.getStatus() == ReservationStatus.RESERVED),
                "Reservation ROOM phải giữ nguyên RESERVED");
        assertTrue(ap101Res.stream().anyMatch(r -> r.getResourceType() == ReservationResourceType.CHAIR
                && r.getResourceId().equals(1L) && r.getStatus() == ReservationStatus.RESERVED),
                "Reservation CHAIR phải giữ nguyên RESERVED");

        // Kiểm tra replacement log & system audit log
        List<DoctorShiftReplacementLog> logs = replacementLogRepository.findByBranchAndDateRangeOrderByReplacedAtDesc(1L, WORK_DATE_2, WORK_DATE_2);
        assertEquals(1, logs.size());
        assertTrue(Boolean.TRUE.equals(logs.get(0).getRoomPermissionOverridden()));

        List<SystemAuditLog> audits = systemAuditLogRepository.findAll();
        assertTrue(audits.stream().anyMatch(a -> "REPLACE_DOCTOR_SHIFT_ROOM_OVERRIDE".equals(a.getAction())),
                "Phải ghi SystemAuditLog khi override quyền phòng");
    }

    @Test
    @Order(6)
    @DisplayName("6. MockMvc RBAC & Regression /admin/schedules: phân ca BS theo phòng, nhân sự hỗ trợ, copy tuần & preview JSON")
    void mockMvcScheduleRbacAndWorkflowRegression() throws Exception {
        // 6a. Chưa đăng nhập -> redirect /login
        mockMvc.perform(get("/admin/schedules"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        // 6b. Bác sĩ không có quyền SCHEDULE_MANAGE -> bị FeaturePermissionInterceptor chặn (302 /home cho HTML, 403 cho JSON)
        MockHttpSession doctorSession = sessionForUser(2L, "BAC_SI");
        mockMvc.perform(get("/admin/schedules").session(doctorSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/home"));
        mockMvc.perform(get("/admin/schedules/doctor-shifts/1/replacement-preview")
                        .header("Accept", "application/json")
                        .session(doctorSession))
                .andExpect(status().isForbidden());

        // 6c. ADMIN GET /admin/schedules -> 200 OK + đầy đủ UI Chi nhánh -> Ngày -> Ca -> Phòng -> Danh sách bác sĩ
        MockHttpSession adminSession = sessionForUser(1L, "ADMIN");
        mockMvc.perform(get("/admin/schedules")
                        .param("branchId", "1")
                        .param("week", WORK_DATE_2.toString())
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bảng phân ca")))
                .andExpect(content().string(containsString("P101")))
                .andExpect(content().string(containsString("BS Nguyen Van A")));

        // 6d. Regression: ADMIN xếp ca nhân sự hỗ trợ (Lễ tân LT01 id=5) cho Ca Sáng ở tuần hiện tại của WORK_DATE_2
        mockMvc.perform(post("/admin/schedules/assign")
                        .with(csrf())
                        .session(adminSession)
                        .param("branchId", "1")
                        .param("staffId", "5")
                        .param("shiftId", "1")
                        .param("workDate", WORK_DATE_2.toString())
                        .param("note", "Le tan truc quay"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        // 6e. ADMIN phân công BS03 vào P102 Ca Tối (shift 3) qua HTTP POST
        mockMvc.perform(post("/admin/schedules/doctor-shifts/assign")
                        .with(csrf())
                        .session(adminSession)
                        .param("branchId", "1")
                        .param("workDate", WORK_DATE_2.toString())
                        .param("shiftId", "3")
                        .param("roomId", "2")
                        .param("staffIds", "2")
                        .param("role", "PRIMARY")
                        .param("note", "Truc toi qua UI"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        // 6f. Preview replacement JSON API cho assignment vừa tạo của BS02 tại Ca Tối
        Long nightAssignId = shiftAssignmentRepository
                .findByBranch_BranchIdAndWorkDateAndShift_ShiftIdAndRoom_RoomIdAndStatusOrderByShiftAssignmentIdAsc(
                        1L, WORK_DATE_2, 3L, 2L, DoctorShiftAssignmentStatus.ACTIVE)
                .get(0).getShiftAssignmentId();

        mockMvc.perform(get("/admin/schedules/doctor-shifts/" + nightAssignId + "/replacement-preview")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalAssignmentId").value(nightAssignId))
                .andExpect(jsonPath("$.candidates").isArray());

        // 6g. Regression copyWeek sang tuần kế tiếp
        mockMvc.perform(post("/admin/schedules/copy-week")
                        .with(csrf())
                        .session(adminSession)
                        .param("branchId", "1")
                        .param("week", NEXT_WEEK_MONDAY.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        List<DoctorShiftAssignment> copiedDoctorShifts = doctorShiftAssignmentService.findWeekAssignments(
                1L, NEXT_WEEK_MONDAY, NEXT_WEEK_MONDAY.plusDays(6));
        assertFalse(copiedDoctorShifts.isEmpty(), "copy-week phải sao chép cả phân công bác sĩ theo phòng");

        List<StaffSchedule> copiedSupport = staffScheduleRepository.findByBranchAndRange(
                1L, NEXT_WEEK_MONDAY, NEXT_WEEK_MONDAY.plusDays(6));
        assertTrue(copiedSupport.stream().anyMatch(s -> s.getStaff().getStaffId().equals(5L)),
                "copy-week phải sao chép cả lịch trực nhân sự hỗ trợ");
    }
}
