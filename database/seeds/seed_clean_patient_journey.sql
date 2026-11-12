-- =============================================================================
-- DATA SEED SẠCH: LUỒNG TOÀN TRÌNH & NGOẠI LỆ (E2E PATIENT JOURNEY & EXCEPTIONS)
-- Hệ thống Quản lý Phòng khám Nha khoa DentalFlow (SEP490_G26)
-- Chi nhánh demo: CN01 (Cơ sở Quận 1, branch_id = 1)
-- Mật khẩu mặc định toàn bộ tài khoản: Hieu1811
-- =============================================================================

SET NAMES utf8mb4;
SET time_zone = '+07:00';
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- BƯỚC 1: DỌN DẸP DỮ LIỆU SEED TEST CŨ (IDEMPOTENT CLEANUP)
-- -----------------------------------------------------------------------------
DELETE FROM emr_record_versions WHERE record_id IN (
    SELECT record_id FROM emr_records WHERE profile_id IN (SELECT profile_id FROM patient_profiles WHERE patient_code LIKE 'BN_E2E_%')
);
DELETE FROM emr_audit_events WHERE record_id IN (
    SELECT record_id FROM emr_records WHERE profile_id IN (SELECT profile_id FROM patient_profiles WHERE patient_code LIKE 'BN_E2E_%')
);
DELETE FROM emr_records WHERE profile_id IN (SELECT profile_id FROM patient_profiles WHERE patient_code LIKE 'BN_E2E_%');

DELETE FROM queue_tickets WHERE visit_id IN (SELECT visit_id FROM visits WHERE visit_code LIKE 'VIS_E2E_%');
DELETE FROM examination_diagnoses WHERE visit_id IN (SELECT visit_id FROM visits WHERE visit_code LIKE 'VIS_E2E_%');
DELETE FROM clinical_examinations WHERE visit_id IN (SELECT visit_id FROM visits WHERE visit_code LIKE 'VIS_E2E_%');
DELETE FROM reception_checkin_keys WHERE visit_id IN (SELECT visit_id FROM visits WHERE visit_code LIKE 'VIS_E2E_%');

DELETE FROM visits WHERE visit_code LIKE 'VIS_E2E_%';
DELETE FROM appointments WHERE appointment_code LIKE 'LH_E2E_%';
DELETE FROM patient_profiles WHERE patient_code LIKE 'BN_E2E_%';

SET FOREIGN_KEY_CHECKS = 1;

-- -----------------------------------------------------------------------------
-- BƯỚC 2: ĐẢM BẢO NHÂN SỰ, PHÒNG KHÁM, GHẾ NHA KHOA & CA TRỰC HÔM NAY (CN01)
-- -----------------------------------------------------------------------------
-- Ca làm việc cả ngày để test luôn hợp lệ thời gian thực
INSERT INTO work_shifts (branch_id, shift_code, shift_name, start_time, end_time, is_active)
SELECT 1, 'FT08_ALL', 'Ca trực cả ngày', '00:00:00', '23:59:59', 1
WHERE NOT EXISTS (SELECT 1 FROM work_shifts WHERE branch_id = 1 AND shift_code = 'FT08_ALL');

-- Đảm bảo Bác sĩ 1 (BS. CK1 Lê Hoàng Nam - staff_id = 1, username: bacsi_nam) có lịch trực hôm nay
INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, created_at)
SELECT 1, 1, w.shift_id, CURDATE(), 'SCHEDULED', NOW()
FROM work_shifts w WHERE w.branch_id = 1 AND w.shift_code = 'FT08_ALL'
AND NOT EXISTS (
    SELECT 1 FROM staff_schedules s 
    WHERE s.staff_id = 1 AND s.branch_id = 1 AND s.work_date = CURDATE() AND s.shift_id = w.shift_id
);

