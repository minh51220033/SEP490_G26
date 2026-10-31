-- Dữ liệu kiểm thử module EMR.
-- Điều kiện: chạy test_data.sql trước để có VIS10001 và VIS10002 cùng dữ liệu khám nguồn.
-- Có thể chạy lại nhiều lần; các mã EMR-DEMO-* và quan hệ visit là duy nhất.

-- ============================================================
-- CASE 1: EMR-DEMO-DRAFT - bệnh án nháp, dùng để test refresh/validate/lock.
-- ============================================================
INSERT INTO emr_records
    (record_code, visit_id, profile_id, branch_id, status, current_version_no, created_by,
     created_at, updated_at, row_version)
SELECT 'EMR-DEMO-DRAFT', v.visit_id, v.profile_id, v.branch_id, 'DRAFT', 1, sp.user_id,
       NOW(), NOW(), 0
FROM visits v
LEFT JOIN staff_profiles sp ON sp.staff_id = v.primary_doctor_id
WHERE v.visit_code = 'VIS10001'
  AND NOT EXISTS (SELECT 1 FROM emr_records r WHERE r.visit_id = v.visit_id);

SET @draft_record_id = (SELECT record_id FROM emr_records WHERE record_code = 'EMR-DEMO-DRAFT' LIMIT 1);
SET @draft_creator_id = (SELECT created_by FROM emr_records WHERE record_id = @draft_record_id);
SET @draft_source = JSON_OBJECT(
    'patient', JSON_OBJECT('patientCode','BN000101','fullName','Nguyễn Văn An','dob','1995-05-20','gender','Nam','phoneNumber','0987654321','address','123 Trần Hưng Đạo, Quận 5, TP.HCM'),
    'visit', JSON_OBJECT('visitCode','VIS10001','initialSymptoms','Đau buốt răng hàm dưới bên phải khi ăn nhai và uống nước đá','status','IN_EXAM','branchCode','CN01','branchName','Cơ sở trung tâm','doctorName','Bác sĩ dữ liệu mẫu'),
    'clinical', JSON_OBJECT('chiefComplaint','Đau buốt răng hàm khi ăn đồ nóng lạnh','historyIllness','Khởi phát 3 ngày trước, đau nhói từng cơn','summaryAssessment','Theo dõi tổn thương sâu ngà và tủy răng'),
    'diagnoses', JSON_ARRAY(JSON_OBJECT('type','MAIN','icd10Code','K02.1','diagnosisText','Sâu ngà răng','toothNumbers','18, 28')),
    'performedServices', JSON_ARRAY(JSON_OBJECT('serviceName','Phục hình răng','toothNumbers','16','doctorName','Bác sĩ dữ liệu mẫu','clinicalNotes','Cắn khớp tốt')),
    'prescriptions', JSON_ARRAY(JSON_OBJECT('prescriptionCode','TOA10001','diagnosisText','K02.1 - Sâu ngà răng','adviceNotes','Vệ sinh răng miệng và tái khám sau 7 ngày','items',JSON_ARRAY())),
    'photos', JSON_ARRAY()
);

INSERT INTO emr_record_versions
    (record_id, version_no, status, template_code, template_version, created_by, created_at)
SELECT @draft_record_id, 1, 'DRAFT', 'OUTPATIENT_DENTAL', 1, @draft_creator_id, NOW()
WHERE @draft_record_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_record_versions WHERE record_id = @draft_record_id AND version_no = 1);

SET @draft_version_id = (SELECT version_id FROM emr_record_versions WHERE record_id = @draft_record_id AND version_no = 1 LIMIT 1);
INSERT INTO emr_source_snapshots (version_id, source_json, source_hash, captured_at)
SELECT @draft_version_id, @draft_source, SHA2(CAST(@draft_source AS CHAR), 256), NOW()
WHERE @draft_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_source_snapshots WHERE version_id = @draft_version_id);

INSERT INTO emr_documents (version_id, document_code, title, document_type, template_version, sort_order, content_json, document_hash)
SELECT @draft_version_id, d.code, d.title, d.doc_type, 1, d.sort_order, @draft_source,
       SHA2(CONCAT(d.code, '|', CAST(@draft_source AS CHAR)), 256)
