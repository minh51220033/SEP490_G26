-- Optional demo data. Run after V2, with the project's patient_an account and CN01 branch.
-- Safe to run repeatedly. This does not change any existing account or patient record.
INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address,
     preferred_branch_id, is_default, medical_history_completed, deposit_balance, status)
SELECT u.user_id, 'BNPH02000', 'Nguyễn Văn An', '1995-05-20', 'Nam',
       u.phone_number, 'Quận 1, TP.HCM', b.branch_id, 1, 0, 0, 'ACTIVE'
FROM users u JOIN branches b ON b.branch_code = 'CN01'
WHERE u.username = 'patient_an'
  AND NOT EXISTS (SELECT 1 FROM patient_profiles p WHERE p.patient_code = 'BNPH02000');

INSERT INTO account_patient_profiles (account_id, profile_id, access_type, created_at)
SELECT p.user_id, p.profile_id, 'SELF', NOW()
FROM patient_profiles p
WHERE p.patient_code = 'BNPH02000' AND p.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM account_patient_profiles a WHERE a.profile_id = p.profile_id);

INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address,
     preferred_branch_id, is_default, medical_history_completed, deposit_balance, status)
SELECT u.user_id, 'BNPH02001', 'Nguyễn Minh Bé', '2015-06-12', 'Nữ',
       u.phone_number, 'Quận 1, TP.HCM', b.branch_id, 0, 0, 0, 'ACTIVE'
FROM users u JOIN branches b ON b.branch_code = 'CN01'
WHERE u.username = 'patient_an'
  AND NOT EXISTS (SELECT 1 FROM patient_profiles p WHERE p.patient_code = 'BNPH02001');

INSERT INTO account_patient_profiles (account_id, profile_id, access_type, created_at)
SELECT p.user_id, p.profile_id, 'DEPENDENT'
       , NOW()
FROM patient_profiles p
WHERE p.patient_code = 'BNPH02001' AND p.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM account_patient_profiles a WHERE a.profile_id = p.profile_id);

INSERT INTO visits
    (visit_code, profile_id, branch_id, visit_type, check_in_at,
     primary_doctor_id, status, created_at)
SELECT 'VISPH02001', p.profile_id, b.branch_id, 'APPOINTMENT',
       DATE_SUB(NOW(), INTERVAL 7 DAY), sp.staff_id, 'COMPLETED', NOW()
FROM patient_profiles p JOIN branches b ON b.branch_code = 'CN01'
JOIN staff_profiles sp JOIN users doctor ON doctor.user_id = sp.user_id
WHERE p.patient_code = 'BNPH02001' AND doctor.username = 'bacsi_nam'
  AND NOT EXISTS (SELECT 1 FROM visits v WHERE v.visit_code = 'VISPH02001');

INSERT INTO appointments
    (appointment_code, profile_id, branch_id, doctor_id, appointment_date,
     expected_time, preferred_session, source, status, reschedule_count, created_at)
SELECT 'LHPH02001', p.profile_id, b.branch_id, sp.staff_id,
       DATE_ADD(CURDATE(), INTERVAL 14 DAY), '09:00:00', 'MORNING',
       'RECEPTIONIST', 'CONFIRMED', 0, NOW()
FROM patient_profiles p JOIN branches b ON b.branch_code = 'CN01'
JOIN staff_profiles sp JOIN users doctor ON doctor.user_id = sp.user_id
WHERE p.patient_code = 'BNPH02001' AND doctor.username = 'bacsi_nam'
  AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.appointment_code = 'LHPH02001');

-- Lịch tái khám có dịch vụ để kiểm tra màn hình chi tiết lượt khám của Lễ tân.
INSERT INTO appointments
    (appointment_code, profile_id, branch_id, doctor_id, service_id, appointment_date,
     expected_time, preferred_session, source, status, reschedule_count, created_at)
SELECT 'LHPH02002', p.profile_id, b.branch_id, sp.staff_id,
       (SELECT MIN(service_id) FROM services WHERE status = 'ACTIVE'),
       DATE_ADD(CURDATE(), INTERVAL 21 DAY), '14:30:00', 'AFTERNOON',
       'RECEPTIONIST', 'CONFIRMED', 0, NOW()
FROM patient_profiles p JOIN branches b ON b.branch_code = 'CN01'
JOIN staff_profiles sp JOIN users doctor ON doctor.user_id = sp.user_id
WHERE p.patient_code = 'BNPH02001' AND doctor.username = 'bacsi_nam'
  AND EXISTS (SELECT 1 FROM services WHERE status = 'ACTIVE')
  AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.appointment_code = 'LHPH02002');

INSERT INTO performed_services
    (visit_id, profile_id, branch_id, service_id, doctor_id, quantity,
     unit_price, discount_amount, final_price, completed_at, status)
SELECT v.visit_id, p.profile_id, v.branch_id, s.service_id, sp.staff_id,
       1, 0, 0, 0, v.check_in_at, 'COMPLETED'
FROM visits v JOIN patient_profiles p ON p.profile_id = v.profile_id
JOIN services s ON s.service_id = (SELECT MIN(service_id) FROM services)
JOIN staff_profiles sp JOIN users doctor ON doctor.user_id = sp.user_id
WHERE v.visit_code = 'VISPH02001' AND doctor.username = 'bacsi_nam'
  AND NOT EXISTS (SELECT 1 FROM performed_services ps WHERE ps.visit_id = v.visit_id);
