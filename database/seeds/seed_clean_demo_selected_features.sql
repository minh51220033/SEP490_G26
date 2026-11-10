-- =============================================================================
-- BỘ DỮ LIỆU DEMO SẠCH & CHUẨN HÓA (CLEAN & REFRESH DEMO SEED DATA)
-- Hệ thống Quản lý Phòng khám Nha khoa Dental Clinic Management System (SEP490_G26)
-- Phạm vi áp dụng:
--   - FT03–04: Hồ sơ Bệnh nhân (SCR-03.1 đến SCR-03.4 & SCR-04.1 đến SCR-04.2)
--   - FT35–36: Người phụ thuộc & Tách tài khoản (SCR-35.1 đến SCR-35.3 & SCR-36.1 đến SCR-36.3)
--   - FT05–06: Lịch hẹn & Đặt lịch Online (SCR-05.1 đến SCR-05.4 & SCR-06.1 đến SCR-06.4)
--   - FT07–10: Tiếp nhận, Điều phối Ghế & Hàng đợi (SCR-07.1 đến SCR-10.4)
-- Mật khẩu mặc định toàn hệ thống: Hieu1811
-- =============================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET CHARACTER SET utf8mb4;
SET time_zone = '+07:00';
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- PHẦN 1: CHUẨN HÓA CẤU TRÚC VÀ BẢNG MÃ CÁC BẢNG TIẾP ĐÓN & ĐIỀU PHỐI
-- -----------------------------------------------------------------------------
ALTER TABLE branches CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE rooms CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE dental_chairs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE work_shifts CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE staff_profiles CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE patient_profiles CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE appointments CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE visits CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE queue_tickets CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- PHẦN 2: CHI NHÁNH, CA LÀM VIỆC, PHÒNG KHÁM & GHẾ ĐIỀU TRỊ DEMO (CN01)
-- -----------------------------------------------------------------------------
UPDATE branches
SET branch_name = 'Cơ sở Quận 1',
    address = '123 Nguyễn Trãi, Phường Bến Thành, Quận 1, TP.HCM',
    phone = '02839251234',
    email = 'cs1@dentalclinic.com',
    is_active = 1
WHERE branch_code = 'CN01';

UPDATE branches
SET branch_name = 'Cơ sở Đống Đa',
    address = '456 Láng Hạ, Phường Láng Hạ, Đống Đa, Hà Nội',
    phone = '02438567890',
    email = 'cs2@dentalclinic.com',
    is_active = 1
WHERE branch_code = 'CN02';

-- Ca làm việc chuẩn hóa tiếng Việt
UPDATE work_shifts SET shift_name = 'Ca sáng (08:00 - 12:00)' WHERE shift_code = 'FT06_AM';
UPDATE work_shifts SET shift_name = 'Ca chiều (13:30 - 17:30)' WHERE shift_code = 'FT06_PM';
UPDATE work_shifts SET shift_name = 'Ca tối (18:00 - 20:30)' WHERE shift_code = 'FT06_EV';
UPDATE work_shifts SET shift_name = 'Ca trực cả ngày' WHERE shift_code = 'FT08_ALL';
UPDATE work_shifts SET shift_name = 'Ca trực chuyên môn cả ngày' WHERE shift_code = 'EMR_FULL_DAY';

INSERT INTO work_shifts (branch_id, shift_code, shift_name, start_time, end_time, is_active)
SELECT 1, 'FT08_ALL', 'Ca trực cả ngày', '00:00:00', '23:59:59', 1
WHERE NOT EXISTS (SELECT 1 FROM work_shifts WHERE branch_id = 1 AND shift_code = 'FT08_ALL');

-- Phòng khám tại CN01: Chuẩn hóa tên và trạng thái
UPDATE rooms SET room_name = 'Phòng khám Tổng quát 101', room_type = 'TREATMENT', status = 'ACTIVE' WHERE room_code = 'FT08_R1';
UPDATE rooms SET room_name = 'Phòng điều trị Kỹ thuật cao 102', room_type = 'TREATMENT', status = 'MAINTENANCE' WHERE room_code = 'FT08_R2';
UPDATE rooms SET room_name = 'Phòng Chẩn đoán Chuyên sâu 201', room_type = 'EXAM', status = 'ACTIVE' WHERE room_code = 'EMR-R01';

INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT 1, 'FT08_R1', 'Phòng khám Tổng quát 101', 'TREATMENT', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE branch_id = 1 AND room_code = 'FT08_R1');

INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT 1, 'FT08_R2', 'Phòng điều trị Kỹ thuật cao 102', 'TREATMENT', 'MAINTENANCE'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE branch_id = 1 AND room_code = 'FT08_R2');

INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT 1, 'EMR-R01', 'Phòng Chẩn đoán Chuyên sâu 201', 'EXAM', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE branch_id = 1 AND room_code = 'EMR-R01');

-- Ghế nha khoa tại CN01: Chuẩn hóa tên và phân bổ trạng thái theo luồng
UPDATE dental_chairs SET chair_name = 'Ghế điều trị 01', status = 'AVAILABLE' WHERE chair_code = 'FT08_C1';
UPDATE dental_chairs SET chair_name = 'Ghế điều trị 02 (Bảo trì định kỳ)', status = 'MAINTENANCE' WHERE chair_code = 'FT08_C2';
UPDATE dental_chairs SET chair_name = 'Ghế điều trị 03', status = 'IN_USE' WHERE chair_code = 'FT10_C1';
UPDATE dental_chairs SET chair_name = 'Ghế khám chuyên sâu 04', status = 'CLEANING' WHERE chair_code = 'EMR-C01';

INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, (SELECT room_id FROM rooms WHERE branch_id = 1 AND room_code = 'FT08_R1' LIMIT 1), 'FT08_C1', 'Ghế điều trị 01', 'AVAILABLE'
WHERE NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'FT08_C1');

INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, (SELECT room_id FROM rooms WHERE branch_id = 1 AND room_code = 'FT08_R1' LIMIT 1), 'FT08_C2', 'Ghế điều trị 02 (Bảo trì định kỳ)', 'MAINTENANCE'
WHERE NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'FT08_C2');

INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, (SELECT room_id FROM rooms WHERE branch_id = 1 AND room_code = 'FT08_R1' LIMIT 1), 'FT10_C1', 'Ghế điều trị 03', 'IN_USE'
WHERE NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'FT10_C1');

INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, (SELECT room_id FROM rooms WHERE branch_id = 1 AND room_code = 'EMR-R01' LIMIT 1), 'EMR-C01', 'Ghế khám chuyên sâu 04', 'CLEANING'
WHERE NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'EMR-C01');

-- -----------------------------------------------------------------------------
-- PHẦN 3: BÁC SĨ & LỄ TÂN DEMO CHUẨN TIẾNG VIỆT
-- -----------------------------------------------------------------------------
-- Cập nhật thông tin bác sĩ 1
UPDATE staff_profiles
SET full_name = 'BS. CK1 Lê Hoàng Nam',
    gender = 'MALE',
    specialty = 'Răng Hàm Mặt - Nha chu & Implant',
    department = 'Khoa Lâm sàng',
    position_title = 'Bác sĩ chuyên khoa',
    phone_number = '0911223344',
    email = 'nam.le@dentalclinic.com',
    status = 'ACTIVE'
WHERE staff_id = 1 OR staff_code = 'BS001';

-- Cập nhật thông tin bác sĩ 2
UPDATE staff_profiles
SET full_name = 'ThS. BS Trần Mai Hoa',
    gender = 'FEMALE',
    specialty = 'Chỉnh nha - Niềng răng thẩm mỹ',
    department = 'Khoa Chỉnh nha',
    position_title = 'Bác sĩ điều trị',
    phone_number = '0922334455',
    email = 'hoa.tran@dentalclinic.com',
    status = 'ACTIVE'
WHERE staff_id = 2 OR staff_code = 'BS002';

-- Cập nhật thông tin bác sĩ 3 (EMR)
UPDATE staff_profiles
SET full_name = 'BS. CKI Nguyễn Minh Khôi',
    gender = 'MALE',
    specialty = 'Nội nha & Nha khoa tổng quát',
    department = 'Khoa Điều trị',
    position_title = 'Bác sĩ chuyên khoa',
    phone_number = '0909001001',
    email = 'khoi.nguyen@dentalclinic.com',
    status = 'ACTIVE'
WHERE staff_id = 12 OR staff_code = 'BSEMR01';

-- Cập nhật thông tin bác sĩ 4 (BSFT0801)
UPDATE staff_profiles
SET full_name = 'BS. CKII Đặng Quốc Huy',
    gender = 'MALE',
    specialty = 'Tiểu phẫu & Phẫu thuật trong miệng',
    department = 'Khoa Phẫu thuật trong miệng',
    position_title = 'Trưởng khoa Phẫu thuật',
    phone_number = '0908881122',
    email = 'huy.dang@dentalclinic.com',
    status = 'ACTIVE'
WHERE staff_id = 5 OR staff_code = 'BSFT0801';

-- Cập nhật thông tin bác sĩ 5 (BSFT0802)
UPDATE staff_profiles
SET full_name = 'ThS. BS Phạm Thu Trang',
    gender = 'FEMALE',
    specialty = 'Phục hình thẩm mỹ & Răng sứ',
    department = 'Khoa Phục hình',
    position_title = 'Bác sĩ điều trị',
    phone_number = '0908882233',
    email = 'trang.pham@dentalclinic.com',
    status = 'ACTIVE'
WHERE staff_id = 6 OR staff_code = 'BSFT0802';

-- Chuẩn hóa thông tin Lễ tân
UPDATE staff_profiles
SET full_name = 'Nguyễn Thùy Linh',
    department = 'Tiếp đón & CSKH',
    position_title = 'Lễ tân trưởng',
    status = 'ACTIVE'
WHERE staff_id = 4 OR staff_code = 'LTFT07';

UPDATE staff_profiles
SET full_name = 'Trần Minh Anh',
    department = 'Tiếp đón & CSKH',
    position_title = 'Chuyên viên lễ tân',
    status = 'ACTIVE'
WHERE staff_id = 7 OR staff_code = 'LTFT0506';

UPDATE staff_profiles
SET full_name = 'Lê Phương Thảo',
    department = 'Tiếp đón & CSKH',
    position_title = 'Chuyên viên điều phối',
    status = 'ACTIVE'
WHERE staff_id = 8 OR staff_code = 'LTPH03';

UPDATE staff_profiles
SET full_name = 'Phạm Thị Dung',
    department = 'Khoa Lâm sàng',
    position_title = 'Phụ tá nha khoa',
    specialty = 'Phụ tá nha khoa',
    status = 'ACTIVE'
WHERE staff_id = 3 OR staff_code = 'PT001';

-- Đảm bảo phân quyền chi nhánh CN01 cho tất cả bác sĩ demo
INSERT INTO staff_branch_assignments (staff_id, branch_id, role_id, is_primary, start_date, status, created_at)
SELECT s.staff_id, 1, (SELECT role_id FROM roles WHERE role_name IN ('BAC_SI','DOCTOR') LIMIT 1), 1, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 'ACTIVE', NOW()
FROM staff_profiles s
WHERE s.staff_id IN (1, 2, 5, 6, 12)
  AND NOT EXISTS (
      SELECT 1 FROM staff_branch_assignments a 
      WHERE a.staff_id = s.staff_id AND a.branch_id = 1 AND a.status = 'ACTIVE'
  );

-- Đảm bảo ca trực hôm nay (CURDATE()) cho tất cả bác sĩ demo tại CN01
INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, created_at)
SELECT s.staff_id, 1, w.shift_id, CURDATE(), 'SCHEDULED', NOW()
FROM staff_profiles s
JOIN work_shifts w ON w.branch_id = 1 AND w.shift_code = 'FT08_ALL'
WHERE s.staff_id IN (1, 2, 5, 6, 12)
  AND NOT EXISTS (
      SELECT 1 FROM staff_schedules sch
      WHERE sch.staff_id = s.staff_id AND sch.branch_id = 1 
        AND sch.work_date = CURDATE() AND sch.shift_id = w.shift_id
  );

