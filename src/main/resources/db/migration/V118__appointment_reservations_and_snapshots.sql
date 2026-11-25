-- ====================================================================================================
-- FLYWAY MIGRATION V118: APPOINTMENT RESOURCE RESERVATIONS & DURATION SNAPSHOTS (PHASE 2 - MILESTONE 1)
-- Mục đích:
--   1. Bổ sung branches.resource_engine_enabled (mặc định 0 = OFF).
--   2. Bổ sung các cột snapshot 3 pha, assigned_room_id, assigned_chair_id, resource_status,
--      resource_conflict_note trên appointments (giữ service_id nullable cho dữ liệu lịch sử).
--   3. Tạo bảng appointment_resource_reservations với FK & index tối ưu kiểm tra giao cắt.
--   4. Backfill AN TOÀN 3 mức cho lịch tương lai:
--      - Mức 1 (Backfill đầy đủ): Đủ service_id, doctor_id, expected_time, xác định duy nhất phòng/ghế
--        (và không thiếu thiết bị REQUIRED).
--      - Mức 2 (Backfill một phần có căn cứ): Đủ service_id, doctor_id, expected_time nhưng chưa xác định
--        chắc chắn phòng/ghế/thiết bị -> chỉ giữ DOCTOR từ snapshot thật & gắn NEEDS_REASSIGNMENT.
--      - Mức 3 (Không backfill, cần điều chuyển): Thiếu service_id, doctor_id hoặc expected_time IS NULL
--        -> KHÔNG tạo reservation giả, gắn NEEDS_REASSIGNMENT.
--
-- SQL Đối soát (Reconciliation) sau Migration:
--   1. Lịch tương lai active bị bỏ sót:
--      SELECT a.appointment_id, a.status, a.expected_time, a.service_id, a.doctor_id, a.resource_status
--      FROM appointments a
--      LEFT JOIN appointment_resource_reservations r ON r.appointment_id = a.appointment_id AND r.status = 'RESERVED'
--      WHERE a.status IN ('PENDING', 'CONFIRMED') AND a.appointment_date >= CURRENT_DATE
--        AND r.reservation_id IS NULL AND (a.resource_status IS NULL OR a.resource_status <> 'NEEDS_REASSIGNMENT');
--   2. Kiểm tra không có reservation giả cho lịch thiếu service_id/doctor_id/expected_time:
--      SELECT a.appointment_id, a.service_id, a.doctor_id, a.expected_time, r.reservation_id, a.resource_status
--      FROM appointments a
--      LEFT JOIN appointment_resource_reservations r ON r.appointment_id = a.appointment_id AND r.status = 'RESERVED'
--      WHERE a.status IN ('PENDING', 'CONFIRMED') AND a.appointment_date >= CURRENT_DATE
--        AND (a.service_id IS NULL OR a.doctor_id IS NULL OR a.expected_time IS NULL)
--        AND (r.reservation_id IS NOT NULL OR a.resource_status <> 'NEEDS_REASSIGNMENT');
--   3. Phát hiện overlap lịch sử sau backfill:
--      SELECT r1.branch_id, r1.resource_type, r1.resource_id, r1.appointment_id AS appt_1, r2.appointment_id AS appt_2,
--             r1.start_at, r1.end_at
--      FROM appointment_resource_reservations r1
--      JOIN appointment_resource_reservations r2
--        ON r1.branch_id = r2.branch_id AND r1.resource_type = r2.resource_type
--       AND r1.resource_id = r2.resource_id AND r1.appointment_id < r2.appointment_id
--       AND r1.status = 'RESERVED' AND r2.status = 'RESERVED'
--       AND r1.start_at < r2.end_at AND r2.start_at < r1.end_at
--      WHERE r1.resource_type IN ('DOCTOR', 'CHAIR', 'EQUIPMENT');
-- ====================================================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v118_appointment_reservations$$

