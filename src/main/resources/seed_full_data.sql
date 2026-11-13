-- =============================================================================
-- SEED FULL DATA FOR CV-DCMS (MASTER DATA + DEMO OPERATIONAL DATA)
-- An toàn chạy lại nhiều lần trên MySQL 8.0 (Idempotent 100%, không dùng DROP/TRUNCATE/DELETE).
-- Mật khẩu dùng chung cho TẤT CẢ tài khoản mẫu: admin123
-- Hash BCrypt (cost 10): $2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe
-- =============================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- 0. ĐẢM BẢO VAI TRÒ (ROLES), QUYỀN (PERMISSIONS) & PHÂN QUYỀN (ROLE_PERMISSIONS)
-- (Phòng trường hợp nạp file SQL trực tiếp vào DB mới trước khi khởi động Spring Boot)
-- =============================================================================
INSERT INTO roles (role_name, display_name, description, is_system, is_active)
SELECT 'ADMIN', 'Quản trị viên hệ thống', 'Toàn quyền quản trị hệ thống, tài khoản, nhân sự và đa chi nhánh.', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_name = 'ADMIN');

INSERT INTO roles (role_name, display_name, description, is_system, is_active)
SELECT 'BAC_SI', 'Bác sĩ nha khoa', 'Khám lâm sàng, chẩn đoán ICD-10, lập phác đồ điều trị, kê đơn thuốc và xem lịch trực.', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_name = 'BAC_SI');

INSERT INTO roles (role_name, display_name, description, is_system, is_active)
SELECT 'LE_TAN', 'Lễ tân & Tiếp đón', 'Tiếp đón bệnh nhân, quản lý lịch hẹn, hồ sơ bệnh nhân, tra cứu hạng thẻ và CSKH.', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_name = 'LE_TAN');

INSERT INTO roles (role_name, display_name, description, is_system, is_active)
SELECT 'QUAN_LY_KHO', 'Quản lý kho vật tư', 'Quản lý tồn kho vật tư nha khoa, phiếu nhập/xuất kho và danh mục nhà cung cấp.', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_name = 'QUAN_LY_KHO');

INSERT INTO roles (role_name, display_name, description, is_system, is_active)
SELECT 'KE_TOAN', 'Kế toán & Thu ngân', 'Quản lý hóa đơn viện phí, thu tiền, công nợ bệnh nhân và xử lý hoàn tiền tại chi nhánh.', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_name = 'KE_TOAN');

INSERT INTO roles (role_name, display_name, description, is_system, is_active)
SELECT 'BENH_NHAN', 'Bệnh nhân', 'Tài khoản cổng thông tin bệnh nhân (đặt lịch hẹn trực tuyến, tra cứu hồ sơ khám cá nhân).', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_name = 'BENH_NHAN');

INSERT INTO permissions (permission_code, module, description) VALUES
('PATIENT_PROFILE_MANAGE', 'RECEPTION', 'Quản lý khách hàng: Tiếp nhận bệnh nhân & quản lý hồ sơ hành chính'),
('RECEPTION_MEMBERSHIPS', 'RECEPTION', 'Thẻ bệnh nhân: Tra cứu thẻ thành viên & điểm tích lũy'),
('APPOINTMENT_MANAGE', 'RECEPTION', 'Quản lý lịch hẹn: Xem danh sách, tạo & quản lý lịch hẹn khám'),
('RECEPTION_CHECKIN', 'RECEPTION', 'Tiếp đón & Check-in: Check-in bệnh nhân & phân luồng tiếp đón'),
('APPOINTMENT_VIEW', 'RECEPTION', 'Hàng chờ gọi số: Theo dõi hàng chờ khám tại phòng khám'),
('RECEPTION_DISPATCH', 'RECEPTION', 'Điều phối ghế nha (Lễ tân): Bảng Kanban điều phối bệnh nhân vào ghế khám'),
('CUSTOMER_CARE_MANAGE', 'RECEPTION', 'Chăm sóc khách hàng: Quản lý nhắc hẹn sau điều trị & CSKH'),
('CLINICAL_DAILY_VISITS', 'CLINICAL', 'Phiên khám & Chẩn đoán: Xem danh sách ca khám & thực hiện phiên khám'),
('CLINICAL_WORKFLOWS', 'CLINICAL', 'Quy trình kỹ thuật: Thiết lập & thực hiện quy trình kỹ thuật lâm sàng Bộ Y Tế'),
('CLINICAL_DISPATCH', 'CLINICAL', 'Điều phối ghế điều trị: Theo dõi & điều phối ca khám ghế nha'),
('PRESCRIPTION_WRITE', 'CLINICAL', 'Đơn thuốc & Dặn dò: Kê đơn thuốc & dặn dò bệnh nhân sau điều trị'),
('TREATMENT_PLAN_MANAGE', 'CLINICAL', 'Kế hoạch điều trị: Lập phác đồ điều trị nha khoa nhiều giai đoạn'),
('CLINICAL_PHOTOS', 'CLINICAL', 'Ảnh chụp lâm sàng: Xem & tải hình ảnh khoang miệng trước/sau điều trị'),
('CLINICAL_XRAY', 'CLINICAL', 'Phim X-quang NanoPix: Trình xem và đối chiếu ảnh chụp X-quang'),
('MEDICAL_RECORD_VIEW', 'CLINICAL', 'Hồ sơ bệnh án điện tử (EMR): Xem bệnh án điện tử EMR & lịch sử điều trị'),
('CLINICAL_LABO', 'CLINICAL', 'Phiếu đặt xưởng Labo: Quản lý phiếu gửi mẫu chế tác răng sứ'),
('LABO_SUPPLIERS', 'CLINICAL', 'Nhà cung cấp Labo: Quản lý danh sách đối tác xưởng gia công răng sứ'),
('LABO_PRICING', 'CLINICAL', 'Bảng giá Labo: Quản lý đơn giá gia công phôi sứ & khí cụ nha khoa'),
('FINANCE_DASHBOARD', 'FINANCE', 'Tổng quan viện phí: Bảng điều khiển tài chính, viện phí & công nợ khách hàng'),
('INVOICE_CREATE', 'FINANCE', 'Hóa đơn viện phí: Lập hóa đơn viện phí mới & áp dụng voucher'),
('FINANCE_DEBT', 'FINANCE', 'Công nợ khách hàng: Theo dõi số dư nợ viện phí & trả góp'),
('FINANCE_DEPOSIT', 'FINANCE', 'Tiền tạm ứng (Đặt cọc): Quản lý thu/hoàn tiền đặt cọc tạm ứng của bệnh nhân'),
('REFUND_MANAGE', 'FINANCE', 'Hoàn tiền viện phí: Tiếp nhận & xử lý yêu cầu hoàn tiền dịch vụ'),
('PAYMENT_COLLECT', 'FINANCE', 'Lịch sử thanh toán: Thu tiền viện phí, gạch nợ & in biên lai'),
('FINANCE_EXPENSES', 'FINANCE', 'Chi phí vận hành: Quản lý phiếu chi & chi phí hoạt động phòng khám'),
('FINANCE_PAYROLL', 'FINANCE', 'Bảng lương nhân sự: Tính lương, phụ cấp và tổng hợp bảng lương tháng'),
('FINANCE_PAYROLL_ADVANCES', 'FINANCE', 'Tạm ứng lương: Quản lý và phê duyệt yêu cầu tạm ứng lương nhân viên'),
('FINANCE_COMMISSION_CONFIG', 'FINANCE', 'Cấu hình hoa hồng: Thiết lập tỷ lệ chia hoa hồng thủ thuật bác sĩ/phụ tá'),
('FINANCE_OWNER_DISTRIBUTIONS', 'FINANCE', 'Phân chia lợi nhuận: Quản lý cổ tức và phân bổ lợi nhuận chủ đầu tư'),
('INVENTORY_STOCK', 'INVENTORY', 'Tồn kho vật tư: Tra cứu tồn kho chi nhánh & cảnh báo chạm ngưỡng'),
('INVENTORY_BATCHES', 'INVENTORY', 'Lô & Hạn dùng: Quản lý số lô vật tư và cảnh báo hạn sử dụng'),
('INVENTORY_RECEIPT', 'INVENTORY', 'Phiếu nhập kho: Lập và quản lý phiếu nhập kho vật tư y tế'),
('INVENTORY_ISSUE', 'INVENTORY', 'Phiếu xuất kho: Lập và quản lý phiếu xuất kho sử dụng điều trị'),
('INVENTORY_TRANSFER', 'INVENTORY', 'Điều chuyển kho: Điều chuyển vật tư giữa các chi nhánh cơ sở'),
('INVENTORY_MATERIALS', 'INVENTORY', 'Danh mục vật tư: Quản lý danh mục mặt hàng vật tư tiêu hao'),
('INVENTORY_CATEGORIES', 'INVENTORY', 'Nhóm vật tư: Quản lý phân nhóm vật tư và đơn vị tính'),
('SUPPLIER_MANAGE', 'INVENTORY', 'NCC & Công nợ: Quản lý danh mục nhà cung cấp & công nợ phải trả'),
('STAFF_MANAGE', 'HR', 'Hồ sơ nhân sự: Quản lý danh sách nhân viên, chứng chỉ hành nghề & hợp đồng'),
('SCHEDULE_MANAGE', 'HR', 'Phân ca làm việc: Xếp lịch phân ca tuần cho nhân viên chi nhánh'),
('HR_BRANCH_ATTENDANCE', 'HR', 'Chấm công chi nhánh: Bảng tổng hợp chấm công thực tế toàn cơ sở'),
('HR_LEAVE_APPROVE', 'HR', 'Duyệt nghỉ phép: Tiếp nhận và phê duyệt đơn xin nghỉ phép/khóa lịch nhân sự'),
('HR_MY_ATTENDANCE', 'HR', 'Chấm công cá nhân: Check-in/Check-out hàng ngày & tra cứu lịch sử công'),
('SCHEDULE_MY', 'HR', 'Lịch trực tuần của tôi: Xem lịch làm việc cá nhân hàng tuần'),
('HR_LEAVE_REQUESTS', 'HR', 'Yêu cầu nghỉ / Khóa lịch: Gửi đơn xin nghỉ phép & khóa lịch khám cá nhân'),
('ACCOUNT_MANAGE', 'SYSTEM', 'Danh sách tài khoản: Tạo tài khoản, khóa/mở khóa tài khoản nhân viên'),
('ROLE_MANAGE', 'SYSTEM', 'Vai trò & Phân quyền: Cấu hình ma trận phân quyền vai trò hệ thống'),
('SYSTEM_BANS', 'SYSTEM', 'Tài khoản bị cấm bình luận: Quản lý danh sách tài khoản bị khóa bình luận'),
('BRANCH_MANAGE', 'SYSTEM', 'Quản lý cơ sở: Quản lý danh sách chi nhánh, phòng khám & ghế nha'),
('CATALOG_SERVICES', 'SYSTEM', 'Danh mục dịch vụ: Quản lý danh mục dịch vụ nha khoa & bảng giá'),
('CATALOG_SERVICE_CATEGORIES', 'SYSTEM', 'Nhóm dịch vụ: Quản lý phân nhóm chuyên khoa dịch vụ nha khoa'),
('CATALOG_MEDICATIONS', 'SYSTEM', 'Danh mục thuốc: Quản lý danh mục thuốc biệt dược & liều dùng'),
('CATALOG_ICD10', 'SYSTEM', 'Danh mục ICD-10: Quản lý bảng mã bệnh quốc tế ICD-10'),
('CATALOG_MEMBERSHIP_TIERS', 'SYSTEM', 'Hạng thẻ & Tích điểm: Quản lý hạng thành viên & quy tắc tích điểm'),
('CATALOG_PROMOTIONS', 'SYSTEM', 'Khuyến mãi & Voucher: Quản lý chương trình ưu đãi & mã giảm giá'),
('CATALOG_NEWS', 'SYSTEM', 'Bài viết tin tức: Đăng tải bài viết tin tức, kiến thức nha khoa'),
('NEWS_COMMENTS_MODERATE', 'SYSTEM', 'Kiểm duyệt bình luận: Kiểm duyệt và quản lý bình luận bài viết tin tức'),
('SYSTEM_AUDIT', 'SYSTEM', 'Nhật ký thao tác: Nhật ký kiểm toán thao tác người dùng hệ thống'),
('FINANCE_REPORTS', 'SYSTEM', 'Tổng quan điều hành (29.1): Báo cáo tổng quan chỉ số điều hành phòng khám'),
('REPORT_FINANCIAL', 'SYSTEM', 'Báo cáo Tài chính & Dòng tiền (29.2): Phân tích doanh thu, thực thu và chi phí'),
('REPORT_DEBTS', 'SYSTEM', 'Báo cáo Công nợ Bệnh nhân: Thống kê và phân tích tuổi nợ viện phí'),
('REPORT_OPERATIONAL', 'SYSTEM', 'Báo cáo Vận hành & Nguồn khách: Thống kê lượt khám và hiệu suất bác sĩ')
ON DUPLICATE KEY UPDATE
    module = VALUES(module),
    description = VALUES(description);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r CROSS JOIN permissions p
