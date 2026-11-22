-- ====================================================================================================
-- FLYWAY MIGRATION V119: BO DU LIEU DEMO DAY DU GIONG DEMO_WORKFLOW_FRAMES.HTML
-- Muc dich: Tao san tai khoan, bac si, benh nhan, luot kham hom nay, phim X-quang (SVG), phieu Labo,
--           quy trinh 5 khung (co truong tuy chinh Do khit), Combo VTTech va 4 ca kham mau de demo ngay.
-- An toan tuyet doi: Khong ghi de du lieu cu, moi buoc deu kiem tra ton tai (Idempotent).
-- ====================================================================================================

-- ----------------------------------------------------------------------------------------------------
-- 1. BO SUNG BAC SI DEMO (Mat khau: admin123) & XUONG LABO HAI DANG
-- ----------------------------------------------------------------------------------------------------
INSERT INTO users (username, password, email, phone_number, branch_id, user_type, status, is_active, custom_permissions_enabled, created_date, updated_at)
SELECT u.uname, '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe',
       u.email, u.phone, b.branch_id, 'STAFF', 'ACTIVE', 1, 0, NOW(), NOW()
FROM (
    SELECT 'bs_khoa' AS uname, 'bs.khoa.demo@dentalclinic.com' AS email, '0904221001' AS phone UNION ALL
    SELECT 'bs_anh',           'bs.anh.demo@dentalclinic.com',           '0904221002'
) u
JOIN branches b ON b.branch_code = 'CN01'
WHERE NOT EXISTS (SELECT 1 FROM users ex WHERE ex.username = u.uname OR ex.phone_number = u.phone OR ex.email = u.email);

INSERT IGNORE INTO user_roles (user_id, role_id)
SELECT u.user_id, r.role_id
FROM users u
JOIN roles r ON r.role_name = 'BAC_SI'
WHERE u.username IN ('bs_khoa', 'bs_anh');

INSERT INTO staff_profiles (
    user_id, staff_code, full_name, gender, dob, phone_number, email,
    department, position_title, specialty, practice_license_no,
    is_tax_resident, contract_type, has_personal_deduction, dependent_count, has_tax_commitment,
    base_salary, allowance_amount, insurance_salary, hire_date, status, created_at, updated_at
)
SELECT u.user_id, src.staff_code, src.full_name, src.gender, src.dob, u.phone_number, u.email,
       'Lâm sàng', 'Bác sĩ Răng-Hàm-Mặt', src.spec, src.cchn,
       1, 'FULL_CONTRACT', 1, 0, 0, 24000000.00, 3000000.00, 18000000.00, '2025-01-01', 'ACTIVE', NOW(), NOW()
FROM (
    SELECT 'bs_khoa' AS uname, 'BS-DEMO-01' AS staff_code, 'BS. Trần Minh Khoa' AS full_name, 'MALE'   AS gender, '1988-05-10' AS dob, 'Phục hình sứ & Tiểu phẫu' AS spec, '009101/BYT-CCHN' AS cchn UNION ALL
    SELECT 'bs_anh',           'BS-DEMO-02',                'BS. Lê Ngọc Anh',    'FEMALE',            '1991-09-18' AS dob, 'Nội nha & Chỉnh nha'      AS spec, '009102/BYT-CCHN' AS cchn
) src
JOIN users u ON u.username = src.uname
WHERE NOT EXISTS (SELECT 1 FROM staff_profiles sp WHERE sp.staff_code = src.staff_code OR sp.user_id = u.user_id);

INSERT INTO staff_branch_assignments (staff_id, branch_id, role_id, is_primary, start_date, status, created_at)
SELECT sp.staff_id, b.branch_id, r.role_id, 1, '2025-01-01', 'ACTIVE', NOW()
FROM staff_profiles sp
JOIN branches b ON b.branch_code = 'CN01'
JOIN roles r ON r.role_name = 'BAC_SI'
WHERE sp.staff_code IN ('BS-DEMO-01', 'BS-DEMO-02')
  AND NOT EXISTS (
      SELECT 1 FROM staff_branch_assignments sba
      WHERE sba.staff_id = sp.staff_id AND sba.branch_id = b.branch_id
  );

INSERT INTO suppliers (supplier_code, supplier_name, tax_code, phone_number, address, is_active)
SELECT 'NCC-LABO-HD', 'Xưởng Labo Hải Đăng', '0319887766', '0909334455', '210 Lý Tự Trọng, Quận 1, TP.HCM', 1
WHERE NOT EXISTS (SELECT 1 FROM suppliers WHERE supplier_code = 'NCC-LABO-HD');

-- ----------------------------------------------------------------------------------------------------
-- 2. DICH VU & MAU QUY TRINH 5 KHUNG GIONG DEMO_WORKFLOW_FRAMES.HTML
-- ----------------------------------------------------------------------------------------------------
INSERT INTO services (
    category_id, service_code, service_name, image_url, service_type, unit,
    base_price, surcharge_amount, estimated_duration_minutes, requires_tooth,
    is_paraclinical, is_bookable_online, warranty_months, description, status, created_at
)
SELECT sc.category_id, 'DV-XN-MAU', 'Lấy máu xét nghiệm tiền phẫu', '/uploads/images/clinic_treatment.png',
       'PROCEDURE', 'Lần', 350000.00, 0.00, 15, 0, 1, 1, NULL,
       'Xét nghiệm công thức máu & đông máu cơ bản trước tiểu phẫu hoặc chỉnh nha.', 'ACTIVE', NOW()
FROM service_categories sc
WHERE sc.category_code = 'CAT_EXAM'
  AND NOT EXISTS (SELECT 1 FROM services s WHERE s.service_code = 'DV-XN-MAU');

-- Bo sung Quy trinh 4 buoc Phuc hinh mao su Zirconia (QT-PHUCHINH) + Truong rieng "Do khit" o buoc Thu mao
INSERT INTO clinical_workflow_templates (template_code, template_name, description, service_id, clinic_id, status, created_by, created_at, updated_at)
SELECT src.template_code, src.template_name, src.description, sv.service_id, b.branch_id, 'ACTIVE',
       (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1), NOW(), NOW()
