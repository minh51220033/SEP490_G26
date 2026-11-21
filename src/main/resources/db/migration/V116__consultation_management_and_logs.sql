-- ====================================================================================================
-- FLYWAY MIGRATION V116: QUẢN LÝ YÊU CẦU TƯ VẤN GUEST TẠI LỄ TÂN (ĐỢT 2B)
-- Mục đích:
--   1. Mở rộng booking_consultation_requests (additive, giữ nguyên toàn bộ cột/constraint của V115;
--      dùng handled_by_user_id làm người phụ trách chính thức, không tạo trùng cột claimed_by_user_id).
--   2. Tạo bảng booking_consultation_logs lưu lịch sử tiếp nhận, liên hệ (không ghi đè), bàn giao,
--      chuyển đổi lịch hẹn và đóng yêu cầu.
--   3. Idempotent 100%, không sửa V107 / V114 / V115.
-- ====================================================================================================

DELIMITER $$

CREATE PROCEDURE sp_upgrade_v116_consultation_management()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'booking_consultation_requests'
          AND column_name = 'version'
    ) THEN
        ALTER TABLE booking_consultation_requests
            ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER request_key;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'booking_consultation_requests'
          AND column_name = 'claimed_at'
    ) THEN
        ALTER TABLE booking_consultation_requests
            ADD COLUMN claimed_at DATETIME(6) NULL AFTER handled_by_user_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'booking_consultation_requests'
          AND column_name = 'last_contacted_at'
    ) THEN
        ALTER TABLE booking_consultation_requests
            ADD COLUMN last_contacted_at DATETIME(6) NULL AFTER claimed_at;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'booking_consultation_requests'
          AND column_name = 'converted_at'
    ) THEN
        ALTER TABLE booking_consultation_requests
            ADD COLUMN converted_at DATETIME(6) NULL AFTER converted_appointment_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'booking_consultation_requests'
          AND column_name = 'closed_at'
    ) THEN
        ALTER TABLE booking_consultation_requests
            ADD COLUMN closed_at DATETIME(6) NULL AFTER converted_at;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'booking_consultation_requests'
          AND column_name = 'close_reason'
    ) THEN
        ALTER TABLE booking_consultation_requests
            ADD COLUMN close_reason VARCHAR(500) NULL AFTER closed_at;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'booking_consultation_requests'
          AND column_name = 'convert_request_key'
    ) THEN
        ALTER TABLE booking_consultation_requests
            ADD COLUMN convert_request_key VARCHAR(64) NULL AFTER close_reason;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'booking_consultation_requests'
          AND index_name = 'uk_bcr_convert_key'
    ) THEN
        ALTER TABLE booking_consultation_requests
            ADD CONSTRAINT uk_bcr_convert_key UNIQUE (convert_request_key);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'booking_consultation_requests'
          AND index_name = 'idx_bcr_handler_status'
    ) THEN
        CREATE INDEX idx_bcr_handler_status
            ON booking_consultation_requests (handled_by_user_id, status, updated_at);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'booking_consultation_requests'
          AND index_name = 'idx_bcr_branch_created'
    ) THEN
        CREATE INDEX idx_bcr_branch_created
            ON booking_consultation_requests (branch_id, created_at);
    END IF;
END$$

DELIMITER ;

CALL sp_upgrade_v116_consultation_management();
DROP PROCEDURE sp_upgrade_v116_consultation_management;

CREATE TABLE IF NOT EXISTS booking_consultation_logs (
    log_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    action VARCHAR(30) NOT NULL,
    old_status VARCHAR(20) NULL,
    new_status VARCHAR(20) NULL,
    contact_result VARCHAR(30) NULL,
    notes VARCHAR(1000) NULL,
    actor_user_id BIGINT NOT NULL,
    target_user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_bcl_request_created (request_id, created_at, log_id),
    CONSTRAINT fk_bcl_request FOREIGN KEY (request_id) REFERENCES booking_consultation_requests(request_id),
    CONSTRAINT fk_bcl_actor_user FOREIGN KEY (actor_user_id) REFERENCES users(user_id),
    CONSTRAINT fk_bcl_target_user FOREIGN KEY (target_user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
