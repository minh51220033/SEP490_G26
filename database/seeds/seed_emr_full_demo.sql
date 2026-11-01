-- Bộ dữ liệu demo đầy đủ cho FT03/FT04 + module Bệnh án điện tử.
-- Tiền điều kiện: ứng dụng đã khởi động một lần để Hibernate tạo schema; đã chạy V11__emr_module.sql.
-- Script dùng utf8mb4, an toàn khi chạy lại. Để quay về dữ liệu sạch, chạy reset_emr_demo.sql trước.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET time_zone = '+07:00';
START TRANSACTION;

-- 1) Tài khoản và quyền demo.
INSERT INTO roles (role_name, display_name, description, is_system, is_active)
SELECT 'BAC_SI', 'Bác sĩ', 'Khám, chẩn đoán và ký bệnh án điện tử', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_name = 'BAC_SI');

INSERT INTO roles (role_name, display_name, description, is_system, is_active)
SELECT 'BENH_NHAN', 'Bệnh nhân', 'Đặt lịch và theo dõi hồ sơ cá nhân', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_name = 'BENH_NHAN');

INSERT INTO users (username, password, email, phone_number, branch_id, user_type, status, is_active, created_date)
SELECT 'emr_demo_doctor', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe',
       'emr.demo.doctor@dentalclinic.test', '0909001001', b.branch_id, 'STAFF', 'ACTIVE', 1, NOW()
FROM branches b
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM users WHERE username = 'emr_demo_doctor');

INSERT INTO users (username, password, email, phone_number, user_type, status, is_active, created_date)
SELECT 'emr_demo_patient', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe',
       'emr.demo.patient@dentalclinic.test', '0909002001', 'PATIENT', 'ACTIVE', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'emr_demo_patient');

INSERT INTO user_roles (user_id, role_id)
SELECT u.user_id, r.role_id
FROM users u JOIN roles r ON r.role_name = 'BAC_SI'
WHERE u.username = 'emr_demo_doctor'
  AND NOT EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.user_id AND ur.role_id = r.role_id);

INSERT INTO user_roles (user_id, role_id)
SELECT u.user_id, r.role_id
FROM users u JOIN roles r ON r.role_name = 'BENH_NHAN'
WHERE u.username = 'emr_demo_patient'
  AND NOT EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.user_id AND ur.role_id = r.role_id);

INSERT INTO staff_profiles
    (user_id, staff_code, full_name, gender, dob, phone_number, email, department, position_title,
     specialty, practice_license_no, status, base_salary, allowance_amount, insurance_salary, created_at)
SELECT u.user_id, 'BSEMR01', 'BS. Nguyễn Minh Khôi', 'MALE', '1987-09-12', u.phone_number, u.email,
       'Răng Hàm Mặt', 'Bác sĩ điều trị', 'Nha khoa tổng quát', 'CCHN-EMR-001', 'ACTIVE', 0, 0, 0, NOW()
FROM users u
WHERE u.username = 'emr_demo_doctor'
  AND NOT EXISTS (SELECT 1 FROM staff_profiles WHERE staff_code = 'BSEMR01');

INSERT INTO staff_branch_assignments
    (staff_id, branch_id, role_id, is_primary, start_date, status, created_at)
SELECT s.staff_id, b.branch_id, r.role_id, 1, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 'ACTIVE', NOW()
FROM staff_profiles s
JOIN branches b ON b.branch_code = 'CN01'
JOIN roles r ON r.role_name = 'BAC_SI'
WHERE s.staff_code = 'BSEMR01'
  AND NOT EXISTS (SELECT 1 FROM staff_branch_assignments a
                  WHERE a.staff_id = s.staff_id AND a.branch_id = b.branch_id
                    AND a.role_id = r.role_id AND a.status = 'ACTIVE');

-- Bác sĩ, phòng và ghế này phục vụ hành trình: check-in -> phân công -> cấp số.
INSERT INTO work_shifts (branch_id, shift_code, shift_name, start_time, end_time, is_active)
SELECT b.branch_id, 'EMR_FULL_DAY', 'Ca demo EMR cả ngày', '00:00:00', '23:59:59', 1
FROM branches b WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM work_shifts ws WHERE ws.branch_id = b.branch_id AND ws.shift_code = 'EMR_FULL_DAY');

INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, created_at)
SELECT s.staff_id, b.branch_id, ws.shift_id, CURDATE(), 'SCHEDULED', NOW()
FROM staff_profiles s
JOIN branches b ON b.branch_code = 'CN01'
JOIN work_shifts ws ON ws.branch_id = b.branch_id AND ws.shift_code = 'EMR_FULL_DAY'
WHERE s.staff_code = 'BSEMR01'
  AND NOT EXISTS (SELECT 1 FROM staff_schedules ss
                  WHERE ss.staff_id = s.staff_id AND ss.branch_id = b.branch_id
                    AND ss.shift_id = ws.shift_id AND ss.work_date = CURDATE());

INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT b.branch_id, 'EMR-R01', 'Phòng khám demo EMR', 'EXAM', 'ACTIVE'
FROM branches b WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM rooms r WHERE r.branch_id = b.branch_id AND r.room_code = 'EMR-R01');

INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT b.branch_id, r.room_id, 'EMR-C01', 'Ghế demo EMR số 1', 'AVAILABLE'
FROM branches b JOIN rooms r ON r.branch_id = b.branch_id AND r.room_code = 'EMR-R01'
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM dental_chairs c WHERE c.branch_id = b.branch_id AND c.chair_code = 'EMR-C01');

-- 2) Danh mục đủ để dữ liệu khám, chẩn đoán, thủ thuật và đơn thuốc hoạt động độc lập.
INSERT INTO icd10_diseases (icd10_code, disease_name_vi, disease_name_en, is_dental_common, is_active)
VALUES ('K02.1', 'Sâu ngà răng', 'Caries of dentine', 1, 1),
       ('K04.0', 'Viêm tủy răng', 'Pulpitis', 1, 1),
       ('K01.1', 'Răng khôn mọc lệch', 'Impacted teeth', 1, 1),
       ('K05.1', 'Viêm nướu mạn tính', 'Chronic gingivitis', 1, 1)
ON DUPLICATE KEY UPDATE disease_name_vi = VALUES(disease_name_vi),
                        disease_name_en = VALUES(disease_name_en), is_active = 1;

INSERT INTO service_categories (category_code, category_name, category_type, display_order, is_active)
VALUES ('EMR_DEMO', 'Dịch vụ demo bệnh án điện tử', 'PROCEDURE', 900, 1)
ON DUPLICATE KEY UPDATE category_name = VALUES(category_name), is_active = 1;

INSERT INTO services
    (category_id, service_code, service_name, service_type, unit, base_price, requires_tooth, is_paraclinical, status)
SELECT c.category_id, src.code, src.name, 'PROCEDURE', src.unit, src.price, src.requires_tooth, 0, 'ACTIVE'
FROM (
    SELECT 'DVEMR-TRAM' code, 'Trám răng Composite' name, 'Răng' unit, 450000.00 price, 1 requires_tooth
    UNION ALL SELECT 'DVEMR-CAOVOI', 'Cạo vôi răng và đánh bóng', 'Lần', 300000.00, 0
    UNION ALL SELECT 'DVEMR-NHOKHON', 'Tiểu phẫu nhổ răng khôn', 'Răng', 2000000.00, 1
) src
JOIN service_categories c ON c.category_code = 'EMR_DEMO'
ON DUPLICATE KEY UPDATE service_name = VALUES(service_name), base_price = VALUES(base_price), status = 'ACTIVE';

INSERT INTO medications (drug_code, drug_name, active_ingredient, unit, usage_instruction, is_active)
VALUES ('THUOC-EMR-PARA', 'Paracetamol 500 mg', 'Paracetamol', 'Viên', 'Uống 1 viên khi đau, cách ít nhất 6 giờ.', 1),
       ('THUOC-EMR-CHX', 'Nước súc miệng Chlorhexidine 0,12%', 'Chlorhexidine', 'Chai', 'Súc miệng 2 lần mỗi ngày, không nuốt.', 1)
ON DUPLICATE KEY UPDATE drug_name = VALUES(drug_name), usage_instruction = VALUES(usage_instruction), is_active = 1;

-- 3) Năm bệnh nhân/lượt khám thể hiện toàn bộ trường hợp EMR.
INSERT INTO patient_profiles
    (patient_code, full_name, dob, gender, phone_number, address, id_card_number,
     has_allergies, allergy_details, has_underlying_disease, underlying_disease_details,
     medical_history_completed, preferred_branch_id, is_default, deposit_balance, status, created_at)
SELECT src.code, src.name, src.dob, src.gender, src.phone, src.address, src.id_card,
       src.has_allergy, src.allergy_note, src.has_disease, src.disease_note,
       1, b.branch_id, 0, 0, 'ACTIVE', NOW()
FROM (
    SELECT 'BNEMR1001' code, 'Nguyễn Minh An - Tạo bệnh án' name, '1994-05-20' dob, 'Nam' gender,
           '0909101001' phone, '12 Nguyễn Trãi, Quận 1, TP.HCM' address, '079094001001' id_card,
           1 has_allergy, 'Dị ứng penicillin' allergy_note, 0 has_disease, NULL disease_note
    UNION ALL SELECT 'BNEMR1002', 'Trần Thu Hà - Làm mới snapshot', '1989-08-15', 'Nữ',
           '0909101002', '85 Hai Bà Trưng, Quận 3, TP.HCM', '079089001002',
           0, NULL, 1, 'Tăng huyết áp đã kiểm soát'
    UNION ALL SELECT 'BNEMR1003', 'Lê Quốc Bảo - Bệnh án đã ký', '1991-12-10', 'Nam',
           '0909101003', '20 Cách Mạng Tháng Tám, Tân Bình, TP.HCM', '079091001003',
           0, NULL, 0, NULL
    UNION ALL SELECT 'BNEMR1004', 'Phạm Ngọc Linh - Thiếu dữ liệu', '1998-03-08', 'Nữ',
           '0909101004', '42 Lê Lợi, Quận 1, TP.HCM', '079098001004',
           0, NULL, 0, NULL
    UNION ALL SELECT 'BNEMR1005', 'Vũ Hoàng Long - Thiếu chẩn đoán chính', '1986-06-18', 'Nam',
           '0909101005', '98 Võ Văn Tần, Quận 3, TP.HCM', '079086001005',
           0, NULL, 0, NULL
) src
JOIN branches b ON b.branch_code = 'CN01'
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name), dob = VALUES(dob), gender = VALUES(gender),
    phone_number = VALUES(phone_number), address = VALUES(address), id_card_number = VALUES(id_card_number),
    has_allergies = VALUES(has_allergies), allergy_details = VALUES(allergy_details),
    has_underlying_disease = VALUES(has_underlying_disease), underlying_disease_details = VALUES(underlying_disease_details),
    medical_history_completed = 1, status = 'ACTIVE';

