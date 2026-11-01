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
    invoice_type VARCHAR(20) NOT NULL DEFAULT 'SERVICE',
    visit_id BIGINT NOT NULL,
    prescription_id BIGINT NULL,
    profile_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    cashier_id BIGINT NOT NULL,
    promotion_id BIGINT NULL,
    voucher_id BIGINT NULL,
    total_service_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_medicine_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    surcharge_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    surcharge_description VARCHAR(500) NULL,
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
                                             item_type VARCHAR(20) NOT NULL DEFAULT 'SERVICE',
                                             invoice_id BIGINT NOT NULL,
                                             performed_id BIGINT NULL,
                                             prescription_item_id BIGINT NULL,
                                             service_id BIGINT NULL,
                                             medication_id BIGINT NULL,
                                             batch_id BIGINT NULL,
                                             item_name VARCHAR(200) NOT NULL,
    quantity DECIMAL(5,2) NOT NULL DEFAULT 1.00,
    unit VARCHAR(30) NULL,
    usage_instruction VARCHAR(255) NULL,
    unit_price DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    final_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    INDEX idx_item_invoice (invoice_id),
    INDEX idx_item_performed (performed_id),
    INDEX idx_item_type (item_type),
    CONSTRAINT fk_item_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE CASCADE,
    CONSTRAINT fk_item_performed FOREIGN KEY (performed_id) REFERENCES performed_services(performed_id),
    CONSTRAINT fk_item_service FOREIGN KEY (service_id) REFERENCES services(service_id),
    CONSTRAINT fk_item_prescription_item FOREIGN KEY (prescription_item_id) REFERENCES prescription_items(item_id) ON DELETE SET NULL,
    CONSTRAINT fk_item_medication FOREIGN KEY (medication_id) REFERENCES medications(medication_id) ON DELETE SET NULL,
    CONSTRAINT fk_item_batch FOREIGN KEY (batch_id) REFERENCES material_batches(batch_id) ON DELETE SET NULL
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

-- 6. HẠNG THÀNH VIÊN (MEMBERSHIP_TIERS - FT19)
CREATE TABLE IF NOT EXISTS membership_tiers (
    tier_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tier_code VARCHAR(20) NOT NULL UNIQUE,
    tier_name VARCHAR(50) NOT NULL,
    min_total_spent DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    discount_percent DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    spend_per_point DECIMAL(15,2) NOT NULL DEFAULT 10000.00,
    point_value DECIMAL(15,2) NOT NULL DEFAULT 10.00,
    display_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX idx_tier_order (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. VÍ TÍCH ĐIỂM BỆNH NHÂN (PATIENT_LOYALTY_WALLETS - FT19)
CREATE TABLE IF NOT EXISTS patient_loyalty_wallets (
    wallet_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_id BIGINT NOT NULL UNIQUE,
    current_points INT NOT NULL DEFAULT 0,
    total_spent_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    last_updated DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_plw_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. LỊCH SỬ GIAO DỊCH ĐIỂM (POINT_TRANSACTIONS - FT19)
CREATE TABLE IF NOT EXISTS point_transactions (
    transaction_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_id BIGINT NOT NULL,
    invoice_id BIGINT NULL,
    payment_id BIGINT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    points INT NOT NULL,
    balance_after INT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pt_profile (profile_id, created_at),
    INDEX idx_pt_invoice (invoice_id),
    CONSTRAINT fk_pt_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE CASCADE,
    CONSTRAINT fk_pt_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE SET NULL,
    CONSTRAINT fk_pt_payment FOREIGN KEY (payment_id) REFERENCES payments(payment_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. CHƯƠNG TRÌNH KHUYẾN MÃI (PROMOTIONS - FT20)
CREATE TABLE IF NOT EXISTS promotions (
    promotion_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    promo_code VARCHAR(30) NOT NULL UNIQUE,
    promo_name VARCHAR(200) NOT NULL,
    description VARCHAR(500) NULL,
    branch_id BIGINT NULL,
    promo_type VARCHAR(20) NOT NULL DEFAULT 'PERCENTAGE',
    discount_value DECIMAL(15,2) NOT NULL,
    max_discount_amount DECIMAL(15,2) NULL,
    min_bill_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    applicable_tier_id BIGINT NULL,
    applicable_service_id BIGINT NULL,
    start_date DATETIME NOT NULL,
    end_date DATETIME NOT NULL,
    usage_limit INT NULL,
    used_count INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX idx_promo_branch_date (branch_id, start_date, end_date),
    CONSTRAINT fk_promo_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE SET NULL,
    CONSTRAINT fk_promo_tier FOREIGN KEY (applicable_tier_id) REFERENCES membership_tiers(tier_id) ON DELETE SET NULL,
    CONSTRAINT fk_promo_service FOREIGN KEY (applicable_service_id) REFERENCES services(service_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. VOUCHER BỆNH NHÂN (PATIENT_VOUCHERS - FT20)
CREATE TABLE IF NOT EXISTS patient_vouchers (
    voucher_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    promotion_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    voucher_code VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    expires_at DATETIME NOT NULL,
    used_at DATETIME NULL,
    INDEX idx_pv_profile_status (profile_id, status),
    CONSTRAINT fk_pv_promo FOREIGN KEY (promotion_id) REFERENCES promotions(promotion_id) ON DELETE CASCADE,
    CONSTRAINT fk_pv_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
