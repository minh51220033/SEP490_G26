-- ====================================================================================================
-- CV-DCMS: 07_appointments_visits_demo.sql (NHÓM LỊCH HẸN, CA KHÁM, EMR, CẬN LÂM SÀNG, ĐƠN THUỐC, LABO & CSKH DEMO)
-- Đối chiếu 100% với Schema cv_dcms_db_backup.sql
-- An toàn khi chạy nhiều lần (Idempotent - ON DUPLICATE KEY UPDATE)
-- ====================================================================================================

SET NAMES utf8mb4;

-- 1. LỊCH HẸN KHÁM (appointments)
INSERT INTO appointments (appointment_id, appointment_code, profile_id, branch_id, doctor_id, service_id, appointment_date, preferred_session, expected_time, duration_minutes, initial_symptoms, source, status, reschedule_count, created_by, created_at) VALUES
    (501, 'LH-SEED-001', 501, 1, 2, 5,  CURDATE(),                           'MORNING',   '08:30:00', 60, 'Đau nhức vùng răng khôn hàm dưới bên phải R48, há miệng hạn chế', 'ONLINE',       'COMPLETED', 0, 12, NOW()),
    (502, 'LH-SEED-002', 502, 1, 2, 6,  CURDATE(),                           'MORNING',   '09:30:00', 60, 'Đau nhức tự phát về đêm vùng răng hàm dưới trái R36',              'RECEPTIONIST', 'CONFIRMED', 0, 4,  NOW()),
    (503, 'LH-SEED-003', 503, 2, 7, 8,  CURDATE(),                           'AFTERNOON', '14:00:00', 60, 'Thử sườn và lắp mão răng sứ Zirconia răng R16',                    'RECEPTIONIST', 'CONFIRMED', 0, 9,  NOW()),
    (504, 'LH-SEED-004', 504, 2, 8, 2,  DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'MORNING',   '09:00:00', 30, 'Khám định kỳ và lấy cao răng siêu âm 2 hàm',                       'ONLINE',       'PENDING',   0, 15, NOW()),
    (505, 'LH-SEED-005', 505, 1, 3, 1,  CURDATE(),                           'MORNING',   '10:15:00', 30, 'Khám kiểm tra răng trẻ em và tư vấn chỉnh nha sớm',                 'ONLINE',       'CONFIRMED', 0, 12, NOW())
ON DUPLICATE KEY UPDATE
    status           = VALUES(status),
    appointment_date = VALUES(appointment_date),
    expected_time    = VALUES(expected_time);

-- 2. LƯỢT KHÁM LÂM SÀNG (visits)
INSERT INTO visits (visit_id, visit_code, profile_id, branch_id, appointment_id, visit_type, check_in_at, initial_symptoms, primary_doctor_id, room_id, chair_id, status, completed_at, notes, created_at) VALUES
    (501, 'VIS-SEED-001', 501, 1, 501, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 3 HOUR), 'Sưng đau góc hàm dưới phải, răng khôn R48 mọc lệch 45 độ gây lợi trùm', 2, 2, 4, 'COMPLETED', DATE_SUB(NOW(), INTERVAL 1 HOUR), 'Đã chụp phim X-Quang Panorama và tiểu phẫu nhổ R48 bằng máy Piezotome an toàn, khâu đóng vạt 3 mũi.', NOW()),
    (502, 'VIS-SEED-002', 502, 1, 502, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 1 HOUR), 'Đau buốt răng hàm dưới trái R36 sâu lỗ lớn sát tủy',                    2, 1, 5, 'IN_EXAM',   NULL,                             'Đang điều trị nội nha lấy tủy lần 1 răng R36, lưu ý bệnh nhân dị ứng nhẹ Penicillin.', NOW()),
    (503, 'VIS-SEED-003', 505, 1, 505, 'APPOINTMENT', NOW(),                            'Khám kiểm tra thay răng sữa và dự phòng sâu răng cho bé Gia Huy',        3, 1, 1, 'WAITING',   NULL,                             'Bệnh nhân nhi đang chờ tại khu vực sảnh Cơ sở 1.', NOW())
