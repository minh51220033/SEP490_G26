-- ====================================================================================================
-- CV-DCMS: 03_branches_config.sql (NHÓM MASTER DATA: CHI NHÁNH, PHÒNG, GHẾ, CA KHÁM, THUẾ, NGÂN HÀNG, CA TRỰC)
-- Đối chiếu 100% với Schema cv_dcms_db_backup.sql
-- An toàn khi chạy nhiều lần (Idempotent - ON DUPLICATE KEY UPDATE)
-- ====================================================================================================

SET NAMES utf8mb4;

-- 1. CHI NHÁNH PHÒNG KHÁM (branches)
INSERT INTO branches (branch_id, branch_code, branch_name, address, phone, email, tax_code, opening_time, closing_time, working_days, allowed_ip_range, image_url, tv_config_json, is_active, created_at, updated_at) VALUES
    (1, 'CN01', 'Cường Việt Dental - Cơ sở 1 (Cầu Giấy)', 'Số 128 Cầu Giấy, Phường Quan Hoa, Quận Cầu Giấy, Hà Nội', '02439988771', 'caugiay@cuongvietdental.vn', '0108889991', '08:00:00', '20:00:00', 'T2-CN', '127.0.0.1,::1,192.168.1.0/24', '/images/clinic-branch-1.jpg', '{"voiceEnabled":true,"theme":"DARK_BLUE","refreshSeconds":10,"marqueeText":"Nha khoa Quốc tế Cường Việt kính chào Quý khách!"}', 1, NOW(), NOW()),
    (2, 'CN02', 'Cường Việt Dental - Cơ sở 2 (Đống Đa)',  'Số 45 Láng Hạ, Phường Thành Công, Quận Đống Đa, Hà Nội',  '02439988772', 'dongda@cuongvietdental.vn',  '0108889992', '08:00:00', '20:00:00', 'T2-CN', '127.0.0.1,::1,192.168.1.0/24', '/images/clinic-branch-2.jpg', '{"voiceEnabled":true,"theme":"DARK_BLUE","refreshSeconds":10,"marqueeText":"Nha khoa Quốc tế Cường Việt - Cơ sở Đống Đa kính chào Quý khách!"}', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    branch_name    = VALUES(branch_name),
    address        = VALUES(address),
    phone          = VALUES(phone),
    email          = VALUES(email),
    opening_time   = VALUES(opening_time),
    closing_time   = VALUES(closing_time),
    working_days   = VALUES(working_days),
    tv_config_json = VALUES(tv_config_json),
    is_active      = VALUES(is_active),
    updated_at     = NOW();

-- 2. PHÒNG CHỨC NĂNG (rooms)
INSERT INTO rooms (room_id, branch_id, room_code, room_name, room_type, status) VALUES
    (1, 1, 'P101', 'Phòng Khám & Tư vấn Tổng quát 1',      'EXAM',      'ACTIVE'),
    (2, 1, 'P102', 'Phòng Tiểu phẫu & Cấy ghép Implant',   'TREATMENT', 'ACTIVE'),
    (3, 1, 'P103', 'Phòng Chỉnh nha & Thẩm mỹ Răng sứ',    'TREATMENT', 'ACTIVE'),
    (4, 1, 'P104', 'Phòng Chụp X-Quang Kỹ thuật số CT 3D', 'XRAY',      'ACTIVE'),
    (5, 2, 'P201', 'Phòng Khám Đa khoa RHM Cơ sở 2',       'EXAM',      'ACTIVE'),
    (6, 2, 'P202', 'Phòng Điều trị Kỹ thuật cao Cơ sở 2',  'TREATMENT', 'ACTIVE'),
    (7, 2, 'P203', 'Phòng X-Quang Kỹ thuật số Cơ sở 2',    'XRAY',      'ACTIVE')
ON DUPLICATE KEY UPDATE
    room_name = VALUES(room_name),
    room_type = VALUES(room_type),
    status    = VALUES(status);

-- 3. GHẾ RĂNG LÂM SÀNG (dental_chairs)
INSERT INTO dental_chairs (chair_id, branch_id, room_id, chair_code, chair_name, status) VALUES
    (1, 1, 1, 'G01',  'Ghế khám Tổng quát 01 (Osstem K3)',      'AVAILABLE'),
    (2, 1, 3, 'G02',  'Ghế Chỉnh nha 02 (Belmont Clesta)',       'AVAILABLE'),
    (3, 1, 2, 'G03',  'Ghế Cấy ghép Implant 03 (Sirona Intego)', 'AVAILABLE'),
    (4, 1, 2, 'G04',  'Ghế Tiểu phẫu Răng khôn 04',              'AVAILABLE'),
    (5, 1, 1, 'G05',  'Ghế Nội nha Chữa tủy 05',                 'AVAILABLE'),
    (6, 1, 3, 'G06',  'Ghế Lấy cao răng & Thẩm mỹ 06',           'AVAILABLE'),
    (7, 2, 5, 'G201', 'Ghế khám Tổng quát CN2 - 01',             'AVAILABLE'),
    (8, 2, 6, 'G202', 'Ghế điều trị Kỹ thuật cao CN2 - 02',      'AVAILABLE')
ON DUPLICATE KEY UPDATE
    chair_name = VALUES(chair_name),
    room_id    = VALUES(room_id),
    status     = VALUES(status);

-- 4. CẤU HÌNH KHUNG GIỜ ĐẶT LỊCH HẸN (appointment_session_windows)
INSERT INTO appointment_session_windows (window_id, branch_id, preferred_session, start_time, end_time, is_active) VALUES
    (1, 1, 'MORNING',   '08:00:00', '12:00:00', 1),
    (2, 1, 'AFTERNOON', '13:30:00', '17:30:00', 1),
    (3, 1, 'EVENING',   '17:30:00', '20:00:00', 1),
    (4, 1, 'ANY',       '08:00:00', '20:00:00', 1),
    (5, 2, 'MORNING',   '08:00:00', '12:00:00', 1),
    (6, 2, 'AFTERNOON', '13:30:00', '17:30:00', 1),
    (7, 2, 'EVENING',   '17:30:00', '20:00:00', 1),
    (8, 2, 'ANY',       '08:00:00', '20:00:00', 1)
ON DUPLICATE KEY UPDATE
    start_time = VALUES(start_time),
    end_time   = VALUES(end_time),
    is_active  = VALUES(is_active);

-- 5. CẤU HÌNH CA LÀM VIỆC NHÂN SỰ (work_shifts)
INSERT INTO work_shifts (shift_id, branch_id, shift_code, shift_name, start_time, end_time, grace_minutes, is_overnight, is_active, created_at) VALUES
    (1, 1, 'CA-SANG-CN1',  'Ca Sáng (08:00 - 12:00)',       '08:00:00', '12:00:00', 15, 0, 1, NOW()),
    (2, 1, 'CA-CHIEU-CN1', 'Ca Chiều (13:30 - 17:30)',      '13:30:00', '17:30:00', 15, 0, 1, NOW()),
    (3, 1, 'CA-TOI-CN1',   'Ca Tối (17:30 - 20:00)',        '17:30:00', '20:00:00', 10, 0, 1, NOW()),
    (4, 1, 'CA-HC-CN1',    'Ca Hành chính (08:00 - 17:30)', '08:00:00', '17:30:00', 15, 0, 1, NOW()),
    (5, 2, 'CA-SANG-CN2',  'Ca Sáng CN2 (08:00 - 12:00)',   '08:00:00', '12:00:00', 15, 0, 1, NOW()),
    (6, 2, 'CA-CHIEU-CN2', 'Ca Chiều CN2 (13:30 - 17:30)',  '13:30:00', '17:30:00', 15, 0, 1, NOW()),
    (7, 2, 'CA-HC-CN2',    'Ca Hành chính CN2 (08:00-17:30)','08:00:00','17:30:00', 15, 0, 1, NOW())
ON DUPLICATE KEY UPDATE
    shift_name    = VALUES(shift_name),
    start_time    = VALUES(start_time),
    end_time      = VALUES(end_time),
    grace_minutes = VALUES(grace_minutes),
    is_active     = VALUES(is_active);

-- 6. TÀI KHOẢN NGÂN HÀNG CHI NHÁNH - VIETQR (branch_bank_accounts)
INSERT INTO branch_bank_accounts (account_id, branch_id, bank_code, bank_name, account_number, account_holder, qr_template, is_default, is_active, notes, created_at, updated_at) VALUES
    (1, 1, 'MB',   'Ngân hàng TMCP Quân Đội (MBBank)',        '0888999111',  'CONG TY TNHH NHA KHOA CUONG VIET CN1', 'compact2', 1, 1, 'Tài khoản nhận thanh toán VietQR chính thức Cơ sở 1 Cầu Giấy', NOW(), NOW()),
    (2, 1, 'VCB',  'Ngân hàng TMCP Ngoại Thương (Vietcombank)','001100998877','CONG TY TNHH NHA KHOA CUONG VIET CN1', 'compact2', 0, 1, 'Tài khoản dự phòng Cơ sở 1', NOW(), NOW()),
    (3, 2, 'TCB',  'Ngân hàng TMCP Kỹ Thương (Techcombank)',   '190388899922','CONG TY TNHH NHA KHOA CUONG VIET CN2', 'compact2', 1, 1, 'Tài khoản nhận thanh toán VietQR chính thức Cơ sở 2 Đống Đa', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    bank_name      = VALUES(bank_name),
    account_number = VALUES(account_number),
    account_holder = VALUES(account_holder),
    is_active      = VALUES(is_active),
    updated_at     = NOW();

-- 7. CẤU HÌNH THUẾ CHI NHÁNH (branch_tax_configs)
INSERT INTO branch_tax_configs (config_id, branch_id, entity_type, tax_regime, service_tax_rate, medicine_tax_rate, cit_rate, effective_from, effective_to, legal_reference, notes, created_at) VALUES
    (1, 1, 'COMPANY', 'CORPORATE_DEDUCTION', 0.00, 5.00, 20.00, '2025-01-01', NULL, 'Thông tư 219/2013/TT-BTC (Dịch vụ y tế khám chữa bệnh thuộc đối tượng không chịu thuế GTGT; Dược phẩm 5%)', 'Cấu hình thuế chuẩn cho Cơ sở 1 Cầu Giấy', NOW()),
    (2, 2, 'COMPANY', 'CORPORATE_DEDUCTION', 0.00, 5.00, 20.00, '2025-01-01', NULL, 'Thông tư 219/2013/TT-BTC', 'Cấu hình thuế chuẩn cho Cơ sở 2 Đống Đa', NOW())
ON DUPLICATE KEY UPDATE
    service_tax_rate  = VALUES(service_tax_rate),
    medicine_tax_rate = VALUES(medicine_tax_rate),
    cit_rate          = VALUES(cit_rate),
    legal_reference   = VALUES(legal_reference);

-- 8. HẠNG THÀNH VIÊN & KHUYẾN MÃI (membership_tiers & promotions)
INSERT INTO membership_tiers (tier_id, tier_code, tier_name, min_total_spent, discount_percent, spend_per_point, point_value, display_order, is_active) VALUES
    (1, 'STANDARD', 'Thành viên mới',  0.00,        0.00,  10000.00, 1000.00, 1, 1),
    (2, 'SILVER',   'Thành viên Bạc',  5000000.00,  5.00,  10000.00, 1000.00, 2, 1),
    (3, 'GOLD',     'Thành viên Vàng', 20000000.00, 10.00, 10000.00, 1000.00, 3, 1),
    (4, 'DIAMOND',  'Kim Cương VIP',   50000000.00, 15.00, 10000.00, 1000.00, 4, 1)
ON DUPLICATE KEY UPDATE
    tier_name        = VALUES(tier_name),
    min_total_spent  = VALUES(min_total_spent),
    discount_percent = VALUES(discount_percent),
    is_active        = VALUES(is_active);

INSERT INTO promotions (promotion_id, promo_code, promo_name, description, promo_type, discount_value, max_discount_amount, min_bill_amount, start_date, end_date, usage_limit, used_count, branch_id, is_active) VALUES
    (1, 'WELCOME2026', 'Ưu đãi Khách hàng mới 10%',      'Giảm 10% tối đa 500.000đ cho hóa đơn từ 500.000đ', 'PERCENTAGE',   10.00,     500000.00, 500000.00,   '2025-01-01 00:00:00', '2027-12-31 23:59:59', 1000, 0, NULL, 1),
    (2, 'IMPLANT2M',   'Trợ giá Cấy ghép Implant 2 Triệu','Giảm trực tiếp 2.000.000đ cho hóa đơn từ 15.000.000đ','FIXED_AMOUNT', 2000000.00,2000000.00,15000000.00, '2025-01-01 00:00:00', '2027-12-31 23:59:59', 500,  0, NULL, 1)
ON DUPLICATE KEY UPDATE
    promo_name     = VALUES(promo_name),
    discount_value = VALUES(discount_value),
    is_active      = VALUES(is_active);
