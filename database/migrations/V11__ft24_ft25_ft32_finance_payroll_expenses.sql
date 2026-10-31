-- =====================================================================
-- CV-DCMS MIGRATION V11: FT24, FT25, FT32 (HOÀN CHỈNH & ĐỒNG BỘ)
-- Phân hệ:
--   - FT24: Quản lý Hoàn tiền & Công nợ (Refunds)
--   - FT25: Quản lý Chi phí vận hành phòng khám (Operating Expenses)
--   - FT32: Bảng lương, Tạm ứng, Hoa hồng & Phân phối lợi nhuận (Payroll, Advances, Commissions, Distributions)
-- Thiết kế tương thích kép:
--   - Tự động tạo mới đầy đủ cấu trúc trên database mới
--   - Tự động kiểm tra và nâng cấp bổ sung cột, ràng buộc, backfill trên database cũ
--   - Idempotent: Có thể chạy nhiều lần mà không gây lỗi hoặc mất mát dữ liệu
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- 1. BẢNG HOÀN TIỀN (REFUNDS - FT24)
-- Bổ sung: idempotency_key (chống hoàn tiền trùng lặp), reduce_invoice_total (điều chỉnh doanh thu hóa đơn)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS refunds (
    refund_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_code VARCHAR(50) NOT NULL UNIQUE,
    idempotency_key VARCHAR(100) NULL,
    invoice_id BIGINT NULL,
    profile_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    refunded_by BIGINT NOT NULL,
    refund_method VARCHAR(30) NOT NULL DEFAULT 'CASH',
    amount DECIMAL(15,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    reduce_invoice_total BOOLEAN NOT NULL DEFAULT FALSE,
    refunded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ref_invoice (invoice_id),
    INDEX idx_ref_profile (profile_id),
    INDEX idx_ref_date (refunded_at),
    CONSTRAINT uk_refund_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_ref_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id),
    CONSTRAINT fk_ref_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id),
    CONSTRAINT fk_ref_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT fk_ref_staff FOREIGN KEY (refunded_by) REFERENCES staff_profiles(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$
DROP PROCEDURE IF EXISTS sp_v11_upgrade_refunds $$
CREATE PROCEDURE sp_v11_upgrade_refunds()
BEGIN
    -- Bổ sung cột idempotency_key nếu bảng đã tồn tại từ trước mà thiếu cột
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refunds' AND COLUMN_NAME = 'idempotency_key'
    ) THEN
        ALTER TABLE refunds ADD COLUMN idempotency_key VARCHAR(100) NULL AFTER refund_code;
    END IF;

    -- Thêm chỉ mục UNIQUE uk_refund_idempotency_key nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refunds' AND INDEX_NAME = 'uk_refund_idempotency_key'
    ) THEN
        ALTER TABLE refunds ADD CONSTRAINT uk_refund_idempotency_key UNIQUE (idempotency_key);
    END IF;

    -- Bổ sung cột reduce_invoice_total nếu thiếu
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refunds' AND COLUMN_NAME = 'reduce_invoice_total'
    ) THEN
        ALTER TABLE refunds ADD COLUMN reduce_invoice_total BOOLEAN NOT NULL DEFAULT FALSE AFTER reason;
    END IF;
END $$
DELIMITER ;
CALL sp_v11_upgrade_refunds();
DROP PROCEDURE IF EXISTS sp_v11_upgrade_refunds;


