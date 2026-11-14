-- ====================================================================================================
-- CV-DCMS: 06_patients_demo.sql (NHÓM BỆNH NHÂN DEMO: TÀI KHOẢN PORTAL, HỒ SƠ BỆNH NHÂN & LIÊN KẾT NGƯỜI THÂN)
-- Đối chiếu 100% với Schema cv_dcms_db_backup.sql
-- Mật khẩu dùng chung: admin123 ($2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe)
-- An toàn khi chạy nhiều lần (Idempotent - ON DUPLICATE KEY UPDATE)
-- ====================================================================================================

SET NAMES utf8mb4;

-- 1. TÀI KHOẢN BỆNH NHÂN TRÊN PORTAL (users)
INSERT INTO users (user_id, username, password, email, phone_number, is_active, status, user_type, branch_id, custom_permissions_enabled, created_date, updated_at) VALUES
    (12, 'benhnhan01', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'benhnhan01@gmail.com', '0912345601', 1, 'ACTIVE', 'PATIENT', 1, 0, NOW(), NOW()),
    (13, 'benhnhan02', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'benhnhan02@gmail.com', '0912345602', 1, 'ACTIVE', 'PATIENT', 1, 0, NOW(), NOW()),
    (14, 'benhnhan03', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'benhnhan03@gmail.com', '0912345603', 1, 'ACTIVE', 'PATIENT', 2, 0, NOW(), NOW()),
    (15, 'benhnhan04', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'benhnhan04@gmail.com', '0912345604', 1, 'ACTIVE', 'PATIENT', 2, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    password     = VALUES(password),
    email        = VALUES(email),
    phone_number = VALUES(phone_number),
    status       = VALUES(status),
    is_active    = VALUES(is_active),
    updated_at   = NOW();

-- 2. GÁN ROLE BENH_NHAN (role_id = 6)
INSERT INTO user_roles (user_id, role_id) VALUES
    (12, 6),
    (13, 6),
    (14, 6),
    (15, 6)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- 3. HỒ SƠ BỆNH NHÂN (patient_profiles)
INSERT INTO patient_profiles (profile_id, user_id, patient_code, full_name, dob, gender, phone_number, id_card_number, address, has_allergies, allergy_details, has_underlying_disease, underlying_disease_details, medical_history_completed, preferred_branch_id, is_default, deposit_balance, status, created_at, updated_at) VALUES
    (501, 12, 'BN-SEED-001', 'Phạm Quốc Bảo',        '1992-06-12', 'Nam', '0912345601', '001092005501', 'Số 15 Ngõ 68 Cầu Giấy, Hà Nội',             0, 'Không dị ứng thuốc',                0, 'Không có bệnh nền',           1, 1, 1, 0.00,       'ACTIVE', NOW(), NOW()),
    (502, 13, 'BN-SEED-002', 'Lê Hoàng Phương Thảo', '1998-11-25', 'Nữ',  '0912345602', '001198005502', 'Chung cư Discovery Complex, 302 Cầu Giấy',   1, 'Dị ứng nhẹ nhóm kháng sinh Penicillin', 0, 'Huyết áp ổn định',        1, 1, 1, 0.00,       'ACTIVE', NOW(), NOW()),
    (503, 14, 'BN-SEED-003', 'Trần Đình Trọng',      '1985-04-10', 'Nam', '0912345603', '001085005503', 'Số 102 Thái Hà, Quận Đống Đa, Hà Nội',       0, 'Không',                             1, 'Tiểu đường tuýp 2 kiểm soát tốt', 1, 2, 1, 2000000.00, 'ACTIVE', NOW(), NOW()),
    (504, 15, 'BN-SEED-004', 'Nguyễn Ngọc Minh Anh', '2001-09-18', 'Nữ',  '0912345604', '001201005504', 'Số 28 Láng Hạ, Quận Đống Đa, Hà Nội',        0, 'Không',                             0, 'Không',                       1, 2, 1, 0.00,       'ACTIVE', NOW(), NOW()),
    (505, 12, 'BN-SEED-005', 'Phạm Gia Huy (Bé)',    '2016-03-20', 'Nam', '0912345601', NULL,           'Số 15 Ngõ 68 Cầu Giấy, Hà Nội',             0, 'Không',                             0, 'Không',                       1, 1, 0, 0.00,       'ACTIVE', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    full_name                 = VALUES(full_name),
    phone_number              = VALUES(phone_number),
    address                   = VALUES(address),
    allergy_details           = VALUES(allergy_details),
    medical_history_completed = VALUES(medical_history_completed),
    status                    = VALUES(status),
    updated_at                = NOW();

-- 4. LIÊN KẾT TÀI KHOẢN & HỒ SƠ BỆNH NHÂN TRÊN PORTAL (account_patient_profiles)
INSERT INTO account_patient_profiles (id, user_id, profile_id, relationship_type, is_primary, can_book, can_view_medical_record, can_MAKE_payment, created_at) VALUES
    (501, 12, 501, 'SELF',  1, 1, 1, 1, NOW()),
    (502, 13, 502, 'SELF',  1, 1, 1, 1, NOW()),
    (503, 14, 503, 'SELF',  1, 1, 1, 1, NOW()),
    (504, 15, 504, 'SELF',  1, 1, 1, 1, NOW()),
    (505, 12, 505, 'CHILD', 0, 1, 1, 1, NOW())
ON DUPLICATE KEY UPDATE
    relationship_type = VALUES(relationship_type),
    is_primary        = VALUES(is_primary);

-- 5. VÍ ĐIỂM THÀNH VIÊN (loyalty_wallets)
INSERT INTO loyalty_wallets (wallet_id, profile_id, tier_id, available_points, lifetime_points, total_spent, updated_at) VALUES
    (501, 501, 1, 230,  230,  2300000.00,  NOW()),
    (502, 502, 1, 50,   50,   500000.00,   NOW()),
    (503, 503, 2, 1200, 1200, 12000000.00, NOW()),
    (504, 504, 1, 0,    0,    0.00,        NOW())
ON DUPLICATE KEY UPDATE
    tier_id          = VALUES(tier_id),
    available_points = VALUES(available_points),
    total_spent      = VALUES(total_spent),
    updated_at       = NOW();
