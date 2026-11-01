-- =============================================================================
-- Migration V14: Theo dõi thanh toán chi tiết từng dịch vụ/thuốc (Payment Allocations)
-- Tương thích: MySQL 8.0+ (Schema: cv_dcms_db)
-- Đặc điểm: Idempotent, bảo toàn dữ liệu hiện có, kiểm tra từng cột độc lập.
-- =============================================================================
USE cv_dcms_db;

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v14_payment_allocations$$

CREATE PROCEDURE sp_upgrade_v14_payment_allocations()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- =========================================================================
    -- 1. BỔ SUNG CÁC CỘT VÀO BẢNG invoice_items
    -- =========================================================================
    -- 1.1 line_order (Thứ tự phân bổ cố định khi phát hành hóa đơn)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'line_order'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN line_order INT NOT NULL DEFAULT 1 COMMENT 'Thứ tự phân bổ chốt lúc tạo';
    END IF;

    -- 1.2 discount_amount (Giảm trừ phân bổ vào dòng này)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'discount_amount'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN discount_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 COMMENT 'Giảm trừ phân bổ';
    END IF;

    -- 1.3 payable_amount (Thành tiền phải trả sau giảm trừ)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'payable_amount'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN payable_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 COMMENT 'Phải trả sau giảm trừ';
    END IF;

    -- 1.4 paid_amount (Số tiền đã thanh toán cho dòng này)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'paid_amount'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN paid_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 COMMENT 'Đã thanh toán';
    END IF;

    -- 1.5 remaining_debt (Số tiền còn nợ của dòng này)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'remaining_debt'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN remaining_debt DECIMAL(15,2) NOT NULL DEFAULT 0.00 COMMENT 'Còn nợ';
    END IF;

    -- 1.6 payment_status (Trạng thái thanh toán của dòng: UNPAID, PARTIAL, PAID)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'payment_status'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID, PARTIAL, PAID';
    END IF;

    -- =========================================================================
    -- 2. BỔ SUNG CÁC CỘT VÀO BẢNG invoices (Dành cho phụ thu)
    -- =========================================================================
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoices' AND COLUMN_NAME = 'surcharge_paid_amount'
    ) THEN
        ALTER TABLE invoices ADD COLUMN surcharge_paid_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 COMMENT 'Đã trả phần phụ thu';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoices' AND COLUMN_NAME = 'surcharge_remaining_debt'
    ) THEN
        ALTER TABLE invoices ADD COLUMN surcharge_remaining_debt DECIMAL(15,2) NOT NULL DEFAULT 0.00 COMMENT 'Còn nợ phần phụ thu';
    END IF;

    -- =========================================================================
    -- 3. TẠO BẢNG payment_allocations
    -- =========================================================================
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLES 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payment_allocations'
    ) THEN
        CREATE TABLE payment_allocations (
            allocation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
            payment_id BIGINT NOT NULL COMMENT 'Mã phiếu thanh toán',
            item_id BIGINT NULL COMMENT 'Mã dòng hóa đơn (NULL nếu phân bổ phụ thu)',
            invoice_id BIGINT NOT NULL COMMENT 'Mã hóa đơn liên quan',
            allocated_amount DECIMAL(15,2) NOT NULL COMMENT 'Số tiền phân bổ trong lần thu này',
            item_type VARCHAR(20) NOT NULL COMMENT 'SERVICE, MEDICINE, SURCHARGE',
            item_title VARCHAR(255) NOT NULL COMMENT 'Tên dịch vụ/thuốc/phụ thu tại thời điểm phân bổ',
            line_order INT NOT NULL DEFAULT 1 COMMENT 'Thứ tự phân bổ',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm ghi nhận phân bổ',
            notes VARCHAR(255) NULL COMMENT 'Ghi chú thêm',
            INDEX idx_pa_payment (payment_id),
            INDEX idx_pa_item (item_id),
            INDEX idx_pa_invoice (invoice_id),
            CONSTRAINT fk_pa_payment FOREIGN KEY (payment_id) REFERENCES payments(payment_id) ON DELETE RESTRICT,
            CONSTRAINT fk_pa_item FOREIGN KEY (item_id) REFERENCES invoice_items(item_id) ON DELETE RESTRICT,
            CONSTRAINT fk_pa_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE RESTRICT
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    END IF;

    -- =========================================================================
    -- 4. KHỞI TẠO DỮ LIỆU CƠ SỞ CHO CÁC DÒNG HÓA ĐƠN ĐÃ CÓ (Bảo toàn dữ liệu)
    -- =========================================================================
    -- Đặt payable_amount = final_amount nếu payable_amount đang bằng 0 và final_amount > 0
    UPDATE invoice_items 
    SET payable_amount = final_amount,
        remaining_debt = final_amount
    WHERE (payable_amount = 0.00 AND final_amount > 0.00);

    -- Với các dòng phụ thu của hóa đơn cũ
    UPDATE invoices
    SET surcharge_remaining_debt = surcharge_amount
    WHERE surcharge_remaining_debt = 0.00 AND surcharge_amount > 0.00 AND paid_amount = 0.00;

END$$

DELIMITER ;

CALL sp_upgrade_v14_payment_allocations();
DROP PROCEDURE IF EXISTS sp_upgrade_v14_payment_allocations;

