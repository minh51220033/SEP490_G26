-- =====================================================================
-- CV-DCMS DENTAL CLINIC MANAGEMENT SYSTEM
-- FILE DỮ LIỆU MẪU ĐỂ TEST (Dành riêng cho Bác sĩ, Bệnh nhân, Ca khám, Chẩn đoán ICD-10)
-- Các bảng branches, roles, icd10_diseases đã có sẵn không bị trùng lặp.
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. HẠNG THÀNH VIÊN (MEMBERSHIP TIERS)
INSERT INTO membership_tiers (tier_id, tier_code, tier_name, min_total_spent, discount_percent, display_order, is_active) VALUES
(1, 'STANDARD', 'Thành viên mới', 0,           0.00, 1, 1),
(2, 'SILVER',   'Thành viên Bạc', 5000000.00,  5.00, 2, 1),
(3, 'GOLD',     'Thành viên Vàng', 20000000.00, 10.00, 3, 1),
(4, 'DIAMOND',  'Kim Cương VIP',  50000000.00, 15.00, 4, 1)
ON DUPLICATE KEY UPDATE tier_name = VALUES(tier_name);

-- 2. TÀI KHOẢN NGƯỜI DÙNG MẪU (USERS: Bác sĩ, Bệnh nhân)
-- Mật khẩu mặc định: Hieu1811 (Hash Bcrypt: $2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe)
INSERT INTO users (user_id, username, password, email, phone_number, status, is_active, created_date) VALUES
(2, 'bacsi_nam',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'nam.le@dentalclinic.com',   '0911223344', 'ACTIVE', 1, NOW()),
(3, 'bacsi_hoa',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'hoa.tran@dentalclinic.com', '0922334455', 'ACTIVE', 1, NOW()),
(4, 'patient_an',    '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'an.nguyen@gmail.com',       '0987654321', 'ACTIVE', 1, NOW()),
(5, 'patient_binh',  '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'binh.tran@gmail.com',      '0912345678', 'ACTIVE', 1, NOW()),
(6, 'patient_cuong', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'cuong.le@gmail.com',       '0903998877', 'ACTIVE', 1, NOW())
ON DUPLICATE KEY UPDATE email = VALUES(email), phone_number = VALUES(phone_number);

-- 3. GÁN VAI TRÒ NGƯỜI DÙNG (USER_ROLES)
INSERT INTO user_roles (user_id, role_id) VALUES
(2, 2), -- bacsi_nam -> BAC_SI (role_id = 2)
(3, 2), -- bacsi_hoa -> BAC_SI (role_id = 2)
(4, 5), -- patient_an -> BENH_NHAN (role_id = 5)
(5, 5), -- patient_binh -> BENH_NHAN (role_id = 5)
(6, 5)  -- patient_cuong -> BENH_NHAN (role_id = 5)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- 4. HỒ SƠ BÁC SĨ (STAFF_PROFILES)
INSERT INTO staff_profiles (staff_id, user_id, staff_code, full_name, gender, dob, phone_number, email, department, position_title, specialty, status, base_salary, allowance_amount, insurance_salary, created_at) VALUES
(1, 2, 'BS001', 'BS. CK1 Lê Hoàng Nam', 'MALE',   '1985-06-12', '0911223344', 'nam.le@dentalclinic.com',   'Bác sĩ', 'Bác sĩ chuyên khoa', 'Răng Hàm Mặt - Nha chu', 'ACTIVE', 25000000, 3000000, 5000000, NOW()),
(2, 3, 'BS002', 'ThS. BS Trần Mai Hoa',  'FEMALE', '1990-11-25', '0922334455', 'hoa.tran@dentalclinic.com', 'Bác sĩ', 'Bác sĩ điều trị',    'Chỉnh nha - Niềng răng', 'ACTIVE', 28000000, 3500000, 5000000, NOW())
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name), specialty = VALUES(specialty);

-- 5. HỒ SƠ BỆNH NHÂN (PATIENT_PROFILES)
INSERT INTO patient_profiles (profile_id, user_id, patient_code, full_name, dob, gender, phone_number, address, preferred_branch_id, membership_tier_id, deposit_balance, status, created_at) VALUES
(1, 4, 'BN000101', 'Nguyễn Văn An', '1995-05-20', 'Nam', '0987654321', '123 Trần Hưng Đạo, Quận 5, TP.HCM', 1, 1, 0, 'ACTIVE', NOW()),
(2, 5, 'BN000102', 'Trần Thị Bình', '1998-08-15', 'Nữ',  '0912345678', '456 Hai Bà Trưng, Quận 3, TP.HCM', 1, 2, 500000, 'ACTIVE', NOW()),
(3, 6, 'BN000103', 'Lê Quốc Cường', '1988-12-10', 'Nam', '0903998877', '789 CMT8, Phường 5, Tân Bình, TP.HCM', 1, 3, 2000000, 'ACTIVE', NOW())
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name), phone_number = VALUES(phone_number), address = VALUES(address);

