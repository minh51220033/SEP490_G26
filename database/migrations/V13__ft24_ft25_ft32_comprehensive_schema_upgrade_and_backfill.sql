-- =====================================================================
-- CV-DCMS MIGRATION V13: TOÀN DIỆN SCHEMA FT24, FT25, FT32 & BACKFILL DỮ LIỆU
-- Đảm bảo nâng cấp an toàn từ schema cũ (db.sql / V1 - V12) lên đầy đủ Entity
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- 1. BẢNG REFUNDS (FT24): idempotency_key, reduce_invoice_total
-- ---------------------------------------------------------------------
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_refunds_schema $$
CREATE PROCEDURE upgrade_refunds_schema()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refunds' AND COLUMN_NAME = 'idempotency_key'
    ) THEN
        ALTER TABLE refunds ADD COLUMN idempotency_key VARCHAR(100) NULL AFTER refund_code;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refunds' AND INDEX_NAME = 'uk_refund_idempotency_key'
    ) THEN
        ALTER TABLE refunds ADD CONSTRAINT uk_refund_idempotency_key UNIQUE (idempotency_key);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refunds' AND COLUMN_NAME = 'reduce_invoice_total'
    ) THEN
        ALTER TABLE refunds ADD COLUMN reduce_invoice_total BOOLEAN NOT NULL DEFAULT FALSE AFTER reason;
    END IF;
END $$
DELIMITER ;
CALL upgrade_refunds_schema();
DROP PROCEDURE IF EXISTS upgrade_refunds_schema;

-- ---------------------------------------------------------------------
-- 2. BẢNG PAYROLL_PERIODS (FT32): Đầy đủ các cột total, actor, payment
-- ---------------------------------------------------------------------
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_payroll_periods_schema $$
CREATE PROCEDURE upgrade_payroll_periods_schema()
BEGIN
    -- Đảm bảo status enum hỗ trợ đủ SUBMITTED, APPROVED, DRAFT, PAID
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' 
          AND COLUMN_NAME = 'status' AND DATA_TYPE = 'enum'
    ) THEN
        ALTER TABLE payroll_periods MODIFY COLUMN status ENUM('DRAFT','SUBMITTED','APPROVED','PAID') NOT NULL DEFAULT 'DRAFT';
    END IF;

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
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'submitted_by') THEN
        ALTER TABLE payroll_periods ADD COLUMN submitted_by BIGINT NULL AFTER status;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'submitted_at') THEN
        ALTER TABLE payroll_periods ADD COLUMN submitted_at DATETIME NULL AFTER submitted_by;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'approved_by') THEN
        ALTER TABLE payroll_periods ADD COLUMN approved_by BIGINT NULL AFTER submitted_at;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'approved_at') THEN
        ALTER TABLE payroll_periods ADD COLUMN approved_at DATETIME NULL AFTER approved_by;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'rejection_reason') THEN
        ALTER TABLE payroll_periods ADD COLUMN rejection_reason VARCHAR(500) NULL AFTER approved_at;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'paid_by') THEN
        ALTER TABLE payroll_periods ADD COLUMN paid_by BIGINT NULL AFTER rejection_reason;
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
CALL upgrade_payroll_periods_schema();
DROP PROCEDURE IF EXISTS upgrade_payroll_periods_schema;

-- ---------------------------------------------------------------------
-- 3. BẢNG PAYROLL_ITEMS (FT32): bonus, deduction, advance, note & UNIQUE
-- ---------------------------------------------------------------------
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_payroll_items_schema $$
CREATE PROCEDURE upgrade_payroll_items_schema()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'period_month') THEN
        ALTER TABLE payroll_items ADD COLUMN period_month INT NULL AFTER staff_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'period_year') THEN
        ALTER TABLE payroll_items ADD COLUMN period_year INT NULL AFTER period_month;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'bonus_amount') THEN
        ALTER TABLE payroll_items ADD COLUMN bonus_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_allowance;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'deduction_amount') THEN
        ALTER TABLE payroll_items ADD COLUMN deduction_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_commission;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'advance_allocated_amount') THEN
        ALTER TABLE payroll_items ADD COLUMN advance_allocated_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER net_salary;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'remaining_payable') THEN
        ALTER TABLE payroll_items ADD COLUMN remaining_payable DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER advance_allocated_amount;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'note') THEN
        ALTER TABLE payroll_items ADD COLUMN note VARCHAR(500) NULL AFTER payment_status;
    END IF;

    -- Backfill month & year
    UPDATE payroll_items pi
    JOIN payroll_periods pp ON pi.period_id = pp.period_id
    SET pi.period_month = pp.month,
        pi.period_year = pp.year
    WHERE pi.period_month IS NULL OR pi.period_year IS NULL;

    -- Backfill net_salary: CHỈ cập nhật khi net_salary rỗng hoặc bằng 0, bảo toàn dữ liệu lịch sử và cấu phần thực tế
    UPDATE payroll_items
    SET net_salary = GREATEST(0, COALESCE(actual_base_salary, base_salary, 0) + COALESCE(total_allowance, 0) + COALESCE(total_commission, 0) + COALESCE(bonus_amount, 0) - COALESCE(deduction_amount, 0))
    WHERE net_salary IS NULL OR net_salary = 0.00;

    -- Backfill remaining_payable:
    -- Xử lý trường hợp cột vừa được thêm (nhận default 0.00) hoặc NULL:
    -- Nếu net_salary > advance_allocated_amount mà remaining_payable = 0 (do default lúc thêm cột),
    -- thì remaining_payable phải được nâng thành (net_salary - advance_allocated_amount).
    -- Nếu advance_allocated_amount >= net_salary, giữ nguyên 0đ (lương đã được ứng hết hợp lệ).
    UPDATE payroll_items
    SET remaining_payable = GREATEST(0, COALESCE(net_salary, 0) - COALESCE(advance_allocated_amount, 0))
    WHERE remaining_payable IS NULL 
       OR (remaining_payable = 0.00 AND COALESCE(net_salary, 0) > COALESCE(advance_allocated_amount, 0));

    -- Backup bảng nếu phát hiện trùng lặp dữ liệu staff/month/year trước khi add UNIQUE
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
        GROUP BY staff_id, period_month, period_year
        HAVING COUNT(*) > 1
    ) dup ON pi.staff_id = dup.staff_id AND pi.period_month = dup.period_month AND pi.period_year = dup.period_year;

    -- Thêm Unique constraint nếu không có trùng lặp
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
CALL upgrade_payroll_items_schema();
DROP PROCEDURE IF EXISTS upgrade_payroll_items_schema;