-- -----------------------------------------------------------------------------
-- PHẦN 4: HỒ SƠ BỆNH NHÂN DEMO (FT03–04 & FT35–36)
-- -----------------------------------------------------------------------------
-- Hồ sơ 1: Nguyễn Văn An (Bản thân - Chủ gia đình, tài khoản patient_an)
UPDATE patient_profiles
SET full_name = 'Nguyễn Văn An',
    patient_code = 'BN000101',
    dob = '1995-05-20',
    gender = 'Nam',
    phone_number = '0987654321',
    address = '123 Nguyễn Trãi, Phường Bến Thành, Quận 1, TP.HCM',
    id_card_number = '079195001234',
    health_insurance_number = 'DN4010195012345',
    has_allergies = 1,
    allergy_details = 'Dị ứng Penicillin nhẹ',
    has_cardiovascular_disease = 0,
    cardiovascular_details = NULL,
    has_underlying_disease = 0,
    underlying_disease_details = NULL,
    medical_history_completed = 1,
    preferred_branch_id = 1,
    is_default = 1,
    membership_tier_id = 2,
    deposit_balance = 500000.00,
    status = 'ACTIVE'
WHERE profile_id = 8 OR patient_code IN ('BNPH02000', 'BN000101');

-- Hồ sơ 2: Nguyễn Minh Bé (Người phụ thuộc - Con gái nhỏ, dùng chung SĐT gia đình)
UPDATE patient_profiles
SET full_name = 'Nguyễn Minh Bé',
    patient_code = 'BN000121',
    dob = '2017-06-15',
    gender = 'Nữ',
    phone_number = '0987654321',
    address = '123 Nguyễn Trãi, Phường Bến Thành, Quận 1, TP.HCM',
    guardian_name = 'Nguyễn Văn An',
    guardian_phone = '0987654321',
    guardian_relationship = 'Cha',
    health_insurance_number = 'TE4010170012345',
    id_card_number = NULL,
    has_allergies = 0,
    allergy_details = NULL,
    has_underlying_disease = 0,
    underlying_disease_details = NULL,
    medical_history_completed = 1,
    preferred_branch_id = 1,
    is_default = 0,
    membership_tier_id = 1,
    status = 'ACTIVE'
WHERE profile_id = 6 OR patient_code IN ('BNPH02001', 'BN000121');

-- Hồ sơ 3: Trần Thu Hương (Người phụ thuộc - Vợ, đủ điều kiện tách tài khoản riêng FT36)
UPDATE patient_profiles
SET full_name = 'Trần Thu Hương',
    patient_code = 'BN000122',
    dob = '1996-04-18',
    gender = 'Nữ',
    phone_number = '0987654321',
    address = '123 Nguyễn Trãi, Phường Bến Thành, Quận 1, TP.HCM',
    id_card_number = '079196022334',
    health_insurance_number = 'DN4010196022334',
    guardian_name = NULL,
    guardian_phone = NULL,
    guardian_relationship = NULL,
    has_allergies = 0,
    allergy_details = NULL,
    has_underlying_disease = 1,
    underlying_disease_details = 'Huyết áp thấp nhẹ',
    medical_history_completed = 1,
    preferred_branch_id = 1,
    is_default = 0,
    membership_tier_id = 2,
    status = 'ACTIVE'
WHERE profile_id = 2 OR patient_code IN ('BNPH01002', 'BN000122');

-- Hồ sơ 4: Nguyễn Quang Anh (Hồ sơ đã tách tài khoản thành công sang user nguyeenquanganh FT36)
UPDATE patient_profiles
SET full_name = 'Nguyễn Quang Anh',
    patient_code = 'BN000123',
    dob = '2002-11-10',
    gender = 'Nam',
    phone_number = '0961895546',
    address = '45 Lê Duẩn, Phường Bến Nghé, Quận 1, TP.HCM',
    id_card_number = '079202033445',
    health_insurance_number = 'DN4010202033445',
    user_id = 14,
    previous_user_id = 4,
    separated_at = DATE_SUB(NOW(), INTERVAL 5 DAY),
    is_default = 1,
    has_allergies = 0,
    allergy_details = NULL,
    has_underlying_disease = 0,
    underlying_disease_details = NULL,
    medical_history_completed = 1,
    preferred_branch_id = 1,
    membership_tier_id = 1,
    status = 'ACTIVE'
WHERE profile_id = 7 OR patient_code IN ('BN1792851138200', 'BN000123');

-- Đồng bộ liên kết phân quyền gia đình trong account_patient_profiles
INSERT INTO account_patient_profiles (account_id, profile_id, access_type, created_at)
VALUES (4, 8, 'SELF', NOW())
ON DUPLICATE KEY UPDATE access_type = 'SELF';

INSERT INTO account_patient_profiles (account_id, profile_id, access_type, created_at)
VALUES (4, 6, 'DEPENDENT', NOW())
ON DUPLICATE KEY UPDATE access_type = 'DEPENDENT';

INSERT INTO account_patient_profiles (account_id, profile_id, access_type, created_at)
VALUES (4, 2, 'DEPENDENT', NOW())
ON DUPLICATE KEY UPDATE access_type = 'DEPENDENT';

INSERT INTO account_patient_profiles (account_id, profile_id, access_type, created_at)
VALUES (14, 7, 'SELF', NOW())
ON DUPLICATE KEY UPDATE access_type = 'SELF';

-- Chuẩn hóa các hồ sơ còn lại trong danh mục hồ sơ bệnh nhân
UPDATE patient_profiles
SET full_name = 'Lê Quốc Cường',
    patient_code = 'BN000103',
    dob = '1988-12-10',
    gender = 'Nam',
    phone_number = '0903998877',
    address = '789 Cách Mạng Tháng Tám, Phường 5, Tân Bình, TP.HCM',
    id_card_number = '079188003456',
    health_insurance_number = 'DN4010188034567',
    has_allergies = 0,
    has_underlying_disease = 1,
    underlying_disease_details = 'Tăng huyết áp nhẹ có dùng thuốc',
    medical_history_completed = 1,
    membership_tier_id = 4,
    deposit_balance = 2000000.00,
    status = 'ACTIVE'
WHERE profile_id = 3;

