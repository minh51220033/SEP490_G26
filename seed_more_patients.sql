-- SEED THÊM BỆNH NHÂN THỰC TẾ CHO TÌM KIẾM
INSERT INTO users (user_id, username, password, email, phone_number, status, is_active, created_date) VALUES
(8, 'patient_duc', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'duc.pham@gmail.com', '0978112233', 'ACTIVE', 1, NOW()),
(9, 'patient_thao', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'thao.hoang@gmail.com', '0934556677', 'ACTIVE', 1, NOW()),
(10, 'patient_dang', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'dang.vu@gmail.com', '0918223344', 'ACTIVE', 1, NOW()),
(11, 'patient_anh', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'anh.do@gmail.com', '0982334455', 'ACTIVE', 1, NOW()),
(12, 'patient_kiet', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'kiet.bui@gmail.com', '0909887766', 'ACTIVE', 1, NOW()),
(13, 'patient_mai', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'mai.dang@gmail.com', '0945667788', 'ACTIVE', 1, NOW())
ON DUPLICATE KEY UPDATE email = VALUES(email);

INSERT INTO patient_profiles (profile_id, user_id, patient_code, full_name, dob, gender, phone_number, address, preferred_branch_id, is_default, status) VALUES
(4, 8, 'BN000104', 'Phạm Minh Đức', '1990-03-14', 'Nam', '0978112233', '88 Cầu Giấy, Hà Nội', 2, 1, 'ACTIVE'),
(5, 9, 'BN000105', 'Hoàng Thu Thảo', '1996-11-25', 'Nữ', '0934556677', '124 Hoàng Cầu, Đống Đa, Hà Nội', 2, 1, 'ACTIVE'),
(6, 10, 'BN000106', 'Vũ Hải Đăng', '1985-07-08', 'Nam', '0918223344', '45 Trần Phú, Hà Đông, Hà Nội', 2, 1, 'ACTIVE'),
(7, 11, 'BN000107', 'Đỗ Quỳnh Anh', '2000-09-19', 'Nữ', '0982334455', '12 Chùa Bộc, Đống Đa, Hà Nội', 2, 1, 'ACTIVE'),
(8, 12, 'BN000108', 'Bùi Tuấn Kiệt', '1992-01-30', 'Nam', '0909887766', '68 Thái Hà, Đống Đa, Hà Nội', 2, 1, 'ACTIVE'),
(9, 13, 'BN000109', 'Đặng Ngọc Mai', '1994-06-12', 'Nữ', '0945667788', '210 Tây Sơn, Đống Đa, Hà Nội', 2, 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name), phone_number = VALUES(phone_number);

-- Thêm một số ca khám tương ứng cho bệnh nhân mới
INSERT INTO visits (visit_id, visit_code, profile_id, branch_id, visit_type, check_in_at, initial_symptoms, primary_doctor_id, status) VALUES
(4, 'VIS10004', 4, 2, 'WALK_IN', NOW(), 'Đau nhức ê buốt vùng răng hàm dưới bên trái', 1, 'IN_EXAM'),
(5, 'VIS10005', 5, 2, 'APPOINTMENT', NOW(), 'Tái khám kiểm tra niềng răng mắc cài định kỳ', 1, 'WAITING'),
(6, 'VIS10006', 6, 2, 'APPOINTMENT', NOW(), 'Thăm khám tư vấn cấy ghép Implant răng 36', 1, 'IN_TREATMENT')
ON DUPLICATE KEY UPDATE status = VALUES(status), initial_symptoms = VALUES(initial_symptoms);