ON DUPLICATE KEY UPDATE
    status            = VALUES(status),
    primary_doctor_id = VALUES(primary_doctor_id),
    room_id           = VALUES(room_id),
    chair_id          = VALUES(chair_id),
    notes             = VALUES(notes);

-- 3. SỐ THỨ TỰ HÀNG ĐỢI PHÒNG CHỜ (queue_tickets)
INSERT INTO queue_tickets (ticket_id, branch_id, visit_id, queue_date, queue_type, ticket_number, display_number, is_priority, is_emergency, priority_score, status, issued_at, called_at, completed_at) VALUES
    (501, 1, 501, CURDATE(), 'TREATMENT', 1, 'DT-001', 0, 0, 10, 'DONE',       DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 160 MINUTE), DATE_SUB(NOW(), INTERVAL 60 MINUTE)),
    (502, 1, 502, CURDATE(), 'EXAM',      1, 'KB-001', 0, 0, 10, 'IN_SERVICE', DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_SUB(NOW(), INTERVAL 45 MINUTE),  NULL),
    (503, 1, 503, CURDATE(), 'EXAM',      2, 'KB-002', 1, 0, 20, 'WAITING',    NOW(),                            NULL,                                 NULL)
ON DUPLICATE KEY UPDATE
    status = VALUES(status);

-- 4. BỆNH ÁN ĐIỆN TỬ & SƠ ĐỒ RĂNG ODONTOGRAM (clinical_examinations & examination_diagnoses)
INSERT INTO clinical_examinations (exam_id, visit_id, profile_id, doctor_id, chief_complaint, history_illness, vital_signs_json, extraoral_exam, intraoral_exam, periodontal_status, odontogram_chart_json, summary_assessment, exam_time) VALUES
    (501, 501, 501, 2,
     'Đau nhức vùng răng khôn hàm dưới bên phải R48 kéo dài 3 ngày, há miệng hơi hạn chế',
     'Bệnh nhân đã từng sưng lợi trùm vùng R48 2 đợt trong 6 tháng qua, tự uống giảm đau không đỡ.',
     '{"bloodPressure":"120/75","heartRate":74,"temperature":36.6,"weight":68,"spo2":99}',
     'Mặt cân đối, hạch dưới hàm phải sờ mềm di động nhẹ.',
     'Răng R48 mọc lệch gần 45 độ húc vào cổ răng R47, lợi trùm sưng đỏ nhẹ, ấn không có mủ.',
     'Mô nha chu vùng răng hàm dưới ổn định, vôi răng độ I hai hàm.',
     '{"48":{"status":"IMPACTED","surface":"ALL","note":"Răng khôn mọc lệch gần 45 độ"},"47":{"status":"WATCH","surface":"D","note":"Chèn ép mặt xa do R48"}}',
     'Chẩn đoán: Răng khôn R48 mọc lệch ngầm có biến chứng viêm lợi trùm tái phát (K01.1). Chỉ định chụp X-Quang Panorama khảo sát ống thần kinh răng dưới và tiểu phẫu nhổ R48 bằng máy siêu âm Piezotome.',
     DATE_SUB(NOW(), INTERVAL 170 MINUTE)),
    (502, 502, 502, 2,
     'Đau nhức tự phát về đêm vùng răng hàm dưới bên trái R36 lan lên thái dương trái',
     'Đau tăng lên khi ăn đồ nóng/lạnh trong 2 ngày gần đây. Tiền sử dị ứng nhẹ nhóm Penicillin.',
     '{"bloodPressure":"115/70","heartRate":78,"temperature":36.7,"weight":52,"spo2":99}',
     'Mặt ngoài không sưng, không lệch.',
     'Răng R36 sâu ngà lớn mặt nhai-xa thông vào buồng tủy, gõ dọc đau nhẹ, thử tủy dương tính kéo dài.',
     'Nướu viền quanh R36 hơi viêm đỏ.',
     '{"36":{"status":"PULPITIS","surface":"OD","note":"Viêm tủy cấp không hồi phục"}}',
     'Chẩn đoán: Viêm tủy răng R36 cấp không hồi phục (K04.0). Tiến hành mở tủy, lấy tủy buồng và ống tủy lần 1, đặt thuốc sát khuẩn Ca(OH)2 và kê đơn giảm đau an toàn (tránh nhóm Penicillin).',
     DATE_SUB(NOW(), INTERVAL 40 MINUTE))