-- Bệnh nhân có tài khoản thật để bắt đầu luồng đặt lịch/tiếp đón.
INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address, id_card_number,
     medical_history_completed, preferred_branch_id, is_default, deposit_balance, status, created_at)
SELECT u.user_id, 'BNFLOW1001', 'Đỗ Khánh Vy - Hành trình đầy đủ', '1996-10-22', 'Nữ', u.phone_number,
       '101 Nguyễn Huệ, Quận 1, TP.HCM', '079096002001', 1, b.branch_id, 1, 0, 'ACTIVE', NOW()
FROM users u JOIN branches b ON b.branch_code = 'CN01'
WHERE u.username = 'emr_demo_patient'
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), full_name = VALUES(full_name), dob = VALUES(dob),
    phone_number = VALUES(phone_number), address = VALUES(address), is_default = 1, status = 'ACTIVE';

INSERT INTO account_patient_profiles (account_id, profile_id, access_type, created_at)
SELECT u.user_id, p.profile_id, 'SELF', NOW()
FROM users u JOIN patient_profiles p ON p.patient_code = 'BNFLOW1001'
WHERE u.username = 'emr_demo_patient'
  AND NOT EXISTS (SELECT 1 FROM account_patient_profiles app WHERE app.profile_id = p.profile_id);

INSERT INTO appointments
    (appointment_code, profile_id, branch_id, doctor_id, service_id, appointment_date, preferred_session,
     expected_time, initial_symptoms, source, status, reschedule_count, created_at)
SELECT 'APPTEMR1001', p.profile_id, b.branch_id, NULL, svc.service_id, CURDATE(), 'MORNING', '09:30:00',
       'Đau buốt răng hàm trên bên phải khi uống lạnh', 'ONLINE', 'CONFIRMED', 0, NOW()
FROM patient_profiles p
JOIN branches b ON b.branch_code = 'CN01'
JOIN services svc ON svc.service_code = 'DVEMR-TRAM'
WHERE p.patient_code = 'BNFLOW1001'
ON DUPLICATE KEY UPDATE appointment_date = CURDATE(), expected_time = '09:30:00',
    initial_symptoms = VALUES(initial_symptoms), status = 'CONFIRMED', doctor_id = NULL, service_id = VALUES(service_id);

INSERT INTO visits
    (visit_code, profile_id, branch_id, visit_type, check_in_at, initial_symptoms, primary_doctor_id, status, notes, created_at)
SELECT src.code, p.profile_id, b.branch_id, src.visit_type, DATE_SUB(NOW(), INTERVAL src.minutes_ago MINUTE),
       src.symptoms, CASE WHEN src.has_doctor = 1 THEN s.staff_id ELSE NULL END,
       src.status, src.notes, NOW()
FROM (
    SELECT 'VISEMR1001' code, 'BNEMR1001' patient_code, 'WALK_IN' visit_type, 80 minutes_ago,
           'Đau buốt răng số 16 khi uống nước lạnh' symptoms, 1 has_doctor, 'IN_EXAM' status,
           'Ca hoàn chỉnh để tạo bệnh án mới.' notes
    UNION ALL SELECT 'VISEMR1002', 'BNEMR1002', 'APPOINTMENT', 130,
           'Đau âm ỉ răng số 26, tăng khi nhai', 1, 'IN_EXAM',
           'Bệnh án nháp có snapshot cũ để kiểm tra làm mới.'
    UNION ALL SELECT 'VISEMR1003', 'BNEMR1003', 'WALK_IN', 1440,
           'Sưng đau vùng răng khôn hàm dưới trái', 1, 'COMPLETED',
           'Bệnh án đã ký thử để tạo phiên bản sửa đổi.'
    UNION ALL SELECT 'VISEMR1004', 'BNEMR1004', 'WALK_IN', 45,
           'Đau răng chưa được khám', 0, 'WAITING',
           'Ca thiếu phiếu khám, bác sĩ và chẩn đoán chính.'
    UNION ALL SELECT 'VISEMR1005', 'BNEMR1005', 'APPOINTMENT', 70,
           'Chảy máu nướu khi chải răng', 1, 'IN_EXAM',
           'Ca có phiếu khám nhưng chỉ có chẩn đoán phụ.'
) src
JOIN patient_profiles p ON p.patient_code = src.patient_code
JOIN branches b ON b.branch_code = 'CN01'
JOIN staff_profiles s ON s.staff_code = 'BSEMR01'
ON DUPLICATE KEY UPDATE profile_id = VALUES(profile_id), branch_id = VALUES(branch_id),
    initial_symptoms = VALUES(initial_symptoms), primary_doctor_id = VALUES(primary_doctor_id),
    status = VALUES(status), notes = VALUES(notes);