-- ---------------------------------------------------------------------
-- 2. BẢNG KỲ LƯƠNG (PAYROLL_PERIODS - FT32)
-- Đầy đủ cấu phần lương tổng hợp, đối soát phê duyệt, thanh toán chi trả
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payroll_periods (
    period_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period_name VARCHAR(100) NOT NULL,
    branch_id BIGINT NOT NULL,
    month INT NOT NULL,
    year INT NOT NULL,
    total_salary DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_base_salary DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_allowance DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_bonus DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_commission DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_deduction DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_advance_allocated DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_remaining_payable DECIMAL(15,2) NOT NULL DEFAULT 0.00,
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

DELIMITER $$
DROP PROCEDURE IF EXISTS sp_v11_upgrade_payroll_periods $$
CREATE PROCEDURE sp_v11_upgrade_payroll_periods()
BEGIN
    -- Chuẩn hóa kiểu dữ liệu status thành VARCHAR(20) nếu trước đó dùng ENUM
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' 
          AND COLUMN_NAME = 'status' AND DATA_TYPE = 'enum'
    ) THEN
        ALTER TABLE payroll_periods MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'DRAFT';
    END IF;

    -- Bổ sung các cột tổng hợp cấu phần lương nếu bảng cũ chưa có
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_base_salary') THEN
        ALTER TABLE payroll_periods ADD COLUMN total_base_salary DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_salary;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_allowance') THEN
        ALTER TABLE payroll_periods ADD COLUMN total_allowance DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_base_salary;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_bonus') THEN
        ALTER TABLE payroll_periods ADD COLUMN total_bonus DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_allowance;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_commission') THEN
        ALTER TABLE payroll_periods ADD COLUMN total_commission DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_bonus;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_deduction') THEN
        ALTER TABLE payroll_periods ADD COLUMN total_deduction DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_commission;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_advance_allocated') THEN
        ALTER TABLE payroll_periods ADD COLUMN total_advance_allocated DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_deduction;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_remaining_payable') THEN
        ALTER TABLE payroll_periods ADD COLUMN total_remaining_payable DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_advance_allocated;
    END IF;

    -- Bổ sung các cột quy trình trình duyệt, thanh toán
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'submitted_by') THEN
        ALTER TABLE payroll_periods ADD COLUMN submitted_by BIGINT NULL AFTER status;
        ALTER TABLE payroll_periods ADD CONSTRAINT fk_pp_submitted_by FOREIGN KEY (submitted_by) REFERENCES staff_profiles(staff_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'submitted_at') THEN
        ALTER TABLE payroll_periods ADD COLUMN submitted_at DATETIME NULL AFTER submitted_by;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'approved_by') THEN
        ALTER TABLE payroll_periods ADD COLUMN approved_by BIGINT NULL AFTER submitted_at;
        ALTER TABLE payroll_periods ADD CONSTRAINT fk_pp_approved_by FOREIGN KEY (approved_by) REFERENCES staff_profiles(staff_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'approved_at') THEN
        ALTER TABLE payroll_periods ADD COLUMN approved_at DATETIME NULL AFTER approved_by;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'rejection_reason') THEN
        ALTER TABLE payroll_periods ADD COLUMN rejection_reason VARCHAR(500) NULL AFTER approved_at;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'paid_by') THEN
        ALTER TABLE payroll_periods ADD COLUMN paid_by BIGINT NULL AFTER rejection_reason;
        ALTER TABLE payroll_periods ADD CONSTRAINT fk_pp_paid_by FOREIGN KEY (paid_by) REFERENCES staff_profiles(staff_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'paid_at') THEN
        ALTER TABLE payroll_periods ADD COLUMN paid_at DATETIME NULL AFTER paid_by;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'payment_method') THEN
        ALTER TABLE payroll_periods ADD COLUMN payment_method VARCHAR(30) NULL AFTER paid_at;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'payment_reference') THEN
        ALTER TABLE payroll_periods ADD COLUMN payment_reference VARCHAR(100) NULL AFTER payment_method;
    END IF;
END $$
DELIMITER ;
CALL sp_v11_upgrade_payroll_periods();
DROP PROCEDURE IF EXISTS sp_v11_upgrade_payroll_periods;


