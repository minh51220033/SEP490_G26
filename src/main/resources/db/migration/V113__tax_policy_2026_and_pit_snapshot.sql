-- =============================================================================
-- Migration V113: Phiên bản chính sách thuế TNCN 2026 (NQ 110/2025/UBTVQH15
-- và Luật Thuế TNCN số 109/2025/QH15 - biểu 5 bậc) + snapshot căn cứ PIT.
-- Quy tắc bảo toàn dữ liệu cũ:
--   - Không sửa đè PIT_VN_2020 thành số liệu 2026 (chỉ chốt effective_to = '2025-12-31'
--     để tránh chồng khoảng hiệu lực khi thêm PIT_VN_2026 từ '2026-01-01').
--   - Không tính lại hoặc thay đổi dữ liệu payroll_items / PAID đã chốt.
-- =============================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v113_tax_policy_2026$$

CREATE PROCEDURE sp_upgrade_v113_tax_policy_2026()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- 1. THÊM LOẠI BIỂU THUẾ LŨY TIẾN TRÊN TAX_POLICIES
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'tax_policies' AND COLUMN_NAME = 'progressive_schedule'
    ) THEN
        ALTER TABLE tax_policies
            ADD COLUMN progressive_schedule VARCHAR(30) NOT NULL DEFAULT 'PROGRESSIVE_7_2007' AFTER non_resident_tax_rate;
    END IF;

    -- 2. ĐÓNG KHOẢNG HIỆU LỰC POLICY 2020 TẠI 2025-12-31 TRƯỚC KHI MỞ POLICY 2026
    UPDATE tax_policies
    SET effective_to = '2025-12-31',
        progressive_schedule = 'PROGRESSIVE_7_2007'
    WHERE policy_code = 'PIT_VN_2020'
      AND effective_to IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM (SELECT policy_code FROM tax_policies WHERE policy_code = 'PIT_VN_2026') tmp
      );

    -- 3. THÊM PHIÊN BẢN POLICY 2026 (15.5M / 6.2M / BIỂU 5 BẬC)
    IF NOT EXISTS (SELECT 1 FROM tax_policies WHERE policy_code = 'PIT_VN_2026') THEN
        INSERT INTO tax_policies (
            policy_code, policy_name, effective_from, effective_to,
            personal_relief, dependent_relief, casual_tax_rate, non_resident_tax_rate,
            progressive_schedule, insurance_employee_rate, insurance_employer_rate,
            legal_reference, is_active
        ) VALUES (
            'PIT_VN_2026',
            'Chính sách thuế TNCN 2026 (NQ 110/2025/UBTVQH15 & Luật 109/2025/QH15 - 5 bậc)',
            '2026-01-01',
            NULL,
            15500000.00,
            6200000.00,
            10.00,
            20.00,
            'PROGRESSIVE_5_2026',
            10.50,
            21.50,
            'Nghị quyết 110/2025/UBTVQH15 (GTGC 15.5M/6.2M) & Luật Thuế TNCN 109/2025/QH15 (Biểu 5 bậc)',
            TRUE
        );
    END IF;

    -- 4. SNAPSHOT PHƯƠNG PHÁP & BIỂU THUẾ PIT TRÊN PAYROLL_ITEMS
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'pit_calculation_method'
    ) THEN
        ALTER TABLE payroll_items
            ADD COLUMN pit_calculation_method VARCHAR(60) NULL AFTER tax_policy_code;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'pit_schedule_snapshot'
    ) THEN
        ALTER TABLE payroll_items
            ADD COLUMN pit_schedule_snapshot VARCHAR(120) NULL AFTER pit_calculation_method;
    END IF;

END$$

DELIMITER ;

CALL sp_upgrade_v113_tax_policy_2026();

DROP PROCEDURE IF EXISTS sp_upgrade_v113_tax_policy_2026;