-- ---------------------------------------------------------------------
-- 4. BACKFILL TỔNG KỲ LƯƠNG TỪ CÁC DÒNG LƯƠNG HỢP LỆ VÀ ĐỐI SOÁT NGOẠI LỆ
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
-- 4. BẢNG SALARY_ADVANCES & SALARY_ADVANCE_ALLOCATIONS (FT32)
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
    INDEX idx_adv_branch_date (branch_id, paid_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS salary_advance_allocations (
    allocation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    advance_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    allocated_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    status VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_saa_advance (advance_id),
    INDEX idx_saa_item (item_id)
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
       CONCAT('Kỳ lương có tổng tạm ứng ', pp.total_advance_allocated, ' đ nhưng thiếu chi tiết salary_advance_allocations. Cần trả về DRAFT để đối soát tái duyệt.')
FROM payroll_periods pp
WHERE pp.status IN ('SUBMITTED', 'APPROVED')
  AND pp.total_advance_allocated > 0
  AND NOT EXISTS (
      SELECT 1 FROM salary_advance_allocations saa
      JOIN payroll_items pi ON saa.item_id = pi.item_id
      WHERE pi.period_id = pp.period_id
  );

-- ---------------------------------------------------------------------
-- 5. BẢNG OPERATING_EXPENSES (FT25): source_type, payroll_period_id, salary_advance_id, huỷ
-- ---------------------------------------------------------------------
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_operating_expenses_schema $$
CREATE PROCEDURE upgrade_operating_expenses_schema()
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
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND COLUMN_NAME = 'cancelled_at') THEN
        ALTER TABLE operating_expenses ADD COLUMN cancelled_at DATETIME NULL AFTER cancelled_by;
    END IF;

    -- Thêm Unique constraints cho nguồn liên kết
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND INDEX_NAME = 'uk_exp_payroll_period') THEN
        ALTER TABLE operating_expenses ADD CONSTRAINT uk_exp_payroll_period UNIQUE (payroll_period_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operating_expenses' AND INDEX_NAME = 'uk_exp_salary_advance') THEN
        ALTER TABLE operating_expenses ADD CONSTRAINT uk_exp_salary_advance UNIQUE (salary_advance_id);
    END IF;
END $$
DELIMITER ;
CALL upgrade_operating_expenses_schema();
DROP PROCEDURE IF EXISTS upgrade_operating_expenses_schema;

-- ---------------------------------------------------------------------
-- 6. BẢNG SERVICE_COMMISSIONS (FT32): payroll_item_id
-- ---------------------------------------------------------------------
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_service_commissions_schema $$
CREATE PROCEDURE upgrade_service_commissions_schema()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'service_commissions' AND COLUMN_NAME = 'payroll_item_id') THEN
        ALTER TABLE service_commissions ADD COLUMN payroll_item_id BIGINT NULL AFTER status;
        ALTER TABLE service_commissions ADD CONSTRAINT fk_sc_payroll_item FOREIGN KEY (payroll_item_id) REFERENCES payroll_items(item_id) ON DELETE SET NULL;
    END IF;
END $$
DELIMITER ;
CALL upgrade_service_commissions_schema();
DROP PROCEDURE IF EXISTS upgrade_service_commissions_schema;

-- ---------------------------------------------------------------------
-- 7. BẢNG SYSTEM_AUDIT_LOGS: Đảm bảo tồn tại cho audit append-only
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