WHERE r.role_name = 'ADMIN'
  AND p.permission_code NOT IN ('HR_MY_ATTENDANCE', 'SCHEDULE_MY', 'HR_LEAVE_REQUESTS')
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r JOIN permissions p ON p.permission_code IN (
    'CLINICAL_DAILY_VISITS', 'CLINICAL_WORKFLOWS', 'CLINICAL_DISPATCH',
    'PRESCRIPTION_WRITE', 'TREATMENT_PLAN_MANAGE', 'CLINICAL_PHOTOS',
    'CLINICAL_XRAY', 'CLINICAL_LABO', 'LABO_SUPPLIERS', 'LABO_PRICING', 'MEDICAL_RECORD_VIEW',
    'APPOINTMENT_VIEW', 'RECEPTION_MEMBERSHIPS', 'CUSTOMER_CARE_MANAGE',
    'SCHEDULE_MY', 'HR_MY_ATTENDANCE', 'HR_LEAVE_REQUESTS'
)
WHERE r.role_name = 'BAC_SI'
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r JOIN permissions p ON p.permission_code IN (
    'PATIENT_PROFILE_MANAGE', 'RECEPTION_MEMBERSHIPS', 'APPOINTMENT_MANAGE',
    'RECEPTION_CHECKIN', 'APPOINTMENT_VIEW', 'RECEPTION_DISPATCH',
    'CUSTOMER_CARE_MANAGE', 'SCHEDULE_MY', 'HR_MY_ATTENDANCE'
)
WHERE r.role_name = 'LE_TAN'
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r JOIN permissions p ON p.permission_code IN (
    'FINANCE_DASHBOARD', 'INVOICE_CREATE', 'PAYMENT_COLLECT', 'FINANCE_DEPOSIT',
    'FINANCE_DEBT', 'REFUND_MANAGE', 'FINANCE_EXPENSES', 'FINANCE_PAYROLL',
    'FINANCE_PAYROLL_ADVANCES', 'FINANCE_OWNER_DISTRIBUTIONS',
    'FINANCE_REPORTS', 'REPORT_FINANCIAL', 'REPORT_DEBTS', 'REPORT_OPERATIONAL',
    'SCHEDULE_MY', 'HR_MY_ATTENDANCE', 'HR_LEAVE_REQUESTS'
)
WHERE r.role_name = 'KE_TOAN'
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r JOIN permissions p ON p.permission_code IN (
    'INVENTORY_STOCK', 'INVENTORY_BATCHES', 'INVENTORY_RECEIPT', 'INVENTORY_ISSUE',
    'INVENTORY_TRANSFER', 'INVENTORY_MATERIALS', 'INVENTORY_CATEGORIES', 'SUPPLIER_MANAGE',
    'SCHEDULE_MY', 'HR_MY_ATTENDANCE'
)
WHERE r.role_name = 'QUAN_LY_KHO'
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id);

