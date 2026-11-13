-- ====================================================================================================
-- CV-DCMS: FILE DỮ LIỆU KHỞI TẠO MẶC ĐỊNH (src/main/resources/data.sql)
-- Đã chuẩn hóa 100% theo Schema thực tế của cv_dcms_db_backup.sql (MySQL 8.0)
-- Lưu ý: Mặc định spring.sql.init.mode=never (dữ liệu chuẩn được quản lý bởi Flyway V111__initial_master_data.sql
-- và PermissionDataInitializer.java). File này được giữ đồng bộ 100% để nếu bật spring.sql.init.mode=always
-- hoặc chạy thủ công nhiều lần đều an toàn tuyệt đối (Idempotent - ON DUPLICATE KEY UPDATE).
-- Mật khẩu mặc định cho các tài khoản: admin123 ($2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe)
-- ====================================================================================================

SET NAMES utf8mb4;

-- 1. VAI TRÒ HỆ THỐNG BẮT BUỘC (6 ROLES)
INSERT INTO roles (role_id, role_name, display_name, description, is_system, is_active) VALUES
    (1, 'ADMIN',       'Quản trị viên',          'Toàn quyền quản trị hệ thống', 1, 1),
    (2, 'BAC_SI',      'Bác sĩ',                 'Khám và điều trị lâm sàng',    1, 1),
    (3, 'LE_TAN',      'Lễ tân',                 'Tiếp đón, đặt lịch và CSKH',   1, 1),
    (4, 'QUAN_LY_KHO', 'Quản lý kho',            'Quản lý vật tư, xuất nhập kho',1, 1),
    (5, 'KE_TOAN',     'Kế toán',                'Thu ngân, hóa đơn, lương',     1, 1),
    (6, 'BENH_NHAN',   'Bệnh nhân / Khách hàng', 'Đặt lịch và tra cứu hồ sơ',    1, 1)
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    description  = VALUES(description),
    is_system    = VALUES(is_system),
    is_active    = VALUES(is_active);

