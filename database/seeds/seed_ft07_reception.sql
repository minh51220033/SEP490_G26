-- Manual demo seed for FT07. Run after the base migrations and V7.
-- Safe to run repeatedly; this seed never updates existing business records.
-- Demo receptionist: reception_ft07 / Hieu1811 (same test-only hash used by data.sql).
SET time_zone = '+07:00';

INSERT INTO roles (role_name, display_name, description, is_system, is_active)
SELECT 'LE_TAN', 'Lễ tân', 'Tiếp đón và check-in', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_name = 'LE_TAN');

INSERT INTO users (username, password, email, phone_number, branch_id,
                   user_type, status, is_active, created_date)
SELECT 'reception_ft07', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe',
       'reception.ft07@dentalclinic.test', '0907700007', b.branch_id,
       'STAFF', 'ACTIVE', 1, NOW()
FROM branches b WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM users WHERE username = 'reception_ft07');

INSERT INTO user_roles (user_id, role_id)
SELECT u.user_id, r.role_id FROM users u JOIN roles r ON r.role_name = 'LE_TAN'
WHERE u.username = 'reception_ft07'
  AND NOT EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.user_id AND ur.role_id = r.role_id);

INSERT INTO staff_profiles (user_id, staff_code, full_name, phone_number, email,
                            department, position_title, status,
                            base_salary, allowance_amount, insurance_salary, created_at)
SELECT u.user_id, 'LTFT07', 'Lễ tân FT07', u.phone_number, u.email,
       'Tiếp đón', 'Lễ tân', 'ACTIVE', 0, 0, 0, NOW()
FROM users u WHERE u.username = 'reception_ft07'
  AND NOT EXISTS (SELECT 1 FROM staff_profiles WHERE staff_code = 'LTFT07');

INSERT INTO staff_branch_assignments
    (staff_id, branch_id, role_id, is_primary, start_date, status, created_at)
SELECT s.staff_id, b.branch_id, r.role_id, 1, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 'ACTIVE', NOW()
FROM staff_profiles s JOIN branches b ON b.branch_code = 'CN01'
JOIN roles r ON r.role_name = 'LE_TAN'
WHERE s.staff_code = 'LTFT07'
  AND NOT EXISTS (SELECT 1 FROM staff_branch_assignments a
                  WHERE a.staff_id = s.staff_id AND a.branch_id = b.branch_id
                    AND a.role_id = r.role_id AND a.status = 'ACTIVE');

-- 01 confirmed appointment; 02 pending; 03/04 same phone for walk-in selection;
-- 05 cancelled; 06 no-show; 07 future; 08 already active; 09 inactive;
-- 10 already checked-in; 11 is at another branch.
INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address,
     preferred_branch_id, is_default, medical_history_completed, deposit_balance,
     has_allergies, allergy_details, has_underlying_disease, underlying_disease_details, status)
SELECT NULL, src.code, src.name, src.dob, src.gender, src.phone, 'Dữ liệu thử FT07',
       b.branch_id, 0, 1, 0, src.allergy, src.allergy_note, src.underlying, src.underlying_note, src.status
FROM (
    SELECT 'BNFT0701' code, 'An Lịch Hẹn' name, '1993-01-10' dob, 'Nam' gender, '0907700101' phone, 'CN01' branch_code, 1 allergy, 'Dị ứng penicillin' allergy_note, 0 underlying, NULL underlying_note, 'ACTIVE' status
    UNION ALL SELECT 'BNFT0702', 'Bình Chờ Xác Nhận', '1989-03-15', 'Nữ', '0907700102', 'CN01', 0, NULL, 1, 'Tăng huyết áp', 'ACTIVE'
    UNION ALL SELECT 'BNFT0703', 'Chi Trùng Tên', '1998-05-20', 'Nữ', '0907700103', 'CN01', 0, NULL, 0, NULL, 'ACTIVE'
    UNION ALL SELECT 'BNFT0704', 'Chi Trùng Tên', '2001-09-11', 'Nữ', '0907700103', 'CN01', 1, 'Dị ứng latex', 0, NULL, 'ACTIVE'
    UNION ALL SELECT 'BNFT0705', 'Dũng Lịch Hủy', '1980-01-06', 'Nam', '0907700105', 'CN01', 0, NULL, 0, NULL, 'ACTIVE'
    UNION ALL SELECT 'BNFT0706', 'Em Lỡ Hẹn', '1996-07-13', 'Nữ', '0907700106', 'CN01', 0, NULL, 0, NULL, 'ACTIVE'
    UNION ALL SELECT 'BNFT0707', 'Giang Hẹn Ngày Mai', '1990-12-22', 'Nữ', '0907700107', 'CN01', 0, NULL, 0, NULL, 'ACTIVE'
    UNION ALL SELECT 'BNFT0708', 'Hải Đã Có Lượt', '1985-02-19', 'Nam', '0907700108', 'CN01', 0, NULL, 0, NULL, 'ACTIVE'
    UNION ALL SELECT 'BNFT0709', 'Khánh Hồ Sơ Khóa', '1986-06-09', 'Nam', '0907700109', 'CN01', 0, NULL, 0, NULL, 'INACTIVE'
    UNION ALL SELECT 'BNFT0710', 'Lan Đã Check-in', '1994-04-24', 'Nữ', '0907700110', 'CN01', 0, NULL, 0, NULL, 'ACTIVE'
    UNION ALL SELECT 'BNFT0711', 'Minh Khác Chi Nhánh', '1992-08-18', 'Nam', '0907700111', 'CN02', 0, NULL, 0, NULL, 'ACTIVE'
) src JOIN branches b ON b.branch_code = src.branch_code
WHERE NOT EXISTS (SELECT 1 FROM patient_profiles p WHERE p.patient_code = src.code);