-- =============================================================================
-- 1. CHI NHÁNH, PHÒNG KHÁM, GHẾ NHA, CA LÀM VIỆC, TÀI KHOẢN NGÂN HÀNG & THUẾ
-- =============================================================================
INSERT IGNORE INTO branches (branch_code, branch_name, address, phone, email, tax_code, opening_time, closing_time, working_days, is_active, created_at, updated_at)
SELECT src.branch_code, src.branch_name, src.address, src.phone, src.email, src.tax_code, src.opening_time, src.closing_time, src.working_days, 1, NOW(), NOW()
FROM (
    SELECT 'CN01' AS branch_code, 'Cơ sở Quận 1'  AS branch_name, '123 Nguyễn Trãi, Quận 1, TP.HCM'     AS address, '0901234567' AS phone, 'cs1@dentalclinic.com' AS email, '0311000111' AS tax_code, '08:00:00' AS opening_time, '20:00:00' AS closing_time, '2,3,4,5,6,7,CN' AS working_days UNION ALL
    SELECT 'CN02',                 'Cơ sở Đống Đa',               '456 Láng Hạ, Đống Đa, Hà Nội',                 '0907654321',          'cs2@dentalclinic.com',          '0102000222',             '08:00:00',                 '20:00:00',                '2,3,4,5,6,7,CN'                UNION ALL
    SELECT 'CN03',                 'Cơ sở Cầu Giấy',              '88 Trần Thái Tông, Cầu Giấy, Hà Nội',          '0908889999',          'cs3@dentalclinic.com',          '0103000333',             '08:00:00',                 '20:00:00',                '2,3,4,5,6,7'
) src
WHERE NOT (
    src.branch_code = 'CN01'
    AND EXISTS (SELECT 1 FROM (SELECT branch_code FROM branches) eb WHERE eb.branch_code = 'CS-MAU-01')
);
INSERT INTO branches (branch_code, branch_name, address, phone, email, tax_code, image_url, opening_time, closing_time, working_days, is_active, created_at, updated_at) VALUES
('CN01', 'Cơ sở Quận 1',   '123 Nguyễn Trãi, Quận 1, TP.HCM',    '0901234567', 'cs1@dentalclinic.com', '0311000111', '/uploads/images/storefront.png', '08:00:00', '20:00:00', '2,3,4,5,6,7,CN', 1, NOW(), NOW()),
('CN02', 'Cơ sở Đống Đa',  '456 Láng Hạ, Đống Đa, Hà Nội',       '0907654321', 'cs2@dentalclinic.com', '0102000222', '/uploads/images/storefront.png', '08:00:00', '20:00:00', '2,3,4,5,6,7,CN', 1, NOW(), NOW()),
('CN03', 'Cơ sở Cầu Giấy', '88 Trần Thái Tông, Cầu Giấy, Hà Nội','0908889999', 'cs3@dentalclinic.com', '0103000333', '/uploads/images/storefront.png', '08:00:00', '20:00:00', '2,3,4,5,6,7',    1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    branch_name = VALUES(branch_name),
    address = VALUES(address),
    phone = VALUES(phone),
    updated_at = NOW();

INSERT IGNORE INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT b.branch_id, r.room_code, r.room_name, r.room_type, 'ACTIVE'
FROM (
    SELECT 'CN01' AS bcode, 'P101' AS room_code, 'Phòng Khám Tổng quát 1'      AS room_name, 'EXAM'      AS room_type UNION ALL
    SELECT 'CN01',          'P102',              'Phòng Điều trị & Tiểu phẫu',               'TREATMENT'              UNION ALL
    SELECT 'CN01',          'P103',              'Phòng Chỉnh nha & Thẩm mỹ',                'TREATMENT'              UNION ALL
    SELECT 'CN01',          'P104',              'Phòng Chụp X-Quang NanoPix',               'XRAY'                   UNION ALL
    SELECT 'CN02',          'P201',              'Phòng Khám Tổng quát Đống Đa',             'EXAM'                   UNION ALL
    SELECT 'CN02',          'P202',              'Phòng Điều trị Đống Đa',                   'TREATMENT'              UNION ALL
    SELECT 'CN03',          'P301',              'Phòng Khám Cầu Giấy',                      'EXAM'
) r
JOIN branches b ON b.branch_code = IF(r.bcode = 'CN01' AND NOT EXISTS (SELECT 1 FROM (SELECT branch_code FROM branches) eb WHERE eb.branch_code = 'CN01'), 'CS-MAU-01', r.bcode);

INSERT IGNORE INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT b.branch_id, rm.room_id, c.chair_code, c.chair_name, c.status
FROM (
    SELECT 'CN01' AS bcode, 'P101' AS rcode, 'G01'  AS chair_code, 'Ghế Khám Tổng Quát 01' AS chair_name, 'AVAILABLE' AS status UNION ALL
    SELECT 'CN01',          'P102',          'G02',                'Ghế Tiểu Phẫu 02',                     'IN_USE'              UNION ALL
    SELECT 'CN01',          'P103',          'G03',                'Ghế Chỉnh Nha 03',                     'AVAILABLE'           UNION ALL
    SELECT 'CN02',          'P201',          'G201',               'Ghế Khám Đống Đa 01',                  'AVAILABLE'           UNION ALL
    SELECT 'CN02',          'P202',          'G202',               'Ghế Điều Trị Đống Đa 02',              'IN_USE'              UNION ALL
    SELECT 'CN03',          'P301',          'G301',               'Ghế Khám Cầu Giấy 01',                 'AVAILABLE'
) c
JOIN branches b ON b.branch_code = c.bcode
JOIN rooms rm ON rm.branch_id = b.branch_id AND rm.room_code = c.rcode;

INSERT IGNORE INTO work_shifts (branch_id, shift_code, shift_name, start_time, end_time, is_active)
SELECT b.branch_id, s.shift_code, s.shift_name, s.start_time, s.end_time, 1
FROM branches b
CROSS JOIN (
    SELECT 'CA_SANG'  AS shift_code, 'Ca Sáng (08:00 - 12:00)'  AS shift_name, '08:00:00' AS start_time, '12:00:00' AS end_time UNION ALL
    SELECT 'CA_CHIEU',               'Ca Chiều (13:30 - 17:30)',               '13:30:00',               '17:30:00'              UNION ALL
    SELECT 'CA_TOI',                 'Ca Tối (17:30 - 20:30)',                 '17:30:00',               '20:30:00'
) s
WHERE b.branch_code IN ('CN01', 'CN02', 'CN03')
  AND NOT EXISTS (SELECT 1 FROM (SELECT branch_id, shift_code FROM work_shifts) ws WHERE ws.branch_id = b.branch_id AND ws.shift_code = s.shift_code);

INSERT IGNORE INTO branch_bank_accounts (branch_id, bank_bin, bank_name, bank_short_name, account_number, account_holder_name, is_default, is_active, notes, created_at, updated_at)
SELECT b.branch_id, '970436', 'Ngân hàng TMCP Ngoại thương Việt Nam', 'Vietcombank',
       CASE b.branch_code WHEN 'CN01' THEN '0071001234561' WHEN 'CN02' THEN '0011001234562' ELSE '0021001234563' END,
       'NHA KHOA CUONG VIET', 1, 1, 'Tài khoản nhận thanh toán QR chi nhánh', NOW(), NOW()
FROM branches b
WHERE b.branch_code IN ('CN01', 'CN02', 'CN03')
  AND NOT EXISTS (SELECT 1 FROM (SELECT branch_id FROM branch_bank_accounts) ba WHERE ba.branch_id = b.branch_id);

INSERT IGNORE INTO tax_policies (policy_code, policy_name, effective_from, personal_relief, dependent_relief, casual_tax_rate, non_resident_tax_rate, insurance_employee_rate, insurance_employer_rate, legal_reference, is_active, created_at) VALUES
('VN_PIT_2026', 'Biểu thuế TNCN lũy tiến & BHXH 2026', '2026-01-01', 11000000.00, 4400000.00, 10.00, 20.00, 10.50, 21.50, 'Nghị quyết 954/2020/UBTVQH14 & Luật BHXH', 1, NOW());

INSERT IGNORE INTO branch_tax_configs (branch_id, entity_type, tax_regime, cit_rate, service_tax_rate, medicine_tax_rate, effective_from, legal_reference, notes, created_at)
SELECT b.branch_id, 'ENTERPRISE', 'CIT_STANDARD', 20.00, 2.00, 0.50, '2026-01-01', 'Thông tư 78/2014/TT-BTC', 'Cấu hình thuế mặc định chi nhánh', NOW()
FROM branches b
WHERE b.branch_code IN ('CN01', 'CN02', 'CN03')
  AND NOT EXISTS (SELECT 1 FROM (SELECT branch_id FROM branch_tax_configs) btc WHERE btc.branch_id = b.branch_id);

-- =============================================================================
-- 2. TÀI KHOẢN MẪU (MẬT KHẨU CHUNG: admin123), GÁN ROLE, HỒ SƠ NHÂN SỰ, CA TRỰC
-- =============================================================================
INSERT INTO users (username, password, email, phone_number, branch_id, user_type, status, is_active, custom_permissions_enabled, created_date, updated_at) VALUES
('admin',     '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'admin@dentalclinic.com',    '0988888888', (SELECT branch_id FROM branches WHERE branch_code = 'CN01' LIMIT 1), 'STAFF',   'ACTIVE', 1, 0, NOW(), NOW()),
('bs_hoang',  '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'bs.hoang@dentalclinic.com', '0901000101', (SELECT branch_id FROM branches WHERE branch_code = 'CN01' LIMIT 1), 'STAFF',   'ACTIVE', 1, 0, NOW(), NOW()),
('bs_huong',  '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'bs.huong@dentalclinic.com', '0901000102', (SELECT branch_id FROM branches WHERE branch_code = 'CN02' LIMIT 1), 'STAFF',   'ACTIVE', 1, 0, NOW(), NOW()),
('letan_01',  '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'letan01@dentalclinic.com',  '0901000201', (SELECT branch_id FROM branches WHERE branch_code = 'CN01' LIMIT 1), 'STAFF',   'ACTIVE', 1, 0, NOW(), NOW()),
('ketoan_01', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'ketoan01@dentalclinic.com', '0901000301', (SELECT branch_id FROM branches WHERE branch_code = 'CN01' LIMIT 1), 'STAFF',   'ACTIVE', 1, 0, NOW(), NOW()),
('kho_01',    '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'kho01@dentalclinic.com',    '0901000401', (SELECT branch_id FROM branches WHERE branch_code = 'CN02' LIMIT 1), 'STAFF',   'ACTIVE', 1, 0, NOW(), NOW()),
('bn_bao',    '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'bao.pham@gmail.com',        '0916040604', (SELECT branch_id FROM branches WHERE branch_code = 'CN01' LIMIT 1), 'PATIENT', 'ACTIVE', 1, 0, NOW(), NOW()),
('bn_thao',   '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'thao.le@gmail.com',         '0916050605', (SELECT branch_id FROM branches WHERE branch_code = 'CN02' LIMIT 1), 'PATIENT', 'ACTIVE', 1, 0, NOW(), NOW()),
('bn_minh',   '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe', 'minh.tran@gmail.com',       '0916060606', (SELECT branch_id FROM branches WHERE branch_code = 'CN02' LIMIT 1), 'PATIENT', 'ACTIVE', 1, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    status = 'ACTIVE',
    is_active = 1,
    updated_at = NOW();

INSERT IGNORE INTO user_roles (user_id, role_id)
SELECT u.user_id, r.role_id
FROM (
    SELECT 'admin'     AS uname, 'ADMIN'       AS rname UNION ALL
    SELECT 'bs_hoang',           'BAC_SI'               UNION ALL
    SELECT 'bs_huong',           'BAC_SI'               UNION ALL
    SELECT 'letan_01',           'LE_TAN'               UNION ALL
    SELECT 'ketoan_01',          'KE_TOAN'              UNION ALL
    SELECT 'kho_01',             'QUAN_LY_KHO'          UNION ALL
    SELECT 'bn_bao',             'BENH_NHAN'            UNION ALL
    SELECT 'bn_thao',            'BENH_NHAN'            UNION ALL
    SELECT 'bn_minh',            'BENH_NHAN'
) m
JOIN users u ON u.username = m.uname
JOIN roles r ON UPPER(r.role_name) = m.rname;

INSERT INTO staff_profiles (user_id, staff_code, full_name, gender, dob, phone_number, email, department, position_title, specialty, practice_license_no, is_tax_resident, contract_type, has_personal_deduction, dependent_count, has_tax_commitment, base_salary, allowance_amount, insurance_salary, hire_date, status, created_at, updated_at)
SELECT u.user_id, s.staff_code, s.full_name, s.gender, s.dob, u.phone_number, u.email, s.dept, s.pos, s.spec, s.cchn, 1, 'FULL_CONTRACT', 1, 0, 0, s.base_sal, s.allow, s.ins_sal, '2025-01-01', 'ACTIVE', NOW(), NOW()
FROM (
    SELECT 'bs_hoang'  AS uname, 'BS-SEED-01'  AS staff_code, 'BS.CKI Trần Minh Hoàng' AS full_name, 'MALE'   AS gender, '1986-04-15' AS dob, 'Lâm sàng'            AS dept, 'Bác sĩ RHM'        AS pos, 'Tiểu phẫu & Nội nha'     AS spec, '001234/BYT-CCHN' AS cchn, 25000000.00 AS base_sal, 3000000.00 AS allow, 18000000.00 AS ins_sal UNION ALL
    SELECT 'bs_huong',            'BS-SEED-02',                'BS. Đỗ Lan Hương',        'FEMALE',            '1990-09-22',            'Lâm sàng',                    'Bác sĩ RHM',               'Chỉnh nha & Thẩm mỹ',             '005678/BYT-CCHN',         22000000.00,             2500000.00,          16000000.00           UNION ALL
    SELECT 'letan_01',            'LT-SEED-01',                'Nguyễn Thị Mai Anh',      'FEMALE',            '1997-08-20',            'Tiếp đón & CSKH',             'Lễ tân & CSKH',            'Tiếp đón bệnh nhân',              NULL,                      10000000.00,             1500000.00,          9000000.00            UNION ALL
    SELECT 'ketoan_01',           'KT-SEED-01',                'Phạm Thanh Thủy',         'FEMALE',            '1993-02-11',            'Tài chính - Kế toán',         'Kế toán thu ngân',         'Kế toán nội bộ',                  NULL,                      14000000.00,             2000000.00,          12000000.00           UNION ALL
    SELECT 'kho_01',              'KHO-SEED-01',               'Lê Mạnh Hùng',            'MALE',              '1994-11-05',            'Kho vật tư',                  'Thủ kho vật tư',           'Quản lý vật tư nha khoa',         NULL,                      11000000.00,             1200000.00,          9500000.00
) s
JOIN users u ON u.username = s.uname
ON DUPLICATE KEY UPDATE
    full_name = VALUES(full_name),
    status = 'ACTIVE',
    updated_at = NOW();

INSERT IGNORE INTO staff_branch_assignments (staff_id, branch_id, role_id, is_primary, start_date, status, created_at)
SELECT sp.staff_id, b.branch_id, r.role_id, 1, '2025-01-01', 'ACTIVE', NOW()
FROM (
    SELECT 'BS-SEED-01'  AS scode, 'CN01' AS bcode, 'BAC_SI'      AS rname UNION ALL
    SELECT 'BS-SEED-02',           'CN02',          'BAC_SI'               UNION ALL
    SELECT 'LT-SEED-01',           'CN01',          'LE_TAN'               UNION ALL
    SELECT 'KT-SEED-01',           'CN01',          'KE_TOAN'              UNION ALL
    SELECT 'KHO-SEED-01',          'CN02',          'QUAN_LY_KHO'
) a
JOIN staff_profiles sp ON sp.staff_code = a.scode
JOIN branches b ON b.branch_code = a.bcode
JOIN roles r ON UPPER(r.role_name) = a.rname
WHERE NOT EXISTS (
    SELECT 1 FROM (SELECT staff_id, branch_id, role_id FROM staff_branch_assignments) sba
    WHERE sba.staff_id = sp.staff_id AND sba.branch_id = b.branch_id AND sba.role_id = r.role_id
);

INSERT IGNORE INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, note, created_at)
SELECT sp.staff_id, b.branch_id, ws.shift_id, CURDATE(), 'SCHEDULED', 'Ca làm việc mặc định trong tuần', NOW()
FROM staff_branch_assignments sba
JOIN staff_profiles sp ON sp.staff_id = sba.staff_id
JOIN branches b ON b.branch_id = sba.branch_id
JOIN work_shifts ws ON ws.branch_id = b.branch_id AND ws.shift_code = 'CA_SANG'
WHERE sba.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM (SELECT staff_id, branch_id, work_date FROM staff_schedules) ss WHERE ss.staff_id = sp.staff_id AND ss.branch_id = b.branch_id AND ss.work_date = CURDATE());

INSERT IGNORE INTO attendance_records (staff_id, branch_id, work_date, check_in_at, check_in_ip, check_out_at, check_out_ip, is_valid_network, worked_minutes, status, notes)
SELECT sp.staff_id, b.branch_id, DATE_SUB(CURDATE(), INTERVAL 1 DAY),
       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '07:55:00'), '192.168.1.10',
       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '17:35:00'), '192.168.1.10',
       1, 520, 'ON_TIME', 'Chấm công hợp lệ tại cơ sở'
FROM staff_branch_assignments sba
JOIN staff_profiles sp ON sp.staff_id = sba.staff_id
JOIN branches b ON b.branch_id = sba.branch_id
WHERE sba.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM (SELECT staff_id, work_date FROM attendance_records) ar WHERE ar.staff_id = sp.staff_id AND ar.work_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY));

INSERT IGNORE INTO leave_requests (staff_id, branch_id, shift_id, leave_type, is_emergency, start_date, end_date, reason, status, submitted_at)
SELECT sp.staff_id, b.branch_id, ws.shift_id, 'ANNUAL', 0, DATE_ADD(CURDATE(), INTERVAL 3 DAY), DATE_ADD(CURDATE(), INTERVAL 3 DAY),
       'Tham dự hội thảo chuyên môn Răng Hàm Mặt', 'PENDING', NOW()
FROM staff_profiles sp
JOIN branches b ON b.branch_code = 'CN01'
JOIN work_shifts ws ON ws.branch_id = b.branch_id AND ws.shift_code = 'CA_CHIEU'
WHERE sp.staff_code = 'BS-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT staff_id, start_date FROM leave_requests) lr WHERE lr.staff_id = sp.staff_id AND lr.start_date = DATE_ADD(CURDATE(), INTERVAL 3 DAY));

-- =============================================================================
-- 3. DANH MỤC DỊCH VỤ (ĐỦ 10 NHÓM), ICD-10, HẠNG THẺ, KHUYẾN MÃI, NHÀ CUNG CẤP, VẬT TƯ & THUỐC
-- =============================================================================
INSERT INTO service_categories (category_code, category_name, category_type, display_order, is_active) VALUES
('CAT_EXAM',    'Khám & Tư vấn',               'CONSULTATION', 1, 1),
('CAT_XRAY',    'Chẩn đoán hình ảnh X-quang',  'XRAY',         2, 1),
('CAT_ENDO',    'Điều trị tủy & Trám răng',    'PROCEDURE',    3, 1),
('CAT_SURG',    'Nhổ răng & Tiểu phẫu',        'PROCEDURE',    4, 1),
('CAT_PROSTH',  'Phục hình răng sứ & Labo',    'PROCEDURE',    5, 1),
('CAT_ORTHO',   'Chỉnh nha - Niềng răng',      'PROCEDURE',    6, 1),
('CAT_IMPLANT', 'Cấy ghép Implant nha khoa',   'PROCEDURE',    7, 1),
('CAT_AESTH',   'Tẩy trắng & Nha khoa thẩm mỹ','PROCEDURE',    8, 1)
ON DUPLICATE KEY UPDATE
    category_name = VALUES(category_name),
    category_type = VALUES(category_type),
    is_active = 1;

INSERT INTO services (category_id, service_code, service_name, image_url, service_type, unit, base_price, surcharge_amount, requires_tooth, is_paraclinical, is_bookable_online, warranty_months, estimated_duration_minutes, description, status, created_at)
SELECT sc.category_id, s.service_code, s.service_name, s.img, s.service_type, s.unit, s.base_price, 0.00, s.req_tooth, s.is_para, 1, s.warranty, s.dur, s.descr, 'ACTIVE', NOW()
FROM (
    SELECT 'CAT_EXAM'    AS ccode, 'DV-KHAM-TQ'     AS service_code, 'Khám & Tư vấn tổng quát'                     AS service_name, '/uploads/images/clinic_room.png'                                AS img, 'CONSULTATION' AS service_type, 'Lượt'       AS unit, 150000.00   AS base_price, 0 AS req_tooth, 0 AS is_para, NULL AS warranty, 20 AS dur, 'Khám tổng quát sức khỏe răng miệng và tư vấn phác đồ.' AS descr UNION ALL
    SELECT 'CAT_EXAM',             'DV-CAOVOI',                      'Lấy cao răng & Đánh bóng siêu âm',                            '/uploads/images/clinic_treatment.png',                                   'PROCEDURE',                    'Hàm',                300000.00,                 0,              0,          NULL,             30,        'Làm sạch mảng bám vôi răng bằng sóng siêu âm êm ái.'            UNION ALL
    SELECT 'CAT_XRAY',             'DV-XQ-QUANH',                    'Chụp X-quang quanh chóp kỹ thuật số',                         '/uploads/images/clinic_treatment.png',                                   'XRAY',                         'Phim',               100000.00,                 1,              1,          NULL,             15,        'Chụp phim X-quang kỹ thuật số khảo sát chân răng.'              UNION ALL
    SELECT 'CAT_XRAY',             'DV-XQ-PANO',                     'Chụp X-quang toàn cảnh Panorama kỹ thuật số',                 '/uploads/images/clinic_treatment.png',                                   'XRAY',                         'Phim',               300000.00,                 0,              1,          NULL,             15,        'Chụp phim toàn cảnh 2 hàm đánh giá răng khôn và xương hàm.'      UNION ALL
    SELECT 'CAT_ENDO',             'DV-TRAM-COMP',                   'Trám răng thẩm mỹ Composite 3M',                              '/uploads/images/clinic_treatment.png',                                   'PROCEDURE',                    'Răng',               450000.00,                 1,              0,          12,               30,        'Phục hồi thân răng sâu bằng vật liệu Composite 3M.'             UNION ALL
    SELECT 'CAT_ENDO',             'DV-NOINHA-RHAM',                 'Điều trị tủy răng hàm (Nội nha)',                             '/uploads/images/clinic_treatment.png',                                   'PROCEDURE',                    'Răng',               1800000.00,                1,              0,          24,               45,        'Làm sạch và hàn kín hệ thống ống tủy răng hàm.'                 UNION ALL
    SELECT 'CAT_SURG',             'DV-NHO-THUONG',                  'Nhổ răng vĩnh viễn lung lay / chân răng',                     '/uploads/images/clinic_treatment.png',                                   'PROCEDURE',                    'Răng',               500000.00,                 1,              0,          NULL,             30,        'Nhổ răng vĩnh viễn bằng kỹ thuật gây tê tại chỗ an toàn.'       UNION ALL
    SELECT 'CAT_SURG',             'DV-NHO-RKHON',                   'Tiểu phẫu nhổ răng khôn mọc lệch Piezotome',                  '/uploads/images/clinic_treatment.png',                                   'PROCEDURE',                    'Răng',               2500000.00,                1,              0,          NULL,             45,        'Nhổ răng khôn không sang chấn bằng máy siêu âm Piezotome.'       UNION ALL
    SELECT 'CAT_AESTH',            'DV-TAY-TRANG',                   'Tẩy trắng răng công nghệ Laser Whitening',                    '/uploads/images/whitening.png',                                          'PROCEDURE',                    'Liệu trình',         2500000.00,                0,              0,          6,                60,        'Tẩy trắng răng hoạt hóa bằng ánh sáng Laser Pola Office.'        UNION ALL
    SELECT 'CAT_PROSTH',           'DV-SU-TITAN',                    'Bọc răng sứ hợp kim Titan CAD/CAM',                           '/uploads/services/e4f1d111-0d3a-4642-95a9-79b669c75ff7.jpg',             'PROCEDURE',                    'Răng',               2500000.00,                1,              0,          36,               60,        'Phục hình mão răng sứ Titan chịu lực nhai tốt.'                 UNION ALL
    SELECT 'CAT_PROSTH',           'DV-SU-ZIRCO',                    'Bọc răng sứ Zirconia cao cấp',                                '/uploads/services/e4f1d111-0d3a-4642-95a9-79b669c75ff7.jpg',             'PROCEDURE',                    'Răng',               4500000.00,                1,              0,          60,               60,        'Phục hình mão răng toàn sứ Zirconia thẩm mỹ cao.'               UNION ALL
    SELECT 'CAT_ORTHO',            'DV-NIENG-KL',                    'Niềng răng mắc cài kim loại tự buộc',                         '/uploads/images/clinic_room.png',                                        'PROCEDURE',                    'Liệu trình',         32000000.00,               0,              0,          36,               60,        'Chỉnh nha toàn hàm với mắc cài tự buộc chuẩn quốc tế.'           UNION ALL
    SELECT 'CAT_IMPLANT',          'DV-IMP-GER',                     'Cấy ghép trụ Implant nha khoa MIS C1 (Đức)',                  '/uploads/images/clinic_treatment.png',                                   'PROCEDURE',                    'Trụ',                22000000.00,               1,              0,          120,              90,        'Phẫu thuật cấy ghép trụ Implant Titanium nguyên chất.'
) s
JOIN service_categories sc ON sc.category_code = s.ccode
ON DUPLICATE KEY UPDATE
    service_name = VALUES(service_name),
    image_url = VALUES(image_url),
    service_type = VALUES(service_type),
    base_price = VALUES(base_price),
    status = 'ACTIVE';

INSERT INTO icd10_diseases (icd10_code, disease_name_vi, disease_name_en, is_dental_common, is_active) VALUES
('K00.6', 'Rối loạn mọc răng (Răng sữa tồn tại lâu / mọc lệch)', 'Disturbances in tooth eruption', 1, 1),
('K01.0', 'Răng mọc ngầm (Embedded teeth)',                      'Embedded teeth',                 1, 1),
('K01.1', 'Răng ngầm / Răng khôn mọc lệch',                      'Impacted teeth',                 1, 1),
('K02.0', 'Sâu men răng',                                        'Caries limited to enamel',       1, 1),
('K02.1', 'Sâu ngà răng',                                        'Dentine caries',                 1, 1),
('K03.0', 'Mòn răng quá mức (Mòn cổ răng)',                      'Excessive attrition of teeth',   1, 1),
('K03.6', 'Cặn bám trên răng (Cao răng)',                        'Deposits on teeth',              1, 1),
('K04.0', 'Viêm tủy răng',                                       'Pulpitis',                       1, 1),
('K04.1', 'Hoại tử tủy răng',                                    'Necrosis of pulp',               1, 1),
('K04.4', 'Viêm quanh cuống răng cấp tính',                      'Acute apical periodontitis',     1, 1),
('K05.0', 'Viêm nướu cấp tính',                                  'Acute gingivitis',               1, 1),
('K05.1', 'Viêm nướu mạn tính',                                  'Chronic gingivitis',             1, 1),
('K05.3', 'Viêm nha chu mạn tính',                               'Chronic periodontitis',          1, 1),
('K07.3', 'Răng mọc lệch vị trí (Chen chúc, thưa)',              'Anomalies of tooth position',    1, 1),
('K08.1', 'Mất răng do tai nạn, nhổ răng hoặc bệnh nha chu',     'Loss of teeth',                  1, 1)
ON DUPLICATE KEY UPDATE
    disease_name_vi = VALUES(disease_name_vi),
    is_active = 1;

INSERT INTO membership_tiers (tier_code, tier_name, min_total_spent, discount_percent, spend_per_point, point_value, display_order, is_active) VALUES
('STANDARD', 'Thành viên mới',  0.00,        0.00,  10000.00, 1000.00, 1, 1),
('SILVER',   'Thành viên Bạc',  5000000.00,  5.00,  10000.00, 1000.00, 2, 1),
('GOLD',     'Thành viên Vàng', 20000000.00, 10.00, 10000.00, 1000.00, 3, 1),
('DIAMOND',  'Kim Cương VIP',   50000000.00, 15.00, 10000.00, 1000.00, 4, 1)
ON DUPLICATE KEY UPDATE
    tier_name = VALUES(tier_name),
    is_active = 1;

INSERT IGNORE INTO promotions (promo_code, promo_name, description, branch_id, promo_type, discount_value, max_discount_amount, min_bill_amount, start_date, end_date, usage_limit, used_count, is_active) VALUES
('CHAOHE2026', 'Ưu đãi chăm sóc răng miệng 10%', 'Giảm 10% tối đa 500.000đ cho hóa đơn từ 1.000.000đ', NULL, 'PERCENTAGE',   10.00,     500000.00, 1000000.00, DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 90 DAY), 200, 1, 1),
('TRIAN200K',  'Voucher tri ân 200.000đ',         'Giảm trực tiếp 200.000đ cho hóa đơn từ 1.500.000đ', NULL, 'FIXED_AMOUNT', 200000.00, 200000.00, 1500000.00, DATE_SUB(CURDATE(), INTERVAL 15 DAY), DATE_ADD(CURDATE(), INTERVAL 60 DAY), 100, 0, 1);

INSERT IGNORE INTO suppliers (supplier_code, supplier_name, tax_code, phone_number, address, is_active) VALUES
('NCC-3M',        'Công ty Thiết bị Nha khoa 3M Việt Nam',        '0101234567', '0901112233', 'Tầng 12, Quận 1, TP.HCM',            1),
('NCC-SEPTODONT', 'Công ty Dược & Vật tư Nha khoa Septodont',     '0102345678', '0902223344', '88 Nguyễn Chí Thanh, Đống Đa, HN',   1),
('NCC-LABO-AV',   'Xưởng Phục Hình Răng Sứ Labo An Việt',         '0109887766', '0909887766', '15 Hoàng Cầu, Đống Đa, Hà Nội',      1);

INSERT IGNORE INTO materials (material_code, material_name, category, unit, min_stock_threshold, is_active) VALUES
('VT-COMP-Z350',  'Composite đặc 3M Filtek Z350 XT (Màu A2)', 'Trám răng',  'Ống', 10.000, 1),
('VT-TE-SEPTO',   'Thuốc tê Septanest 1:100.000 (Hộp 50 ống)', 'Thuốc tê',   'Hộp',  5.000, 1),
('VT-TH-GANG',    'Găng tay y tế Nitrile không bột (Hộp 100c)', 'Tiêu hao',   'Hộp', 20.000, 1),
('TH-AUG-1G',     'Augmentin 1g (Amoxicillin + Clavulanic)',    'Kháng sinh', 'Viên',30.000, 1),
('TH-PARA-500',   'Efferalgan / Paracetamol 500mg',             'Giảm đau',   'Viên',50.000, 1);

INSERT IGNORE INTO medications (drug_code, drug_name, active_ingredient, unit, sale_price, usage_instruction, is_active, material_id)
SELECT 'MED-AUG1G', 'Augmentin 1g', 'Amoxicillin 875mg + Acid Clavulanic 125mg', 'Viên', 25000.00, 'Uống sau ăn sáng và tối', 1, (SELECT material_id FROM materials WHERE material_code = 'TH-AUG-1G' LIMIT 1)
UNION ALL
SELECT 'MED-PARA500', 'Efferalgan 500mg', 'Paracetamol 500mg', 'Viên', 5000.00, 'Uống sau ăn khi đau, cách nhau 4-6 giờ', 1, (SELECT material_id FROM materials WHERE material_code = 'TH-PARA-500' LIMIT 1)
UNION ALL
SELECT 'MED-ALPHA', 'Alpha Choay 4.2mg', 'Alphachymotrypsin', 'Viên', 4000.00, 'Ngậm dưới lưỡi 4 viên/ngày chia 2 lần', 1, NULL;

-- =============================================================================
-- 4. BỆNH NHÂN, LIÊN KẾT TÀI KHOẢN CỔNG BỆNH NHÂN, VÍ ĐIỂM THƯỞNG, VOUCHER & TÀI LIỆU
-- =============================================================================
INSERT IGNORE INTO patient_profiles (user_id, patient_code, full_name, dob, gender, phone_number, address, has_allergies, allergy_details, has_underlying_disease, underlying_disease_details, medical_history_completed, preferred_branch_id, is_default, membership_tier_id, deposit_balance, customer_source, status, created_at, updated_at)
SELECT u.user_id, p.patient_code, p.full_name, p.dob, p.gender, u.phone_number, p.addr, p.allergy, p.allergy_dt, 0, 'Không', 1, b.branch_id, 1, mt.tier_id, p.dep, p.src, 'ACTIVE', NOW(), NOW()
FROM (
    SELECT 'bn_bao'  AS uname, 'BN-SEED-01' AS patient_code, 'Phạm Quốc Bảo'        AS full_name, '1992-06-12' AS dob, 'Nam' AS gender, '128 Nguyễn Thị Minh Khai, Quận 1, TP.HCM' AS addr, 0 AS allergy, 'Không dị ứng thuốc'    AS allergy_dt, 'CN01' AS bcode, 'SILVER'   AS tcode, 500000.00  AS dep, 'Google'     AS src UNION ALL
    SELECT 'bn_thao',          'BN-SEED-02',                  'Lê Hoàng Phương Thảo', '1998-11-25',        'Nữ',            '45 Láng Hạ, Đống Đa, Hà Nội',                       1,            'Dị ứng nhẹ Penicillin',               'CN02',          'STANDARD',          0.00,              'Facebook'          UNION ALL
    SELECT 'bn_minh',          'BN-SEED-03',                  'Trần Đức Minh',        '1988-03-19',        'Nam',           '12 Nguyễn Chí Thanh, Đống Đa, Hà Nội',              0,            'Không',                               'CN02',          'GOLD',              1000000.00,        'Giới thiệu'
) p
JOIN users u ON u.username = p.uname
JOIN branches b ON b.branch_code = p.bcode
LEFT JOIN membership_tiers mt ON mt.tier_code = p.tcode;

-- Đồng bộ liên kết tài khoản bệnh nhân với hồ sơ bệnh nhân (account_patient_profiles)
INSERT IGNORE INTO account_patient_profiles (account_id, profile_id, access_type, created_at)
SELECT pp.user_id, pp.profile_id, CASE WHEN pp.is_default = 1 THEN 'SELF' ELSE 'DEPENDENT' END, NOW()
FROM patient_profiles pp
WHERE pp.user_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM (SELECT profile_id FROM account_patient_profiles) app WHERE app.profile_id = pp.profile_id
  );

INSERT IGNORE INTO patient_loyalty_wallets (profile_id, current_points, total_spent_amount, last_updated)
SELECT pp.profile_id,
       CASE pp.patient_code WHEN 'BN-SEED-01' THEN 120 WHEN 'BN-SEED-03' THEN 450 ELSE 30 END,
       CASE pp.patient_code WHEN 'BN-SEED-01' THEN 6200000.00 WHEN 'BN-SEED-03' THEN 24500000.00 ELSE 1800000.00 END,
       NOW()
FROM patient_profiles pp
WHERE pp.patient_code IN ('BN-SEED-01', 'BN-SEED-02', 'BN-SEED-03');

INSERT IGNORE INTO patient_vouchers (promotion_id, profile_id, voucher_code, status, expires_at)
SELECT pr.promotion_id, pp.profile_id, CONCAT('VC-', pp.patient_code), 'AVAILABLE', DATE_ADD(CURDATE(), INTERVAL 60 DAY)
FROM promotions pr
JOIN patient_profiles pp ON pp.patient_code IN ('BN-SEED-01', 'BN-SEED-03')
WHERE pr.promo_code = 'TRIAN200K';

INSERT IGNORE INTO patient_documents (profile_id, document_type, document_name, file_url, file_type, notes, uploaded_by, uploaded_at)
SELECT pp.profile_id, 'CONSENT_FORM', 'Phiếu cam kết tiểu phẫu nhổ răng khôn', '/uploads/clinical_photos/sample-consent.pdf', 'PDF', 'Bệnh nhân đã ký xác nhận',
       (SELECT user_id FROM users WHERE username = 'letan_01' LIMIT 1), NOW()
FROM patient_profiles pp
WHERE pp.patient_code = 'BN-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT profile_id FROM patient_documents) pd WHERE pd.profile_id = pp.profile_id);

-- =============================================================================
-- 5. LỊCH HẸN, LƯỢT KHÁM, HÀNG CHỜ GỌI SỐ, BỆNH ÁN, ĐƠN THUỐC, LABO & X-QUANG
-- =============================================================================
INSERT IGNORE INTO appointment_session_windows (branch_id, preferred_session, start_time, end_time, is_active)
SELECT b.branch_id, w.sess, w.st, w.et, 1
FROM branches b
CROSS JOIN (
    SELECT 'MORNING'   AS sess, '08:00:00' AS st, '12:00:00' AS et UNION ALL
    SELECT 'AFTERNOON',         '13:30:00',       '17:30:00'       UNION ALL
    SELECT 'EVENING',           '17:30:00',       '20:00:00'
) w
WHERE b.branch_code IN ('CN01', 'CN02', 'CN03');

INSERT IGNORE INTO appointment_session_policies (branch_id, appointment_date, preferred_session, branch_capacity, doctor_capacity)
SELECT b.branch_id, CURDATE(), 'MORNING', 20, 8
FROM branches b WHERE b.branch_code IN ('CN01', 'CN02');

INSERT IGNORE INTO appointments (appointment_code, profile_id, branch_id, doctor_id, service_id, appointment_date, preferred_session, expected_time, duration_minutes, initial_symptoms, source, status, reschedule_count, created_at)
SELECT a.code, pp.profile_id, b.branch_id, sp.staff_id, sv.service_id, a.adate, a.sess, a.etime, 30, a.symp, 'ONLINE', a.st, 0, NOW()
FROM (
    SELECT 'LH-SEED-01' AS code, 'BN-SEED-01' AS pcode, 'CN01' AS bcode, 'BS-SEED-01' AS dcode, 'DV-NHO-RKHON' AS scode, CURDATE()                          AS adate, 'MORNING'   AS sess, '09:00:00' AS etime, 'Đau nhức răng khôn R48 hàm dưới phải' AS symp, 'COMPLETED' AS st UNION ALL
    SELECT 'LH-SEED-02',         'BN-SEED-02',          'CN02',          'BS-SEED-02',          'DV-KHAM-TQ',            CURDATE(),                                    'MORNING',           '10:00:00',          'Kiểm tra sâu răng và ê buốt R36',              'CONFIRMED'       UNION ALL
    SELECT 'LH-SEED-03',         'BN-SEED-03',          'CN02',          'BS-SEED-02',          'DV-CAOVOI',             CURDATE(),                                    'AFTERNOON',         '14:30:00',          'Lấy cao răng định kỳ',                         'PENDING'         UNION ALL
    SELECT 'LH-SEED-04',         'BN-SEED-01',          'CN01',          'BS-SEED-01',          'DV-KHAM-TQ',            DATE_ADD(CURDATE(), INTERVAL 1 DAY),          'MORNING',           '09:30:00',          'Tái khám cắt chỉ sau nhổ răng',                'CONFIRMED'
) a
JOIN patient_profiles pp ON pp.patient_code = a.pcode
JOIN branches b ON b.branch_code = a.bcode
JOIN staff_profiles sp ON sp.staff_code = a.dcode
JOIN services sv ON sv.service_code = a.scode;

INSERT IGNORE INTO appointment_histories (appointment_id, action, actor_user_id, old_value, new_value, reason, created_at)
SELECT ap.appointment_id, 'CONFIRMED', (SELECT user_id FROM users WHERE username = 'letan_01' LIMIT 1), 'PENDING', 'CONFIRMED', 'Lễ tân gọi điện xác nhận lịch hẹn', NOW()
FROM appointments ap
WHERE ap.appointment_code = 'LH-SEED-02'
  AND NOT EXISTS (SELECT 1 FROM (SELECT appointment_id FROM appointment_histories) ah WHERE ah.appointment_id = ap.appointment_id);

INSERT IGNORE INTO visits (visit_code, profile_id, branch_id, appointment_id, visit_type, check_in_at, initial_symptoms, primary_doctor_id, room_id, chair_id, status, completed_at, notes, created_at)
SELECT v.vcode, pp.profile_id, b.branch_id, ap.appointment_id, v.vtype, v.checkin, v.symp, sp.staff_id, rm.room_id, dc.chair_id, v.st, v.comp_at, v.notes, NOW()
FROM (
    SELECT 'VIS-SEED-01' AS vcode, 'BN-SEED-01' AS pcode, 'CN01' AS bcode, 'LH-SEED-01' AS acode, 'APPOINTMENT' AS vtype, NOW() - INTERVAL 90 MINUTE AS checkin, 'Đau nhức răng khôn R48 mọc lệch'     AS symp, 'BS-SEED-01' AS dcode, 'P102' AS rcode, 'G02'  AS ccode, 'COMPLETED'      AS st, NOW() - INTERVAL 30 MINUTE AS comp_at, 'Đã tiểu phẫu nhổ R48 an toàn'       AS notes UNION ALL
    SELECT 'VIS-SEED-02',          'BN-SEED-02',          'CN02',          'LH-SEED-02',          'APPOINTMENT',          NOW() - INTERVAL 40 MINUTE,            'Ê buốt răng hàm R36 khi uống lạnh',       'BS-SEED-02',          'P202',          'G202',          'IN_EXAM',               NULL,                                  'Đang khám và điều trị nội nha R36'             UNION ALL
    SELECT 'VIS-SEED-03',          'BN-SEED-03',          'CN02',          NULL,                  'WALK_IN',              NOW() - INTERVAL 20 MINUTE,            'Lấy cao răng & đánh bóng định kỳ',        'BS-SEED-02',          'P201',          'G201',          'WAITING',               NULL,                                  'Đã cấp số ưu tiên chờ gọi vào phòng'          UNION ALL
    SELECT 'VIS-SEED-04',          'BN-SEED-01',          'CN01',          NULL,                  'WALK_IN',              NOW() - INTERVAL 15 MINUTE,            'Kiểm tra phim X-quang răng hàm dưới',     'BS-SEED-01',          'P101',          'G01',           'WAITING_RESULT',        NULL,                                  'Đang chờ kết quả X-quang'
) v
JOIN patient_profiles pp ON pp.patient_code = v.pcode
JOIN branches b ON b.branch_code = v.bcode
LEFT JOIN appointments ap ON ap.appointment_code = v.acode
JOIN staff_profiles sp ON sp.staff_code = v.dcode
LEFT JOIN rooms rm ON rm.branch_id = b.branch_id AND rm.room_code = v.rcode
LEFT JOIN dental_chairs dc ON dc.branch_id = b.branch_id AND dc.chair_code = v.ccode;

INSERT IGNORE INTO queue_tickets (visit_id, branch_id, queue_type, queue_date, ticket_number, is_priority, display_number, is_emergency, priority_score, priority_reason, status, issued_at, called_at, completed_at)
SELECT v.visit_id, v.branch_id, 'EXAM', CURDATE(),
       COALESCE((SELECT MAX(q.ticket_number) FROM (SELECT branch_id, queue_date, queue_type, is_priority, ticket_number FROM queue_tickets) q WHERE q.branch_id = v.branch_id AND q.queue_date = CURDATE() AND q.queue_type = 'EXAM' AND q.is_priority = 0), 0) + 101,
       0, 'S101', 0, 0, NULL, 'IN_SERVICE', NOW() - INTERVAL 40 MINUTE, NOW() - INTERVAL 20 MINUTE, NULL
FROM visits v WHERE v.visit_code = 'VIS-SEED-02'
  AND NOT EXISTS (SELECT 1 FROM (SELECT visit_id FROM queue_tickets) q2 WHERE q2.visit_id = v.visit_id)
UNION ALL
SELECT v.visit_id, v.branch_id, 'EXAM', CURDATE(),
       COALESCE((SELECT MAX(q.ticket_number) FROM (SELECT branch_id, queue_date, queue_type, is_priority, ticket_number FROM queue_tickets) q WHERE q.branch_id = v.branch_id AND q.queue_date = CURDATE() AND q.queue_type = 'EXAM' AND q.is_priority = 1), 0) + 101,
       1, 'U101', 0, 50, 'Khách VIP / Ưu tiên', 'CALLED', NOW() - INTERVAL 20 MINUTE, NOW() - INTERVAL 3 MINUTE, NULL
FROM visits v WHERE v.visit_code = 'VIS-SEED-03'
  AND NOT EXISTS (SELECT 1 FROM (SELECT visit_id FROM queue_tickets) q2 WHERE q2.visit_id = v.visit_id);

INSERT IGNORE INTO clinical_examinations (visit_id, profile_id, doctor_id, chief_complaint, history_illness, vital_signs_json, extraoral_exam, intraoral_exam, periodontal_status, odontogram_chart_json, summary_assessment, exam_time)
SELECT v.visit_id, v.profile_id, v.primary_doctor_id,
       v.initial_symptoms, 'Khởi phát cách đây 3 ngày, đau âm ỉ tăng về đêm',
       '{"bloodPressure":"120/75","heartRate":76,"temperature":36.6}',
       'Mặt cân đối, hạch ngoại vi không sưng đau', 'Lỗ sâu mặt nhai R36 hoặc lợi trùm R48 sưng đỏ nhẹ',
       'Vôi răng độ I, nướu viền hồng nhạt', '{"48":{"status":"IMPACTED"}}',
       'Chẩn đoán lâm sàng hoàn tất, tiến hành thủ thuật theo phác đồ.', NOW()
FROM visits v
WHERE v.visit_code IN ('VIS-SEED-01', 'VIS-SEED-02');

INSERT IGNORE INTO examination_diagnoses (exam_id, visit_id, icd10_code, diagnosis_text, diagnosis_type, tooth_numbers, notes)
SELECT ce.exam_id, ce.visit_id, 'K01.1', 'Răng khôn hàm dưới phải R48 mọc lệch gây viêm lợi trùm', 'MAIN', '48', 'Đã tiểu phẫu nhổ R48 bằng máy Piezotome'
FROM clinical_examinations ce
JOIN visits v ON v.visit_id = ce.visit_id
WHERE v.visit_code = 'VIS-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT visit_id FROM examination_diagnoses) ed WHERE ed.visit_id = ce.visit_id);

INSERT IGNORE INTO treatment_plans (plan_code, profile_id, branch_id, doctor_id, plan_name, total_estimated_cost, final_estimated_cost, status, start_date, expected_end_date, notes, sent_to_accounting, created_at)
SELECT 'KH-SEED-01', pp.profile_id, b.branch_id, sp.staff_id,
       'Phác đồ nhổ răng khôn R48 & phục hình sứ Zirconia', 7000000.00, 7000000.00, 'IN_PROGRESS', CURDATE(), DATE_ADD(CURDATE(), INTERVAL 14 DAY), 'Điều trị 2 giai đoạn', 1, NOW()
FROM patient_profiles pp
JOIN branches b ON b.branch_code = 'CN01'
JOIN staff_profiles sp ON sp.staff_code = 'BS-SEED-01'
WHERE pp.patient_code = 'BN-SEED-01';

INSERT IGNORE INTO treatment_plan_stages (plan_id, stage_order, stage_name, description, status, expected_end_date)
SELECT tp.plan_id, 1, 'Giai đoạn 1: Tiểu phẫu & Điều trị cơ bản', 'Xử lý răng khôn R48 và vệ sinh khoang miệng', 'IN_PROGRESS', DATE_ADD(CURDATE(), INTERVAL 7 DAY)
FROM treatment_plans tp WHERE tp.plan_code = 'KH-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT plan_id, stage_order FROM treatment_plan_stages) s WHERE s.plan_id = tp.plan_id AND s.stage_order = 1);

INSERT IGNORE INTO treatment_plan_items (stage_id, plan_id, service_id, step_name, tooth_numbers, quantity, estimated_price, status)
SELECT st.stage_id, tp.plan_id, sv.service_id, 'Nhổ răng khôn R48', '48', 1.00, 2500000.00, 'COMPLETED'
FROM treatment_plans tp
JOIN treatment_plan_stages st ON st.plan_id = tp.plan_id AND st.stage_order = 1
JOIN services sv ON sv.service_code = 'DV-NHO-RKHON'
WHERE tp.plan_code = 'KH-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT plan_id, service_id FROM treatment_plan_items) i WHERE i.plan_id = tp.plan_id AND i.service_id = sv.service_id);

INSERT IGNORE INTO performed_services (visit_id, plan_item_id, profile_id, branch_id, service_id, step_name, tooth_numbers, quantity, unit_price, discount_amount, final_price, doctor_id, completed_at, status, clinical_notes)
SELECT v.visit_id,
       (SELECT tpi.plan_item_id FROM treatment_plan_items tpi JOIN treatment_plans tp ON tp.plan_id = tpi.plan_id WHERE tp.plan_code = 'KH-SEED-01' LIMIT 1),
       v.profile_id, v.branch_id, sv.service_id, 'Tiểu phẫu nhổ răng khôn R48', '48', 1.00, 2500000.00, 0.00, 2500000.00, v.primary_doctor_id, NOW(), 'COMPLETED', 'Thực hiện thành công, không biến chứng'
FROM visits v
JOIN services sv ON sv.service_code = 'DV-NHO-RKHON'
WHERE v.visit_code = 'VIS-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT visit_id, service_id FROM performed_services) ps WHERE ps.visit_id = v.visit_id AND ps.service_id = sv.service_id);

INSERT IGNORE INTO paraclinical_orders (order_code, visit_id, profile_id, doctor_id, status, order_time)
SELECT 'CLS-SEED-01', v.visit_id, v.profile_id, v.primary_doctor_id, 'ORDERED', NOW()
FROM visits v WHERE v.visit_code = 'VIS-SEED-04';

INSERT IGNORE INTO paraclinical_order_items (order_id, service_id, tooth_numbers, status, external_ref_id)
SELECT po.order_id, sv.service_id, '48', 'WAITING', 'XQ-SEED-001'
FROM paraclinical_orders po
JOIN services sv ON sv.service_code = 'DV-XQ-QUANH'
WHERE po.order_code = 'CLS-SEED-01';

INSERT IGNORE INTO nanopix_xray_sync_logs (external_study_uid, external_patient_id, raw_patient_name, image_url, sync_status, matched_profile_id, matched_visit_id, branch_id, matched_item_id, tooth_numbers, is_manual_linked, captured_at, notes, sync_time)
SELECT 'STUDY-NANOPIX-SEED-001', 'BN-SEED-01', 'Pham Quoc Bao', '/uploads/nanopix/sample-xray.png', 'MATCHED',
       v.profile_id, v.visit_id, v.branch_id, poi.order_item_id, '48', 1, NOW(), 'Ảnh phim X-quang quanh chóp R48 rõ nét', NOW()
FROM visits v
JOIN paraclinical_orders po ON po.visit_id = v.visit_id
JOIN paraclinical_order_items poi ON poi.order_id = po.order_id
WHERE v.visit_code = 'VIS-SEED-04';

INSERT IGNORE INTO prescriptions (prescription_code, visit_id, profile_id, branch_id, doctor_id, diagnosis_text, advice_notes, followup_date, status, created_at)
SELECT 'TOA-SEED-01', v.visit_id, v.profile_id, v.branch_id, v.primary_doctor_id,
       '[K01.1] Hậu phẫu nhổ răng khôn R48', 'Cắn gạc 45 phút, chườm lạnh 24h đầu, uống thuốc đúng liều.', DATE_ADD(CURDATE(), INTERVAL 7 DAY), 'DISPENSED', NOW()
FROM visits v WHERE v.visit_code = 'VIS-SEED-01';

INSERT IGNORE INTO prescription_items (prescription_id, medication_id, drug_name, dosage, duration_days, quantity, bought_quantity, unit, usage_instruction, unit_price)
SELECT pr.prescription_id, m.medication_id, m.drug_name, 'Ngày 2 lần, mỗi lần 1 viên', 5, 10.00, 10.00, m.unit, m.usage_instruction, m.sale_price
FROM prescriptions pr
JOIN medications m ON m.drug_code = 'MED-AUG1G'
WHERE pr.prescription_code = 'TOA-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT prescription_id, medication_id FROM prescription_items) pi WHERE pi.prescription_id = pr.prescription_id AND pi.medication_id = m.medication_id);

INSERT IGNORE INTO labo_orders (labo_code, visit_id, profile_id, branch_id, doctor_id, supplier_id, order_type, tooth_positions, material_type, shade_color, quantity, unit_price, cost_to_clinic, sent_date, expected_date, status, notes)
SELECT 'LAB-SEED-01', v.visit_id, v.profile_id, v.branch_id, v.primary_doctor_id, s.supplier_id, 'NEW', '36', 'Zirconia DDBIO Đức', 'A2', 1, 1200000.00, 1200000.00, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 4 DAY), 'SENT', 'Gửi xưởng chế tác mão sứ R36'
FROM visits v
JOIN suppliers s ON s.supplier_code = 'NCC-LABO-AV'
WHERE v.visit_code = 'VIS-SEED-02';

