-- Dữ liệu khởi tạo cho bảng roles (Chỉ insert nếu chưa tồn tại để tránh lỗi duplicate)
INSERT IGNORE INTO roles (role_id, role_name, is_active) VALUES (1, 'ADMIN', true);
INSERT IGNORE INTO roles (role_id, role_name, is_active) VALUES (2, 'LE_TAN', true);
INSERT IGNORE INTO roles (role_id, role_name, is_active) VALUES (3, 'BAC_SI', true);
INSERT IGNORE INTO roles (role_id, role_name, is_active) VALUES (4, 'QUAN_LY_KHO', true);
INSERT IGNORE INTO roles (role_id, role_name, is_active) VALUES (5, 'KE_TOAN', true);
INSERT IGNORE INTO roles (role_id, role_name, is_active) VALUES (6, 'BENH_NHAN', true);

-- Tạo sẵn 1 tài khoản Admin mặc định (mật khẩu mặc định: Hieu1811 - đã mã hóa Bcrypt)
-- Hash Bcrypt của 'Hieu1811' là: $2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe
INSERT IGNORE INTO users (user_id, username, password, email, phone_number, full_name, is_active, role_id)
VALUES (1, 'admin', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'fakerthanhmien@gmail.com', '0123456789', 'Quản trị viên', true, 1);