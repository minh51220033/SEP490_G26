-- ====================================================================================================
-- FLYWAY MIGRATION V119: DOCTOR SHIFT ASSIGNMENTS & REPLACEMENT LOGS (PHASE 2 - MILESTONE 2)
-- Mục đích:
--   1. Tạo bảng doctor_shift_assignments hỗ trợ NHIỀU BÁC SĨ trong cùng một (branch, work_date, shift, room).
--   2. Bảo vệ mỗi bác sĩ chỉ có tối đa 1 phân công ACTIVE trong cùng (staff_id, work_date, shift_id)
--      thông qua unique key uk_dsa_active_staff_shift(active_slot_key), đồng thời giữ lại các bản ghi
--      ON_LEAVE / REPLACED / CANCELLED (active_slot_key = NULL) để lưu lịch sử.
--   3. Liên kết bắt buộc staff_schedule_id -> staff_schedules(schedule_id) và thống nhất khóa nhân sự staff_id.
--   4. Tạo bảng doctor_shift_replacement_logs lưu vết thay thế bác sĩ, quyền ADMIN ngoại lệ phòng,
--      số lịch hẹn chuyển giao và số lịch hẹn đánh dấu NEEDS_REASSIGNMENT.
-- ====================================================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v119_doctor_shift_assignments$$

CREATE PROCEDURE sp_upgrade_v119_doctor_shift_assignments()
BEGIN
    CREATE TABLE IF NOT EXISTS doctor_shift_assignments (
        shift_assignment_id BIGINT NOT NULL AUTO_INCREMENT,
        staff_schedule_id BIGINT NOT NULL,
        branch_id BIGINT NOT NULL,
        work_date DATE NOT NULL,
        shift_id BIGINT NOT NULL,
        room_id BIGINT NOT NULL,
        staff_id BIGINT NOT NULL,
        assignment_role VARCHAR(20) NOT NULL DEFAULT 'CO_ATTENDING',
        status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
        replaces_shift_assignment_id BIGINT NULL,
        replaced_for_staff_id BIGINT NULL,
        replacement_reason VARCHAR(500) NULL,
        replaced_by_user_id BIGINT NULL,
        leave_request_id BIGINT NULL,
        room_permission_overridden TINYINT(1) NOT NULL DEFAULT 0,
        active_slot_key VARCHAR(64) NULL,
        note VARCHAR(500) NULL,
        created_by BIGINT NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at DATETIME NULL,
        PRIMARY KEY (shift_assignment_id),
        UNIQUE KEY uk_dsa_active_staff_shift (active_slot_key),
        INDEX idx_dsa_branch_date_shift_room (branch_id, work_date, shift_id, room_id, status),
        INDEX idx_dsa_staff_date_status (staff_id, work_date, status),
        INDEX idx_dsa_schedule (staff_schedule_id),
        CONSTRAINT fk_dsa_staff_schedule FOREIGN KEY (staff_schedule_id) REFERENCES staff_schedules (schedule_id),
        CONSTRAINT fk_dsa_branch FOREIGN KEY (branch_id) REFERENCES branches (branch_id),
        CONSTRAINT fk_dsa_work_shift FOREIGN KEY (shift_id) REFERENCES work_shifts (shift_id),
        CONSTRAINT fk_dsa_room FOREIGN KEY (room_id) REFERENCES rooms (room_id),
        CONSTRAINT fk_dsa_staff FOREIGN KEY (staff_id) REFERENCES staff_profiles (staff_id),
        CONSTRAINT fk_dsa_replaces FOREIGN KEY (replaces_shift_assignment_id) REFERENCES doctor_shift_assignments (shift_assignment_id),
        CONSTRAINT fk_dsa_replaced_for_staff FOREIGN KEY (replaced_for_staff_id) REFERENCES staff_profiles (staff_id),
        CONSTRAINT fk_dsa_replaced_by_user FOREIGN KEY (replaced_by_user_id) REFERENCES users (user_id),
        CONSTRAINT fk_dsa_leave_request FOREIGN KEY (leave_request_id) REFERENCES leave_requests (leave_id),
        CONSTRAINT fk_dsa_created_by FOREIGN KEY (created_by) REFERENCES users (user_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    CREATE TABLE IF NOT EXISTS doctor_shift_replacement_logs (
        replacement_log_id BIGINT NOT NULL AUTO_INCREMENT,
        branch_id BIGINT NOT NULL,
        work_date DATE NOT NULL,
        shift_id BIGINT NOT NULL,
        room_id BIGINT NOT NULL,
        replaced_for_staff_id BIGINT NOT NULL,
        replacement_staff_id BIGINT NOT NULL,
        original_shift_assignment_id BIGINT NOT NULL,
        replacement_shift_assignment_id BIGINT NOT NULL,
        leave_request_id BIGINT NULL,
        room_permission_overridden TINYINT(1) NOT NULL DEFAULT 0,
        is_emergency TINYINT(1) NOT NULL DEFAULT 0,
        transferred_appointment_count INT NOT NULL DEFAULT 0,
        needs_reassignment_count INT NOT NULL DEFAULT 0,
        replacement_reason VARCHAR(500) NOT NULL,
        replaced_by_user_id BIGINT NOT NULL,
        replaced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (replacement_log_id),
        INDEX idx_dsrl_branch_date (branch_id, work_date),
        INDEX idx_dsrl_orig_staff (replaced_for_staff_id, work_date),
        INDEX idx_dsrl_repl_staff (replacement_staff_id, work_date),
        CONSTRAINT fk_dsrl_branch FOREIGN KEY (branch_id) REFERENCES branches (branch_id),
        CONSTRAINT fk_dsrl_shift FOREIGN KEY (shift_id) REFERENCES work_shifts (shift_id),
        CONSTRAINT fk_dsrl_room FOREIGN KEY (room_id) REFERENCES rooms (room_id),
        CONSTRAINT fk_dsrl_orig_staff FOREIGN KEY (replaced_for_staff_id) REFERENCES staff_profiles (staff_id),
        CONSTRAINT fk_dsrl_repl_staff FOREIGN KEY (replacement_staff_id) REFERENCES staff_profiles (staff_id),
        CONSTRAINT fk_dsrl_orig_assign FOREIGN KEY (original_shift_assignment_id) REFERENCES doctor_shift_assignments (shift_assignment_id),
        CONSTRAINT fk_dsrl_repl_assign FOREIGN KEY (replacement_shift_assignment_id) REFERENCES doctor_shift_assignments (shift_assignment_id),
        CONSTRAINT fk_dsrl_leave FOREIGN KEY (leave_request_id) REFERENCES leave_requests (leave_id),
        CONSTRAINT fk_dsrl_actor FOREIGN KEY (replaced_by_user_id) REFERENCES users (user_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
END$$

CALL sp_upgrade_v119_doctor_shift_assignments()$$
DROP PROCEDURE IF EXISTS sp_upgrade_v119_doctor_shift_assignments$$

DELIMITER ;
