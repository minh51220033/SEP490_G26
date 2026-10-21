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

-- 13. DANH MỤC THUỐC NHA KHOA (MEDICATIONS - TEST FT18)
INSERT INTO medications (medication_id, drug_code, drug_name, active_ingredient, unit, usage_instruction, is_active) VALUES
(1, 'THUOC01', 'Paracetamol 500mg', 'Paracetamol', 'Viên', 'Uống 1 viên khi đau buốt hoặc sốt, cách nhau ít nhất 4-6 giờ', 1),
(2, 'THUOC02', 'Ibuprofen 400mg', 'Ibuprofen', 'Viên', 'Uống 1 viên sau ăn no, ngày 2 lần (sáng - tối)', 1),
(3, 'THUOC03', 'Amoxicillin 500mg', 'Amoxicillin', 'Viên', 'Uống 1 viên/lần, ngày 2 lần cách nhau 12 giờ sau ăn', 1),
(4, 'THUOC04', 'Augmentin 1g', 'Amoxicillin + Clavulanate', 'Viên', 'Uống 1 viên/lần, ngày 2 lần (sáng 1 viên, tối 1 viên sau ăn)', 1),
(5, 'THUOC05', 'Rodogyl', 'Spiramycin + Metronidazol', 'Viên', 'Uống 2 viên/lần, ngày 2 lần trong bữa ăn (đặc trị răng miệng)', 1),
(6, 'THUOC06', 'Alphachymotrypsin 4.2mg', 'Chymotrypsin', 'Viên', 'Ngậm dưới lưỡi 2 viên/lần, ngày 2-3 lần giảm phù nề', 1),
(7, 'THUOC07', 'Efferalgan Codeine', 'Paracetamol 500mg + Codein 30mg', 'Viên sủi', 'Hòa tan 1 viên vào 200ml nước uống khi đau nhiều, cách nhau 6 giờ', 1),
(8, 'THUOC08', 'Nước súc miệng Kin Gingival 0.12%', 'Chlorhexidine Digluconate 0.12%', 'Chai', 'Súc miệng 15ml trong 30 giây sau khi đánh răng, ngày 2 lần, không nuốt', 1),
(9, 'THUOC09', 'Hapacol 250mg', 'Paracetamol', 'Gói', 'Pha 1 gói với nước ấm cho trẻ uống khi sốt hoặc đau, ngày 2-3 lần', 1),
(10, 'THUOC10', 'Prednisolone 5mg', 'Prednisolone', 'Viên', 'Uống 2 viên vào buổi sáng sau khi ăn no', 1)
ON DUPLICATE KEY UPDATE drug_name = VALUES(drug_name), usage_instruction = VALUES(usage_instruction);

-- 14. ĐƠN THUỐC MẪU (PRESCRIPTIONS - TEST FT18)
INSERT INTO prescriptions (prescription_id, prescription_code, visit_id, profile_id, branch_id, doctor_id, diagnosis_text, advice_notes, followup_date, created_at) VALUES
(1, 'TOA10001', 1, 1, 1, 1, 'K02.1 - Sâu ngà răng / Lắp răng sứ', 'Vệ sinh răng miệng sạch sẽ bằng chỉ nha khoa, súc miệng nước muối sinh lý ấm, không ăn đồ quá cứng hoặc quá nóng lạnh trong 3 ngày đầu.', DATE_ADD(CURDATE(), INTERVAL 7 DAY), NOW()),
(2, 'TOA10002', 2, 2, 1, 2, 'K01.1 - Răng khôn 38 mọc lệch tiểu phẫu', 'Cắn chặt gòn 45 phút, không khạc nhổ hoặc súc miệng mạnh trong 24h đầu. Chườm lạnh ngoài má ngày đầu, chườm ấm từ ngày thứ 2. Tái khám cắt chỉ sau 7 ngày.', DATE_ADD(CURDATE(), INTERVAL 7 DAY), NOW())
ON DUPLICATE KEY UPDATE diagnosis_text = VALUES(diagnosis_text), advice_notes = VALUES(advice_notes);

