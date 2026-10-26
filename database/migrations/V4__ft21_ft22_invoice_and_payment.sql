-- =============================================================================
-- Migration V4: FT21 & FT22 Hóa đơn, Thanh toán, Tạm ứng, Hoàn tiền và Audit Log
-- Tương thích: MySQL 8.0.40 (Schema: cv_dcms_db)
-- Đặc điểm: Idempotent (chạy nhiều lần không lỗi), bảo toàn dữ liệu, an toàn tuyệt đối
-- =============================================================================
USE cv_dcms_db;
DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_ft21_ft22$$

CREATE PROCEDURE sp_upgrade_ft21_ft22()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- 1. Bổ sung cột 'status' cho bảng invoices nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoices' 
          AND COLUMN_NAME = 'status'
    ) THEN
        SET @sql = 'ALTER TABLE invoices ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''CONFIRMED'' AFTER payment_status';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;

        -- Khi mới bổ sung invoices.status, chuyển các hóa đơn cũ có payment_status = CANCELLED thành status = CANCELLED
        UPDATE invoices SET status = 'CANCELLED' WHERE payment_status = 'CANCELLED';
    ELSE
        -- Nếu cột status đã có sẵn, đảm bảo các hóa đơn có payment_status = CANCELLED có status = CANCELLED
        UPDATE invoices SET status = 'CANCELLED' WHERE payment_status = 'CANCELLED' AND (status IS NULL OR status != 'CANCELLED');
    END IF;

    -- 2. Bổ sung cột 'adjusted_by' cho bảng invoices nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoices' 
          AND COLUMN_NAME = 'adjusted_by'
    ) THEN
        SET @sql = 'ALTER TABLE invoices ADD COLUMN adjusted_by BIGINT AFTER signature_hash';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 3. Bổ sung cột 'adjustment_reason' cho bảng invoices nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoices' 
          AND COLUMN_NAME = 'adjustment_reason'
    ) THEN
        SET @sql = 'ALTER TABLE invoices ADD COLUMN adjustment_reason VARCHAR(500) AFTER adjusted_by';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 4. Bổ sung khóa ngoại fk_inv_adjust nếu cột adjusted_by và bảng staff_profiles tồn tại
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE TABLE_SCHEMA = v_schema
          AND TABLE_NAME = 'invoices'
          AND CONSTRAINT_NAME = 'fk_inv_adjust'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles'
        ) THEN
            SET @sql = 'ALTER TABLE invoices ADD CONSTRAINT fk_inv_adjust FOREIGN KEY (adjusted_by) REFERENCES staff_profiles(staff_id) ON DELETE SET NULL';
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END IF;
    END IF;

    -- 5. Bổ sung chỉ mục idx_inv_status cho bảng invoices nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoices' 
          AND INDEX_NAME = 'idx_inv_status'
    ) THEN
        SET @sql = 'CREATE INDEX idx_inv_status ON invoices(status)';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 6. Bổ sung chỉ mục idx_inv_payment_status cho bảng invoices nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'invoices' 
          AND INDEX_NAME = 'idx_inv_payment_status'
    ) THEN
        SET @sql = 'CREATE INDEX idx_inv_payment_status ON invoices(payment_status)';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 7. Bổ sung chỉ mục idx_pmt_transaction_ref cho bảng payments nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'payments' 
          AND INDEX_NAME = 'idx_pmt_transaction_ref'
    ) THEN
        SET @sql = 'CREATE INDEX idx_pmt_transaction_ref ON payments(transaction_ref)';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 8. Bổ sung cột 'idempotency_key' cho bảng payments nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'payments' 
          AND COLUMN_NAME = 'idempotency_key'
    ) THEN
        SET @sql = 'ALTER TABLE payments ADD COLUMN idempotency_key VARCHAR(100) AFTER transaction_ref';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 9. Bổ sung unique index uk_pmt_idempotency cho payments(idempotency_key) nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = v_schema 
          AND TABLE_NAME = 'payments' 
          AND INDEX_NAME = 'uk_pmt_idempotency'
    ) THEN
        SET @sql = 'CREATE UNIQUE INDEX uk_pmt_idempotency ON payments(idempotency_key)';
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;

    -- 10. Bổ sung các cột audit discount cho invoice_audit_logs nếu bảng đã tồn tại
    IF EXISTS (
        SELECT 1 FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_audit_logs'
    ) THEN
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.COLUMNS 
            WHERE TABLE_SCHEMA = v_schema 
              AND TABLE_NAME = 'invoice_audit_logs' 
              AND COLUMN_NAME = 'old_discount_amount'
        ) THEN
            SET @sql = 'ALTER TABLE invoice_audit_logs ADD COLUMN old_discount_amount DECIMAL(15,2) AFTER new_total_amount';
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM information_schema.COLUMNS 
            WHERE TABLE_SCHEMA = v_schema 
              AND TABLE_NAME = 'invoice_audit_logs' 
              AND COLUMN_NAME = 'new_discount_amount'
        ) THEN
            SET @sql = 'ALTER TABLE invoice_audit_logs ADD COLUMN new_discount_amount DECIMAL(15,2) AFTER old_discount_amount';
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END IF;
    END IF;


    -- Bổ sung số tiền phụ thu cho hóa đơn
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema
          AND TABLE_NAME = 'invoices'
          AND COLUMN_NAME = 'surcharge_amount'
    ) THEN
ALTER TABLE invoices
    ADD COLUMN surcharge_amount
        DECIMAL(15,2) NOT NULL DEFAULT 0.00;
END IF;

    -- Bổ sung nội dung phụ thu
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema
          AND TABLE_NAME = 'invoices'
          AND COLUMN_NAME = 'surcharge_description'
    ) THEN
ALTER TABLE invoices
    ADD COLUMN surcharge_description VARCHAR(500) NULL;
END IF;

END$$

DELIMITER ;

-- Thực thi nâng cấp
CALL sp_upgrade_ft21_ft22();

-- Dọn dẹp thủ tục tạm thời
DROP PROCEDURE IF EXISTS sp_upgrade_ft21_ft22;

-- 11. Tạo bảng invoice_audit_logs ghi nhận lịch sử tạo, sửa, hủy hóa đơn (SCR-21.4)
-- Ràng buộc ON DELETE RESTRICT: bảo toàn lịch sử audit, ngăn chặn việc xóa cứng hóa đơn khi đã có nhật ký tài chính
CREATE TABLE IF NOT EXISTS invoice_audit_logs (
    log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    old_total_amount DECIMAL(15,2),
    new_total_amount DECIMAL(15,2),
    old_discount_amount DECIMAL(15,2),
    new_discount_amount DECIMAL(15,2),
    old_paid_amount DECIMAL(15,2),
    new_paid_amount DECIMAL(15,2),
    reason VARCHAR(500),
    details TEXT,
    performed_by BIGINT,
    performed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_inv_audit_invoice (invoice_id),
    INDEX idx_inv_audit_date (performed_at),
    CONSTRAINT fk_inv_audit_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE RESTRICT,
    CONSTRAINT fk_inv_audit_staff FOREIGN KEY (performed_by) REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