-- 4) Phiếu khám, chẩn đoán, thủ thuật, đơn thuốc và kế hoạch điều trị cho FT11/FT03/FT04.
INSERT INTO clinical_examinations
    (visit_id, profile_id, doctor_id, chief_complaint, history_illness, vital_signs_json,
     extraoral_exam, intraoral_exam, periodontal_status, odontogram_chart_json, summary_assessment, exam_time)
SELECT v.visit_id, v.profile_id, s.staff_id, src.chief, src.history,
       JSON_OBJECT('bloodPressure', src.bp, 'pulse', src.pulse), src.extraoral, src.intraoral,
       src.periodontal, JSON_OBJECT('teeth', JSON_ARRAY(src.tooth)), src.summary, NOW()
FROM (
    SELECT 'VISEMR1001' visit_code, 'Đau buốt răng số 16 khi uống lạnh' chief,
           'Đau 4 ngày, tăng khi dùng thức ăn lạnh.' history, '118/76' bp, '72' pulse,
           'Không sưng hạch, mặt cân đối.' extraoral, 'Răng 16 có lỗ sâu mặt nhai, thử lạnh dương tính nhẹ.' intraoral,
           'Cao răng mức độ nhẹ vùng răng cửa dưới.' periodontal, '16' tooth,
           'Sâu ngà răng 16, tủy còn đáp ứng.' summary
    UNION ALL SELECT 'VISEMR1002', 'Đau âm ỉ răng số 26 khi nhai' ,
           'Khởi phát một tuần, không sốt.' , '126/80', '74',
           'Không hạn chế há miệng.', 'Răng 26 sâu mặt nhai - xa, gõ dọc âm tính.',
           'Nướu vùng răng 26 viêm nhẹ.', '26', 'Sâu ngà răng 26, cần trám phục hồi.'
    UNION ALL SELECT 'VISEMR1003', 'Sưng đau vùng răng khôn hàm dưới trái',
           'Đau hai ngày, sưng nhẹ, đã dùng giảm đau.', '120/78', '76',
           'Sưng nhẹ góc hàm trái.', 'Răng 38 mọc lệch gần, lợi trùm viêm.',
           'Nướu vùng 38 đỏ, chảy máu khi thăm khám.', '38', 'Răng khôn 38 mọc lệch, đã chỉ định tiểu phẫu.'
    UNION ALL SELECT 'VISEMR1005', 'Chảy máu nướu khi chải răng',
           'Kéo dài ba tuần, không đau.', '116/74', '70',
           'Không bất thường ngoài mặt.', 'Nướu viền đỏ, có cao răng trên và dưới nướu.',
           'Viêm nướu lan tỏa mức độ vừa.', '31', 'Cần bổ sung chẩn đoán chính trước khi khóa bệnh án.'
) src
JOIN visits v ON v.visit_code = src.visit_code
JOIN staff_profiles s ON s.staff_code = 'BSEMR01'
ON DUPLICATE KEY UPDATE chief_complaint = VALUES(chief_complaint), history_illness = VALUES(history_illness),
    vital_signs_json = VALUES(vital_signs_json), extraoral_exam = VALUES(extraoral_exam),
    intraoral_exam = VALUES(intraoral_exam), periodontal_status = VALUES(periodontal_status),
    odontogram_chart_json = VALUES(odontogram_chart_json), summary_assessment = VALUES(summary_assessment);

INSERT INTO examination_diagnoses (exam_id, visit_id, icd10_code, diagnosis_text, diagnosis_type, tooth_numbers, notes)
SELECT ce.exam_id, v.visit_id, src.icd10_code, src.diagnosis_text, src.diagnosis_type, src.tooth_numbers, src.notes
FROM (
    SELECT 'VISEMR1001' visit_code, 'K02.1' icd10_code, 'Sâu ngà răng' diagnosis_text, 'MAIN' diagnosis_type, '16' tooth_numbers, 'Tổn thương mặt nhai, chưa lộ tủy.' notes
    UNION ALL SELECT 'VISEMR1001', 'K05.1', 'Viêm nướu mạn tính', 'SECONDARY', '31, 41', 'Cao răng mức độ nhẹ.'
    UNION ALL SELECT 'VISEMR1002', 'K02.1', 'Sâu ngà răng', 'MAIN', '26', 'Chỉ định trám Composite.'
    UNION ALL SELECT 'VISEMR1003', 'K01.1', 'Răng khôn mọc lệch', 'MAIN', '38', 'Đã thực hiện tiểu phẫu.'
    UNION ALL SELECT 'VISEMR1005', 'K05.1', 'Viêm nướu mạn tính', 'SECONDARY', 'Toàn hàm', 'Cố ý không có chẩn đoán chính để test validate.'
) src
JOIN visits v ON v.visit_code = src.visit_code
JOIN clinical_examinations ce ON ce.visit_id = v.visit_id
WHERE NOT EXISTS (SELECT 1 FROM examination_diagnoses d
                  WHERE d.visit_id = v.visit_id AND d.diagnosis_type = src.diagnosis_type
                    AND d.icd10_code = src.icd10_code AND d.tooth_numbers = src.tooth_numbers);

