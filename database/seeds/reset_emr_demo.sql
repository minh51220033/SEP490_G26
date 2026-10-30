-- Xóa DUY NHẤT dữ liệu demo EMR có tiền tố EMR-DEMO-, VISEMR, BNEMR và BNFLOW.
-- Dùng trước khi muốn trở về một kịch bản demo sạch; không xóa dữ liệu nghiệp vụ khác.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
START TRANSACTION;

DELETE s
FROM emr_signatures s
JOIN emr_record_versions rv ON rv.version_id = s.version_id
JOIN emr_records r ON r.record_id = rv.record_id
JOIN patient_profiles p ON p.profile_id = r.profile_id
WHERE r.record_code LIKE 'EMR-DEMO-%' OR p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';

DELETE d
FROM emr_documents d
JOIN emr_record_versions rv ON rv.version_id = d.version_id
JOIN emr_records r ON r.record_id = rv.record_id
JOIN patient_profiles p ON p.profile_id = r.profile_id
WHERE r.record_code LIKE 'EMR-DEMO-%' OR p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';

DELETE ss
FROM emr_source_snapshots ss
JOIN emr_record_versions rv ON rv.version_id = ss.version_id
JOIN emr_records r ON r.record_id = rv.record_id
JOIN patient_profiles p ON p.profile_id = r.profile_id
WHERE r.record_code LIKE 'EMR-DEMO-%' OR p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';

DELETE a
FROM emr_audit_events a
JOIN emr_records r ON r.record_id = a.record_id
JOIN patient_profiles p ON p.profile_id = r.profile_id
WHERE r.record_code LIKE 'EMR-DEMO-%' OR p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';

DELETE rv
FROM emr_record_versions rv
JOIN emr_records r ON r.record_id = rv.record_id
JOIN patient_profiles p ON p.profile_id = r.profile_id
WHERE r.record_code LIKE 'EMR-DEMO-%' OR p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';

DELETE r
FROM emr_records r
JOIN patient_profiles p ON p.profile_id = r.profile_id
WHERE r.record_code LIKE 'EMR-DEMO-%' OR p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';

DELETE pi
FROM prescription_items pi
JOIN prescriptions p ON p.prescription_id = pi.prescription_id
WHERE p.prescription_code LIKE 'RXEMR%';

DELETE FROM prescriptions WHERE prescription_code LIKE 'RXEMR%';
DELETE ps FROM performed_services ps JOIN visits v ON v.visit_id = ps.visit_id
JOIN patient_profiles p ON p.profile_id = v.profile_id
WHERE p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';
DELETE d FROM examination_diagnoses d JOIN visits v ON v.visit_id = d.visit_id
JOIN patient_profiles p ON p.profile_id = v.profile_id
WHERE p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';
DELETE ce FROM clinical_examinations ce JOIN visits v ON v.visit_id = ce.visit_id
JOIN patient_profiles p ON p.profile_id = v.profile_id
WHERE p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';

DELETE qt FROM queue_tickets qt JOIN visits v ON v.visit_id = qt.visit_id
JOIN patient_profiles p ON p.profile_id = v.profile_id
WHERE p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';
DELETE ck FROM reception_checkin_keys ck JOIN visits v ON v.visit_id = ck.visit_id
JOIN patient_profiles p ON p.profile_id = v.profile_id
WHERE p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';

DELETE tpi
FROM treatment_plan_items tpi
JOIN treatment_plans tp ON tp.plan_id = tpi.plan_id
WHERE tp.plan_code LIKE 'KH-EMR%';
DELETE tps
FROM treatment_plan_stages tps
JOIN treatment_plans tp ON tp.plan_id = tps.plan_id
WHERE tp.plan_code LIKE 'KH-EMR%';
DELETE FROM treatment_plans WHERE plan_code LIKE 'KH-EMR%';

DELETE v FROM visits v JOIN patient_profiles p ON p.profile_id = v.profile_id
WHERE p.patient_code LIKE 'BNEMR%' OR p.patient_code = 'BNFLOW1001';
DELETE FROM appointments WHERE appointment_code = 'APPTEMR1001';
DELETE app FROM account_patient_profiles app JOIN patient_profiles p ON p.profile_id = app.profile_id
WHERE p.patient_code = 'BNFLOW1001';
DELETE FROM patient_profiles WHERE patient_code LIKE 'BNEMR%' OR patient_code = 'BNFLOW1001';

COMMIT;