INSERT INTO clinical_workflow_templates (template_code, template_name, description, service_id, clinic_id, status, created_by, created_at, updated_at)
SELECT src.template_code, src.template_name, src.description, sv.service_id, b.branch_id, 'ACTIVE',
       (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1), NOW(), NOW()
FROM (
    SELECT 'QT-NHO-RKHON' template_code, 'Quy trình tiểu phẫu nhổ răng khôn mọc lệch' template_name,
           'Quy trình chuẩn theo Quyết định 3207/QĐ-BYT của Bộ Y Tế' description, 'DV-NHO-RKHON' service_code
    UNION ALL SELECT 'QTKT-CAOVOI', 'Quy trình kỹ thuật Lấy cao răng & Đánh bóng',
           'Hướng dẫn kỹ thuật lấy cao răng siêu âm và đánh bóng men răng theo Quyết định 3207/QĐ-BYT', 'DV-CAOVOI'
    UNION ALL SELECT 'QTKT-TRAM-RANG', 'Quy trình kỹ thuật Hàn răng thẩm mỹ Composite',
           'Hướng dẫn điều trị sâu ngà răng phục hồi bằng Composite quang trùng hợp theo chuẩn Bộ Y Tế', 'DV-TRAM-COMP'
    UNION ALL SELECT 'QTKT-TUY-RH', 'Quy trình kỹ thuật Điều trị nội nha tủy răng nhiều chân',
           'Hướng dẫn kỹ thuật mở tủy, đo chiều dài làm việc Apex Locator, tạo hình bơm rửa và hàn bít ống tủy BYT', 'DV-NOINHA-RHAM'
    UNION ALL SELECT 'QTKT-IMP-GER', 'Quy trình kỹ thuật Phẫu thuật Cấy ghép Implant nha khoa',
           'Hướng dẫn quy trình kỹ thuật đặt trụ Implant trong xương hàm chuẩn Bộ Y Tế', 'DV-IMP-GER'
    UNION ALL SELECT 'QTKT-SU-TITAN', 'Quy trình kỹ thuật Phục hình Răng sứ thẩm mỹ cố định',
           'Hướng dẫn quy trình mài cùi, lấy dấu cao su, thử sườn và gắn phục hình răng sứ cố định', 'DV-SU-TITAN'
) src
JOIN services sv ON sv.service_code = src.service_code
JOIN branches b ON b.branch_code = 'CN01'
ON DUPLICATE KEY UPDATE
    template_name = VALUES(template_name),
    description = VALUES(description),
    updated_at = NOW();