UPDATE patient_profiles
SET full_name = 'Phạm Minh Đức',
    patient_code = 'BN000104',
    dob = '1990-03-14',
    gender = 'Nam',
    phone_number = '0978112233',
    address = '88 Cầu Giấy, Phường Quan Hoa, Cầu Giấy, Hà Nội',
    id_card_number = '001090004567',
    health_insurance_number = 'DN4010190045678',
    preferred_branch_id = 2,
    membership_tier_id = 1,
    status = 'ACTIVE'
WHERE profile_id = 1 OR patient_code = 'BNPH01001';

UPDATE patient_profiles
SET full_name = 'Trần Thanh Hà',
    patient_code = 'BN000105',
    dob = '1988-02-14',
    gender = 'Nữ',
    phone_number = '0912345678',
    address = '33 Võ Thị Sáu, Phường Đa Kao, Quận 1, TP.HCM',
    id_card_number = '079188002345',
    health_insurance_number = 'DN4010188023456',
    preferred_branch_id = 1,
    status = 'ACTIVE'
WHERE profile_id = 10 OR patient_code = 'BNPH01003';

UPDATE patient_profiles
SET full_name = 'Trần Thanh Hà',
    patient_code = 'BN000106',
    dob = '1992-11-06',
    gender = 'Nữ',
    phone_number = '0934567890',
    address = '68 Trần Hưng Đạo, Phường 2, Quận 5, TP.HCM',
    id_card_number = '079192003456',
    health_insurance_number = 'DN4010192034567',
    preferred_branch_id = 1,
    status = 'ACTIVE'
WHERE profile_id = 11 OR patient_code = 'BNPH01004';

UPDATE patient_profiles
SET full_name = 'Lê Gia Bảo',
    patient_code = 'BN000107',
    dob = '2018-08-09',
    gender = 'Nam',
    phone_number = '0988123456',
    address = '789 Cách Mạng Tháng Tám, Phường 5, Tân Bình, TP.HCM',
    guardian_name = 'Lê Quốc Cường',
    guardian_phone = '0988123456',
    guardian_relationship = 'Cha',
    health_insurance_number = 'TE4010180034567',
    preferred_branch_id = 1,
    status = 'ACTIVE'
WHERE profile_id = 12 OR patient_code = 'BNPH01005';

UPDATE patient_profiles
SET full_name = 'Nguyễn Tuấn Kiệt',
    dob = '1991-03-20', gender = 'Nam',
    address = '28 Nguyễn Thị Minh Khai, Quận 1, TP.HCM',
    id_card_number = '079191007890',
    status = 'ACTIVE'
WHERE patient_code = 'BNFT0601';

UPDATE patient_profiles
SET full_name = 'Nguyễn Bảo Ngọc',
    dob = '2016-09-12', gender = 'Nữ',
    address = '28 Nguyễn Thị Minh Khai, Quận 1, TP.HCM',
    guardian_name = 'Nguyễn Tuấn Kiệt',
    guardian_phone = '0905000601',
    guardian_relationship = 'Cha',
    status = 'ACTIVE'
WHERE patient_code = 'BNFT0602';

UPDATE patient_profiles
SET full_name = 'Nguyễn Phương Vy',
    dob = '2019-12-05', gender = 'Nữ',
    address = '28 Nguyễn Thị Minh Khai, Quận 1, TP.HCM',
    status = 'INACTIVE'
WHERE patient_code = 'BNFT0603';

UPDATE patient_profiles SET full_name = 'Trần Đức Thịnh', address = '45 Lê Thánh Tôn, Quận 1, TP.HCM' WHERE patient_code = 'BNFT0604';
UPDATE patient_profiles SET full_name = 'Lê Gia Hưng', address = '67 Pasteur, Quận 1, TP.HCM' WHERE patient_code = 'BNFT0605';

UPDATE patient_profiles
SET full_name = 'Nguyễn Thị Lan',
    dob = '1993-01-10', gender = 'Nữ',
    address = '56 Lê Thánh Tôn, Bến Nghé, Quận 1, TP.HCM',
    id_card_number = '079193008901',
    health_insurance_number = 'DN4010193008901',
    has_allergies = 1,
    allergy_details = 'Dị ứng Aspirin',
    status = 'ACTIVE'
WHERE patient_code = 'BNFT0701';

UPDATE patient_profiles
SET full_name = 'Phạm Thu Hằng',
    dob = '1989-03-15', gender = 'Nữ',
    address = '88 Hai Bà Trưng, Bến Nghé, Quận 1, TP.HCM',
    id_card_number = '079189009012',
    has_underlying_disease = 1,
    underlying_disease_details = 'Tăng huyết áp nhẹ',
    status = 'ACTIVE'
WHERE patient_code = 'BNFT0702';

UPDATE patient_profiles SET full_name = 'Đỗ Kim Chi', address = '15 Lê Duẩn, Quận 1, TP.HCM' WHERE patient_code = 'BNFT0703';
UPDATE patient_profiles SET full_name = 'Đặng Kim Chi', address = '22 Nguyễn Huệ, Quận 1, TP.HCM' WHERE patient_code = 'BNFT0704';
UPDATE patient_profiles SET full_name = 'Nguyễn Hoàng Dũng', address = '104 Điện Biên Phủ, Quận 1, TP.HCM' WHERE patient_code = 'BNFT0705';
UPDATE patient_profiles SET full_name = 'Lê Thùy Dung', address = '37 Nam Kỳ Khởi Nghĩa, Quận 1, TP.HCM' WHERE patient_code = 'BNFT0706';
UPDATE patient_profiles SET full_name = 'Đỗ Hương Giang', address = '82 Đồng Khởi, Quận 1, TP.HCM' WHERE patient_code = 'BNFT0707';
UPDATE patient_profiles SET full_name = 'Trần Thanh Hải', address = '19 Tôn Đức Thắng, Quận 1, TP.HCM' WHERE patient_code = 'BNFT0708';

UPDATE patient_profiles
SET full_name = 'Nguyễn Thảo My',
    dob = '1986-06-09', gender = 'Nữ',
    address = '90 Trần Hưng Đạo, Quận 1, TP.HCM',
    id_card_number = '079186010123',
    status = 'INACTIVE'