INSERT INTO performed_services
    (visit_id, profile_id, branch_id, service_id, tooth_numbers, quantity, unit_price, discount_amount,
     final_price, doctor_id, completed_at, status, clinical_notes)
SELECT v.visit_id, v.profile_id, v.branch_id, svc.service_id, src.tooth_numbers, 1, svc.base_price, 0,
       svc.base_price, s.staff_id, DATE_SUB(NOW(), INTERVAL src.minutes_ago MINUTE), 'COMPLETED', src.notes
FROM (
    SELECT 'VISEMR1001' visit_code, 'DVEMR-TRAM' service_code, '16' tooth_numbers, 35 minutes_ago,
           'Làm sạch tổn thương sâu, trám Composite quang trùng hợp, kiểm tra khớp cắn.' notes
    UNION ALL SELECT 'VISEMR1002', 'DVEMR-TRAM', '26', 90,
           'Trám phục hồi mặt nhai - xa, bệnh nhân hợp tác tốt.'
    UNION ALL SELECT 'VISEMR1003', 'DVEMR-NHOKHON', '38', 1390,
           'Tiểu phẫu lấy răng 38, khâu hai mũi chỉ tự tiêu, cầm máu tốt.'
) src
JOIN visits v ON v.visit_code = src.visit_code
JOIN services svc ON svc.service_code = src.service_code
JOIN staff_profiles s ON s.staff_code = 'BSEMR01'
WHERE NOT EXISTS (SELECT 1 FROM performed_services ps WHERE ps.visit_id = v.visit_id AND ps.service_id = svc.service_id);

INSERT INTO prescriptions
    (prescription_code, visit_id, profile_id, branch_id, doctor_id, diagnosis_text, advice_notes, followup_date, status, created_at)
SELECT src.code, v.visit_id, v.profile_id, v.branch_id, s.staff_id, src.diagnosis,
       src.advice, DATE_ADD(CURDATE(), INTERVAL 7 DAY), 'PENDING', NOW()
FROM (
    SELECT 'RXEMR1001' code, 'VISEMR1001' visit_code, 'K02.1 - Sâu ngà răng 16' diagnosis,
           'Tránh nhai thức ăn cứng trong 24 giờ, chải răng mềm và tái khám nếu đau tăng.' advice
    UNION ALL SELECT 'RXEMR1003', 'VISEMR1003', 'K01.1 - Răng khôn 38 mọc lệch',
           'Cắn gạc 45 phút, không súc miệng mạnh trong 24 giờ, tái khám cắt chỉ sau 7 ngày.'
) src
JOIN visits v ON v.visit_code = src.visit_code
JOIN staff_profiles s ON s.staff_code = 'BSEMR01'
ON DUPLICATE KEY UPDATE diagnosis_text = VALUES(diagnosis_text), advice_notes = VALUES(advice_notes),
    followup_date = VALUES(followup_date), status = 'PENDING';

INSERT INTO prescription_items
    (prescription_id, medication_id, drug_name, dosage, duration_days, quantity, unit, usage_instruction, unit_price)
SELECT p.prescription_id, m.medication_id, src.drug_name, src.dosage, src.duration_days, src.quantity,
       src.unit, src.instruction, 0
FROM (
    SELECT 'RXEMR1001' prescription_code, 'THUOC-EMR-PARA' drug_code, 'Paracetamol 500 mg' drug_name,
           '500 mg' dosage, 3 duration_days, 6 quantity, 'Viên' unit, 'Uống khi đau, tối đa 3 viên/ngày.' instruction
    UNION ALL SELECT 'RXEMR1003', 'THUOC-EMR-CHX', 'Nước súc miệng Chlorhexidine 0,12%',
           '15 ml', 7, 1, 'Chai', 'Súc miệng nhẹ nhàng hai lần mỗi ngày.'
) src
JOIN prescriptions p ON p.prescription_code = src.prescription_code
JOIN medications m ON m.drug_code = src.drug_code
WHERE NOT EXISTS (SELECT 1 FROM prescription_items pi WHERE pi.prescription_id = p.prescription_id AND pi.medication_id = m.medication_id);

INSERT INTO treatment_plans
    (plan_code, profile_id, branch_id, doctor_id, plan_name, total_estimated_cost, final_estimated_cost,
     status, start_date, expected_end_date, notes, created_at)
SELECT 'KH-EMR1001', p.profile_id, b.branch_id, s.staff_id,
       'Điều trị sâu răng 16 và kiểm soát viêm nướu', 750000, 750000, 'IN_PROGRESS', CURDATE(),
       DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'Theo dõi đáp ứng sau trám; hướng dẫn vệ sinh răng miệng.', NOW()
