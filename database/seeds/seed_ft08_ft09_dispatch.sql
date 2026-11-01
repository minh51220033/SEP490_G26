-- Manual, repeatable FT08/FT09 demo data. Run after seed_ft07_reception.sql and V8.
-- All visits use the current day; rerun on a new day with fresh test database for a clean scenario.
-- Login reception_ft07 / Hieu1811. Demo doctors have no login requirement for dispatch.
SET time_zone = '+07:00';

INSERT INTO staff_profiles (staff_code, full_name, department, position_title, status,
                            base_salary, allowance_amount, insurance_salary, created_at)
SELECT 'BSFT0801', 'Bác sĩ FT08 đang làm việc', 'Nha khoa', 'Bác sĩ', 'ACTIVE', 0, 0, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM staff_profiles WHERE staff_code = 'BSFT0801');
INSERT INTO staff_profiles (staff_code, full_name, department, position_title, status,
                            base_salary, allowance_amount, insurance_salary, created_at)
SELECT 'BSFT0802', 'Bác sĩ FT08 ngoài ca', 'Nha khoa', 'Bác sĩ', 'ACTIVE', 0, 0, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM staff_profiles WHERE staff_code = 'BSFT0802');

INSERT INTO staff_branch_assignments
    (staff_id, branch_id, role_id, is_primary, start_date, status, created_at)
SELECT s.staff_id, b.branch_id, r.role_id, 1, DATE_SUB(CURDATE(), INTERVAL 1 DAY), 'ACTIVE', NOW()
FROM staff_profiles s JOIN branches b ON b.branch_code = 'CN01' JOIN roles r ON r.role_name = 'BAC_SI'
WHERE s.staff_code IN ('BSFT0801','BSFT0802')
  AND NOT EXISTS (SELECT 1 FROM staff_branch_assignments a WHERE a.staff_id = s.staff_id
    AND a.branch_id = b.branch_id AND a.role_id = r.role_id AND a.status = 'ACTIVE');

INSERT INTO work_shifts (branch_id, shift_code, shift_name, start_time, end_time, is_active)
SELECT b.branch_id, 'FT08_ALL', 'Ca thử FT08 cả ngày', '00:00:00', '23:59:59', 1
FROM branches b WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM work_shifts s WHERE s.branch_id = b.branch_id AND s.shift_code = 'FT08_ALL');
INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, created_at)
SELECT s.staff_id, b.branch_id, w.shift_id, CURDATE(), 'SCHEDULED', NOW()
FROM staff_profiles s JOIN branches b ON b.branch_code = 'CN01'
JOIN work_shifts w ON w.branch_id = b.branch_id AND w.shift_code = 'FT08_ALL'
WHERE s.staff_code = 'BSFT0801'
  AND NOT EXISTS (SELECT 1 FROM staff_schedules x WHERE x.staff_id = s.staff_id AND x.work_date = CURDATE() AND x.shift_id = w.shift_id);

INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT b.branch_id, 'FT08_R1', 'Phòng khám FT08', 'TREATMENT', 'ACTIVE'
FROM branches b WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM rooms r WHERE r.branch_id = b.branch_id AND r.room_code = 'FT08_R1');
INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT b.branch_id, 'FT08_R2', 'Phòng bảo trì FT08', 'TREATMENT', 'MAINTENANCE'
FROM branches b WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM rooms r WHERE r.branch_id = b.branch_id AND r.room_code = 'FT08_R2');
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT b.branch_id, r.room_id, 'FT08_C1', 'Ghế FT08 khả dụng', 'AVAILABLE'
FROM branches b JOIN rooms r ON r.branch_id = b.branch_id AND r.room_code = 'FT08_R1'
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM dental_chairs c WHERE c.branch_id = b.branch_id AND c.chair_code = 'FT08_C1');
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT b.branch_id, r.room_id, 'FT08_C2', 'Ghế FT08 bảo trì', 'MAINTENANCE'
FROM branches b JOIN rooms r ON r.branch_id = b.branch_id AND r.room_code = 'FT08_R1'
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM dental_chairs c WHERE c.branch_id = b.branch_id AND c.chair_code = 'FT08_C2');

INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address,
     preferred_branch_id, is_default, medical_history_completed, deposit_balance, status)
SELECT NULL, src.code, src.name, '1990-01-01', 'Nam', src.phone, 'Thử FT08/09',
       b.branch_id, 0, 1, 0, 'ACTIVE'
FROM (
 SELECT 'BNFT0801' code, 'Thử phân công mới' name, '0907800001' phone
 UNION ALL SELECT 'BNFT0802', 'Thử số thường', '0907800002'
 UNION ALL SELECT 'BNFT0803', 'Thử số cấp cứu', '0907800003'
 UNION ALL SELECT 'BNFT0804', 'Thử số ưu tiên', '0907800004'
) src JOIN branches b ON b.branch_code = 'CN01'
WHERE NOT EXISTS (SELECT 1 FROM patient_profiles p WHERE p.patient_code = src.code);

INSERT INTO visits (visit_code, profile_id, branch_id, visit_type, check_in_at,
                    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at)
SELECT src.code, p.profile_id, b.branch_id, src.type, NOW(), src.symptoms,
       CASE WHEN src.assigned = 1 THEN s.staff_id ELSE NULL END,
       CASE WHEN src.assigned = 1 THEN r.room_id ELSE NULL END, NULL, 'WAITING', NOW()
FROM (
 SELECT 'VISFT0801' code, 'BNFT0801' patient, 'WALK_IN' type, 'Đau răng cần phân bác sĩ' symptoms, 0 assigned
 UNION ALL SELECT 'VISFT0802', 'BNFT0802', 'WALK_IN', 'Đã phân công và cấp số thường', 1
 UNION ALL SELECT 'VISFT0803', 'BNFT0803', 'EMERGENCY', 'Cấp cứu chảy máu răng kéo dài', 1
 UNION ALL SELECT 'VISFT0804', 'BNFT0804', 'WALK_IN', 'Cần ưu tiên vì hạn chế vận động', 1
) src JOIN patient_profiles p ON p.patient_code = src.patient
JOIN branches b ON b.branch_code = 'CN01'
JOIN staff_profiles s ON s.staff_code = 'BSFT0801'
JOIN rooms r ON r.branch_id = b.branch_id AND r.room_code = 'FT08_R1'
WHERE NOT EXISTS (SELECT 1 FROM visits v WHERE v.visit_code = src.code);

-- Existing tickets exercise reprint/idempotency and the next daily numbers:
-- VISFT0801 after assignment gets 02; VISFT0804 gets P-02 with a reason.
INSERT INTO queue_tickets
    (visit_id, branch_id, queue_type, queue_date, ticket_number, is_priority,
     display_number, is_emergency, priority_score, priority_reason, status, issued_at)
SELECT v.visit_id, v.branch_id, 'EXAM', CURDATE(), 1, 0, '01', 0, 0, NULL, 'WAITING', NOW()
FROM visits v WHERE v.visit_code = 'VISFT0802'
  AND NOT EXISTS (SELECT 1 FROM queue_tickets t WHERE t.visit_id = v.visit_id AND t.queue_type = 'EXAM');
INSERT INTO queue_tickets
    (visit_id, branch_id, queue_type, queue_date, ticket_number, is_priority,
     display_number, is_emergency, priority_score, priority_reason, status, issued_at)
SELECT v.visit_id, v.branch_id, 'EXAM', CURDATE(), 1, 1, 'P-01', 1, 100,
       'Cấp cứu chảy máu răng kéo dài', 'WAITING', NOW()
FROM visits v WHERE v.visit_code = 'VISFT0803'
  AND NOT EXISTS (SELECT 1 FROM queue_tickets t WHERE t.visit_id = v.visit_id AND t.queue_type = 'EXAM');
