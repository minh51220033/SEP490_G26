-- Manual FT10 scenarios. Run after V8, seed_ft07_reception.sql and seed_ft08_ft09_dispatch.sql.
-- Run on a fresh test database each day because queue_date and check_in_at are dated at seed time.
SET time_zone = '+07:00';

INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address,
     preferred_branch_id, is_default, medical_history_completed, deposit_balance, status)
SELECT NULL, src.code, src.name, '1990-01-01', 'Nữ', src.phone, 'Thử FT10',
       b.branch_id, 0, 1, 0, 'ACTIVE'
FROM (
 SELECT 'BNFT1001' code, 'Đang gọi số' name, '0907100001' phone
 UNION ALL SELECT 'BNFT1002', 'Tạm dừng chờ', '0907100002'
 UNION ALL SELECT 'BNFT1003', 'Đang khám', '0907100003'
 UNION ALL SELECT 'BNFT1004', 'Sẵn sàng hoàn tất số', '0907100004'
) src JOIN branches b ON b.branch_code = 'CN01'
WHERE NOT EXISTS (SELECT 1 FROM patient_profiles p WHERE p.patient_code = src.code);

INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT b.branch_id, r.room_id, 'FT10_C1', 'Ghế đang phục vụ FT10', 'IN_USE'
FROM branches b JOIN rooms r ON r.branch_id = b.branch_id AND r.room_code = 'FT08_R1'
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM dental_chairs c WHERE c.branch_id = b.branch_id AND c.chair_code = 'FT10_C1');

INSERT INTO visits (visit_code, profile_id, branch_id, visit_type, check_in_at, initial_symptoms,
                    primary_doctor_id, room_id, chair_id, status, created_at)
SELECT src.code, p.profile_id, b.branch_id, 'WALK_IN', NOW(), src.symptoms,
       s.staff_id, r.room_id, CASE WHEN src.in_exam = 1 THEN c.chair_id ELSE NULL END,
       CASE WHEN src.in_exam = 1 THEN 'IN_EXAM'
            WHEN src.in_exam = 2 THEN 'WAITING_PARACLINICAL' ELSE 'WAITING' END, NOW()
FROM (
 SELECT 'VISFT1001' code, 'BNFT1001' patient, 'Đang chờ được bác sĩ tiếp nhận' symptoms, 0 in_exam
 UNION ALL SELECT 'VISFT1002', 'BNFT1002', 'Tạm dừng vì bệnh nhân rời khu chờ', 0
 UNION ALL SELECT 'VISFT1003', 'BNFT1003', 'Đang khám để thử hoàn tất hàng chờ', 1
 UNION ALL SELECT 'VISFT1004', 'BNFT1004', 'Đã chuyển sang cận lâm sàng để thử hoàn tất số', 2
) src JOIN patient_profiles p ON p.patient_code = src.patient
JOIN branches b ON b.branch_code = 'CN01'
JOIN staff_profiles s ON s.staff_code = 'BSFT0801'
JOIN rooms r ON r.branch_id = b.branch_id AND r.room_code = 'FT08_R1'
JOIN dental_chairs c ON c.branch_id = b.branch_id AND c.chair_code = 'FT10_C1'
WHERE NOT EXISTS (SELECT 1 FROM visits v WHERE v.visit_code = src.code);

INSERT INTO queue_tickets (visit_id, branch_id, queue_type, queue_date, ticket_number,
                           is_priority, display_number, is_emergency, priority_score,
                           priority_reason, status, issued_at, called_at)
SELECT v.visit_id, v.branch_id, 'EXAM', CURDATE(), src.number,
       0, src.display_no, 0, 0, NULL, src.status, NOW(),
       CASE WHEN src.status IN ('CALLED','IN_SERVICE') THEN NOW() ELSE NULL END
FROM (
 SELECT 'VISFT1001' code, 3 number, '03' display_no, 'CALLED' status
 UNION ALL SELECT 'VISFT1002', 4, '04', 'PAUSED'
 UNION ALL SELECT 'VISFT1003', 5, '05', 'IN_SERVICE'
 UNION ALL SELECT 'VISFT1004', 6, '06', 'IN_SERVICE'
) src JOIN visits v ON v.visit_code = src.code
WHERE NOT EXISTS (SELECT 1 FROM queue_tickets t WHERE t.visit_id = v.visit_id AND t.queue_type = 'EXAM');

-- Try RESUME on 04, START on 03, EMERGENCY on a waiting ticket with a reason.
-- DONE is available immediately for 06. To test rejection on 05, try DONE before
-- advancing VISFT1003 via the doctor workflow to a post-exam state.