-- ---------------------------------------------------------------------
-- 3. BẢNG DÒNG LƯƠNG NHÂN VIÊN (PAYROLL_ITEMS - FT32)
-- Đầy đủ cấu phần: period_month, period_year, bonus, deduction, advance, note
-- Ràng buộc: Mỗi nhân viên chỉ có 1 dòng lương trong 1 tháng trên toàn bộ chi nhánh
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payroll_items (
    item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period_id BIGINT NOT NULL,
    staff_id BIGINT NOT NULL,
    period_month INT NOT NULL,
    period_year INT NOT NULL,
    base_salary DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    actual_work_days DECIMAL(5,2) NULL DEFAULT NULL,
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
    CONSTRAINT uk_payroll_item_staff_month_year UNIQUE (staff_id, period_month, period_year),
    CONSTRAINT fk_pi_period FOREIGN KEY (period_id) REFERENCES payroll_periods(period_id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_staff FOREIGN KEY (staff_id) REFERENCES staff_profiles(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$
DROP PROCEDURE IF EXISTS sp_v11_upgrade_payroll_items $$
CREATE PROCEDURE sp_v11_upgrade_payroll_items()
BEGIN
    -- Bổ sung period_month, period_year
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'period_month') THEN
        ALTER TABLE payroll_items ADD COLUMN period_month INT NULL AFTER staff_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'period_year') THEN
        ALTER TABLE payroll_items ADD COLUMN period_year INT NULL AFTER period_month;
    END IF;

    -- Bổ sung bonus_amount, deduction_amount
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'bonus_amount') THEN
        ALTER TABLE payroll_items ADD COLUMN bonus_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_allowance;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'deduction_amount') THEN
        ALTER TABLE payroll_items ADD COLUMN deduction_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_commission;
    END IF;

    -- Bổ sung advance_allocated_amount, remaining_payable, note
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'advance_allocated_amount') THEN
        ALTER TABLE payroll_items ADD COLUMN advance_allocated_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER net_salary;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'remaining_payable') THEN
        ALTER TABLE payroll_items ADD COLUMN remaining_payable DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER advance_allocated_amount;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'note') THEN
        ALTER TABLE payroll_items ADD COLUMN note VARCHAR(500) NULL AFTER payment_status;
    END IF;

    -- Cho phép actual_work_days là NULL khi chưa tích hợp máy chấm công
    ALTER TABLE payroll_items MODIFY COLUMN actual_work_days DECIMAL(5,2) NULL DEFAULT NULL;

    -- Backfill period_month và period_year từ kỳ lương cha (payroll_periods)
    UPDATE payroll_items pi
    JOIN payroll_periods pp ON pi.period_id = pp.period_id
    SET pi.period_month = pp.month,
        pi.period_year = pp.year
    WHERE pi.period_month IS NULL OR pi.period_year IS NULL;

    -- Backfill net_salary (chỉ khi chưa có hoặc = 0)
    UPDATE payroll_items
    SET net_salary = GREATEST(0, COALESCE(actual_base_salary, base_salary, 0) + COALESCE(total_allowance, 0) + COALESCE(total_commission, 0) + COALESCE(bonus_amount, 0) - COALESCE(deduction_amount, 0))
    WHERE net_salary IS NULL OR net_salary = 0.00;

    -- Backfill remaining_payable (thực lĩnh sau khi cấn trừ tạm ứng)
    UPDATE payroll_items
    SET remaining_payable = GREATEST(0, COALESCE(net_salary, 0) - COALESCE(advance_allocated_amount, 0))
    WHERE remaining_payable IS NULL 
       OR (remaining_payable = 0.00 AND COALESCE(net_salary, 0) > COALESCE(advance_allocated_amount, 0));

    -- Tạo bảng lưu vết nếu có bản ghi trùng lặp nhân viên trong cùng tháng/năm trước khi gắn Unique Constraint
    CREATE TABLE IF NOT EXISTS _payroll_items_duplicate_audit (
        audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
        item_id BIGINT NOT NULL,
        staff_id BIGINT NOT NULL,
        period_month INT NOT NULL,
        period_year INT NOT NULL,
        detected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

    INSERT INTO _payroll_items_duplicate_audit (item_id, staff_id, period_month, period_year)
    SELECT pi.item_id, pi.staff_id, pi.period_month, pi.period_year
    FROM payroll_items pi
    INNER JOIN (
        SELECT staff_id, period_month, period_year
        FROM payroll_items
        WHERE period_month IS NOT NULL AND period_year IS NOT NULL
        GROUP BY staff_id, period_month, period_year
        HAVING COUNT(*) > 1
    ) dup ON pi.staff_id = dup.staff_id AND pi.period_month = dup.period_month AND pi.period_year = dup.period_year;

    -- Thêm Unique Constraint uk_payroll_item_staff_month_year nếu không bị trùng lặp
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND INDEX_NAME = 'uk_payroll_item_staff_month_year'
    ) THEN
        IF (SELECT COUNT(*) FROM _payroll_items_duplicate_audit) = 0 THEN
            ALTER TABLE payroll_items MODIFY COLUMN period_month INT NOT NULL;
            ALTER TABLE payroll_items MODIFY COLUMN period_year INT NOT NULL;
            ALTER TABLE payroll_items ADD CONSTRAINT uk_payroll_item_staff_month_year UNIQUE (staff_id, period_month, period_year);
        END IF;
    END IF;