INSERT IGNORE INTO clinical_workflow_versions (template_id, version_number, status, flow_mode, effective_from, change_summary, created_by, created_at, published_at)
SELECT t.template_id, 1, 'PUBLISHED', 'CONTINUOUS', CURDATE(), 'Phiên bản chuẩn hóa theo QĐ 3207/QĐ-BYT', (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1), NOW(), NOW()
FROM clinical_workflow_templates t
WHERE t.template_code IN ('QT-NHO-RKHON', 'QTKT-CAOVOI', 'QTKT-TRAM-RANG', 'QTKT-TUY-RH', 'QTKT-IMP-GER', 'QTKT-SU-TITAN')
  AND NOT EXISTS (SELECT 1 FROM (SELECT template_id, version_number FROM clinical_workflow_versions) v WHERE v.template_id = t.template_id AND v.version_number = 1);

INSERT IGNORE INTO clinical_workflow_sections (version_id, section_code, section_title, content, display_order)
SELECT v.version_id, sec.section_code, sec.section_title, sec.content_text, sec.display_order
FROM clinical_workflow_versions v
JOIN clinical_workflow_templates t ON t.template_id = v.template_id AND v.version_number = 1
CROSS JOIN (
    SELECT 'I' section_code, 'I. ĐẠI CƯƠNG & CHỈ ĐỊNH ĐIỀU TRỊ' section_title,
           'Áp dụng cho người bệnh có bệnh lý răng hàm mặt đã được bác sĩ khám lâm sàng và chẩn đoán xác định.' content_text, 1 display_order
    UNION ALL SELECT 'II', 'II. CHỐNG CHỈ ĐỊNH',
           'Chống chỉ định tạm thời với người bệnh đang sốt cao cấp tính hoặc huyết áp tăng cao chưa kiểm soát.', 2
    UNION ALL SELECT 'III', 'III. CHUẨN BỊ (NHÂN SỰ, PHƯƠNG TIỆN & NGƯỜI BỆNH)',
           'Bác sĩ Răng Hàm Mặt có chứng chỉ hành nghề, trợ thủ nha khoa, bộ dụng cụ vô trùng tuyệt đối.', 3
    UNION ALL SELECT 'IV', 'IV. CÁC BƯỚC TIẾN HÀNH KỸ THUẬT',
           'Thực hiện tuần tự từng bước lâm sàng trong danh sách bước quy trình bên dưới.', 4
    UNION ALL SELECT 'V', 'V. THEO DÕI & CHĂM SÓC SAU THỦ THUẬT',
           'Theo dõi toàn trạng tại phòng khám 15-30 phút sau can thiệp và dặn dò vệ sinh răng miệng.', 5
    UNION ALL SELECT 'VI', 'VI. TAI BIẾN & HƯỚNG XỬ TRÍ',
           'Xử trí chảy máu kéo dài, đau nhức hoặc phản ứng thuốc tê theo phác đồ cấp cứu tại chỗ.', 6
) sec
WHERE t.template_code IN ('QT-NHO-RKHON', 'QTKT-CAOVOI', 'QTKT-TRAM-RANG', 'QTKT-TUY-RH', 'QTKT-IMP-GER', 'QTKT-SU-TITAN')
  AND NOT EXISTS (SELECT 1 FROM (SELECT version_id, section_code FROM clinical_workflow_sections) s WHERE s.version_id = v.version_id AND s.section_code = sec.section_code);

