-- =============================================================================
-- Migration V108: Cấu hình thuế, VAT và Thuế TNCN trong luồng tính lương (FT29, FT32)
-- Căn cứ pháp lý:
--   - Luật Thuế TNCN số 04/2007/QH12 & Nghị quyết 954/2020/UBTVQH14 (Giảm trừ bản thân 11M, người phụ thuộc 4.4M)
--   - Thông tư 111/2013/TT-BTC & Thông tư 80/2021/TT-BTC
--   - Luật Thuế GTGT số 13/2008/QH12 (Khoản 9 Điều 5: Dịch vụ khám chữa bệnh không chịu thuế GTGT)
--   - Luật Thuế TNDN số 14/2008/QH12 & Thông tư 40/2021/TT-BTC
-- =============================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v108_tax_system$$

CREATE PROCEDURE sp_upgrade_v108_tax_system()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- 1. BẢNG CẤU HÌNH CHÍNH SÁCH THUẾ TNCN & BẢO HIỂM (TAX_POLICIES)
    CREATE TABLE IF NOT EXISTS tax_policies (
        policy_id BIGINT AUTO_INCREMENT PRIMARY KEY,
        policy_code VARCHAR(50) NOT NULL UNIQUE,
        policy_name VARCHAR(150) NOT NULL,
        effective_from DATE NOT NULL,
        effective_to DATE NULL,
        personal_relief DECIMAL(15,2) NOT NULL DEFAULT 11000000.00,
        dependent_relief DECIMAL(15,2) NOT NULL DEFAULT 4400000.00,
        casual_tax_rate DECIMAL(5,2) NOT NULL DEFAULT 10.00,
        non_resident_tax_rate DECIMAL(5,2) NOT NULL DEFAULT 20.00,
        insurance_employee_rate DECIMAL(5,2) NOT NULL DEFAULT 10.50,
        insurance_employer_rate DECIMAL(5,2) NOT NULL DEFAULT 21.50,
        legal_reference VARCHAR(255) NOT NULL,
        is_active BOOLEAN NOT NULL DEFAULT TRUE,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_tp_dates (effective_from, effective_to)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    -- 2. BẢNG CẤU HÌNH THUẾ ĐƠN VỊ KINH DOANH THEO CHI NHÁNH / PHÁP NHÂN (BRANCH_TAX_CONFIGS)
    CREATE TABLE IF NOT EXISTS branch_tax_configs (
        config_id BIGINT AUTO_INCREMENT PRIMARY KEY,
        branch_id BIGINT NOT NULL,
        entity_type VARCHAR(30) NOT NULL DEFAULT 'ENTERPRISE',
        tax_regime VARCHAR(30) NOT NULL DEFAULT 'CIT_STANDARD',
        cit_rate DECIMAL(5,2) NULL DEFAULT 20.00,
        service_tax_rate DECIMAL(5,2) NULL DEFAULT 2.00,
        medicine_tax_rate DECIMAL(5,2) NULL DEFAULT 0.50,
        effective_from DATE NOT NULL,
        effective_to DATE NULL,
        legal_reference VARCHAR(255) NULL,
        notes VARCHAR(500) NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_btc_branch_dates (branch_id, effective_from, effective_to),
        CONSTRAINT fk_btc_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    -- 3. MỞ RỘNG THÔNG TIN THUẾ TRÊN HỒ SƠ NHÂN VIÊN (STAFF_PROFILES)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'tax_code'
    ) THEN
        ALTER TABLE staff_profiles ADD COLUMN tax_code VARCHAR(30) NULL AFTER practice_license_no;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'is_tax_resident'
    ) THEN
        ALTER TABLE staff_profiles ADD COLUMN is_tax_resident BOOLEAN NOT NULL DEFAULT TRUE AFTER tax_code;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'contract_type'
    ) THEN
        ALTER TABLE staff_profiles ADD COLUMN contract_type VARCHAR(30) NOT NULL DEFAULT 'FULL_CONTRACT' AFTER is_tax_resident;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'has_personal_deduction'
    ) THEN
        ALTER TABLE staff_profiles ADD COLUMN has_personal_deduction BOOLEAN NOT NULL DEFAULT TRUE AFTER contract_type;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'dependent_count'
    ) THEN
        ALTER TABLE staff_profiles ADD COLUMN dependent_count INT NOT NULL DEFAULT 0 AFTER has_personal_deduction;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'has_tax_commitment'
    ) THEN
        ALTER TABLE staff_profiles ADD COLUMN has_tax_commitment BOOLEAN NOT NULL DEFAULT FALSE AFTER dependent_count;
    END IF;

    -- 4. MỞ RỘNG BẢNG DÒNG LƯƠNG NHÂN VIÊN (PAYROLL_ITEMS)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'gross_salary'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN gross_salary DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_commission;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'non_taxable_income'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN non_taxable_income DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER gross_salary;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'insurance_employee_deduction'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN insurance_employee_deduction DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER non_taxable_income;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'insurance_employer_amount'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN insurance_employer_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER insurance_employee_deduction;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'personal_deduction_amount'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN personal_deduction_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER insurance_employer_amount;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'dependent_deduction_amount'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN dependent_deduction_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER personal_deduction_amount;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'taxable_income'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN taxable_income DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER dependent_deduction_amount;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'assessable_income'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN assessable_income DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER taxable_income;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'pit_amount'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN pit_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER assessable_income;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'tax_policy_code'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN tax_policy_code VARCHAR(50) NULL AFTER pit_amount;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'tax_calculation_detail'
    ) THEN
        ALTER TABLE payroll_items ADD COLUMN tax_calculation_detail VARCHAR(1000) NULL AFTER tax_policy_code;
    END IF;

    -- 5. MỞ RỘNG BẢNG KỲ LƯƠNG (PAYROLL_PERIODS)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_gross_salary'
    ) THEN
        ALTER TABLE payroll_periods ADD COLUMN total_gross_salary DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_remaining_payable;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_insurance_employee'
    ) THEN
        ALTER TABLE payroll_periods ADD COLUMN total_insurance_employee DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_gross_salary;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_insurance_employer'
    ) THEN
        ALTER TABLE payroll_periods ADD COLUMN total_insurance_employer DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_insurance_employee;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'total_pit_amount'
    ) THEN
        ALTER TABLE payroll_periods ADD COLUMN total_pit_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_insurance_employer;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_periods' AND COLUMN_NAME = 'tax_policy_code'
    ) THEN
        ALTER TABLE payroll_periods ADD COLUMN tax_policy_code VARCHAR(50) NULL AFTER total_pit_amount;
    END IF;

    -- 6. MỞ RỘNG HÓA ĐƠN VÀ DÒNG HÓA ĐƠN (INVOICES & INVOICE_ITEMS)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'vat_rate'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN vat_rate DECIMAL(5,2) NOT NULL DEFAULT 0.00 AFTER payable_amount;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoice_items' AND COLUMN_NAME = 'vat_amount'
    ) THEN
        ALTER TABLE invoice_items ADD COLUMN vat_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER vat_rate;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'invoices' AND COLUMN_NAME = 'total_vat_amount'
    ) THEN
        ALTER TABLE invoices ADD COLUMN total_vat_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 AFTER total_amount;
    END IF;

    -- 7. SEED CHÍNH SÁCH THUẾ TNCN MẶC ĐỊNH (THEO NGHỊ QUYẾT 954/2020/UBTVQH14)
    IF NOT EXISTS (SELECT 1 FROM tax_policies WHERE policy_code = 'PIT_VN_2020') THEN
        INSERT INTO tax_policies (
            policy_code, policy_name, effective_from, effective_to,
            personal_relief, dependent_relief, casual_tax_rate, non_resident_tax_rate,
            insurance_employee_rate, insurance_employer_rate, legal_reference, is_active
        ) VALUES (
            'PIT_VN_2020',
            'Chính sách thuế TNCN & Bảo hiểm theo NQ 954/2020/UBTVQH14',
            '2020-07-01',
            NULL,
            11000000.00,
            4400000.00,
            10.00,
            20.00,
            10.50,
            21.50,
            'Nghị quyết 954/2020/UBTVQH14, Điều 22 Luật Thuế TNCN 04/2007/QH12, TT 111/2013/TT-BTC',
            TRUE
        );
    END IF;

    -- 8. SEED CẤU HÌNH THUẾ ĐƠN VỊ KINH DOANH CHO CÁC CHI NHÁNH HIỆN HỮU NẾU CHƯA CÓ
    INSERT INTO branch_tax_configs (
        branch_id, entity_type, tax_regime, cit_rate, service_tax_rate, medicine_tax_rate,
        effective_from, effective_to, legal_reference, notes
    )
    SELECT 
        b.branch_id,
        'ENTERPRISE',
        'CIT_STANDARD',
        20.00,
        2.00,
        0.50,
        '2024-01-01',
        NULL,
        'Luật Thuế TNDN 14/2008/QH12 sửa đổi',
        'Doanh nghiệp y tế chuẩn tính thuế TNDN 20% trên thu nhập tính thuế'
    FROM branches b
    WHERE NOT EXISTS (
        SELECT 1 FROM branch_tax_configs btc WHERE btc.branch_id = b.branch_id
    );

    -- 9. BACKFILL DỮ LIỆU GROSS_SALARY CHO CÁC DÒNG LƯƠNG CŨ
    UPDATE payroll_items
    SET gross_salary = GREATEST(0, COALESCE(actual_base_salary, base_salary, 0) + COALESCE(total_allowance, 0) + COALESCE(bonus_amount, 0) + COALESCE(total_commission, 0))
    WHERE gross_salary = 0.00 AND (actual_base_salary > 0 OR base_salary > 0);

    UPDATE payroll_periods pp
    SET pp.total_gross_salary = (
        SELECT COALESCE(SUM(pi.gross_salary), 0)
        FROM payroll_items pi
        WHERE pi.period_id = pp.period_id
    )
    WHERE pp.total_gross_salary = 0.00;

END$$

DELIMITER ;

CALL sp_upgrade_v108_tax_system();

DROP PROCEDURE IF EXISTS sp_upgrade_v108_tax_system;

