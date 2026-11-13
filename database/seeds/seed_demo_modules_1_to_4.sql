-- =============================================================================
-- DỮ LIỆU SEED MẪU DÀNH RIÊNG CHO DEMO MODULE 1 - 4 (CV-DCMS)
-- Chi nhánh: Cơ sở Quận 1 (branch_id = 1)
-- Ngày áp dụng: CURDATE() (Tự động thích ứng thời gian thực khi chạy bất kỳ ngày nào)
-- Mật khẩu mặc định toàn hệ thống: Hieu1811
-- =============================================================================

SET NAMES utf8mb4;
SET time_zone = '+07:00';
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 1. DỌN DẸP DỮ LIỆU DEMO CŨ (IDEMPOTENT CLEANUP)
-- -----------------------------------------------------------------------------
DELETE FROM queue_tickets WHERE visit_id IN (
    SELECT visit_id FROM visits WHERE visit_code LIKE 'VIS_DEMO_%'
);
DELETE FROM reception_checkin_keys WHERE visit_id IN (
    SELECT visit_id FROM visits WHERE visit_code LIKE 'VIS_DEMO_%'
);
DELETE FROM visits WHERE visit_code LIKE 'VIS_DEMO_%';
DELETE FROM appointments WHERE appointment_code LIKE 'LH_DEMO_%';
DELETE FROM patient_profiles WHERE patient_code LIKE 'BN_DEMO_%';

SET FOREIGN_KEY_CHECKS = 1;

-- -----------------------------------------------------------------------------
-- 2. ĐẢM BẢO CA TRỰC HÔM NAY CỦA BÁC SĨ (MODULE 4: ACTIVE SHIFT VALIDATION)
-- -----------------------------------------------------------------------------
-- Ca trực cả ngày (00:00:00 - 23:59:59) đảm bảo demo vào bất kỳ giờ nào đều hợp lệ
INSERT INTO work_shifts (branch_id, shift_code, shift_name, start_time, end_time, is_active)
SELECT 1, 'FT08_ALL', 'Ca trực cả ngày', '00:00:00', '23:59:59', 1
WHERE NOT EXISTS (SELECT 1 FROM work_shifts WHERE branch_id = 1 AND shift_code = 'FT08_ALL');

-- Phân công ca trực hôm nay cho Bác sĩ 1 (BS. CK1 Lê Hoàng Nam - staff_id = 1)
INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, created_at)
SELECT 1, 1, w.shift_id, CURDATE(), 'SCHEDULED', NOW()
FROM work_shifts w WHERE w.branch_id = 1 AND w.shift_code = 'FT08_ALL'
AND NOT EXISTS (
    SELECT 1 FROM staff_schedules s 
    WHERE s.staff_id = 1 AND s.branch_id = 1 AND s.work_date = CURDATE() AND s.status = 'SCHEDULED'
);

-- Phân công ca trực hôm nay cho Bác sĩ 2 (ThS. BS Trần Mai Hoa - staff_id = 2)
INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, created_at)
SELECT 2, 1, w.shift_id, CURDATE(), 'SCHEDULED', NOW()
FROM work_shifts w WHERE w.branch_id = 1 AND w.shift_code = 'FT08_ALL'
AND NOT EXISTS (
    SELECT 1 FROM staff_schedules s 
    WHERE s.staff_id = 2 AND s.branch_id = 1 AND s.work_date = CURDATE() AND s.status = 'SCHEDULED'
);

-- -----------------------------------------------------------------------------
-- 3. ĐẢM BẢO PHÒNG KHÁM VÀ GHẾ NHA KHOA SẴN SÀNG
-- -----------------------------------------------------------------------------
-- Phòng 1: Phòng khám Tổng quát 101 (ACTIVE)
INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT 1, 'FT08_R1', 'Phòng khám Tổng quát 101', 'TREATMENT', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE branch_id = 1 AND room_code = 'FT08_R1');

-- Ghế 1: Ghế điều trị 01 (AVAILABLE - Sẵn sàng điều phối)
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, r.room_id, 'FT08_C1', 'Ghế điều trị 01', 'AVAILABLE'
FROM rooms r WHERE r.branch_id = 1 AND r.room_code = 'FT08_R1'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'FT08_C1');

