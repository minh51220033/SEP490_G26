package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.AppointmentResourceReservation;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.ClinicEquipment;
import com.cvdcms.entity.DentalChair;
import com.cvdcms.entity.Room;
import com.cvdcms.entity.enums.AppointmentResourceStatus;
import com.cvdcms.entity.enums.ChairStatus;
import com.cvdcms.entity.enums.ReservationResourceType;
import com.cvdcms.entity.enums.ReservationStatus;
import com.cvdcms.repository.AppointmentRepository;
import com.cvdcms.repository.AppointmentResourceReservationRepository;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.ClinicEquipmentRepository;
import com.cvdcms.repository.DentalChairRepository;
import com.cvdcms.repository.RoomRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("v118test")
class Phase2Milestone1V118VerificationTest {

    private static final String TARGET_VERIFY_SCHEMA = IsolatedVerifySchemaHelper.TARGET_VERIFY_SCHEMA;

    static {
        prepareIsolatedVerifySchemaBeforeSpringBoot();
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private DentalChairRepository dentalChairRepository;

    @Autowired
    private ClinicEquipmentRepository clinicEquipmentRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private AppointmentResourceReservationRepository reservationRepository;

    private static void prepareIsolatedVerifySchemaBeforeSpringBoot() {
        try (Connection conn = IsolatedVerifySchemaHelper.openVerifiedConnection();
             Statement stmt = conn.createStatement()) {
            IsolatedVerifySchemaHelper.resetAllTablesInVerifySchema(stmt);

            // 1. Run clean empty baseline DDL (0 user rows)
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "sql/v118_test_baseline_ddl.sql", ";");

            // 2. Run V117 ONCE
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V117__resource_scheduling_master_data.sql", "$$");

            // 3. Seed synthetic test fixtures ONLY (never copy real cv_dcms_db rows)
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            stmt.execute("INSERT INTO branches (branch_id, branch_code, branch_name, address, opening_time, closing_time, working_days, is_active, created_at, updated_at) "
                    + "VALUES (1, 'CS1', 'Chi nhanh Test V118', '100 Duong Test', '08:00:00', '20:00:00', '2,3,4,5,6,7,CN', b'1', NOW(), NOW())");
            stmt.execute("INSERT INTO rooms (room_id, branch_id, room_code, room_name, room_type, status, floor_label, zone_label, floor_order) "
                    + "VALUES (1, 1, 'P101', 'Phong Kham Test 1', 'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu A', 1)");
            stmt.execute("INSERT INTO dental_chairs (chair_id, branch_id, room_id, chair_code, chair_name, status) "
                    + "VALUES (1, 1, 1, 'G01', 'Ghe Test 1', 'AVAILABLE')");
            stmt.execute("INSERT INTO staff_profiles (staff_id, staff_code, full_name, status, contract_type, base_salary, allowance_amount, insurance_salary, dependent_count, has_personal_deduction, has_tax_commitment, is_tax_resident, insurance_participation_status, created_at, updated_at) "
                    + "VALUES "
                    + "(1, 'BS01', 'Bac si Test 1', 'ACTIVE', 'FULL_TIME', 10000000.00, 0.00, 10000000.00, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(2, 'BS02', 'Bac si Test 2', 'ACTIVE', 'FULL_TIME', 10000000.00, 0.00, 10000000.00, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW())");
            stmt.execute("INSERT INTO patient_profiles (profile_id, patient_code, full_name, gender, address, phone_number, deposit_balance, is_default, medical_history_completed, status, preferred_branch_id, created_at, updated_at) "
                    + "VALUES (1, 'BN01', 'Benh nhan Test', 'MALE', 'Dia chi Test', '0900000001', 0.00, b'1', b'0', 'ACTIVE', 1, NOW(), NOW())");
            stmt.execute("INSERT INTO service_categories (category_id, category_code, category_name, category_type, display_order, is_active) "
                    + "VALUES (1, 'CAT-KHAM', 'Kham tong quat', 'CONSULTATION', 1, b'1')");
            stmt.execute("INSERT INTO services (service_id, category_id, service_code, service_name, service_type, unit, base_price, surcharge_amount, estimated_duration_minutes, preparation_duration_minutes, procedure_duration_minutes, turnover_duration_minutes, requires_chair, is_room_exclusive, doctor_required_in_prep, doctor_required_in_turnover, is_default_consultation, is_bookable_online, is_paraclinical, requires_tooth, status, created_at) "
                    + "VALUES (1, 1, 'DV-KHAM-TQ', 'Kham va tu van tong quat', 'CONSULTATION', 'Lan', 100000.00, 0.00, 30, 5, 20, 5, b'1', b'0', b'0', b'0', b'1', b'1', b'0', b'0', 'ACTIVE', NOW())");
            stmt.execute("INSERT INTO doctor_room_assignments (branch_id, staff_id, room_id, is_primary, effective_from, status) "
                    + "VALUES (1, 1, 1, b'1', CURDATE(), 'ACTIVE')");

            // 5 synthetic appointments covering all 3 backfill tiers:
            // 1: Mức 1 (Full backfill -> DOCTOR + ROOM + CHAIR + OK)
            // 2: Mức 2 (Partial backfill: BS02 has no room assignment -> DOCTOR only + NEEDS_REASSIGNMENT)
            // 3: Mức 3 (Missing service_id -> 0 reservation + NEEDS_REASSIGNMENT)
            // 4: Mức 3 (Missing expected_time -> 0 reservation + NEEDS_REASSIGNMENT)
            // 5: Completed/Cancelled -> 0 reservation
            stmt.execute("INSERT INTO appointments (appointment_id, appointment_code, branch_id, profile_id, doctor_id, service_id, appointment_date, preferred_session, expected_time, duration_minutes, reschedule_count, source, status, created_at) VALUES "
                    + "(1, 'AP-FULL',   1, 1, 1, 1, DATE_ADD(CURDATE(), INTERVAL 2 DAY), 'MORNING', '09:00:00', 30, 0, 'ONLINE', 'CONFIRMED', NOW()),"
                    + "(2, 'AP-PART',   1, 1, 2, 1, DATE_ADD(CURDATE(), INTERVAL 2 DAY), 'MORNING', '10:00:00', 30, 0, 'ONLINE', 'PENDING',   NOW()),"
                    + "(3, 'AP-NOSVC',  1, 1, 1, NULL, DATE_ADD(CURDATE(), INTERVAL 2 DAY), 'MORNING', '11:00:00', 30, 0, 'ONLINE', 'CONFIRMED', NOW()),"
                    + "(4, 'AP-NOTIME', 1, 1, 1, 1, DATE_ADD(CURDATE(), INTERVAL 2 DAY), 'MORNING', NULL,       30, 0, 'ONLINE', 'CONFIRMED', NOW()),"
                    + "(5, 'AP-CANCEL', 1, 1, 1, 1, DATE_ADD(CURDATE(), INTERVAL 2 DAY), 'AFTERNOON', '14:00:00', 30, 0, 'ONLINE', 'CANCELLED', NOW())");

            // 4. Run V118 ONCE
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V118__appointment_reservations_and_snapshots.sql", "$$");

            // 5. Run V119 ONCE so Hibernate ddl-auto=validate passes for DoctorShiftAssignment & DoctorShiftReplacementLog
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V119__doctor_shift_assignments_and_replacements.sql", "$$");
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Test
    @DisplayName("1. Xác minh cô lập 100%: profile v118test, ddl-auto=validate, không kết nối cv_dcms_db")
    void verifyDatasourceStrictlyIsolatedToVerifySchema() throws Exception {
        assertTrue(Arrays.asList(environment.getActiveProfiles()).contains("v118test"),
                "Profile v118test phải được kích hoạt");
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"),
                "ddl-auto phải là validate");
        assertEquals("false", environment.getProperty("spring.flyway.enabled"),
                "flyway phải tắt trong v118test vì migration chạy có kiểm soát");

        String configuredUrl = environment.getProperty("spring.datasource.url", "");
        assertTrue(configuredUrl.contains("/" + TARGET_VERIFY_SCHEMA), "URL phải trỏ cv_dcms_v118_verify");
        assertFalse(configuredUrl.toLowerCase(Locale.ROOT).contains("cv_dcms_db"), "Tuyệt đối không được chứa cv_dcms_db");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            assertEquals(TARGET_VERIFY_SCHEMA, conn.getCatalog());
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
        }
    }

