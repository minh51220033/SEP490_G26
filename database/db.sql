-- =====================================================================
-- CV-DCMS DENTAL CLINIC MANAGEMENT SYSTEM
-- FULL SCHEMA DDL FOR NEW ENVIRONMENTS (INCLUDES FT21 & FT22)
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. HÓA ĐƠN VIỆN PHÍ (INVOICES - FT21)
CREATE TABLE IF NOT EXISTS invoices (
                                        invoice_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        invoice_code VARCHAR(50) NOT NULL UNIQUE,
    visit_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    cashier_id BIGINT NOT NULL,
    promotion_id BIGINT NULL,
    voucher_id BIGINT NULL,
    total_service_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    discount_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    promotion_discount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    point_discount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    paid_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    remaining_debt DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    signature_hash TEXT NULL,
    adjusted_by BIGINT NULL,
    adjustment_reason VARCHAR(500) NULL,
    notes TEXT NULL,
    issued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_inv_profile (profile_id),
    INDEX idx_inv_branch_date (branch_id, issued_at),
    INDEX idx_inv_status (status),
    INDEX idx_inv_payment_status (payment_status),
    CONSTRAINT fk_inv_visit FOREIGN KEY (visit_id) REFERENCES visits(visit_id),
    CONSTRAINT fk_inv_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id),
    CONSTRAINT fk_inv_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT fk_inv_cashier FOREIGN KEY (cashier_id) REFERENCES staff_profiles(staff_id),
    CONSTRAINT fk_inv_adjusted_by FOREIGN KEY (adjusted_by) REFERENCES staff_profiles(staff_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. CHI TIẾT DỊCH VỤ TRÊN HÓA ĐƠN (INVOICE_ITEMS - FT21)
CREATE TABLE IF NOT EXISTS invoice_items (
                                             item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                             invoice_id BIGINT NOT NULL,
                                             performed_id BIGINT NULL,
                                             service_id BIGINT NOT NULL,
                                             item_name VARCHAR(200) NOT NULL,
    quantity DECIMAL(5,2) NOT NULL DEFAULT 1.00,
    unit_price DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    final_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    INDEX idx_item_invoice (invoice_id),
    INDEX idx_item_performed (performed_id),
    CONSTRAINT fk_item_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE CASCADE,
    CONSTRAINT fk_item_performed FOREIGN KEY (performed_id) REFERENCES performed_services(performed_id),
    CONSTRAINT fk_item_service FOREIGN KEY (service_id) REFERENCES services(service_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. NHẬT KÝ ĐIỀU CHỈNH & HỦY HÓA ĐƠN (INVOICE_AUDIT_LOGS - SCR-21.4)
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

-- 4. GIAO DỊCH THANH TOÁN & TẠM ỨNG (PAYMENTS - FT22)
CREATE TABLE IF NOT EXISTS payments (
                                        payment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        payment_code VARCHAR(50) NOT NULL UNIQUE,
    invoice_id BIGINT NULL,
    profile_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    cashier_id BIGINT NOT NULL,
    payment_type VARCHAR(20) NOT NULL DEFAULT 'PAYMENT',
    payment_method VARCHAR(30) NOT NULL DEFAULT 'CASH',
    amount DECIMAL(15,2) NOT NULL,
    transaction_ref VARCHAR(100) NULL,
    idempotency_key VARCHAR(100) NULL UNIQUE,
    notes VARCHAR(255) NULL,
    paid_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pmt_invoice (invoice_id),
    INDEX idx_pmt_profile (profile_id),
    INDEX idx_pmt_date (paid_at),
    INDEX idx_pmt_transaction_ref (transaction_ref),
    UNIQUE INDEX uk_pmt_idempotency (idempotency_key),
    CONSTRAINT fk_pmt_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id),
    CONSTRAINT fk_pmt_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id),
    CONSTRAINT fk_pmt_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT fk_pmt_cashier FOREIGN KEY (cashier_id) REFERENCES staff_profiles(staff_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. HOÀN TIỀN DỊCH VỤ (REFUNDS - FT22)
CREATE TABLE IF NOT EXISTS refunds (
                                       refund_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                       refund_code VARCHAR(50) NOT NULL UNIQUE,
    invoice_id BIGINT NULL,
    profile_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    refunded_by BIGINT NOT NULL,
    refund_method VARCHAR(30) NOT NULL DEFAULT 'CASH',
    amount DECIMAL(15,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    refunded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ref_invoice (invoice_id),
    INDEX idx_ref_profile (profile_id),
    INDEX idx_ref_date (refunded_at),
    CONSTRAINT fk_ref_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id),
    CONSTRAINT fk_ref_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id),
    CONSTRAINT fk_ref_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT fk_ref_staff FOREIGN KEY (refunded_by) REFERENCES staff_profiles(staff_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;