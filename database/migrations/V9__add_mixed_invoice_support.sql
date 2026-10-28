-- =============================================================================
-- Migration V9: Hỗ trợ Hóa đơn chung Dịch vụ và Thuốc (InvoiceType.MIXED)
-- Tương thích: MySQL 8.0+ (Schema: cv_dcms_db)
-- Đặc điểm: Idempotent (chạy nhiều lần không lỗi), bảo toàn dữ liệu
-- =============================================================================
USE cv_dcms_db;
DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v9_mixed_invoice_support$$

CREATE PROCEDURE sp_upgrade_v9_mixed_invoice_support()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- 1. BẢNG INVOICES: Bổ sung trường total_medicine_amount để tách biệt tổng tiền thuốc và tổng tiền dịch vụ
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoices' 
          AND COLUMN_NAME = 'total_medicine_amount'
    ) THEN
        SET @sql = 'ALTER TABLE invoices ADD COLUMN total_medicine_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_service_amount';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;

        -- Đối với các hóa đơn MEDICINE cũ (nếu có trước đây lưu tiền thuốc vào total_service_amount):
        -- Chuyển số tiền từ total_service_amount sang total_medicine_amount
        UPDATE invoices 
        SET total_medicine_amount = total_service_amount,
            total_service_amount = 0.00 
        WHERE invoice_type = 'MEDICINE' AND total_medicine_amount = 0.00;
    END IF;

END$$

DELIMITER ;

CALL sp_upgrade_v9_mixed_invoice_support();
DROP PROCEDURE IF EXISTS sp_upgrade_v9_mixed_invoice_support;