WHERE patient_code = 'BNFT0709';

UPDATE patient_profiles SET full_name = 'Phạm Mai Lan', address = '45 Lê Lai, Quận 1, TP.HCM' WHERE patient_code = 'BNFT0710';
UPDATE patient_profiles SET full_name = 'Hoàng Tuấn Minh', address = '12 Láng Hạ, Đống Đa, Hà Nội' WHERE patient_code = 'BNFT0711';

UPDATE patient_profiles
SET full_name = 'Huỳnh Văn Tiến',
    dob = '1986-09-15', gender = 'Nam',
    address = '78 Quốc lộ 50, Bình Chánh, TP.HCM',
    id_card_number = '079186011234',
    status = 'ACTIVE'
WHERE patient_code = 'BNFT0801';

UPDATE patient_profiles
SET full_name = 'Phan Kim Oanh',
    dob = '1987-03-22', gender = 'Nữ',
    address = '34 Cách Mạng Tháng Tám, Quận 10, TP.HCM',
    id_card_number = '079187012345',
    status = 'ACTIVE'
WHERE patient_code = 'BNFT0802';

UPDATE patient_profiles
SET full_name = 'Võ Đình Trọng',
    dob = '1991-10-05', gender = 'Nam',
    address = '12 Đoàn Văn Bơ, Quận 4, TP.HCM',
    id_card_number = '079191013456',
    status = 'ACTIVE'
WHERE patient_code = 'BNFT0803';

UPDATE patient_profiles
SET full_name = 'Trịnh Bá Thông',
    dob = '1943-05-18', gender = 'Nam',
    address = '88 Pasteur, Phường Bến Nghé, Quận 1, TP.HCM',
    id_card_number = '079143014567',
    health_insurance_number = 'GD4010143011234',
    has_underlying_disease = 1,
    underlying_disease_details = 'Bệnh tim mạch người cao tuổi',
    membership_tier_id = 2,
    status = 'ACTIVE'
WHERE patient_code = 'BNFT0804';

UPDATE patient_profiles SET full_name = 'Nguyễn Thanh Sơn', address = '14 Nguyễn Đình Chiểu, Quận 1, TP.HCM' WHERE patient_code = 'BNFT1001';
UPDATE patient_profiles SET full_name = 'Lâm Gia Hân', address = '45 Nam Kỳ Khởi Nghĩa, Quận 1, TP.HCM' WHERE patient_code = 'BNFT1002';
UPDATE patient_profiles SET full_name = 'Trương Vĩnh Khang', address = '22 Lê Lợi, Phường Bến Nghé, Quận 1, TP.HCM' WHERE patient_code = 'BNFT1003';
UPDATE patient_profiles SET full_name = 'Đặng Quốc Tuấn', address = '91 Hàm Nghi, Quận 1, TP.HCM' WHERE patient_code = 'BNFT1004';

UPDATE patient_profiles
SET full_name = 'Đỗ Khánh Vy',
    dob = '1996-10-22', gender = 'Nữ',
    address = '101 Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP.HCM',
    id_card_number = '079096002001',
    membership_tier_id = 3,
    status = 'ACTIVE'
WHERE patient_code = 'BNFLOW1001';

UPDATE patient_profiles SET full_name = 'Nguyễn Minh Khôi', address = '12 Nguyễn Trãi, Quận 1, TP.HCM' WHERE patient_code = 'BNEMR1001';
UPDATE patient_profiles SET full_name = 'Trần Thu Hà', address = '85 Hai Bà Trưng, Quận 3, TP.HCM' WHERE patient_code = 'BNEMR1002';
UPDATE patient_profiles SET full_name = 'Lê Quốc Bảo', address = '20 Cách Mạng Tháng Tám, Tân Bình, TP.HCM' WHERE patient_code = 'BNEMR1003';
UPDATE patient_profiles SET full_name = 'Phạm Ngọc Linh', address = '42 Lê Lợi, Quận 1, TP.HCM' WHERE patient_code = 'BNEMR1004';
UPDATE patient_profiles SET full_name = 'Vũ Hoàng Long', address = '98 Võ Văn Tần, Quận 3, TP.HCM' WHERE patient_code = 'BNEMR1005';

UPDATE patient_profiles SET full_name = 'Trần Văn Thuận', address = '12 Nguyễn Trãi, Quận 1, TP.HCM' WHERE patient_code = 'BN_E2E_01';
UPDATE patient_profiles SET full_name = 'Lê Hoàng Long', address = '45 Lê Lai, Quận 1, TP.HCM' WHERE patient_code = 'BN_E2E_02';
UPDATE patient_profiles SET full_name = 'Bùi Hữu Tài', address = '78 Hai Bà Trưng, Quận 1, TP.HCM' WHERE patient_code = 'BN_E2E_03';
UPDATE patient_profiles SET full_name = 'Phạm Ngọc Mai', address = '90 Trần Hưng Đạo, Quận 1, TP.HCM' WHERE patient_code = 'BN_E2E_04';
UPDATE patient_profiles SET full_name = 'Đặng Thành Nhân', address = '14 Điện Biên Phủ, Quận 1, TP.HCM' WHERE patient_code = 'BN_E2E_05';
UPDATE patient_profiles SET full_name = 'Hoàng Bảo Nam', address = '33 Nam Kỳ Khởi Nghĩa, Quận 1, TP.HCM' WHERE patient_code = 'BN_E2E_06';
UPDATE patient_profiles SET full_name = 'Vũ Thị Cẩm Tú', address = '55 Bến Vân Đồn, Quận 4, TP.HCM', status = 'INACTIVE' WHERE patient_code = 'BN_E2E_07';
UPDATE patient_profiles SET full_name = 'Nguyễn Thùy Dương', address = '21 Pasteur, Quận 1, TP.HCM' WHERE patient_code = 'BN_E2E_08';
UPDATE patient_profiles SET full_name = 'Phan Văn Trực', address = '66 Lê Lợi, Quận 1, TP.HCM' WHERE patient_code = 'BN_E2E_09';