INSERT IGNORE INTO clinical_workflow_steps (version_id, step_code, step_name, step_description, step_type, sequence_order, is_optional, requires_clinical_approval, estimated_duration_minutes, queue_priority, status)
SELECT v.version_id, st.scode, st.sname, st.sdesc, st.stype, st.ord, 0, 0, 15, 0, 'ACTIVE'
FROM clinical_workflow_versions v
JOIN clinical_workflow_templates t ON t.template_id = v.template_id
CROSS JOIN (
    SELECT 'STEP_1' AS scode, 'Chụp X-quang & Đánh giá chân răng' AS sname, 'Kiểm tra hình thể chân răng và ống thần kinh' AS sdesc, 'PARACLINICAL' AS stype, 1 AS ord UNION ALL
    SELECT 'STEP_2',           'Tiểu phẫu nhổ răng & Khâu vạt',             'Thực hiện nhổ bằng sóng siêu âm Piezotome',                'CLINICAL',              2
) st
WHERE t.template_code = 'QT-NHO-RKHON'
  AND NOT EXISTS (SELECT 1 FROM (SELECT version_id, step_code FROM clinical_workflow_steps) ws WHERE ws.version_id = v.version_id AND ws.step_code = st.scode);

-- =============================================================================
-- 6. KHO VẬT TƯ, LÔ HÀNG, NHẬP KHO, XUẤT KHO & CÔNG NỢ NHÀ CUNG CẤP
-- =============================================================================
INSERT IGNORE INTO material_batches (branch_id, material_id, batch_number, expiry_date, quantity_remaining, import_price, supplier_id, status)
SELECT b.branch_id, m.material_id, mb.bno, mb.exp, mb.qty, mb.price, s.supplier_id, 'ACTIVE'
FROM (
    SELECT 'CN02' AS bcode, 'VT-COMP-Z350' AS mcode, 'LO-COMP-2026-01' AS bno, DATE_ADD(CURDATE(), INTERVAL 365 DAY) AS exp, 25.000 AS qty, 450000.00 AS price, 'NCC-3M'        AS scode UNION ALL
    SELECT 'CN02',          'VT-TE-SEPTO',           'LO-TE-2026-01',            DATE_ADD(CURDATE(), INTERVAL 180 DAY),        12.000,        650000.00,          'NCC-SEPTODONT'          UNION ALL
    SELECT 'CN02',          'VT-TH-GANG',            'LO-GANG-2026-01',          DATE_ADD(CURDATE(), INTERVAL 540 DAY),        48.000,        110000.00,          'NCC-3M'                 UNION ALL
    SELECT 'CN01',          'VT-COMP-Z350',          'LO-COMP-CN1-01',           DATE_ADD(CURDATE(), INTERVAL 365 DAY),        18.000,        450000.00,          'NCC-3M'
) mb
JOIN branches b ON b.branch_code = mb.bcode
JOIN materials m ON m.material_code = mb.mcode
JOIN suppliers s ON s.supplier_code = mb.scode;

INSERT INTO branch_inventory (branch_id, material_id, current_stock, last_updated)
SELECT mb.branch_id, mb.material_id, SUM(mb.quantity_remaining), NOW()
FROM material_batches mb
GROUP BY mb.branch_id, mb.material_id
ON DUPLICATE KEY UPDATE
    current_stock = VALUES(current_stock),
    last_updated = NOW();

-- Phiếu nhập kho PN-SEED-001: 25 ống VT-COMP-Z350 (11.250.000đ) + 50 hộp VT-TH-GANG (5.500.000đ) = 16.750.000đ
INSERT IGNORE INTO stock_receipts (receipt_code, branch_id, supplier_id, supplier_invoice_no, receipt_date, total_amount, receiver_id, notes)
SELECT 'PN-SEED-001', b.branch_id, s.supplier_id, 'HD-3M-2026-01', CURDATE(), 16750000.00, sp.staff_id, 'Nhập lô vật tư nha khoa đầu kỳ'
FROM branches b
JOIN suppliers s ON s.supplier_code = 'NCC-3M'
JOIN staff_profiles sp ON sp.staff_code = 'KHO-SEED-01'
WHERE b.branch_code = 'CN02';

INSERT IGNORE INTO stock_receipt_items (receipt_id, material_id, batch_number, expiry_date, quantity, unit_price)
SELECT sr.receipt_id, m.material_id, 'LO-COMP-2026-01', DATE_ADD(CURDATE(), INTERVAL 365 DAY), 25.000, 450000.00
FROM stock_receipts sr
JOIN materials m ON m.material_code = 'VT-COMP-Z350'
WHERE sr.receipt_code = 'PN-SEED-001'
  AND NOT EXISTS (SELECT 1 FROM (SELECT receipt_id, material_id FROM stock_receipt_items) sri WHERE sri.receipt_id = sr.receipt_id AND sri.material_id = m.material_id);

INSERT IGNORE INTO stock_receipt_items (receipt_id, material_id, batch_number, expiry_date, quantity, unit_price)
SELECT sr.receipt_id, m.material_id, 'LO-GANG-2026-01', DATE_ADD(CURDATE(), INTERVAL 540 DAY), 50.000, 110000.00
FROM stock_receipts sr
JOIN materials m ON m.material_code = 'VT-TH-GANG'
WHERE sr.receipt_code = 'PN-SEED-001'
  AND NOT EXISTS (SELECT 1 FROM (SELECT receipt_id, material_id FROM stock_receipt_items) sri WHERE sri.receipt_id = sr.receipt_id AND sri.material_id = m.material_id);

INSERT IGNORE INTO stock_issues (issue_code, branch_id, issue_type, issue_date, issuer_id, notes)
SELECT 'PX-SEED-001', b.branch_id, 'INTERNAL_USE', CURDATE(), sp.staff_id, 'Xuất vật tư tiêu hao cho phòng khám'
FROM branches b
JOIN staff_profiles sp ON sp.staff_code = 'KHO-SEED-01'
WHERE b.branch_code = 'CN02';

INSERT IGNORE INTO stock_issue_items (issue_id, material_id, batch_id, quantity, notes)
SELECT si.issue_id, m.material_id, mb.batch_id, 2.000, 'Xuất dùng hàng ngày tại ghế khám'
FROM stock_issues si
JOIN materials m ON m.material_code = 'VT-TH-GANG'
JOIN material_batches mb ON mb.branch_id = si.branch_id AND mb.material_id = m.material_id
WHERE si.issue_code = 'PX-SEED-001'
  AND NOT EXISTS (SELECT 1 FROM (SELECT issue_id, material_id FROM stock_issue_items) sii WHERE sii.issue_id = si.issue_id AND sii.material_id = m.material_id);

INSERT IGNORE INTO supplier_payments (payment_code, branch_id, supplier_id, receipt_id, payment_date, amount, payment_method, reference_no, paid_by, notes, created_at)
SELECT 'PC-NCC-SEED-01', sr.branch_id, sr.supplier_id, sr.receipt_id, CURDATE(), 10000000.00, 'BANK_TRANSFER', 'UNC-3M-001',
       (SELECT staff_id FROM staff_profiles WHERE staff_code = 'KT-SEED-01' LIMIT 1), 'Thanh toán đợt 1 hóa đơn nhập kho 3M', NOW()