FROM (
    SELECT 'COVER' code, 'Bìa hồ sơ bệnh án' title, 'COVER' doc_type, 10 sort_order
    UNION ALL SELECT 'OUTPATIENT_DENTAL', 'Bệnh án ngoại trú Răng Hàm Mặt', 'MEDICAL_RECORD', 20
    UNION ALL SELECT 'TREATMENT_SUMMARY', 'Tổng kết quá trình khám và điều trị', 'SUMMARY', 30
    UNION ALL SELECT 'ATTACHMENT_INDEX', 'Danh mục tài liệu đính kèm', 'ATTACHMENT_INDEX', 40
    UNION ALL SELECT 'VERIFICATION', 'Thông tin xác thực hồ sơ', 'VERIFICATION', 50
) d
WHERE @draft_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_documents e WHERE e.version_id = @draft_version_id AND e.document_code = d.code);

INSERT INTO emr_audit_events (record_id, version_id, actor_user_id, action, details, created_at)
SELECT @draft_record_id, @draft_version_id, @draft_creator_id, 'SEED_CREATE_DRAFT', 'Kịch bản kiểm thử bệnh án nháp.', NOW()
WHERE @draft_record_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_audit_events WHERE record_id = @draft_record_id AND action = 'SEED_CREATE_DRAFT');

-- ============================================================
-- CASE 2: EMR-DEMO-SIGNED - bệnh án đã ký thử, dùng test amendment.
-- ============================================================
INSERT INTO emr_records
    (record_code, visit_id, profile_id, branch_id, status, current_version_no, created_by,
     created_at, updated_at, row_version)
SELECT 'EMR-DEMO-SIGNED', v.visit_id, v.profile_id, v.branch_id, 'SIGNED', 1, sp.user_id,
       NOW(), NOW(), 0
FROM visits v
LEFT JOIN staff_profiles sp ON sp.staff_id = v.primary_doctor_id
WHERE v.visit_code = 'VIS10002'
  AND NOT EXISTS (SELECT 1 FROM emr_records r WHERE r.visit_id = v.visit_id);

SET @signed_record_id = (SELECT record_id FROM emr_records WHERE record_code = 'EMR-DEMO-SIGNED' LIMIT 1);
SET @signed_creator_id = (SELECT created_by FROM emr_records WHERE record_id = @signed_record_id);
SET @signed_source = JSON_OBJECT(
    'patient', JSON_OBJECT('patientCode','BN000102','fullName','Trần Thị Bình','dob','1998-08-15','gender','Nữ','phoneNumber','0912345678','address','456 Hai Bà Trưng, Quận 3, TP.HCM'),
    'visit', JSON_OBJECT('visitCode','VIS10002','initialSymptoms','Răng khôn hàm dưới mọc lệch gây sưng nề nướu và sốt nhẹ','status','IN_EXAM','branchCode','CN01','branchName','Cơ sở trung tâm','doctorName','Bác sĩ dữ liệu mẫu'),
    'clinical', JSON_OBJECT('chiefComplaint','Sưng đau vùng góc hàm dưới bên trái','historyIllness','Răng khôn mọc lệch gây đau nhức 2 ngày','summaryAssessment','Răng khôn 38 mọc lệch, chỉ định tiểu phẫu'),
    'diagnoses', JSON_ARRAY(JSON_OBJECT('type','MAIN','icd10Code','K01.1','diagnosisText','Răng khôn mọc lệch','toothNumbers','38')),
    'performedServices', JSON_ARRAY(JSON_OBJECT('serviceName','Tiểu phẫu răng khôn','toothNumbers','38','doctorName','Bác sĩ dữ liệu mẫu','clinicalNotes','Khâu 2 mũi chỉ vicryl')),
    'prescriptions', JSON_ARRAY(JSON_OBJECT('prescriptionCode','TOA10002','diagnosisText','K01.1 - Răng khôn 38 mọc lệch','adviceNotes','Tái khám cắt chỉ sau 7 ngày','items',JSON_ARRAY())),
    'photos', JSON_ARRAY()
);
SET @signed_source_hash = SHA2(CAST(@signed_source AS CHAR), 256);
SET @signed_content_hash = SHA2(CONCAT(@signed_source_hash, 'SIGNED-DEMO-DOCUMENTS'), 256);

INSERT INTO emr_record_versions
    (record_id, version_no, status, template_code, template_version, content_hash, created_by,
     created_at, locked_at, signed_at)
SELECT @signed_record_id, 1, 'SIGNED', 'OUTPATIENT_DENTAL', 1, @signed_content_hash,
       @signed_creator_id, DATE_SUB(NOW(), INTERVAL 15 MINUTE),
       DATE_SUB(NOW(), INTERVAL 10 MINUTE), DATE_SUB(NOW(), INTERVAL 5 MINUTE)
WHERE @signed_record_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_record_versions WHERE record_id = @signed_record_id AND version_no = 1);