-- Đảm bảo Bác sĩ 2 (ThS. BS Trần Mai Hoa - staff_id = 2, username: bacsi_hoa) có lịch trực hôm nay
INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, created_at)
SELECT 2, 1, w.shift_id, CURDATE(), 'SCHEDULED', NOW()
FROM work_shifts w WHERE w.branch_id = 1 AND w.shift_code = 'FT08_ALL'
AND NOT EXISTS (
    SELECT 1 FROM staff_schedules s 
    WHERE s.staff_id = 2 AND s.branch_id = 1 AND s.work_date = CURDATE() AND s.shift_id = w.shift_id
);

-- Phòng khám & Ghế nha khoa tại CN01:
-- Phòng 1: FT08_R1 (ACTIVE - Hoạt động)
INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT 1, 'FT08_R1', 'Phòng khám tổng quát 101', 'TREATMENT', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE branch_id = 1 AND room_code = 'FT08_R1');

-- Phòng 2: FT08_R2 (MAINTENANCE - Bảo trì) -> Test ngoại lệ phòng không khả dụng
INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT 1, 'FT08_R2', 'Phòng khám 102 (Đang bảo trì)', 'TREATMENT', 'MAINTENANCE'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE branch_id = 1 AND room_code = 'FT08_R2');

-- Ghế 1: FT08_C1 (AVAILABLE - Sẵn sàng đón bệnh nhân)
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, r.room_id, 'FT08_C1', 'Ghế điều trị 01 (Sẵn sàng)', 'AVAILABLE'
FROM rooms r WHERE r.branch_id = 1 AND r.room_code = 'FT08_R1'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'FT08_C1');

-- Ghế 2: FT08_C2 (MAINTENANCE - Ghế hỏng/bảo trì) -> Test ngoại lệ ghế hỏng
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, r.room_id, 'FT08_C2', 'Ghế điều trị 02 (Đang bảo trì)', 'MAINTENANCE'
FROM rooms r WHERE r.branch_id = 1 AND r.room_code = 'FT08_R1'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'FT08_C2');

-- Ghế 3: FT10_C1 (IN_USE - Đang có ca khám) -> Test ngoại lệ ghế đang bận
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, r.room_id, 'FT10_C1', 'Ghế điều trị 03 (Đang khám)', 'IN_USE'
FROM rooms r WHERE r.branch_id = 1 AND r.room_code = 'FT08_R1'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'FT10_C1');

