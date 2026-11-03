-- =============================================================================
-- Migration V103: Tách hóa đơn dịch vụ và thuốc (Medicine & Service Invoices)
-- Bổ sung invoice_type, prescription_id và các trường thuốc cho invoice_items
-- Idempotent, an toàn cho MySQL 8.0+
-- =============================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v103_separate_invoices$$

CREATE PROCEDURE sp_upgrade_v103_separate_invoices()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- 1. Invoices
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoices' AND COLUMN_NAME = 'invoice_type'
    ) THEN
        ALTER TABLE invoices ADD COLUMN invoice_type VARCHAR(20) NOT NULL DEFAULT 'SERVICE';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoices' AND COLUMN_NAME = 'prescription_id'
    ) THEN
        ALTER TABLE invoices ADD COLUMN prescription_id BIGINT NULL;
    END IF;

    -- 2. Invoice Items
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'item_type'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN item_type VARCHAR(20) NOT NULL DEFAULT 'SERVICE';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'prescription_item_id'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN prescription_item_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'medication_id'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN medication_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'batch_id'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN batch_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'unit'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN unit VARCHAR(30) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'usage_instruction'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN usage_instruction VARCHAR(255) NULL;
    END IF;

    -- Đảm bảo service_id có thể NULL cho dòng thuốc
    ALTER TABLE invoice_items MODIFY COLUMN service_id BIGINT NULL;

END$$

DELIMITER ;

CALL sp_upgrade_v103_separate_invoices();
DROP PROCEDURE IF EXISTS sp_upgrade_v103_separate_invoices;

