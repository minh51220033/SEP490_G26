-- ====================================================================================================
-- CV-DCMS: HỆ THỐNG QUẢN LÝ PHÒNG KHÁM NHA KHOA CƯỜNG VIỆT (DENTAL CLINIC MANAGEMENT & BOOKING SYSTEM)
-- FILE SQL KHỞI TẠO DỮ LIỆU ĐỒNG BỘ 100% LUỒNG NGHIỆP VỤ: CSKH, KHO VẬT TƯ, CHAT TRỰC TUYẾN
-- Mật khẩu dùng chung cho TẤT CẢ tài khoản: Hieu1811 (Bcrypt: $2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe)
-- ====================================================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------------------------------------------------------------------------------
-- 1. DANH MỤC CƠ SỞ / CHI NHÁNH (2 CƠ SỞ HOẠT ĐỘNG SONG SONG)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO branches (branch_id, branch_code, branch_name, phone, email, address, is_active, created_at, updated_at) VALUES
(1, 'CN01', 'Cơ sở 1 - Cầu Giấy (Trụ sở chính)', '02439988771', 'caugiay@cuongvietdental.vn', 'Số 128 Cầu Giấy, Phường Dịch Vọng, Quận Cầu Giấy, Hà Nội', 1, NOW(), NOW()),
(2, 'CN02', 'Cơ sở 2 - Đống Đa', '02439988772', 'dongda@cuongvietdental.vn', 'Số 45 Láng Hạ, Phường Thành Công, Quận Đống Đa, Hà Nội', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE branch_name = VALUES(branch_name), phone = VALUES(phone), address = VALUES(address);

-- ----------------------------------------------------------------------------------------------------
-- 2. DANH MỤC VAI TRÒ HỆ THỐNG (ROLES)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO roles (role_id, role_name, display_name, description, is_system, is_active) VALUES
(1, 'ADMIN',       'Quản trị viên hệ thống', 'Toàn quyền cấu hình, nhân sự và quản trị toàn hệ thống', 1, 1),
(2, 'BAC_SI',      'Bác sĩ nha khoa',        'Khám, chẩn đoán, điều trị, chỉ định thuốc và phản hồi CSKH', 1, 1),
(3, 'LE_TAN',      'Lễ tân & CSKH',          'Tiếp đón, lập lịch, gọi điện CSKH, xử lý chat trực tuyến', 1, 1),
(4, 'QUAN_LY_KHO', 'Quản lý kho vật tư',     'Lập phiếu nhập kho, xuất kho, điều chuyển kho và kiểm kê', 1, 1),
(5, 'KE_TOAN',     'Kế toán phòng khám',     'Thu ngân, công nợ, hóa đơn, dòng tiền và chi phí', 1, 1),
(6, 'BENH_NHAN',   'Bệnh nhân',              'Cổng thông tin khách hàng, đặt hẹn trực tuyến, xem hồ sơ', 1, 1)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), description = VALUES(description);

