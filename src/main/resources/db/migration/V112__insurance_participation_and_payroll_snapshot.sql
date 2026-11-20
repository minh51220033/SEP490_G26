-- =============================================================================
-- Migration V112: Tách diện tham gia bảo hiểm khỏi hợp đồng/cư trú thuế
-- và snapshot thông số đóng bảo hiểm trên từng dòng lương (P0-08A).
-- Quy tắc bảo toàn dữ liệu cũ:
--   - Nhân sự hiện có giữ trạng thái UNVERIFIED (không tự suy từ contract_type).
--   - Các dòng lương lịch sử / PAID giữ nguyên số liệu đã chốt.
-- =============================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v112_insurance_separation$$

CREATE PROCEDURE sp_upgrade_v112_insurance_separation()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- 1. MỞ RỘNG HỒ SƠ NHÂN SỰ (STAFF_PROFILES)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'insurance_participation_status'
    ) THEN
        ALTER TABLE staff_profiles
            ADD COLUMN insurance_participation_status VARCHAR(30) NOT NULL DEFAULT 'UNVERIFIED' AFTER insurance_salary;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'insurance_effective_from'
    ) THEN
        ALTER TABLE staff_profiles
            ADD COLUMN insurance_effective_from DATE NULL AFTER insurance_participation_status;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'insurance_effective_to'
    ) THEN
        ALTER TABLE staff_profiles
            ADD COLUMN insurance_effective_to DATE NULL AFTER insurance_effective_from;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'insurance_employee_rate_override'
    ) THEN
        ALTER TABLE staff_profiles
            ADD COLUMN insurance_employee_rate_override DECIMAL(5,2) NULL AFTER insurance_effective_to;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'insurance_employer_rate_override'
    ) THEN
        ALTER TABLE staff_profiles
            ADD COLUMN insurance_employer_rate_override DECIMAL(5,2) NULL AFTER insurance_employee_rate_override;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'staff_profiles' AND COLUMN_NAME = 'insurance_exemption_reason'
    ) THEN
        ALTER TABLE staff_profiles
            ADD COLUMN insurance_exemption_reason VARCHAR(255) NULL AFTER insurance_employer_rate_override;
    END IF;

    -- 2. SNAPSHOT THÔNG SỐ ĐÓNG BẢO HIỂM TRÊN DÒNG LƯƠNG (PAYROLL_ITEMS)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'insurance_participation_status'
    ) THEN
        ALTER TABLE payroll_items
            ADD COLUMN insurance_participation_status VARCHAR(30) NULL AFTER insurance_employer_amount;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'insurance_base_salary_snapshot'
    ) THEN
        ALTER TABLE payroll_items
            ADD COLUMN insurance_base_salary_snapshot DECIMAL(15,2) NULL AFTER insurance_participation_status;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'insurance_employee_rate_snapshot'
    ) THEN
        ALTER TABLE payroll_items
            ADD COLUMN insurance_employee_rate_snapshot DECIMAL(5,2) NULL AFTER insurance_base_salary_snapshot;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'insurance_employer_rate_snapshot'
    ) THEN
        ALTER TABLE payroll_items
            ADD COLUMN insurance_employer_rate_snapshot DECIMAL(5,2) NULL AFTER insurance_employee_rate_snapshot;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payroll_items' AND COLUMN_NAME = 'insurance_basis_note'
    ) THEN
        ALTER TABLE payroll_items
            ADD COLUMN insurance_basis_note VARCHAR(500) NULL AFTER insurance_employer_rate_snapshot;
    END IF;

END$$

DELIMITER ;

CALL sp_upgrade_v112_insurance_separation();

DROP PROCEDURE IF EXISTS sp_upgrade_v112_insurance_separation;