    @Test
    @DisplayName("2. Xác minh V118 DDL, FK, Index, Backfill 3 mức và SQL Reconciliation trên cv_dcms_v118_verify")
    void verifyV118DdlFkIndexBackfillAndReconciliation() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);

            // branches.resource_engine_enabled mặc định = 0 (OFF)
            try (ResultSet rs = stmt.executeQuery("SELECT resource_engine_enabled FROM branches WHERE branch_id = 1")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "branches.resource_engine_enabled mặc định phải OFF (0)");
            }

            // appointments.service_id giữ nullable
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '" + TARGET_VERIFY_SCHEMA
                            + "' AND TABLE_NAME = 'appointments' AND COLUMN_NAME = 'service_id'")) {
                assertTrue(rs.next());
                assertEquals("YES", rs.getString(1));
            }

            // FK và Index của V118
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = '" + TARGET_VERIFY_SCHEMA
                            + "' AND TABLE_NAME = 'appointment_resource_reservations' AND CONSTRAINT_TYPE = 'FOREIGN KEY'")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "appointment_resource_reservations phải có 2 FK");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = '" + TARGET_VERIFY_SCHEMA
                            + "' AND TABLE_NAME = 'appointment_resource_reservations'")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1), "Phải có PRIMARY, idx_res_lookup, idx_res_appt_status");
            }

            // Kiểm tra Backfill 3 mức
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM appointment_resource_reservations WHERE appointment_id = 1 AND status = 'RESERVED'")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1), "Mức 1 (AP-FULL) phải tạo đủ 3 reservation DOCTOR + ROOM + CHAIR");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*), MAX(resource_type) FROM appointment_resource_reservations WHERE appointment_id = 2 AND status = 'RESERVED'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Mức 2 (AP-PART) chỉ được tạo 1 DOCTOR reservation");
                assertEquals("DOCTOR", rs.getString(2));
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM appointment_resource_reservations WHERE appointment_id IN (3, 4, 5)")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Mức 3 / Cancelled không được tạo reservation giả");
            }

            // Kiểm tra 3 câu SQL Reconciliation
            try (ResultSet rs = stmt.executeQuery("""
                    SELECT COUNT(*) FROM appointments a
                    LEFT JOIN appointment_resource_reservations r ON r.appointment_id = a.appointment_id AND r.status = 'RESERVED'
                    WHERE a.status IN ('PENDING', 'CONFIRMED') AND a.appointment_date >= CURDATE()
                      AND r.reservation_id IS NULL AND (a.resource_status IS NULL OR a.resource_status <> 'NEEDS_REASSIGNMENT')
                    """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Reconciliation #1: không được có lịch tương lai bị bỏ sót");
            }
            try (ResultSet rs = stmt.executeQuery("""
                    SELECT COUNT(*) FROM appointments a
                    LEFT JOIN appointment_resource_reservations r ON r.appointment_id = a.appointment_id AND r.status = 'RESERVED'
                    WHERE a.status IN ('PENDING', 'CONFIRMED') AND a.appointment_date >= CURDATE()
                      AND (a.service_id IS NULL OR a.doctor_id IS NULL OR a.expected_time IS NULL)
                      AND (r.reservation_id IS NOT NULL OR a.resource_status <> 'NEEDS_REASSIGNMENT')
                    """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Reconciliation #2: không được có reservation giả");
            }
            try (ResultSet rs = stmt.executeQuery("""
                    SELECT COUNT(*)
                    FROM appointment_resource_reservations r1
                    JOIN appointment_resource_reservations r2
                      ON r1.branch_id = r2.branch_id AND r1.resource_type = r2.resource_type
                     AND r1.resource_id = r2.resource_id AND r1.appointment_id < r2.appointment_id
                     AND r1.status = 'RESERVED' AND r2.status = 'RESERVED'
                     AND r1.start_at < r2.end_at AND r2.start_at < r1.end_at
                    WHERE r1.resource_type IN ('DOCTOR', 'CHAIR', 'EQUIPMENT')
                    """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Reconciliation #3: không có overlap sau backfill");
            }
        }
    }

    @Test
    @Transactional
    @DisplayName("3. Xác minh Hibernate Entity mapping (validate) & PESSIMISTIC_WRITE locks theo ID tăng dần")
    void verifyEntityMappingAndPessimisticWriteLocks() {
        Branch lockedBranch = branchRepository.findByIdForUpdate(1L).orElseThrow();
        assertFalse(Boolean.TRUE.equals(lockedBranch.getResourceEngineEnabled()));

        List<Room> rooms = roomRepository.findByIdInForUpdateOrderByRoomIdAsc(List.of(1L));
        assertEquals(1, rooms.size());

        List<DentalChair> chairs = dentalChairRepository.findCandidatesByRoomIdForUpdateOrderByChairIdAsc(
                1L, List.of(ChairStatus.MAINTENANCE, ChairStatus.INACTIVE));
        assertEquals(1, chairs.size());

        List<ClinicEquipment> equipments = clinicEquipmentRepository.findByIdInForUpdateOrderByEquipmentIdAsc(List.of(-1L));
        assertTrue(equipments.isEmpty());

        List<Appointment> appts = appointmentRepository.findByIdInForUpdateOrderByAppointmentIdAsc(List.of(2L, 1L));
        assertEquals(2, appts.size());
        assertEquals(1L, appts.get(0).getAppointmentId());
        assertEquals(AppointmentResourceStatus.OK, appts.get(0).getResourceStatus());
        assertEquals(AppointmentResourceStatus.NEEDS_REASSIGNMENT, appts.get(1).getResourceStatus());

        List<AppointmentResourceReservation> lockedRes = reservationRepository
                .lockActiveByAppointmentIdsOrderByReservationIdAsc(List.of(1L), ReservationStatus.RESERVED);
        assertEquals(3, lockedRes.size());

        LocalDate targetDay = LocalDate.now().plusDays(2);
        List<AppointmentResourceReservation> overlaps = reservationRepository.findOverlappingReservations(
                1L,
                ReservationResourceType.DOCTOR,
                List.of(1L),
                ReservationStatus.RESERVED,
                LocalDateTime.of(targetDay.getYear(), targetDay.getMonth(), targetDay.getDayOfMonth(), 9, 5),
                LocalDateTime.of(targetDay.getYear(), targetDay.getMonth(), targetDay.getDayOfMonth(), 9, 25));
        assertEquals(1, overlaps.size());
    }
}
