-- =============================================================================
-- Migration V10: Cập nhật tỷ lệ quy đổi điểm tích lũy thành viên
-- Quy chuẩn mới: 1 điểm = 10 VNĐ (100 điểm = 1.000 VNĐ)
-- Tương thích: MySQL 8.0+ (Schema: cv_dcms_db)
-- Đặc điểm: Idempotent (chạy nhiều lần không lỗi), bảo toàn cấu trúc và ID các hạng
-- =============================================================================
USE cv_dcms_db;
DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v10_update_point_conversion_rate$$

CREATE PROCEDURE sp_upgrade_v10_update_point_conversion_rate()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- 1. Cập nhật giá trị quy đổi của tất cả các hạng thành viên hiện có thành 10.00 VNĐ/điểm
    IF EXISTS (
        SELECT 1 FROM information_schema.TABLES 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'membership_tiers'
    ) THEN
        -- Cập nhật toàn bộ các hạng hiện có thành 10 VNĐ/điểm, giữ nguyên ID, min_total_spent, discount_percent
        UPDATE membership_tiers 
        SET point_value = 10.00;

        -- Đổi default value của cột point_value thành 10.00 cho các bản ghi mới về sau
        ALTER TABLE membership_tiers 
        MODIFY COLUMN point_value DECIMAL(15,2) NOT NULL DEFAULT 10.00;
    END IF;

END$$

DELIMITER ;

CALL sp_upgrade_v10_update_point_conversion_rate();
DROP PROCEDURE IF EXISTS sp_upgrade_v10_update_point_conversion_rate;
