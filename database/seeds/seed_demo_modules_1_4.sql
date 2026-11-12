-- =============================================================================
-- DỮ LIỆU SEED CHUẨN DEMO BÁO CÁO HỘI ĐỒNG: PHÂN HỆ 1 - 4 (CV-DCMS)
-- Chi nhánh demo: Cơ sở Quận 1 (branch_id = 1)
-- Phân hệ bao phủ:
--   1. Quản lý Lịch hẹn (FT05)
--   2. Tiếp đón & Check-in (FT07)
--   3. Điều phối Ghế nha & Bác sĩ (FT09)
--   4. Hàng chờ Khám bệnh & Tivi Sảnh Chờ (FT08)
--
-- Đặc điểm:
--   * Mã hóa chuẩn UTF-8 / utf8mb4 tiếng Việt hoàn chỉnh, không lỗi font.
--   * Dữ liệu y khoa thực tế, sạch sẽ, không dùng dữ liệu giả/demo vô nghĩa.
--   * Tuân thủ triệt để BR-PAT-09 (Người giám hộ cho trẻ em < 18 tuổi).
--   * Tính năng Idempotent: Có thể nạp nhiều lần an toàn mà không trùng bản ghi.
-- =============================================================================

SET NAMES utf8mb4;
SET time_zone = '+07:00';
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- BƯỚC 1: DỌN DẸP DỮ LIỆU DEMO CŨ CỦA MODULE 1-4 (IDEMPOTENT CLEANUP)
-- -----------------------------------------------------------------------------
DELETE FROM queue_tickets WHERE visit_id IN (
    SELECT visit_id FROM visits WHERE visit_code LIKE 'VIS_DEMO_%'
);

DELETE FROM visits WHERE visit_code LIKE 'VIS_DEMO_%';

DELETE FROM appointments WHERE appointment_code LIKE 'LH_DEMO_%';

DELETE FROM patient_profiles WHERE patient_code LIKE 'BN_DEMO_%';

SET FOREIGN_KEY_CHECKS = 1;

-- -----------------------------------------------------------------------------
-- BƯỚC 2: THIẾT LẬP CA TRỰC & BÁC SĨ TRỰC HÔM NAY (CV-04: ACTIVE DUTY SHIFT)
-- -----------------------------------------------------------------------------
-- 2.1 Ca trực cả ngày bao phủ thời điểm demo
INSERT INTO work_shifts (branch_id, shift_code, shift_name, start_time, end_time, is_active)
SELECT 1, 'CA_DEMO_ALL', 'Ca trực lâm sàng toàn thời gian', '00:00:00', '23:59:59', 1
WHERE NOT EXISTS (SELECT 1 FROM work_shifts WHERE branch_id = 1 AND shift_code = 'CA_DEMO_ALL');

-- 2.2 Bác sĩ 1: BS. CK1 Lê Hoàng Nam (staff_id = 1) - Trực hôm nay
INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, created_at)
SELECT 1, 1, w.shift_id, CURDATE(), 'SCHEDULED', NOW()
FROM work_shifts w WHERE w.branch_id = 1 AND w.shift_code = 'CA_DEMO_ALL'
AND NOT EXISTS (
    SELECT 1 FROM staff_schedules s 
    WHERE s.staff_id = 1 AND s.branch_id = 1 AND s.work_date = CURDATE() AND s.shift_id = w.shift_id
);

-- 2.3 Bác sĩ 2: ThS. BS Trần Mai Hoa (staff_id = 2) - Trực hôm nay
INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, created_at)
SELECT 2, 1, w.shift_id, CURDATE(), 'SCHEDULED', NOW()
FROM work_shifts w WHERE w.branch_id = 1 AND w.shift_code = 'CA_DEMO_ALL'
AND NOT EXISTS (
    SELECT 1 FROM staff_schedules s 
    WHERE s.staff_id = 2 AND s.branch_id = 1 AND s.work_date = CURDATE() AND s.shift_id = w.shift_id
);