FROM (
    SELECT 'QT-PHUCHINH' AS template_code, 'Phục hình mão sứ Zirconia' AS template_name,
           'Quy trình phục hình mão răng gồm Khám, X-quang kiểm tra, bước Thử mão (có trường Độ khít) và Gắn cố định.' AS description,
           'DV-SU-ZIRCO' AS service_code
    UNION ALL
    SELECT 'QT-XETNGHIEM', 'Lấy máu xét nghiệm',
           'Dịch vụ cận lâm sàng tiền thủ thuật trước tiểu phẫu hoặc chỉnh nha.',
           'DV-XN-MAU'
) src
JOIN services sv ON sv.service_code = src.service_code
JOIN branches b ON b.branch_code = 'CN01'
WHERE NOT EXISTS (SELECT 1 FROM clinical_workflow_templates t WHERE t.template_code = src.template_code);

INSERT INTO clinical_workflow_versions (template_id, version_number, status, flow_mode, effective_from, change_summary, created_by, approved_by, created_at, published_at)
SELECT t.template_id, 1, 'PUBLISHED', 'INTERRUPTIBLE', NOW(), 'Khởi tạo chuẩn theo mẫu quy trình 5 khung thao tác',
       (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1),
       (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1),
       NOW(), NOW()
FROM clinical_workflow_templates t
WHERE t.template_code IN ('QT-PHUCHINH', 'QT-XETNGHIEM')
  AND NOT EXISTS (
      SELECT 1 FROM clinical_workflow_versions v WHERE v.template_id = t.template_id AND v.version_number = 1
  );

INSERT INTO clinical_workflow_steps (
    version_id, step_code, step_name, step_description, step_type, frame_key, sequence_order,
    is_optional, queue_priority, completion_criteria, custom_fields_json, requires_clinical_approval, estimated_duration_minutes, step_price, status
)
SELECT v.version_id, st.step_code, st.step_name, st.step_description, st.step_type, st.frame_key, st.seq,
       st.is_opt, 0, st.criteria, st.cf_json, 0, st.duration_min, st.step_price, 'ACTIVE'
FROM (
    SELECT 'QT-PHUCHINH' AS tpl_code, 'PH-B1' AS step_code, 'Khám lâm sàng cùi răng' AS step_name,
           'Kiểm tra cùi răng, đường hoàn tất, mô nướu xung quanh và độ nhạy cảm trước gắn mão.' AS step_description,
           'CLINICAL' AS step_type, 'CLINICAL_EXAM' AS frame_key, 1 AS seq, 0 AS is_opt,
           'Cùi răng hoàn thiện bờ vai rõ, mô nướu hồng hào ổn định' AS criteria,
           NULL AS cf_json, 15 AS duration_min, 500000.00 AS step_price
    UNION ALL SELECT 'QT-PHUCHINH', 'PH-B2', 'X-quang kiểm tra mô quanh chóp',
           'Kiểm tra phim X-quang vùng chóp và chất lượng nội nha trước khi thử và gắn mão sứ cố định.',
           'PARACLINICAL', 'XRAY', 2, 0,
           'Có phim X-quang quanh chóp đạt chuẩn không thấu quang bệnh lý',
           NULL, 15, 500000.00
    UNION ALL SELECT 'QT-PHUCHINH', 'PH-B3', 'Thử mão',
           'Nhận mẫu từ xưởng Labo, thử mão trên miệng bệnh nhân và đánh giá độ khít sát bờ viền, khớp cắn.',
           'TREATMENT', 'PROSTHETICS_LABO', 3, 0,
           'Đánh giá độ khít đạt chuẩn và xác nhận màu sắc với bệnh nhân',
           '[{"id":"cf-do-khit","label":"Độ khít","type":"select","options":["Đạt","Cần chỉnh"]}]',
           25, 2000000.00
    UNION ALL SELECT 'QT-PHUCHINH', 'PH-B4', 'Gắn cố định & Dặn dò',
           'Gắn cố định mão toàn sứ Zirconia bằng xi măng chuyên dụng, làm sạch xi măng dư và dặn dò vệ sinh.',
           'TREATMENT', 'PROCEDURE', 4, 0,
           'Mão sứ khít sát, không cộm khớp cắn, sạch xi măng dư dưới nướu',
           NULL, 20, 1500000.00
    UNION ALL SELECT 'QT-XETNGHIEM', 'XN-B1', 'Lấy mẫu & ghi nhận chỉ số tiền thủ thuật',
           'Lấy mẫu máu xét nghiệm công thức máu, thời gian đông chảy máu trước can thiệp.',
           'CLINICAL', 'PROCEDURE', 1, 0,
           'Các chỉ số đông máu và sinh hóa đạt ngưỡng an toàn thủ thuật',
           NULL, 15, 350000.00
) st
JOIN clinical_workflow_templates t ON t.template_code = st.tpl_code
JOIN clinical_workflow_versions v ON v.template_id = t.template_id AND v.version_number = 1
WHERE NOT EXISTS (
    SELECT 1 FROM clinical_workflow_steps s WHERE s.version_id = v.version_id AND s.step_code = st.step_code
);

-- Bat INTERRUPTIBLE cho các quy trinh demo nhieu buoi (tam tat safe update trong phien de Workbench chay duoc ca multi-table UPDATE)
SET @prev_safe_updates = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

UPDATE clinical_workflow_versions v
JOIN clinical_workflow_templates t ON t.template_id = v.template_id
SET v.flow_mode = 'INTERRUPTIBLE'
WHERE v.version_id > 0
  AND t.template_code IN ('QTKT-TUY-RH', 'QT-NHO-RKHON', 'QT-PHUCHINH', 'QTKT-NIENG-RANG');

-- Cap nhat Combo CB-TUY-SU tro ve mao Zirconia 4 buoc de test tron ven Buoc 3 Thu mao + Do khit + Labo
UPDATE service_combo_items sci
JOIN service_combos sc ON sc.combo_id = sci.combo_id
JOIN services s_old ON s_old.service_id = sci.service_id AND s_old.service_code = 'DV-SU-TITAN'
JOIN services s_new ON s_new.service_code = 'DV-SU-ZIRCO'
SET sci.service_id = s_new.service_id,
    sci.notes = 'Phục hình mão sứ Zirconia 4 bước (Thử mão & Labo)'