-- 6. CA KHÁM (VISITS)
INSERT INTO visits (visit_id, visit_code, profile_id, branch_id, visit_type, primary_doctor_id, status, initial_symptoms, check_in_at, created_at) VALUES
(1, 'VIS10001', 1, 1, 'APPOINTMENT', 1, 'IN_EXAM', 'Đau buốt răng hàm dưới bên phải khi ăn nhai và uống nước đá', NOW(), NOW()),
(2, 'VIS10002', 2, 1, 'WALK_IN',      2, 'IN_EXAM', 'Răng khôn hàm dưới mọc lệch gây sưng nề nướu và sốt nhẹ', NOW(), NOW()),
(3, 'VIS10003', 3, 1, 'APPOINTMENT', 1, 'COMPLETED', 'Khám răng định kỳ 6 tháng và cạo vôi răng', NOW(), NOW())
ON DUPLICATE KEY UPDATE initial_symptoms = VALUES(initial_symptoms), primary_doctor_id = VALUES(primary_doctor_id);

-- 7. KHÁM LÂM SÀNG (CLINICAL_EXAMINATIONS)
INSERT INTO clinical_examinations (exam_id, visit_id, profile_id, doctor_id, chief_complaint, history_illness, exam_time) VALUES
(1, 1, 1, 1, 'Đau buốt răng hàm khi ăn đồ nóng lạnh', 'Khởi phát 3 ngày trước, đau nhói từng cơn', NOW()),
(2, 2, 2, 2, 'Sưng đau vùng góc hàm dưới bên trái', 'Răng khôn mọc lệch gây đau nhức 2 ngày nay', NOW()),
(3, 3, 3, 1, 'Khám định kỳ & cạo vôi răng', 'Không có tiền sử bệnh lý toàn thân', NOW())
ON DUPLICATE KEY UPDATE chief_complaint = VALUES(chief_complaint);

-- 8. CHẨN ĐOÁN BỆNH LÝ THEO ICD-10 (EXAMINATION_DIAGNOSES - TEST FT14)
INSERT INTO examination_diagnoses (diagnosis_id, exam_id, visit_id, icd10_code, diagnosis_text, diagnosis_type, tooth_numbers, notes) VALUES
(1, 1, 1, 'K02.1', 'Sâu ngà răng',                             'MAIN',         '18, 28',    'Lỗ sâu mặt nhai R18, nhạy cảm khi thăm dò ngà'),
(2, 1, 1, 'K04.0', 'Viêm tủy răng (Cấp/Mạn)',                  'SECONDARY',    '18',        'Viêm tủy có khả năng hồi phục'),
(3, 2, 2, 'K01.1', 'Răng khôn mọc kẹt / mọc ngầm',             'MAIN',         '38, 48',    'Răng 38 mọc kẹt nghiêng 45 độ húc vào răng 37'),
(4, 2, 2, 'K05.0', 'Viêm nướu cấp tính',                       'SECONDARY',    '38',        'Nướu trùm sưng nề đỏ quanh thân răng 38'),
(5, 3, 3, 'K05.1', 'Viêm nướu mạn tính',                       'MAIN',         'Toàn hàm',  'Vôi răng bám nhiều dưới nướu độ 2')
ON DUPLICATE KEY UPDATE diagnosis_text = VALUES(diagnosis_text), notes = VALUES(notes);

-- 9. DANH MỤC NHÓM DỊCH VỤ (SERVICE_CATEGORIES)
INSERT INTO service_categories (category_id, category_code, category_name, category_type, display_order, is_active) VALUES
(1, 'PHUC_HINH', 'Phục hình răng sứ', 'PROCEDURE', 1, 1),
(2, 'TONG_QUAT', 'Nha khoa tổng quát & Điều trị', 'PROCEDURE', 2, 1),
(3, 'TIEU_PHAU', 'Tiểu phẫu & Nhổ răng', 'PROCEDURE', 3, 1),
(4, 'IMPLANT', 'Cấy ghép Implant', 'PROCEDURE', 4, 1),
(5, 'CHINH_NHA', 'Chỉnh nha - Niềng răng', 'PROCEDURE', 5, 1)
ON DUPLICATE KEY UPDATE category_name = VALUES(category_name);