-- ----------------------------------------------------------------------------------------------------
-- 3. DANH SÁCH TÀI KHOẢN NGƯỜI DÙNG (USERS)
-- Tài khoản đúng chuẩn định dạng yêu cầu:
-- Admin: admin
-- Cơ sở 1: bacsi01, bacsi02, letan01, quanlykho01, ketoan01
-- Cơ sở 2: bacsi03, bacsi04, letan02, quanlykho02, ketoan02
-- Bệnh nhân: benhnhan01, benhnhan02, benhnhan03, benhnhan04
-- Password chung: Hieu1811
-- ----------------------------------------------------------------------------------------------------
INSERT INTO users (user_id, username, password, email, phone_number, branch_id, user_type, status, is_active, created_date) VALUES
-- Admin hệ thống
(101, 'admin',       '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'admin@cuongvietdental.vn',       '0988888001', 1, 'STAFF',   'ACTIVE', 1, NOW()),

-- Nhân sự Cơ sở 1 (CN01)
(102, 'bacsi01',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bacsi01@cuongvietdental.vn',     '0912001001', 1, 'STAFF',   'ACTIVE', 1, NOW()),
(103, 'bacsi02',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bacsi02@cuongvietdental.vn',     '0912001002', 1, 'STAFF',   'ACTIVE', 1, NOW()),
(104, 'letan01',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'letan01@cuongvietdental.vn',     '0912001003', 1, 'STAFF',   'ACTIVE', 1, NOW()),
(105, 'quanlykho01', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'quanlykho01@cuongvietdental.vn', '0912001004', 1, 'STAFF',   'ACTIVE', 1, NOW()),
(106, 'ketoan01',    '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'ketoan01@cuongvietdental.vn',    '0912001005', 1, 'STAFF',   'ACTIVE', 1, NOW()),

-- Nhân sự Cơ sở 2 (CN02)
(107, 'bacsi03',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bacsi03@cuongvietdental.vn',     '0912002001', 2, 'STAFF',   'ACTIVE', 1, NOW()),
(108, 'bacsi04',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bacsi04@cuongvietdental.vn',     '0912002002', 2, 'STAFF',   'ACTIVE', 1, NOW()),
(109, 'letan02',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'letan02@cuongvietdental.vn',     '0912002003', 2, 'STAFF',   'ACTIVE', 1, NOW()),
(110, 'quanlykho02', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'quanlykho02@cuongvietdental.vn', '0912002004', 2, 'STAFF',   'ACTIVE', 1, NOW()),
(111, 'ketoan02',    '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'ketoan02@cuongvietdental.vn',    '0912002005', 2, 'STAFF',   'ACTIVE', 1, NOW()),

-- Bệnh nhân kiểm thử
(112, 'benhnhan01',  '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'benhnhan01@gmail.com',           '0987654321', 1, 'PATIENT', 'ACTIVE', 1, NOW()),
(113, 'benhnhan02',  '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'benhnhan02@gmail.com',           '0978123456', 1, 'PATIENT', 'ACTIVE', 1, NOW()),
(114, 'benhnhan03',  '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'benhnhan03@gmail.com',           '0965987654', 2, 'PATIENT', 'ACTIVE', 1, NOW()),
(115, 'benhnhan04',  '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'benhnhan04@gmail.com',           '0909112233', 2, 'PATIENT', 'ACTIVE', 1, NOW())
ON DUPLICATE KEY UPDATE password = VALUES(password), email = VALUES(email), phone_number = VALUES(phone_number), status = VALUES(status);

-- ----------------------------------------------------------------------------------------------------
-- 4. PHÂN QUYỀN TÀI KHOẢN (USER_ROLES) & GÁN TOÀN QUYỀN KHO / NHÀ CUNG CẤP CHO QUẢN LÝ KHO
-- ----------------------------------------------------------------------------------------------------
INSERT INTO user_roles (user_id, role_id) VALUES
(101, 1), -- admin -> ADMIN
(102, 2), -- bacsi01 -> BAC_SI
(103, 2), -- bacsi02 -> BAC_SI
(104, 3), -- letan01 -> LE_TAN
(105, 4), -- quanlykho01 -> QUAN_LY_KHO
(106, 5), -- ketoan01 -> KE_TOAN
(107, 2), -- bacsi03 -> BAC_SI
(108, 2), -- bacsi04 -> BAC_SI
(109, 3), -- letan02 -> LE_TAN
(110, 4), -- quanlykho02 -> QUAN_LY_KHO
(111, 5), -- ketoan02 -> KE_TOAN
(112, 6), -- benhnhan01 -> BENH_NHAN
(113, 6), -- benhnhan02 -> BENH_NHAN
(114, 6), -- benhnhan03 -> BENH_NHAN
(115, 6)  -- benhnhan04 -> BENH_NHAN
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- Đảm bảo quyền danh mục Kho & Nhà cung cấp tồn tại và gán đầy đủ cho QUAN_LY_KHO (role_id = 4)
INSERT INTO permissions (permission_id, permission_code, module, description) VALUES
(401, 'INVENTORY_STOCK', 'INVENTORY', 'Quản lý kho vật tư: Tra cứu tồn kho & lập phiếu xuất/nhập kho'),
(402, 'SUPPLIER_MANAGE', 'INVENTORY', 'Nhà cung cấp: Quản lý danh mục nhà cung cấp & công nợ phải trả'),
(403, 'SCHEDULE_MY',     'HR',        'Lịch trực & Chấm công: Xem lịch làm việc cá nhân hàng tuần'),
(404, 'HR_MY_ATTENDANCE','HR',        'Chấm công: Check-in/Check-out hàng ngày & tra cứu lịch sử công')
ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code), module = VALUES(module);

INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES
(4, 401),
(4, 402),
(4, 403),
(4, 404);

-- ----------------------------------------------------------------------------------------------------
-- 5. HỒ SƠ NHÂN SỰ CHI TIẾT (STAFF_PROFILES)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO staff_profiles (staff_id, user_id, staff_code, full_name, gender, dob, phone_number, email, department, position_title, specialty, base_salary, allowance_amount, insurance_salary, status, created_at) VALUES
(101, 101, 'NV-ADMIN',   'Nguyễn Văn An',         'MALE',   '1983-05-10', '0988888001', 'admin@cuongvietdental.vn',       'Ban Giám Đốc',    'Tổng Giám Đốc',             'Quản trị phòng khám',         35000000.00, 5000000.00, 20000000.00, 'ACTIVE', NOW()),

-- Nhân sự CN01
(102, 102, 'BS-CN01-01', 'ThS.BS Lê Hoàng Nam',   'MALE',   '1986-04-15', '0912001001', 'bacsi01@cuongvietdental.vn',     'Khối Lâm sàng',   'Bác sĩ Chuyên khoa RHM',    'Cấy ghép Implant & Tiểu phẫu', 28000000.00, 4000000.00, 20000000.00, 'ACTIVE', NOW()),
(103, 103, 'BS-CN01-02', 'BS.CKI Trần Mai Hoa',  'FEMALE', '1990-11-20', '0912001002', 'bacsi02@cuongvietdental.vn',     'Khối Lâm sàng',   'Bác sĩ Chuyên khoa RHM',    'Chỉnh nha - Niềng răng',      26000000.00, 3500000.00, 18000000.00, 'ACTIVE', NOW()),
(104, 104, 'LT-CN01-01', 'Hoàng Thu Trang',       'FEMALE', '1997-08-25', '0912001003', 'letan01@cuongvietdental.vn',     'Lễ tân & CSKH',   'Trưởng nhóm Lễ tân & CSKH', 'Chăm sóc khách hàng nha khoa', 12000000.00, 1500000.00, 10000000.00, 'ACTIVE', NOW()),
(105, 105, 'KH-CN01-01', 'Đỗ Minh Quân',          'MALE',   '1993-02-18', '0912001004', 'quanlykho01@cuongvietdental.vn', 'Quản lý Dược/Kho','Thủ kho vật tư nha khoa',    'Quản trị kho y tế & Dược phẩm',11000000.00, 1500000.00, 9000000.00,  'ACTIVE', NOW()),
(106, 106, 'KT-CN01-01', 'Vũ Kim Ngân',           'FEMALE', '1995-12-05', '0912001005', 'ketoan01@cuongvietdental.vn',    'Tài chính - KT',  'Kế toán trưởng chi nhánh',  'Kế toán doanh nghiệp nha khoa', 14000000.00, 2000000.00, 10000000.00, 'ACTIVE', NOW()),

-- Nhân sự CN02
(107, 107, 'BS-CN02-01', 'BSCKII Nguyễn Quốc Tuấn','MALE',  '1982-09-02', '0912002001', 'bacsi03@cuongvietdental.vn',     'Khối Lâm sàng',   'Bác sĩ Phẫu thuật cao cấp', 'Răng khôn mọc kẹt & Phục hình', 30000000.00, 5000000.00, 20000000.00, 'ACTIVE', NOW()),
(108, 108, 'BS-CN02-02', 'ThS.BS Phạm Thúy Nga',  'FEMALE', '1989-06-30', '0912002002', 'bacsi04@cuongvietdental.vn',     'Khối Lâm sàng',   'Bác sĩ Điều trị RHM',       'Nội nha & Nha khoa Thẩm mỹ',   27000000.00, 3500000.00, 18000000.00, 'ACTIVE', NOW()),
(109, 109, 'LT-CN02-01', 'Lê Phương Linh',        'FEMALE', '1998-03-12', '0912002003', 'letan02@cuongvietdental.vn',     'Lễ tân & CSKH',   'Chuyên viên Lễ tân CSKH',   'Chăm sóc khách hàng & Đặt hẹn',10500000.00, 1500000.00, 9000000.00,  'ACTIVE', NOW()),
(110, 110, 'KH-CN02-01', 'Bùi Trọng Đạt',         'MALE',   '1994-07-22', '0912002004', 'quanlykho02@cuongvietdental.vn', 'Quản lý Dược/Kho','Nhân viên Quản lý kho',     'Quản lý vật tư & xuất nhập',  10500000.00, 1500000.00, 9000000.00,  'ACTIVE', NOW()),
(111, 111, 'KT-CN02-01', 'Trần Thu Hà',           'FEMALE', '1996-10-14', '0912002005', 'ketoan02@cuongvietdental.vn',    'Tài chính - KT',  'Chuyên viên Kế toán',       'Kế toán thanh toán & viện phí', 12000000.00, 1500000.00, 10000000.00, 'ACTIVE', NOW())
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name), phone_number = VALUES(phone_number), specialty = VALUES(specialty);

-- ----------------------------------------------------------------------------------------------------
-- 6. PHÂN BỔ NHÂN SỰ VÀO CƠ SỞ (STAFF_BRANCH_ASSIGNMENTS)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO staff_branch_assignments (assignment_id, staff_id, branch_id, role_id, is_primary, start_date, status, created_at) VALUES
(101, 101, 1, 1, 1, CURDATE(), 'ACTIVE', NOW()),
(102, 102, 1, 2, 1, CURDATE(), 'ACTIVE', NOW()),
(103, 103, 1, 2, 1, CURDATE(), 'ACTIVE', NOW()),
(104, 104, 1, 3, 1, CURDATE(), 'ACTIVE', NOW()),
(105, 105, 1, 4, 1, CURDATE(), 'ACTIVE', NOW()),
(106, 106, 1, 5, 1, CURDATE(), 'ACTIVE', NOW()),

(107, 107, 2, 2, 1, CURDATE(), 'ACTIVE', NOW()),
(108, 108, 2, 2, 1, CURDATE(), 'ACTIVE', NOW()),
(109, 109, 2, 3, 1, CURDATE(), 'ACTIVE', NOW()),
(110, 110, 2, 4, 1, CURDATE(), 'ACTIVE', NOW()),
(111, 111, 2, 5, 1, CURDATE(), 'ACTIVE', NOW())
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- ----------------------------------------------------------------------------------------------------
-- 7. HỒ SƠ BỆNH NHÂN (PATIENT_PROFILES)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO patient_profiles (profile_id, user_id, patient_code, full_name, dob, gender, phone_number, address, has_allergies, allergy_details, has_underlying_disease, underlying_disease_details, medical_history_completed, preferred_branch_id, is_default, deposit_balance, status, created_at) VALUES
(101, 112, 'BN-000101', 'Phạm Quốc Bảo',        '1992-06-12', 'Nam', '0987654321', 'Số 15 ngõ 102 Cầu Giấy, Hà Nội',          0, 'Không dị ứng thuốc', 0, 'Không có bệnh nền', 1, 1, 1, 0.00, 'ACTIVE', NOW()),
(102, 113, 'BN-000102', 'Lê Hoàng Phương Thảo', '1998-11-25', 'Nữ',  '0978123456', 'P.402 Chung cư Dịch Vọng, Cầu Giấy, Hà Nội', 1, 'Dị ứng nhẹ Penicillin & Amoxicillin', 0, 'Không', 1, 1, 1, 500000.00, 'ACTIVE', NOW()),
(103, 114, 'BN-000201', 'Nguyễn Minh Khang',    '1985-03-18', 'Nam', '0965987654', 'Số 88 Láng Hạ, Đống Đa, Hà Nội',          0, 'Không', 0, 'Không', 1, 2, 1, 2000000.00, 'ACTIVE', NOW()),
(104, 115, 'BN-000202', 'Trần Ngọc Ánh',        '2002-09-08', 'Nữ',  '0909112233', 'Số 22 Thái Hà, Đống Đa, Hà Nội',          0, 'Không', 0, 'Không', 1, 2, 1, 0.00, 'ACTIVE', NOW())
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name), phone_number = VALUES(phone_number), address = VALUES(address);

-- ----------------------------------------------------------------------------------------------------
-- 8. DANH MỤC NHÀ CUNG CẤP & VẬT TƯ KHO NHA KHOA
-- ----------------------------------------------------------------------------------------------------
INSERT INTO suppliers (supplier_id, supplier_code, supplier_name, tax_code, phone_number, email, address, is_active) VALUES
(1, 'NCC-3M',        'Công ty TNHH Thiết bị & Vật tư Nha khoa 3M Việt Nam', '0101234567', '0901112233', 'contact@3m.com.vn',       'Tòa nhà Landmark 72, Cầu Giấy, Hà Nội', 1),
(2, 'NCC-SEPTODONT', 'Công ty Dược & Vật tư Y tế Septodont Châu Á',         '0102345678', '0902223344', 'sales@septodont.vn',     '88 Nguyễn Chí Thanh, Đống Đa, Hà Nội',  1),
(3, 'NCC-DENTSPLY',  'Công ty Vật tư Nha khoa Dentsply Sirona',             '0103456789', '0903334455', 'support@dentsply.vn',    '25 Lê Duẩn, Quận 1, TP.HCM',           1),
(4, 'NCC-GC',        'Công ty Vật liệu Phục hình & Xi măng GC Dental',      '0104567890', '0904445566', 'service@gcdental.vn',    'Tầng 6 Charmvit, Trần Duy Hưng, Hà Nội', 1)
ON DUPLICATE KEY UPDATE supplier_name = VALUES(supplier_name), phone_number = VALUES(phone_number);

INSERT INTO materials (material_id, material_code, material_name, category, unit, min_stock_threshold, is_active) VALUES
(1, 'VT-COMP-Z350',  'Composite đặc 3M Filtek Z350 XT (Màu A2)', 'Trám răng',        'Ống',  10.000, 1),
(2, 'VT-COMP-A3',    'Composite lỏng 3M Filtek Supreme (Màu A3)','Trám răng',        'Ống',  10.000, 1),
(3, 'VT-TE-SEPTO',   'Thuốc tê Septanest 1:100.000 (Hộp 50 ống)','Thuốc tê',         'Hộp',  5.000,  1),
(4, 'VT-MC-KL3M',    'Bộ mắc cài kim loại tự buộc 3M Unitek',     'Chỉnh nha',        'Bộ',   5.000,  1),
(5, 'VT-DC-NITI',    'Dây cung NiTi tròn kích thước 016',         'Chỉnh nha',        'Gói',  15.000, 1),
(6, 'VT-IMP-MISC1',  'Trụ Implant Đức MIS C1 đường kính 3.75mm',  'Cấy ghép Implant', 'Trụ',  3.000,  1),
(7, 'VT-TH-GANG',    'Găng tay y tế Nitrile không bột (Hộp 100c)','Tiêu hao',        'Hộp',  20.000, 1),
(8, 'VT-TH-KIM30G',  'Kim tiêm nha khoa dùng một lần 30G ngắn',  'Tiêu hao',        'Hộp',  15.000, 1),
(9, 'VT-NN-GUTTA',   'Côn gutta percha nội nha trám bít F1-F3',  'Nội nha',          'Hộp',  8.000,  1),
(10,'VT-PH-ALGIN',   'Bột lấy dấu Alginate Cavex Creamy (500g)', 'Phục hình',        'Gói',  10.000, 1)
ON DUPLICATE KEY UPDATE material_name = VALUES(material_name), category = VALUES(category), unit = VALUES(unit);

-- ----------------------------------------------------------------------------------------------------
-- 9. KỊCH BẢN QUẢN LÝ KHO (INVENTORY DEMO SCENARIOS):
-- Kịch bản 1: Nhập kho NCC-3M và NCC-SEPTODONT về CN01 và CN02
-- Kịch bản 2: Xuất kho tiêu hao nội bộ (INTERNAL_USE) cho ghế điều trị
-- Kịch bản 3: Xuất điều chuyển kho (TRANSFER) từ CN01 sang CN02
-- Kịch bản 4: Lô hàng sắp hết hạn và mặt hàng chạm ngưỡng tồn tối thiểu
-- ----------------------------------------------------------------------------------------------------
-- Bảng tồn kho chi nhánh (branch_inventories)
INSERT INTO branch_inventories (branch_id, material_id, current_stock, last_updated) VALUES
(1, 1, 25.000, NOW()), -- Composite Z350 tại CN01: Tồn tốt (25 ống)
(1, 2, 18.000, NOW()), -- Composite A3 tại CN01: Tồn tốt
(1, 3, 3.000,  NOW()), -- Thuốc tê tại CN01: CẢNH BÁO SẮP HẾT (3 hộp < min 5 hộp)
(1, 4, 12.000, NOW()), -- Mắc cài 3M tại CN01: Tồn 12 bộ
(1, 5, 30.000, NOW()), -- Dây cung NiTi tại CN01: Tồn 30 gói
(1, 6, 8.000,  NOW()), -- Trụ Implant tại CN01: Tồn 8 trụ
(1, 7, 45.000, NOW()), -- Găng tay tại CN01: Tồn 45 hộp
(1, 8, 35.000, NOW()), -- Kim tiêm tại CN01: Tồn 35 hộp
(1, 9, 15.000, NOW()), -- Côn nội nha tại CN01: Tồn 15 hộp
(1, 10,22.000, NOW()), -- Bột lấy dấu tại CN01: Tồn 22 gói

(2, 1, 15.000, NOW()), -- Composite Z350 tại CN02
(2, 3, 10.000, NOW()), -- Thuốc tê tại CN02
(2, 4, 2.000,  NOW()), -- Mắc cài tại CN02: CẢNH BÁO SẮP HẾT (2 bộ < min 5 bộ)
(2, 6, 4.000,  NOW()), -- Trụ Implant tại CN02: Tồn 4 trụ
(2, 7, 28.000, NOW())  -- Găng tay tại CN02
ON DUPLICATE KEY UPDATE current_stock = VALUES(current_stock), last_updated = NOW();

-- Lô hàng chi tiết (material_batches)
INSERT INTO material_batches (batch_id, material_id, branch_id, batch_number, expiry_date, quantity, unit_price, is_active, created_at) VALUES
(1, 1, 1, 'LOT-3M-2026A',  DATE_ADD(CURDATE(), INTERVAL 540 DAY), 25.000, 380000.00,  1, NOW()),
(2, 3, 1, 'LOT-SEP-2026B', DATE_ADD(CURDATE(), INTERVAL 15 DAY),  3.000,  620000.00,  1, NOW()), -- LÔ SẮP HẾT HẠN (Còn 15 ngày)
(3, 4, 1, 'LOT-3M-MC26',   DATE_ADD(CURDATE(), INTERVAL 720 DAY), 12.000, 2500000.00, 1, NOW()),
(4, 6, 1, 'LOT-MIS-2027',  DATE_ADD(CURDATE(), INTERVAL 900 DAY), 8.000,  7500000.00, 1, NOW()),
(5, 4, 2, 'LOT-3M-MC26B',  DATE_ADD(CURDATE(), INTERVAL 720 DAY), 2.000,  2500000.00, 1, NOW())
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), expiry_date = VALUES(expiry_date);

-- Phiếu nhập kho (stock_receipts)
INSERT INTO stock_receipts (receipt_id, receipt_code, branch_id, supplier_id, created_by, receipt_date, total_amount, supplier_invoice_no, notes, signed_by_user_id, signed_by_name, signed_at) VALUES
(101, 'PNK-2026-00101', 1, 1, 105, DATE_SUB(CURDATE(), INTERVAL 3 DAY), 39500000.00, 'INV-3M-8899', 'Nhập bổ sung Composite và mắc cài kim loại cho CN01 Cầu Giấy', 105, 'Đỗ Minh Quân', NOW()),
(102, 'PNK-2026-00102', 1, 2, 105, DATE_SUB(CURDATE(), INTERVAL 2 DAY), 18600000.00, 'INV-SEP-4512', 'Nhập thuốc tê Septanest phục vụ tiểu phẫu nhổ răng khôn', 105, 'Đỗ Minh Quân', NOW()),
(103, 'PNK-2026-00201', 2, 1, 110, DATE_SUB(CURDATE(), INTERVAL 1 DAY), 25000000.00, 'INV-3M-9011', 'Nhập vật tư phục vụ khai trương phòng chỉnh nha CN02 Đống Đa', 110, 'Bùi Trọng Đạt', NOW())
ON DUPLICATE KEY UPDATE total_amount = VALUES(total_amount), notes = VALUES(notes);

-- Chi tiết phiếu nhập kho (stock_receipt_items)
INSERT INTO stock_receipt_items (receipt_item_id, receipt_id, material_id, batch_number, expiry_date, quantity, unit_price) VALUES
(1011, 101, 1, 'LOT-3M-2026A',  DATE_ADD(CURDATE(), INTERVAL 540 DAY), 25.000, 380000.00),
(1012, 101, 4, 'LOT-3M-MC26',   DATE_ADD(CURDATE(), INTERVAL 720 DAY), 12.000, 2500000.00),
(1021, 102, 3, 'LOT-SEP-2026B', DATE_ADD(CURDATE(), INTERVAL 360 DAY), 30.000, 620000.00),
(1031, 103, 4, 'LOT-3M-MC26B',  DATE_ADD(CURDATE(), INTERVAL 720 DAY), 10.000, 2500000.00)
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), unit_price = VALUES(unit_price);

-- Phiếu xuất kho (stock_issues)
INSERT INTO stock_issues (issue_id, issue_code, branch_id, issue_type, issue_date, issue_reason, target_branch_id, created_by, notes, signed_by_user_id, signed_by_name, signed_at) VALUES
(101, 'PXK-2026-00101', 1, 'INTERNAL_USE', CURDATE(), 'Xuất thuốc tê và composite cho buồng tiểu phẫu số 1 CN01', NULL, 105, 'Bác sĩ Lê Hoàng Nam nhận vật tư ca sáng', 105, 'Đỗ Minh Quân', NOW()),
(102, 'PXK-2026-00102', 1, 'TRANSFER',     CURDATE(), 'Điều chuyển gấp 3 bộ mắc cài 3M sang CN02 Đống Đa', 2,    105, 'Chuyển xe nội bộ giữa 2 cơ sở Cầu Giấy và Đống Đa', 105, 'Đỗ Minh Quân', NOW()),
(103, 'PXK-2026-00201', 2, 'DAMAGE_EXPIRED', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'Xuất hủy 2 tuýp composite quá hạn sử dụng', NULL, 110, 'Biên bản hủy vật tư số 04/BB-CN02', 110, 'Bùi Trọng Đạt', NOW())
ON DUPLICATE KEY UPDATE issue_reason = VALUES(issue_reason), notes = VALUES(notes);

-- Chi tiết phiếu xuất kho (stock_issue_items)
INSERT INTO stock_issue_items (issue_item_id, issue_id, material_id, batch_id, quantity, notes) VALUES
(1011, 101, 3, 2, 2.000, 'Xuất 2 hộp thuốc tê Septanest dùng trong ngày'),
(1012, 101, 1, 1, 3.000, 'Xuất 3 ống composite trám răng'),
(1021, 102, 4, 3, 3.000, 'Chuyển kho 3 bộ mắc cài kim loại 3M Unitek sang CN02'),
(1031, 103, 1, 1, 2.000, 'Xuất hủy do rơi vỡ nắp tuýp')
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);

-- ----------------------------------------------------------------------------------------------------
-- 10. KỊCH BẢN CHĂM SÓC KHÁCH HÀNG (CUSTOMER CARE DEMO SCENARIOS):
-- Ca khám lâm sàng & Đơn thuốc làm gốc dữ liệu cho CSKH
-- ----------------------------------------------------------------------------------------------------
INSERT INTO icd10_diseases (icd10_code, disease_name_vi, disease_name_en, is_dental_common, is_active) VALUES
('K01.1', 'Răng ngầm / Răng khôn mọc lệch', 'Impacted teeth', 1, 1),
('K04.0', 'Viêm tủy răng cấp/mạn', 'Pulpitis', 1, 1),
('K02.1', 'Sâu ngà răng', 'Dentin caries', 1, 1),
('K07.2', 'Sai khớp cắn góc loại II', 'Anomalies of dental arch relationship', 1, 1)
ON DUPLICATE KEY UPDATE disease_name_vi = VALUES(disease_name_vi);

-- Lịch hẹn (appointments)
INSERT INTO appointments (appointment_id, appointment_code, profile_id, branch_id, doctor_id, appointment_date, preferred_session, expected_time, initial_symptoms, source, status, reschedule_count, created_at) VALUES
(101, 'LH-2026-0101', 101, 1, 102, DATE_SUB(CURDATE(), INTERVAL 1 DAY), 'MORNING',   '09:00:00', 'Sưng đau nhức góc hàm phải dưới do răng khôn R48', 'ONLINE',       'COMPLETED', 0, NOW()),
(102, 'LH-2026-0102', 102, 1, 102, DATE_SUB(CURDATE(), INTERVAL 2 DAY), 'AFTERNOON', '14:30:00', 'Đau buốt tủy răng R36 về đêm khi ăn uống',            'RECEPTIONIST', 'COMPLETED', 0, NOW()),
(103, 'LH-2026-0201', 103, 2, 107, CURDATE(),                            'MORNING',   '10:00:00', 'Mất răng R46 lâu năm, muốn tư vấn cấy ghép Implant', 'ONLINE',       'COMPLETED', 0, NOW()),
(104, 'LH-2026-0202', 104, 2, 108, DATE_ADD(CURDATE(), INTERVAL 3 DAY), 'AFTERNOON', '15:00:00', 'Tái khám siết dây cung chỉnh nha định kỳ 4 tuần',     'ONLINE',       'CONFIRMED', 0, NOW())
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- Lượt khám (visits)
INSERT INTO visits (visit_id, visit_code, profile_id, branch_id, appointment_id, visit_type, check_in_at, initial_symptoms, primary_doctor_id, status, completed_at, notes, created_at) VALUES
(101, 'VIS-2026-0101', 101, 1, 101, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 24 HOUR), 'Răng khôn R48 mọc lệch 45 độ gây sưng lợi trùm', 102, 'COMPLETED', DATE_SUB(NOW(), INTERVAL 22 HOUR), 'Đã tiểu phẫu nhổ R48 bằng máy siêu âm Piezotome, cầm máu tốt, dặn cắn gạc 45 phút.', NOW()),
(102, 'VIS-2026-0102', 102, 1, 102, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 48 HOUR), 'Đau buốt răng R36 sâu to, gõ đau',              102, 'COMPLETED', DATE_SUB(NOW(), INTERVAL 46 HOUR), 'Đã lấy sạch tủy viêm R36 lần 1, đặt thuốc sát trùng Ca(OH)2 và trám tạm Cavit.', NOW()),
(103, 'VIS-2026-0201', 103, 2, 103, 'APPOINTMENT', DATE_SUB(NOW(), INTERVAL 5 HOUR),  'Mất răng 46, tiêu xương nhẹ',                      107, 'COMPLETED', DATE_SUB(NOW(), INTERVAL 3 HOUR),  'Chụp CT ConeBeam 3D, lập kế hoạch cấy ghép trụ Implant Đức MIS C1.', NOW())
ON DUPLICATE KEY UPDATE status = VALUES(status), notes = VALUES(notes);

-- Đơn thuốc & Lời dặn (#tab-instructions)
INSERT INTO prescriptions (prescription_id, prescription_code, visit_id, profile_id, branch_id, doctor_id, diagnosis_text, advice_notes, followup_date, status, created_at) VALUES
(101, 'TOA-2026-0101', 101, 101, 1, 102,
 '[K01.1] Hậu phẫu tiểu phẫu nhổ răng khôn R48 mọc lệch',
 '1. Cắn chặt gạc vô trùng 45 phút, không khạc nhổ mạnh.\n2. Chườm lạnh má phải 24h đầu để giảm sưng nề.\n3. Ăn cháo nguội, thức ăn mềm lỏng 3 ngày đầu; kiêng đồ cay nóng và rượu bia.\n4. Uống thuốc kháng sinh & giảm đau đúng liều. Tái khám cắt chỉ sau 7 ngày.',
 DATE_ADD(CURDATE(), INTERVAL 6 DAY), 'DISPENSED', DATE_SUB(NOW(), INTERVAL 22 HOUR)),

(102, 'TOA-2026-0102', 102, 102, 1, 102,
 '[K04.0] Điều trị nội nha lấy tủy R36 lần 1 (Bệnh nhân dị ứng Penicillin)',
 '1. Hạn chế nhai thức ăn cứng bên hàm trái đang đặt thuốc.\n2. Uống thuốc giảm đau Paracetamol sau ăn no khi đau (TUYỆT ĐỐI KHÔNG UỐNG AMOXICILLIN do tiền sử dị ứng).\n3. Tái khám sau 5 ngày để hàn kín ống tủy.',
 DATE_ADD(CURDATE(), INTERVAL 3 DAY), 'DISPENSED', DATE_SUB(NOW(), INTERVAL 46 HOUR))
ON DUPLICATE KEY UPDATE advice_notes = VALUES(advice_notes);

-- CÔNG VIỆC CHĂM SÓC KHÁCH HÀNG (customer_care_tasks)
-- Thể hiện trọn vẹn 4 kịch bản điển hình:
-- Kịch bản 1: Hậu phẫu (Đã gọi điện hỏi thăm, hoàn thành)
-- Kịch bản 2: Bệnh nhân báo triệu chứng bất thường -> Nhân viên bấm "Chuyển BS" -> Bác sĩ bấm "Phản hồi"
-- Kịch bản 3: Nhắc lịch hẹn tái khám định kỳ (Đang chờ xử lý)
-- Kịch bản 4: Nhắc dùng thuốc và hướng dẫn chăm sóc sau cấy Implant
INSERT INTO customer_care_tasks (task_id, task_code, profile_id, branch_id, task_type, visit_id, appointment_id, prescription_id, assigned_staff_id, escalated_to_doctor_id, escalated_at, due_date, status, notes, doctor_response) VALUES
(101, 'TASK-CC-00101', 101, 1, 'POST_TREATMENT_FOLLOWUP', 101, 101, 101, 104, NULL, NULL, CURDATE(), 'COMPLETED',
 'Hỏi thăm sức khỏe bệnh nhân Phạm Quốc Bảo sau nhổ răng khôn R48: tình trạng cầm máu, sưng nề má và tuân thủ lời dặn trong #tab-instructions.',
 NULL),

(102, 'TASK-CC-00102', 102, 1, 'MEDICATION_REMINDER',     102, 102, 102, 104, 102, NOW(), CURDATE(), 'ESCALATED_TO_DOCTOR',
 'Bệnh nhân Lê Hoàng Phương Thảo gọi hotline phản ánh còn tức nhẹ răng R36 đặt thuốc và hỏi có được uống viên kháng sinh Amoxil gia đình có sẵn không (Lưu ý hồ sơ ghi dị ứng Penicillin).',
 'Đã xem hồ sơ khám VIS-2026-0102: Răng R36 đặt Ca(OH)2 tức nhẹ 24-48h là phản ứng mô bình thường. Bệnh nhân dị ứng nhóm Penicillin/Amoxicillin nên TUYỆT ĐỐI KHÔNG uống viên Amoxil. Tiếp tục dùng Paracetamol theo toa và chườm ấm ngoài má.'),

(103, 'TASK-CC-00201', 104, 2, 'APPOINTMENT_REMINDER',     NULL, 104, NULL, 109, NULL, NULL, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'PENDING',
 'Nhắc bạn Trần Ngọc Ánh đến đúng giờ hẹn 15:00 ngày kia tại CN02 Đống Đa để Bác sĩ Thúy Nga siết mắc cài niềng răng.',
 NULL),

(104, 'TASK-CC-00202', 103, 2, 'POST_TREATMENT_FOLLOWUP', 103, 103, NULL, 109, NULL, NULL, CURDATE(), 'IN_PROGRESS',
 'Tư vấn sau thăm khám cấy Implant cho anh Nguyễn Minh Khang, gửi kế hoạch điều trị và chi phí trụ Straumann / MIS C1.',
 NULL)
ON DUPLICATE KEY UPDATE status = VALUES(status), doctor_response = VALUES(doctor_response), notes = VALUES(notes);

-- LỊCH SỬ TƯƠNG TÁC CSKH (customer_care_interactions)
INSERT INTO customer_care_interactions (interaction_id, task_id, profile_id, staff_id, channel, contact_result, notes, next_retry_at, created_at) VALUES
(1011, 101, 101, 104, 'PHONE_CALL', 'CONNECTED',
 'Đã gọi điện hỏi thăm anh Bảo: Vết thương nhổ răng khôn R48 đã cầm máu hoàn toàn, má phải chỉ hơi căng nhẹ, ăn được cháo thịt băm nguội, đã uống thuốc theo đơn. Rất hài lòng với dịch vụ.',
 NULL, DATE_SUB(NOW(), INTERVAL 4 HOUR)),

(1021, 102, 102, 104, 'PHONE_CALL', 'CONNECTED',
 'Chị Thảo gọi hỏi về triệu chứng ê tức răng R36. Đã bấm nút [Chuyển BS] trên hệ thống cho BS Lê Hoàng Nam xử lý vì bệnh nhân có tiền sử dị ứng thuốc.',
 NULL, DATE_SUB(NOW(), INTERVAL 2 HOUR)),

(1041, 104, 103, 109, 'ZALO', 'CONNECTED',
 'Đã gửi file PDF phác đồ điều trị Implant qua Zalo cho anh Khang. Khách hẹn phản hồi lại vào buổi chiều.',
 DATE_ADD(NOW(), INTERVAL 4 HOUR), DATE_SUB(NOW(), INTERVAL 1 HOUR))
ON DUPLICATE KEY UPDATE notes = VALUES(notes);

-- ----------------------------------------------------------------------------------------------------
-- 11. KỊCH BẢN CHAT TRỰC TUYẾN (LIVE CHAT & WEBSOCKET DEMO SCENARIOS):
-- Kịch bản 1: Cuộc hội thoại hoàn thành (Bệnh nhân hỏi tư vấn - Lễ tân trả lời đầy đủ)
-- Kịch bản 2: Tin nhắn MỚI CHƯA ĐỌC từ Bệnh nhân -> BẬT BADGE ĐỎ Sidebar "Tin nhắn trực tuyến"
-- Kịch bản 3: Bàn giao ca chat từ Lễ tân sang Bác sĩ chuyên môn (ChatHandoverLog)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO chat_conversations (conversation_id, profile_id, user_id, recipient_user_id, branch_id, channel, status, created_at, last_message_at) VALUES
(101, 101, 112, NULL, 1, 'WEB_WIDGET', 'STAFF_HANDLING', DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR)),
(102, 102, 113, NULL, 1, 'WEB_WIDGET', 'STAFF_HANDLING', DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(103, 103, 114, 102,  2, 'WEB_WIDGET', 'STAFF_HANDLING', DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 20 MINUTE))
ON DUPLICATE KEY UPDATE status = VALUES(status), last_message_at = VALUES(last_message_at);

-- Tin nhắn chat (chat_messages)
INSERT INTO chat_messages (message_id, conversation_id, sender_type, sender_id, message_text, is_read, is_edited, is_recalled, sent_at) VALUES
-- Cuộc trò chuyện 101: Đã đọc hoàn tất
(1011, 101, 'PATIENT', 112, 'Chào phòng khám, em muốn hỏi sau khi nhổ răng khôn bao lâu thì được ăn cơm bình thường ạ?', 1, 0, 0, DATE_SUB(NOW(), INTERVAL 2 HOUR)),
(1012, 101, 'STAFF',   104, 'Dạ chào anh Bảo! Sau 2-3 ngày đầu anh nên ăn cháo nguội hoặc súp mềm; khi vết thương ổn định sau 5-7 ngày anh có thể ăn cơm mềm bên hàm đối diện ạ.', 1, 0, 0, DATE_SUB(NOW(), INTERVAL 1 HOUR)),

-- Cuộc trò chuyện 102: CÓ TIN NHẮN CHƯA ĐỌC -> KÍCH HOẠT BADGE ĐỎ TRÊN GIAO DIỆN
(1021, 102, 'PATIENT', 113, 'Bác sĩ ơi cho em hỏi, em đang đặt thuốc tủy răng R36 mà thấy hơi ê buốt khi nuốt nước bọt thì có sao không ạ?', 0, 0, 0, NOW()),

-- Cuộc trò chuyện 103: Tư vấn Implant & Bàn giao ca chat sang Bác sĩ
(1031, 103, 'PATIENT', 114, 'Chào nha khoa, tôi bị mất răng hàm 46 muốn cấy trụ Implant MIS C1 của Đức, cho tôi hỏi thời gian tích hợp xương là bao lâu?', 1, 0, 0, DATE_SUB(NOW(), INTERVAL 40 MINUTE)),
(1032, 103, 'STAFF',   109, 'Dạ em chào anh Khang! Em xin phép kết nối và bàn giao cuộc hội thoại sang ThS.BS Lê Hoàng Nam - Chuyên gia Cấy ghép Implant để giải đáp chính xác phác đồ cho anh ạ!', 1, 0, 0, DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
(1033, 103, 'STAFF',   102, 'Chào anh Khang, tôi là BS Nam. Với trụ MIS C1 bề mặt xử lý SLA, thời gian tích hợp xương sinh học thông thường từ 8 đến 12 tuần là có thể lấy dấu phục hình răng sứ chắc chắn anh nhé.', 1, 0, 0, DATE_SUB(NOW(), INTERVAL 20 MINUTE))
ON DUPLICATE KEY UPDATE message_text = VALUES(message_text), is_read = VALUES(is_read);

-- Trạng thái đọc của từng User trong hội thoại (chat_conversation_user_states)
INSERT INTO chat_conversation_user_states (conversation_id, user_id, last_read_message_id, unread_count, updated_at) VALUES
(101, 104, 1012, 0, NOW()), -- Lễ tân 01 đã đọc hết hội thoại 101
(102, 104, NULL, 1, NOW()), -- Lễ tân 01 có 1 TIN NHẮN CHƯA ĐỌC tại hội thoại 102 (Hiển thị badge số 1 màu đỏ)
(103, 102, 1033, 0, NOW())  -- Bác sĩ Nam đã đọc hội thoại 103
ON DUPLICATE KEY UPDATE unread_count = VALUES(unread_count);

-- Nhật ký bàn giao ca chat (chat_handover_logs)
INSERT INTO chat_handover_logs (log_id, conversation_id, from_user_id, to_user_id, reason, handover_at) VALUES
(101, 103, 109, 102, 'Khách hàng yêu cầu tư vấn chuyên sâu về thời gian lành thương và phác đồ cấy ghép trụ Implant Đức MIS C1', DATE_SUB(NOW(), INTERVAL 30 MINUTE))
ON DUPLICATE KEY UPDATE reason = VALUES(reason);

SET FOREIGN_KEY_CHECKS = 1;

-- ====================================================================================================
-- KẾT THÚC KHỞI TẠO DỮ LIỆU THÀNH CÔNG!
-- ====================================================================================================