FROM patient_profiles p
JOIN branches b ON b.branch_code = 'CN01'
JOIN staff_profiles s ON s.staff_code = 'BSEMR01'
WHERE p.patient_code = 'BNEMR1001'
ON DUPLICATE KEY UPDATE plan_name = VALUES(plan_name), notes = VALUES(notes), status = 'IN_PROGRESS';

-- 4.1) Phiếu labo có thật cho các lượt demo.
-- Màn Khám & Điều trị luôn tải tab Labo khi mở hồ sơ. Seed các dòng này để
-- không kích hoạt đường tự sinh demo của FT15 trong lúc render trang, đồng thời
-- bác sĩ có dữ liệu đủ để thử tab Đặt xưởng mà không đụng tới code FT15.
INSERT INTO suppliers (supplier_code, supplier_name, phone_number, address, is_active)
SELECT 'LBEMR01', 'Labo demo EMR', '02838220011', 'Quận 1, TP. Hồ Chí Minh', 1
WHERE NOT EXISTS (SELECT 1 FROM suppliers WHERE supplier_code = 'LBEMR01');

INSERT INTO labo_orders
    (labo_code, visit_id, profile_id, branch_id, doctor_id, supplier_id, order_type,
     tooth_positions, material_type, shade_color, quantity, unit_price, cost_to_clinic,
     sent_date, try_in_date, try_in_time, expected_date, received_date, status, notes)
SELECT src.labo_code, v.visit_id, v.profile_id, v.branch_id, s.staff_id, supplier.supplier_id,
       'NEW', src.tooth, src.material, src.shade, 1, src.price, src.price,
       CURDATE(), DATE_ADD(CURDATE(), INTERVAL 2 DAY), '09:30',
       DATE_ADD(CURDATE(), INTERVAL 4 DAY), NULL, src.status, src.notes
FROM (
    SELECT 'LABEMR1001' labo_code, 'VISEMR1001' visit_code, '16' tooth,
           'Mão sứ Zirconia thử màu' material, 'A2' shade, 1500000.00 price, 'PROCESSING' status,
           'Phiếu labo demo cho ca tạo bệnh án mới.' notes
    UNION ALL SELECT 'LABEMR1002', 'VISEMR1002', '26',
           'Mão sứ toàn sứ e.max' , 'A1', 1800000.00, 'SENT',
           'Phiếu labo demo cho ca làm mới snapshot.'
    UNION ALL SELECT 'LABEMR1003', 'VISEMR1003', '38',
           'Mẫu xét nghiệm đông máu' , 'N/A', 350000.00, 'DELIVERED',
           'Kết quả labo đã nhận trước khi ký bệnh án.'
    UNION ALL SELECT 'LABEMR1004', 'VISEMR1004', '16',
           'Mão tạm PMMA' , 'A2', 500000.00, 'SENT',
           'Ca thiếu dữ liệu lâm sàng để kiểm thử validation.'
    UNION ALL SELECT 'LABEMR1005', 'VISEMR1005', '36',
           'Máng hướng dẫn vệ sinh nha chu' , 'Trong suốt', 400000.00, 'PROCESSING',
           'Ca thiếu chẩn đoán chính để kiểm thử validation.'
) src
JOIN visits v ON v.visit_code = src.visit_code
JOIN staff_profiles s ON s.staff_code = 'BSEMR01'
JOIN suppliers supplier ON supplier.supplier_code = 'LBEMR01'
WHERE NOT EXISTS (SELECT 1 FROM labo_orders lo WHERE lo.labo_code = src.labo_code);

-- 5) Hai bệnh án đã có sẵn: một bản nháp snapshot cũ và một bản đã ký thử để sửa đổi.
INSERT INTO emr_records
    (record_code, visit_id, profile_id, branch_id, status, current_version_no, created_by, created_at, updated_at, row_version)
SELECT 'EMR-DEMO-DRAFT', v.visit_id, v.profile_id, v.branch_id, 'DRAFT', 1, u.user_id, NOW(), NOW(), 0
FROM visits v JOIN users u ON u.username = 'emr_demo_doctor'
WHERE v.visit_code = 'VISEMR1002'
  AND NOT EXISTS (SELECT 1 FROM emr_records r WHERE r.visit_id = v.visit_id);

SET @draft_record_id = (SELECT record_id FROM emr_records WHERE record_code = 'EMR-DEMO-DRAFT' LIMIT 1);
SET @draft_user_id = (SELECT user_id FROM users WHERE username = 'emr_demo_doctor' LIMIT 1);
SET @draft_source = JSON_OBJECT(
    'patient', JSON_OBJECT('patientCode','BNEMR1002','fullName','Trần Thu Hà - Làm mới snapshot','dob','1989-08-15','gender','Nữ','phoneNumber','0909101002','address','85 Hai Bà Trưng, Quận 3, TP.HCM'),
    'visit', JSON_OBJECT('visitCode','VISEMR1002','initialSymptoms','DỮ LIỆU SNAPSHOT CŨ - hãy bấm Làm mới dữ liệu','status','IN_EXAM','branchCode','CN01','branchName','Cơ sở Quận 1','doctorName','BS. Nguyễn Minh Khôi'),
    'clinical', JSON_OBJECT('chiefComplaint','Dữ liệu snapshot cũ để demo refresh','historyIllness','Hãy làm mới snapshot để lấy dữ liệu khám hiện tại','summaryAssessment','Snapshot cũ'),
    'diagnoses', JSON_ARRAY(), 'performedServices', JSON_ARRAY(), 'prescriptions', JSON_ARRAY(), 'photos', JSON_ARRAY()
);

