package com.cvdcms.service;

import com.cvdcms.DTO.availability.ResourceBookingRequest;
import com.cvdcms.DTO.availability.ResourceBookingResult;
import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.AppointmentResourceReservation;
import com.cvdcms.entity.enums.AppointmentResourceStatus;
import com.cvdcms.entity.enums.DoctorShiftAssignmentRole;
import com.cvdcms.entity.enums.ReservationResourceType;
import com.cvdcms.entity.enums.ReservationStatus;
import com.cvdcms.repository.AppointmentRepository;
import com.cvdcms.repository.AppointmentResourceReservationRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.cvdcms.service.Phase2Milestone4BookingTransactionIntegrationTest$LockOrderSqlInspector"
})
@ActiveProfiles("v118test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase2Milestone4BookingTransactionIntegrationTest {

    private static final LocalDate D_BASIC = LocalDate.now().plusDays(2);
    private static final LocalDate D_CHAIR_RACE = LocalDate.now().plusDays(3);
    private static final LocalDate D_EXCL_RACE = LocalDate.now().plusDays(4);
    private static final LocalDate D_MULTI_CHAIR = LocalDate.now().plusDays(5);
    private static final LocalDate D_EQUIP_RACE = LocalDate.now().plusDays(6);
    private static final LocalDate D_BUFFER = LocalDate.now().plusDays(7);

    public static class LockOrderSqlInspector implements StatementInspector {
        private static volatile boolean capture = false;
        private static final List<String> capturedForUpdateSql = Collections.synchronizedList(new ArrayList<>());

        static void startCapture() {
            capturedForUpdateSql.clear();
            capture = true;
        }

        static List<String> stopCapture() {
            capture = false;
            return new ArrayList<>(capturedForUpdateSql);
        }

        @Override
        public String inspect(String sql) {
            if (capture && sql != null && sql.toLowerCase().contains("for update")) {
                capturedForUpdateSql.add(sql);
            }
            return sql;
        }
    }

    static {
        prepareIsolatedVerifySchemaBeforeSpringBoot();
    }

    @Autowired private DataSource dataSource;
    @Autowired private ResourceBookingTransactionService bookingTransactionService;
    @Autowired private DoctorShiftAssignmentService doctorShiftAssignmentService;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private AppointmentResourceReservationRepository reservationRepository;

    private static void prepareIsolatedVerifySchemaBeforeSpringBoot() {
        try (Connection conn = IsolatedVerifySchemaHelper.openVerifiedConnection();
             Statement stmt = conn.createStatement()) {
            IsolatedVerifySchemaHelper.resetAllTablesInVerifySchema(stmt);
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "sql/v118_test_baseline_ddl.sql", ";");
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V117__resource_scheduling_master_data.sql", "$$");

            stmt.execute("INSERT INTO branches (branch_id, branch_code, branch_name, address, opening_time, closing_time, working_days, is_active, created_at, updated_at) VALUES "
                    + "(1, 'CS1', 'Chi nhanh M4-1', '100 Duong M4', '08:00:00', '21:00:00', '2,3,4,5,6,7,CN', b'1', NOW(), NOW()),"
                    + "(2, 'CS2', 'Chi nhanh M4-2', '200 Duong M4', '08:00:00', '21:00:00', '2,3,4,5,6,7,CN', b'1', NOW(), NOW())");

            // P101 (2 ghe), P102 (1 ghe), P201 o CN2 (1 ghe)
            stmt.execute("INSERT INTO rooms (room_id, branch_id, room_code, room_name, room_type, status, floor_label, zone_label, floor_order) VALUES "
                    + "(1, 1, 'P101', 'Phong 2 Ghe 101', 'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu A', 1),"
                    + "(2, 1, 'P102', 'Phong 1 Ghe 102', 'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu B', 1),"
                    + "(3, 2, 'P201', 'Phong CN2 201',   'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu A', 1)");

            stmt.execute("INSERT INTO dental_chairs (chair_id, branch_id, room_id, chair_code, chair_name, status) VALUES "
                    + "(1, 1, 1, 'G101-A', 'Ghe 101A', 'AVAILABLE'),"
                    + "(2, 1, 1, 'G101-B', 'Ghe 101B', 'AVAILABLE'),"
                    + "(3, 1, 2, 'G102-A', 'Ghe 102A', 'AVAILABLE'),"
                    + "(4, 2, 3, 'G201-A', 'Ghe 201A', 'AVAILABLE')");

            stmt.execute("INSERT INTO work_shifts (shift_id, branch_id, shift_code, shift_name, start_time, end_time, is_active) VALUES "
                    + "(1, 1, 'SANG',  'Ca Sang',  '08:00:00', '12:00:00', b'1'),"
                    + "(2, 1, 'CHIEU', 'Ca Chieu', '12:00:00', '17:00:00', b'1'),"
                    + "(3, 2, 'SANG2', 'Ca Sang CN2', '08:00:00', '12:00:00', b'1')");

            stmt.execute("INSERT INTO roles (role_id, role_name, display_name, is_active, is_system) VALUES "
                    + "(1, 'ADMIN',  'Quan tri vien', b'1', b'1'),"
                    + "(2, 'BAC_SI', 'Bac si',        b'1', b'1')");

            stmt.execute("INSERT INTO users (user_id, username, password, email, phone_number, user_type, branch_id, is_active, status, custom_permissions_enabled, created_date, updated_at) VALUES "
                    + "(1, 'admin_m4', '$2a$10$dummyhash', 'admin_m4@test.local', '0900003001', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(2, 'bs01_m4',  '$2a$10$dummyhash', 'bs01_m4@test.local',  '0900003002', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(3, 'bs02_m4',  '$2a$10$dummyhash', 'bs02_m4@test.local',  '0900003003', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW())");
            stmt.execute("INSERT INTO user_roles (user_id, role_id) VALUES (1,1),(2,2),(3,2)");

            stmt.execute("INSERT INTO staff_profiles (staff_id, user_id, staff_code, full_name, specialty, status, contract_type, base_salary, allowance_amount, insurance_salary, dependent_count, has_personal_deduction, has_tax_commitment, is_tax_resident, insurance_participation_status, created_at, updated_at) VALUES "
                    + "(1, 2, 'BS01', 'BS Nguyen A', 'Tong quat', 'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(2, 3, 'BS02', 'BS Tran B',   'Nha chu',   'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW())");

            stmt.execute("INSERT INTO staff_branch_assignments (branch_id, staff_id, role_id, is_primary, start_date, status, created_at) VALUES "
                    + "(1, 1, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(2, 1, 2, b'0', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(1, 2, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW())");

            stmt.execute("INSERT INTO doctor_room_assignments (branch_id, staff_id, room_id, is_primary, effective_from, status) VALUES "
                    + "(1, 1, 1, b'1', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 1, 2, b'0', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(2, 1, 3, b'0', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 2, 1, b'1', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 2, 2, b'0', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE')");

            // 12 patient profiles cho 12 concurrent threads tranh chấp khác profile
            StringBuilder patSql = new StringBuilder(
                    "INSERT INTO patient_profiles (profile_id, patient_code, full_name, gender, address, phone_number, deposit_balance, is_default, medical_history_completed, status, preferred_branch_id, created_at, updated_at) VALUES ");
            for (int i = 1; i <= 12; i++) {
                if (i > 1) patSql.append(',');
                patSql.append("(").append(i).append(", 'BN-M4-").append(i).append("', 'Benh nhan ").append(i)
                        .append("', 'MALE', 'Ha Noi', '09220000").append(String.format("%02d", i))
                        .append("', 0.00, b'1', b'0', 'ACTIVE', 1, NOW(), NOW())");
            }
            stmt.execute(patSql.toString());

            stmt.execute("INSERT INTO service_categories (category_id, category_code, category_name, category_type, display_order, is_active) VALUES (1, 'CAT-M4', 'Dich vu M4', 'PROCEDURE', 1, b'1')");
            // 1: Kham thuong (30p, ko doc quyen)
            // 2: Tieu phau doc quyen (PREP=5, PROC=20, TURN=5, is_room_exclusive=1)
            // 3: Laser (PREP=5, PROC=15, TURN=5, yeu cau EQUIPMENT_TYPE=LASER BRANCH_SHARED trong PROCEDURE)
            // 4: Thieu thiet bi bat buoc (yeu cau EQUIPMENT_TYPE=CT_SCANNER chua co may ACTIVE)
            stmt.execute("INSERT INTO services (service_id, category_id, service_code, service_name, service_type, unit, base_price, surcharge_amount, estimated_duration_minutes, preparation_duration_minutes, procedure_duration_minutes, turnover_duration_minutes, requires_chair, is_room_exclusive, doctor_required_in_prep, doctor_required_in_turnover, is_default_consultation, is_bookable_online, is_paraclinical, requires_tooth, status, created_at) VALUES "
                    + "(1, 1, 'DV-KHAM', 'Kham thuong',      'CONSULTATION', 'Lan', 100000, 0, 30, 0, 30, 0, b'1', b'0', b'0', b'0', b'1', b'1', b'0', b'0', 'ACTIVE', NOW()),"
                    + "(2, 1, 'DV-EXCL', 'Thu thuat doc quyen','PROCEDURE',    'Lan', 500000, 0, 30, 5, 20, 5, b'1', b'1', b'0', b'0', b'0', b'1', b'0', b'0', 'ACTIVE', NOW()),"
                    + "(3, 1, 'DV-LASER','Dieu tri Laser',   'PROCEDURE',    'Lan', 400000, 0, 25, 5, 15, 5, b'1', b'0', b'0', b'0', b'0', b'1', b'0', b'0', 'ACTIVE', NOW()),"
                    + "(4, 1, 'DV-NOEQ', 'Chup CT thieu may','XRAY',         'Lan', 900000, 0, 30, 0, 30, 0, b'0', b'1', b'0', b'0', b'0', b'1', b'1', b'0', 'ACTIVE', NOW())");

            stmt.execute("INSERT INTO service_resource_requirements (service_id, resource_kind, room_type, equipment_type, usage_phase, is_required) VALUES "
                    + "(1, 'ROOM_TYPE', 'TREATMENT', NULL, 'ALL', b'1'),"
                    + "(2, 'ROOM_TYPE', 'TREATMENT', NULL, 'ALL', b'1'),"
                    + "(3, 'ROOM_TYPE', 'TREATMENT', NULL, 'ALL', b'1'),"
                    + "(3, 'EQUIPMENT_TYPE', NULL, 'LASER', 'PROCEDURE', b'1'),"
                    + "(4, 'ROOM_TYPE', 'TREATMENT', NULL, 'ALL', b'1'),"
                    + "(4, 'EQUIPMENT_TYPE', NULL, 'CONE_BEAM', 'PROCEDURE', b'1')");

            stmt.execute("INSERT INTO clinic_equipments (equipment_id, branch_id, allocation_mode, fixed_room_id, current_room_id, equipment_code, equipment_name, equipment_type, device_serial_number, status) VALUES "
                    + "(1, 1, 'BRANCH_SHARED', NULL, 1, 'LSR-M4', 'May Laser M4', 'LASER', 'SN-M4-LSR', 'ACTIVE'),"
                    + "(2, 1, 'BRANCH_SHARED', NULL, 1, 'CBCT-P', 'May CBCT Cho Duyet', 'CONE_BEAM', 'SN-M4-CBCT', 'PENDING_VERIFICATION')");

            stmt.execute("INSERT INTO doctor_service_permissions (staff_id, service_id, branch_id, effective_from, status) VALUES "
                    + "(1, 1, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 2, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 3, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 4, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(2, 1, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(2, 2, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(2, 3, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE')");

            // Travel buffer P101 -> P102 = 15 phut
            stmt.execute("INSERT INTO branch_room_transition_rules (branch_id, rule_scope, from_room_id, to_room_id, travel_buffer_minutes, is_active, rule_signature, active_guard) VALUES "
                    + "(1, 'SPECIFIC_PAIR', 1, 2, 15, b'1', 'SPECIFIC_PAIR:1:2', 1)");

            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V118__appointment_reservations_and_snapshots.sql", "$$");
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V119__doctor_shift_assignments_and_replacements.sql", "$$");
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private record RaceStats(List<ResourceBookingResult> successes, List<Throwable> failures) {}

    private RaceStats runConcurrentBookings(int threadCount, java.util.function.IntFunction<ResourceBookingRequest> reqFactory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            tasks.add(() -> {
                ready.countDown();
                assertTrue(start.await(15, TimeUnit.SECONDS));
                try {
                    return bookingTransactionService.bookWithRetry(reqFactory.apply(idx));
                } catch (Throwable t) {
                    return t;
                }
            });
        }
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        start.countDown();

        List<ResourceBookingResult> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (Future<Object> f : futures) {
            Object val = f.get(30, TimeUnit.SECONDS);
            if (val instanceof ResourceBookingResult r) {
                successes.add(r);
            } else if (val instanceof Throwable t) {
                failures.add(t);
            }
        }
        pool.shutdownNow();
        return new RaceStats(successes, failures);
    }

    @Test
    @Order(1)
    @DisplayName("1. Đặt giữ đủ Doctor+Room+Chair+Equipment, kiểm tra Lock Order SQL & rollback toàn bộ khi thiếu thiết bị bắt buộc")
    void fullBookingSuccessRollbackOnMissingResourceAndLockOrderVerification() {
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, D_BASIC, 1L, 1L, List.of(1L),
                DoctorShiftAssignmentRole.PRIMARY, "Ca M4 basic", 1L);

        LockOrderSqlInspector.startCapture();
        ResourceBookingResult ok = bookingTransactionService.bookWithRetry(
                ResourceBookingRequest.newBooking(1L, 1L, 3L, D_BASIC, LocalTime.of(9, 0), 1L, false, "IDEM-M4-1"));
        List<String> lockSqls = LockOrderSqlInspector.stopCapture();

        assertFalse(ok.idempotentReplay());
        assertEquals(1L, ok.doctorId());
        assertEquals(1L, ok.roomId());
        assertNotNull(ok.chairId());
        assertEquals(List.of(1L), ok.equipmentIds());
        assertEquals(4, ok.reservationIds().size(), "Phải tạo đủ DOCTOR + ROOM + CHAIR + EQUIPMENT");

        Appointment savedAppt = appointmentRepository.findById(ok.appointmentId()).orElseThrow();
        assertEquals(5, savedAppt.getPreparationDurationMinutes());
        assertEquals(15, savedAppt.getProcedureDurationMinutes());
        assertEquals(5, savedAppt.getTurnoverDurationMinutes());
        assertEquals(AppointmentResourceStatus.OK, savedAppt.getResourceStatus());

        // Xác minh thứ tự khóa toàn cục: patient_profiles(2) -> branches(3) -> staff_profiles(4) -> staff_schedules/dsa(5) -> rooms(6) -> dental_chairs(7) -> clinic_equipments(8)
        List<Integer> lockRanks = lockSqls.stream().map(sql -> {
            String lower = sql.toLowerCase();
            if (lower.contains("from patient_profiles")) return 2;
            if (lower.contains("from branches")) return 3;
            if (lower.contains("from staff_profiles")) return 4;
            if (lower.contains("from staff_schedules") || lower.contains("from doctor_shift_assignments")) return 5;
            if (lower.contains("from rooms")) return 6;
            if (lower.contains("from dental_chairs")) return 7;
            if (lower.contains("from clinic_equipments")) return 8;
            if (lower.contains("from appointments")) return 9;
            if (lower.contains("from appointment_resource_reservations")) return 10;
            return 99;
        }).toList();
        assertFalse(lockRanks.isEmpty());
        for (int i = 1; i < lockRanks.size(); i++) {
            assertTrue(lockRanks.get(i) >= lockRanks.get(i - 1),
                    "Vi phạm Global Lock Order: " + lockRanks + " | SQL: " + lockSqls);
        }

        // Idempotency: gửi lại cùng idempotencyKey -> idempotentReplay = true, không tạo thêm appointment/reservation
        long apptsBefore = appointmentRepository.count();
        long resBefore = reservationRepository.count();
        ResourceBookingResult replay = bookingTransactionService.bookWithRetry(
                ResourceBookingRequest.newBooking(1L, 1L, 3L, D_BASIC, LocalTime.of(9, 0), 1L, false, "IDEM-M4-1"));
        assertTrue(replay.idempotentReplay());
        assertEquals(ok.appointmentId(), replay.appointmentId());
        assertEquals(ok.reservationIds(), replay.reservationIds());
        assertEquals(apptsBefore, appointmentRepository.count());
        assertEquals(resBefore, reservationRepository.count());

        // Thiếu thiết bị bắt buộc (DV 4 cần CBCT ACTIVE, hiện chỉ có PENDING_VERIFICATION) -> 409 + rollback toàn bộ
        assertThrows(ResponseStatusException.class, () -> bookingTransactionService.bookWithRetry(
                ResourceBookingRequest.newBooking(1L, 2L, 4L, D_BASIC, LocalTime.of(10, 0), 1L, false, "IDEM-FAIL-CBCT")));
        assertEquals(apptsBefore, appointmentRepository.count(), "Rollback không được giữ lại Appointment");
        assertEquals(resBefore, reservationRepository.count(), "Rollback không được giữ lại Reservation một phần");
    }

    @Test
    @Order(2)
    @DisplayName("2. MySQL Concurrency (12 threads): Tranh cùng 1 ghế tại P102 -> đúng 1 thread thắng, 11 threads bị 409")
    void mysqlConcurrency12ThreadsCompeteForSingleChair() throws Exception {
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, D_CHAIR_RACE, 1L, 2L, List.of(1L),
                DoctorShiftAssignmentRole.PRIMARY, "Tranh 1 ghe P102", 1L);

        RaceStats stats = runConcurrentBookings(12, idx ->
                ResourceBookingRequest.newBooking(1L, (long) (idx + 1), 1L, D_CHAIR_RACE, LocalTime.of(9, 0), 1L, false, null));

        assertEquals(1, stats.successes().size(), "Đúng 1 thread được phép thắng 1 ghế duy nhất");
        assertEquals(11, stats.failures().size());
        assertTrue(stats.failures().stream().allMatch(t -> t instanceof ResponseStatusException r && r.getStatusCode().value() == 409));
    }

    @Test
    @Order(3)
    @DisplayName("3. MySQL Concurrency (10 threads): Phòng độc quyền P101 với 2 BS -> đúng 1 thread thắng")
    void mysqlConcurrency10ThreadsCompeteForExclusiveRoom() throws Exception {
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, D_EXCL_RACE, 1L, 1L, List.of(1L, 2L),
                DoctorShiftAssignmentRole.CO_ATTENDING, "Tranh phong doc quyen P101", 1L);

        RaceStats stats = runConcurrentBookings(10, idx ->
                ResourceBookingRequest.newBooking(1L, (long) (idx + 1), 2L, D_EXCL_RACE, LocalTime.of(9, 0), null, true, null));

        assertEquals(1, stats.successes().size(), "Phòng độc quyền chỉ cho đúng 1 booking thắng");
        assertEquals(9, stats.failures().size());
    }

    @Test
    @Order(4)
    @DisplayName("4. MySQL Concurrency (10 threads): Phòng 2 ghế P101 (2 BS) cho phép 2 booking khác ghế đồng thời")
    void mysqlConcurrency10ThreadsMultiChairAllocatesDistinctChairs() throws Exception {
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, D_MULTI_CHAIR, 1L, 1L, List.of(1L, 2L),
                DoctorShiftAssignmentRole.CO_ATTENDING, "Phong 2 ghe P101", 1L);

        RaceStats stats = runConcurrentBookings(10, idx ->
                ResourceBookingRequest.newBooking(1L, (long) (idx + 1), 1L, D_MULTI_CHAIR, LocalTime.of(9, 0), null, true, null));

        assertEquals(2, stats.successes().size(), "Phòng 2 ghế + 2 BS phải cấp đúng 2 booking đồng thời");
        assertEquals(8, stats.failures().size());
        Set<Long> allocatedChairs = stats.successes().stream().map(ResourceBookingResult::chairId).collect(Collectors.toSet());
        Set<Long> allocatedDoctors = stats.successes().stream().map(ResourceBookingResult::doctorId).collect(Collectors.toSet());
        assertEquals(2, allocatedChairs.size(), "Phải phân bổ 2 ghế khác nhau (G101-A và G101-B)");
        assertEquals(2, allocatedDoctors.size(), "Phải phân bổ 2 bác sĩ khác nhau");
    }

    @Test
    @Order(5)
    @DisplayName("5. MySQL Concurrency (10 threads): Tranh cùng thiết bị BRANCH_SHARED (LSR-M4) trên 2 phòng khác nhau")
    void mysqlConcurrency10ThreadsCompeteForSingleSharedEquipment() throws Exception {
        // BS01 ở P101, BS02 ở P102 cùng ca Sáng D_EQUIP_RACE -> 2 phòng độc lập nhưng chỉ có 1 máy Laser LSR-M4
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, D_EQUIP_RACE, 1L, 1L, List.of(1L),
                DoctorShiftAssignmentRole.PRIMARY, "Laser P101", 1L);
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, D_EQUIP_RACE, 1L, 2L, List.of(2L),
                DoctorShiftAssignmentRole.PRIMARY, "Laser P102", 1L);

        RaceStats stats = runConcurrentBookings(10, idx ->
                ResourceBookingRequest.newBooking(1L, (long) (idx + 1), 3L, D_EQUIP_RACE, LocalTime.of(9, 0), null, true, null));

        assertEquals(1, stats.successes().size(), "Chỉ có 1 máy Laser LSR-M4 nên chỉ 1 thread được giữ thiết bị");
        assertEquals(9, stats.failures().size());
    }

    @Test
    @Order(6)
    @DisplayName("6. Xung đột chéo chi nhánh, travel buffer, legacy appointment chưa có reservation & idempotent race")
    void crossBranchOverlapTravelBufferLegacyAppointmentAndConcurrentIdempotency() throws Exception {
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, D_BUFFER, 1L, 1L, List.of(1L),
                DoctorShiftAssignmentRole.PRIMARY, "Ca sang P101", 1L);
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, D_BUFFER, 2L, 2L, List.of(1L),
                DoctorShiftAssignmentRole.PRIMARY, "Ca chieu P102", 1L);

        // Seed 1 legacy appointment chưa có reservation cho BS01 lúc 08:00-08:30 tại P101
        // và 1 reservation chéo chi nhánh CN2 cho BS01 lúc 10:00-10:30
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO appointments (appointment_id, appointment_code, branch_id, profile_id, doctor_id, service_id, assigned_room_id, appointment_date, preferred_session, expected_time, duration_minutes, reschedule_count, source, status, created_at) VALUES "
                    + "(501, 'AP-LEGACY-08', 1, 1, 1, 1, 1, '" + D_BUFFER + "', 'MORNING', '08:00:00', 30, 0, 'ONLINE', 'CONFIRMED', NOW()),"
                    + "(502, 'AP-CN2-10',    2, 2, 1, 1, 3, '" + D_BUFFER + "', 'MORNING', '10:00:00', 30, 0, 'ONLINE', 'CONFIRMED', NOW())");
            stmt.execute("INSERT INTO appointment_resource_reservations (appointment_id, branch_id, resource_type, resource_id, usage_scope, is_room_exclusive, start_at, end_at, status, created_at) "
                    + "VALUES (502, 2, 'DOCTOR', 1, 'PROCEDURE', 0, '" + D_BUFFER + " 10:00:00', '" + D_BUFFER + " 10:30:00', 'RESERVED', NOW())");
        }

        // Chặn trùng legacy appointment lúc 08:15
        assertThrows(ResponseStatusException.class, () -> bookingTransactionService.bookWithRetry(
                ResourceBookingRequest.newBooking(1L, 3L, 1L, D_BUFFER, LocalTime.of(8, 15), 1L, false, null)));

        // Chặn trùng reservation chéo chi nhánh CN2 lúc 10:15
        assertThrows(ResponseStatusException.class, () -> bookingTransactionService.bookWithRetry(
                ResourceBookingRequest.newBooking(1L, 3L, 1L, D_BUFFER, LocalTime.of(10, 15), 1L, false, null)));

        // Đặt 11:30-12:00 tại P101 -> thành công; sau đó đặt 12:10 tại P102 (vi phạm buffer 15p) -> 409; đặt 12:15 tại P102 -> thành công
        ResourceBookingResult morningLast = bookingTransactionService.bookWithRetry(
                ResourceBookingRequest.newBooking(1L, 3L, 1L, D_BUFFER, LocalTime.of(11, 30), 1L, false, null));
        assertEquals(1L, morningLast.roomId());

        assertThrows(ResponseStatusException.class, () -> bookingTransactionService.bookWithRetry(
                ResourceBookingRequest.newBooking(1L, 4L, 1L, D_BUFFER, LocalTime.of(12, 10), 1L, false, null)));

        // 10 concurrent threads gửi CÙNG 1 idempotencyKey cho cùng profile 4 lúc 12:15 -> tất cả trả về cùng 1 appointmentId
        RaceStats idemRace = runConcurrentBookings(10, idx ->
                ResourceBookingRequest.newBooking(1L, 4L, 1L, D_BUFFER, LocalTime.of(12, 15), 1L, false, "SAME-KEY-1215"));
        assertEquals(10, idemRace.successes().size(), "Idempotent concurrent requests phải thành công đồng nhất");
        assertEquals(1, idemRace.successes().stream().map(ResourceBookingResult::appointmentId).collect(Collectors.toSet()).size());
        assertEquals(9, idemRace.successes().stream().filter(ResourceBookingResult::idempotentReplay).count());
    }
}