ON DUPLICATE KEY UPDATE
    summary_assessment    = VALUES(summary_assessment),
    odontogram_chart_json = VALUES(odontogram_chart_json);

INSERT INTO examination_diagnoses (diagnosis_id, exam_id, visit_id, icd10_code, diagnosis_text, diagnosis_type, tooth_numbers, notes) VALUES
    (501, 501, 501, 'K01.1', 'Răng khôn hàm dưới bên phải (R48) mọc lệch gần 45 độ gây viêm lợi trùm', 'MAIN',      '48',    'Chỉ định tiểu phẫu nhổ R48 bằng máy Piezotome'),
    (502, 501, 501, 'K03.6', 'Cao răng và mảng bám dưới nướu hai hàm độ I',                            'SECONDARY', 'ALL',   'Làm sạch vùng phẫu thuật trước khi nhổ'),
    (503, 502, 502, 'K04.0', 'Viêm tủy răng hàm dưới bên trái (R36) cấp không hồi phục',               'MAIN',      '36',    'Điều trị nội nha răng nhiều chân bằng máy X-Smart')
ON DUPLICATE KEY UPDATE
    diagnosis_text = VALUES(diagnosis_text),
    tooth_numbers  = VALUES(tooth_numbers);

-- 5. CẬN LÂM SÀNG X-QUANG NANOPIX & ẢNH LÂM SÀNG (paraclinical_orders, paraclinical_order_items, clinical_photos)
INSERT INTO paraclinical_orders (order_id, order_code, visit_id, exam_id, profile_id, branch_id, ordered_by, order_type, priority, status, clinical_indication, created_at) VALUES
    (501, 'CLS-SEED-001', 501, 501, 501, 1, 2, 'INTERNAL_XRAY', 'NORMAL', 'COMPLETED', 'Khảo sát hình thái chân răng khôn R48 và khoảng cách tới ống thần kinh răng dưới trước tiểu phẫu.', DATE_SUB(NOW(), INTERVAL 160 MINUTE))
ON DUPLICATE KEY UPDATE
    status = VALUES(status);

INSERT INTO paraclinical_order_items (order_item_id, order_id, service_id, tooth_positions, instructions, unit_price, status, result_text, result_json, result_image_url, nanopix_study_id, performed_by, performed_at) VALUES
    (501, 501, 11, '48', 'Chụp phim Panorama toàn cảnh chuẩn nét vùng góc hàm phải', 200000.00, 'COMPLETED',
     'Răng R48 mọc lệch gần 45 độ, có 2 chân chụm nhẹ, chóp chân răng cách bờ trên ống thần kinh hàm dưới 2.5mm (an toàn phẫu thuật). Xương ổ răng vùng R47-R48 tiêu nhẹ mặt xa R47.',
     '{"modality":"PANORAMA","findings":"R48 mesioangular impaction 45 deg","nerveDistanceMm":2.5,"conclusion":"Safe for Piezotome extraction"}',
     '/uploads/nanopix/sample-xray.png', 'NANOPIX-STUDY-501', 2, DATE_SUB(NOW(), INTERVAL 150 MINUTE))
ON DUPLICATE KEY UPDATE
    status           = VALUES(status),
    result_text      = VALUES(result_text),
    result_image_url = VALUES(result_image_url);

INSERT INTO clinical_photos (photo_id, visit_id, profile_id, uploaded_by, photo_type, image_url, caption, taken_at) VALUES
    (501, 501, 501, 2, 'XRAY',   '/uploads/nanopix/sample-xray.png',             'Phim X-Quang Panorama khảo sát răng khôn R48 trước nhổ', DATE_SUB(NOW(), INTERVAL 150 MINUTE)),
    (502, 501, 501, 2, 'BEFORE', '/uploads/clinical_photos/sample-r48-before.jpg','Hình ảnh trong miệng vùng răng R48 trước tiểu phẫu',     DATE_SUB(NOW(), INTERVAL 145 MINUTE))
ON DUPLICATE KEY UPDATE
    image_url = VALUES(image_url),
    caption   = VALUES(caption);