-- Ghế 2: Ghế điều trị 02 (AVAILABLE - Sẵn sàng điều phối ca 2)
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, r.room_id, 'FT08_C2', 'Ghế điều trị 02', 'AVAILABLE'
FROM rooms r WHERE r.branch_id = 1 AND r.room_code = 'FT08_R1'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'FT08_C2');

-- Ghế 3: Ghế điều trị 03 (IN_USE - Đang có ca điều trị tại ghế)
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, r.room_id, 'FT10_C1', 'Ghế điều trị 03', 'IN_USE'
FROM rooms r WHERE r.branch_id = 1 AND r.room_code = 'FT08_R1'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'FT10_C1');

-- Cập nhật trạng thái ghế chuẩn
UPDATE dental_chairs SET status = 'AVAILABLE' WHERE chair_code IN ('FT08_C1', 'FT08_C2');
UPDATE dental_chairs SET status = 'IN_USE' WHERE chair_code = 'FT10_C1';

-- -----------------------------------------------------------------------------
-- 4. HỒ SƠ BỆNH NHÂN PHỤC VỤ TỪNG MÀN HÌNH DEMO
-- -----------------------------------------------------------------------------
INSERT INTO patient_profiles (
    patient_code, full_name, dob, gender, phone_number, address,
    preferred_branch_id, is_default, medical_history_completed, deposit_balance,
    guardian_name, guardian_phone, guardian_relationship,
    has_allergies, allergy_details, has_underlying_disease, underlying_disease_details,
    status, created_at
) VALUES
-- BN 1: Vị thành niên (14 tuổi) - Đầy đủ thông tin người giám hộ chuẩn BR-PAT-09
('BN_DEMO_01', 'Nguyễn Gia Bảo', '2012-06-15', 'Nam', '0912345678', '123 Nguyễn Huệ, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 'Nguyễn Văn Minh', '0987654321', 'Bố',
 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- BN 2: Sẵn sàng Demo Xác nhận lịch hẹn & Check-in (Có cờ Cảnh báo Y khoa rực đỏ)
('BN_DEMO_02', 'Trần Thị Mai Anh', '1995-08-20', 'Nữ', '0901234567', '45 Lê Duẩn, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 1, 'Dị ứng thuốc tê Lidocaine & Kháng sinh Amoxicillin', 1, 'Tiền sử hen suyễn nhẹ khi thời tiết lạnh',
 'ACTIVE', NOW()),

-- BN 3: Đã tiếp đón xong, đang nằm chờ tại bàn Điều phối ghế nha (Module 4)
('BN_DEMO_03', 'Lê Hoàng Long', '1988-11-10', 'Nam', '0934567890', '78 Hai Bà Trưng, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- BN 4: Đã điều phối cấp số A-001, đang chờ ở cột "Đang chờ" của Bác sĩ Nam (Module 3)
('BN_DEMO_04', 'Phạm Thùy Linh', '1997-03-25', 'Nữ', '0945678901', '89 Đồng Khởi, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- BN 5: Đang gọi vào khám A-002 của Bác sĩ Hoa -> Sẵn sàng bấm nút "GỌI LẠI" (Kiểm chứng CV-05)
('BN_DEMO_05', 'Vũ Minh Khang', '1990-12-04', 'Nam', '0967890123', '14 Pasteur, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- BN 6: Đang khám trên ghế A-003 -> Hiển thị trực tiếp trên cột 3 của Màn hình Tivi sảnh
('BN_DEMO_06', 'Đặng Ngọc Mai', '1993-07-15', 'Nữ', '0978901234', '56 Nam Kỳ Khởi Nghĩa, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- BN 7: Lịch hẹn đã HỦY -> Dùng để demo bộ lọc trạng thái "Đã hủy"
('BN_DEMO_07', 'Hoàng Đức Anh', '1985-04-18', 'Nam', '0989012345', '22 Tôn Đức Thắng, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- BN 8: Lịch hẹn đã CONFIRMED -> Sẵn sàng bấm 1-click Tiếp đón (Check-in)
('BN_DEMO_08', 'Lê Quốc Hưng', '1992-09-30', 'Nam', '0908889999', '101 Cách Mạng Tháng 8, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 'ACTIVE', NOW());

-- -----------------------------------------------------------------------------
-- 5. LỊCH HẸN HÔM NAY (APPOINTMENTS - MODULE 1)
-- -----------------------------------------------------------------------------
-- Lịch 1: BN_DEMO_02 - Chờ xác nhận (PENDING / BOOKED) -> Lễ tân bấm "Xác nhận"
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH_DEMO_02', p.profile_id, 1, 1, 5,
       CURDATE(), 'MORNING', '09:00:00', 30,
       'Ê buốt răng số 36 khi ăn nóng lạnh, có tiền sử hen suyễn', 'ONLINE', 'PENDING', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_02';

-- Lịch 2: BN_DEMO_08 - Đã xác nhận (CONFIRMED) -> Lễ tân bấm "Tiếp đón (Check-in)"
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH_DEMO_08', p.profile_id, 1, 2, 4,
       CURDATE(), 'MORNING', '10:00:00', 30,
       'Khám và cạo vôi răng định kỳ 6 tháng', 'PHONE', 'CONFIRMED', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_08';

-- Lịch 3: BN_DEMO_07 - Đã hủy (CANCELLED) kèm lý do rõ ràng
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, cancel_reason, cancelled_at, created_at
)
SELECT 'LH_DEMO_07', p.profile_id, 1, 1, 4,
       CURDATE(), 'MORNING', '08:00:00', 30,
       'Kiểm tra khớp cắn', 'PHONE', 'CANCELLED', 0, 'Bệnh nhân bận công tác đột xuất', NOW(), NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_07';

-- -----------------------------------------------------------------------------
-- 6. LƯỢT TIẾP ĐÓN ĐANG CHỜ ĐIỀU PHỐI GHẾ (VISITS - MODULE 4)
-- -----------------------------------------------------------------------------
-- BN_DEMO_03: Đã check-in lúc 15 phút trước, chưa có Bác sĩ / Ghế nha
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_DEMO_03', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 15 MINUTE),
       'Đau nhức vùng hàm trên bên phải, sưng nướu nhẹ', NULL, NULL, NULL, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_03';

-- -----------------------------------------------------------------------------
-- 7. HÀNG CHỜ KHÁM & MÀN HÌNH TIVI SẢNH (QUEUE TICKETS - MODULE 3)
-- -----------------------------------------------------------------------------
-- Ca 1: BN_DEMO_04 - Đã điều phối cho BS Nam, Ghế 01, số phiếu A-001 (WAITING)
-- => Sẵn sàng để bấm nút "GỌI VÀO KHÁM" trên Kanban -> Tivi reng chuông & đọc loa!
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_DEMO_04', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 25 MINUTE),
       'Viêm lợi chảy máu khi đánh răng', 1, 1, 1, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_04';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 1,
       0, 'A-001', 0, 0,
       'WAITING', DATE_SUB(NOW(), INTERVAL 25 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS_DEMO_04';

-- Ca 2: BN_DEMO_05 - Đã điều phối cho BS Hoa, Ghế 02, số phiếu A-002 (CALLED)
-- => Sẵn sàng để bấm nút "GỌI LẠI" (Re-call) -> Kiểm chứng CV-05 phát chuông & loa lần 2!
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_DEMO_05', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 35 MINUTE),
       'Khám tư vấn niềng răng mắc cài', 2, 1, 2, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_05';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at, called_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 2,
       0, 'A-002', 0, 0,
       'CALLED', DATE_SUB(NOW(), INTERVAL 35 MINUTE), DATE_SUB(NOW(), INTERVAL 1 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS_DEMO_05';

-- Ca 3: BN_DEMO_06 - Đang khám tại ghế 03 (IN_EXAM, ticket IN_SERVICE)
-- => Hiển thị trực quan ngay trên cột thứ 3 "ĐANG KHÁM TẠI GHẾ" của Màn hình Tivi!
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_DEMO_06', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 50 MINUTE),
       'Điều trị tủy răng số 46 buổi thứ nhất', 1, 1, 3, 'IN_EXAM', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_06';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at, called_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 3,
       0, 'A-003', 0, 0,
       'IN_SERVICE', DATE_SUB(NOW(), INTERVAL 50 MINUTE), DATE_SUB(NOW(), INTERVAL 40 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS_DEMO_06';

-- -----------------------------------------------------------------------------
-- HOÀN TẤT NẠP DỮ LIỆU DEMO SẠCH CHO 4 MODULE
-- -----------------------------------------------------------------------------