WHERE sci.combo_item_id > 0
  AND sc.combo_code = 'CB-TUY-SU';

SET SQL_SAFE_UPDATES = @prev_safe_updates;

-- ----------------------------------------------------------------------------------------------------
-- 3. 4 BENH NHAN & 4 LUOT KHAM HOM NAY (CURDATE) GIONG DEMO_WORKFLOW_FRAMES.HTML
-- ----------------------------------------------------------------------------------------------------
INSERT INTO users (username, password, email, phone_number, branch_id, user_type, status, is_active, custom_permissions_enabled, created_date, updated_at)
SELECT p.uname, '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe',
       p.email, p.phone, b.branch_id, 'PATIENT', 'ACTIVE', 1, 0, NOW(), NOW()
FROM (
    SELECT 'bn_tuan16' AS uname, 'tuan.nguyen16@gmail.com' AS email, '0904221816' AS phone UNION ALL
    SELECT 'bn_ha36',            'ha.tran36@gmail.com',               '0912785036'          UNION ALL
    SELECT 'bn_nam48',           'nam.le48@gmail.com',                '0988334048'          UNION ALL
    SELECT 'bn_vy14',            'vy.pham14@gmail.com',               '0905141414'
) p
JOIN branches b ON b.branch_code = 'CN01'
WHERE NOT EXISTS (SELECT 1 FROM users ex WHERE ex.username = p.uname OR ex.phone_number = p.phone OR ex.email = p.email);

INSERT IGNORE INTO user_roles (user_id, role_id)
SELECT u.user_id, r.role_id
FROM users u
JOIN roles r ON r.role_name = 'BENH_NHAN'
WHERE u.username IN ('bn_tuan16', 'bn_ha36', 'bn_nam48', 'bn_vy14');

INSERT INTO patient_profiles (
    user_id, patient_code, full_name, dob, gender, phone_number, address,
    has_allergies, allergy_details, has_underlying_disease, underlying_disease_details,
    medical_history_completed, preferred_branch_id, is_default, deposit_balance, customer_source, status, created_at, updated_at
)
SELECT u.user_id, src.pcode, src.fname, src.dob, src.gender, u.phone_number, src.addr,
       0, 'Không dị ứng thuốc', 0, 'Không bệnh nền',
       1, b.branch_id, 1, 0.00, 'Demo VTTech / BYT', 'ACTIVE', NOW(), NOW()
FROM (
    SELECT 'bn_tuan16' AS uname, 'BN-2026-016' AS pcode, 'Nguyễn Minh Tuấn' AS fname, '1993-05-12' AS dob, 'Nam' AS gender, '120 Nguyễn Trãi, Quận 1, TP.HCM' AS addr UNION ALL
    SELECT 'bn_ha36',            'BN-2026-036',          'Trần Thu Hà',              '1996-08-24',        'Nữ',            '45 Võ Văn Tần, Quận 3, TP.HCM'            UNION ALL
    SELECT 'bn_nam48',           'BN-2026-048',          'Lê Hoàng Nam',             '1995-02-18',        'Nam',           '88 Điện Biên Phủ, Bình Thạnh, TP.HCM'      UNION ALL
    SELECT 'bn_vy14',            'BN-2026-014',          'Phạm Thảo Vy',             '2000-11-02',        'Nữ',            '19 Phan Xích Long, Phú Nhuận, TP.HCM'
) src
JOIN users u ON u.username = src.uname
JOIN branches b ON b.branch_code = 'CN01'
WHERE NOT EXISTS (SELECT 1 FROM patient_profiles pp WHERE pp.patient_code = src.pcode OR pp.phone_number = u.phone_number);

INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, is_emergency, is_booked_and_on_time,
    check_in_at, actual_start_time, initial_symptoms, primary_doctor_id, room_id, chair_id, status, notes, created_at
)
SELECT src.vcode, pp.profile_id, b.branch_id, 'APPOINTMENT', 0, 1,
       DATE_SUB(NOW(), INTERVAL src.mins_ago MINUTE), DATE_SUB(NOW(), INTERVAL src.mins_ago MINUTE),
       src.symptoms, doc.staff_id, r.room_id, c.chair_id, src.vstatus, src.vnotes, NOW()
FROM (
    SELECT 'VIS-DEMO-1601' AS vcode, 'BN-2026-016' AS pcode, 'BS-DEMO-01' AS scode, 'P102' AS rcode, 'G02' AS ccode,
           'IN_TREATMENT' AS vstatus, 90 AS mins_ago,
           'Ca R16 · Phục hình mão toàn sứ Zirconia sau nội nha (Labo LAB-2026-016 - Buổi Thử mão)' AS symptoms,
           'Demo Bước 3 Thử mão · Khung Phục hình/Labo + Trường Độ khít + Lưu 2 buổi' AS vnotes
    UNION ALL
    SELECT 'VIS-DEMO-3602', 'BN-2026-036', 'BS-DEMO-02', 'P102', 'G02',
           'IN_TREATMENT', 65,
           'Ca R36 · Combo Điều trị tủy & Bọc mão sứ Zirconia (Đang thực hiện nội nha)',
           'Demo Combo 2 dịch vụ nối tiếp: Điều trị tủy -> Phục hình mão sứ + Labo LAB-2026-036'
    UNION ALL
    SELECT 'VIS-DEMO-4803', 'BN-2026-048', 'BS-DEMO-01', 'P102', 'G02',
           'IN_TREATMENT', 40,
           'Ca R48 · Tiểu phẫu nhổ răng khôn hàm dưới mọc lệch 90 độ sát ống thần kinh',
           'Demo thêm bước phát sinh (Adhoc Step) trong ca nhổ răng khôn'
    UNION ALL
    SELECT 'VIS-DEMO-1404', 'BN-2026-014', 'BS-DEMO-01', 'P103', 'G03',
           'IN_EXAM', 15,
           'Ca Niềng răng chỉnh nha toàn hàm · Có sẵn Kế hoạch điều trị 4 bước (32.000.000 đ)',
           'Demo Kế hoạch điều trị (Chỉnh nha 4 bước) đồng bộ từng bước <-> Quy trình <-> Thu ngân'
) src
JOIN patient_profiles pp ON pp.patient_code = src.pcode
JOIN branches b ON b.branch_code = 'CN01'
JOIN staff_profiles doc ON doc.staff_code = src.scode
LEFT JOIN rooms r ON r.branch_id = b.branch_id AND r.room_code = src.rcode
LEFT JOIN dental_chairs c ON c.branch_id = b.branch_id AND c.chair_code = src.ccode
WHERE NOT EXISTS (SELECT 1 FROM visits v WHERE v.visit_code = src.vcode);

