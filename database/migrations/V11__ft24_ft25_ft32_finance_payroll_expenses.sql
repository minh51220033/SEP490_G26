-- =====================================================================
-- CV-DCMS MIGRATION V11: FT24, FT25, FT32
-- Bổ sung phân hệ Công nợ/Hoàn tiền (FT24), Chi phí vận hành (FT25), Lương/Hoa hồng/Phân phối (FT32)
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. BẢNG KỲ LƯƠNG (PAYROLL_PERIODS - FT32)
CREATE TABLE IF NOT EXISTS payroll_periods (
    period_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period_name VARCHAR(100) NOT NULL,
    branch_id BIGINT NOT NULL,
    month INT NOT NULL,
    year INT NOT NULL,
    total_salary DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_base_salary DECIMAL(15,2) DEFAULT 0.00,
    total_allowance DECIMAL(15,2) DEFAULT 0.00,
    total_bonus DECIMAL(15,2) DEFAULT 0.00,
    total_commission DECIMAL(15,2) DEFAULT 0.00,
    total_deduction DECIMAL(15,2) DEFAULT 0.00,
    total_advance_allocated DECIMAL(15,2) DEFAULT 0.00,
    total_remaining_payable DECIMAL(15,2) DEFAULT 0.00,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    submitted_by BIGINT NULL,
    submitted_at DATETIME NULL,
    approved_by BIGINT NULL,
    approved_at DATETIME NULL,
    rejection_reason VARCHAR(500) NULL,
    paid_by BIGINT NULL,
    paid_at DATETIME NULL,
    payment_method VARCHAR(30) NULL,
    payment_reference VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_payroll_period UNIQUE (branch_id, month, year),
    CONSTRAINT fk_pp_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT fk_pp_submitted_by FOREIGN KEY (submitted_by) REFERENCES staff_profiles(staff_id),
    CONSTRAINT fk_pp_approved_by FOREIGN KEY (approved_by) REFERENCES staff_profiles(staff_id),
    CONSTRAINT fk_pp_paid_by FOREIGN KEY (paid_by) REFERENCES staff_profiles(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. BẢNG CHI TIẾT BẢNG LƯƠNG NHÂN VIÊN (PAYROLL_ITEMS - FT32)
CREATE TABLE IF NOT EXISTS payroll_items (
    item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period_id BIGINT NOT NULL,
    staff_id BIGINT NOT NULL,
    base_salary DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    actual_work_days DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    actual_base_salary DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_allowance DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    bonus_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_commission DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    deduction_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    net_salary DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    advance_allocated_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    remaining_payable DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
    note VARCHAR(500) NULL,
    CONSTRAINT uk_pi_staff UNIQUE (period_id, staff_id),
    CONSTRAINT fk_pi_period FOREIGN KEY (period_id) REFERENCES payroll_periods(period_id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_staff FOREIGN KEY (staff_id) REFERENCES staff_profiles(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. BẢNG TẠM ỨNG LƯƠNG NHÂN VIÊN (SALARY_ADVANCES - FT32)
CREATE TABLE IF NOT EXISTS salary_advances (
    advance_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    advance_code VARCHAR(30) NOT NULL UNIQUE,
    staff_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    payment_method VARCHAR(30) NOT NULL DEFAULT 'BANK_TRANSFER',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    paid_at DATETIME NULL,
    allocated_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    remaining_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    created_by BIGINT NOT NULL,
    approved_by BIGINT NULL,
    approved_at DATETIME NULL,
    notes VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_adv_staff_status (staff_id, status),
    INDEX idx_adv_branch_date (branch_id, paid_at),
    CONSTRAINT fk_adv_staff FOREIGN KEY (staff_id) REFERENCES staff_profiles(staff_id),
    CONSTRAINT fk_adv_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT fk_adv_created_by FOREIGN KEY (created_by) REFERENCES users(user_id),
    CONSTRAINT fk_adv_approved_by FOREIGN KEY (approved_by) REFERENCES staff_profiles(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. BẢNG CHI PHÍ VẬN HÀNH (OPERATING_EXPENSES - FT25)
CREATE TABLE IF NOT EXISTS operating_expenses (
    expense_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    expense_code VARCHAR(30) NOT NULL UNIQUE,
    branch_id BIGINT NOT NULL,
    category VARCHAR(100) NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    expense_date DATE NOT NULL,
    recipient_name VARCHAR(150) NULL,
    payment_method VARCHAR(30) NOT NULL DEFAULT 'BANK_TRANSFER',
    source_type VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    payroll_period_id BIGINT NULL UNIQUE,
    salary_advance_id BIGINT NULL UNIQUE,
    description TEXT NOT NULL,
    is_cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    cancelled_reason VARCHAR(500) NULL,
    cancelled_by BIGINT NULL,
    cancelled_at DATETIME NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_exp_branch_date (branch_id, expense_date),
    INDEX idx_exp_category (category),
    INDEX idx_exp_source (source_type),
    CONSTRAINT fk_exp_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT fk_exp_payroll FOREIGN KEY (payroll_period_id) REFERENCES payroll_periods(period_id) ON DELETE RESTRICT,
    CONSTRAINT fk_exp_advance FOREIGN KEY (salary_advance_id) REFERENCES salary_advances(advance_id) ON DELETE RESTRICT,
    CONSTRAINT fk_exp_created_by FOREIGN KEY (created_by) REFERENCES users(user_id),
    CONSTRAINT fk_exp_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. BẢNG CHÍNH SÁCH HOA HỒNG (COMMISSION_POLICIES - FT32)
CREATE TABLE IF NOT EXISTS commission_policies (
    policy_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_name VARCHAR(150) NOT NULL,
    service_id BIGINT NULL,
    role_id BIGINT NULL,
    staff_id BIGINT NULL,
    commission_type VARCHAR(20) NOT NULL DEFAULT 'PERCENTAGE',
    commission_value DECIMAL(15,2) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_cp_service FOREIGN KEY (service_id) REFERENCES services(service_id),
    CONSTRAINT fk_cp_role FOREIGN KEY (role_id) REFERENCES roles(role_id),
    CONSTRAINT fk_cp_staff FOREIGN KEY (staff_id) REFERENCES staff_profiles(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. BẢNG HOA HỒNG DỊCH VỤ PHÁT SINH (SERVICE_COMMISSIONS - FT32)
CREATE TABLE IF NOT EXISTS service_commissions (
    commission_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    performed_id BIGINT NOT NULL,
    staff_id BIGINT NOT NULL,
    role_type VARCHAR(20) NOT NULL DEFAULT 'MAIN_DOCTOR',
    commission_amount DECIMAL(15,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payroll_item_id BIGINT NULL,
    calculated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sc UNIQUE (performed_id, staff_id, role_type),
    CONSTRAINT fk_sc_performed FOREIGN KEY (performed_id) REFERENCES performed_services(performed_id),
    CONSTRAINT fk_sc_staff FOREIGN KEY (staff_id) REFERENCES staff_profiles(staff_id),
    CONSTRAINT fk_sc_payroll_item FOREIGN KEY (payroll_item_id) REFERENCES payroll_items(item_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. BẢNG PHÂN PHỐI LỢI NHUẬN CHỦ PHÒNG KHÁM (OWNER_DISTRIBUTIONS - FT32)
CREATE TABLE IF NOT EXISTS owner_distributions (
    distribution_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    distribution_code VARCHAR(30) NOT NULL UNIQUE,
    branch_id BIGINT NULL,
    month INT NOT NULL,
    year INT NOT NULL,
    distributable_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    retained_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_distributed_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    basis_notes TEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    submitted_by BIGINT NULL,
    submitted_at DATETIME NULL,
    approved_by BIGINT NULL,
    approved_at DATETIME NULL,
    rejection_reason VARCHAR(500) NULL,
    paid_by BIGINT NULL,
    paid_at DATETIME NULL,
    payment_method VARCHAR(30) NULL,
    payment_reference VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_dist_month_year (month, year),
    CONSTRAINT fk_od_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT fk_od_submitted_by FOREIGN KEY (submitted_by) REFERENCES staff_profiles(staff_id),
    CONSTRAINT fk_od_approved_by FOREIGN KEY (approved_by) REFERENCES staff_profiles(staff_id),
    CONSTRAINT fk_od_paid_by FOREIGN KEY (paid_by) REFERENCES staff_profiles(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. CHI TIẾT NGƯỜI NHẬN PHÂN PHỐI LỢI NHUẬN (OWNER_DISTRIBUTION_ITEMS - FT32)
CREATE TABLE IF NOT EXISTS owner_distribution_items (
    item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    distribution_id BIGINT NOT NULL,
    recipient_staff_id BIGINT NOT NULL,
    percentage DECIMAL(5,2) NULL,
    amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    note VARCHAR(255) NULL,
    CONSTRAINT fk_odi_distribution FOREIGN KEY (distribution_id) REFERENCES owner_distributions(distribution_id) ON DELETE CASCADE,
    CONSTRAINT fk_odi_staff FOREIGN KEY (recipient_staff_id) REFERENCES staff_profiles(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
