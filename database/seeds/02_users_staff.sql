-- ====================================================================================================
-- CV-DCMS: 02_users_staff.sql (NHÓM NHÂN SỰ: TÀI KHOẢN QUẢN TRỊ, BÁC SĨ, LỄ TÂN, KHO, KẾ TOÁN & HỒ SƠ STAFF)
-- Đối chiếu 100% với Schema cv_dcms_db_backup.sql
-- Mật khẩu dùng chung cho tất cả tài khoản: admin123 ($2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe)
-- Yêu cầu chạy sau 01_roles_permissions.sql và 03_branches_config.sql
-- ====================================================================================================

SET NAMES utf8mb4;

-- 1. TÀI KHOẢN QUẢN TRỊ & NHÂN VIÊN (users)
INSERT INTO users (user_id, username, password, email, phone_number, is_active, status, user_type, branch_id, custom_permissions_enabled, created_date, updated_at) VALUES
    (1,  'admin',       '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'admin@dentalclinic.com',       '0988888888', 1, 'ACTIVE', 'STAFF', 1, 0, NOW(), NOW()),
    -- Nhân sự Cơ sở 1 (CN01 - Cầu Giấy)
    (2,  'bacsi01',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'bacsi01@cuongvietdental.vn',   '0901000101', 1, 'ACTIVE', 'STAFF', 1, 0, NOW(), NOW()),
    (3,  'bacsi02',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'bacsi02@cuongvietdental.vn',   '0901000102', 1, 'ACTIVE', 'STAFF', 1, 0, NOW(), NOW()),
    (4,  'letan01',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'letan01@cuongvietdental.vn',   '0901000103', 1, 'ACTIVE', 'STAFF', 1, 0, NOW(), NOW()),
    (5,  'quanlykho01', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'kho01@cuongvietdental.vn',     '0901000104', 1, 'ACTIVE', 'STAFF', 1, 0, NOW(), NOW()),
    (6,  'ketoan01',    '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'ketoan01@cuongvietdental.vn',  '0901000105', 1, 'ACTIVE', 'STAFF', 1, 0, NOW(), NOW()),
    -- Nhân sự Cơ sở 2 (CN02 - Đống Đa)
    (7,  'bacsi03',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'bacsi03@cuongvietdental.vn',   '0902000201', 1, 'ACTIVE', 'STAFF', 2, 0, NOW(), NOW()),
    (8,  'bacsi04',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'bacsi04@cuongvietdental.vn',   '0902000202', 1, 'ACTIVE', 'STAFF', 2, 0, NOW(), NOW()),
    (9,  'letan02',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'letan02@cuongvietdental.vn',   '0902000203', 1, 'ACTIVE', 'STAFF', 2, 0, NOW(), NOW()),
    (10, 'quanlykho02', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'kho02@cuongvietdental.vn',     '0902000204', 1, 'ACTIVE', 'STAFF', 2, 0, NOW(), NOW()),
    (11, 'ketoan02',    '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'ketoan02@cuongvietdental.vn',  '0902000205', 1, 'ACTIVE', 'STAFF', 2, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    password     = VALUES(password),
    email        = VALUES(email),
    phone_number = VALUES(phone_number),
    status       = VALUES(status),
    is_active    = VALUES(is_active),
    updated_at   = NOW();

-- 2. GÁN VAI TRÒ CHO TÀI KHOẢN NHÂN SỰ (user_roles)
INSERT INTO user_roles (user_id, role_id) VALUES
    (1,  1), -- admin -> ADMIN
    (2,  2), -- bacsi01 -> BAC_SI
    (3,  2), -- bacsi02 -> BAC_SI
    (4,  3), -- letan01 -> LE_TAN
    (5,  4), -- quanlykho01 -> QUAN_LY_KHO
    (6,  5), -- ketoan01 -> KE_TOAN
    (7,  2), -- bacsi03 -> BAC_SI
    (8,  2), -- bacsi04 -> BAC_SI
    (9,  3), -- letan02 -> LE_TAN
    (10, 4), -- quanlykho02 -> QUAN_LY_KHO
    (11, 5)  -- ketoan02 -> KE_TOAN
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- 3. HỒ SƠ NHÂN SỰ (staff_profiles)
INSERT INTO staff_profiles (staff_id, user_id, staff_code, full_name, gender, dob, phone_number, email, id_card_number, department, position_title, specialty, practice_license_no, moh_doctor_code, hire_date, contract_type, dependent_count, has_personal_deduction, has_tax_commitment, is_tax_resident, base_salary, allowance_amount, insurance_salary, bank_name, bank_account_number, tax_code, status, created_at, updated_at) VALUES
    (1,  1,  'NV-ADMIN',   'Nguyễn Việt Cường',       'MALE',   '1982-03-15', '0988888888', 'admin@dentalclinic.com',      '001082011001', 'Ban Giám Đốc',    'Giám đốc Điều hành',        'Quản trị Y tế & Nha khoa',          '001001/BYT-CCHN', 'BS.001001', '2020-01-01', 'FULL_CONTRACT', 2, 1, 0, 1, 35000000.00, 5000000.00, 20000000.00, 'Vietcombank', '0011001122334', '8001001001', 'ACTIVE', NOW(), NOW()),
    (2,  2,  'BS-CN01-01', 'ThS.BS Lê Hoàng Nam',     'MALE',   '1986-04-15', '0901000101', 'bacsi01@cuongvietdental.vn',  '001086011002', 'Khối Lâm sàng',   'Trưởng khoa Cấy ghép',      'Cấy ghép Implant & Phẫu thuật RHM', '012456/HNO-CCHN', 'BS.012456', '2021-03-01', 'FULL_CONTRACT', 2, 1, 0, 1, 28000000.00, 4000000.00, 20000000.00, 'MBBank',      '0901000101888', '8001001002', 'ACTIVE', NOW(), NOW()),
    (3,  3,  'BS-CN01-02', 'BS.CKI Trần Mai Hoa',    'FEMALE', '1990-11-20', '0901000102', 'bacsi02@cuongvietdental.vn',  '001190011003', 'Khối Lâm sàng',   'Bác sĩ Chuyên khoa RHM',    'Chỉnh nha & Nha khoa Thẩm mỹ',      '015789/HNO-CCHN', 'BS.015789', '2021-06-15', 'FULL_CONTRACT', 1, 1, 0, 1, 25000000.00, 3500000.00, 18000000.00, 'Techcombank', '1903456789012', '8001001003', 'ACTIVE', NOW(), NOW()),
    (4,  4,  'LT-CN01-01', 'Hoàng Thu Trang',         'FEMALE', '1997-08-25', '0901000103', 'letan01@cuongvietdental.vn',  '001197011004', 'Lễ tân & CSKH',   'Trưởng nhóm Lễ tân & CSKH', 'Chăm sóc khách hàng Y tế',          NULL,              NULL,        '2022-02-01', 'FULL_CONTRACT', 0, 1, 0, 1, 11000000.00, 1500000.00, 10000000.00, 'MBBank',      '0901000103888', '8001001004', 'ACTIVE', NOW(), NOW()),
    (5,  5,  'KH-CN01-01', 'Đỗ Minh Quân',            'MALE',   '1993-02-18', '0901000104', 'kho01@cuongvietdental.vn',    '001093011005', 'Quản lý Dược/Kho','Thủ kho Vật tư & Dược',      'Dược sĩ / Quản lý vật tư nha khoa', NULL,              NULL,        '2022-04-01', 'FULL_CONTRACT', 1, 1, 0, 1, 12000000.00, 1500000.00, 10000000.00, 'Vietcombank', '0011005566778', '8001001005', 'ACTIVE', NOW(), NOW()),
    (6,  6,  'KT-CN01-01', 'Vũ Kim Ngân',             'FEMALE', '1995-12-05', '0901000105', 'ketoan01@cuongvietdental.vn', '001195011006', 'Tài chính - KT',  'Kế toán trưởng chi nhánh',  'Tài chính - Kế toán doanh nghiệp',  NULL,              NULL,        '2021-09-01', 'FULL_CONTRACT', 1, 1, 0, 1, 15000000.00, 2000000.00, 12000000.00, 'Vietcombank', '0011007788990', '8001001006', 'ACTIVE', NOW(), NOW()),
    (7,  7,  'BS-CN02-01', 'BSCKII Nguyễn Quốc Tuấn', 'MALE',   '1984-09-02', '0902000201', 'bacsi03@cuongvietdental.vn',  '001084011007', 'Khối Lâm sàng',   'Trưởng cơ sở Chuyên môn CN2','Phục hình sứ & Nội nha',            '018901/HNO-CCHN', 'BS.018901', '2022-01-10', 'FULL_CONTRACT', 2, 1, 0, 1, 28000000.00, 4000000.00, 20000000.00, 'Techcombank', '1903998877665', '8001001007', 'ACTIVE', NOW(), NOW()),
    (8,  8,  'BS-CN02-02', 'ThS.BS Phạm Thúy Nga',    'FEMALE', '1989-06-30', '0902000202', 'bacsi04@cuongvietdental.vn',  '001189011008', 'Khối Lâm sàng',   'Bác sĩ Điều trị RHM',       'Nha khoa Tổng quát & Trẻ em',       '019234/HNO-CCHN', 'BS.019234', '2022-05-01', 'FULL_CONTRACT', 1, 1, 0, 1, 24000000.00, 3000000.00, 18000000.00, 'MBBank',      '0902000202888', '8001001008', 'ACTIVE', NOW(), NOW()),
    (9,  9,  'LT-CN02-01', 'Lê Phương Linh',          'FEMALE', '1998-03-12', '0902000203', 'letan02@cuongvietdental.vn',  '001198011009', 'Lễ tân & CSKH',   'Chuyên viên Lễ tân CSKH',   'Lễ tân & Tư vấn nha khoa',          NULL,              NULL,        '2023-01-15', 'FULL_CONTRACT', 0, 1, 0, 1, 10500000.00, 1500000.00,  9500000.00, 'MBBank',      '0902000203888', '8001001009', 'ACTIVE', NOW(), NOW()),
    (10, 10, 'KH-CN02-01', 'Bùi Trọng Đạt',           'MALE',   '1994-07-22', '0902000204', 'kho02@cuongvietdental.vn',    '001094011010', 'Quản lý Dược/Kho','Chuyên viên Quản lý kho',   'Quản lý kho vật tư nha khoa',       NULL,              NULL,        '2023-02-01', 'FULL_CONTRACT', 0, 1, 0, 1, 11000000.00, 1500000.00,  9500000.00, 'Techcombank', '1903112233445', '8001001010', 'ACTIVE', NOW(), NOW()),
    (11, 11, 'KT-CN02-01', 'Trần Thu Hà',             'FEMALE', '1996-10-14', '0902000205', 'ketoan02@cuongvietdental.vn', '001196011011', 'Tài chính - KT',  'Kế toán Thu ngân CN2',      'Kế toán thanh toán & viện phí',     NULL,              NULL,        '2023-03-01', 'FULL_CONTRACT', 0, 1, 0, 1, 13000000.00, 1500000.00, 10500000.00, 'Vietcombank', '0011003344556', '8001001011', 'ACTIVE', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    full_name        = VALUES(full_name),
    phone_number     = VALUES(phone_number),
    email            = VALUES(email),
    position_title   = VALUES(position_title),
    specialty        = VALUES(specialty),
    base_salary      = VALUES(base_salary),
    allowance_amount = VALUES(allowance_amount),
    insurance_salary = VALUES(insurance_salary),
    status           = VALUES(status),
    updated_at       = NOW();

-- 4. PHÂN CÔNG NHÂN SỰ TẠI CHI NHÁNH (staff_branch_assignments)
INSERT INTO staff_branch_assignments (assignment_id, staff_id, branch_id, role_id, is_primary, start_date, status, created_at) VALUES
    (1,  1,  1, 1, 1, '2020-01-01', 'ACTIVE', NOW()),
    (2,  2,  1, 2, 1, '2021-03-01', 'ACTIVE', NOW()),
    (3,  3,  1, 2, 1, '2021-06-15', 'ACTIVE', NOW()),
    (4,  4,  1, 3, 1, '2022-02-01', 'ACTIVE', NOW()),
    (5,  5,  1, 4, 1, '2022-04-01', 'ACTIVE', NOW()),
    (6,  6,  1, 5, 1, '2021-09-01', 'ACTIVE', NOW()),
    (7,  7,  2, 2, 1, '2022-01-10', 'ACTIVE', NOW()),
    (8,  8,  2, 2, 1, '2022-05-01', 'ACTIVE', NOW()),
    (9,  9,  2, 3, 1, '2023-01-15', 'ACTIVE', NOW()),
    (10, 10, 2, 4, 1, '2023-02-01', 'ACTIVE', NOW()),
    (11, 11, 2, 5, 1, '2023-03-01', 'ACTIVE', NOW())
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- Cập nhật người quản lý chi nhánh (manager_user_id)
UPDATE branches SET manager_user_id = 1 WHERE branch_id = 1;
UPDATE branches SET manager_user_id = 7 WHERE branch_id = 2;
