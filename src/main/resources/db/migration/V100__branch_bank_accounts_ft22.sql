-- FT22: tài khoản nhận tiền theo chi nhánh.
-- This is intentionally idempotent: some local databases may already have part
-- of the FT22 schema from the former manually run SQL scripts.

CREATE TABLE IF NOT EXISTS branch_bank_accounts (
    account_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    bank_bin VARCHAR(20) NOT NULL,
    bank_name VARCHAR(100) NOT NULL,
    bank_short_name VARCHAR(50) NULL,
    account_number VARCHAR(50) NOT NULL,
    account_holder_name VARCHAR(150) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    notes VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
    default_branch_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN is_default = 1 THEN branch_id ELSE NULL END) STORED,
    INDEX idx_bba_branch_active (branch_id, is_active),
    UNIQUE KEY uk_bba_default_branch (default_branch_id),
    CONSTRAINT fk_bba_branch
        FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The Payment entity added by FT22 also requires these snapshot columns. MySQL
-- does not support ADD COLUMN IF NOT EXISTS, so use metadata checks to keep the
-- migration safe for a partially upgraded local schema.
DELIMITER $$

CREATE PROCEDURE add_ft22_payment_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payments'
          AND column_name = 'bank_account_id'
    ) THEN
        ALTER TABLE payments ADD COLUMN bank_account_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payments'
          AND column_name = 'bank_bin'
    ) THEN
        ALTER TABLE payments ADD COLUMN bank_bin VARCHAR(20) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payments'
          AND column_name = 'bank_name'
    ) THEN
        ALTER TABLE payments ADD COLUMN bank_name VARCHAR(100) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payments'
          AND column_name = 'bank_account_number'
    ) THEN
        ALTER TABLE payments ADD COLUMN bank_account_number VARCHAR(50) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payments'
          AND column_name = 'bank_account_holder'
    ) THEN
        ALTER TABLE payments ADD COLUMN bank_account_holder VARCHAR(150) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payments'
          AND column_name = 'expected_transfer_content'
    ) THEN
        ALTER TABLE payments ADD COLUMN expected_transfer_content VARCHAR(100) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'payments'
          AND index_name = 'idx_pmt_bank_account'
    ) THEN
        CREATE INDEX idx_pmt_bank_account ON payments(bank_account_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_schema = DATABASE() AND table_name = 'payments'
          AND constraint_name = 'fk_pmt_bank_account'
    ) THEN
        ALTER TABLE payments
            ADD CONSTRAINT fk_pmt_bank_account
            FOREIGN KEY (bank_account_id) REFERENCES branch_bank_accounts(account_id)
            ON DELETE RESTRICT;
    END IF;
END$$

CALL add_ft22_payment_columns()$$
DROP PROCEDURE add_ft22_payment_columns$$

DELIMITER ;