-- 6. KẾ HOẠCH ĐIỀU TRỊ & THỦ THUẬT ĐÃ THỰC HIỆN (treatment_plans, treatment_plan_items, performed_services)
INSERT INTO treatment_plans (plan_id, plan_code, profile_id, branch_id, doctor_id, created_visit_id, plan_title, status, total_estimated_cost, total_discount, total_final_cost, consent_form_url, approved_at, created_at) VALUES
    (501, 'KHDT-SEED-001', 501, 1, 2, 501, 'Kế hoạch Tiểu phẫu Nhổ răng khôn R48 & Vệ sinh răng miệng', 'COMPLETED',   2300000.00, 0.00, 2300000.00, '/uploads/clinical_photos/sample-consent.pdf', DATE_SUB(NOW(), INTERVAL 140 MINUTE), DATE_SUB(NOW(), INTERVAL 145 MINUTE)),
    (502, 502,             502, 1, 2, 502, 'Kế hoạch Điều trị Nội nha răng hàm R36 bằng máy X-Smart',   'IN_PROGRESS', 1500000.00, 0.00, 1500000.00, NULL,                                          DATE_SUB(NOW(), INTERVAL 30 MINUTE),  DATE_SUB(NOW(), INTERVAL 35 MINUTE))
ON DUPLICATE KEY UPDATE
    plan_code        = 'KHDT-SEED-002',
    status           = VALUES(status),
    total_final_cost = VALUES(total_final_cost);

UPDATE treatment_plans SET plan_code = 'KHDT-SEED-001' WHERE plan_id = 501;
UPDATE treatment_plans SET plan_code = 'KHDT-SEED-002' WHERE plan_id = 502;

INSERT INTO treatment_plan_items (item_id, plan_id, service_id, sequence_order, tooth_numbers, quantity, unit_price, discount_amount, final_amount, status, scheduled_date, completed_visit_id, notes) VALUES
    (501, 501, 1,  1, NULL, 1, 100000.00,  0.00, 100000.00,  'COMPLETED',   CURDATE(), 501, 'Khám tổng quát và kiểm tra sinh hiệu'),
    (502, 501, 11, 2, '48', 1, 200000.00,  0.00, 200000.00,  'COMPLETED',   CURDATE(), 501, 'Chụp phim X-Quang Panorama kỹ thuật số'),
    (503, 501, 5,  3, '48', 1, 2000000.00, 0.00, 2000000.00, 'COMPLETED',   CURDATE(), 501, 'Tiểu phẫu nhổ răng khôn R48 bằng máy Piezotome'),
    (504, 502, 6,  1, '36', 1, 1500000.00, 0.00, 1500000.00, 'IN_PROGRESS', CURDATE(), NULL,'Điều trị tủy răng R36 (Lần 1 mở tủy, đặt thuốc)')
ON DUPLICATE KEY UPDATE
    status       = VALUES(status),
    final_amount = VALUES(final_amount);

INSERT INTO performed_services (performed_id, visit_id, plan_item_id, service_id, doctor_id, assistant_id, tooth_numbers, quantity, unit_price, surcharge_amount, discount_amount, total_price, status, warranty_end_date, procedure_notes, performed_at) VALUES
    (501, 501, 501, 1,  2, NULL, NULL, 1, 100000.00,  0.00, 0.00, 100000.00,  'COMPLETED',   NULL, 'Đã khám lâm sàng và đo huyết áp 120/75 mmHg', DATE_SUB(NOW(), INTERVAL 165 MINUTE)),
    (502, 501, 502, 11, 2, NULL, '48', 1, 200000.00,  0.00, 0.00, 200000.00,  'COMPLETED',   NULL, 'Đã chụp phim X-Quang Panorama R48',           DATE_SUB(NOW(), INTERVAL 150 MINUTE)),
    (503, 501, 503, 5,  2, NULL, '48', 1, 2000000.00, 0.00, 0.00, 2000000.00, 'COMPLETED',   NULL, 'Gây tê vùng thần kinh xương ổ dưới bằng 1.5 ống Septanest, chia cắt thân chân răng R48 bằng mũi Piezotome US1, bơm rửa nước muối sinh lý và khâu 3 mũi chỉ 4.0.', DATE_SUB(NOW(), INTERVAL 90 MINUTE)),
    (504, 502, 504, 6,  2, NULL, '36', 1, 1500000.00, 0.00, 0.00, 1500000.00, 'IN_PROGRESS', DATE_ADD(CURDATE(), INTERVAL 12 MONTH), 'Đang mở buồng tủy và sửa soạn ống tủy R36 lần 1', DATE_SUB(NOW(), INTERVAL 25 MINUTE))
