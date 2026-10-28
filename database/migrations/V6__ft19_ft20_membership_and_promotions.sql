-- =============================================================================
-- Migration V6: FT19 (Quản lý hạng thẻ & Tích điểm) và FT20 (Khuyến mãi & Voucher)
-- Tương thích: MySQL 8.0+
-- Đặc điểm: Idempotent (chạy nhiều lần không lỗi), bảo toàn dữ liệu
-- =============================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_ft19_ft20$$

CREATE PROCEDURE sp_upgrade_ft19_ft20()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- 1. Bổ sung cột 'description' cho bảng promotions nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'promotions' 
          AND COLUMN_NAME = 'description'
    ) THEN
        SET @sql = 'ALTER TABLE promotions ADD COLUMN description VARCHAR(500) AFTER promo_name';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 2. Bổ sung cột 'payment_id' cho bảng point_transactions nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'point_transactions' 
          AND COLUMN_NAME = 'payment_id'
    ) THEN
        SET @sql = 'ALTER TABLE point_transactions ADD COLUMN payment_id BIGINT AFTER invoice_id';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 3. Bổ sung khóa ngoại fk_pt_payment nếu bảng payments tồn tại
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE TABLE_SCHEMA = v_schema
          AND TABLE_NAME = 'point_transactions'
          AND CONSTRAINT_NAME = 'fk_pt_payment'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payments'
        ) THEN
            SET @sql = 'ALTER TABLE point_transactions ADD CONSTRAINT fk_pt_payment FOREIGN KEY (payment_id) REFERENCES payments(payment_id) ON DELETE SET NULL';
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END IF;
    END IF;

    -- 4. Bổ sung chỉ mục idx_pt_profile cho point_transactions
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'point_transactions' 
          AND INDEX_NAME = 'idx_pt_profile'
    ) THEN
        SET @sql = 'CREATE INDEX idx_pt_profile ON point_transactions(profile_id, created_at)';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 5. Bổ sung chỉ mục idx_pv_patient_status cho patient_vouchers
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'patient_vouchers' 
          AND INDEX_NAME = 'idx_pv_patient_status'
    ) THEN
        SET @sql = 'CREATE INDEX idx_pv_patient_status ON patient_vouchers(profile_id, status)';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 6. Đảm bảo dữ liệu các hạng thành viên chuẩn đã có sẵn
    IF EXISTS (
        SELECT 1 FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'membership_tiers'
    ) THEN
        INSERT INTO membership_tiers (tier_code, tier_name, min_total_spent, discount_percent, spend_per_point, point_value, display_order, is_active)
        SELECT 'STANDARD', 'Thành viên mới', 0.00, 0.00, 10000.00, 1000.00, 1, 1
        WHERE NOT EXISTS (SELECT 1 FROM membership_tiers WHERE tier_code = 'STANDARD');

        INSERT INTO membership_tiers (tier_code, tier_name, min_total_spent, discount_percent, spend_per_point, point_value, display_order, is_active)
        SELECT 'SILVER', 'Thành viên Bạc', 5000000.00, 5.00, 10000.00, 1000.00, 2, 1
        WHERE NOT EXISTS (SELECT 1 FROM membership_tiers WHERE tier_code = 'SILVER');

        INSERT INTO membership_tiers (tier_code, tier_name, min_total_spent, discount_percent, spend_per_point, point_value, display_order, is_active)
        SELECT 'GOLD', 'Thành viên Vàng', 20000000.00, 10.00, 10000.00, 1000.00, 3, 1
        WHERE NOT EXISTS (SELECT 1 FROM membership_tiers WHERE tier_code = 'GOLD');

        INSERT INTO membership_tiers (tier_code, tier_name, min_total_spent, discount_percent, spend_per_point, point_value, display_order, is_active)
        SELECT 'DIAMOND', 'Kim Cương VIP', 50000000.00, 15.00, 10000.00, 1000.00, 4, 1
        WHERE NOT EXISTS (SELECT 1 FROM membership_tiers WHERE tier_code = 'DIAMOND');
    END IF;

END$$

DELIMITER ;

CALL sp_upgrade_ft19_ft20();
DROP PROCEDURE IF EXISTS sp_upgrade_ft19_ft20;