-- 15. CHI TIẾT ĐƠN THUỐC (PRESCRIPTION_ITEMS - TEST FT18)
INSERT INTO prescription_items (item_id, prescription_id, medication_id, drug_name, dosage, duration_days, quantity, unit, usage_instruction) VALUES
(1, 1, 1, 'Paracetamol 500mg', '500mg', 3, 6.00, 'Viên', 'Ngày uống 2 lần, mỗi lần 1 viên sau ăn khi đau'),
(2, 1, 6, 'Alphachymotrypsin 4.2mg', '4.2mg', 3, 6.00, 'Viên', 'Ngày ngậm dưới lưỡi 2 lần, mỗi lần 1 viên sau ăn'),
(3, 2, 4, 'Augmentin 1g', '1g', 5, 10.00, 'Viên', 'Ngày uống 2 lần, mỗi lần 1 viên sáng/chiều sau ăn no'),
(4, 2, 2, 'Ibuprofen 400mg', '400mg', 4, 8.00, 'Viên', 'Ngày uống 2 lần, mỗi lần 1 viên sau ăn khi đau'),
(5, 2, 6, 'Alphachymotrypsin 4.2mg', '4.2mg', 5, 15.00, 'Viên', 'Ngày ngậm dưới lưỡi 3 lần, mỗi lần 1 viên giảm phù nề'),
(6, 2, 8, 'Nước súc miệng Kin Gingival 0.12%', '0.12%', 7, 1.00, 'Chai', 'Súc miệng ngày 2 lần sáng/tối sau đánh răng, ngậm 30 giây')
ON DUPLICATE KEY UPDATE drug_name = VALUES(drug_name), usage_instruction = VALUES(usage_instruction);

-- 16. KẾ HOẠCH ĐIỀU TRỊ MẪU (TREATMENT_PLANS - TEST FT15)
INSERT INTO treatment_plans (plan_id, plan_code, profile_id, branch_id, doctor_id, plan_name, total_estimated_cost, final_estimated_cost, status, start_date, expected_end_date, notes, created_at) VALUES
(1, 'KHDT1001', 1, 1, 1, 'Phác đồ Cấy ghép Implant R16 & Phục hình mão sứ Zirconia', 15500000.00, 15500000.00, 'IN_PROGRESS', CURDATE(), DATE_ADD(CURDATE(), INTERVAL 90 DAY), 'Bệnh nhân mất R16, xương hàm đủ điều kiện cấy ghép trực tiếp. Sử dụng trụ Implant Biotem Hàn Quốc + mão toàn sứ Zirconia DDBio.', NOW()),
(2, 'KHDT1002', 2, 1, 2, 'Kế hoạch Tiểu phẫu Nhổ R38 & Chăm sóc nha chu toàn diện', 1900000.00, 1900000.00, 'ACCEPTED', CURDATE(), DATE_ADD(CURDATE(), INTERVAL 14 DAY), 'Răng 38 mọc kẹt nghiêng 45 độ. Cần chụp phim Panorama đối chiếu ống thần kinh răng dưới trước khi mở vạt.', NOW())
ON DUPLICATE KEY UPDATE plan_name = VALUES(plan_name), notes = VALUES(notes);

-- 17. GIAI ĐOẠN ĐIỀU TRỊ (TREATMENT_PLAN_STAGES - TEST FT15)
INSERT INTO treatment_plan_stages (stage_id, plan_id, stage_order, stage_name, status, completed_at) VALUES
(1, 1, 1, 'Giai đoạn 1: Chuẩn bị & Phẫu thuật cấy trụ Implant Biotem', 'COMPLETED', NOW()),
(2, 1, 2, 'Giai đoạn 2: Tái khám cắt chỉ & Đặt trụ lành thương (Healing)', 'IN_PROGRESS', NULL),
(3, 1, 3, 'Giai đoạn 3: Lấy dấu kỹ thuật số & Gắn mão toàn sứ Zirconia', 'PENDING', NULL),
(4, 2, 1, 'Giai đoạn 1: Chụp phim X-quang đối chiếu & Khám tiền phẫu', 'COMPLETED', NOW()),
(5, 2, 2, 'Giai đoạn 2: Phẫu thuật mở vạt nhổ răng khôn mọc lệch R38', 'PENDING', NULL)
ON DUPLICATE KEY UPDATE stage_name = VALUES(stage_name), status = VALUES(status);