ON DUPLICATE KEY UPDATE
    status      = VALUES(status),
    total_price = VALUES(total_price);

-- 7. ĐƠN THUỐC & CHI TIẾT THUỐC (prescriptions & prescription_items)
INSERT INTO prescriptions (prescription_id, prescription_code, visit_id, profile_id, branch_id, doctor_id, handled_by, diagnosis_text, advice_notes, followup_date, purchase_choice, status, created_at, handled_at) VALUES
    (501, 'TOA-SEED-001', 501, 501, 1, 2, 4,
     '[K01.1] Hậu phẫu tiểu phẫu nhổ răng khôn hàm dưới bên phải R48 mọc lệch',
     '1. Cắn chặt gạc vô trùng trong 45-60 phút sau nhổ, không khạc nhổ mạnh hoặc mút vùng ổ răng. 2. Chườm lạnh ngoài má phải trong 24 giờ đầu (15 phút chườm, 15 phút nghỉ). 3. Ăn cháo nguội, thức ăn mềm lỏng 2-3 ngày đầu. 4. Uống thuốc đúng đơn và tái khám cắt chỉ sau 7 ngày.',
     DATE_ADD(CURDATE(), INTERVAL 7 DAY), 'CLINIC', 'DISPENSED', DATE_SUB(NOW(), INTERVAL 80 MINUTE), DATE_SUB(NOW(), INTERVAL 65 MINUTE)),
    (502, 'TOA-SEED-002', 502, 502, 1, 2, NULL,
     '[K04.0] Điều trị nội nha răng R36 lần 1 (Lưu ý: Bệnh nhân dị ứng Penicillin)',
     '1. Hạn chế nhai đồ cứng bên hàm trái đang đặt thuốc trám tạm. 2. Sử dụng kháng sinh Dalacin C (Clindamycin) và giảm đau Efferalgan an toàn cho cơ địa dị ứng Penicillin. 3. Hẹn tái khám sau 5 ngày để hàn kín ống tủy.',
     DATE_ADD(CURDATE(), INTERVAL 5 DAY), NULL, 'PENDING', DATE_SUB(NOW(), INTERVAL 20 MINUTE), NULL)
ON DUPLICATE KEY UPDATE
    status       = VALUES(status),
    advice_notes = VALUES(advice_notes);

INSERT INTO prescription_items (item_id, prescription_id, medication_id, drug_name, dosage, duration_days, quantity, bought_quantity, unit, unit_price, usage_instruction) VALUES
    (5011, 501, 1, 'Augmentin 1g (GSK)',         'Ngày 2 lần, mỗi lần 1 viên', 5, 10.00, 10.00, 'Viên', 25000.00, 'Uống sau ăn sáng và tối'),
    (5012, 501, 4, 'Efferalgan 500mg Sủi',       'Ngày 3 lần, mỗi lần 1 viên', 3,  9.00,  9.00, 'Viên',  5000.00, 'Hòa tan trong nước uống khi đau (cách nhau 4-6h)'),
    (5013, 501, 6, 'Alpha Choay (Sanofi)',       'Ngày 4 viên chia 2 lần',     5, 20.00, 20.00, 'Viên',  4000.00, 'Ngậm dưới lưỡi chống phù nề sau tiểu phẫu'),
    (5021, 502, 3, 'Dalacin C 300mg (Pfizer)',   'Ngày 2 lần, mỗi lần 1 viên', 5, 10.00,  0.00, 'Viên', 12000.00, 'Uống sau ăn sáng và tối (An toàn cho BN dị ứng Penicillin)'),
    (5022, 502, 4, 'Efferalgan 500mg Sủi',       'Ngày 2 lần, mỗi lần 1 viên', 3,  6.00,  0.00, 'Viên',  5000.00, 'Uống sau ăn khi đau')
