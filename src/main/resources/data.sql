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