-- -----------------------------------------------------------------------------
-- BƯỚC 3: SEED DỮ LIỆU HỒ SƠ BỆNH NHÂN (PATIENT PROFILES)
-- -----------------------------------------------------------------------------
INSERT INTO patient_profiles (
    patient_code, full_name, dob, gender, phone_number, address,
    preferred_branch_id, is_default, medical_history_completed, deposit_balance,
    has_allergies, allergy_details, has_underlying_disease, underlying_disease_details, status, created_at
) VALUES
-- 1. Happy Path: Bệnh nhân sẵn sàng check-in luồng chuẩn
('BN_E2E_01', 'Trần Văn Thuận', '1992-05-15', 'Nam', '0909001001', '12 Nguyễn Trãi, Q1, TP.HCM',
 1, 0, 1, 0.00, 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- 2. Cấp cứu: Bệnh nhân sẵn sàng tiếp đón cấp cứu (Emergency)
('BN_E2E_02', 'Lê Cấp Cứu', '1995-10-20', 'Nam', '0909001002', '45 Lê Lai, Q1, TP.HCM',
 1, 0, 1, 0.00, 1, 'Dị ứng Aspirin', 0, NULL, 'ACTIVE', NOW()),

-- 3. Ưu tiên tuổi cao: Cụ già sinh năm 1945 (>80 tuổi) tự động nhận P-xx
('BN_E2E_03', 'Cụ Bùi Ưu Tiên', '1945-03-10', 'Nam', '0909001003', '78 Hai Bà Trưng, Q1, TP.HCM',
 1, 0, 1, 0.00, 0, NULL, 1, 'Tăng huyết áp nhẹ', 'ACTIVE', NOW()),

-- 4. Ngoại lệ check-in: Lịch hẹn ngày mai (Sai ngày)
('BN_E2E_04', 'Phạm Ngày Mai', '1990-08-12', 'Nữ', '0909001004', '90 Trần Hưng Đạo, Q1, TP.HCM',
 1, 0, 1, 0.00, 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- 5. Ngoại lệ check-in: Lịch hẹn đã bị HỦY (Cancelled)
('BN_E2E_05', 'Đặng Đã Hủy', '1988-12-05', 'Nam', '0909001005', '14 Điện Biên Phủ, Q1, TP.HCM',
 1, 0, 1, 0.00, 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- 6. Ngoại lệ check-in: Bệnh nhân đang có ca khám dở dang hôm nay
('BN_E2E_06', 'Hoàng Khám Dở', '1994-06-18', 'Nam', '0909001006', '33 Nam Kỳ Khởi Nghĩa, Q1, TP.HCM',
 1, 0, 1, 0.00, 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- 7. Ngoại lệ tiếp nhận: Hồ sơ bệnh nhân bị KHÓA (INACTIVE)
('BN_E2E_07', 'Vũ Bị Khóa', '1985-01-25', 'Nữ', '0909001007', '55 Bến Vân Đồn, Q4, TP.HCM',
 1, 0, 1, 0.00, 0, NULL, 0, NULL, 'INACTIVE', NOW()),

-- 8. Hàng chờ sẵn sàng: Đã điều phối số 02, đang chờ gọi số / tạm dừng / đổi bác sĩ
('BN_E2E_08', 'Nguyễn Đang Chờ', '1996-09-09', 'Nữ', '0909001008', '21 Pasteur, Q1, TP.HCM',
 1, 0, 1, 0.00, 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- 9. Ca khám sẵn sàng: Đang khám trên ghế FT10_C1, sẵn sàng chẩn đoán và tạo bệnh án EMR
('BN_E2E_09', 'Phan Đang Khám', '1991-04-04', 'Nam', '0909001009', '66 Lê Lợi, Q1, TP.HCM',
 1, 0, 1, 0.00, 0, NULL, 0, NULL, 'ACTIVE', NOW());

-- -----------------------------------------------------------------------------
-- BƯỚC 4: SEED DỮ LIỆU LỊCH HẸN (APPOINTMENTS)
-- -----------------------------------------------------------------------------
-- Lịch hẹn 1 (Happy Path): Khám hôm nay lúc 09:30, trạng thái CONFIRMED, BS. Lê Hoàng Nam
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH_E2E_01', p.profile_id, 1, 1, 4,
       CURDATE(), 'MORNING', '09:30:00', 30,
       'Cạo vôi răng và kiểm tra răng định kỳ', 'PHONE', 'CONFIRMED', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_E2E_01';

-- Lịch hẹn 3 (Ưu tiên người già): Khám hôm nay lúc 10:00, CONFIRMED
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH_E2E_03', p.profile_id, 1, 1, 5,
       CURDATE(), 'MORNING', '10:00:00', 30,
       'Khám răng người cao tuổi, ê buốt răng hàm dưới', 'ONLINE', 'CONFIRMED', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_E2E_03';

-- Lịch hẹn 4 (Ngoại lệ: Lịch hẹn ngày mai)
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH_E2E_04', p.profile_id, 1, 1, 4,
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'MORNING', '09:00:00', 30,
       'Lịch hẹn ngày mai, thử check-in sớm', 'ONLINE', 'CONFIRMED', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_E2E_04';

-- Lịch hẹn 5 (Ngoại lệ: Lịch hẹn đã bị HỦY)
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, cancel_reason, cancelled_at, created_at
)
SELECT 'LH_E2E_05', p.profile_id, 1, 1, 4,
       CURDATE(), 'MORNING', '08:30:00', 30,
       'Lịch hẹn đã hủy vì bận việc đột xuất', 'PHONE', 'CANCELLED', 0, 'Bệnh nhân báo bận', NOW(), NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_E2E_05';

-- -----------------------------------------------------------------------------
-- BƯỚC 5: SEED LƯỢT KHÁM DỞ DANG VÀ HÀNG CHỜ CÓ SẴN (VISITS & QUEUE TICKETS)
-- -----------------------------------------------------------------------------
-- Bệnh nhân 6 (BN_E2E_06): Đang có ca khám dở dang hôm nay -> Chặn check-in trùng
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_E2E_06', p.profile_id, 1, 'WALK_IN', NOW(),
       'Ca khám chưa hoàn thành', 1, 1, 1, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_E2E_06';

-- Bệnh nhân 8 (BN_E2E_08): Đã check-in, đã được cấp số thứ tự 02 (WAITING)
-- Sẵn sàng để test trên Bảng hàng chờ: Gọi số, Tạm dừng, Tiếp tục, Đổi bác sĩ
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_E2E_08', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 20 MINUTE),
       'Đau nhức răng hàm số 6', 1, 1, 1, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_E2E_08';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 2,
       0, '02', 0, 0,
       'WAITING', DATE_SUB(NOW(), INTERVAL 20 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS_E2E_08';

-- Bệnh nhân 9 (BN_E2E_09): Đang khám trên ghế FT10_C1 (IN_SERVICE, Visit IN_EXAM)
-- Bác sĩ Nam có thể vào ngay để chẩn đoán, xem odontogram, kê đơn và sang EMR tạo bệnh án
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_E2E_09', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 45 MINUTE),
       'Viêm tủy răng hàm dưới, sưng đau nhẹ', 1, 1, 3, 'IN_EXAM', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_E2E_09';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at, called_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 1,
       0, '01', 0, 0,
       'IN_SERVICE', DATE_SUB(NOW(), INTERVAL 45 MINUTE), DATE_SUB(NOW(), INTERVAL 35 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS_E2E_09';

-- Khởi tạo bản ghi khám lâm sàng cho ca đang khám VIS_E2E_09
INSERT INTO clinical_examinations (
    visit_id, profile_id, doctor_id, chief_complaint, history_illness,
    extraoral_exam, intraoral_exam, periodontal_status, summary_assessment, exam_time
)
SELECT v.visit_id, v.profile_id, 1,
       'Đau nhức răng 46 khi ăn nhai',
       'Bệnh nhân đau buốt dữ dội 3 ngày nay, đau giật theo nhịp mạch',
       'Mặt cân đối, không sưng hạch góc hàm',
       'Răng 46 lỗ sâu lớn mặt nhai, gõ dọc (+), gõ ngang (-)',
       'Nướu viền quanh răng 46 hơi đỏ, chảy máu nhẹ khi thăm khám',
       'Theo dõi Viêm tủy cấp răng 46',
       NOW()
FROM visits v WHERE v.visit_code = 'VIS_E2E_09';

-- Chẩn đoán mẫu ICD-10 K04.0 (Viêm tủy răng)
INSERT INTO examination_diagnoses (
    exam_id, visit_id, icd10_code, diagnosis_text, diagnosis_type, tooth_numbers, notes
)
SELECT e.exam_id, v.visit_id, 'K04.0', 'Viêm tủy răng', 'MAIN', '46', 'Chỉ định điều trị tủy răng 46'
FROM visits v JOIN clinical_examinations e ON e.visit_id = v.visit_id
WHERE v.visit_code = 'VIS_E2E_09';

-- Cập nhật ghế FT10_C1 sang IN_USE
UPDATE dental_chairs SET status = 'IN_USE' WHERE chair_code = 'FT10_C1';

-- Cập nhật ghế FT08_C1 sang AVAILABLE (sẵn sàng đón BN_E2E_01)
UPDATE dental_chairs SET status = 'AVAILABLE' WHERE chair_code = 'FT08_C1';
