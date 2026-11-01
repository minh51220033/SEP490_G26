-- Chuyển các bản đã khóa đang chờ người bệnh sang chờ bác sĩ ký thử.
-- Không thay đổi hồ sơ đã SIGNED hoặc lịch sử chữ ký đã ghi.
UPDATE emr_records
SET status = 'DOCTOR_SIGNATURE_PENDING'
WHERE status = 'PATIENT_SIGNATURE_PENDING';

-- Dọn riêng chữ ký bệnh nhân của hồ sơ seed cũ; giữ nguyên dữ liệu thật.
DELETE s FROM emr_signatures s
JOIN emr_record_versions v ON v.version_id = s.version_id
JOIN emr_records r ON r.record_id = v.record_id
WHERE r.record_code = 'EMR-DEMO-SIGNED'
  AND s.signer_type = 'PATIENT'
  AND s.provider = 'LOCAL_TEST_ONLY'
  AND s.external_transaction_id = 'seed-patient-emr1003';