-- Cap quyen cover tu dong cho TAT CA bac si dang hoat dong (ke ca tai khoan bac si ban dang dang nhap) de xem & thao tac duoc ca 4 ca demo
INSERT INTO doctor_cover_grants (visit_id, covering_doctor_id, granted_by_user_id, reason, granted_at, expires_at)
SELECT DISTINCT v.visit_id, sp.staff_id,
       COALESCE((SELECT user_id FROM users WHERE username = 'admin' LIMIT 1), sp.user_id),
       'Demo nhóm: mở quyền truy cập liên khoa cho 4 ca mẫu', NOW(), DATE_ADD(NOW(), INTERVAL 365 DAY)
FROM visits v
JOIN staff_branch_assignments sba ON sba.status = 'ACTIVE'
JOIN roles r ON r.role_id = sba.role_id AND UPPER(r.role_name) IN ('BAC_SI', 'ADMIN')
JOIN staff_profiles sp ON sp.staff_id = sba.staff_id AND sp.status = 'ACTIVE'
WHERE v.visit_code IN ('VIS-DEMO-1601', 'VIS-DEMO-3602', 'VIS-DEMO-4803', 'VIS-DEMO-1404')
  AND v.primary_doctor_id <> sp.staff_id
  AND NOT EXISTS (
      SELECT 1 FROM doctor_cover_grants g
      WHERE g.visit_id = v.visit_id AND g.covering_doctor_id = sp.staff_id AND g.revoked_at IS NULL
  );

-- ----------------------------------------------------------------------------------------------------
-- 4. KHAM LAM SANG & CHAN DOAN ICD-10 CHO 4 CA DEMO
-- ----------------------------------------------------------------------------------------------------
INSERT INTO clinical_examinations (
    visit_id, profile_id, doctor_id, chief_complaint, history_illness,
    vital_signs_json, extraoral_exam, intraoral_exam, periodontal_status, odontogram_chart_json, summary_assessment, exam_time
)
SELECT v.visit_id, v.profile_id, v.primary_doctor_id, src.cc, src.hist,
       '{"bloodPressure":"120/75","heartRate":75,"temperature":36.6}',
       'Mặt cân đối, hạch ngoại vi không sưng đau.', src.intra, 'Mô nha chu ổn định.',
       src.chart_json, src.summary, DATE_SUB(NOW(), INTERVAL 30 MINUTE)
FROM (
    SELECT 'VIS-DEMO-1601' AS vcode,
           'Gắn mão sứ bảo vệ răng 16 sau điều trị nội nha' AS cc,
           'Răng 16 đã hoàn tất điều trị tủy 1 tuần trước, không còn đau nhức.' AS hist,
           'Cùi răng 16 hoàn thiện bờ vai rõ, nướu xung quanh hồng hào, gõ dọc không đau.' AS intra,
           '{"16":{"status":"CROWN","note":"Đã mài cùi chờ thử & gắn mão Zirconia"}}' AS chart_json,
           'Chỉ định thử & gắn mão toàn sứ Zirconia R16.' AS summary
    UNION ALL
    SELECT 'VIS-DEMO-3602',
           'Đau nhức răng hàm dưới trái R36 khi ăn nhai',
           'Đau tự phát về đêm 2 ngày nay, ê buốt kéo dài.',
           'Lỗ sâu lớn mặt nhai R36 sát buồng tủy, gõ dọc đau nhẹ.',
           '{"36":{"status":"PULPITIS","note":"Viêm tủy không hồi phục"}}',
           'Chẩn đoán K04.0 Viêm tủy cấp R36. Chỉ định Combo Điều trị tủy & Bọc mão sứ.'
    UNION ALL
    SELECT 'VIS-DEMO-4803',
           'Viêm lợi trùm răng khôn hàm dưới phải R48',
           'Sưng đau vùng góc hàm phải tái phát nhiều lần.',
           'R48 mọc kẹt bán phần, lợi trùm phù nề nhẹ.',
           '{"48":{"status":"IMPACTED","note":"Mọc lệch 90 độ sát ống thần kinh"}}',
           'Chẩn đoán K01.1 R48 mọc ngầm lệch ngang. Chỉ định tiểu phẫu nhổ R48.'
    UNION ALL
    SELECT 'VIS-DEMO-1404',
           'Tư vấn niềng răng chỉnh nha toàn hàm do chen chúc khớp cắn lệch',
           'Muốn cải thiện thẩm mỹ nụ cười và khớp cắn.',
           'Răng cửa hàm trên và dưới chen chúc trung bình, tương quan hạng I.',
           '{"14":{"status":"MALOCCLUSION","note":"Chen chúc cung hàm"}}',
           'Chẩn đoán K07.3 Lệch lạc vị trí răng. Lập phác đồ Niềng răng mắc cài 4 bước.'
) src
JOIN visits v ON v.visit_code = src.vcode
WHERE NOT EXISTS (SELECT 1 FROM clinical_examinations ce WHERE ce.visit_id = v.visit_id);

-- Dam bao 4 ma ICD-10 demo ton tai trong icd10_diseases tranh loi FK fk_ed_icd10
INSERT IGNORE INTO icd10_diseases (icd10_code, disease_name_vi, disease_name_en, is_dental_common, is_active) VALUES
('K02.1', 'Sâu ngà răng / Mất mô cứng thân răng', 'Caries of dentine', 1, 1),
('K04.0', 'Viêm tủy răng', 'Pulpitis', 1, 1),
('K01.1', 'Răng ngầm / Răng khôn mọc lệch', 'Impacted teeth', 1, 1),
('K07.3', 'Răng mọc lệch vị trí (Chen chúc, thưa, xoay trục)', 'Anomalies of tooth position', 1, 1);