END $$
DELIMITER ;
CALL sp_v11_upgrade_payroll_items();
DROP PROCEDURE IF EXISTS sp_v11_upgrade_payroll_items;


-- ---------------------------------------------------------------------
-- 4. ĐỒNG BỘ TỔNG TIỀN KỲ LƯƠNG TỪ CÁC DÒNG LƯƠNG THỰC TẾ
-- ---------------------------------------------------------------------
UPDATE payroll_periods pp
JOIN (
    SELECT 
        period_id,
        COALESCE(SUM(base_salary), 0) AS calc_base,
        COALESCE(SUM(total_allowance), 0) AS calc_allowance,
        COALESCE(SUM(bonus_amount), 0) AS calc_bonus,
        COALESCE(SUM(deduction_amount), 0) AS calc_deduction,
        COALESCE(SUM(total_commission), 0) AS calc_commission,
        COALESCE(SUM(net_salary), 0) AS calc_net,
        COALESCE(SUM(advance_allocated_amount), 0) AS calc_advance,
        COALESCE(SUM(remaining_payable), 0) AS calc_remaining
    FROM payroll_items
    GROUP BY period_id
) agg ON pp.period_id = agg.period_id
SET 
    pp.total_base_salary = agg.calc_base,
    pp.total_allowance = agg.calc_allowance,
    pp.total_bonus = agg.calc_bonus,
    pp.total_deduction = agg.calc_deduction,
    pp.total_commission = agg.calc_commission,
    pp.total_salary = agg.calc_net,
    pp.total_advance_allocated = agg.calc_advance,
    pp.total_remaining_payable = agg.calc_remaining
WHERE pp.total_remaining_payable IS NULL OR pp.total_remaining_payable = 0.00;


-- ---------------------------------------------------------------------
-- 5. BẢNG TẠM ỨNG LƯƠNG & PHÂN BỔ TẠM ỨNG (SALARY_ADVANCES & ALLOCATIONS - FT32)
-- ---------------------------------------------------------------------
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

DELIMITER $$
DROP PROCEDURE IF EXISTS sp_v11_upgrade_salary_advances $$
CREATE PROCEDURE sp_v11_upgrade_salary_advances()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'salary_advances' AND COLUMN_NAME = 'allocated_amount') THEN
        ALTER TABLE salary_advances ADD COLUMN allocated_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER paid_at;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'salary_advances' AND COLUMN_NAME = 'remaining_amount') THEN
        ALTER TABLE salary_advances ADD COLUMN remaining_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER allocated_amount;
        -- Cập nhật remaining_amount mặc định bằng amount nếu chưa từng được phân bổ
        UPDATE salary_advances SET remaining_amount = amount WHERE remaining_amount = 0.00 AND allocated_amount = 0.00;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'salary_advances' AND COLUMN_NAME = 'notes') THEN
        ALTER TABLE salary_advances ADD COLUMN notes VARCHAR(500) NULL AFTER approved_at;
    END IF;
END $$
DELIMITER ;
CALL sp_v11_upgrade_salary_advances();
DROP PROCEDURE IF EXISTS sp_v11_upgrade_salary_advances;

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

