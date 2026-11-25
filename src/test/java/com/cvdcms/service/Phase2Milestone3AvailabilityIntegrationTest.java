package com.cvdcms.service;

import com.cvdcms.DTO.availability.AvailabilityCheckRequest;
import com.cvdcms.DTO.availability.AvailableResourceOption;
import com.cvdcms.DTO.availability.ResourceAvailabilityResult;
import com.cvdcms.entity.DoctorShiftAssignment;
import com.cvdcms.entity.enums.DoctorShiftAssignmentRole;
import com.cvdcms.repository.AppointmentRepository;
import com.cvdcms.repository.AppointmentResourceReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("v118test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase2Milestone3AvailabilityIntegrationTest {

    private static final LocalDate FUTURE_DATE = LocalDate.now().plusDays(2);
    private static final LocalDate TODAY = LocalDate.now();

    static {
        prepareIsolatedVerifySchemaBeforeSpringBoot();
    }

    @Autowired private DataSource dataSource;
    @Autowired private ResourceAvailabilityService availabilityService;
    @Autowired private DoctorShiftAssignmentService doctorShiftAssignmentService;
    @Autowired private AppointmentResourceReservationRepository reservationRepository;
    @Autowired private AppointmentRepository appointmentRepository;

    private static void prepareIsolatedVerifySchemaBeforeSpringBoot() {
        try (Connection conn = IsolatedVerifySchemaHelper.openVerifiedConnection();
             Statement stmt = conn.createStatement()) {
            IsolatedVerifySchemaHelper.resetAllTablesInVerifySchema(stmt);
            IsolatedVerifySchemaHelper.assertConnectedDatabaseAllowlisted(stmt);
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "sql/v118_test_baseline_ddl.sql", ";");
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V117__resource_scheduling_master_data.sql", "$$");

            // Seed chi nhánh, phòng (multi-chair P101, P102, XRAY 0-chair P104, P201 ở CN2), ghế (AVAILABLE, IN_USE, TURNOVER), thiết bị
            stmt.execute("INSERT INTO branches (branch_id, branch_code, branch_name, address, opening_time, closing_time, working_days, is_active, created_at, updated_at) VALUES "
                    + "(1, 'CS1', 'Chi nhanh M3-1', '100 Duong M3', '08:00:00', '21:00:00', '2,3,4,5,6,7,CN', b'1', NOW(), NOW()),"
                    + "(2, 'CS2', 'Chi nhanh M3-2', '200 Duong M3', '08:00:00', '21:00:00', '2,3,4,5,6,7,CN', b'1', NOW(), NOW())");

            stmt.execute("INSERT INTO rooms (room_id, branch_id, room_code, room_name, room_type, status, floor_label, zone_label, floor_order) VALUES "
                    + "(1, 1, 'P101', 'Phong Dieu Tri 101', 'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu A', 1),"
                    + "(2, 1, 'P102', 'Phong Dieu Tri 102', 'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu B', 1),"
                    + "(3, 1, 'P103', 'Phong Ghe Ban 103',  'TREATMENT', 'ACTIVE', 'Tang 2', 'Khu A', 2),"
                    + "(4, 1, 'P104', 'Phong X-Quang 104',  'XRAY',      'ACTIVE', 'Tang 1', 'Khu X', 1),"
                    + "(5, 2, 'P201', 'Phong CN2 201',      'TREATMENT', 'ACTIVE', 'Tang 1', 'Khu A', 1)");

            // P101 có 2 ghế AVAILABLE; P102 có 1 ghế AVAILABLE; P103 có 2 ghế đang IN_USE và TURNOVER; P104 XRAY 0 ghế; P201 1 ghế AVAILABLE
            stmt.execute("INSERT INTO dental_chairs (chair_id, branch_id, room_id, chair_code, chair_name, status) VALUES "
                    + "(1, 1, 1, 'G101-A', 'Ghe 101A', 'AVAILABLE'),"
                    + "(2, 1, 1, 'G101-B', 'Ghe 101B', 'AVAILABLE'),"
                    + "(3, 1, 2, 'G102-A', 'Ghe 102A', 'AVAILABLE'),"
                    + "(4, 1, 3, 'G103-A', 'Ghe 103A', 'IN_USE'),"
                    + "(5, 1, 3, 'G103-B', 'Ghe 103B', 'TURNOVER'),"
                    + "(6, 2, 5, 'G201-A', 'Ghe 201A', 'AVAILABLE')");

            stmt.execute("INSERT INTO work_shifts (shift_id, branch_id, shift_code, shift_name, start_time, end_time, is_active) VALUES "
                    + "(1, 1, 'SANG',  'Ca Sang',  '08:00:00', '12:00:00', b'1'),"
                    + "(2, 1, 'CHIEU', 'Ca Chieu', '12:00:00', '17:00:00', b'1'),"
                    + "(3, 2, 'SANG2', 'Ca Sang CN2', '08:00:00', '12:00:00', b'1')");

            stmt.execute("INSERT INTO roles (role_id, role_name, display_name, is_active, is_system) VALUES "
                    + "(1, 'ADMIN',  'Quan tri vien', b'1', b'1'),"
                    + "(2, 'BAC_SI', 'Bac si',        b'1', b'1')");

            stmt.execute("INSERT INTO users (user_id, username, password, email, phone_number, user_type, branch_id, is_active, status, custom_permissions_enabled, created_date, updated_at) VALUES "
                    + "(1, 'admin_m3', '$2a$10$dummyhash', 'admin_m3@test.local', '0900002001', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(2, 'bs01_m3',  '$2a$10$dummyhash', 'bs01_m3@test.local',  '0900002002', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(3, 'bs02_m3',  '$2a$10$dummyhash', 'bs02_m3@test.local',  '0900002003', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW()),"
                    + "(4, 'bs03_m3',  '$2a$10$dummyhash', 'bs03_m3@test.local',  '0900002004', 'STAFF', 1, b'1', 'ACTIVE', b'0', NOW(), NOW())");

            stmt.execute("INSERT INTO user_roles (user_id, role_id) VALUES (1,1),(2,2),(3,2),(4,2)");

            stmt.execute("INSERT INTO staff_profiles (staff_id, user_id, staff_code, full_name, specialty, status, contract_type, base_salary, allowance_amount, insurance_salary, dependent_count, has_personal_deduction, has_tax_commitment, is_tax_resident, insurance_participation_status, created_at, updated_at) VALUES "
                    + "(1, 2, 'BS01', 'BS Nguyen A', 'Tong quat', 'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(2, 3, 'BS02', 'BS Tran B',   'Nha chu',   'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),"
                    + "(3, 4, 'BS03', 'BS Le C',     'X-Quang',   'ACTIVE', 'FULL_TIME', 12000000, 0, 12000000, 0, b'0', b'0', b'1', 'PARTICIPATING', NOW(), NOW())");

            stmt.execute("INSERT INTO staff_branch_assignments (branch_id, staff_id, role_id, is_primary, start_date, status, created_at) VALUES "
                    + "(1, 1, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(2, 1, 2, b'0', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(1, 2, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW()),"
                    + "(1, 3, 2, b'1', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW())");

            stmt.execute("INSERT INTO doctor_room_assignments (branch_id, staff_id, room_id, is_primary, effective_from, status) VALUES "
                    + "(1, 1, 1, b'1', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 1, 2, b'0', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 1, 3, b'0', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(2, 1, 5, b'0', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 2, 1, b'1', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 3, 4, b'1', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE')");

            stmt.execute("INSERT INTO patient_profiles (profile_id, patient_code, full_name, gender, address, phone_number, deposit_balance, is_default, medical_history_completed, status, preferred_branch_id, created_at, updated_at) "
                    + "VALUES (1, 'BN-M3-1', 'Benh nhan M3', 'MALE', 'Ha Noi', '0911000003', 0.00, b'1', b'0', 'ACTIVE', 1, NOW(), NOW())");

            stmt.execute("INSERT INTO service_categories (category_id, category_code, category_name, category_type, display_order, is_active) VALUES "
                    + "(1, 'CAT-M3', 'Dich vu M3', 'PROCEDURE', 1, b'1')");

            // 1: Kham thuong (30p, ko doc quyen, ko XRAY)
            // 2: Tieu phau doc quyen (PREP=10, PROC=20, TURN=10, is_room_exclusive=1, doc_in_prep=0, doc_in_turn=0)
            // 3: Tieu phau khoa BS ca PREP/TURN (PREP=10, PROC=20, TURN=10, doc_in_prep=1, doc_in_turn=1)
            // 4: Chup X-Quang NanoPix (20p, requires_chair=0, yeu cau ROOM_TYPE=XRAY + EQUIPMENT_TYPE=XRAY FIXED_ROOM P104)
            // 5: Laser chia se (20p, yeu cau EQUIPMENT_TYPE=LASER BRANCH_SHARED)
            stmt.execute("INSERT INTO services (service_id, category_id, service_code, service_name, service_type, unit, base_price, surcharge_amount, estimated_duration_minutes, preparation_duration_minutes, procedure_duration_minutes, turnover_duration_minutes, requires_chair, is_room_exclusive, doctor_required_in_prep, doctor_required_in_turnover, is_default_consultation, is_bookable_online, is_paraclinical, requires_tooth, status, created_at) VALUES "
                    + "(1, 1, 'DV-KHAM-TQ',  'Kham tong quat',       'CONSULTATION', 'Lan',  100000, 0, 30, 0,  30, 0,  b'1', b'0', b'0', b'0', b'1', b'1', b'0', b'0', 'ACTIVE', NOW()),"
                    + "(2, 1, 'DV-DOC-QUYEN','Tieu phau doc quyen',  'PROCEDURE',    'Lan',  500000, 0, 40, 10, 20, 10, b'1', b'1', b'0', b'0', b'0', b'1', b'0', b'0', 'ACTIVE', NOW()),"
                    + "(3, 1, 'DV-BS-ALL',   'Tieu phau khoa BS',    'PROCEDURE',    'Lan',  500000, 0, 40, 10, 20, 10, b'1', b'0', b'1', b'1', b'0', b'1', b'0', b'0', 'ACTIVE', NOW()),"
                    + "(4, 1, 'DV-XRAY',     'Chup X-Quang NanoPix', 'XRAY',         'Phim', 200000, 0, 20, 0,  20, 0,  b'0', b'1', b'0', b'0', b'0', b'1', b'1', b'0', 'ACTIVE', NOW()),"
                    + "(5, 1, 'DV-LASER',    'Dieu tri Laser',       'PROCEDURE',    'Lan',  400000, 0, 20, 0,  20, 0,  b'1', b'0', b'0', b'0', b'0', b'1', b'0', b'0', 'ACTIVE', NOW())");

            stmt.execute("INSERT INTO service_resource_requirements (service_id, resource_kind, room_type, equipment_type, usage_phase, is_required) VALUES "
                    + "(1, 'ROOM_TYPE', 'TREATMENT', NULL, 'ALL', b'1'),"
                    + "(2, 'ROOM_TYPE', 'TREATMENT', NULL, 'ALL', b'1'),"
                    + "(3, 'ROOM_TYPE', 'TREATMENT', NULL, 'ALL', b'1'),"
                    + "(4, 'ROOM_TYPE', 'XRAY', NULL, 'ALL', b'1'),"
                    + "(4, 'EQUIPMENT_TYPE', NULL, 'XRAY', 'PROCEDURE', b'1'),"
                    + "(5, 'ROOM_TYPE', 'TREATMENT', NULL, 'ALL', b'1'),"
                    + "(5, 'EQUIPMENT_TYPE', NULL, 'LASER', 'PROCEDURE', b'1')");

            // Thiet bi: NanoPix FIXED_ROOM P104 (ACTIVE), Laser BRANCH_SHARED (ACTIVE), May cho xac minh PENDING_VERIFICATION, May bao tri MAINTENANCE
            stmt.execute("INSERT INTO clinic_equipments (equipment_id, branch_id, allocation_mode, fixed_room_id, current_room_id, equipment_code, equipment_name, equipment_type, device_serial_number, status) VALUES "
                    + "(1, 1, 'FIXED_ROOM',    4, 4,    'NPX-01', 'May NanoPix P104',     'XRAY',  'SN-NPX-01', 'ACTIVE'),"
                    + "(2, 1, 'BRANCH_SHARED', NULL, 1, 'LSR-01', 'May Laser Di Dong 01', 'LASER', 'SN-LSR-01', 'ACTIVE'),"
                    + "(3, 1, 'BRANCH_SHARED', NULL, 1, 'LSR-02', 'May Laser Cho Duyet',  'LASER', 'SN-LSR-02', 'PENDING_VERIFICATION'),"
                    + "(4, 1, 'BRANCH_SHARED', NULL, 1, 'LSR-03', 'May Laser Bao Tri',    'LASER', 'SN-LSR-03', 'MAINTENANCE')");

            // Quyền dịch vụ: BS01 đủ cả 1,2,3,5; BS02 chỉ có dịch vụ 1 (thiếu 2,3,5); BS03 có dịch vụ 4 (XRAY)
            stmt.execute("INSERT INTO doctor_service_permissions (staff_id, service_id, branch_id, effective_from, status) VALUES "
                    + "(1, 1, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 2, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 3, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(1, 5, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(2, 1, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE'),"
                    + "(2, 2, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'PENDING_REVIEW'),"
                    + "(3, 4, 1, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ACTIVE')");

            // Rule di chuyển P101 -> P102 = 15 phút
            stmt.execute("INSERT INTO branch_room_transition_rules (branch_id, rule_scope, from_room_id, to_room_id, travel_buffer_minutes, is_active, rule_signature, active_guard) VALUES "
                    + "(1, 'SPECIFIC_PAIR', 1, 2, 15, b'1', 'SPECIFIC_PAIR:1:2', 1)");

            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V118__appointment_reservations_and_snapshots.sql", "$$");
            IsolatedVerifySchemaHelper.executeSqlScriptStatements(stmt, "db/migration/V119__doctor_shift_assignments_and_replacements.sql", "$$");
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. Nhiều bác sĩ trong phòng P101, lọc DoctorServicePermission ACTIVE/PENDING_REVIEW & không ghi reservation")
    void multiDoctorSameRoomServicePermissionAndStrictlyReadOnly() {
        // P101 ca Sáng FUTURE_DATE có BS01 và BS02; P104 ca Sáng có BS03
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, FUTURE_DATE, 1L, 1L, List.of(1L, 2L),
                DoctorShiftAssignmentRole.CO_ATTENDING, "Ca sang M3", 1L);
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, FUTURE_DATE, 1L, 4L, List.of(3L),
                DoctorShiftAssignmentRole.PRIMARY, "Ca X-Quang M3", 1L);

        long resCountBefore = reservationRepository.count();
        long apptCountBefore = appointmentRepository.count();

        // Dịch vụ 1 (DV-KHAM-TQ): cả BS01 và BS02 đều khả dụng tại P101, không đụng NanoPix/P104
        ResourceAvailabilityResult r1 = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forSlot(1L, 1L, FUTURE_DATE, LocalTime.of(9, 0)));
        assertTrue(r1.available());
        assertEquals(2, r1.options().size());
        assertTrue(r1.options().stream().allMatch(o -> o.roomId().equals(1L) && o.equipmentIds().isEmpty()));

        // Dịch vụ 2 (DV-DOC-QUYEN): BS02 chỉ có PENDING_REVIEW nên bị loại, chỉ còn BS01
        ResourceAvailabilityResult r2 = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forSlot(1L, 2L, FUTURE_DATE, LocalTime.of(9, 0)));
        assertTrue(r2.available());
        assertEquals(1, r2.options().size());
        assertEquals(1L, r2.options().get(0).doctorId());

        // Kiểm tra đích danh BS02 cho DV 2 -> DOCTOR_INELIGIBLE_SERVICE
        ResourceAvailabilityResult r2Bs02 = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forDoctorSlot(1L, 2L, FUTURE_DATE, LocalTime.of(9, 0), 2L));
        assertFalse(r2Bs02.available());
        assertEquals("DOCTOR_INELIGIBLE_SERVICE", r2Bs02.reasonCode());

        assertEquals(resCountBefore, reservationRepository.count(), "M3 không được ghi reservation");
        assertEquals(apptCountBefore, appointmentRepository.count(), "M3 không được ghi appointment");
    }

    @Test
    @Order(2)
    @DisplayName("2. Thiết bị FIXED_ROOM (NanoPix P104), BRANCH_SHARED (Laser), loại PENDING_VERIFICATION/MAINTENANCE")
    void equipmentFixedRoomBranchSharedAndVerificationStatus() {
        // Dịch vụ 4 (XRAY): chọn BS03 tại P104 (0 ghế) + máy NPX-01 (id=1)
        ResourceAvailabilityResult xrayRes = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forSlot(1L, 4L, FUTURE_DATE, LocalTime.of(9, 0)));
        assertTrue(xrayRes.available());
        AvailableResourceOption xrayOpt = xrayRes.options().get(0);
        assertEquals(4L, xrayOpt.roomId());
        assertNull(xrayOpt.chairId());
        assertEquals(List.of(1L), xrayOpt.equipmentIds());

        // Dịch vụ 5 (LASER): chọn BS01 tại P101 + máy BRANCH_SHARED ACTIVE duy nhất LSR-01 (id=2), loại LSR-02/LSR-03
        ResourceAvailabilityResult laserRes = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forSlot(1L, 5L, FUTURE_DATE, LocalTime.of(9, 0)));
        assertTrue(laserRes.available());
        assertEquals(List.of(2L), laserRes.options().get(0).equipmentIds());

        // Nếu LSR-01 (id=2) đang bị giữ RESERVED [09:00, 09:20) thì không được tự dùng LSR-02 (PENDING) hay LSR-03 (MAINTENANCE)
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO appointments (appointment_id, appointment_code, branch_id, profile_id, doctor_id, service_id, assigned_room_id, appointment_date, preferred_session, expected_time, duration_minutes, reschedule_count, source, status, created_at) "
                    + "VALUES (201, 'AP-LSR-BUSY', 1, 1, 2, 5, 1, '" + FUTURE_DATE + "', 'MORNING', '09:00:00', 20, 0, 'ONLINE', 'CONFIRMED', NOW())");
            stmt.execute("INSERT INTO appointment_resource_reservations (appointment_id, branch_id, resource_type, resource_id, usage_scope, is_room_exclusive, start_at, end_at, status, created_at) "
                    + "VALUES (201, 1, 'EQUIPMENT', 2, 'PROCEDURE', 0, '" + FUTURE_DATE + " 09:00:00', '" + FUTURE_DATE + " 09:20:00', 'RESERVED', NOW())");
        } catch (Exception e) {
            fail(e);
        }

        ResourceAvailabilityResult laserBusy = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forSlot(1L, 5L, FUTURE_DATE, LocalTime.of(9, 0)));
        assertFalse(laserBusy.available());
        assertEquals("MISSING_REQUIRED_EQUIPMENT", laserBusy.reasonCode());

        // Chạm biên [09:20, 09:40) sau khi LSR-01 xong lúc 09:20 -> khả dụng!
        ResourceAvailabilityResult laserNext = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forSlot(1L, 5L, FUTURE_DATE, LocalTime.of(9, 20)));
        assertTrue(laserNext.available());
    }

    @Test
    @Order(3)
    @DisplayName("3. Phòng nhiều ghế P101 (2 ghế), phòng độc quyền, chạm biên [start, end), PREP/TURNOVER & doctor flags")
    void multiChairExclusivityHalfOpenAndThreePhaseDoctorFlags() {
        // Seed 1 lịch khám thường của BS02 tại P101 ghế G101-A (id=1) lúc 10:00-10:30
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO appointments (appointment_id, appointment_code, branch_id, profile_id, doctor_id, service_id, assigned_room_id, assigned_chair_id, appointment_date, preferred_session, expected_time, duration_minutes, reschedule_count, source, status, created_at) "
                    + "VALUES (202, 'AP-P101-G1', 1, 1, 2, 1, 1, 1, '" + FUTURE_DATE + "', 'MORNING', '10:00:00', 30, 0, 'ONLINE', 'CONFIRMED', NOW())");
            stmt.execute("INSERT INTO appointment_resource_reservations (appointment_id, branch_id, resource_type, resource_id, usage_scope, is_room_exclusive, start_at, end_at, status, created_at) VALUES "
                    + "(202, 1, 'DOCTOR', 2, 'PROCEDURE', 0, '" + FUTURE_DATE + " 10:00:00', '" + FUTURE_DATE + " 10:30:00', 'RESERVED', NOW()),"
                    + "(202, 1, 'ROOM',   1, 'FULL_STAY', 0, '" + FUTURE_DATE + " 10:00:00', '" + FUTURE_DATE + " 10:30:00', 'RESERVED', NOW()),"
                    + "(202, 1, 'CHAIR',  1, 'FULL_STAY', 0, '" + FUTURE_DATE + " 10:00:00', '" + FUTURE_DATE + " 10:30:00', 'RESERVED', NOW())");
        } catch (Exception e) {
            fail(e);
        }

        // BS01 vẫn đặt được khám thường (DV 1) lúc 10:00 trên ghế còn lại G101-B (id=2)
        ResourceAvailabilityResult secondChair = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forDoctorSlot(1L, 1L, FUTURE_DATE, LocalTime.of(10, 0), 1L));
        assertTrue(secondChair.available());
        assertEquals(2L, secondChair.options().get(0).chairId());

        // Nhưng đặt dịch vụ ĐỘC QUYỀN (DV 2) lúc 10:00 tại P101 -> ROOM_EXCLUSIVE_CONFLICT vì P101 đang có ghế bận!
        ResourceAvailabilityResult exclusiveBlocked = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forDoctorSlot(1L, 2L, FUTURE_DATE, LocalTime.of(10, 0), 1L));
        assertFalse(exclusiveBlocked.available());
        assertEquals("ROOM_EXCLUSIVE_CONFLICT", exclusiveBlocked.reasonCode());

        // Chạm biên [10:30, 11:00) trên ghế G101-A (id=1) -> không giao cắt với [10:00, 10:30)!
        ResourceAvailabilityResult boundaryRes = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forDoctorSlot(1L, 1L, FUTURE_DATE, LocalTime.of(10, 30), 2L));
        assertTrue(boundaryRes.available());
        assertEquals(1L, boundaryRes.options().get(0).chairId());

        // Seed lịch BS01 [10:00, 10:30) (không chiếm P101) để test cờ doctorRequiredInPrep=true ở DV 3 lúc 10:20
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO appointments (appointment_id, appointment_code, branch_id, profile_id, doctor_id, service_id, appointment_date, preferred_session, expected_time, duration_minutes, reschedule_count, source, status, created_at) "
                    + "VALUES (205, 'AP-BS01-PREP', 1, 1, 1, 1, '" + FUTURE_DATE + "', 'MORNING', '10:00:00', 30, 0, 'ONLINE', 'CONFIRMED', NOW())");
            stmt.execute("INSERT INTO appointment_resource_reservations (appointment_id, branch_id, resource_type, resource_id, usage_scope, is_room_exclusive, start_at, end_at, status, created_at) "
                    + "VALUES (205, 1, 'DOCTOR', 1, 'PROCEDURE', 0, '" + FUTURE_DATE + " 10:00:00', '" + FUTURE_DATE + " 10:30:00', 'RESERVED', NOW())");
        } catch (Exception e) {
            fail(e);
        }

        // Đặt lúc 10:20 với DV 3 (PREP=10, doctorRequiredInPrep=true) -> BS01 bận [10:20, 11:00) đụng [10:00, 10:30) -> DOCTOR_TIME_CONFLICT!
        ResourceAvailabilityResult docInPrepConflict = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forDoctorSlot(1L, 3L, FUTURE_DATE, LocalTime.of(10, 20), 1L));
        assertFalse(docInPrepConflict.available());
        assertEquals("DOCTOR_TIME_CONFLICT", docInPrepConflict.reasonCode());
    }

    @Test
    @Order(4)
    @DisplayName("4. Buffer di chuyển giữa P101 -> P102 (15p), nghỉ phép APPROVED, ghế IN_USE/TURNOVER ca tức thời")
    void travelBufferLeaveAndChairOperationalStatus() {
        // Seed ca Sáng hôm nay (TODAY) cho BS01 tại P103 (2 ghế đang IN_USE & TURNOVER) để test ca tức thời
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, TODAY, 1L, 3L, List.of(1L),
                DoctorShiftAssignmentRole.PRIMARY, "Test ghe IN_USE/TURNOVER", 1L);

        // Ca tức thời (immediateDispatch=true) lúc 09:00 hôm nay tại P103 -> CHAIR_BUSY_IN_USE_OR_TURNOVER (không tự giải phóng ghế)
        // Lưu ý: dùng khung giờ nằm trong ca Sáng (08:00-12:00), immediateDispatch=true
        ResourceAvailabilityResult immediateBusy = availabilityService.checkAvailability(
                new AvailabilityCheckRequest(1L, 1L, TODAY, LocalTime.of(9, 0), 1L, null, true));
        assertFalse(immediateBusy.available());
        assertEquals("CHAIR_BUSY_IN_USE_OR_TURNOVER", immediateBusy.reasonCode());

        // Test buffer di chuyển P101 -> P102 (15 phút) vào ngày FUTURE_DATE + 1
        LocalDate bufferDay = FUTURE_DATE.plusDays(1);
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, bufferDay, 1L, 1L, List.of(1L),
                DoctorShiftAssignmentRole.PRIMARY, "Ca sang P101", 1L);
        doctorShiftAssignmentService.assignDoctorsToRoom(
                1L, bufferDay, 2L, 2L, List.of(1L),
                DoctorShiftAssignmentRole.PRIMARY, "Ca chieu P102", 1L);

        // BS01 có lịch tại P101 kết thúc 12:00 ([11:30, 12:00))
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO appointments (appointment_id, appointment_code, branch_id, profile_id, doctor_id, service_id, assigned_room_id, assigned_chair_id, appointment_date, preferred_session, expected_time, duration_minutes, reschedule_count, source, status, created_at) "
                    + "VALUES (203, 'AP-BUF-P101', 1, 1, 1, 1, 1, 1, '" + bufferDay + "', 'MORNING', '11:30:00', 30, 0, 'ONLINE', 'CONFIRMED', NOW())");
            stmt.execute("INSERT INTO appointment_resource_reservations (appointment_id, branch_id, resource_type, resource_id, usage_scope, is_room_exclusive, start_at, end_at, status, created_at) VALUES "
                    + "(203, 1, 'DOCTOR', 1, 'PROCEDURE', 0, '" + bufferDay + " 11:30:00', '" + bufferDay + " 12:00:00', 'RESERVED', NOW()),"
                    + "(203, 1, 'ROOM',   1, 'FULL_STAY', 0, '" + bufferDay + " 11:30:00', '" + bufferDay + " 12:00:00', 'RESERVED', NOW()),"
                    + "(203, 1, 'CHAIR',  1, 'FULL_STAY', 0, '" + bufferDay + " 11:30:00', '" + bufferDay + " 12:00:00', 'RESERVED', NOW())");
        } catch (Exception e) {
            fail(e);
        }

        // Đặt ca chiều P102 lúc 12:10 -> cách 10p < buffer 15p -> DOCTOR_TRAVEL_BUFFER_CONFLICT
        ResourceAvailabilityResult bufBlocked = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forDoctorSlot(1L, 1L, bufferDay, LocalTime.of(12, 10), 1L));
        assertFalse(bufBlocked.available());
        assertEquals("DOCTOR_TRAVEL_BUFFER_CONFLICT", bufBlocked.reasonCode());

        // Đặt ca chiều P102 lúc 12:15 -> đủ buffer 15p -> khả dụng!
        ResourceAvailabilityResult bufOk = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forDoctorSlot(1L, 1L, bufferDay, LocalTime.of(12, 15), 1L));
        assertTrue(bufOk.available());
        assertEquals(2L, bufOk.options().get(0).roomId());

        // BS01 có LeaveRequest APPROVED cho ca Sáng bufferDay -> bị loại khi check giờ sáng, ca chiều vẫn OK
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO leave_requests (leave_id, staff_id, branch_id, shift_id, leave_type, is_emergency, start_date, end_date, reason, status, submitted_at) "
                    + "VALUES (10, 1, 1, 1, 'PERSONAL', b'0', '" + bufferDay + "', '" + bufferDay + "', 'Nghi da duyet', 'APPROVED', NOW())");
        } catch (Exception e) {
            fail(e);
        }

        ResourceAvailabilityResult onLeave = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forDoctorSlot(1L, 1L, bufferDay, LocalTime.of(9, 0), 1L));
        assertFalse(onLeave.available());
        assertEquals("DOCTOR_ON_LEAVE", onLeave.reasonCode());

        // Cross-branch / cross-room: BS01 có reservation ở CN2 [09:00, 09:30) vào FUTURE_DATE -> không thể được chọn ở CN1 lúc 09:15
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO appointments (appointment_id, appointment_code, branch_id, profile_id, doctor_id, service_id, assigned_room_id, appointment_date, preferred_session, expected_time, duration_minutes, reschedule_count, source, status, created_at) "
                    + "VALUES (204, 'AP-CN2-OVERLAP', 2, 1, 1, 1, 5, '" + FUTURE_DATE + "', 'MORNING', '09:00:00', 30, 0, 'ONLINE', 'CONFIRMED', NOW())");
            stmt.execute("INSERT INTO appointment_resource_reservations (appointment_id, branch_id, resource_type, resource_id, usage_scope, is_room_exclusive, start_at, end_at, status, created_at) "
                    + "VALUES (204, 2, 'DOCTOR', 1, 'PROCEDURE', 0, '" + FUTURE_DATE + " 09:00:00', '" + FUTURE_DATE + " 09:30:00', 'RESERVED', NOW())");
        } catch (Exception e) {
            fail(e);
        }

        ResourceAvailabilityResult crossBranchBlocked = availabilityService.checkAvailability(
                AvailabilityCheckRequest.forDoctorSlot(1L, 1L, FUTURE_DATE, LocalTime.of(9, 15), 1L));
        assertFalse(crossBranchBlocked.available());
        assertEquals("DOCTOR_TIME_CONFLICT", crossBranchBlocked.reasonCode());

        // Xác nhận cuối cùng: không có bất kỳ reservation mới nào do M3 tự ghi (1 + 3 + 1 + 3 + 1 = 9)
        assertEquals(9L, reservationRepository.count(), "Chỉ đúng 9 reservation đã seed từ test, M3 không tạo thêm");
    }
}