INSERT INTO examination_diagnoses (exam_id, visit_id, icd10_code, diagnosis_text, diagnosis_type, tooth_numbers, notes)
SELECT ce.exam_id, ce.visit_id, ic.icd10_code, src.dtext, 'MAIN', src.teeth, src.dnotes
FROM (
    SELECT 'VIS-DEMO-1601' AS vcode, 'K02.1' AS icd, 'Mất mô cứng thân răng R16 sau điều trị nội nha (chỉ định mão sứ)' AS dtext, '16' AS teeth, 'Đã mài cùi & gửi Labo' AS dnotes UNION ALL
    SELECT 'VIS-DEMO-3602',          'K04.0',        'Viêm tủy không hồi phục răng hàm dưới trái R36',                           '36',          'Áp dụng Combo Tủy + Sứ'       UNION ALL
    SELECT 'VIS-DEMO-4803',          'K01.1',        'Răng khôn hàm dưới phải R48 mọc lệch ngang 90° sát ống thần kinh',         '48',          'Tiểu phẫu nhổ R48'             UNION ALL
    SELECT 'VIS-DEMO-1404',          'K07.3',        'Răng chen chúc, sai lệch vị trí cần chỉnh nha toàn hàm',                   'Toàn hàm',    'Kế hoạch niềng răng 4 bước'
) src
JOIN visits v ON v.visit_code = src.vcode
JOIN clinical_examinations ce ON ce.visit_id = v.visit_id
LEFT JOIN icd10_diseases ic ON ic.icd10_code = src.icd
WHERE NOT EXISTS (SELECT 1 FROM examination_diagnoses ed WHERE ed.visit_id = v.visit_id);

-- ----------------------------------------------------------------------------------------------------
-- 5. PHIM X-QUANG (PARACLINICAL + ANH SVG DEMO) & PHIEU LABO HAI DANG (LAB-2026-016, LAB-2026-036)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO paraclinical_orders (order_code, visit_id, profile_id, doctor_id, status, order_time, completed_at)
SELECT src.ocode, v.visit_id, v.profile_id, v.primary_doctor_id, src.ostatus, DATE_SUB(NOW(), INTERVAL 40 MINUTE), src.comp_at
FROM (
    SELECT 'CLS-DEMO-16' AS ocode, 'VIS-DEMO-1601' AS vcode, 'IN_PROGRESS' AS ostatus, NULL AS comp_at UNION ALL
    SELECT 'CLS-DEMO-36',          'VIS-DEMO-3602',          'COMPLETED',              NOW()           UNION ALL
    SELECT 'CLS-DEMO-48',          'VIS-DEMO-4803',          'COMPLETED',              NOW()
) src
JOIN visits v ON v.visit_code = src.vcode
WHERE NOT EXISTS (SELECT 1 FROM paraclinical_orders po WHERE po.order_code = src.ocode);

INSERT INTO paraclinical_order_items (
    order_id, service_id, tooth_numbers, status, technician_id, performed_at, result_summary, external_ref_id
)
SELECT po.order_id, sv.service_id, src.tooth, src.istatus, v.primary_doctor_id, src.perf_at, src.rsumm, src.xref
FROM (
    SELECT 'CLS-DEMO-16' AS ocode, '16' AS tooth, 'COMPLETED' AS istatus, DATE_SUB(NOW(), INTERVAL 35 MINUTE) AS perf_at,
           '3 ống tủy răng 16 trám bít đặc, chiều dài chuẩn, vùng quanh chóp sạch không có tiêu xương.' AS rsumm, 'XQ-16-001' AS xref
    UNION ALL
    SELECT 'CLS-DEMO-16', '16', 'WAITING', NULL,
           NULL, 'XQ-16-002'
    UNION ALL
    SELECT 'CLS-DEMO-36', '36', 'COMPLETED', DATE_SUB(NOW(), INTERVAL 25 MINUTE),
           'Sâu sát buồng tủy, có vùng thấu quang nhẹ quanh chóp chân gần R36.', 'XQ-36-001'
    UNION ALL
    SELECT 'CLS-DEMO-48', '48', 'COMPLETED', DATE_SUB(NOW(), INTERVAL 20 MINUTE),
           'Răng 48 mọc lệch ngang 90° sát ống thần kinh răng dưới.', 'XQ-48-001'
) src
JOIN paraclinical_orders po ON po.order_code = src.ocode
JOIN visits v ON v.visit_id = po.visit_id
JOIN services sv ON sv.service_code = 'DV-XQ-QUANH'
WHERE NOT EXISTS (SELECT 1 FROM paraclinical_order_items poi WHERE poi.external_ref_id = src.xref);

INSERT INTO paraclinical_result_attachments (order_item_id, file_name, file_url, file_type, caption, uploaded_at)
SELECT poi.order_item_id, src.fname, src.furl, 'IMAGE', src.cap, NOW()
FROM (
    SELECT 'XQ-16-001' AS xref, 'xray-r16.svg' AS fname, '/images/demo/xray-r16.svg' AS furl, 'Phim chóp R16 (sau điều trị tủy)' AS cap UNION ALL
    SELECT 'XQ-36-001',         'xray-r36.svg',          '/images/demo/xray-r36.svg',          'Phim chóp R36 (thấu quang quanh chóp)'    UNION ALL
    SELECT 'XQ-48-001',         'xray-r48.svg',          '/images/demo/xray-r48.svg',          'Phim R48 mọc lệch 90° sát ống thần kinh'
) src
JOIN paraclinical_order_items poi ON poi.external_ref_id = src.xref
WHERE NOT EXISTS (SELECT 1 FROM paraclinical_result_attachments pra WHERE pra.order_item_id = poi.order_item_id);