INSERT INTO emr_record_versions (record_id, version_no, status, template_code, template_version, created_by, created_at)
SELECT @draft_record_id, 1, 'DRAFT', 'OUTPATIENT_DENTAL', 1, @draft_user_id, NOW()
WHERE @draft_record_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_record_versions WHERE record_id = @draft_record_id AND version_no = 1);
SET @draft_version_id = (SELECT version_id FROM emr_record_versions WHERE record_id = @draft_record_id AND version_no = 1 LIMIT 1);
INSERT INTO emr_source_snapshots (version_id, source_json, source_hash, captured_at)
SELECT @draft_version_id, @draft_source, SHA2(CAST(@draft_source AS CHAR), 256), DATE_SUB(NOW(), INTERVAL 1 DAY)
WHERE @draft_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_source_snapshots WHERE version_id = @draft_version_id);
INSERT INTO emr_documents (version_id, document_code, title, document_type, template_version, sort_order, content_json, document_hash)
SELECT @draft_version_id, x.code, x.title, x.doc_type, 1, x.sort_order, @draft_source,
       SHA2(CONCAT(x.code, '|', CAST(@draft_source AS CHAR)), 256)
FROM (
    SELECT 'COVER' code, 'Bìa hồ sơ bệnh án' title, 'COVER' doc_type, 10 sort_order
    UNION ALL SELECT 'OUTPATIENT_DENTAL', 'Bệnh án ngoại trú Răng Hàm Mặt', 'MEDICAL_RECORD', 20
    UNION ALL SELECT 'TREATMENT_SUMMARY', 'Tổng kết quá trình khám và điều trị', 'SUMMARY', 30
    UNION ALL SELECT 'ATTACHMENT_INDEX', 'Danh mục tài liệu đính kèm', 'ATTACHMENT_INDEX', 40
    UNION ALL SELECT 'VERIFICATION', 'Thông tin xác thực hồ sơ', 'VERIFICATION', 50
) x
WHERE @draft_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_documents d WHERE d.version_id = @draft_version_id AND d.document_code = x.code);
INSERT INTO emr_audit_events (record_id, version_id, actor_user_id, action, details, created_at)
SELECT @draft_record_id, @draft_version_id, @draft_user_id, 'SEED_STALE_DRAFT',
       'Bản nháp cố ý có snapshot cũ để kiểm thử làm mới dữ liệu.', NOW()
WHERE @draft_record_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_audit_events a WHERE a.record_id = @draft_record_id AND a.action = 'SEED_STALE_DRAFT');

INSERT INTO emr_records
    (record_code, visit_id, profile_id, branch_id, status, current_version_no, created_by, created_at, updated_at, row_version)
SELECT 'EMR-DEMO-SIGNED', v.visit_id, v.profile_id, v.branch_id, 'SIGNED', 1, u.user_id,
       DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0
FROM visits v JOIN users u ON u.username = 'emr_demo_doctor'
WHERE v.visit_code = 'VISEMR1003'
  AND NOT EXISTS (SELECT 1 FROM emr_records r WHERE r.visit_id = v.visit_id);

SET @signed_record_id = (SELECT record_id FROM emr_records WHERE record_code = 'EMR-DEMO-SIGNED' LIMIT 1);
SET @signed_source = JSON_OBJECT(
    'patient', JSON_OBJECT('patientCode','BNEMR1003','fullName','Lê Quốc Bảo - Bệnh án đã ký','dob','1991-12-10','gender','Nam','phoneNumber','0909101003','address','20 Cách Mạng Tháng Tám, Tân Bình, TP.HCM'),
    'visit', JSON_OBJECT('visitCode','VISEMR1003','initialSymptoms','Sưng đau vùng răng khôn hàm dưới trái','status','COMPLETED','branchCode','CN01','branchName','Cơ sở Quận 1','doctorName','BS. Nguyễn Minh Khôi'),
    'clinical', JSON_OBJECT('chiefComplaint','Sưng đau vùng răng khôn hàm dưới trái','historyIllness','Đau hai ngày, sưng nhẹ','summaryAssessment','Răng khôn 38 mọc lệch, đã xử trí tiểu phẫu.'),
    'diagnoses', JSON_ARRAY(JSON_OBJECT('type','MAIN','icd10Code','K01.1','diagnosisText','Răng khôn mọc lệch','toothNumbers','38')),
    'performedServices', JSON_ARRAY(JSON_OBJECT('serviceName','Tiểu phẫu nhổ răng khôn','toothNumbers','38','doctorName','BS. Nguyễn Minh Khôi','clinicalNotes','Khâu hai mũi chỉ tự tiêu.')),
    'prescriptions', JSON_ARRAY(JSON_OBJECT('prescriptionCode','RXEMR1003','diagnosisText','K01.1 - Răng khôn mọc lệch','adviceNotes','Tái khám sau 7 ngày.','items',JSON_ARRAY())),
    'photos', JSON_ARRAY()
);
SET @signed_hash = SHA2(CAST(@signed_source AS CHAR), 256);
INSERT INTO emr_record_versions
    (record_id, version_no, status, template_code, template_version, content_hash, created_by, created_at, locked_at, signed_at)