-- -----------------------------------------------------------------------------
-- PHẦN 5: LỊCH HẸN DEMO (FT05–06)
-- -----------------------------------------------------------------------------
-- Chuẩn hóa các lịch hẹn cũ để xóa bỏ nội dung test
UPDATE appointments SET initial_symptoms = 'Khám tổng quát và kiểm tra răng định kỳ' WHERE appointment_code = 'LHFT0501REC';
UPDATE appointments SET initial_symptoms = 'Tư vấn cấy ghép Implant' WHERE appointment_code = 'LHFT0502CAN';
UPDATE appointments SET initial_symptoms = 'Khám răng hàm mặt' WHERE appointment_code = 'LHFT0503NOS';
UPDATE appointments SET initial_symptoms = 'Cạo vôi răng và đánh bóng men răng' WHERE appointment_code = 'LHFT0504DON';
UPDATE appointments SET initial_symptoms = 'Trám răng sâu mặt nhai' WHERE appointment_code = 'LHFT0604CAP';
UPDATE appointments SET initial_symptoms = 'Tư vấn chỉnh nha niềng răng' WHERE appointment_code = 'LHFT0605CAP';
UPDATE appointments SET initial_symptoms = 'Đau nhức răng hàm dưới khi ăn nhai' WHERE appointment_code = 'LHFT0701';
UPDATE appointments SET initial_symptoms = 'Ê buốt răng khi uống nước lạnh' WHERE appointment_code = 'LHFT0702';
UPDATE appointments SET initial_symptoms = 'Kiểm tra khớp cắn định kỳ' WHERE appointment_code = 'LHFT0705';
UPDATE appointments SET initial_symptoms = 'Tư vấn niềng răng mắc cài' WHERE appointment_code = 'LHFT0706';
UPDATE appointments SET initial_symptoms = 'Cạo vôi răng và làm sạch mảng bám' WHERE appointment_code = 'LHFT0707';
UPDATE appointments SET initial_symptoms = 'Đau buốt răng số 46 khi nhai' WHERE appointment_code = 'LHFT0708';
UPDATE appointments SET initial_symptoms = 'Khám tổng quát khoang miệng' WHERE appointment_code = 'LHFT0710';
UPDATE appointments SET initial_symptoms = 'Kiểm tra men răng định kỳ' WHERE appointment_code = 'LHFT0711';
UPDATE appointments SET initial_symptoms = 'Khám răng người cao tuổi' WHERE appointment_code = 'LH_E2E_03';
UPDATE appointments SET initial_symptoms = 'Khám kiểm tra men răng trẻ em' WHERE appointment_code = 'LH_E2E_04';
UPDATE appointments SET initial_symptoms = 'Nhổ răng khôn mọc lệch' WHERE appointment_code = 'LH_E2E_05';

-- Tạo bộ lịch hẹn demo chuẩn theo đúng các tình huống:
-- 1. Lịch hẹn HÔM NAY - Sẵn sàng bấm Check-in trên SCR-07.1
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH-2611-001', p.profile_id, 1, 1, 4,
       CURDATE(), 'MORNING', '08:30:00', 30,
       'Cạo vôi răng và kiểm tra răng định kỳ', 'ONLINE', 'CONFIRMED', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFT0701' OR p.patient_code = 'BN000110'
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = CURDATE(), expected_time = '08:30:00', status = 'CONFIRMED', initial_symptoms = VALUES(initial_symptoms);

-- 2. Lịch hẹn HÔM NAY - Dành cho người phụ thuộc (Nguyễn Minh Bé)
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH-2611-002', p.profile_id, 1, 1, 4,
       CURDATE(), 'MORNING', '09:15:00', 30,
       'Khám răng trẻ em định kỳ và bôi vecni fluor dự phòng sâu răng', 'ONLINE', 'CONFIRMED', 0, NOW()
FROM patient_profiles p WHERE p.profile_id = 6 OR p.patient_code IN ('BNPH02001', 'BN000121')
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = CURDATE(), expected_time = '09:15:00', status = 'CONFIRMED', initial_symptoms = VALUES(initial_symptoms);

-- 3. Lịch hẹn HÔM NAY - Chờ xác nhận (PENDING - Có nút Xác nhận & Check-in trên SCR-07.1)
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH-2611-003', p.profile_id, 1, 1, 4,
       CURDATE(), 'AFTERNOON', '15:30:00', 30,
       'Chảy máu nướu khi đánh răng, nghi viêm nha chu', 'ONLINE', 'PENDING', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFT0702' OR p.patient_code = 'BN000111'
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = CURDATE(), expected_time = '15:30:00', status = 'PENDING', initial_symptoms = VALUES(initial_symptoms);

-- 4. Lịch hẹn HÔM NAY - Đã check-in thành công (CHECKED_IN)
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH-2611-004', p.profile_id, 1, 2, 6,
       CURDATE(), 'MORNING', '10:00:00', 60,
       'Điều trị nội nha lấy tủy lần 2 răng hàm số 46', 'RECEPTIONIST', 'CHECKED_IN', 0, NOW()
FROM patient_profiles p WHERE p.profile_id = 42 OR p.patient_code = 'BNFT1003'
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = CURDATE(), expected_time = '10:00:00', status = 'CHECKED_IN', initial_symptoms = VALUES(initial_symptoms);

-- 5. Lịch hẹn HÔM NAY - Đã hoàn thành (COMPLETED)
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH-2611-005', p.profile_id, 1, 1, 5,
       CURDATE(), 'MORNING', '08:00:00', 45,
       'Trám răng sâu mặt nhai composite thẩm mỹ', 'PHONE', 'COMPLETED', 0, NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFLOW1001'
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = CURDATE(), expected_time = '08:00:00', status = 'COMPLETED', initial_symptoms = VALUES(initial_symptoms);

-- 6. Lịch hẹn HÔM NAY - Đã hủy (CANCELLED) có lý do rõ ràng
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, cancel_reason, cancelled_at, created_at
)
SELECT 'LH-2611-006', p.profile_id, 1, 1, 7,
       CURDATE(), 'AFTERNOON', '16:00:00', 60,
       'Tiểu phẫu nhổ răng khôn hàm dưới lệch gần', 'PHONE', 'CANCELLED', 0,
       'Bệnh nhân bận công tác đột xuất, xin dời sang tuần sau', NOW(), NOW()