INSERT INTO labo_orders (
    labo_code, visit_id, profile_id, branch_id, doctor_id, supplier_id, order_type,
    tooth_positions, material_type, shade_color, quantity, unit_price, cost_to_clinic,
    sent_date, expected_date, received_date, status, notes
)
SELECT src.lcode, v.visit_id, v.profile_id, v.branch_id, v.primary_doctor_id, s.supplier_id, 'NEW',
       src.teeth, src.mat, 'Vita A2', 1, 1500000.00, 1500000.00,
       DATE_SUB(CURDATE(), INTERVAL 2 DAY), DATE_ADD(CURDATE(), INTERVAL src.due_days DAY), src.rec_date, src.lstatus, src.lnotes
FROM (
    SELECT 'LAB-2026-016' AS lcode, 'VIS-DEMO-1601' AS vcode, '16' AS teeth, 'Mão toàn sứ Zirconia' AS mat,
           0 AS due_days, CURDATE() AS rec_date, 'DELIVERED' AS lstatus, 'Mẫu thử mão toàn sứ Zirconia R16 đã về phòng khám, sẵn sàng Thử mão.' AS lnotes
    UNION ALL
    SELECT 'LAB-2026-036', 'VIS-DEMO-3602', '36', 'Mão toàn sứ Zirconia',
           5, NULL, 'SENT', 'Đang đặt chế tác tại Xưởng Labo Hải Đăng cho ca Combo Tủy & Sứ R36.'
) src
JOIN visits v ON v.visit_code = src.vcode
JOIN suppliers s ON s.supplier_code = 'NCC-LABO-HD'
WHERE NOT EXISTS (SELECT 1 FROM labo_orders lo WHERE lo.labo_code = src.lcode);

-- ----------------------------------------------------------------------------------------------------
-- 6. KE HOACH DIEU TRI CHINH NHA 4 BUOC CHO CA 4 (VIS-DEMO-1404) DE TEST DONG BO 1-1 & TINH TIEN
-- ----------------------------------------------------------------------------------------------------
INSERT INTO treatment_plans (
    plan_code, profile_id, branch_id, doctor_id, plan_name,
    total_estimated_cost, final_estimated_cost, status, start_date, expected_end_date,
    notes, sent_to_accounting, is_installment, total_paid_amount, created_at
)
SELECT 'KH-DEMO-1404', pp.profile_id, b.branch_id, doc.staff_id,
       'Kế hoạch điều trị: Niềng răng Chỉnh nha mắc cài toàn hàm (4 bước)',
       32000000.00, 32000000.00, 'ACCEPTED', CURDATE(), DATE_ADD(CURDATE(), INTERVAL 30 DAY),
       'Mỗi bước trong giai đoạn đồng bộ 1-1 với Bàn quy trình bước & Thu ngân.', 0, 0, 0.00, NOW()
FROM patient_profiles pp
JOIN branches b ON b.branch_code = 'CN01'
JOIN staff_profiles doc ON doc.staff_code = 'BS-DEMO-01'
WHERE pp.patient_code = 'BN-2026-014'
  AND NOT EXISTS (SELECT 1 FROM treatment_plans tp WHERE tp.plan_code = 'KH-DEMO-1404');

INSERT INTO treatment_plan_stages (plan_id, stage_order, stage_name, description, status, expected_end_date)
SELECT tp.plan_id, 1, 'Quy trình Niềng răng Chỉnh nha mắc cài', 'Gồm 4 bước lâm sàng chuẩn BYT', 'PENDING', DATE_ADD(CURDATE(), INTERVAL 30 DAY)
FROM treatment_plans tp
WHERE tp.plan_code = 'KH-DEMO-1404'
  AND NOT EXISTS (SELECT 1 FROM treatment_plan_stages tps WHERE tps.plan_id = tp.plan_id AND tps.stage_order = 1);

INSERT INTO treatment_plan_items (stage_id, plan_id, service_id, step_name, tooth_numbers, quantity, estimated_price, status)
SELECT st.stage_id, tp.plan_id, sv.service_id, src.sname, 'Toàn hàm', 1.00, 8000000.00, 'PLANNED'
FROM (
    SELECT 'Bước 1: Khám khớp cắn, lấy dấu mẫu hàm & Chụp ảnh chỉnh nha' AS sname, 1 AS ord UNION ALL
    SELECT 'Bước 2: Đọc phim Panorama / Cephalo chỉnh nha',                          2        UNION ALL
    SELECT 'Bước 3: Gắn mắc cài & Đi dây cung khởi tạo',                           3        UNION ALL
    SELECT 'Bước 4: Tái khám điều chỉnh lực & Hoàn tất tháo mắc cài',               4
) src
JOIN treatment_plans tp ON tp.plan_code = 'KH-DEMO-1404'
JOIN treatment_plan_stages st ON st.plan_id = tp.plan_id AND st.stage_order = 1
JOIN services sv ON sv.service_code = 'DV-NIENG-KL'
WHERE NOT EXISTS (
    SELECT 1 FROM treatment_plan_items tpi WHERE tpi.stage_id = st.stage_id
);

-- ----------------------------------------------------------------------------------------------------
-- 7. KHOI TAO QUY TRINH BUOC DANG CHAY SAN CHO 3 CA DEMO (CA 1 R16, CA 2 COMBO R36, CA 3 R48)
-- ----------------------------------------------------------------------------------------------------
-- Ca 1: Phuc hinh mao su Zirconia R16 (Buoc 1 & 2 da xong, Buoc 3 Thu mao dang thuc hien)
INSERT INTO workflow_executions (version_id, visit_id, profile_id, branch_id, current_step_id, status, started_at)
SELECT ver.version_id, v.visit_id, v.profile_id, v.branch_id, st3.step_id, 'IN_PROGRESS', DATE_SUB(NOW(), INTERVAL 45 MINUTE)
FROM visits v
JOIN clinical_workflow_templates tpl ON tpl.template_code = 'QT-PHUCHINH'
JOIN clinical_workflow_versions ver ON ver.template_id = tpl.template_id AND ver.version_number = 1
JOIN clinical_workflow_steps st3 ON st3.version_id = ver.version_id AND st3.step_code = 'PH-B3'
WHERE v.visit_code = 'VIS-DEMO-1601'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_executions we WHERE we.visit_id = v.visit_id AND we.version_id = ver.version_id
  );

