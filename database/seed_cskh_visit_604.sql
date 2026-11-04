-- =============================================================================
-- Dữ liệu hạt giống Kiểm thử Chăm sóc Khách hàng & Ca khám 604 (INSERT IGNORE an toàn)
-- Phục vụ URL: /doctor/examination/604/diagnoses#tab-instructions & /customer-care
-- =============================================================================
INSERT IGNORE INTO icd10_diseases (icd10_code, disease_name_vi, disease_name_en, is_dental_common, is_active) VALUES
    ('K04.0', 'Viêm tủy răng', 'Pulpitis', 1, 1),
    ('K01.1', 'Răng ngầm / Răng khôn mọc lệch', 'Impacted teeth', 1, 1),
    ('K05.1', 'Viêm nướu mạn tính', 'Chronic gingivitis', 1, 1);

INSERT IGNORE INTO users (user_id, username, password, email, phone_number, is_active, status, branch_id, created_date) VALUES
    (601, 'bs_cskh_604', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bs604@dentalclinic.com', '0906040001', 1, 'ACTIVE', 1, NOW()),
    (602, 'lt_cskh_604', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'lt604@dentalclinic.com', '0906040002', 1, 'ACTIVE', 1, NOW()),
    (604, 'bn_604',      '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bn604@dentalclinic.com', '0916040604', 1, 'ACTIVE', 1, NOW()),
    (605, 'bn_605',      '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bn605@dentalclinic.com', '0916050605', 1, 'ACTIVE', 1, NOW());

INSERT IGNORE INTO staff_profiles (staff_id, user_id, staff_code, full_name, gender, dob, phone_number, email, department, position_title, specialty, base_salary, allowance_amount, insurance_salary, status, created_at) VALUES
    (601, 601, 'BS-604', 'BS.CKI Trần Minh Hoàng', 'MALE',   '1985-04-15', '0906040001', 'bs604@dentalclinic.com', 'Lâm sàng', 'Bác sĩ Răng-Hàm-Mặt', 'Phẫu thuật trong miệng & Nội nha', 25000000.00, 3000000.00, 20000000.00, 'ACTIVE', NOW()),
    (602, 602, 'LT-604', 'Nguyễn Thị Mai Anh',     'FEMALE', '1996-08-20', '0906040002', 'lt604@dentalclinic.com', 'Lễ tân & CSKH', 'Chuyên viên Chăm sóc khách hàng', 'CSKH Nha khoa', 10000000.00, 1500000.00, 10000000.00, 'ACTIVE', NOW());

INSERT IGNORE INTO patient_profiles (profile_id, user_id, patient_code, full_name, dob, gender, phone_number, address, has_allergies, allergy_details, has_underlying_disease, underlying_disease_details, medical_history_completed, preferred_branch_id, is_default, deposit_balance, status, created_at) VALUES
    (604, 604, 'BN-CC-604', 'Phạm Quốc Bảo',        '1992-06-12', 'Nam', '0916040604', '128 Nguyễn Thị Minh Khai, Quận 1, TP.HCM', 0, 'Không dị ứng thuốc', 0, 'Không có bệnh nền', 1, 1, 1, 0.00, 'ACTIVE', NOW()),
    (605, 605, 'BN-CC-605', 'Lê Hoàng Phương Thảo', '1998-11-25', 'Nữ',  '0916050605', '45 Điện Biên Phủ, Quận 1, TP.HCM',         1, 'Dị ứng nhẹ Penicillin', 0, 'Không', 1, 1, 1, 0.00, 'ACTIVE', NOW()),
    (606, 604, 'BN-CC-606', 'Phạm Gia Huy (Con)',    '2015-03-10', 'Nam', '0916040604', '128 Nguyễn Thị Minh Khai, Quận 1, TP.HCM', 0, 'Không', 0, 'Không', 1, 1, 0, 0.00, 'ACTIVE', NOW());

INSERT IGNORE INTO appointments (appointment_id, appointment_code, profile_id, branch_id, doctor_id, appointment_date, preferred_session, expected_time, initial_symptoms, source, status, reschedule_count, created_at) VALUES
    (604, 'LH-2026-0604', 604, 1, 601, CURDATE(),                        'MORNING',   '09:00:00', 'Đau nhức răng khôn hàm dưới bên phải R48, há miệng hạn chế', 'ONLINE',       'COMPLETED', 0, NOW()),
    (605, 'LH-2026-0605', 605, 1, 601, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'AFTERNOON', '14:30:00', 'Ê buốt răng hàm dưới bên trái R36 khi uống nước lạnh',      'RECEPTIONIST', 'CONFIRMED', 0, NOW()),
    (606, 'LH-2026-0606', 606, 1, 601, CURDATE(),                        'MORNING',   '10:30:00', 'Khám định kỳ răng trẻ em và bôi vecni fluor',              'ONLINE',       'PENDING',   0, NOW());

INSERT IGNORE INTO visits (visit_id, visit_code, profile_id, branch_id, appointment_id, visit_type, check_in_at, initial_symptoms, primary_doctor_id, status, completed_at, notes, created_at) VALUES
    (604, 'VIS-2026-0604', 604, 1, 604, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 1 DAY), 'Sưng đau vùng góc hàm phải, răng khôn R48 mọc lệch 45 độ gây lợi trùm', 601, 'COMPLETED', DATE_SUB(NOW(), INTERVAL 22 HOUR),
     'Đã tiểu phẫu nhổ răng khôn R48 bằng máy Piezotome, khâu đóng vạt 3 mũi chỉ 4.0. Cầm máu tốt, dặn cắn gạc 45 phút và chườm lạnh 24h đầu.', NOW()),
    (605, 'VIS-2026-0605', 605, 1, 605, 'WALK_IN',     DATE_SUB(NOW(), INTERVAL 2 HOUR), 'Đau tủy cấp răng R36 sâu mặt nhai lớn',                                601, 'EXAMINING', NULL,
     'Đang điều trị nội nha lấy tủy lần 1 răng R36, đặt thuốc sát khuẩn ống tủy Ca(OH)2 và trám tạm Cavit.', NOW()),
    (606, 'VIS-2026-0606', 606, 1, 606, 'APPOINTMENT', NOW(),                            'Khám kiểm tra men răng và dự phòng sâu răng trẻ em',                     601, 'WAITING',   NULL,
     'Bệnh nhân nhi đang chờ vào buồng khám số 2.', NOW());