FROM patient_profiles p WHERE p.profile_id = 3
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = CURDATE(), expected_time = '16:00:00', status = 'CANCELLED', cancel_reason = VALUES(cancel_reason);

-- 7. Lịch hẹn SẮP TỚI - Đã đổi lịch hẹn (Rescheduled count = 1)
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH-2611-007', p.profile_id, 1, 2, 4,
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'AFTERNOON', '15:00:00', 30,
       'Đổi lịch hẹn từ buổi sáng sang buổi chiều do bận việc', 'ONLINE', 'CONFIRMED', 1, NOW()
FROM patient_profiles p WHERE p.profile_id = 10
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY), expected_time = '15:00:00', status = 'CONFIRMED', reschedule_count = 1;

-- 8. Lịch hẹn SẮP TỚI - Tương lai (3 ngày tới)
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH-2611-008', p.profile_id, 1, 1, 8,
       DATE_ADD(CURDATE(), INTERVAL 3 DAY), 'MORNING', '10:00:00', 60,
       'Tái khám kiểm tra trụ Implant sau phẫu thuật 1 tuần', 'RECEPTIONIST', 'CONFIRMED', 0, NOW()
FROM patient_profiles p WHERE p.profile_id = 8 OR p.patient_code = 'BN000101'
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = DATE_ADD(CURDATE(), INTERVAL 3 DAY), expected_time = '10:00:00', status = 'CONFIRMED';

-- 9. Lịch hẹn SẮP TỚI - Tương lai (7 ngày tới)
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH-2611-009', p.profile_id, 1, 2, 2,
       DATE_ADD(CURDATE(), INTERVAL 7 DAY), 'AFTERNOON', '14:30:00', 60,
       'Thăm khám tư vấn bọc răng sứ thẩm mỹ Cercon HT', 'ONLINE', 'CONFIRMED', 0, NOW()
FROM patient_profiles p WHERE p.profile_id = 2 OR p.patient_code = 'BN000122'
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = DATE_ADD(CURDATE(), INTERVAL 7 DAY), expected_time = '14:30:00', status = 'CONFIRMED';

-- 10. Lịch hẹn VẮNG MẶT (NO_SHOW) - Xuất hiện trên màn hình Không đến & Cần nhắc (SCR-05.4)
INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH-2611-010', p.profile_id, 1, 1, 4,
       DATE_SUB(CURDATE(), INTERVAL 1 DAY), 'MORNING', '09:30:00', 30,
       'Khám răng định kỳ nhưng không đến và không liên hệ', 'PHONE', 'NO_SHOW', 0, NOW()
FROM patient_profiles p WHERE p.profile_id = 87 OR p.patient_code = 'BNEMR1001'
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY), status = 'NO_SHOW';

INSERT INTO appointments (
    appointment_code, profile_id, branch_id, doctor_id, service_id,
    appointment_date, preferred_session, expected_time, duration_minutes,
    initial_symptoms, source, status, reschedule_count, created_at
)
SELECT 'LH-2611-011', p.profile_id, 1, 1, 4,
       DATE_SUB(CURDATE(), INTERVAL 2 DAY), 'AFTERNOON', '14:00:00', 30,
       'Khách hẹn cạo vôi răng không đến phòng khám', 'ONLINE', 'NO_SHOW', 0, NOW()
FROM patient_profiles p WHERE p.profile_id = 26 OR p.patient_code = 'BNFT0709'
LIMIT 1
ON DUPLICATE KEY UPDATE appointment_date = DATE_SUB(CURDATE(), INTERVAL 2 DAY), status = 'NO_SHOW';

-- -----------------------------------------------------------------------------
-- PHẦN 6: LƯỢT KHÁM, ĐIỀU PHỐI & HÀNG CHỜ DEMO TRONG NGÀY (FT07–10)
-- -----------------------------------------------------------------------------
-- Làm sạch các lượt khám và vé hàng chờ cũ của ngày hôm nay để nạp kịch bản chuẩn
DELETE FROM queue_tickets WHERE branch_id = 1 AND queue_date = CURDATE();

-- Tình huống 1: Bệnh nhân WALK-IN vừa check-in, chưa phân công bác sĩ & ghế (Hiển thị ở Bàn điều phối SCR-08.1)
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS-2611-001', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 40 MINUTE),
       'Đau nhức dữ dội răng hàm số 6 khi nhai thức ăn', NULL, NULL, NULL, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFT0801'
LIMIT 1
ON DUPLICATE KEY UPDATE check_in_at = DATE_SUB(NOW(), INTERVAL 40 MINUTE), primary_doctor_id = NULL, room_id = NULL, chair_id = NULL, status = 'WAITING';

-- Tình huống 2: Bệnh nhân WALK-IN đã phân công BS Nam & Ghế 01, sẵn sàng cấp số tại Bàn điều phối (SCR-08.2)
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS-2611-002', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 35 MINUTE),
       'Sưng nướu răng hàm trên, chảy máu chân răng khi đánh răng', 1, 1, 1, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFT0802'
LIMIT 1
ON DUPLICATE KEY UPDATE check_in_at = DATE_SUB(NOW(), INTERVAL 35 MINUTE), primary_doctor_id = 1, room_id = 1, chair_id = 1, status = 'WAITING';

-- Tình huống 3: Số thường 01 - Đang chờ (WAITING) trong hàng chờ khám
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS-2611-003', p.profile_id, 1, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 30 MINUTE),
       'Khám kiểm tra định kỳ răng hàm mặt và cạo vôi răng', 1, 1, 1, 'WAITING', NOW()