FROM stock_receipts sr WHERE sr.receipt_code = 'PN-SEED-001';

-- =============================================================================
-- 7. TÀI CHÍNH, HÓA ĐƠN, THANH TOÁN, CHI PHÍ VẬN HÀNH, HOA HỒNG & BẢNG LƯƠNG
-- =============================================================================
INSERT IGNORE INTO invoices (invoice_code, invoice_type, visit_id, profile_id, branch_id, cashier_id, total_service_amount, total_medicine_amount, surcharge_amount, surcharge_paid_amount, surcharge_remaining_debt, discount_amount, promotion_discount, point_discount, points_redeemed, total_amount, total_vat_amount, paid_amount, remaining_debt, payment_status, status, issued_at, updated_at, notes)
SELECT 'HD-SEED-001', 'SERVICE', v.visit_id, v.profile_id, v.branch_id,
       (SELECT staff_id FROM staff_profiles WHERE staff_code = 'KT-SEED-01' LIMIT 1),
       2500000.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0, 2500000.00, 0.00, 1500000.00, 1000000.00, 'PARTIAL', 'CONFIRMED', NOW(), NOW(), 'Hóa đơn tiểu phẫu R48 (đã thanh toán 1.5tr, còn nợ 1tr)'
FROM visits v WHERE v.visit_code = 'VIS-SEED-01';

INSERT IGNORE INTO invoice_items (item_type, invoice_id, performed_id, service_id, item_name, quantity, unit, unit_price, final_amount, line_order, discount_amount, payable_amount, vat_rate, vat_amount, paid_amount, remaining_debt, payment_status)
SELECT 'SERVICE', inv.invoice_id, ps.performed_id, ps.service_id, 'Tiểu phẫu nhổ răng khôn mọc lệch R48', 1.00, 'Răng', 2500000.00, 2500000.00, 1, 0.00, 2500000.00, 0.00, 0.00, 1500000.00, 1000000.00, 'PARTIAL'
FROM invoices inv
JOIN performed_services ps ON ps.visit_id = inv.visit_id
WHERE inv.invoice_code = 'HD-SEED-001'
  AND NOT EXISTS (SELECT 1 FROM (SELECT invoice_id FROM invoice_items) ii WHERE ii.invoice_id = inv.invoice_id);

INSERT IGNORE INTO payments (payment_code, invoice_id, profile_id, branch_id, payment_type, payment_method, amount, transaction_ref, idempotency_key, notes, cashier_id, paid_at)
SELECT 'PT-SEED-001', inv.invoice_id, inv.profile_id, inv.branch_id, 'PAYMENT', 'BANK_TRANSFER', 1500000.00, 'FT261110001', 'IDEMP-PT-SEED-001',
       'Thu tiền đợt 1 hóa đơn HD-SEED-001', inv.cashier_id, NOW()
FROM invoices inv WHERE inv.invoice_code = 'HD-SEED-001';

INSERT IGNORE INTO payment_allocations (payment_id, item_id, invoice_id, allocated_amount, item_type, item_title, line_order, created_at, notes)
SELECT p.payment_id, ii.item_id, p.invoice_id, 1500000.00, 'SERVICE', ii.item_name, 1, NOW(), 'Phân bổ thu đợt 1'
FROM payments p
JOIN invoice_items ii ON ii.invoice_id = p.invoice_id
WHERE p.payment_code = 'PT-SEED-001'
  AND NOT EXISTS (SELECT 1 FROM (SELECT payment_id FROM payment_allocations) pa WHERE pa.payment_id = p.payment_id);

INSERT IGNORE INTO operating_expenses (expense_code, branch_id, category, amount, expense_date, recipient_name, payment_method, source_type, description, is_cancelled, created_by, created_at)
SELECT 'PC-VH-SEED-01', b.branch_id, 'Điện nước & Mặt bằng', 12000000.00, CURDATE(), 'Ban quản lý tòa nhà Láng Hạ', 'BANK_TRANSFER', 'MANUAL', 'Thanh toán tiền điện, nước & thuê mặt bằng tháng hiện tại', 0,
       (SELECT user_id FROM users WHERE username = 'ketoan_01' LIMIT 1), NOW()
FROM branches b WHERE b.branch_code = 'CN02';

INSERT IGNORE INTO commission_policies (policy_name, service_id, staff_id, commission_type, commission_value, is_active)
SELECT 'Hoa hồng tiểu phẫu Bác sĩ 15%', sv.service_id, sp.staff_id, 'PERCENTAGE', 15.00, 1
FROM services sv
JOIN staff_profiles sp ON sp.staff_code = 'BS-SEED-01'
WHERE sv.service_code = 'DV-NHO-RKHON'
  AND NOT EXISTS (SELECT 1 FROM (SELECT service_id, staff_id FROM commission_policies) cp WHERE cp.service_id = sv.service_id AND cp.staff_id = sp.staff_id);

INSERT IGNORE INTO service_commissions (performed_id, staff_id, role_type, commission_amount, status, calculated_at)
SELECT ps.performed_id, ps.doctor_id, 'MAIN_DOCTOR', 375000.00, 'PENDING', NOW()
FROM performed_services ps
JOIN visits v ON v.visit_id = ps.visit_id
WHERE v.visit_code = 'VIS-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT performed_id FROM service_commissions) sc WHERE sc.performed_id = ps.performed_id);

INSERT IGNORE INTO payroll_periods (period_name, branch_id, month, year, total_salary, total_base_salary, total_allowance, total_bonus, total_commission, total_deduction, total_advance_allocated, total_remaining_payable, total_gross_salary, total_insurance_employee, total_insurance_employer, total_pit_amount, status, created_at, updated_at)
SELECT CONCAT('Bảng lương T', MONTH(CURDATE()), '/', YEAR(CURDATE()), ' - CN01'), b.branch_id, MONTH(CURDATE()), YEAR(CURDATE()),
       25000000.00, 25000000.00, 3000000.00, 0.00, 375000.00, 0.00, 0.00, 28375000.00, 28375000.00, 1890000.00, 3870000.00, 0.00, 'DRAFT', NOW(), NOW()
FROM branches b WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT branch_id, month, year FROM payroll_periods) pp WHERE pp.branch_id = b.branch_id AND pp.month = MONTH(CURDATE()) AND pp.year = YEAR(CURDATE()));

INSERT IGNORE INTO payroll_items (period_id, staff_id, period_month, period_year, base_salary, actual_work_days, actual_base_salary, total_allowance, bonus_amount, total_commission, gross_salary, non_taxable_income, insurance_employee_deduction, insurance_employer_amount, personal_deduction_amount, dependent_deduction_amount, taxable_income, assessable_income, pit_amount, deduction_amount, net_salary, advance_allocated_amount, remaining_payable, payment_status)
SELECT pp.period_id, sp.staff_id, pp.month, pp.year, sp.base_salary, 22.00, sp.base_salary, sp.allowance_amount, 0.00, 375000.00,
       sp.base_salary + sp.allowance_amount + 375000.00, 0.00, 1890000.00, 3870000.00, 11000000.00, 0.00, 0.00, 0.00, 0.00, 1890000.00,
       sp.base_salary + sp.allowance_amount + 375000.00 - 1890000.00, 0.00, sp.base_salary + sp.allowance_amount + 375000.00 - 1890000.00, 'UNPAID'
FROM payroll_periods pp
JOIN branches b ON b.branch_id = pp.branch_id AND b.branch_code = 'CN01'
JOIN staff_profiles sp ON sp.staff_code = 'BS-SEED-01'
WHERE pp.month = MONTH(CURDATE()) AND pp.year = YEAR(CURDATE())
  AND NOT EXISTS (SELECT 1 FROM (SELECT period_id, staff_id FROM payroll_items) pi WHERE pi.period_id = pp.period_id AND pi.staff_id = sp.staff_id);

INSERT IGNORE INTO salary_advances (advance_code, staff_id, branch_id, amount, reason, payment_method, status, allocated_amount, remaining_amount, created_by, notes, created_at)
SELECT 'TU-SEED-001', sp.staff_id, b.branch_id, 2000000.00, 'Tạm ứng lương giữa tháng', 'BANK_TRANSFER', 'PENDING', 0.00, 2000000.00,
       (SELECT user_id FROM users WHERE username = 'letan_01' LIMIT 1), 'Đơn tạm ứng chờ duyệt', NOW()
FROM staff_profiles sp
JOIN branches b ON b.branch_code = 'CN01'
WHERE sp.staff_code = 'LT-SEED-01';

INSERT IGNORE INTO owner_distributions (distribution_code, branch_id, month, year, distributable_amount, retained_amount, total_distributed_amount, basis_notes, status, submitted_by, submitted_at, created_at)
SELECT 'PCLN-SEED-01', b.branch_id, MONTH(CURDATE()), YEAR(CURDATE()), 100000000.00, 20000000.00, 80000000.00, 'Phân bổ lợi nhuận định kỳ quý', 'SUBMITTED',
       (SELECT staff_id FROM staff_profiles WHERE staff_code = 'KT-SEED-01' LIMIT 1), NOW(), NOW()
FROM branches b WHERE b.branch_code = 'CN01';

INSERT IGNORE INTO owner_distribution_items (distribution_id, recipient_staff_id, percentage, amount, note)
SELECT od.distribution_id, sp.staff_id, 100.00, 80000000.00, 'Cổ đông sáng lập'
FROM owner_distributions od
JOIN staff_profiles sp ON sp.staff_code = 'BS-SEED-01'
WHERE od.distribution_code = 'PCLN-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT distribution_id FROM owner_distribution_items) odi WHERE odi.distribution_id = od.distribution_id);

-- =============================================================================
-- 8. CSKH, TIN TỨC, BÌNH LUẬN, CHAT, ĐÁNH GIÁ & NHẬT KÝ HỆ THỐNG
-- =============================================================================
INSERT IGNORE INTO customer_care_tasks (task_code, profile_id, branch_id, task_type, visit_id, appointment_id, assigned_staff_id, due_date, status, notes)
SELECT 'CSKH-SEED-01', v.profile_id, v.branch_id, 'POST_TREATMENT_FOLLOWUP', v.visit_id, v.appointment_id,
       (SELECT staff_id FROM staff_profiles WHERE staff_code = 'LT-SEED-01' LIMIT 1),
       CURDATE(), 'IN_PROGRESS', 'Gọi hỏi thăm tình trạng cầm máu & giảm sưng sau nhổ răng khôn R48'
FROM visits v WHERE v.visit_code = 'VIS-SEED-01';

INSERT IGNORE INTO customer_care_interactions (task_id, profile_id, staff_id, channel, contact_result, notes, created_at)
SELECT cct.task_id, cct.profile_id, cct.assigned_staff_id, 'PHONE_CALL', 'CONNECTED', 'Bệnh nhân phản hồi ổn định, ăn uống bình thường.', NOW()
FROM customer_care_tasks cct WHERE cct.task_code = 'CSKH-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT task_id FROM customer_care_interactions) cci WHERE cci.task_id = cct.task_id);

INSERT IGNORE INTO news_articles (title, slug, summary, content, thumbnail, status, created_by, branch_id, created_at, updated_at, published_at)
SELECT 'Hướng dẫn chăm sóc răng miệng đúng cách sau khi nhổ răng khôn', 'cham-soc-sau-nho-rang-khon-seed',
       'Những lưu ý quan trọng trong 48 giờ đầu giúp vết thương mau lành, tránh viêm ổ răng khô.',
       '<p>Sau tiểu phẫu nhổ răng khôn, bệnh nhân cần cắn chặt gạc vô trùng trong 45 phút, chườm lạnh ngoài má và ăn thức ăn mềm nguội trong 2 ngày đầu.</p>',
       '/uploads/news/36739395-8602-41ca-9833-04ae326aa20f_WIN_20241002_11_46_43_Pro.jpg',
       'PUBLISHED', (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1), b.branch_id, NOW(), NOW(), NOW()
FROM branches b WHERE b.branch_code = 'CN01';

INSERT IGNORE INTO comments (news_id, user_id, content, status, created_at, updated_at)
SELECT na.news_id, (SELECT user_id FROM users WHERE username = 'bn_bao' LIMIT 1), 'Bài viết rất hữu ích, cảm ơn bác sĩ đã hướng dẫn tận tình!', 'ACTIVE', NOW(), NOW()
FROM news_articles na WHERE na.slug = 'cham-soc-sau-nho-rang-khon-seed'
  AND NOT EXISTS (SELECT 1 FROM (SELECT news_id FROM comments) c WHERE c.news_id = na.news_id);

INSERT IGNORE INTO chat_conversations (profile_id, user_id, branch_id, channel, status, assigned_staff_id, created_at, last_message_at)
SELECT pp.profile_id, pp.user_id, pp.preferred_branch_id, 'WEB_WIDGET', 'STAFF_HANDLING',
       (SELECT staff_id FROM staff_profiles WHERE staff_code = 'LT-SEED-01' LIMIT 1), NOW(), NOW()
FROM patient_profiles pp WHERE pp.patient_code = 'BN-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT profile_id FROM chat_conversations) cc WHERE cc.profile_id = pp.profile_id);

INSERT IGNORE INTO chat_messages (conversation_id, sender_type, sender_id, message_text, is_read, sent_at)
SELECT cc.conversation_id, 'PATIENT', cc.user_id, 'Em muốn hỏi lịch tái khám cắt chỉ cuối tuần này còn trống giờ sáng không ạ?', 1, NOW()
FROM chat_conversations cc
JOIN patient_profiles pp ON pp.profile_id = cc.profile_id
WHERE pp.patient_code = 'BN-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT conversation_id FROM chat_messages) cm WHERE cm.conversation_id = cc.conversation_id);