INSERT IGNORE INTO clinical_examinations (exam_id, visit_id, profile_id, doctor_id, chief_complaint, history_illness, vital_signs_json, extraoral_exam, intraoral_exam, periodontal_status, odontogram_chart_json, summary_assessment, exam_time) VALUES
    (604, 604, 604, 601,
     'Đau nhức vùng răng khôn hàm dưới phải R48 kéo dài 3 ngày',
     'Đã từng sưng lợi trùm 2 đợt trong 6 tháng qua, tự uống giảm đau không đỡ.',
     '{"bloodPressure":"120/75","heartRate":76,"temperature":36.7}',
     'Mặt cân đối, hạch dưới hàm phải sờ nhẹ không đau.',
     'Răng R48 mọc lệch gần 45 độ, lợi trùm sưng đỏ nhẹ, ấn không có mủ.',
     'Mô nha chu vùng răng hàm dưới ổn định, vôi răng độ I.',
     '{"48":{"status":"IMPACTED","note":"Răng khôn mọc lệch 45 độ"}}',
     'Chẩn đoán Răng khôn R48 mọc lệch ngầm có biến chứng lợi trùm (K01.1). Đã hoàn tất tiểu phẫu nhổ R48 an toàn, cần CSKH gọi hỏi thăm cầm máu sau 24h và nhắc cắt chỉ sau 7 ngày.',
     DATE_SUB(NOW(), INTERVAL 23 HOUR)),
    (605, 605, 605, 601,
     'Đau nhức tự phát về đêm vùng răng hàm dưới bên trái R36',
     'Đau nhói lan lên thái dương trái 2 ngày nay.',
     '{"bloodPressure":"115/70","heartRate":78,"temperature":36.6}',
     'Không sưng mặt ngoài.',
     'Răng R36 sâu lỗ lớn mặt nhai-xa sát buồng tủy, gõ dọc đau nhẹ.',
     'Nướu viền quanh R36 hơi viêm đỏ.',
     '{"36":{"status":"PULPITIS","note":"Viêm tủy không hồi phục"}}',
     'Viêm tủy răng R36 không hồi phục (K04.0). Lưu ý bệnh nhân có tiền sử dị ứng nhẹ Penicillin nên kê đơn kháng sinh nhóm Macrolid/Lincosamid hoặc giảm đau an toàn.',
     DATE_SUB(NOW(), INTERVAL 1 HOUR));