ON DUPLICATE KEY UPDATE
    quantity        = VALUES(quantity),
    bought_quantity = VALUES(bought_quantity),
    unit_price      = VALUES(unit_price);

-- 8. ĐƠN ĐẶT HÀNG RĂNG SỨ LABO (labo_orders)
INSERT INTO labo_orders (labo_id, labo_code, branch_id, visit_id, profile_id, doctor_id, supplier_id, order_type, material_type, tooth_positions, shade_color, quantity, crown_info, bridge_info, unit_price, cost_to_clinic, sent_date, try_in_date, try_in_time, expected_date, received_date, status, notes) VALUES
    (501, 'LABO-SEED-001', 2, NULL, 503, 7, 4, 'NEW', ' Toàn sứ Đức Zirconia HT CAD/CAM', '16', 'A2 VITA 3D', 1, 'Mão đơn lẻ R16 khít sát đường hoàn tất bờ vai', NULL, 2200000.00, 2200000.00, DATE_SUB(CURDATE(), INTERVAL 3 DAY), CURDATE(), '14:00', CURDATE(), CURDATE(), 'DELIVERED', 'Xưởng Labo Việt Đức đã giao mẫu mão sứ R16 đúng màu A2, sẵn sàng lắp cho BN Trần Đình Trọng chiều nay.')
ON DUPLICATE KEY UPDATE
    status         = VALUES(status),
    cost_to_clinic = VALUES(cost_to_clinic);

-- 9. CHĂM SÓC KHÁCH HÀNG HẬU PHẪU & NHẮC LỊCH (customer_care_tasks & customer_care_interactions)
INSERT INTO customer_care_tasks (task_id, task_code, profile_id, branch_id, task_type, visit_id, appointment_id, prescription_id, assigned_staff_id, escalated_to_doctor_id, escalated_at, due_date, status, notes, doctor_response) VALUES
    (501, 'CSKH-SEED-001', 501, 1, 'POST_TREATMENT_FOLLOWUP', 501, 501, 501, 4, NULL, NULL, CURDATE(), 'IN_PROGRESS',
     'Gọi điện hỏi thăm bệnh nhân Phạm Quốc Bảo sau tiểu phẫu nhổ răng khôn R48: kiểm tra tình trạng cầm máu, độ sưng nề má phải và nhắc uống thuốc theo đơn.',
     NULL),
    (502, 'CSKH-SEED-002', 502, 1, 'MEDICATION_REMINDER',     502, 502, 502, 4, 2,    NOW(), CURDATE(), 'ESCALATED_TO_DOCTOR',
     'Bệnh nhân Lê Hoàng Phương Thảo nhắn hỏi cảm giác hơi tức vùng răng R36 đang đặt thuốc nội nha và xác nhận lại loại kháng sinh dùng cho người dị ứng Penicillin.',
     'Bác sĩ Lê Hoàng Nam phản hồi: Răng R36 đã lấy sạch tủy buồng và đặt thuốc sát khuẩn Ca(OH)2, cảm giác tức nhẹ 24h đầu là hoàn toàn bình thường. Đơn thuốc đã kê Dalacin C 300mg (nhóm Lincosamid) an toàn tuyệt đối cho người dị ứng Penicillin.')
ON DUPLICATE KEY UPDATE
    status          = VALUES(status),
    notes           = VALUES(notes),
    doctor_response = VALUES(doctor_response);

INSERT INTO customer_care_interactions (interaction_id, task_id, profile_id, staff_id, channel, contact_result, notes, next_retry_at, created_at) VALUES
    (5011, 501, 501, 4, 'PHONE_CALL', 'CONNECTED', 'Đã gọi hỏi thăm anh Phạm Quốc Bảo: ổ răng R48 đã cầm máu tốt, bệnh nhân đang chườm lạnh và đã ăn cháo nguội.', NULL, DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
    (5021, 502, 502, 4, 'ZALO',       'CONNECTED', 'Tiếp nhận câu hỏi của chị Phương Thảo qua Zalo và chuyển Bác sĩ Lê Hoàng Nam phản hồi chuyên môn.',            NULL, DATE_SUB(NOW(), INTERVAL 15 MINUTE))
ON DUPLICATE KEY UPDATE
    notes = VALUES(notes);