-- Ca 2: Combo Dieu tri tuy & Boc mao su R36 (2 quy trinh tren cung luot kham)
INSERT INTO workflow_executions (version_id, visit_id, profile_id, branch_id, current_step_id, status, started_at)
SELECT ver.version_id, v.visit_id, v.profile_id, v.branch_id, st3.step_id, 'IN_PROGRESS', DATE_SUB(NOW(), INTERVAL 35 MINUTE)
FROM visits v
JOIN clinical_workflow_templates tpl ON tpl.template_code = 'QTKT-TUY-RH'
JOIN clinical_workflow_versions ver ON ver.template_id = tpl.template_id AND ver.version_number = 1
JOIN clinical_workflow_steps st3 ON st3.version_id = ver.version_id AND st3.sequence_order = 3 AND st3.adhoc_execution_id IS NULL
WHERE v.visit_code = 'VIS-DEMO-3602'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_executions we WHERE we.visit_id = v.visit_id AND we.version_id = ver.version_id
  );

INSERT INTO workflow_executions (version_id, visit_id, profile_id, branch_id, current_step_id, status, started_at)
SELECT ver.version_id, v.visit_id, v.profile_id, v.branch_id, st1.step_id, 'IN_PROGRESS', DATE_SUB(NOW(), INTERVAL 25 MINUTE)
FROM visits v
JOIN clinical_workflow_templates tpl ON tpl.template_code = 'QT-PHUCHINH'
JOIN clinical_workflow_versions ver ON ver.template_id = tpl.template_id AND ver.version_number = 1
JOIN clinical_workflow_steps st1 ON st1.version_id = ver.version_id AND st1.step_code = 'PH-B1'
WHERE v.visit_code = 'VIS-DEMO-3602'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_executions we WHERE we.visit_id = v.visit_id AND we.version_id = ver.version_id
  );

-- Ca 3: Tieu phau nho rang khon R48 (Buoc 1 da xong, Buoc 2 dang thuc hien)
INSERT INTO workflow_executions (version_id, visit_id, profile_id, branch_id, current_step_id, status, started_at)
SELECT ver.version_id, v.visit_id, v.profile_id, v.branch_id, st2.step_id, 'IN_PROGRESS', DATE_SUB(NOW(), INTERVAL 20 MINUTE)
FROM visits v
JOIN clinical_workflow_templates tpl ON tpl.template_code = 'QT-NHO-RKHON'
JOIN clinical_workflow_versions ver ON ver.template_id = tpl.template_id AND ver.version_number = 1
JOIN clinical_workflow_steps st2 ON st2.version_id = ver.version_id AND st2.sequence_order = 2 AND st2.adhoc_execution_id IS NULL
WHERE v.visit_code = 'VIS-DEMO-4803'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_executions we WHERE we.visit_id = v.visit_id AND we.version_id = ver.version_id
  );