INSERT IGNORE INTO examination_diagnoses (diagnosis_id, exam_id, visit_id, icd10_code, diagnosis_text, diagnosis_type, tooth_numbers, notes) VALUES
    (604, 604, 604, 'K01.1', 'Răng khôn hàm dưới bên phải (R48) mọc lệch 45 độ gây viêm lợi trùm tái phát', 'MAIN',      '48', 'Đã tiểu phẫu nhổ răng khôn R48 bằng sóng siêu âm Piezotome'),
    (605, 604, 604, 'K05.1', 'Viêm nướu cục bộ vùng tam giác hậu hàm R48',                                  'SECONDARY', '47,48', 'Bơm rửa Chlorhexidine 0.12% và khâu đóng vạt'),
    (606, 605, 605, 'K04.0', 'Viêm tủy răng R36 cấp không hồi phục do sâu răng mặt nhai-xa',                'MAIN',      '36', 'Mở tủy, lấy tủy buồng & tủy chân, đặt thuốc nội nha');

INSERT IGNORE INTO prescriptions (prescription_id, prescription_code, visit_id, profile_id, branch_id, doctor_id, diagnosis_text, advice_notes, followup_date, status, created_at) VALUES
    (604, 'TOA-2026-0604', 604, 604, 1, 601,
     '[K01.1] Hậu phẫu tiểu phẫu nhổ răng khôn hàm dưới phải R48 mọc lệch',
     '1. Cắn chặt gạc vô trùng trong 45-60 phút đầu sau nhổ, tuyệt đối không khạc nhổ mạnh hoặc mút vùng ổ răng. 2. Chườm lạnh ngoài má vùng góc hàm phải trong 24 giờ đầu (15 phút chườm, 15 phút nghỉ) để giảm sưng. 3. Ăn cháo nguội, thức ăn mềm lỏng trong 2-3 ngày đầu; kiêng đồ nóng, cay, rượu bia và thuốc lá. 4. Uống thuốc kháng sinh và giảm đau đúng liều theo đơn. Tái khám cắt chỉ sau 7 ngày hoặc liên hệ ngay phòng khám nếu chảy máu tươi kéo dài.',
     DATE_ADD(CURDATE(), INTERVAL 7 DAY), 'DISPENSED', DATE_SUB(NOW(), INTERVAL 22 HOUR)),
    (605, 'TOA-2026-0605', 605, 605, 1, 601,
     '[K04.0] Điều trị nội nha răng R36 lần 1 (Bệnh nhân dị ứng Penicillin)',
     '1. Hạn chế nhai thức ăn cứng bên hàm trái (vùng răng R36 đang đặt thuốc trám tạm). 2. Uống thuốc giảm đau sau ăn no. Nếu miếng trám tạm bị bong hoặc đau nhức tăng lên, liên hệ ngay CSKH. 3. Trở lại phòng khám đúng lịch hẹn sau 5 ngày để hàn kín ống tủy.',
     DATE_ADD(CURDATE(), INTERVAL 5 DAY), 'PENDING', DATE_SUB(NOW(), INTERVAL 1 HOUR));

