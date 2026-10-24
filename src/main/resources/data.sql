INSERT INTO branches (address, branch_code, branch_name, created_at, email, is_active, phone, updated_at)
VALUES
    ('123 Nguyễn Trãi, Quận 1, TP.HCM', 'CN01', 'Cơ sở Quận 1', NOW(), 'cs1@dentalclinic.com', 1, '0901234567', NOW()),
    ('456 Láng Hạ, Đống Đa, Hà Nội', 'CN02', 'Cơ sở Đống Đa', NOW(), 'cs2@dentalclinic.com', 1, '0907654321', NOW());


-- Tạo sẵn 1 tài khoản Admin mặc định (mật khẩu mặc định: Hieu1811 - đã mã hóa Bcrypt)
-- Hash Bcrypt của 'Hieu1811' là: $2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe
INSERT INTO users (created_date, email, is_active, password, phone_number, status, username)
VALUES (NOW(), 'admin@dentalclinic.com', 1, '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', '0988888888', 'ACTIVE', 'admin');

-- Bước 2: Gán quyền ADMIN (role_id = 1) cho tài khoản vừa được tạo
INSERT INTO user_roles (user_id, role_id)
VALUES (LAST_INSERT_ID(), 1);

-- Dữ liệu mẫu Nhà cung cấp & Danh mục vật tư nha khoa (INSERT IGNORE an toàn, không ghi đè dữ liệu cũ)
INSERT IGNORE INTO suppliers (supplier_code, supplier_name, tax_code, phone_number, address, is_active) VALUES
    ('NCC-3M',        'Công ty Thiết bị Nha khoa 3M Việt Nam', '0101234567', '0901112233', 'Tầng 12, Quận 1, TP.HCM', 1),
    ('NCC-SEPTODONT', 'Công ty Dược & Vật tư Nha khoa Septodont', '0102345678', '0902223344', '88 Nguyễn Chí Thanh, Đống Đa, Hà Nội', 1),
    ('NCC-DENTSPLY',  'Công ty Vật tư Nha khoa Dentsply Sirona', '0103456789', '0903334455', '25 Lê Duẩn, Quận 1, TP.HCM', 1);

INSERT IGNORE INTO materials (material_code, material_name, category, unit, min_stock_threshold, is_active) VALUES
    ('VT-COMP-Z350',  'Composite đặc 3M Filtek Z350 XT (Màu A2)', 'Trám răng',        'Ống', '10.000', 1),
    ('VT-COMP-A3',    'Composite lỏng 3M Filtek Supreme (Màu A3)', 'Trám răng',       'Ống', '10.000', 1),
    ('VT-TE-SEPTO',   'Thuốc tê Septanest 1:100.000 (Hộp 50 ống)', 'Thuốc tê',        'Hộp', '5.000',  1),
    ('VT-MC-KL3M',    'Bộ mắc cài kim loại tự buộc 3M Unitek',     'Chỉnh nha',       'Bộ',  '5.000',  1),
    ('VT-DC-NITI',    'Dây cung NiTi chỉnh nha 016',               'Chỉnh nha',       'Gói', '15.000', 1),
    ('VT-TH-GANG',    'Găng tay y tế Nitrile không bột (Hộp 100c)', 'Tiêu hao',       'Hộp', '20.000', 1),
    ('VT-TH-KIM30G',  'Kim tiêm nha khoa siêu nhỏ 30G (Hộp 100c)', 'Tiêu hao',        'Hộp', '15.000', 1),
    ('VT-NN-GUTTA',   'Côn chính Gutta Percha nội nha F1-F3',      'Nội nha',         'Hộp', '10.000', 1),
    ('VT-PH-ALGIN',   'Bột lấy dấu răng Alginate Cavex',           'Phục hình',       'Gói', '12.000', 1);
