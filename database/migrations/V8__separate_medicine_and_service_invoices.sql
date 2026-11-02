-- =============================================================================
-- Migration V8: Tách riêng Hóa đơn Dịch vụ và Hóa đơn Thuốc (CV-DCMS)
-- Tương thích: MySQL 8.0+ (Schema: cv_dcms_db)
-- Đặc điểm: Idempotent (chạy nhiều lần không lỗi), bảo toàn dữ liệu, an toàn tuyệt đối
-- =============================================================================
USE cv_dcms_db;
DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v8_medicine_service_invoices$$

CREATE PROCEDURE sp_upgrade_v8_medicine_service_invoices()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- 1. BẢNG INVOICES: Bổ sung loại hóa đơn (invoice_type) và liên kết đơn thuốc nguồn (prescription_id)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoices' 
          AND COLUMN_NAME = 'invoice_type'
    ) THEN
        SET @sql = 'ALTER TABLE invoices ADD COLUMN invoice_type VARCHAR(20) NOT NULL DEFAULT ''SERVICE'' AFTER invoice_code';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;

        -- Đảm bảo tất cả hóa đơn cũ được định danh là SERVICE
        UPDATE invoices SET invoice_type = 'SERVICE' WHERE invoice_type IS NULL OR invoice_type = '';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema
          AND TABLE_NAME = 'invoices' 
          AND COLUMN_NAME = 'prescription_id'
    ) THEN
        SET @sql = 'ALTER TABLE invoices ADD COLUMN prescription_id BIGINT NULL AFTER visit_id';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- Thêm index và FK cho invoices.prescription_id nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoices' 
          AND CONSTRAINT_NAME = 'fk_inv_prescription'
    ) THEN
        SET @sql = 'ALTER TABLE invoices ADD CONSTRAINT fk_inv_prescription FOREIGN KEY (prescription_id) REFERENCES prescriptions(prescription_id) ON DELETE RESTRICT';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoices' 
          AND INDEX_NAME = 'idx_inv_type'
    ) THEN
        SET @sql = 'ALTER TABLE invoices ADD INDEX idx_inv_type (invoice_type)';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 2. BẢNG INVOICE_ITEMS: Cho phép service_id NULL khi là dòng thuốc, bổ sung liên kết thuốc
    -- Chuyển service_id thành NULL
    SET @sql = 'ALTER TABLE invoice_items MODIFY COLUMN service_id BIGINT NULL';
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoice_items' 
          AND COLUMN_NAME = 'item_type'
    ) THEN
        SET @sql = 'ALTER TABLE invoice_items ADD COLUMN item_type VARCHAR(20) NOT NULL DEFAULT ''SERVICE'' AFTER item_id';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;

        UPDATE invoice_items SET item_type = 'SERVICE' WHERE item_type IS NULL OR item_type = '';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoice_items' 
          AND COLUMN_NAME = 'prescription_item_id'
    ) THEN
        SET @sql = 'ALTER TABLE invoice_items ADD COLUMN prescription_item_id BIGINT NULL AFTER performed_id';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoice_items' 
          AND COLUMN_NAME = 'medication_id'
    ) THEN
        SET @sql = 'ALTER TABLE invoice_items ADD COLUMN medication_id BIGINT NULL AFTER service_id';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoice_items' 
          AND COLUMN_NAME = 'batch_id'
    ) THEN
        SET @sql = 'ALTER TABLE invoice_items ADD COLUMN batch_id BIGINT NULL AFTER medication_id';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoice_items' 
          AND COLUMN_NAME = 'unit'
    ) THEN
        SET @sql = 'ALTER TABLE invoice_items ADD COLUMN unit VARCHAR(30) NULL AFTER quantity';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoice_items' 
          AND COLUMN_NAME = 'usage_instruction'
    ) THEN
        SET @sql = 'ALTER TABLE invoice_items ADD COLUMN usage_instruction VARCHAR(255) NULL AFTER unit';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- Thêm các FK cho invoice_items nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoice_items' 
          AND CONSTRAINT_NAME = 'fk_item_prescription_item'
    ) THEN
        SET @sql = 'ALTER TABLE invoice_items ADD CONSTRAINT fk_item_prescription_item FOREIGN KEY (prescription_item_id) REFERENCES prescription_items(item_id) ON DELETE SET NULL';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoice_items' 
          AND CONSTRAINT_NAME = 'fk_item_medication'
    ) THEN
        SET @sql = 'ALTER TABLE invoice_items ADD CONSTRAINT fk_item_medication FOREIGN KEY (medication_id) REFERENCES medications(medication_id) ON DELETE SET NULL';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoice_items' 
          AND CONSTRAINT_NAME = 'fk_item_batch'
    ) THEN
        SET @sql = 'ALTER TABLE invoice_items ADD CONSTRAINT fk_item_batch FOREIGN KEY (batch_id) REFERENCES material_batches(batch_id) ON DELETE SET NULL';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 3. BẢNG PRESCRIPTIONS: Trạng thái tiếp nhận, lựa chọn mua thuốc
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'prescriptions' 
          AND COLUMN_NAME = 'status'
    ) THEN
        SET @sql = 'ALTER TABLE prescriptions ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT ''PENDING'' AFTER followup_date';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'prescriptions' 
          AND COLUMN_NAME = 'purchase_choice'
    ) THEN
        SET @sql = 'ALTER TABLE prescriptions ADD COLUMN purchase_choice VARCHAR(30) NULL AFTER status';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'prescriptions' 
          AND COLUMN_NAME = 'choice_note'
    ) THEN
        SET @sql = 'ALTER TABLE prescriptions ADD COLUMN choice_note VARCHAR(255) NULL AFTER purchase_choice';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'prescriptions' 
          AND COLUMN_NAME = 'handled_by'
    ) THEN
        SET @sql = 'ALTER TABLE prescriptions ADD COLUMN handled_by BIGINT NULL AFTER choice_note';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'prescriptions' 
          AND COLUMN_NAME = 'handled_at'
    ) THEN
        SET @sql = 'ALTER TABLE prescriptions ADD COLUMN handled_at DATETIME NULL AFTER handled_by';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'prescriptions' 
          AND CONSTRAINT_NAME = 'fk_presc_handled_by'
    ) THEN
        SET @sql = 'ALTER TABLE prescriptions ADD CONSTRAINT fk_presc_handled_by FOREIGN KEY (handled_by) REFERENCES staff_profiles(staff_id) ON DELETE SET NULL';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 4. BẢNG PRESCRIPTION_ITEMS: Theo dõi số lượng đã mua (bought_quantity)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'prescription_items' 
          AND COLUMN_NAME = 'bought_quantity'
    ) THEN
        SET @sql = 'ALTER TABLE prescription_items ADD COLUMN bought_quantity DECIMAL(6,2) NOT NULL DEFAULT 0.00 AFTER quantity';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 5. BẢNG MEDICATIONS: Đơn giá bán niêm yết và liên kết vật tư kho (materials)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'medications' 
          AND COLUMN_NAME = 'sale_price'
    ) THEN
        SET @sql = 'ALTER TABLE medications ADD COLUMN sale_price DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER unit';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'medications' 
          AND COLUMN_NAME = 'material_id'
    ) THEN
        SET @sql = 'ALTER TABLE medications ADD COLUMN material_id BIGINT NULL AFTER is_active';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'medications' 
          AND CONSTRAINT_NAME = 'fk_med_material'
    ) THEN
        SET @sql = 'ALTER TABLE medications ADD CONSTRAINT fk_med_material FOREIGN KEY (material_id) REFERENCES materials(material_id) ON DELETE SET NULL';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

END$$

DELIMITER ;

CALL sp_upgrade_v8_medicine_service_invoices();
DROP PROCEDURE IF EXISTS sp_upgrade_v8_medicine_service_invoices;