INSERT IGNORE INTO prescription_items (item_id, prescription_id, drug_name, dosage, duration_days, quantity, bought_quantity, unit, usage_instruction, unit_price) VALUES
    (6041, 604, 'Augmentin 1g (Amoxicillin + Acid Clavulanic)', 'Ngày 2 lần, mỗi lần 1 viên', 5, 10.00, 10.00, 'Viên', 'Uống sau ăn sáng và tối', 25000.00),
    (6042, 604, 'Efferalgan 500mg (Paracetamol)',               'Ngày 3 lần, mỗi lần 1 viên', 3, 9.00,  9.00,  'Viên', 'Hòa tan trong nước uống khi đau (cách nhau ít nhất 4-6h)', 5000.00),
    (6043, 604, 'Alpha Choay (Alphachymotrypsin 4.2mg)',        'Ngày 4 viên chia 2 lần',     5, 20.00, 20.00, 'Viên', 'Ngậm dưới lưỡi chống phù nề sau nhổ răng khôn', 4000.00),
    (6051, 605, 'Paracetamol 500mg',                            'Ngày 2 lần, mỗi lần 1 viên', 3, 6.00,  0.00,  'Viên', 'Uống sau ăn khi đau (an toàn cho BN dị ứng Penicillin)', 3000.00);

INSERT IGNORE INTO customer_care_tasks (task_id, task_code, profile_id, branch_id, task_type, visit_id, appointment_id, prescription_id, assigned_staff_id, escalated_to_doctor_id, escalated_at, due_date, status, notes, doctor_response) VALUES
    (604, 'TASK-CC-0604', 604, 1, 'POST_TREATMENT_FOLLOWUP', 604, 604, 604, 602, NULL, NULL, CURDATE(), 'IN_PROGRESS',
     'Hỏi thăm sức khỏe bệnh nhân Phạm Quốc Bảo sau tiểu phẫu nhổ răng khôn R48 (ca khám VIS-2026-0604): kiểm tra tình trạng cầm máu, độ sưng nề má phải và nhắc uống thuốc theo lời dặn.',
     NULL),
    (605, 'TASK-CC-0605', 605, 1, 'MEDICATION_REMINDER', 605, 605, 605, 602, 601, NOW(), CURDATE(), 'ESCALATED_TO_DOCTOR',
     'Bệnh nhân Lê Hoàng Phương Thảo báo còn hơi ê vùng răng R36 đang đặt thuốc nội nha và hỏi có cần dùng thêm kháng sinh ngoài đơn không (lưu ý BN có tiền sử dị ứng Penicillin).',
     'Đã xem hồ sơ khám VIS-2026-0605: Răng R36 đã lấy sạch tủy viêm và đặt Ca(OH)2, cảm giác tức nhẹ 24h đầu là bình thường. Tuyệt đối không tự ý uống nhóm Amoxicillin/Penicillin. Tiếp tục dùng Paracetamol theo đơn và tái khám đúng hẹn.'),
    (606, 'TASK-CC-0606', 606, 1, 'APPOINTMENT_REMINDER', 606, 606, NULL, 602, NULL, NULL, CURDATE(), 'PENDING',
     'Nhắc lịch khám răng định kỳ và bôi vecni fluor cho bé Phạm Gia Huy (liên hệ qua SĐT phụ huynh Phạm Quốc Bảo).',
     NULL);

INSERT IGNORE INTO customer_care_interactions (interaction_id, task_id, profile_id, staff_id, channel, contact_result, notes, next_retry_at, created_at) VALUES
    (6041, 604, 604, 602, 'PHONE_CALL', 'CONNECTED',
     'Đã gọi điện hỏi thăm anh Phạm Quốc Bảo sau nhổ răng khôn R48: ổ răng đã cầm máu hoàn toàn, má phải chỉ hơi sưng nhẹ, bệnh nhân đã ăn được cháo nguội và uống thuốc cử sáng đúng lời dặn trong #tab-instructions.',
     NULL, DATE_SUB(NOW(), INTERVAL 3 HOUR)),
    (6051, 605, 605, 602, 'ZALO', 'CONNECTED',
     'Chị Phương Thảo nhắn Zalo hỏi về cảm giác tức nhẹ răng R36 và tiền sử dị ứng thuốc. Đã chuyển Bác sĩ Trần Minh Hoàng cho ý kiến chuyên môn.',
     NULL, DATE_SUB(NOW(), INTERVAL 1 HOUR));