INSERT INTO appointments
    (appointment_code, profile_id, branch_id, appointment_date, preferred_session,
     expected_time, initial_symptoms, source, status, reschedule_count, created_at)
SELECT src.code, p.profile_id, b.branch_id,
       DATE_ADD(CURDATE(), INTERVAL src.day_offset DAY), 'MORNING', '09:00:00',
       src.symptoms, 'RECEPTIONIST', src.status, 0, NOW()
FROM (
    SELECT 'LHFT0701' code, 'BNFT0701' patient_code, 'CN01' branch_code, 0 day_offset, 'Đau răng hàm dưới' symptoms, 'CONFIRMED' status
    UNION ALL SELECT 'LHFT0702', 'BNFT0702', 'CN01', 0, 'Ê buốt khi uống lạnh', 'PENDING'
    UNION ALL SELECT 'LHFT0705', 'BNFT0705', 'CN01', 0, 'Kiểm tra định kỳ', 'CANCELLED'
    UNION ALL SELECT 'LHFT0706', 'BNFT0706', 'CN01', 0, 'Tư vấn chỉnh nha', 'NO_SHOW'
    UNION ALL SELECT 'LHFT0707', 'BNFT0707', 'CN01', 1, 'Cạo vôi răng', 'CONFIRMED'
    UNION ALL SELECT 'LHFT0708', 'BNFT0708', 'CN01', 0, 'Đau khi nhai', 'CONFIRMED'
    UNION ALL SELECT 'LHFT0710', 'BNFT0710', 'CN01', 0, 'Khám tổng quát', 'CHECKED_IN'
    UNION ALL SELECT 'LHFT0711', 'BNFT0711', 'CN02', 0, 'Kiểm tra răng', 'CONFIRMED'
) src JOIN patient_profiles p ON p.patient_code = src.patient_code
JOIN branches b ON b.branch_code = src.branch_code
WHERE NOT EXISTS (SELECT 1 FROM appointments a WHERE a.appointment_code = src.code);

INSERT INTO visits (visit_code, profile_id, branch_id, appointment_id, visit_type,
                    check_in_at, initial_symptoms, status, created_at)
SELECT 'VISFT0708', p.profile_id, b.branch_id, NULL, 'WALK_IN', NOW(), 'Đang chờ khám từ trước', 'WAITING', NOW()
FROM patient_profiles p JOIN branches b ON b.branch_code = 'CN01'
WHERE p.patient_code = 'BNFT0708'
  AND NOT EXISTS (SELECT 1 FROM visits WHERE visit_code = 'VISFT0708');

INSERT INTO visits (visit_code, profile_id, branch_id, appointment_id, visit_type,
                    check_in_at, initial_symptoms, status, created_at)
SELECT 'VISFT0710', p.profile_id, b.branch_id, a.appointment_id, 'APPOINTMENT', NOW(), 'Khám tổng quát', 'WAITING', NOW()
FROM patient_profiles p JOIN branches b ON b.branch_code = 'CN01'
JOIN appointments a ON a.appointment_code = 'LHFT0710'
WHERE p.patient_code = 'BNFT0710'
  AND NOT EXISTS (SELECT 1 FROM visits WHERE visit_code = 'VISFT0710');