-- Tao tat ca cac dong workflow_step_executions cho ca 4 quy trinh tren + dien du lieu lam sang giong demo
INSERT IGNORE INTO workflow_step_executions (
    execution_id, step_id, step_order, status, assigned_staff_id, started_at, completed_at, result_note, result_data
)
SELECT we.execution_id,
       cws.step_id,
       cws.sequence_order,
       CASE
           WHEN tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-1601' THEN
               CASE cws.sequence_order WHEN 1 THEN 'COMPLETED' WHEN 2 THEN 'COMPLETED' WHEN 3 THEN 'IN_PROGRESS' ELSE 'PENDING' END
           WHEN tpl.template_code = 'QTKT-TUY-RH' AND v.visit_code = 'VIS-DEMO-3602' THEN
               CASE WHEN cws.sequence_order IN (1, 2) THEN 'COMPLETED' WHEN cws.sequence_order = 3 THEN 'IN_PROGRESS' ELSE 'PENDING' END
           WHEN tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-3602' THEN
               CASE cws.sequence_order WHEN 1 THEN 'IN_PROGRESS' ELSE 'PENDING' END
           WHEN tpl.template_code = 'QT-NHO-RKHON' AND v.visit_code = 'VIS-DEMO-4803' THEN
               CASE cws.sequence_order WHEN 1 THEN 'COMPLETED' ELSE 'IN_PROGRESS' END
           ELSE 'PENDING'
       END,
       v.primary_doctor_id,
       DATE_SUB(NOW(), INTERVAL (45 - cws.sequence_order * 5) MINUTE),
       CASE
           WHEN (tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-1601' AND cws.sequence_order IN (1, 2))
             OR (tpl.template_code = 'QTKT-TUY-RH' AND v.visit_code = 'VIS-DEMO-3602' AND cws.sequence_order IN (1, 2))
             OR (tpl.template_code = 'QT-NHO-RKHON' AND v.visit_code = 'VIS-DEMO-4803' AND cws.sequence_order = 1)
           THEN DATE_SUB(NOW(), INTERVAL (35 - cws.sequence_order * 5) MINUTE)
           ELSE NULL
       END,
       CASE
           WHEN tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-1601' AND cws.sequence_order = 1
               THEN 'Cùi răng 16 hoàn thiện bờ vai rõ, nướu xung quanh hồng hào, gõ dọc không đau.'
           WHEN tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-1601' AND cws.sequence_order = 2
               THEN '3 ống tủy răng 16 trám bít đặc, chiều dài chuẩn, vùng quanh chóp sạch không có tiêu xương.'
           WHEN tpl.template_code = 'QTKT-TUY-RH' AND v.visit_code = 'VIS-DEMO-3602' AND cws.sequence_order = 1
               THEN 'Phim XQ chóp R36 thấy sâu sát buồng tủy và thấu quang nhẹ chân gần. Đã gây tê & cách ly đê.'
           WHEN tpl.template_code = 'QTKT-TUY-RH' AND v.visit_code = 'VIS-DEMO-3602' AND cws.sequence_order = 2
               THEN 'Đã mở tủy buồng, xác định đủ 3 lỗ ống tủy bằng thám châm DG-16.'
           WHEN tpl.template_code = 'QTKT-TUY-RH' AND v.visit_code = 'VIS-DEMO-3602' AND cws.sequence_order = 3
               THEN 'Sửa soạn 3 ống tủy bằng trâm máy, đặt thuốc Ca(OH)2.'
           WHEN tpl.template_code = 'QT-NHO-RKHON' AND v.visit_code = 'VIS-DEMO-4803' AND cws.sequence_order = 1
               THEN 'Phim X-quang XQ-48-001 cho thấy răng 48 mọc lệch ngang 90° sát ống thần kinh.'
           WHEN tpl.template_code = 'QT-NHO-RKHON' AND v.visit_code = 'VIS-DEMO-4803' AND cws.sequence_order = 2
               THEN 'Gây tê vùng, chia cắt thân chân răng lấy trọn vẹn R48, khâu 2 mũi.'
           ELSE NULL
       END,
       CASE
           WHEN tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-1601' AND cws.sequence_order = 1
               THEN JSON_OBJECT('teeth', JSON_ARRAY('16'), 'reason', 'Gắn mão sứ bảo vệ răng 16 sau điều trị nội nha', 'examNotes', 'Cùi răng 16 hoàn thiện bờ vai rõ, nướu xung quanh hồng hào, gõ dọc không đau.')
           WHEN tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-1601' AND cws.sequence_order = 2
               THEN JSON_OBJECT('teeth', JSON_ARRAY('16'), 'xrayNotes', '3 ống tủy răng 16 trám bít đặc, chiều dài chuẩn, vùng quanh chóp sạch không có tiêu xương.')
           WHEN tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-1601' AND cws.sequence_order = 3
               THEN JSON_OBJECT(
                   'teeth', JSON_ARRAY('16'),
                   'laboId', (SELECT labo_id FROM labo_orders WHERE labo_code = 'LAB-2026-016' LIMIT 1),
                   'laboOrderCode', 'LAB-2026-016',
                   'prostheticNotes', '',
                   'custom', JSON_OBJECT()
               )
           WHEN tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-1601' AND cws.sequence_order = 4
               THEN JSON_OBJECT('teeth', JSON_ARRAY('16'), 'materialUsed', 'Xi măng gắn tự dán', 'procedureNotes', '')
           WHEN tpl.template_code = 'QTKT-TUY-RH' AND v.visit_code = 'VIS-DEMO-3602' AND cws.sequence_order = 1
               THEN JSON_OBJECT('teeth', JSON_ARRAY('36'), 'xrayNotes', 'Phim XQ chóp R36 (XQ-36-001) thấy sâu sát buồng tủy và vùng thấu quang nhẹ chân gần.')
           WHEN tpl.template_code = 'QTKT-TUY-RH' AND v.visit_code = 'VIS-DEMO-3602' AND cws.sequence_order = 2
               THEN JSON_OBJECT('teeth', JSON_ARRAY('36'), 'canalCount', '3', 'workingLength', 'MB 19.5mm · ML 19.0mm · D 20.5mm', 'medication', 'NaOCl 2.5%', 'endoNotes', 'Đã mở tủy buồng, xác định đủ 3 lỗ ống tủy bằng thám châm DG-16.')
           WHEN tpl.template_code = 'QTKT-TUY-RH' AND v.visit_code = 'VIS-DEMO-3602' AND cws.sequence_order = 3
               THEN JSON_OBJECT('teeth', JSON_ARRAY('36'), 'canalCount', '3', 'workingLength', 'MB 19.5mm · ML 19.0mm · D 20.5mm', 'medication', 'NaOCl 2.5%, Ca(OH)2', 'endoNotes', 'Sửa soạn 3 ống tủy bằng trâm máy, đặt thuốc Ca(OH)2. Hẹn buổi sau kiểm tra ống tủy khô sạch để trám bít Gutta-percha.')
           WHEN tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-3602' AND cws.sequence_order = 1
               THEN JSON_OBJECT('teeth', JSON_ARRAY('36'), 'reason', 'Phục hình mão toàn sứ sau điều trị tủy R36', 'examNotes', '')
           WHEN tpl.template_code = 'QT-PHUCHINH' AND v.visit_code = 'VIS-DEMO-3602' AND cws.sequence_order = 3
               THEN JSON_OBJECT('teeth', JSON_ARRAY('36'), 'laboId', (SELECT labo_id FROM labo_orders WHERE labo_code = 'LAB-2026-036' LIMIT 1), 'laboOrderCode', 'LAB-2026-036', 'custom', JSON_OBJECT())
           WHEN tpl.template_code = 'QT-NHO-RKHON' AND v.visit_code = 'VIS-DEMO-4803' AND cws.sequence_order = 1
               THEN JSON_OBJECT('teeth', JSON_ARRAY('48'), 'xrayNotes', 'Răng 48 mọc lệch ngang 90° sát ống thần kinh răng dưới (XQ-48-001).')
           WHEN tpl.template_code = 'QT-NHO-RKHON' AND v.visit_code = 'VIS-DEMO-4803' AND cws.sequence_order = 2
               THEN JSON_OBJECT('teeth', JSON_ARRAY('48'), 'anesthesia', 'Gây tê gai Spix Lidocain 2%', 'materialUsed', 'Chỉ khâu 4.0, Bơm rửa Betadine', 'procedureNotes', 'Gây tê vùng, chia cắt thân chân răng lấy trọn vẹn R48, khâu 2 mũi.')
           ELSE JSON_OBJECT('teeth', JSON_ARRAY(CASE v.visit_code WHEN 'VIS-DEMO-1601' THEN '16' WHEN 'VIS-DEMO-3602' THEN '36' ELSE '48' END))
       END
FROM workflow_executions we
JOIN visits v ON v.visit_id = we.visit_id AND v.visit_code IN ('VIS-DEMO-1601', 'VIS-DEMO-3602', 'VIS-DEMO-4803')
JOIN clinical_workflow_versions ver ON ver.version_id = we.version_id
JOIN clinical_workflow_templates tpl ON tpl.template_id = ver.template_id
JOIN clinical_workflow_steps cws ON cws.version_id = ver.version_id AND cws.adhoc_execution_id IS NULL;