-- 18. HẠNG MỤC CHI TIẾT TRONG GIAI ĐOẠN (TREATMENT_PLAN_ITEMS - TEST FT15)
INSERT INTO treatment_plan_items (plan_item_id, stage_id, plan_id, service_id, tooth_numbers, quantity, estimated_price, status) VALUES
(1, 1, 1, 5, '16', 1.00, 12000000.00, 'COMPLETED'),
(2, 2, 1, 6, '16', 1.00, 0.00, 'IN_PROGRESS'),
(3, 3, 1, 2, '16', 1.00, 3500000.00, 'PLANNED'),
(4, 4, 2, 6, '38', 1.00, 400000.00, 'COMPLETED'),
(5, 5, 2, 4, '38', 1.00, 1500000.00, 'PLANNED')
ON DUPLICATE KEY UPDATE tooth_numbers = VALUES(tooth_numbers), estimated_price = VALUES(estimated_price);

-- 19. QUY TRÌNH KỸ THUẬT LÂM SÀNG (BỘ Y TẾ - QUYẾT ĐỊNH 3207/QĐ-BYT)
INSERT INTO clinical_workflow_templates (template_id, template_code, template_name, description, service_id, clinic_id, status, created_by, created_at, updated_at) VALUES
(1, 'QTKT-CAOVOI', 'Quy trình kỹ thuật Lấy cao răng & Đánh bóng', 'Hướng dẫn kỹ thuật lấy cao răng siêu âm và đánh bóng men răng theo Quyết định 3207/QĐ-BYT', 4, 1, 'ACTIVE', 1, NOW(), NOW()),
(2, 'QTKT-TRAM-RANG', 'Quy trình kỹ thuật Hàn răng thẩm mỹ Composite', 'Hướng dẫn điều trị sâu ngà răng phục hồi bằng Composite vi thể quang trùng hợp theo chuẩn Bộ Y Tế', 5, 1, 'ACTIVE', 1, NOW(), NOW()),
(3, 'QTKT-TUY-RH', 'Quy trình kỹ thuật Điều trị nội nha tủy răng nhiều chân', 'Hướng dẫn kỹ thuật mở tủy, đo chiều dài làm việc Apex Locator, tạo hình bơm rửa và hàn bít ống tủy BYT', 6, 1, 'ACTIVE', 1, NOW(), NOW()),
(4, 'QTKT-NHO-RKHON', 'Quy trình kỹ thuật Tiểu phẫu nhổ răng khôn mọc lệch / ngầm', 'Phẫu thuật nhổ răng mọc lệch có cắt thân, chia chân răng theo Hướng dẫn quy trình kỹ thuật Răng Hàm Mặt BYT', 7, 1, 'ACTIVE', 1, NOW(), NOW()),
(5, 'QTKT-IMP-GER', 'Quy trình kỹ thuật Phẫu thuật Cấy ghép Implant nha khoa', 'Hướng dẫn quy trình kỹ thuật đặt trụ Implant trong xương hàm, tạo vạt và ghép xương chuẩn Bộ Y Tế', 8, 1, 'ACTIVE', 1, NOW(), NOW()),
(6, 'QTKT-SU-TITAN', 'Quy trình kỹ thuật Phục hình Răng sứ thẩm mỹ', 'Hướng dẫn quy trình mài cùi, lấy dấu cao su, thử sườn và gắn phục hình răng sứ cố định', 1, 1, 'ACTIVE', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE template_name = VALUES(template_name), description = VALUES(description), status = VALUES(status);

INSERT INTO clinical_workflow_versions (version_id, template_id, version_number, status, flow_mode, effective_from, change_summary, created_by, created_at, published_at) VALUES
(1, 1, 1, 'PUBLISHED', 'CONTINUOUS', NOW(), 'Phiên bản ban hành theo QĐ 3207/QĐ-BYT', 1, NOW(), NOW()),
(2, 2, 1, 'PUBLISHED', 'CONTINUOUS', NOW(), 'Phiên bản ban hành theo QĐ 3207/QĐ-BYT', 1, NOW(), NOW()),
(3, 3, 1, 'PUBLISHED', 'CONTINUOUS', NOW(), 'Phiên bản ban hành theo QĐ 3207/QĐ-BYT', 1, NOW(), NOW()),
(4, 4, 1, 'PUBLISHED', 'CONTINUOUS', NOW(), 'Phiên bản ban hành theo QĐ 3207/QĐ-BYT', 1, NOW(), NOW()),
(5, 5, 1, 'PUBLISHED', 'CONTINUOUS', NOW(), 'Phiên bản ban hành theo QĐ 3207/QĐ-BYT', 1, NOW(), NOW()),
(6, 6, 1, 'PUBLISHED', 'CONTINUOUS', NOW(), 'Phiên bản ban hành theo QĐ 3207/QĐ-BYT', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE status = VALUES(status), change_summary = VALUES(change_summary);

INSERT INTO clinical_workflow_steps (step_id, version_id, step_code, step_name, step_description, step_type, sequence_order, is_optional, completion_criteria, requires_clinical_approval, estimated_duration_minutes, status) VALUES
(101, 1, 'CV-B1', 'Bước 1: Thăm khám và đánh giá tình trạng cao răng', 'Khám khoang miệng tổng quát, dùng thám châm xác định mức độ cao răng trên lợi, dưới lợi, mức độ viêm nướu và túi nha chu. Giải thích cho người bệnh quy trình thao tác.', 'CLINICAL', 1, 0, 'Xác định đầy đủ độ 1-3 của cao răng và giải thích cho bệnh nhân rõ ràng', 0, 5, 'ACTIVE'),
(102, 1, 'CV-B2', 'Bước 2: Lấy cao răng siêu âm trên và dưới lợi', 'Dùng đầu rung máy siêu âm nghiêng góc 15-20 độ so với trục răng. Di chuyển liên tục đầu rung nhẹ nhàng từ thân răng đến cổ răng dưới tia nước làm mát vô trùng. Lấy sạch mảng bám và vôi răng.', 'TREATMENT', 2, 0, 'Sạch hoàn toàn mảng bám vôi răng bề mặt và dưới khe nướu, không phạm mô nướu lành', 0, 15, 'ACTIVE'),
(103, 1, 'CV-B3', 'Bước 3: Đánh bóng bề mặt men răng', 'Lắp chổi cước mịn/đài cao su vào tay khoan chậm. Lấy lượng vừa đủ bột đánh bóng nha khoa mịn (Prophy paste), xoa đều và đánh bóng nhẵn mịn toàn bộ các mặt ngoài, mặt trong và kẽ răng.', 'TREATMENT', 3, 0, 'Bề mặt men răng trơn bóng, nhẵn mịn, không còn vệt ố màu', 0, 5, 'ACTIVE'),
(104, 1, 'CV-B4', 'Bước 4: Bơm rửa sát khuẩn và dặn dò vệ sinh', 'Dùng bơm tiêm bơm rửa khe nướu bằng dung dịch Chlorhexidine 0.12% hoặc Betadine súc miệng. Kiểm tra cầm máu nướu. Dặn dò bệnh nhân chải răng đúng cách và tái khám định kỳ 6 tháng.', 'FOLLOW_UP', 4, 0, 'Vùng nướu cầm máu hoàn toàn, bệnh nhân nắm vững cách vệ sinh răng miệng', 0, 5, 'ACTIVE'),
(201, 2, 'TR-B1', 'Bước 1: Thăm khám, làm sạch và so màu Composite', 'Khám xác định độ sâu xoang sâu, kiểm tra tủy sống. Dùng bảng so màu VITA chọn đúng tone màu men và ngà răng phù hợp với răng thật của người bệnh.', 'CLINICAL', 1, 0, 'Chọn đúng màu composite hài hòa, xác định loại xoang Black (I-V)', 0, 5, 'ACTIVE'),
(202, 2, 'TR-B2', 'Bước 2: Sửa soạn xoang hàn và cách ly răng', 'Dùng mũi khoan kim cương mở rộng bộc lộ tổn thương, nạo sạch toàn bộ ngà mủn sâu hoại tử. Đặt bông gòn cách ly nước bọt hoặc đặt đê cao su (Rubber dam) tuyệt đối khô ráo.', 'TREATMENT', 2, 0, 'Xoang sạch ngà sâu, bờ xoang vát men 45 độ, vùng làm việc khô ráo tuyệt đối', 0, 10, 'ACTIVE'),
(203, 2, 'TR-B3', 'Bước 3: Xử lý bề mặt men ngà (Etching & Bonding)', 'Bơm gel Acid Phosphoric 37% lên men răng 15-20s và ngà răng 10s. Rửa sạch bằng tia nước 20s, thổi khô vừa phải (giữ độ ẩm ngà). Quét keo dán dính Bonding, thổi nhẹ 5s và chiếu đèn quang trùng hợp 20s.', 'TREATMENT', 3, 0, 'Bề mặt men mờ như phấn, lớp bonding phủ đều bóng nhẹ, quang trùng hợp cứng chắc', 0, 5, 'ACTIVE'),
(204, 2, 'TR-B4', 'Bước 4: Đắp Composite từng lớp và quang trùng hợp', 'Đưa composite vào xoang theo kỹ thuật từng lớp đắp chéo nghiêng dày không quá 2mm để giảm co ngót trùng hợp. Dùng cây điêu khắc tái tạo đúng hình thể giải phẫu múi rãnh tự nhiên. Chiếu đèn LED 20-40s mỗi lớp.', 'TREATMENT', 4, 0, 'Lớp composite bám dính khít sát bờ, tái tạo chuẩn xác hình thái giải phẫu mặt nhai', 0, 15, 'ACTIVE'),
(205, 2, 'TR-B5', 'Bước 5: Kiểm tra khớp cắn và đánh bóng miếng trám', 'Dùng giấy cắn xanh/đỏ kiểm tra tiếp xúc cắn trung tâm và vận động trượt sang bên. Mài chỉnh điểm cộm sớm. Dùng đĩa mài Sof-Lex và mũi đánh bóng mịn hoàn thiện bề mặt sáng bóng.', 'TREATMENT', 5, 0, 'Không cộm vướng khớp cắn, ranh giới miếng hàn láng mịn không khấc bờ', 0, 10, 'ACTIVE'),
(301, 3, 'DT-B1', 'Bước 1: Thăm khám lâm sàng, chụp X-quang & Gây tê', 'Đánh giá mức độ đau, gõ dọc, gõ ngang. Chụp phim cận chóp kiểm tra số lượng và độ cong ống tủy. Tiến hành gây tê tại chỗ hoặc gây tê vùng hiệu quả trước khi can thiệp.', 'CLINICAL', 1, 0, 'Vô cảm hoàn toàn vùng can thiệp, phim X-quang rõ nét thấy rõ chóp chân răng', 0, 10, 'ACTIVE'),
(302, 3, 'DT-B2', 'Bước 2: Mở tủy và bộc lộ các lỗ ống tủy', 'Dùng mũi khoan tròn mở lối vào buồng tủy, loại bỏ hoàn toàn trần buồng tủy. Dùng thám châm nội nha DG-16 thăm dò tìm đủ các lỗ vào ống tủy (kể cả ống tủy phụ).', 'TREATMENT', 2, 0, 'Mở thông thẳng góc vào ống tủy, thấy rõ toàn bộ các lỗ ống tủy, không thủng sàn', 1, 15, 'ACTIVE'),
(303, 3, 'DT-B3', 'Bước 3: Đo chiều dài làm việc và tạo hình ống tủy', 'Sử dụng trâm tay K-file #10/#15 kết hợp máy định vị chóp (Apex Locator) xác định chiều dài làm việc (WL = chóp sinh học - 0.5mm). Dùng hệ thống trâm máy quay Ni-Ti tạo hình thuôn đều.', 'TREATMENT', 3, 0, 'Xác định chính xác chiều dài làm việc từng ống tủy, ống tủy thuôn đều liên tục', 1, 20, 'ACTIVE'),
(304, 3, 'DT-B4', 'Bước 4: Bơm rửa làm sạch và đặt thuốc sát trùng', 'Bơm rửa luân phiên bằng dung dịch NaOCl 2.5% kết hợp kích hoạt sóng siêu âm nội nha để lấy sạch mùn ngà và vi khuẩn trong các vi ống ngà. Rửa sạch bằng nước muối sinh lý, lau khô bằng côn giấy và đặt thuốc.', 'TREATMENT', 4, 0, 'Ống tủy sạch mùn ngà, dịch bơm trong, không còn mùi hôi hoại tử', 0, 15, 'ACTIVE'),
(305, 3, 'DT-B5', 'Bước 5: Thử côn Gutta Percha và bít kín ống tủy', 'Chọn côn Gutta Percha chính có độ thuôn phù hợp, thử độ ôm khít chóp (tug-back). Trộn xi măng gắn nội nha (AH-Plus/Bioceramic), đưa vào ống tủy và lèn bít kín 3 chiều. Chụp phim X-quang kiểm tra sau bít.', 'TREATMENT', 5, 0, 'Phim X-quang kiểm tra cho thấy ống tủy bít kín sát chóp sinh học, không thiếu/thừa', 1, 15, 'ACTIVE'),
(401, 4, 'NK-B1', 'Bước 1: Khám tiền phẫu, đọc phim X-quang & Đo huyết áp', 'Kiểm tra tiền sử bệnh lý toàn thân, đo huyết áp, nhịp tim. Đọc phim Panorama/CT ConeBeam xác định vị trí chân răng khôn, độ nghiêng lệch và khoảng cách tới dây thần kinh răng dưới.', 'CLINICAL', 1, 0, 'Huyết áp ổn định, xác định chính xác liên quan giải phẫu dây thần kinh', 1, 10, 'ACTIVE'),
(402, 4, 'NK-B2', 'Bước 2: Sát khuẩn phẫu trường và gây tê sâu', 'Sát khuẩn vùng miệng và xung quanh môi. Tiến hành gây tê gai Spix kết hợp gây tê ngách lợi tiền đình và gây tê dây thần kinh má. Chờ 3-5 phút kiểm tra dấu hiệu tê môi cằm.', 'TREATMENT', 2, 0, 'Bệnh nhân mất cảm giác đau hoàn toàn vùng phẫu thuật', 0, 10, 'ACTIVE'),
(403, 4, 'NK-B3', 'Bước 3: Rạch lợi tạo vạt niêm mạc màng xương', 'Dùng dao mổ số 15 rạch đường rạch trên mào xương từ cành cao đến bờ xa răng số 7, rạch đường giảm căng ở ngách tiền đình. Dùng cây bóc tách tách vạt toàn phần bộc lộ xương và thân răng khôn.', 'TREATMENT', 3, 0, 'Vạt bộc lộ trường phẫu thuật đủ rộng, mép rạch sắc gọn, không rách vạt', 0, 10, 'ACTIVE'),
(404, 4, 'NK-B4', 'Bước 4: Mở xương và cắt thân chia chân răng', 'Dùng mũi khoan phẫu thuật với tay khoan nghiêng 45 độ có nước vô trùng mở lối thoát xương. Dùng mũi khoan cắt chia tách thân răng và các chân răng riêng biệt. Dùng bẩy lấy từng phần răng ra ngoài.', 'TREATMENT', 4, 0, 'Răng khôn lấy ra an toàn trọn vẹn, bảo tồn xương ổ và răng số 7 kế cận', 1, 20, 'ACTIVE'),
(405, 4, 'NK-B5', 'Bước 5: Làm sạch ổ răng, cầm máu và khâu đóng vạt', 'Dùng muỗng nạo sạch mô hạt, bơm rửa ổ răng thật kỹ bằng nước muối sinh lý vô trùng. Đặt màng collagen/gạc cầm máu nếu cần. Khâu khép vạt bằng chỉ tiêu Vicryl hoặc Silk 3-0. Cắn gạc chặt 45 phút.', 'FOLLOW_UP', 5, 0, 'Ổ răng sạch dị vật, vạt khâu kín áp sát, không chảy máu ồ ạt', 0, 15, 'ACTIVE'),
(501, 5, 'IM-B1', 'Bước 1: Chuẩn bị phòng mổ vô trùng, khám & Gây tê', 'Kiểm tra trang thiết bị vô trùng cấp phẫu thuật. Sát khuẩn phẫu trường trong và ngoài miệng. Tiến hành gây tê tại chỗ và gây tê vùng cấy ghép.', 'CLINICAL', 1, 0, 'Phòng mổ và dụng cụ đạt chuẩn vô trùng tuyệt đối, bệnh nhân vô cảm tốt', 1, 15, 'ACTIVE'),
(502, 5, 'IM-B2', 'Bước 2: Rạch tạo vạt bộc lộ mào xương sống hàm', 'Dạch mào xương sống hàm tại vị trí mất răng, bóc tách vạt niêm mạc màng xương bộc lộ rõ bề mặt xương ổ răng. Đánh giá chất lượng và chiều rộng xương thực tế.', 'TREATMENT', 2, 0, 'Bề mặt xương sống hàm bộc lộ rõ, vạt đủ dày nuôi dưỡng tốt', 0, 10, 'ACTIVE'),
(503, 5, 'IM-B3', 'Bước 3: Khoan tạo ổ xương theo tuần tự mũi khoan chuẩn', 'Sử dụng máy cấy Implant với nước muối sinh lý lạnh làm mát. Dùng mũi khoan định vị (Pilot drill), tiếp theo là các mũi khoan mở rộng dần đường kính theo đúng kích thước trụ Implant dự kiến.', 'TREATMENT', 3, 0, 'Khoan đúng hướng 3D, đúng chiều sâu quy định, không làm bỏng xương', 1, 20, 'ACTIVE'),
(504, 5, 'IM-B4', 'Bước 4: Đặt trụ Implant vào xương hàm đạt lực torque', 'Lắp trụ Implant vô trùng vào tay vặn hoặc tay khoan máy. Vặn trụ từ từ vào ổ xương đã chuẩn bị, siết chặt với lực torque ban đầu đạt từ 35-45 Ncm để đảm bảo độ ổn định sơ khởi.', 'TREATMENT', 4, 0, 'Trụ Implant nằm dưới mào xương 0.5-1mm, đạt độ ổn định sơ khởi chuẩn (Torque ≥ 35Ncm)', 1, 15, 'ACTIVE'),
(505, 5, 'IM-B5', 'Bước 5: Lắp nắp đậy / Trụ lành thương và khâu vạt', 'Lắp Cover screw (nếu vùi) hoặc Healing Abutment (nếu làm thì một thì). Khâu khép vạt niêm mạc bằng chỉ phẫu thuật không tiêu hoặc tự tiêu. Chụp phim X-quang kiểm tra ngay sau cấy.', 'FOLLOW_UP', 5, 0, 'Vạt khâu kín ôm khít trụ, phim X-quang kiểm tra vị trí Implant chuẩn xác', 0, 15, 'ACTIVE')
ON DUPLICATE KEY UPDATE step_name = VALUES(step_name), step_description = VALUES(step_description), completion_criteria = VALUES(completion_criteria);

SET FOREIGN_KEY_CHECKS = 1;