-- -----------------------------------------------------------------------------
-- BƯỚC 3: THIẾT LẬP PHÒNG KHÁM & GHẾ NHA KHOA TẠI CƠ SỞ QUẬN 1
-- -----------------------------------------------------------------------------
-- Phòng khám tổng quát 101
INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT 1, 'PK_101', 'Phòng khám Nha khoa Tổng quát 101', 'TREATMENT', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE branch_id = 1 AND room_code = 'PK_101');

-- Ghế điều trị 01: Sẵn sàng phục vụ (AVAILABLE)
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, r.room_id, 'GHE_01', 'Ghế điều trị 01', 'AVAILABLE'
FROM rooms r WHERE r.branch_id = 1 AND r.room_code = 'PK_101'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'GHE_01');

-- Ghế điều trị 02: Sẵn sàng phục vụ (AVAILABLE)
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, r.room_id, 'GHE_02', 'Ghế điều trị 02', 'AVAILABLE'
FROM rooms r WHERE r.branch_id = 1 AND r.room_code = 'PK_101'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'GHE_02');

-- Ghế điều trị 03: Đang có ca điều trị (IN_USE)
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, r.room_id, 'GHE_03', 'Ghế điều trị 03', 'IN_USE'
FROM rooms r WHERE r.branch_id = 1 AND r.room_code = 'PK_101'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'GHE_03');

-- Đảm bảo trạng thái ghế chuẩn
UPDATE dental_chairs SET status = 'AVAILABLE' WHERE chair_code IN ('GHE_01', 'GHE_02');
UPDATE dental_chairs SET status = 'IN_USE' WHERE chair_code = 'GHE_03';