FROM patient_profiles p WHERE p.profile_id = 8 OR p.patient_code = 'BN000101'
LIMIT 1
ON DUPLICATE KEY UPDATE check_in_at = DATE_SUB(NOW(), INTERVAL 30 MINUTE), primary_doctor_id = 1, room_id = 1, chair_id = 1, status = 'WAITING';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 1,
       0, '01', 0, 0,
       'WAITING', DATE_SUB(NOW(), INTERVAL 30 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS-2611-003';

-- Tình huống 4: Số thường 02 - Đang chờ (WAITING) bác sĩ Trần Mai Hoa
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS-2611-004', p.profile_id, 1, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 25 MINUTE),
       'Điều trị nội nha lấy tủy răng hàm số 46', 2, 1, 3, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFT0710' OR p.patient_code = 'BN000110'
LIMIT 1
ON DUPLICATE KEY UPDATE check_in_at = DATE_SUB(NOW(), INTERVAL 25 MINUTE), primary_doctor_id = 2, room_id = 1, chair_id = 3, status = 'WAITING';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 2,
       0, '02', 0, 0,
       'WAITING', DATE_SUB(NOW(), INTERVAL 25 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS-2611-004';

-- Tình huống 5: Số ưu tiên P-02 - Đang chờ (WAITING) - Bệnh nhân cao tuổi (Trịnh Bá Thông - 83 tuổi)
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS-2611-005', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 20 MINUTE),
       'Khám phục hình răng giả tháo lắp cho người cao tuổi', 1, 1, 1, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFT0804'
LIMIT 1
ON DUPLICATE KEY UPDATE check_in_at = DATE_SUB(NOW(), INTERVAL 20 MINUTE), primary_doctor_id = 1, room_id = 1, chair_id = 1, status = 'WAITING';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score, priority_reason,
    status, issued_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 2,
       1, 'P-02', 0, 50, 'Bệnh nhân cao tuổi (83 tuổi) ưu tiên khám',
       'WAITING', DATE_SUB(NOW(), INTERVAL 20 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS-2611-005';

-- Tình huống 6: Số cấp cứu P-01 - Đang gọi vào khám (CALLED) - Hiển thị trực tiếp trên TV Queue (SCR-10.3)
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS-2611-006', p.profile_id, 1, 'EMERGENCY', DATE_SUB(NOW(), INTERVAL 15 MINUTE),
       'Cấp cứu chảy máu chân răng kéo dài sau tiểu phẫu nhổ răng', 1, 1, 1, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFT0803'
LIMIT 1
ON DUPLICATE KEY UPDATE check_in_at = DATE_SUB(NOW(), INTERVAL 15 MINUTE), primary_doctor_id = 1, room_id = 1, chair_id = 1, status = 'WAITING';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score, priority_reason,
    status, issued_at, called_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 1,
       1, 'P-01', 1, 100, 'Cấp cứu chảy máu chân răng sau tiểu phẫu',
       'CALLED', DATE_SUB(NOW(), INTERVAL 15 MINUTE), DATE_SUB(NOW(), INTERVAL 3 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS-2611-006';

-- Tình huống 7: Số thường 03 - Đang khám tại ghế (IN_SERVICE, Visit IN_EXAM) trên Ghế 03 (FT10_C1)
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS-2611-007', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 50 MINUTE),
       'Viêm tủy cấp răng số 36, đau nhức giật theo nhịp mạch', 2, 1, 3, 'IN_EXAM', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFT1003'
LIMIT 1
ON DUPLICATE KEY UPDATE check_in_at = DATE_SUB(NOW(), INTERVAL 50 MINUTE), primary_doctor_id = 2, room_id = 1, chair_id = 3, status = 'IN_EXAM';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at, called_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 3,
       0, '03', 0, 0,
       'IN_SERVICE', DATE_SUB(NOW(), INTERVAL 50 MINUTE), DATE_SUB(NOW(), INTERVAL 30 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS-2611-007';

-- Tình huống 8: Số thường 04 - Tạm dừng (PAUSED) do bệnh nhân tạm thời rời khu chờ
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT 'VIS-2611-008', p.profile_id, 1, 'WALK_IN', DATE_SUB(NOW(), INTERVAL 45 MINUTE),
       'Kiểm tra khớp cắn sau hàn răng và điều chỉnh cộm', 12, 1, NULL, 'WAITING', NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFT1002'
LIMIT 1
ON DUPLICATE KEY UPDATE check_in_at = DATE_SUB(NOW(), INTERVAL 45 MINUTE), primary_doctor_id = 12, room_id = 1, chair_id = NULL, status = 'WAITING';

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score, pause_reason,
    status, issued_at, paused_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 4,
       0, '04', 0, 0, 'Bệnh nhân tạm thời rời khu chờ đi làm thủ tục',
       'PAUSED', DATE_SUB(NOW(), INTERVAL 45 MINUTE), DATE_SUB(NOW(), INTERVAL 15 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS-2611-008';

-- Tình huống 9: Số thường 05 - Đã hoàn tất (DONE, Visit COMPLETED) - Ghế 04 chuyển sang CLEANING
INSERT INTO visits (
    visit_code, profile_id, branch_id, visit_type, check_in_at,
    initial_symptoms, primary_doctor_id, room_id, chair_id, status, completed_at, created_at
)
SELECT 'VIS-2611-009', p.profile_id, 1, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 80 MINUTE),
       'Khám và hoàn tất thủ thuật trám răng composite thẩm mỹ', 1, 1, 6, 'COMPLETED', DATE_SUB(NOW(), INTERVAL 10 MINUTE), NOW()
FROM patient_profiles p WHERE p.patient_code = 'BNFLOW1001'
LIMIT 1
ON DUPLICATE KEY UPDATE check_in_at = DATE_SUB(NOW(), INTERVAL 80 MINUTE), primary_doctor_id = 1, room_id = 1, chair_id = 6, status = 'COMPLETED', completed_at = DATE_SUB(NOW(), INTERVAL 10 MINUTE);

INSERT INTO queue_tickets (
    visit_id, branch_id, queue_type, queue_date, ticket_number,
    is_priority, display_number, is_emergency, priority_score,
    status, issued_at, called_at, completed_at
)
SELECT v.visit_id, 1, 'EXAM', CURDATE(), 5,
       0, '05', 0, 0,
       'DONE', DATE_SUB(NOW(), INTERVAL 80 MINUTE), DATE_SUB(NOW(), INTERVAL 50 MINUTE), DATE_SUB(NOW(), INTERVAL 10 MINUTE)
FROM visits v WHERE v.visit_code = 'VIS-2611-009';

SET FOREIGN_KEY_CHECKS = 1;