-- Bảng đối soát lưu vết các kỳ SUBMITTED / APPROVED có tạm ứng nhưng thiếu chi tiết allocation nguồn
CREATE TABLE IF NOT EXISTS _payroll_reconciliation_audit (
    audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period_id BIGINT NOT NULL,
    period_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_advance DECIMAL(15,2) NOT NULL,
    issue_description VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO _payroll_reconciliation_audit (period_id, period_name, status, total_advance, issue_description)
SELECT pp.period_id, pp.period_name, pp.status, pp.total_advance_allocated,
       CONCAT('Kỳ lương có tổng tạm ứng ', pp.total_advance_allocated, ' đ nhưng thiếu chi tiết salary_advance_allocations. Cần kiểm tra đối soát nguồn.')
FROM payroll_periods pp
WHERE pp.status IN ('SUBMITTED', 'APPROVED')
  AND pp.total_advance_allocated > 0
  AND NOT EXISTS (
      SELECT 1 FROM salary_advance_allocations saa
      JOIN payroll_items pi ON saa.item_id = pi.item_id
      WHERE pi.period_id = pp.period_id
  );


-- ---------------------------------------------------------------------
-- 6. BẢNG CHI PHÍ VẬN HÀNH (OPERATING_EXPENSES - FT25)
-- Hỗ trợ: Nguồn phát sinh (MANUAL, PAYROLL, SALARY_ADVANCE), Hủy phiếu chi và Lý do
-- ---------------------------------------------------------------------
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
    CONSTRAINT uk_exp_payroll_period UNIQUE (payroll_period_id),
    CONSTRAINT uk_exp_salary_advance UNIQUE (salary_advance_id),
    CONSTRAINT fk_exp_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT fk_exp_payroll FOREIGN KEY (payroll_period_id) REFERENCES payroll_periods(period_id) ON DELETE RESTRICT,
    CONSTRAINT fk_exp_advance FOREIGN KEY (salary_advance_id) REFERENCES salary_advances(advance_id) ON DELETE RESTRICT,
    CONSTRAINT fk_exp_created_by FOREIGN KEY (created_by) REFERENCES users(user_id),
    CONSTRAINT fk_exp_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$
DROP PROCEDURE IF EXISTS sp_v11_upgrade_operating_expenses $$
CREATE PROCEDURE sp_v11_upgrade_operating_expenses()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND COLUMN_NAME = 'source_type') THEN
        ALTER TABLE operating_expenses ADD COLUMN source_type VARCHAR(30) NOT NULL DEFAULT 'MANUAL' AFTER payment_method;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND COLUMN_NAME = 'payroll_period_id') THEN
        ALTER TABLE operating_expenses ADD COLUMN payroll_period_id BIGINT NULL AFTER source_type;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND COLUMN_NAME = 'salary_advance_id') THEN
        ALTER TABLE operating_expenses ADD COLUMN salary_advance_id BIGINT NULL AFTER payroll_period_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND COLUMN_NAME = 'is_cancelled') THEN
        ALTER TABLE operating_expenses ADD COLUMN is_cancelled BOOLEAN NOT NULL DEFAULT FALSE AFTER description;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND COLUMN_NAME = 'cancelled_reason') THEN
        ALTER TABLE operating_expenses ADD COLUMN cancelled_reason VARCHAR(500) NULL AFTER is_cancelled;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND COLUMN_NAME = 'cancelled_by') THEN
        ALTER TABLE operating_expenses ADD COLUMN cancelled_by BIGINT NULL AFTER cancelled_reason;
        ALTER TABLE operating_expenses ADD CONSTRAINT fk_exp_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users(user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND COLUMN_NAME = 'cancelled_at') THEN
        ALTER TABLE operating_expenses ADD COLUMN cancelled_at DATETIME NULL AFTER cancelled_by;
    END IF;

    -- Thêm Unique constraints cho nguồn liên kết nếu chưa có
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND INDEX_NAME = 'uk_exp_payroll_period') THEN
        ALTER TABLE operating_expenses ADD CONSTRAINT uk_exp_payroll_period UNIQUE (payroll_period_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND INDEX_NAME = 'uk_exp_salary_advance') THEN
        ALTER TABLE operating_expenses ADD CONSTRAINT uk_exp_salary_advance UNIQUE (salary_advance_id);
    END IF;

    -- Thêm Khóa ngoại cho liên kết payroll và salary_advance nếu chưa có
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND CONSTRAINT_NAME = 'fk_exp_payroll') THEN
        ALTER TABLE operating_expenses ADD CONSTRAINT fk_exp_payroll FOREIGN KEY (payroll_period_id) REFERENCES payroll_periods(period_id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND CONSTRAINT_NAME = 'fk_exp_advance') THEN
        ALTER TABLE operating_expenses ADD CONSTRAINT fk_exp_advance FOREIGN KEY (salary_advance_id) REFERENCES salary_advances(advance_id) ON DELETE RESTRICT;
    END IF;