SELECT @signed_record_id, 1, 'SIGNED', 'OUTPATIENT_DENTAL', 1, @signed_hash, @draft_user_id,
       DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 23 HOUR), DATE_SUB(NOW(), INTERVAL 22 HOUR)
WHERE @signed_record_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_record_versions WHERE record_id = @signed_record_id AND version_no = 1);
SET @signed_version_id = (SELECT version_id FROM emr_record_versions WHERE record_id = @signed_record_id AND version_no = 1 LIMIT 1);
INSERT INTO emr_source_snapshots (version_id, source_json, source_hash, captured_at)
SELECT @signed_version_id, @signed_source, @signed_hash, DATE_SUB(NOW(), INTERVAL 1 DAY)
WHERE @signed_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_source_snapshots WHERE version_id = @signed_version_id);
INSERT INTO emr_documents (version_id, document_code, title, document_type, template_version, sort_order, content_json, document_hash)
SELECT @signed_version_id, x.code, x.title, x.doc_type, 1, x.sort_order, @signed_source,
       SHA2(CONCAT(x.code, '|', CAST(@signed_source AS CHAR)), 256)
FROM (
    SELECT 'COVER' code, 'Bìa hồ sơ bệnh án' title, 'COVER' doc_type, 10 sort_order
    UNION ALL SELECT 'OUTPATIENT_DENTAL', 'Bệnh án ngoại trú Răng Hàm Mặt', 'MEDICAL_RECORD', 20
    UNION ALL SELECT 'TREATMENT_SUMMARY', 'Tổng kết quá trình khám và điều trị', 'SUMMARY', 30
    UNION ALL SELECT 'ATTACHMENT_INDEX', 'Danh mục tài liệu đính kèm', 'ATTACHMENT_INDEX', 40
    UNION ALL SELECT 'VERIFICATION', 'Thông tin xác thực hồ sơ', 'VERIFICATION', 50
) x
WHERE @signed_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_documents d WHERE d.version_id = @signed_version_id AND d.document_code = x.code);
INSERT INTO emr_signatures
    (version_id, signer_type, signer_name, signer_user_id, provider, external_transaction_id, status, signature_hash, created_at, signed_at)
SELECT @signed_version_id, 'PATIENT', 'Lê Quốc Bảo - Bệnh án đã ký', NULL, 'LOCAL_TEST_ONLY', 'seed-patient-emr1003', 'SIGNED',
       SHA2(CONCAT(@signed_hash, '|PATIENT'), 256), DATE_SUB(NOW(), INTERVAL 22 HOUR), DATE_SUB(NOW(), INTERVAL 22 HOUR)
WHERE @signed_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_signatures s WHERE s.version_id = @signed_version_id AND s.signer_type = 'PATIENT');
INSERT INTO emr_signatures
    (version_id, signer_type, signer_name, signer_user_id, provider, external_transaction_id, status, signature_hash, created_at, signed_at)
SELECT @signed_version_id, 'DOCTOR', 'BS. Nguyễn Minh Khôi', @draft_user_id, 'LOCAL_TEST_ONLY', 'seed-doctor-emr1003', 'SIGNED',
       SHA2(CONCAT(@signed_hash, '|DOCTOR'), 256), DATE_SUB(NOW(), INTERVAL 21 HOUR), DATE_SUB(NOW(), INTERVAL 21 HOUR)
WHERE @signed_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_signatures s WHERE s.version_id = @signed_version_id AND s.signer_type = 'DOCTOR');
INSERT INTO emr_audit_events (record_id, version_id, actor_user_id, action, details, created_at)
SELECT @signed_record_id, @signed_version_id, @draft_user_id, 'SEED_SIGNED',
       'Bệnh án đã ký thử, dùng để tạo phiên bản sửa đổi.', DATE_SUB(NOW(), INTERVAL 21 HOUR)
WHERE @signed_record_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_audit_events a WHERE a.record_id = @signed_record_id AND a.action = 'SEED_SIGNED');

COMMIT;

-- Kiểm tra ngắn sau khi nạp seed.
SELECT v.visit_code, p.patient_code, p.full_name, v.status AS visit_status,
       er.record_code, er.status AS emr_status
FROM visits v
JOIN patient_profiles p ON p.profile_id = v.profile_id
LEFT JOIN emr_records er ON er.visit_id = v.visit_id
WHERE v.visit_code LIKE 'VISEMR%'
ORDER BY v.visit_code;