SET @signed_version_id = (SELECT version_id FROM emr_record_versions WHERE record_id = @signed_record_id AND version_no = 1 LIMIT 1);
INSERT INTO emr_source_snapshots (version_id, source_json, source_hash, captured_at)
SELECT @signed_version_id, @signed_source, @signed_source_hash, DATE_SUB(NOW(), INTERVAL 15 MINUTE)
WHERE @signed_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_source_snapshots WHERE version_id = @signed_version_id);

INSERT INTO emr_documents (version_id, document_code, title, document_type, template_version, sort_order, content_json, document_hash)
SELECT @signed_version_id, d.code, d.title, d.doc_type, 1, d.sort_order, @signed_source,
       SHA2(CONCAT(d.code, '|', CAST(@signed_source AS CHAR)), 256)
FROM (
    SELECT 'COVER' code, 'Bìa hồ sơ bệnh án' title, 'COVER' doc_type, 10 sort_order
    UNION ALL SELECT 'OUTPATIENT_DENTAL', 'Bệnh án ngoại trú Răng Hàm Mặt', 'MEDICAL_RECORD', 20
    UNION ALL SELECT 'TREATMENT_SUMMARY', 'Tổng kết quá trình khám và điều trị', 'SUMMARY', 30
    UNION ALL SELECT 'ATTACHMENT_INDEX', 'Danh mục tài liệu đính kèm', 'ATTACHMENT_INDEX', 40
    UNION ALL SELECT 'VERIFICATION', 'Thông tin xác thực hồ sơ', 'VERIFICATION', 50
) d
WHERE @signed_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_documents e WHERE e.version_id = @signed_version_id AND e.document_code = d.code);

INSERT INTO emr_signatures
    (version_id, signer_type, signer_name, signer_user_id, provider, external_transaction_id,
     status, signature_hash, created_at, signed_at)
SELECT @signed_version_id, 'PATIENT', 'Trần Thị Bình', NULL, 'LOCAL_TEST_ONLY', 'SEED-PATIENT-SIGN', 'SIGNED',
       SHA2(CONCAT(@signed_content_hash, '|PATIENT|Trần Thị Bình'), 256),
       DATE_SUB(NOW(), INTERVAL 8 MINUTE), DATE_SUB(NOW(), INTERVAL 7 MINUTE)
WHERE @signed_version_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_signatures WHERE version_id = @signed_version_id AND signer_type = 'PATIENT');

INSERT INTO emr_signatures
    (version_id, signer_type, signer_name, signer_user_id, provider, external_transaction_id,
     status, signature_hash, created_at, signed_at)
SELECT @signed_version_id, 'DOCTOR', COALESCE(sp.full_name, 'Bác sĩ dữ liệu mẫu'), @signed_creator_id,
       'LOCAL_TEST_ONLY', 'SEED-DOCTOR-SIGN', 'SIGNED',
       SHA2(CONCAT(@signed_content_hash, '|DOCTOR|', COALESCE(sp.full_name, 'Bác sĩ dữ liệu mẫu')), 256),
       DATE_SUB(NOW(), INTERVAL 6 MINUTE), DATE_SUB(NOW(), INTERVAL 5 MINUTE)
FROM emr_records r
LEFT JOIN users u ON u.user_id = @signed_creator_id
LEFT JOIN staff_profiles sp ON sp.user_id = u.user_id
WHERE r.record_id = @signed_record_id
  AND NOT EXISTS (SELECT 1 FROM emr_signatures WHERE version_id = @signed_version_id AND signer_type = 'DOCTOR');

INSERT INTO emr_audit_events (record_id, version_id, actor_user_id, action, details, created_at)
SELECT @signed_record_id, @signed_version_id, @signed_creator_id, 'SEED_SIGNED',
       'Kịch bản kiểm thử bệnh án đã ký và tạo bản sửa đổi.', NOW()
WHERE @signed_record_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emr_audit_events WHERE record_id = @signed_record_id AND action = 'SEED_SIGNED');

-- Kiểm tra cuối seed: phải trả về 2 dòng nếu test_data.sql đã được nạp.
SELECT r.record_code, r.status, r.current_version_no, v.status AS version_status,
       COUNT(DISTINCT d.document_id) AS documents, COUNT(DISTINCT s.signature_id) AS signatures
FROM emr_records r
JOIN emr_record_versions v ON v.record_id = r.record_id AND v.version_no = r.current_version_no
LEFT JOIN emr_documents d ON d.version_id = v.version_id
LEFT JOIN emr_signatures s ON s.version_id = v.version_id
WHERE r.record_code IN ('EMR-DEMO-DRAFT', 'EMR-DEMO-SIGNED')
GROUP BY r.record_code, r.status, r.current_version_no, v.status
ORDER BY r.record_code;