END $$
DELIMITER ;
CALL sp_v11_upgrade_operating_expenses();
DROP PROCEDURE IF EXISTS sp_v11_upgrade_operating_expenses;


-- ---------------------------------------------------------------------
-- 7. BẢNG CHÍNH SÁCH HOA HỒNG (COMMISSION_POLICIES - FT32)
-- Mặc định is_active = FALSE (Trạng thái bản nháp/chờ kích hoạt)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS commission_policies (
    policy_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_name VARCHAR(150) NOT NULL,
    service_id BIGINT NULL,
    role_id BIGINT NULL,
    staff_id BIGINT NULL,
    commission_type VARCHAR(20) NOT NULL DEFAULT 'PERCENTAGE',
    commission_value DECIMAL(15,2) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_cp_service FOREIGN KEY (service_id) REFERENCES services(service_id),
    CONSTRAINT fk_cp_role FOREIGN KEY (role_id) REFERENCES roles(role_id),
    CONSTRAINT fk_cp_staff FOREIGN KEY (staff_id) REFERENCES staff_profiles(staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$
DROP PROCEDURE IF EXISTS sp_v11_upgrade_commission_policies $$
CREATE PROCEDURE sp_v11_upgrade_commission_policies()
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
CALL sp_v11_upgrade_commission_policies();
DROP PROCEDURE IF EXISTS sp_v11_upgrade_commission_policies;


-- ---------------------------------------------------------------------
-- 8. BẢNG HOA HỒNG DỊCH VỤ PHÁT SINH (SERVICE_COMMISSIONS - FT32)
-- Liên kết khóa ngoại payroll_item_id phục vụ chốt hoa hồng vào bảng lương
-- ---------------------------------------------------------------------
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

DELIMITER $$
DROP PROCEDURE IF EXISTS sp_v11_upgrade_service_commissions $$
CREATE PROCEDURE sp_v11_upgrade_service_commissions()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'service_commissions' AND COLUMN_NAME = 'payroll_item_id') THEN
        ALTER TABLE service_commissions ADD COLUMN payroll_item_id BIGINT NULL AFTER status;
        ALTER TABLE service_commissions ADD CONSTRAINT fk_sc_payroll_item FOREIGN KEY (payroll_item_id) REFERENCES payroll_items(item_id) ON DELETE SET NULL;
    END IF;
END $$
DELIMITER ;
CALL sp_v11_upgrade_service_commissions();
DROP PROCEDURE IF EXISTS sp_v11_upgrade_service_commissions;


-- ---------------------------------------------------------------------
-- 9. BẢNG PHÂN PHỐI LỢI NHUẬN CHỦ PHÒNG KHÁM (OWNER_DISTRIBUTIONS & ITEMS - FT32)
-- ---------------------------------------------------------------------
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


-- ---------------------------------------------------------------------
-- 10. BẢNG NHẬT KÝ KIỂM TOÁN HỆ THỐNG (SYSTEM_AUDIT_LOGS)
-- Lưu vết hành động kiểm toán kế toán, quản trị tài chính append-only
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS system_audit_logs (
    log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    branch_id BIGINT NULL,
    action VARCHAR(50) NOT NULL,
    entity_table VARCHAR(60) NOT NULL,
    entity_id BIGINT NULL,
    old_values_json JSON NULL,
    new_values_json JSON NULL,
    ip_address VARCHAR(45) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_table_id (entity_table, entity_id),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_audit_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
