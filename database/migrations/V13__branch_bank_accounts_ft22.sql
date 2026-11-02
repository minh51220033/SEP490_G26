-- =============================================================================
-- Migration V13: Quản lý tài khoản ngân hàng nhận tiền theo chi nhánh & VietQR (FT22)
-- Tương thích: MySQL 8.0+ (Schema: cv_dcms_db)
-- Đặc điểm: Idempotent, tự phục hồi từ trạng thái dở dang, kiểm tra từng cột độc lập,
--           bảo toàn dữ liệu hiện có, không tự chèn tài khoản giả vào database thật.
-- =============================================================================
USE cv_dcms_db;

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v13_bank_accounts$$

CREATE PROCEDURE sp_upgrade_v13_bank_accounts()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- =========================================================================
    -- 1. BẢNG branch_bank_accounts (Tạo bảng hoặc tự bổ sung cột còn thiếu)
    -- =========================================================================
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLES 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts'
    ) THEN
        CREATE TABLE branch_bank_accounts (
            account_id BIGINT AUTO_INCREMENT PRIMARY KEY,
            branch_id BIGINT NOT NULL,
            bank_bin VARCHAR(20) NOT NULL COMMENT 'Mã BIN ngân hàng VietQR (VD: 970436)',
            bank_name VARCHAR(100) NOT NULL COMMENT 'Tên ngân hàng (VD: Vietcombank)',
            bank_short_name VARCHAR(50) NULL COMMENT 'Tên viết tắt (VD: VCB, MB, TCB)',
            account_number VARCHAR(50) NOT NULL COMMENT 'Số tài khoản ngân hàng',
            account_holder_name VARCHAR(150) NOT NULL COMMENT 'Tên chủ tài khoản (in hoa không dấu)',
            is_default BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Tối đa 1 tài khoản mặc định/chi nhánh',
            is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Trạng thái hoạt động',
            notes VARCHAR(255) NULL COMMENT 'Ghi chú',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
            default_branch_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_default = 1 THEN branch_id ELSE NULL END) STORED,
            INDEX idx_bba_branch_active (branch_id, is_active),
            UNIQUE KEY uk_bba_default_branch (default_branch_id),
            CONSTRAINT fk_bba_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    ELSE
        -- Nếu bảng đã tồn tại nhưng thiếu các cột, bổ sung độc lập từng cột
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'bank_bin') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN bank_bin VARCHAR(20) NOT NULL COMMENT 'Mã BIN ngân hàng VietQR';
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'bank_name') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN bank_name VARCHAR(100) NOT NULL COMMENT 'Tên ngân hàng';
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'bank_short_name') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN bank_short_name VARCHAR(50) NULL COMMENT 'Tên viết tắt';
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'account_number') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN account_number VARCHAR(50) NOT NULL COMMENT 'Số tài khoản';
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'account_holder_name') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN account_holder_name VARCHAR(150) NOT NULL COMMENT 'Tên chủ tài khoản';
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'is_default') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'is_active') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'notes') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN notes VARCHAR(255) NULL;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'created_at') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'updated_at') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN updated_at DATETIME NULL ON UPDATE CURRENT_TIMESTAMP;
        END IF;

        -- Generated Column default_branch_id
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND COLUMN_NAME = 'default_branch_id') THEN
            ALTER TABLE branch_bank_accounts ADD COLUMN default_branch_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_default = 1 THEN branch_id ELSE NULL END) STORED;
        END IF;

        -- Chỉ mục tìm kiếm theo chi nhánh & trạng thái
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND INDEX_NAME = 'idx_bba_branch_active') THEN
            CREATE INDEX idx_bba_branch_active ON branch_bank_accounts(branch_id, is_active);
        END IF;

        -- Ràng buộc Unique: Tối đa 1 tài khoản mặc định/chi nhánh
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND INDEX_NAME = 'uk_bba_default_branch') THEN
            ALTER TABLE branch_bank_accounts ADD UNIQUE KEY uk_bba_default_branch (default_branch_id);
        END IF;

        -- Khóa ngoại tới bảng branches
        IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'branch_bank_accounts' AND CONSTRAINT_NAME = 'fk_bba_branch') THEN
            ALTER TABLE branch_bank_accounts ADD CONSTRAINT fk_bba_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT;
        END IF;
    END IF;

    -- =========================================================================
    -- 2. BẢNG payments: Kiểm tra và bổ sung độc lập từng cột snapshot ngân hàng
    -- =========================================================================
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'bank_account_id') THEN
        ALTER TABLE payments ADD COLUMN bank_account_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'bank_bin') THEN
        ALTER TABLE payments ADD COLUMN bank_bin VARCHAR(20) NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'bank_name') THEN
        ALTER TABLE payments ADD COLUMN bank_name VARCHAR(100) NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'bank_account_number') THEN
        ALTER TABLE payments ADD COLUMN bank_account_number VARCHAR(50) NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'bank_account_holder') THEN
        ALTER TABLE payments ADD COLUMN bank_account_holder VARCHAR(150) NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'expected_transfer_content') THEN
        ALTER TABLE payments ADD COLUMN expected_transfer_content VARCHAR(100) NULL;
    END IF;

    -- =========================================================================
    -- 3. CHỈ MỤC & KHÓA NGOẠI TRÊN BẢNG payments
    -- =========================================================================
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_pmt_bank_account'
    ) THEN
        CREATE INDEX idx_pmt_bank_account ON payments(bank_account_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'payments' AND CONSTRAINT_NAME = 'fk_pmt_bank_account'
    ) THEN
        ALTER TABLE payments 
        ADD CONSTRAINT fk_pmt_bank_account 
        FOREIGN KEY (bank_account_id) REFERENCES branch_bank_accounts(account_id) ON DELETE RESTRICT;
    END IF;