-- -----------------------------------------------------------------------------
-- BƯỚC 4: HỒ SƠ BỆNH NHÂN CHUẨN Y KHOA (PATIENT PROFILES)
-- -----------------------------------------------------------------------------
INSERT INTO patient_profiles (
    patient_code, full_name, dob, gender, phone_number, address,
    preferred_branch_id, is_default, medical_history_completed, deposit_balance,
    guardian_name, guardian_phone, guardian_relationship,
    has_allergies, allergy_details,
    has_cardiovascular_disease, cardiovascular_details,
    has_underlying_disease, underlying_disease_details,
    status, created_at
) VALUES
-- 1. Bệnh nhân trẻ em (14 tuổi) - Đầy đủ thông tin người giám hộ (BR-PAT-09)
-- Mục đích demo: Luồng chính Lịch hẹn -> Tiếp đón -> Điều phối -> Khám
('BN_DEMO_01', 'Nguyễn Gia Bảo', '2012-06-15', 'Nam', '0912345678', '120 Hai Bà Trưng, Phường Bến Nghé, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 'Nguyễn Văn Minh', '0987654321', 'Bố',
 0, NULL, 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- 2. Bệnh nhân có CẢNH BÁO Y TẾ ĐỎ (Medical Alert Flag: Dị ứng thuốc tê & Tăng huyết áp)
-- Mục đích demo: Check-in tự động hiển thị cờ cảnh báo lâm sàng nổi bật
('BN_DEMO_02', 'Trần Thị Mai Anh', '1990-04-20', 'Nữ', '0908123456', '45 Lê Duẩn, Phường Bến Nghé, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 1, 'Dị ứng thuốc tê Lidocain và kháng sinh nhóm Penicillin',
 1, 'Tiền sử tăng huyết áp vô căn (đang dùng Amlodipine 5mg)',
 0, NULL, 'ACTIVE', NOW()),

-- 3. Bệnh nhân có lịch hẹn Chờ xác nhận (PENDING)
-- Mục đích demo: Bấm nút "Xác nhận" (Confirm) trên bảng Lịch hẹn
('BN_DEMO_03', 'Lê Quốc Hưng', '1988-11-10', 'Nam', '0933987654', '88 Nguyễn Đình Chiểu, Phường Đa Kao, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- 4. Bệnh nhân người cao tuổi (84 tuổi)
-- Mục đích demo: Nằm trong danh sách "Chờ điều phối" để gán số ưu tiên người già
('BN_DEMO_04', 'Cụ Hoàng Thị Phúc', '1942-02-05', 'Nữ', '0918765432', '15 Mạc Đĩnh Chi, Phường Đa Kao, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 'Hoàng Anh Tuấn', '0918765433', 'Con trai',
 0, NULL, 0, NULL, 1, 'Đái tháo đường Type 2 kiểm soát tốt', 'ACTIVE', NOW()),

-- 5. Bệnh nhân tiếp đón cấp cứu (Emergency)
-- Mục đích demo: Thử tính năng chuyển ưu tiên cấp cứu vượt lên đầu hàng chờ
('BN_DEMO_05', 'Vũ Cấp Cứu', '1995-08-12', 'Nam', '0977112233', '30 Nguyễn Thị Minh Khai, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- 6. Bệnh nhân đang ở HÀNG CHỜ SẴN SÀNG (Số 01 - WAITING)
-- Mục đích demo: Bấm nút "GỌI VÀO KHÁM" -> Tivi sảnh phát chuông chime và đọc loa!
('BN_DEMO_06', 'Đỗ Văn Nam', '1993-03-25', 'Nam', '0966554433', '52 Đinh Tiên Hoàng, Phường Đa Kao, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- 7. Bệnh nhân đang ở trạng thái ĐANG GỌI (Số 02 - CALLED)
-- Mục đích demo: Bấm nút "GỌI LẠI" (Re-call) -> Kiểm chứng CV-05 phát âm thanh lại ngay!
('BN_DEMO_07', 'Phạm Thùy Linh', '1997-09-18', 'Nữ', '0944332211', '76 Pasteur, Phường Bến Nghé, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 0, NULL, 'ACTIVE', NOW()),

-- 8. Bệnh nhân ĐANG ĐIỀU TRỊ TẠI GHẾ (Số 03 - IN_SERVICE)
-- Mục đích demo: Hiển thị minh bạch tại cột "ĐANG KHÁM TẠI GHẾ" trên Tivi sảnh
('BN_DEMO_08', 'Ngô Đức Thịnh', '1985-12-30', 'Nam', '0988776655', '92 Nam Kỳ Khởi Nghĩa, Quận 1, TP.HCM',
 1, 0, 1, 0.00,
 NULL, NULL, NULL,
 0, NULL, 0, NULL, 0, NULL, 'ACTIVE', NOW());

-- -----------------------------------------------------------------------------
-- BƯỚC 5: LỊCH HẸN KHÁM HÔM NAY (APPOINTMENTS)
-- -----------------------------------------------------------------------------
-- Lịch 1: Nguyễn Gia Bảo - 09:30 - CONFIRMED -> Sẵn sàng bấm nút "Tiếp đón (Check-in)"
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH_DEMO_01', p.profile_id, 1, 1, 4,
       CURDATE(), 'MORNING', '09:30:00', 30,
       'Khám răng định kỳ và cạo vôi răng cho học sinh', 'RECEPTIONIST', 'CONFIRMED', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_01';

-- Lịch 2: Trần Thị Mai Anh - 10:30 - CONFIRMED -> Sẵn sàng bấm check-in xem cờ dị ứng
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH_DEMO_02', p.profile_id, 1, 2, 6,
       CURDATE(), 'MORNING', '10:30:00', 45,
       'Đau nhức tủy răng hàm số 46, ê buốt khi ăn nhai', 'ONLINE', 'CONFIRMED', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_02';

-- Lịch 3: Lê Quốc Hưng - 14:00 - PENDING (Chờ xác nhận) -> Sẵn sàng bấm nút "Xác nhận"
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH_DEMO_03', p.profile_id, 1, 1, 5,
       CURDATE(), 'AFTERNOON', '14:00:00', 30,
       'Răng cửa bị mẻ nhẹ, cần trám thẩm mỹ Composite', 'PHONE', 'PENDING', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_03';

-- Lịch 4: Lịch mẫu Đã Hủy có lý do chuẩn y tế
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, cancel_reason, cancelled_at, created_at
)
SELECT 'LH_DEMO_CANCEL', p.profile_id, 1, 1, 4,
       CURDATE(), 'MORNING', '08:00:00', 30,
       'Khám tổng quát', 'PHONE', 'CANCELLED', 0,
       'Bệnh nhân báo bận công tác đột xuất, dời lịch sang tuần sau', NOW(), NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_03';

-- -----------------------------------------------------------------------------
-- BƯỚC 6: LƯỢT KHÁM & HÀNG CHỜ PHỤC VỤ DEMO (VISITS & QUEUE TICKETS)
-- -----------------------------------------------------------------------------
-- 6.1 Bệnh nhân BN_DEMO_04 (Cụ Hoàng Thị Phúc):
-- Đã check-in lúc sáng, đang ở danh sách CHỜ ĐIỀU PHỐI (chưa có bác sĩ & ghế)
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_DEMO_04', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 25 MINUTE),
       'Bệnh nhân cao tuổi khám kiểm tra phục hình răng tháo lắp', NULL, NULL, NULL, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_04';

-- 6.2 Bệnh nhân BN_DEMO_06 (Đỗ Văn Nam):
-- Đã được điều phối cho BS. Lê Hoàng Nam (Phòng 101, Ghế 01)
-- Số thứ tự: A-001 (WAITING) -> SẴN SÀNG BẤM "GỌI VÀO KHÁM"
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_DEMO_06', p.profile_id, 1, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 30 MINUTE),
       'Cạo vôi răng và đánh bóng định kỳ 6 tháng', 1,
       (SELECT room_id FROM rooms WHERE branch_id = 1 AND room_code = 'PK_101' LIMIT 1),
       (SELECT chair_id FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'GHE_01' LIMIT 1),
       'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_06';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 1,
       0, 'A-001', 0, 0,
       'WAITING', DATE_SUB(NOW(), INTERVAL 25 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS_DEMO_06';

-- 6.3 Bệnh nhân BN_DEMO_07 (Phạm Thùy Linh):
-- Đang ở trạng thái ĐANG GỌI (CALLED) của BS. Lê Hoàng Nam (Phòng 101, Ghế 02)
-- Số thứ tự: A-002 (CALLED) -> SẴN SÀNG BẤM "GỌI LẠI" (RE-CALL ĐỂ TEST CV-05)
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_DEMO_07', p.profile_id, 1, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 40 MINUTE),
       'Khám và tư vấn niềng răng trong suốt Invisalign', 1,
       (SELECT room_id FROM rooms WHERE branch_id = 1 AND room_code = 'PK_101' LIMIT 1),
       (SELECT chair_id FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'GHE_02' LIMIT 1),
       'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_07';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at, called_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 2,
       0, 'A-002', 0, 0,
       'CALLED', DATE_SUB(NOW(), INTERVAL 35 MINUTE), DATE_SUB(NOW(), INTERVAL 3 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS_DEMO_07';

-- 6.4 Bệnh nhân BN_DEMO_08 (Ngô Đức Thịnh):
-- Đang ngồi thực tế trên Ghế 03 (Phòng 101), BS. Lê Hoàng Nam đang điều trị
-- Số thứ tự: A-003 (IN_SERVICE, Visit IN_EXAM) -> HIỂN THỊ CỘT 3 TRÊN TIVI SẢNH
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS_DEMO_08', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 60 MINUTE),
       'Điều trị tủy răng hàm số 36 lần 2', 1,
       (SELECT room_id FROM rooms WHERE branch_id = 1 AND room_code = 'PK_101' LIMIT 1),
       (SELECT chair_id FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'GHE_03' LIMIT 1),
       'IN_EXAM', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BN_DEMO_08';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at, called_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 3,
       0, 'A-003', 0, 0,
       'IN_SERVICE', DATE_SUB(NOW(), INTERVAL 55 MINUTE), DATE_SUB(NOW(), INTERVAL 45 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS_DEMO_08';
