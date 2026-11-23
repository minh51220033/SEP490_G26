-- ====================================================================================================
-- FLYWAY MIGRATION V123: BO SUNG COT CHU KY DIEN TU CHO CAC CHUNG TU KE TOAN / TAI CHINH
-- Tai su dung co che luu file PNG /uploads/signatures/** dung chung cua he thong.
-- ====================================================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v123_finance_signatures$$
CREATE PROCEDURE sp_upgrade_v123_finance_signatures()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema AND table_name = 'operating_expenses' AND column_name = 'signature_url'
    ) THEN
        ALTER TABLE operating_expenses ADD COLUMN signature_url VARCHAR(500) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema AND table_name = 'invoices' AND column_name = 'signature_url'
    ) THEN
        ALTER TABLE invoices ADD COLUMN signature_url VARCHAR(500) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema AND table_name = 'payments' AND column_name = 'signature_url'
    ) THEN
        ALTER TABLE payments ADD COLUMN signature_url VARCHAR(500) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema AND table_name = 'refunds' AND column_name = 'signature_url'
    ) THEN
        ALTER TABLE refunds ADD COLUMN signature_url VARCHAR(500) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema AND table_name = 'supplier_payments' AND column_name = 'signature_url'
    ) THEN
        ALTER TABLE supplier_payments ADD COLUMN signature_url VARCHAR(500) NULL;
    END IF;
END$$

DELIMITER ;

CALL sp_upgrade_v123_finance_signatures();