END$$

DELIMITER ;

-- Thực thi nâng cấp schema cấu trúc
CALL sp_upgrade_v13_bank_accounts();

DROP PROCEDURE IF EXISTS sp_upgrade_v13_bank_accounts;

-- =============================================================================
-- GHI CHÚ QUẢN LÝ DỮ LIỆU TÀI KHOẢN NGÂN HÀNG (SEED DATA):
-- Nhằm tránh chèn tài khoản giả mạo vào database hoạt động thật của phòng khám,
-- dữ liệu tài khoản sẽ do Quản trị viên (Admin) cấu hình trực tiếp qua giao diện:
-- Đường dẫn: /admin/bank-accounts (Quản trị -> Tài khoản ngân hàng).
--
-- Trường hợp môi trường TEST/DEMO cần nạp tài khoản mẫu cho chi nhánh 1, 2, 3:
-- Có thể kích hoạt thủ công đoạn script bên dưới:
-- =============================================================================
/*
INSERT INTO branch_bank_accounts 
    (branch_id, bank_bin, bank_name, bank_short_name, account_number, account_holder_name, is_default, is_active, notes)
SELECT 1, '970436', 'Vietcombank', 'VCB', '0011004328888', 'NHA KHOA KY THUAT CAO CV DCMS', TRUE, TRUE, 'Tài khoản Vietcombank chính CN1'
FROM DUAL WHERE EXISTS (SELECT 1 FROM branches WHERE branch_id = 1)
  AND NOT EXISTS (SELECT 1 FROM branch_bank_accounts WHERE branch_id = 1 AND bank_bin = '970436' AND account_number = '0011004328888');

INSERT INTO branch_bank_accounts 
    (branch_id, bank_bin, bank_name, bank_short_name, account_number, account_holder_name, is_default, is_active, notes)
SELECT 2, '970407', 'Techcombank', 'TCB', '19036789999018', 'NHA KHOA KY THUAT CAO CV DCMS', TRUE, TRUE, 'Tài khoản Techcombank chính CN2'
FROM DUAL WHERE EXISTS (SELECT 1 FROM branches WHERE branch_id = 2)
  AND NOT EXISTS (SELECT 1 FROM branch_bank_accounts WHERE branch_id = 2 AND bank_bin = '970407' AND account_number = '19036789999018');
*/