-- 2. CHI NHÁNH PHÒNG KHÁM (Đầy đủ NOT NULL: opening_time, closing_time, working_days, created_at, updated_at)
INSERT INTO branches (branch_id, branch_code, branch_name, address, phone, email, tax_code, opening_time, closing_time, working_days, allowed_ip_range, is_active, created_at, updated_at) VALUES
    (1, 'CN01', 'Cơ sở 1 - Cầu Giấy (Trụ sở chính)', '123 Nguyễn Trãi, Quận Cầu Giấy, Hà Nội', '0901234567', 'cs1@dentalclinic.com', '0108889991', '08:00:00', '20:00:00', 'T2-CN', '127.0.0.1,::1,192.168.1.0/24', 1, NOW(), NOW()),
    (2, 'CN02', 'Cơ sở 2 - Đống Đa',                  '456 Láng Hạ, Quận Đống Đa, Hà Nội',      '0907654321', 'cs2@dentalclinic.com', '0108889992', '08:00:00', '20:00:00', 'T2-CN', '127.0.0.1,::1,192.168.1.0/24', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    branch_name  = VALUES(branch_name),
    address      = VALUES(address),
    phone        = VALUES(phone),
    opening_time = VALUES(opening_time),
    closing_time = VALUES(closing_time),
    working_days = VALUES(working_days),
    updated_at   = NOW();

-- 3. TÀI KHOẢN QUẢN TRỊ MẶC ĐỊNH (Mật khẩu: admin123)
INSERT INTO users (user_id, username, password, email, phone_number, is_active, status, user_type, branch_id, custom_permissions_enabled, created_date, updated_at) VALUES
    (1, 'admin', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'admin@dentalclinic.com', '0988888888', 1, 'ACTIVE', 'STAFF', 1, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    password   = VALUES(password),
    status     = VALUES(status),
    is_active  = VALUES(is_active),
    updated_at = NOW();

INSERT INTO user_roles (user_id, role_id)
SELECT u.user_id, r.role_id FROM users u JOIN roles r ON r.role_name = 'ADMIN' WHERE u.username = 'admin'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- 4. NHÀ CUNG CẤP & DANH MỤC VẬT TƯ NHA KHOA
INSERT INTO suppliers (supplier_id, supplier_code, supplier_name, tax_code, phone_number, address, is_active) VALUES
    (1, 'NCC-3M',        'Công ty Thiết bị Nha khoa 3M Việt Nam',    '0101234567', '0901112233', 'Tầng 12, Quận 1, TP.HCM',               1),
    (2, 'NCC-SEPTODONT', 'Công ty Dược & Vật tư Nha khoa Septodont', '0102345678', '0902223344', '88 Nguyễn Chí Thanh, Đống Đa, Hà Nội', 1),
    (3, 'NCC-DENTSPLY',  'Công ty Vật tư Nha khoa Dentsply Sirona',  '0103456789', '0903334455', '25 Lê Duẩn, Quận 1, TP.HCM',            1)
ON DUPLICATE KEY UPDATE
    supplier_name = VALUES(supplier_name),
    phone_number  = VALUES(phone_number),
    is_active     = VALUES(is_active);

INSERT INTO materials (material_id, material_code, material_name, category, unit, min_stock_threshold, is_active) VALUES
    (1, 'VT-COMP-Z350', 'Composite đặc 3M Filtek Z350 XT (Màu A2)',  'Trám răng', 'Ống', 10.000, 1),
    (2, 'VT-COMP-A3',   'Composite lỏng 3M Filtek Supreme (Màu A3)', 'Trám răng', 'Ống', 10.000, 1),
    (3, 'VT-TE-SEPTO',  'Thuốc tê Septanest 1:100.000 (Hộp 50 ống)', 'Thuốc tê',  'Hộp',  5.000, 1),
    (4, 'VT-MC-KL3M',   'Bộ mắc cài kim loại tự buộc 3M Unitek',     'Chỉnh nha', 'Bộ',   5.000, 1),
    (5, 'VT-DC-NITI',   'Dây cung NiTi chỉnh nha 016',               'Chỉnh nha', 'Gói', 15.000, 1),
    (6, 'VT-TH-GANG',   'Găng tay y tế Nitrile không bột (Hộp 100c)','Tiêu hao',  'Hộp', 20.000, 1),
    (7, 'VT-TH-KIM30G', 'Kim tiêm nha khoa siêu nhỏ 30G (Hộp 100c)', 'Tiêu hao',  'Hộp', 15.000, 1),
    (8, 'VT-NN-GUTTA',  'Côn chính Gutta Percha nội nha F1-F3',      'Nội nha',   'Hộp', 10.000, 1),
    (9, 'VT-PH-ALGIN',  'Bột lấy dấu răng Alginate Cavex',           'Phục hình', 'Gói', 12.000, 1)
ON DUPLICATE KEY UPDATE
    material_name       = VALUES(material_name),
    min_stock_threshold = VALUES(min_stock_threshold),
    is_active           = VALUES(is_active);

-- 5. HẠNG THÀNH VIÊN (MEMBERSHIP TIERS)
INSERT INTO membership_tiers (tier_id, tier_code, tier_name, min_total_spent, discount_percent, spend_per_point, point_value, display_order, is_active) VALUES
    (1, 'STANDARD', 'Thành viên mới',  0.00,        0.00,  10000.00, 1000.00, 1, 1),
    (2, 'SILVER',   'Thành viên Bạc',  5000000.00,  5.00,  10000.00, 1000.00, 2, 1),
    (3, 'GOLD',     'Thành viên Vàng', 20000000.00, 10.00, 10000.00, 1000.00, 3, 1),
    (4, 'DIAMOND',  'Kim Cương VIP',   50000000.00, 15.00, 10000.00, 1000.00, 4, 1)
ON DUPLICATE KEY UPDATE
    tier_name        = VALUES(tier_name),
    min_total_spent  = VALUES(min_total_spent),
    discount_percent = VALUES(discount_percent),
    is_active        = VALUES(is_active);

-- 6. PHÒNG KHÁM & GHẾ RĂNG (Chỉ liên kết tới branch_id 1 và 2 hợp lệ)
INSERT INTO rooms (room_id, branch_id, room_code, room_name, room_type, status) VALUES
    (1, 1, 'P101', 'Phòng Khám Tổng quát 1',        'EXAM',      'ACTIVE'),
    (2, 1, 'P102', 'Phòng Điều trị & Tiểu phẫu',    'TREATMENT', 'ACTIVE'),
    (3, 1, 'P103', 'Phòng Chỉnh nha & Thẩm mỹ',     'TREATMENT', 'ACTIVE'),
    (4, 1, 'P104', 'Phòng Chụp X-Quang Kỹ thuật số','XRAY',      'ACTIVE'),
    (5, 2, 'P201', 'Phòng Khám Đa khoa RHM',        'EXAM',      'ACTIVE'),
    (6, 2, 'P202', 'Phòng Điều trị Kỹ thuật cao',   'TREATMENT', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    room_name = VALUES(room_name),
    room_type = VALUES(room_type),
    status    = VALUES(status);

INSERT INTO dental_chairs (chair_id, branch_id, room_id, chair_code, chair_name, status) VALUES
    (1, 1, 1, 'G01',  'Ghế khám Tổng quát 01',       'AVAILABLE'),
    (2, 1, 3, 'G02',  'Ghế Chỉnh nha 02',            'AVAILABLE'),
    (3, 1, 2, 'G03',  'Ghế Cấy ghép Implant 03',     'AVAILABLE'),
    (4, 1, 2, 'G04',  'Ghế Tiểu phẫu Răng khôn 04',  'AVAILABLE'),
    (5, 1, 1, 'G05',  'Ghế Nội nha Chữa tủy 05',     'AVAILABLE'),
    (6, 1, 1, 'G06',  'Ghế Lấy cao răng & Thẩm mỹ 06','AVAILABLE'),
    (7, 2, 5, 'G201', 'Ghế khám CN2 - 01',           'AVAILABLE'),
    (8, 2, 6, 'G202', 'Ghế điều trị CN2 - 02',       'AVAILABLE')
ON DUPLICATE KEY UPDATE
    chair_name = VALUES(chair_name),
    status     = VALUES(status);

-- 7. DANH MỤC ICD-10 PHỔ BIẾN
INSERT INTO icd10_diseases (icd10_code, disease_name_vi, disease_name_en, is_dental_common, is_active) VALUES
    ('K04.0', 'Viêm tủy răng',                  'Pulpitis',           1, 1),
    ('K01.1', 'Răng ngầm / Răng khôn mọc lệch', 'Impacted teeth',     1, 1),
    ('K05.1', 'Viêm nướu mạn tính',             'Chronic gingivitis', 1, 1)
ON DUPLICATE KEY UPDATE
    disease_name_vi = VALUES(disease_name_vi),
    is_active       = VALUES(is_active);

-- 8. DỮ LIỆU DEMO CA KHÁM 604 & CHĂM SÓC KHÁCH HÀNG (Đúng ENUM visits.status = 'IN_EXAM', Role BENH_NHAN = 6, Đầy đủ NOT NULL)
INSERT INTO users (user_id, username, password, email, phone_number, is_active, status, user_type, branch_id, custom_permissions_enabled, created_date, updated_at) VALUES
    (601, 'bs_cskh_604', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'bs604@dentalclinic.com', '0906040001', 1, 'ACTIVE', 'STAFF',   1, 0, NOW(), NOW()),
    (602, 'lt_cskh_604', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'lt604@dentalclinic.com', '0906040002', 1, 'ACTIVE', 'STAFF',   1, 0, NOW(), NOW()),
    (604, 'bn_604',      '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'bn604@dentalclinic.com', '0916040604', 1, 'ACTIVE', 'PATIENT', 1, 0, NOW(), NOW()),
    (605, 'bn_605',      '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'bn605@dentalclinic.com', '0916050605', 1, 'ACTIVE', 'PATIENT', 1, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    password   = VALUES(password),
    status     = VALUES(status),
    updated_at = NOW();

INSERT INTO user_roles (user_id, role_id) VALUES
    (601, 2),
    (602, 3),
    (604, 6),
    (605, 6)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO staff_profiles (staff_id, user_id, staff_code, full_name, gender, dob, phone_number, email, department, position_title, specialty, contract_type, dependent_count, has_personal_deduction, has_tax_commitment, is_tax_resident, base_salary, allowance_amount, insurance_salary, status, created_at, updated_at) VALUES
    (601, 601, 'BS-604', 'BS.CKI Trần Minh Hoàng', 'MALE',   '1985-04-15', '0906040001', 'bs604@dentalclinic.com', 'Lâm sàng',     'Bác sĩ Răng-Hàm-Mặt',             'Phẫu thuật trong miệng & Nội nha', 'FULL_CONTRACT', 1, 1, 0, 1, 25000000.00, 3000000.00, 20000000.00, 'ACTIVE', NOW(), NOW()),
    (602, 602, 'LT-604', 'Nguyễn Thị Mai Anh',     'FEMALE', '1996-08-20', '0906040002', 'lt604@dentalclinic.com', 'Lễ tân & CSKH','Chuyên viên Chăm sóc khách hàng', 'CSKH Nha khoa',                    'FULL_CONTRACT', 0, 1, 0, 1, 10000000.00, 1500000.00, 10000000.00, 'ACTIVE', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    full_name  = VALUES(full_name),
    status     = VALUES(status),
    updated_at = NOW();

INSERT INTO staff_branch_assignments (assignment_id, staff_id, branch_id, role_id, is_primary, start_date, status, created_at) VALUES
    (601, 601, 1, 2, 1, CURDATE(), 'ACTIVE', NOW()),
    (602, 602, 1, 3, 1, CURDATE(), 'ACTIVE', NOW())
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO patient_profiles (profile_id, user_id, patient_code, full_name, dob, gender, phone_number, address, has_allergies, allergy_details, has_underlying_disease, underlying_disease_details, medical_history_completed, preferred_branch_id, is_default, deposit_balance, status, created_at, updated_at) VALUES
    (604, 604, 'BN-CC-604', 'Phạm Quốc Bảo',        '1992-06-12', 'Nam', '0916040604', '128 Nguyễn Thị Minh Khai, Quận 1, TP.HCM', 0, 'Không dị ứng thuốc',    0, 'Không có bệnh nền', 1, 1, 1, 0.00, 'ACTIVE', NOW(), NOW()),
    (605, 605, 'BN-CC-605', 'Lê Hoàng Phương Thảo', '1998-11-25', 'Nữ',  '0916050605', '45 Điện Biên Phủ, Quận 1, TP.HCM',         1, 'Dị ứng nhẹ Penicillin', 0, 'Không',             1, 1, 1, 0.00, 'ACTIVE', NOW(), NOW()),
    (606, 604, 'BN-CC-606', 'Phạm Gia Huy (Con)',    '2015-03-10', 'Nam', '0916040604', '128 Nguyễn Thị Minh Khai, Quận 1, TP.HCM', 0, 'Không',                 0, 'Không',             1, 1, 0, 0.00, 'ACTIVE', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    full_name  = VALUES(full_name),
    status     = VALUES(status),
    updated_at = NOW();

INSERT INTO account_patient_profiles (id, user_id, profile_id, relationship_type, is_primary, can_book, can_view_medical_record, can_MAKE_payment, created_at) VALUES
    (604, 604, 604, 'SELF',  1, 1, 1, 1, NOW()),
    (605, 605, 605, 'SELF',  1, 1, 1, 1, NOW()),
    (606, 604, 606, 'CHILD', 0, 1, 1, 1, NOW())
ON DUPLICATE KEY UPDATE relationship_type = VALUES(relationship_type);

INSERT INTO appointments (appointment_id, appointment_code, profile_id, branch_id, doctor_id, appointment_date, preferred_session, expected_time, duration_minutes, initial_symptoms, source, status, reschedule_count, created_at) VALUES
    (604, 'LH-2026-0604', 604, 1, 601, CURDATE(),                            'MORNING',   '09:00:00', 45, 'Đau nhức răng khôn hàm dưới bên phải R48, há miệng hạn chế', 'ONLINE',       'COMPLETED', 0, NOW()),
    (605, 'LH-2026-0605', 605, 1, 601, DATE_ADD(CURDATE(), INTERVAL 1 DAY),  'AFTERNOON', '14:30:00', 45, 'Ê buốt răng hàm dưới bên trái R36 khi uống nước lạnh',      'RECEPTIONIST', 'CONFIRMED', 0, NOW()),
    (606, 'LH-2026-0606', 606, 1, 601, CURDATE(),                            'MORNING',   '10:30:00', 30, 'Khám định kỳ răng trẻ em và bôi vecni fluor',              'ONLINE',       'PENDING',   0, NOW())
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO visits (visit_id, visit_code, profile_id, branch_id, appointment_id, visit_type, check_in_at, initial_symptoms, primary_doctor_id, room_id, chair_id, status, completed_at, notes, created_at) VALUES
    (604, 'VIS-2026-0604', 604, 1, 604, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 1 DAY),  'Sưng đau vùng góc hàm phải, răng khôn R48 mọc lệch 45 độ gây lợi trùm', 601, 2, 4, 'COMPLETED', DATE_SUB(NOW(), INTERVAL 22 HOUR), 'Đã tiểu phẫu nhổ răng khôn R48 bằng máy Piezotome, khâu đóng vạt 3 mũi chỉ 4.0.', NOW()),
    (605, 'VIS-2026-0605', 605, 1, 605, 'WALK_IN',     DATE_SUB(NOW(), INTERVAL 2 HOUR), 'Đau tủy cấp răng R36 sâu mặt nhai lớn',                                601, 1, 5, 'IN_EXAM',   NULL,                              'Đang điều trị nội nha lấy tủy lần 1 răng R36, đặt thuốc sát khuẩn ống tủy Ca(OH)2.', NOW()),
    (606, 'VIS-2026-0606', 606, 1, 606, 'APPOINTMENT', NOW(),                            'Khám kiểm tra men răng và dự phòng sâu răng trẻ em',                     601, 1, 1, 'WAITING',   NULL,                              'Bệnh nhân nhi đang chờ vào buồng khám số 1.', NOW())
ON DUPLICATE KEY UPDATE status = VALUES(status), notes = VALUES(notes);

INSERT INTO clinical_examinations (exam_id, visit_id, profile_id, doctor_id, chief_complaint, history_illness, vital_signs_json, extraoral_exam, intraoral_exam, periodontal_status, odontogram_chart_json, summary_assessment, exam_time) VALUES
    (604, 604, 604, 601,
     'Đau nhức vùng răng khôn hàm dưới phải R48 kéo dài 3 ngày',
     'Đã từng sưng lợi trùm 2 đợt trong 6 tháng qua, tự uống giảm đau không đỡ.',
     '{"bloodPressure":"120/75","heartRate":76,"temperature":36.7}',
     'Mặt cân đối, hạch dưới hàm phải sờ nhẹ không đau.',
     'Răng R48 mọc lệch gần 45 độ, lợi trùm sưng đỏ nhẹ, ấn không có mủ.',
     'Mô nha chu vùng răng hàm dưới ổn định, vôi răng độ I.',
     '{"48":{"status":"IMPACTED","note":"Răng khôn mọc lệch 45 độ"}}',
     'Chẩn đoán Răng khôn R48 mọc lệch ngầm có biến chứng lợi trùm (K01.1). Đã hoàn tất tiểu phẫu nhổ R48 an toàn.',
     DATE_SUB(NOW(), INTERVAL 23 HOUR)),
    (605, 605, 605, 601,
     'Đau nhức tự phát về đêm vùng răng hàm dưới bên trái R36',
     'Đau nhói lan lên thái dương trái 2 ngày nay.',
     '{"bloodPressure":"115/70","heartRate":78,"temperature":36.6}',
     'Không sưng mặt ngoài.',
     'Răng R36 sâu lỗ lớn mặt nhai-xa sát buồng tủy, gõ dọc đau nhẹ.',
     'Nướu viền quanh R36 hơi viêm đỏ.',
     '{"36":{"status":"PULPITIS","note":"Viêm tủy không hồi phục"}}',
     'Viêm tủy răng R36 không hồi phục (K04.0). Lưu ý bệnh nhân có tiền sử dị ứng nhẹ Penicillin.',
     DATE_SUB(NOW(), INTERVAL 1 HOUR))
ON DUPLICATE KEY UPDATE summary_assessment = VALUES(summary_assessment);

INSERT INTO examination_diagnoses (diagnosis_id, exam_id, visit_id, icd10_code, diagnosis_text, diagnosis_type, tooth_numbers, notes) VALUES
    (604, 604, 604, 'K01.1', 'Răng khôn hàm dưới bên phải (R48) mọc lệch 45 độ gây viêm lợi trùm tái phát', 'MAIN',      '48',    'Đã tiểu phẫu nhổ răng khôn R48 bằng sóng siêu âm Piezotome'),
    (605, 604, 604, 'K05.1', 'Viêm nướu cục bộ vùng tam giác hậu hàm R48',                                  'SECONDARY', '47,48', 'Bơm rửa Chlorhexidine 0.12% và khâu đóng vạt'),
    (606, 605, 605, 'K04.0', 'Viêm tủy răng R36 cấp không hồi phục do sâu răng mặt nhai-xa',                'MAIN',      '36',    'Mở tủy, lấy tủy buồng & tủy chân, đặt thuốc nội nha')
ON DUPLICATE KEY UPDATE diagnosis_text = VALUES(diagnosis_text);

INSERT INTO prescriptions (prescription_id, prescription_code, visit_id, profile_id, branch_id, doctor_id, diagnosis_text, advice_notes, followup_date, status, created_at) VALUES
    (604, 'TOA-2026-0604', 604, 604, 1, 601,
     '[K01.1] Hậu phẫu tiểu phẫu nhổ răng khôn hàm dưới phải R48 mọc lệch',
     '1. Cắn chặt gạc vô trùng trong 45-60 phút đầu sau nhổ. 2. Chườm lạnh ngoài má vùng góc hàm phải trong 24 giờ đầu. 3. Ăn cháo nguội, thức ăn mềm lỏng trong 2-3 ngày đầu. 4. Uống thuốc đúng liều theo đơn. Tái khám cắt chỉ sau 7 ngày.',
     DATE_ADD(CURDATE(), INTERVAL 7 DAY), 'DISPENSED', DATE_SUB(NOW(), INTERVAL 22 HOUR)),
    (605, 'TOA-2026-0605', 605, 605, 1, 601,
     '[K04.0] Điều trị nội nha răng R36 lần 1 (Bệnh nhân dị ứng Penicillin)',
     '1. Hạn chế nhai thức ăn cứng bên hàm trái. 2. Uống thuốc giảm đau sau ăn no. 3. Trở lại phòng khám đúng lịch hẹn sau 5 ngày để hàn kín ống tủy.',
     DATE_ADD(CURDATE(), INTERVAL 5 DAY), 'PENDING', DATE_SUB(NOW(), INTERVAL 1 HOUR))
ON DUPLICATE KEY UPDATE advice_notes = VALUES(advice_notes), status = VALUES(status);

INSERT INTO prescription_items (item_id, prescription_id, drug_name, dosage, duration_days, quantity, bought_quantity, unit, usage_instruction, unit_price) VALUES
    (6041, 604, 'Augmentin 1g (Amoxicillin + Acid Clavulanic)', 'Ngày 2 lần, mỗi lần 1 viên', 5, 10.00, 10.00, 'Viên', 'Uống sau ăn sáng và tối', 25000.00),
    (6042, 604, 'Efferalgan 500mg (Paracetamol)',               'Ngày 3 lần, mỗi lần 1 viên', 3,  9.00,  9.00, 'Viên', 'Hòa tan trong nước uống khi đau (cách nhau ít nhất 4-6h)', 5000.00),
    (6043, 604, 'Alpha Choay (Alphachymotrypsin 4.2mg)',        'Ngày 4 viên chia 2 lần',     5, 20.00, 20.00, 'Viên', 'Ngậm dưới lưỡi chống phù nề sau nhổ răng khôn', 4000.00),
    (6051, 605, 'Paracetamol 500mg',                            'Ngày 2 lần, mỗi lần 1 viên', 3,  6.00,  0.00, 'Viên', 'Uống sau ăn khi đau (an toàn cho BN dị ứng Penicillin)', 3000.00)
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), unit_price = VALUES(unit_price);

INSERT INTO customer_care_tasks (task_id, task_code, profile_id, branch_id, task_type, visit_id, appointment_id, prescription_id, assigned_staff_id, escalated_to_doctor_id, escalated_at, due_date, status, notes, doctor_response) VALUES
    (604, 'TASK-CC-0604', 604, 1, 'POST_TREATMENT_FOLLOWUP', 604, 604, 604, 602, NULL, NULL, CURDATE(), 'IN_PROGRESS',
     'Hỏi thăm sức khỏe bệnh nhân Phạm Quốc Bảo sau tiểu phẫu nhổ răng khôn R48 (ca khám VIS-2026-0604).',
     NULL),
    (605, 'TASK-CC-0605', 605, 1, 'MEDICATION_REMINDER', 605, 605, 605, 602, 601, NOW(), CURDATE(), 'ESCALATED_TO_DOCTOR',
     'Bệnh nhân Lê Hoàng Phương Thảo báo còn hơi ê vùng răng R36 đang đặt thuốc nội nha và hỏi có cần dùng thêm kháng sinh ngoài đơn không.',
     'Đã xem hồ sơ khám VIS-2026-0605: Răng R36 đã lấy sạch tủy viêm và đặt Ca(OH)2, cảm giác tức nhẹ 24h đầu là bình thường. Tuyệt đối không tự ý uống nhóm Amoxicillin/Penicillin.'),
    (606, 'TASK-CC-0606', 606, 1, 'APPOINTMENT_REMINDER', 606, 606, NULL, 602, NULL, NULL, CURDATE(), 'PENDING',
     'Nhắc lịch khám răng định kỳ và bôi vecni fluor cho bé Phạm Gia Huy.',
     NULL)
ON DUPLICATE KEY UPDATE status = VALUES(status), doctor_response = VALUES(doctor_response);

INSERT INTO customer_care_interactions (interaction_id, task_id, profile_id, staff_id, channel, contact_result, notes, next_retry_at, created_at) VALUES
    (6041, 604, 604, 602, 'PHONE_CALL', 'CONNECTED',
     'Đã gọi điện hỏi thăm anh Phạm Quốc Bảo sau nhổ răng khôn R48: ổ răng đã cầm máu hoàn toàn, má phải chỉ hơi sưng nhẹ.',
     NULL, DATE_SUB(NOW(), INTERVAL 3 HOUR)),
    (6051, 605, 605, 602, 'ZALO', 'CONNECTED',
     'Chị Phương Thảo nhắn Zalo hỏi về cảm giác tức nhẹ răng R36 và tiền sử dị ứng thuốc. Đã chuyển Bác sĩ Trần Minh Hoàng.',
     NULL, DATE_SUB(NOW(), INTERVAL 1 HOUR))
ON DUPLICATE KEY UPDATE notes = VALUES(notes);