INSERT IGNORE INTO service_reviews (visit_id, profile_id, branch_id, rating, comment, tags, clinic_response, created_at)
SELECT v.visit_id, v.profile_id, v.branch_id, 5, 'Bác sĩ làm nhẹ tay, nhân viên lễ tân tiếp đón rất chu đáo!', 'Tận tâm, Sạch sẽ, Nhanh chóng', 'Cảm ơn anh Bảo đã tin tưởng Nha khoa Cường Việt!', NOW()
FROM visits v WHERE v.visit_code = 'VIS-SEED-01';

INSERT IGNORE INTO system_notifications (user_id, title, message, notification_type, is_read, created_at)
SELECT u.user_id, 'Chào mừng đến với CV-DCMS', 'Hệ thống đã sẵn sàng dữ liệu mẫu cho toàn bộ phân hệ nghiệp vụ.', 'SYSTEM', 0, NOW()
FROM users u WHERE u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM (SELECT user_id, notification_type FROM system_notifications) sn WHERE sn.user_id = u.user_id AND sn.notification_type = 'SYSTEM');

INSERT IGNORE INTO system_audit_logs (user_id, branch_id, action, entity_table, entity_id, new_values_json, ip_address, created_at)
SELECT u.user_id, b.branch_id, 'SEED_INIT', 'branches', b.branch_id, '{"note":"Khởi tạo dữ liệu mẫu toàn hệ thống"}', '127.0.0.1', NOW()
FROM users u
JOIN branches b ON b.branch_code = 'CN01'
WHERE u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM (SELECT action FROM system_audit_logs) sal WHERE sal.action = 'SEED_INIT');

-- =============================================================================
-- 9. BỔ SUNG ĐẦY ĐỦ CÁC BẢNG CÒN LẠI (QUY TRÌNH CHI TIẾT, LÂM SÀNG, TƯƠNG TÁC, TÀI CHÍNH CHI TIẾT)
-- =============================================================================
INSERT IGNORE INTO clinical_workflow_step_paraclinical (step_id, service_id, is_required, result_required)
SELECT ws.step_id, sv.service_id, 1, 1
FROM clinical_workflow_steps ws
JOIN clinical_workflow_versions v ON v.version_id = ws.version_id
JOIN clinical_workflow_templates t ON t.template_id = v.template_id
JOIN services sv ON sv.service_code = 'DV-XQ-QUANH'
WHERE t.template_code = 'QT-NHO-RKHON' AND ws.step_code = 'STEP_1'
  AND NOT EXISTS (SELECT 1 FROM (SELECT step_id, service_id FROM clinical_workflow_step_paraclinical) x WHERE x.step_id = ws.step_id AND x.service_id = sv.service_id);

INSERT IGNORE INTO clinical_workflow_step_roles (step_id, role_id, is_primary)
SELECT ws.step_id, r.role_id, 1
FROM clinical_workflow_steps ws
JOIN clinical_workflow_versions v ON v.version_id = ws.version_id
JOIN clinical_workflow_templates t ON t.template_id = v.template_id
JOIN roles r ON UPPER(r.role_name) = 'BAC_SI'
WHERE t.template_code = 'QT-NHO-RKHON'
  AND NOT EXISTS (SELECT 1 FROM (SELECT step_id, role_id FROM clinical_workflow_step_roles) x WHERE x.step_id = ws.step_id AND x.role_id = r.role_id);

INSERT IGNORE INTO clinical_workflow_transition_rules (version_id, from_step_id, to_step_id, condition_expression, priority_order, is_default)
SELECT v.version_id, s1.step_id, s2.step_id, '{"xrayResult":"CLEAR"}', 1, 1
FROM clinical_workflow_versions v
JOIN clinical_workflow_templates t ON t.template_id = v.template_id
JOIN clinical_workflow_steps s1 ON s1.version_id = v.version_id AND s1.step_code = 'STEP_1'
JOIN clinical_workflow_steps s2 ON s2.version_id = v.version_id AND s2.step_code = 'STEP_2'
WHERE t.template_code = 'QT-NHO-RKHON'
  AND NOT EXISTS (SELECT 1 FROM (SELECT version_id, from_step_id, to_step_id FROM clinical_workflow_transition_rules) x WHERE x.version_id = v.version_id AND x.from_step_id = s1.step_id AND x.to_step_id = s2.step_id);

INSERT IGNORE INTO workflow_executions (version_id, visit_id, profile_id, branch_id, current_step_id, status, started_at, completed_at)
SELECT v.version_id, vis.visit_id, vis.profile_id, vis.branch_id, s2.step_id, 'COMPLETED', NOW() - INTERVAL 80 MINUTE, NOW() - INTERVAL 35 MINUTE
FROM clinical_workflow_versions v
JOIN clinical_workflow_templates t ON t.template_id = v.template_id
JOIN clinical_workflow_steps s2 ON s2.version_id = v.version_id AND s2.step_code = 'STEP_2'
JOIN visits vis ON vis.visit_code = 'VIS-SEED-01'
WHERE t.template_code = 'QT-NHO-RKHON'
  AND NOT EXISTS (SELECT 1 FROM (SELECT visit_id, version_id FROM workflow_executions) we WHERE we.visit_id = vis.visit_id AND we.version_id = v.version_id);

INSERT IGNORE INTO workflow_step_executions (execution_id, step_id, status, assigned_staff_id, started_at, completed_at, result_note, result_data)
SELECT we.execution_id, ws.step_id, 'COMPLETED', vis.primary_doctor_id, NOW() - INTERVAL 75 MINUTE, NOW() - INTERVAL 40 MINUTE,
       'Đã hoàn tất bước theo đúng quy trình chuẩn.', '{"ok":true}'
FROM workflow_executions we
JOIN visits vis ON vis.visit_id = we.visit_id AND vis.visit_code = 'VIS-SEED-01'
JOIN clinical_workflow_steps ws ON ws.version_id = we.version_id
WHERE NOT EXISTS (SELECT 1 FROM (SELECT execution_id, step_id FROM workflow_step_executions) wse WHERE wse.execution_id = we.execution_id AND wse.step_id = ws.step_id);

INSERT IGNORE INTO clinical_photos (profile_id, visit_id, performed_id, photo_stage, photo_category, image_url, caption, captured_at)
SELECT ps.profile_id, ps.visit_id, ps.performed_id, 'BEFORE', 'INTRAORAL', '/uploads/clinical_photos/sample-r48-before.jpg',
       'Ảnh lâm sàng vùng răng R48 trước khi nhổ', NOW() - INTERVAL 60 MINUTE
FROM performed_services ps
JOIN visits v ON v.visit_id = ps.visit_id
WHERE v.visit_code = 'VIS-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT performed_id FROM clinical_photos) cp WHERE cp.performed_id = ps.performed_id);

INSERT IGNORE INTO paraclinical_result_attachments (order_item_id, file_name, file_url, file_type, caption, uploaded_at)
SELECT poi.order_item_id, 'xq-r48-can-chóp.png', '/uploads/nanopix/sample-xray.png', 'IMAGE', 'Phim X-quang quanh chóp R48', NOW()
FROM paraclinical_order_items poi
JOIN paraclinical_orders po ON po.order_id = poi.order_id
WHERE po.order_code = 'CLS-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT order_item_id FROM paraclinical_result_attachments) pra WHERE pra.order_item_id = poi.order_item_id);

INSERT IGNORE INTO lab_external_orders (order_item_id, lab_partner_name, sample_code, sample_type, sent_at, expected_result_date, lab_status, notes)
SELECT poi.order_item_id, 'Phòng Lab Chẩn Đoán Hình Ảnh Việt Mỹ', 'LAB-EXT-SEED-01', 'Phim CBCT 3D', NOW(), DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'SENT', 'Gửi dựng phim 3D đánh giá ống thần kinh hàm dưới'
FROM paraclinical_order_items poi
JOIN paraclinical_orders po ON po.order_id = poi.order_id
WHERE po.order_code = 'CLS-SEED-01';

INSERT IGNORE INTO material_consumptions (visit_id, performed_id, branch_id, material_id, batch_id, quantity, consumed_by, consumed_at)
SELECT ps.visit_id, ps.performed_id, ps.branch_id, m.material_id, mb.batch_id, 1.000, ps.doctor_id, NOW()
FROM performed_services ps
JOIN visits v ON v.visit_id = ps.visit_id AND v.visit_code = 'VIS-SEED-01'
JOIN materials m ON m.material_code = 'VT-COMP-Z350'
JOIN material_batches mb ON mb.branch_id = ps.branch_id AND mb.material_id = m.material_id
WHERE NOT EXISTS (SELECT 1 FROM (SELECT performed_id, material_id FROM material_consumptions) mc WHERE mc.performed_id = ps.performed_id AND mc.material_id = m.material_id);

INSERT IGNORE INTO doctor_cover_grants (visit_id, covering_doctor_id, granted_by_user_id, reason, granted_at, expires_at)
SELECT v.visit_id, sp.staff_id, (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1),
       'Hỗ trợ khám trực thay ca buổi sáng', NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY)
FROM visits v
JOIN staff_profiles sp ON sp.staff_code = 'BS-SEED-01'
WHERE v.visit_code = 'VIS-SEED-02'
  AND NOT EXISTS (SELECT 1 FROM (SELECT visit_id, covering_doctor_id FROM doctor_cover_grants) dcg WHERE dcg.visit_id = v.visit_id AND dcg.covering_doctor_id = sp.staff_id);

INSERT IGNORE INTO point_transactions (profile_id, invoice_id, payment_id, transaction_type, points, balance_after, reason, created_at)
SELECT p.profile_id, p.invoice_id, p.payment_id, 'EARN', 150, 270, 'Tích điểm từ thanh toán hóa đơn HD-SEED-001', NOW()
FROM payments p WHERE p.payment_code = 'PT-SEED-001'
  AND NOT EXISTS (SELECT 1 FROM (SELECT payment_id FROM point_transactions) pt WHERE pt.payment_id = p.payment_id);

INSERT IGNORE INTO refunds (refund_code, idempotency_key, invoice_id, profile_id, branch_id, amount, refund_method, reason, reduce_invoice_total, refunded_by, refunded_at)
SELECT 'HT-SEED-001', 'IDEMP-HT-SEED-001', inv.invoice_id, inv.profile_id, inv.branch_id, 100000.00, 'CASH',
       'Hoàn tiền chênh lệch chi phí vật tư phụ', 0, inv.cashier_id, NOW()
FROM invoices inv WHERE inv.invoice_code = 'HD-SEED-001';

INSERT IGNORE INTO invoice_audit_logs (invoice_id, action_type, old_total_amount, new_total_amount, old_discount_amount, new_discount_amount, old_paid_amount, new_paid_amount, reason, details, performed_by, performed_at)
SELECT inv.invoice_id, 'PAYMENT', 2500000.00, 2500000.00, 0.00, 0.00, 0.00, 1500000.00,
       'Ghi nhận thanh toán đợt 1', 'Thu qua chuyển khoản PT-SEED-001', inv.cashier_id, NOW()
FROM invoices inv WHERE inv.invoice_code = 'HD-SEED-001'
  AND NOT EXISTS (SELECT 1 FROM (SELECT invoice_id, action_type FROM invoice_audit_logs) ial WHERE ial.invoice_id = inv.invoice_id AND ial.action_type = 'PAYMENT');

INSERT IGNORE INTO salary_advance_allocations (advance_id, item_id, allocated_amount, status, created_at, updated_at)
SELECT sa.advance_id, pi.item_id, 500000.00, 'RESERVED', NOW(), NOW()
FROM salary_advances sa
JOIN payroll_items pi ON pi.period_month = MONTH(CURDATE()) AND pi.period_year = YEAR(CURDATE())
WHERE sa.advance_code = 'TU-SEED-001'
  AND NOT EXISTS (SELECT 1 FROM (SELECT advance_id, item_id FROM salary_advance_allocations) saa WHERE saa.advance_id = sa.advance_id AND saa.item_id = pi.item_id)
LIMIT 1;

INSERT IGNORE INTO news_article_likes (news_id, user_id)
SELECT na.news_id, u.user_id
FROM news_articles na
JOIN users u ON u.username IN ('bn_bao', 'bn_thao')
WHERE na.slug = 'cham-soc-sau-nho-rang-khon-seed';

INSERT IGNORE INTO comment_likes (comment_id, user_id)
SELECT c.comment_id, u.user_id
FROM comments c
JOIN news_articles na ON na.news_id = c.news_id AND na.slug = 'cham-soc-sau-nho-rang-khon-seed'
JOIN users u ON u.username = 'bs_hoang';

INSERT IGNORE INTO comment_bans (user_id, banned_by, reason, banned_at, expires_at, is_active)
SELECT u_minh.user_id,
       (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1),
       'Lịch sử cảnh báo spam mẫu (đã hết hiệu lực)', DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 23 DAY), 0
FROM users u_minh
WHERE u_minh.username = 'bn_minh'
  AND NOT EXISTS (SELECT 1 FROM (SELECT user_id FROM comment_bans) cb WHERE cb.user_id = u_minh.user_id);

INSERT IGNORE INTO chat_handover_logs (conversation_id, from_type, to_role, to_staff_id, handover_reason, handed_over_at)
SELECT cc.conversation_id, 'BOT', 'RECEPTIONIST', cc.assigned_staff_id, 'Khách hàng yêu cầu đặt lịch tái khám trực tiếp với nhân viên', NOW()
FROM chat_conversations cc
JOIN patient_profiles pp ON pp.profile_id = cc.profile_id
WHERE pp.patient_code = 'BN-SEED-01'
  AND NOT EXISTS (SELECT 1 FROM (SELECT conversation_id FROM chat_handover_logs) chl WHERE chl.conversation_id = cc.conversation_id);