-- 10. DANH MỤC THỦ THUẬT / DỊCH VỤ NHA KHOA (SERVICES)
INSERT INTO services (service_id, category_id, service_code, service_name, service_type, unit, base_price, requires_tooth, is_paraclinical, status) VALUES
(1, 1, 'DV-SU-TITAN', 'Răng sứ Titan', 'PROCEDURE', 'Đơn vị', 2500000.00, 1, 0, 'ACTIVE'),
(2, 1, 'DV-SU-ZIR',   'Răng sứ Zirconia DDBio', 'PROCEDURE', 'Đơn vị', 3500000.00, 1, 0, 'ACTIVE'),
(3, 1, 'DV-SU-CERCON', 'Răng sứ Cercon HT', 'PROCEDURE', 'Đơn vị', 5000000.00, 1, 0, 'ACTIVE'),
(4, 2, 'DV-CAOVOI',   'Cạo vôi răng & Đánh bóng', 'PROCEDURE', 'Lần', 300000.00, 0, 0, 'ACTIVE'),
(5, 2, 'DV-TRAM-RANG', 'Trám răng thẩm mỹ Composite', 'PROCEDURE', 'Răng', 450000.00, 1, 0, 'ACTIVE'),
(6, 2, 'DV-TUY-RH',   'Điều trị tủy răng hàm nhiều chân', 'PROCEDURE', 'Răng', 1500000.00, 1, 0, 'ACTIVE'),
(7, 3, 'DV-NHO-RKHON', 'Nhổ răng khôn mọc lệch / mọc ngầm', 'PROCEDURE', 'Răng', 2000000.00, 1, 0, 'ACTIVE'),
(8, 4, 'DV-IMP-GER',   'Cấy ghép Trụ Implant Đức MIS C1', 'PROCEDURE', 'Trụ', 22000000.00, 1, 0, 'ACTIVE'),
(9, 5, 'DV-MC-KIMLOAI', 'Gắn mắc cài kim loại chuẩn 2 hàm', 'PROCEDURE', 'Gói', 30000000.00, 0, 0, 'ACTIVE')
ON DUPLICATE KEY UPDATE service_name = VALUES(service_name), base_price = VALUES(base_price);

-- 11. HỒ SƠ PHỤ TÁ / TRỢ THỦ NHA KHOA (ĐÚNG MẪU IMAGE 7: PHẠM THỊ DUNG)
INSERT INTO users (user_id, username, password, email, phone_number, status, is_active, created_date) VALUES
(7, 'trothu_dung', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'dung.pham@dentalclinic.com', '0933445566', 'ACTIVE', 1, NOW())
ON DUPLICATE KEY UPDATE email = VALUES(email);

INSERT INTO user_roles (user_id, role_id) VALUES
(7, 4) -- role_id 4: Y_TA / DIEU_DUONG
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO staff_profiles (staff_id, user_id, staff_code, full_name, gender, dob, phone_number, email, department, position_title, specialty, status, base_salary, allowance_amount, insurance_salary, created_at) VALUES
(3, 7, 'PT001', 'Phạm Thị Dung', 'FEMALE', '1996-03-15', '0933445566', 'dung.pham@dentalclinic.com', 'Điều dưỡng', 'Trợ thủ nha khoa', 'Phụ tá nha khoa', 'ACTIVE', 12000000, 1500000, 5000000, NOW())
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name);

-- 12. DỊCH VỤ THỰC HIỆN THỰC TẾ (PERFORMED_SERVICES - TEST FT16)
INSERT INTO performed_services (performed_id, visit_id, profile_id, branch_id, service_id, tooth_numbers, quantity, unit_price, discount_amount, final_price, doctor_id, assistant_id, completed_at, status, clinical_notes) VALUES
(1, 1, 1, 1, 1, '16', 2.00, 2500000.00, 0.00, 5000000.00, 1, 3, NOW(), 'COMPLETED', 'Lắp 2 răng sứ Titan R16 và R26 khít sát, cắn khớp tốt'),
(2, 2, 2, 1, 7, '38', 1.00, 2000000.00, 200000.00, 1800000.00, 2, 3, NOW(), 'COMPLETED', 'Tiểu phẫu mở xương chia chân răng khôn 38 mọc lệch, khâu 2 mũi chỉ vicryl'),
(3, 3, 3, 1, 4, NULL, 1.00, 300000.00, 0.00, 300000.00, 1, 3, NOW(), 'COMPLETED', 'Cạo vôi siêu âm 2 hàm trên dưới và đánh bóng men răng')
ON DUPLICATE KEY UPDATE tooth_numbers = VALUES(tooth_numbers), final_price = VALUES(final_price);

SET FOREIGN_KEY_CHECKS = 1;