CREATE PROCEDURE sp_upgrade_v118_appointment_reservations()
BEGIN
    -- 1. Mở rộng bảng branches: Feature Toggle mặc định OFF (0)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'branches' AND column_name = 'resource_engine_enabled'
    ) THEN
        ALTER TABLE branches ADD COLUMN resource_engine_enabled TINYINT(1) NOT NULL DEFAULT 0;
    END IF;

    -- 2. Mở rộng bảng appointments (giữ service_id nullable)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'preparation_duration_minutes'
    ) THEN
        ALTER TABLE appointments ADD COLUMN preparation_duration_minutes INT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'procedure_duration_minutes'
    ) THEN
        ALTER TABLE appointments ADD COLUMN procedure_duration_minutes INT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'turnover_duration_minutes'
    ) THEN
        ALTER TABLE appointments ADD COLUMN turnover_duration_minutes INT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'is_room_exclusive_snapshot'
    ) THEN
        ALTER TABLE appointments ADD COLUMN is_room_exclusive_snapshot TINYINT(1) NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'doctor_required_in_prep_snapshot'
    ) THEN
        ALTER TABLE appointments ADD COLUMN doctor_required_in_prep_snapshot TINYINT(1) NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'doctor_required_in_turnover_snapshot'
    ) THEN
        ALTER TABLE appointments ADD COLUMN doctor_required_in_turnover_snapshot TINYINT(1) NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'assigned_room_id'
    ) THEN
        ALTER TABLE appointments ADD COLUMN assigned_room_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'assigned_chair_id'
    ) THEN
        ALTER TABLE appointments ADD COLUMN assigned_chair_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'resource_status'
    ) THEN
        ALTER TABLE appointments ADD COLUMN resource_status VARCHAR(30) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'resource_conflict_note'
    ) THEN
        ALTER TABLE appointments ADD COLUMN resource_conflict_note VARCHAR(500) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND constraint_name = 'fk_appt_assigned_room'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.key_column_usage
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'assigned_room_id' AND referenced_table_name IS NOT NULL
    ) THEN
        ALTER TABLE appointments
            ADD CONSTRAINT fk_appt_assigned_room FOREIGN KEY (assigned_room_id) REFERENCES rooms (room_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND constraint_name = 'fk_appt_assigned_chair'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.key_column_usage
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'assigned_chair_id' AND referenced_table_name IS NOT NULL
    ) THEN
        ALTER TABLE appointments
            ADD CONSTRAINT fk_appt_assigned_chair FOREIGN KEY (assigned_chair_id) REFERENCES dental_chairs (chair_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'appointments' AND index_name = 'idx_appt_resource_status'
    ) THEN
        CREATE INDEX idx_appt_resource_status ON appointments (branch_id, appointment_date, resource_status);
    END IF;

    -- 3. Tạo bảng appointment_resource_reservations
    CREATE TABLE IF NOT EXISTS appointment_resource_reservations (
        reservation_id BIGINT NOT NULL AUTO_INCREMENT,
        appointment_id BIGINT NOT NULL,
        branch_id BIGINT NOT NULL,
        resource_type VARCHAR(20) NOT NULL,
        resource_id BIGINT NOT NULL,
        usage_scope VARCHAR(20) NOT NULL,
        is_room_exclusive TINYINT(1) NOT NULL DEFAULT 0,
        start_at DATETIME NOT NULL,
        end_at DATETIME NOT NULL,
        status VARCHAR(20) NOT NULL,
        released_reason VARCHAR(50) NULL,
        released_at DATETIME NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (reservation_id),
        CONSTRAINT fk_res_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (appointment_id),
        CONSTRAINT fk_res_branch FOREIGN KEY (branch_id) REFERENCES branches (branch_id),
        INDEX idx_res_lookup (branch_id, resource_type, resource_id, status, start_at, end_at),
        INDEX idx_res_appt_status (appointment_id, status)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    -- 4a. Copy snapshot thật từ services cho lịch tương lai có service_id
    UPDATE appointments a
    JOIN services s ON s.service_id = a.service_id
    SET a.preparation_duration_minutes = s.preparation_duration_minutes,
        a.procedure_duration_minutes = s.procedure_duration_minutes,
        a.turnover_duration_minutes = s.turnover_duration_minutes,
        a.is_room_exclusive_snapshot = s.is_room_exclusive,
        a.doctor_required_in_prep_snapshot = s.doctor_required_in_prep,
        a.doctor_required_in_turnover_snapshot = s.doctor_required_in_turnover
    WHERE a.status IN ('PENDING', 'CONFIRMED')
      AND a.appointment_date >= CURDATE()
      AND a.procedure_duration_minutes IS NULL;

    -- 4b. Mức 3 (Không backfill, cần điều chuyển): thiếu service_id, doctor_id hoặc expected_time
    UPDATE appointments a
    SET a.resource_status = 'NEEDS_REASSIGNMENT',
        a.resource_conflict_note = COALESCE(
            a.resource_conflict_note,
            'Lịch cũ thiếu dịch vụ, bác sĩ hoặc giờ hẹn cụ thể (không tự tạo reservation giả)'
        )
    WHERE a.status IN ('PENDING', 'CONFIRMED')
      AND a.appointment_date >= CURDATE()
      AND (a.service_id IS NULL OR a.doctor_id IS NULL OR a.expected_time IS NULL)
      AND a.resource_status IS NULL;

    -- 4c. Mức 1/2: Tạo DOCTOR reservation từ snapshot thật cho lịch đủ service_id + doctor_id + expected_time
    INSERT INTO appointment_resource_reservations (
        appointment_id, branch_id, resource_type, resource_id, usage_scope,
        is_room_exclusive, start_at, end_at, status, created_at
    )
    SELECT
        a.appointment_id,
        a.branch_id,
        'DOCTOR',
        a.doctor_id,
        'PROCEDURE',
        0,
        TIMESTAMP(a.appointment_date, a.expected_time)
            + INTERVAL (CASE WHEN a.doctor_required_in_prep_snapshot = 1 THEN 0 ELSE COALESCE(a.preparation_duration_minutes, 0) END) MINUTE,
        TIMESTAMP(a.appointment_date, a.expected_time)
            + INTERVAL (
                COALESCE(a.preparation_duration_minutes, 0)
                + a.procedure_duration_minutes
                + CASE WHEN a.doctor_required_in_turnover_snapshot = 1 THEN COALESCE(a.turnover_duration_minutes, 0) ELSE 0 END
            ) MINUTE,
        'RESERVED',
        NOW()
    FROM appointments a
    WHERE a.status IN ('PENDING', 'CONFIRMED')
      AND a.appointment_date >= CURDATE()
      AND a.service_id IS NOT NULL
      AND a.doctor_id IS NOT NULL
      AND a.expected_time IS NOT NULL
      AND a.procedure_duration_minutes IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM appointment_resource_reservations r
          WHERE r.appointment_id = a.appointment_id
            AND r.resource_type = 'DOCTOR'
            AND r.status = 'RESERVED'
      );

    -- 4d. Mức 1 (Backfill đầy đủ): Chỉ gán ROOM/CHAIR khi xác định duy nhất không mơ hồ
    UPDATE appointments a
    JOIN services s ON s.service_id = a.service_id
    SET a.assigned_room_id = (
        SELECT dra.room_id
        FROM doctor_room_assignments dra
        JOIN rooms rm ON rm.room_id = dra.room_id
        WHERE dra.staff_id = a.doctor_id
          AND dra.branch_id = a.branch_id
          AND dra.status = 'ACTIVE'
          AND rm.status = 'ACTIVE'
        LIMIT 1
    )
    WHERE a.status IN ('PENDING', 'CONFIRMED')
      AND a.appointment_date >= CURDATE()
      AND a.service_id IS NOT NULL
      AND a.doctor_id IS NOT NULL
      AND a.expected_time IS NOT NULL
      AND a.assigned_room_id IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM service_resource_requirements srr
          WHERE srr.service_id = a.service_id AND srr.is_required = 1
      )
      AND (
          SELECT COUNT(*)
          FROM doctor_room_assignments dra
          JOIN rooms rm ON rm.room_id = dra.room_id
          WHERE dra.staff_id = a.doctor_id
            AND dra.branch_id = a.branch_id
            AND dra.status = 'ACTIVE'
            AND rm.status = 'ACTIVE'
      ) = 1
      AND (
          s.requires_chair = 0
          OR (
              SELECT COUNT(*)
              FROM dental_chairs dc
              JOIN doctor_room_assignments dra2 ON dra2.room_id = dc.room_id
              WHERE dra2.staff_id = a.doctor_id
                AND dra2.branch_id = a.branch_id
                AND dra2.status = 'ACTIVE'
                AND dc.status NOT IN ('MAINTENANCE', 'INACTIVE')
          ) = 1
      );

    UPDATE appointments a
    JOIN services s ON s.service_id = a.service_id
    SET a.assigned_chair_id = (
        SELECT dc.chair_id
        FROM dental_chairs dc
        WHERE dc.room_id = a.assigned_room_id
          AND dc.status NOT IN ('MAINTENANCE', 'INACTIVE')
        LIMIT 1
    )
    WHERE a.status IN ('PENDING', 'CONFIRMED')
      AND a.appointment_date >= CURDATE()
      AND a.assigned_room_id IS NOT NULL
      AND a.assigned_chair_id IS NULL
      AND s.requires_chair = 1
      AND (
          SELECT COUNT(*)
          FROM dental_chairs dc2
          WHERE dc2.room_id = a.assigned_room_id
            AND dc2.status NOT IN ('MAINTENANCE', 'INACTIVE')
      ) = 1;

    -- Tạo ROOM reservation cho các lịch đã xác định chắc chắn assigned_room_id
    INSERT INTO appointment_resource_reservations (
        appointment_id, branch_id, resource_type, resource_id, usage_scope,
        is_room_exclusive, start_at, end_at, status, created_at
    )
    SELECT
        a.appointment_id,
        a.branch_id,
        'ROOM',
        a.assigned_room_id,
        'FULL_STAY',
        a.is_room_exclusive_snapshot,
        TIMESTAMP(a.appointment_date, a.expected_time),
        TIMESTAMP(a.appointment_date, a.expected_time)
            + INTERVAL (COALESCE(a.preparation_duration_minutes, 0) + a.procedure_duration_minutes + COALESCE(a.turnover_duration_minutes, 0)) MINUTE,
        'RESERVED',
        NOW()
    FROM appointments a
    WHERE a.status IN ('PENDING', 'CONFIRMED')
      AND a.appointment_date >= CURDATE()
      AND a.assigned_room_id IS NOT NULL
      AND a.expected_time IS NOT NULL
      AND a.procedure_duration_minutes IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM appointment_resource_reservations r
          WHERE r.appointment_id = a.appointment_id
            AND r.resource_type = 'ROOM'
            AND r.status = 'RESERVED'
      );

    -- Tạo CHAIR reservation cho các lịch đã xác định chắc chắn assigned_chair_id
    INSERT INTO appointment_resource_reservations (
        appointment_id, branch_id, resource_type, resource_id, usage_scope,
        is_room_exclusive, start_at, end_at, status, created_at
    )
    SELECT
        a.appointment_id,
        a.branch_id,
        'CHAIR',
        a.assigned_chair_id,
        'FULL_STAY',
        0,
        TIMESTAMP(a.appointment_date, a.expected_time),
        TIMESTAMP(a.appointment_date, a.expected_time)
            + INTERVAL (COALESCE(a.preparation_duration_minutes, 0) + a.procedure_duration_minutes + COALESCE(a.turnover_duration_minutes, 0)) MINUTE,
        'RESERVED',
        NOW()
    FROM appointments a
    WHERE a.status IN ('PENDING', 'CONFIRMED')
      AND a.appointment_date >= CURDATE()
      AND a.assigned_chair_id IS NOT NULL
      AND a.expected_time IS NOT NULL
      AND a.procedure_duration_minutes IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM appointment_resource_reservations r
          WHERE r.appointment_id = a.appointment_id
            AND r.resource_type = 'CHAIR'
            AND r.status = 'RESERVED'
      );

    -- Đánh dấu OK cho lịch đủ bộ, NEEDS_REASSIGNMENT cho lịch mới backfill một phần
    UPDATE appointments a
    JOIN services s ON s.service_id = a.service_id
    SET a.resource_status = CASE
            WHEN a.assigned_room_id IS NOT NULL AND (s.requires_chair = 0 OR a.assigned_chair_id IS NOT NULL) THEN 'OK'
            ELSE 'NEEDS_REASSIGNMENT'
        END,
        a.resource_conflict_note = CASE
            WHEN a.assigned_room_id IS NOT NULL AND (s.requires_chair = 0 OR a.assigned_chair_id IS NOT NULL) THEN a.resource_conflict_note
            ELSE COALESCE(a.resource_conflict_note, 'Backfill một phần (DOCTOR); thiếu phòng/ghế/thiết bị xác định duy nhất')
        END
    WHERE a.status IN ('PENDING', 'CONFIRMED')
      AND a.appointment_date >= CURDATE()
      AND a.service_id IS NOT NULL
      AND a.doctor_id IS NOT NULL
      AND a.expected_time IS NOT NULL
      AND a.resource_status IS NULL;
END$$

CALL sp_upgrade_v118_appointment_reservations()$$
DROP PROCEDURE IF EXISTS sp_upgrade_v118_appointment_reservations$$

DELIMITER ;
