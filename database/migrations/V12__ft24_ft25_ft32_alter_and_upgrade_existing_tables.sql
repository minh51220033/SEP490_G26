-- =====================================================================
-- CV-DCMS MIGRATION V12: NÂNG CẤP VÀ HOÀN THIỆN SCHEMA FT24, FT25, FT32
-- Bổ sung ALTER TABLE, Idempotency Key, Khóa phân bổ tạm ứng, Ràng buộc lương liên chi nhánh
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- 1. BẢNG HOÀN TIỀN (REFUNDS - FT24): Bổ sung idempotency_key và Unique Index
-- ---------------------------------------------------------------------
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_refunds_table $$
CREATE PROCEDURE upgrade_refunds_table()
BEGIN
    -- Thêm cột idempotency_key nếu chưa tồn tại
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() 
          AND TABLE_NAME = 'refunds' 
          AND COLUMN_NAME = 'idempotency_key'
    ) THEN
        ALTER TABLE refunds ADD COLUMN idempotency_key VARCHAR(100) NULL AFTER refund_code;
    END IF;

    -- Thêm chỉ mục UNIQUE uk_refund_idempotency_key nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = DATABASE() 
          AND TABLE_NAME = 'refunds' 
          AND INDEX_NAME = 'uk_refund_idempotency_key'
    ) THEN
        ALTER TABLE refunds ADD CONSTRAINT uk_refund_idempotency_key UNIQUE (idempotency_key);
    END IF;
END $$
DELIMITER ;
CALL upgrade_refunds_table();
DROP PROCEDURE IF EXISTS upgrade_refunds_table;

-- ---------------------------------------------------------------------
-- 2. BẢNG DÒNG LƯƠNG (PAYROLL_ITEMS - FT32): Ràng buộc duy nhất 1 lương/tháng xuyên chi nhánh
-- ---------------------------------------------------------------------
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_payroll_items_table $$
CREATE PROCEDURE upgrade_payroll_items_table()
BEGIN
    -- Thêm cột period_month nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() 
          AND TABLE_NAME = 'payroll_items' 
          AND COLUMN_NAME = 'period_month'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN period_month INT NULL AFTER staff_id;
    END IF;

    -- Thêm cột period_year nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() 
          AND TABLE_NAME = 'payroll_items' 
          AND COLUMN_NAME = 'period_year'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN period_year INT NULL AFTER period_month;
    END IF;

    -- Cho phép actual_work_days NULL khi chưa có dữ liệu chấm công tham khảo
    ALTER TABLE payroll_items MODIFY COLUMN actual_work_days DECIMAL(5,2) NULL DEFAULT NULL;

    -- Backfill period_month và period_year từ bảng payroll_periods
    UPDATE payroll_items pi
    JOIN payroll_periods pp ON pi.period_id = pp.period_id
    SET pi.period_month = pp.month,
        pi.period_year = pp.year
    WHERE pi.period_month IS NULL OR pi.period_year IS NULL;

    -- Ràng buộc NOT NULL sau khi đã backfill
    ALTER TABLE payroll_items MODIFY COLUMN period_month INT NOT NULL;
    ALTER TABLE payroll_items MODIFY COLUMN period_year INT NOT NULL;

    -- Thêm Unique Constraint uk_payroll_item_staff_month_year
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = DATABASE() 
          AND TABLE_NAME = 'payroll_items' 
          AND INDEX_NAME = 'uk_payroll_item_staff_month_year'
    ) THEN
        ALTER TABLE payroll_items ADD CONSTRAINT uk_payroll_item_staff_month_year UNIQUE (staff_id, period_month, period_year);
    END IF;
END $$
DELIMITER ;
CALL upgrade_payroll_items_table();
DROP PROCEDURE IF EXISTS upgrade_payroll_items_table;

-- ---------------------------------------------------------------------
-- 3. BẢNG PHÂN BỔ TẠM ỨNG LƯƠNG (SALARY_ADVANCE_ALLOCATIONS - FT32)
-- Chống cấn trừ trùng lặp một đơn tạm ứng cho nhiều kỳ lương
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS salary_advance_allocations (
    allocation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    advance_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    allocated_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    status VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_saa_advance (advance_id),
    INDEX idx_saa_item (item_id),
    CONSTRAINT fk_saa_advance FOREIGN KEY (advance_id) REFERENCES salary_advances(advance_id) ON DELETE CASCADE,
    CONSTRAINT fk_saa_item FOREIGN KEY (item_id) REFERENCES payroll_items(item_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 4. BẢNG CHÍNH SÁCH HOA HỒNG (COMMISSION_POLICIES - FT32)
-- Đảm bảo mặc định is_active = FALSE (Standby draft mode)
-- ---------------------------------------------------------------------
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_commission_policies_table $$
CREATE PROCEDURE upgrade_commission_policies_table()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() 
          AND TABLE_NAME = 'commission_policies' 
          AND COLUMN_NAME = 'is_active'
    ) THEN
        ALTER TABLE commission_policies MODIFY COLUMN is_active BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
END $$
DELIMITER ;
CALL upgrade_commission_policies_table();
DROP PROCEDURE IF EXISTS upgrade_commission_policies_table;

SET FOREIGN_KEY_CHECKS = 1;
