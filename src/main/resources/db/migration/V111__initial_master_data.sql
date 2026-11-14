-- ====================================================================================================
-- FLYWAY MIGRATION V111: INITIAL PRODUCTION MASTER DATA FOR CV-DCMS
-- Mục đích: Khởi tạo dữ liệu nền tảng (Master Data) an toàn 100% khi deploy lên Production / VPS.
-- Đặc điểm an toàn:
--   1. KHÔNG chứa dữ liệu giao dịch giả lập (Appointments, Visits, Invoices, Payments, Attendance...).
--   2. Idempotent 100%: Sử dụng WHERE NOT EXISTS / ON DUPLICATE KEY UPDATE, chạy lại nhiều lần không lỗi.
--   3. Tài khoản quản trị mặc định (admin / admin123) chỉ tạo khi chưa tồn tại, KHÔNG ghi đè mật khẩu cũ.
--   4. Khớp tuyệt đối 100% với cấu trúc 74 bảng trong cv_dcms_db_backup.sql (NOT NULL, ENUM, FK, UNIQUE).
-- ====================================================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ====================================================================================================
-- 1. VAI TRÒ HỆ THỐNG (ROLES - 6 VAI TRÒ CHUẨN)
-- ====================================================================================================
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

-- ====================================================================================================
-- 2. TẬP QUYỀN TĨNH CHUẨN HỆ THỐNG (PERMISSIONS - 61 QUYỀN)
-- ====================================================================================================
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

-- Gán quyền mặc định cho các vai trò hệ thống (Idempotent)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r
CROSS JOIN permissions p
WHERE r.role_name = 'ADMIN'
  AND p.permission_code NOT IN ('HR_MY_ATTENDANCE', 'SCHEDULE_MY', 'HR_LEAVE_REQUESTS')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r
JOIN permissions p ON p.permission_code IN (
    'CLINICAL_DAILY_VISITS', 'CLINICAL_WORKFLOWS', 'CLINICAL_DISPATCH',
    'PRESCRIPTION_WRITE', 'TREATMENT_PLAN_MANAGE', 'CLINICAL_PHOTOS',
    'CLINICAL_XRAY', 'CLINICAL_LABO', 'LABO_SUPPLIERS', 'LABO_PRICING', 'MEDICAL_RECORD_VIEW',
    'APPOINTMENT_VIEW', 'RECEPTION_MEMBERSHIPS', 'CUSTOMER_CARE_MANAGE',
    'SCHEDULE_MY', 'HR_MY_ATTENDANCE', 'HR_LEAVE_REQUESTS'
)
WHERE r.role_name = 'BAC_SI'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r
JOIN permissions p ON p.permission_code IN (
    'PATIENT_PROFILE_MANAGE', 'RECEPTION_MEMBERSHIPS', 'APPOINTMENT_MANAGE',
    'RECEPTION_CHECKIN', 'APPOINTMENT_VIEW', 'RECEPTION_DISPATCH',
    'CUSTOMER_CARE_MANAGE', 'SCHEDULE_MY', 'HR_MY_ATTENDANCE'
)
WHERE r.role_name = 'LE_TAN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r
JOIN permissions p ON p.permission_code IN (
    'FINANCE_DASHBOARD', 'INVOICE_CREATE', 'PAYMENT_COLLECT', 'FINANCE_DEPOSIT',
    'FINANCE_DEBT', 'REFUND_MANAGE', 'FINANCE_EXPENSES', 'FINANCE_PAYROLL',
    'FINANCE_PAYROLL_ADVANCES', 'FINANCE_OWNER_DISTRIBUTIONS',
    'FINANCE_REPORTS', 'REPORT_FINANCIAL', 'REPORT_DEBTS', 'REPORT_OPERATIONAL',
    'SCHEDULE_MY', 'HR_MY_ATTENDANCE', 'HR_LEAVE_REQUESTS'
)
WHERE r.role_name = 'KE_TOAN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r
JOIN permissions p ON p.permission_code IN (
    'INVENTORY_STOCK', 'INVENTORY_BATCHES', 'INVENTORY_RECEIPT', 'INVENTORY_ISSUE',
    'INVENTORY_TRANSFER', 'INVENTORY_MATERIALS', 'INVENTORY_CATEGORIES', 'SUPPLIER_MANAGE',
    'SCHEDULE_MY', 'HR_MY_ATTENDANCE'
)
WHERE r.role_name = 'QUAN_LY_KHO'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id
  );

-- ====================================================================================================
-- 3. CHI NHÁNH PHÒNG KHÁM, PHÒNG CHỨC NĂNG, GHẾ NHA, CA LÀM VIỆC & CẤU HÌNH ĐẶT LỊCH
-- ====================================================================================================
INSERT INTO branches (branch_code, branch_name, address, phone, email, tax_code, image_url, opening_time, closing_time, working_days, is_active, created_at, updated_at)
SELECT 'CN01', 'Cơ sở Quận 1', '123 Nguyễn Trãi, Quận 1, TP.HCM', '0901234567', 'cs1@dentalclinic.com', '0311000111', '/uploads/images/storefront.png', '08:00:00', '20:00:00', '2,3,4,5,6,7,CN', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM branches WHERE branch_code = 'CN01');

INSERT INTO branches (branch_code, branch_name, address, phone, email, tax_code, image_url, opening_time, closing_time, working_days, is_active, created_at, updated_at)
SELECT 'CN02', 'Cơ sở Đống Đa', '456 Láng Hạ, Đống Đa, Hà Nội', '0907654321', 'cs2@dentalclinic.com', '0102000222', '/uploads/images/storefront.png', '08:00:00', '20:00:00', '2,3,4,5,6,7,CN', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM branches WHERE branch_code = 'CN02');

INSERT INTO branches (branch_code, branch_name, address, phone, email, tax_code, image_url, opening_time, closing_time, working_days, is_active, created_at, updated_at)
SELECT 'CN03', 'Cơ sở Cầu Giấy', '88 Trần Thái Tông, Cầu Giấy, Hà Nội', '0908889999', 'cs3@dentalclinic.com', '0103000333', '/uploads/images/storefront.png', '08:00:00', '20:00:00', '2,3,4,5,6,7', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM branches WHERE branch_code = 'CN03');

-- Phòng khám (rooms)
INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT b.branch_id, src.room_code, src.room_name, src.room_type, 'ACTIVE'
FROM (
    SELECT 'CN01' branch_code, 'P101' room_code, 'Phòng Khám Tổng quát 1' room_name, 'EXAM' room_type
    UNION ALL SELECT 'CN01', 'P102', 'Phòng Điều trị & Tiểu phẫu', 'TREATMENT'
    UNION ALL SELECT 'CN01', 'P103', 'Phòng Chỉnh nha & Thẩm mỹ', 'TREATMENT'
    UNION ALL SELECT 'CN01', 'P104', 'Phòng Chụp X-Quang NanoPix', 'XRAY'
    UNION ALL SELECT 'CN02', 'P201', 'Phòng Khám Tổng quát Đống Đa', 'EXAM'
    UNION ALL SELECT 'CN02', 'P202', 'Phòng Điều trị Đống Đa', 'TREATMENT'
    UNION ALL SELECT 'CN03', 'P301', 'Phòng Khám Cầu Giấy', 'EXAM'
) src
JOIN branches b ON b.branch_code = src.branch_code
WHERE NOT EXISTS (SELECT 1 FROM rooms r WHERE r.branch_id = b.branch_id AND r.room_code = src.room_code);

-- Ghế nha (dental_chairs)
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT b.branch_id, r.room_id, src.chair_code, src.chair_name, 'AVAILABLE'
FROM (
    SELECT 'CN01' branch_code, 'P101' room_code, 'G01' chair_code, 'Ghế Khám Tổng Quát 01' chair_name
    UNION ALL SELECT 'CN01', 'P102', 'G02', 'Ghế Tiểu Phẫu 02'
    UNION ALL SELECT 'CN01', 'P103', 'G03', 'Ghế Chỉnh Nha 03'
    UNION ALL SELECT 'CN02', 'P201', 'G201', 'Ghế Khám Đống Đa 01'
    UNION ALL SELECT 'CN02', 'P202', 'G202', 'Ghế Điều Trị Đống Đa 02'
    UNION ALL SELECT 'CN03', 'P301', 'G301', 'Ghế Khám Cầu Giấy 01'
) src
JOIN branches b ON b.branch_code = src.branch_code
JOIN rooms r ON r.branch_id = b.branch_id AND r.room_code = src.room_code
WHERE NOT EXISTS (SELECT 1 FROM dental_chairs c WHERE c.branch_id = b.branch_id AND c.chair_code = src.chair_code);

-- Ca làm việc tiêu chuẩn (work_shifts)
INSERT INTO work_shifts (branch_id, shift_code, shift_name, start_time, end_time, is_active)
SELECT b.branch_id, src.shift_code, src.shift_name, src.start_time, src.end_time, 1
FROM (
    SELECT 'CA_SANG' shift_code, 'Ca Sáng (08:00 - 12:00)' shift_name, '08:00:00' start_time, '12:00:00' end_time
    UNION ALL SELECT 'CA_CHIEU', 'Ca Chiều (13:30 - 17:30)', '13:30:00', '17:30:00'
    UNION ALL SELECT 'CA_TOI', 'Ca Tối (17:30 - 20:30)', '17:30:00', '20:30:00'
) src
CROSS JOIN branches b
WHERE b.branch_code IN ('CN01', 'CN02', 'CN03')
  AND NOT EXISTS (SELECT 1 FROM work_shifts ws WHERE ws.branch_id = b.branch_id AND ws.shift_code = src.shift_code);

-- Cấu hình khung giờ đặt lịch hẹn (appointment_session_windows)
INSERT INTO appointment_session_windows (branch_id, preferred_session, start_time, end_time, is_active)
SELECT b.branch_id, src.sess, src.start_time, src.end_time, 1
FROM (
    SELECT 'MORNING' sess, '08:00:00' start_time, '12:00:00' end_time
    UNION ALL SELECT 'AFTERNOON', '13:30:00', '17:30:00'
    UNION ALL SELECT 'EVENING', '17:30:00', '20:00:00'
) src
CROSS JOIN branches b
WHERE b.branch_code IN ('CN01', 'CN02', 'CN03')
  AND NOT EXISTS (SELECT 1 FROM appointment_session_windows w WHERE w.branch_id = b.branch_id AND w.preferred_session = src.sess);

-- ====================================================================================================
-- 4. TÀI KHOẢN QUẢN TRỊ VIÊN HỆ THỐNG MẶC ĐỊNH (admin / admin123)
-- Hash BCrypt (cost 10) của 'admin123': $2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe
-- ====================================================================================================
INSERT INTO users (username, password, email, phone_number, branch_id, user_type, status, is_active, custom_permissions_enabled, created_date, updated_at)
SELECT 'admin', '$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe',
       'admin@dentalclinic.com', '0988888888', b.branch_id, 'STAFF', 'ACTIVE', 1, 0, NOW(), NOW()
FROM branches b
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin');

INSERT INTO user_roles (user_id, role_id)
SELECT u.user_id, r.role_id
FROM users u
JOIN roles r ON r.role_name = 'ADMIN'
WHERE u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.user_id AND ur.role_id = r.role_id);

-- ====================================================================================================
-- 5. CẤU HÌNH THUẾ, TÀI KHOẢN NGÂN HÀNG CHI NHÁNH & HẠNG THẺ THÀNH VIÊN
-- ====================================================================================================
INSERT INTO tax_policies (
    policy_code, policy_name, effective_from, effective_to,
    personal_relief, dependent_relief, casual_tax_rate, non_resident_tax_rate,
    insurance_employee_rate, insurance_employer_rate, legal_reference, is_active, created_at
)
SELECT 'PIT_VN_2020', 'Chính sách thuế TNCN & Bảo hiểm theo NQ 954/2020/UBTVQH14',
       '2020-07-01', NULL, 11000000.00, 4400000.00, 10.00, 20.00, 10.50, 21.50,
       'Nghị quyết 954/2020/UBTVQH14, Điều 22 Luật Thuế TNCN 04/2007/QH12, TT 111/2013/TT-BTC', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM tax_policies WHERE policy_code = 'PIT_VN_2020');

INSERT INTO branch_tax_configs (
    branch_id, entity_type, tax_regime, cit_rate, service_tax_rate, medicine_tax_rate,
    effective_from, effective_to, legal_reference, notes, created_at
)
SELECT b.branch_id, 'ENTERPRISE', 'CIT_STANDARD', 20.00, 2.00, 0.50, '2024-01-01', NULL,
       'Luật Thuế TNDN 14/2008/QH12 sửa đổi & Thông tư 78/2014/TT-BTC',
       'Cấu hình thuế mặc định tại chi nhánh phòng khám nha khoa', NOW()
FROM branches b
WHERE NOT EXISTS (SELECT 1 FROM branch_tax_configs btc WHERE btc.branch_id = b.branch_id);

INSERT INTO branch_bank_accounts (
    branch_id, bank_bin, bank_name, bank_short_name, account_number, account_holder_name,
    is_default, is_active, notes, created_at, updated_at
)
SELECT b.branch_id, '970436', 'Ngân hàng TMCP Ngoại thương Việt Nam', 'Vietcombank',
       CASE b.branch_code
           WHEN 'CN01' THEN '0071001234561'
           WHEN 'CN02' THEN '0011001234562'
           ELSE '0021001234563'
       END,
       'NHA KHOA CUONG VIET', 1, 1, 'Tài khoản nhận thanh toán VietQR mặc định tại chi nhánh', NOW(), NOW()
FROM branches b
WHERE b.branch_code IN ('CN01', 'CN02', 'CN03')
  AND NOT EXISTS (SELECT 1 FROM branch_bank_accounts bba WHERE bba.branch_id = b.branch_id AND bba.is_default = 1);

INSERT INTO membership_tiers (tier_code, tier_name, min_total_spent, discount_percent, spend_per_point, point_value, display_order, is_active) VALUES
('STANDARD', 'Thành viên mới',  0.00,        0.00,  10000.00, 1000.00, 1, 1),
('SILVER',   'Thành viên Bạc',  5000000.00,  5.00,  10000.00, 1000.00, 2, 1),
('GOLD',     'Thành viên Vàng', 20000000.00, 10.00, 10000.00, 1000.00, 3, 1),
('DIAMOND',  'Kim Cương VIP',   50000000.00, 15.00, 10000.00, 1000.00, 4, 1)
ON DUPLICATE KEY UPDATE
    tier_name = VALUES(tier_name),
    min_total_spent = VALUES(min_total_spent),
    discount_percent = VALUES(discount_percent),
    spend_per_point = VALUES(spend_per_point),
    point_value = VALUES(point_value),
    is_active = VALUES(is_active);

-- ====================================================================================================
-- 6. DANH MỤC MÃ BỆNH QUỐC TẾ RĂNG HÀM MẶT (ICD-10 DISEASES)
-- ====================================================================================================
INSERT INTO icd10_diseases (icd10_code, disease_name_vi, disease_name_en, is_dental_common, is_active) VALUES
('K00.6', 'Rối loạn mọc răng (Răng sữa tồn tại lâu / mọc lệch)', 'Disturbances in tooth eruption', 1, 1),
('K01.0', 'Răng mọc ngầm (Embedded teeth)', 'Embedded teeth', 1, 1),
('K01.1', 'Răng ngầm / Răng khôn mọc lệch', 'Impacted teeth', 1, 1),
('K02.0', 'Sâu men răng (Sâu răng giai đoạn sớm)', 'Caries limited to enamel', 1, 1),
('K02.1', 'Sâu ngà răng', 'Caries of dentine', 1, 1),
('K02.2', 'Sâu xê-măng chân răng (Sâu cổ răng)', 'Caries of cementum', 1, 1),
('K02.9', 'Sâu răng không xác định', 'Dental caries, unspecified', 1, 1),
('K03.0', 'Mòn răng quá mức (Mòn cổ răng / mặt nhai)', 'Excessive attrition of teeth', 1, 1),
('K03.6', 'Cặn bám trên răng (Cao răng / Mảng bám vôi hóa)', 'Deposits [accretions] on teeth', 1, 1),
('K04.0', 'Viêm tủy răng', 'Pulpitis', 1, 1),
('K04.1', 'Hoại tử tủy răng (Tủy răng chết)', 'Necrosis of pulp', 1, 1),
('K04.4', 'Viêm quanh cuống răng cấp tính nguồn gốc tủy', 'Acute apical periodontitis of pulpal origin', 1, 1),
('K04.5', 'Viêm quanh cuống răng mạn tính (U hạt quanh chóp)', 'Chronic apical periodontitis', 1, 1),
('K04.7', 'Áp-xe quanh chóp không có lỗ dò', 'Periapical abscess without sinus', 1, 1),
('K05.0', 'Viêm nướu cấp tính (Viêm lợi trùm)', 'Acute gingivitis', 1, 1),
('K05.1', 'Viêm nướu mạn tính', 'Chronic gingivitis', 1, 1),
('K05.3', 'Viêm nha chu mạn tính', 'Chronic periodontitis', 1, 1),
('K07.3', 'Răng mọc lệch vị trí (Chen chúc, thưa, xoay trục)', 'Anomalies of tooth position', 1, 1),
('K08.1', 'Mất răng do tai nạn, nhổ răng hoặc bệnh nha chu', 'Loss of teeth due to accident, extraction or local periodontal disease', 1, 1)
ON DUPLICATE KEY UPDATE
    disease_name_vi = VALUES(disease_name_vi),
    disease_name_en = VALUES(disease_name_en),
    is_dental_common = VALUES(is_dental_common),
    is_active = VALUES(is_active);

-- ====================================================================================================
-- 7. DANH MỤC NHÓM DỊCH VỤ (SERVICE_CATEGORIES) & DỊCH VỤ NHA KHOA (SERVICES)
-- Đầy đủ 10 nhóm dịch vụ thực tế: Khám tổng quát, Lấy cao răng, Trám răng, Nhổ răng,
-- Điều trị tủy, Tẩy trắng răng, Bọc răng sứ, Niềng răng, Implant, Chụp X-quang.
-- ====================================================================================================
INSERT INTO service_categories (category_code, category_name, category_type, display_order, is_active) VALUES
('CAT_EXAM',   'Khám & Tư vấn',              'CONSULTATION', 1, 1),
('CAT_XRAY',   'Chẩn đoán hình ảnh X-quang', 'XRAY',         2, 1),
('CAT_ENDO',   'Điều trị tủy & Trám răng',   'PROCEDURE',    3, 1),
('CAT_SURG',   'Nhổ răng & Tiểu phẫu',       'PROCEDURE',    4, 1),
('CAT_PROSTH', 'Phục hình răng sứ & Labo',   'PROCEDURE',    5, 1),
('CAT_ORTHO',  'Chỉnh nha - Niềng răng',     'PROCEDURE',    6, 1),
('CAT_IMPLANT','Cấy ghép Implant nha khoa',  'PROCEDURE',    7, 1),
('CAT_AESTH',  'Tẩy trắng & Nha khoa thẩm mỹ','PROCEDURE',   8, 1)
ON DUPLICATE KEY UPDATE
    category_name = VALUES(category_name),
    category_type = VALUES(category_type),
    display_order = VALUES(display_order),
    is_active = VALUES(is_active);

INSERT INTO services (
    category_id, service_code, service_name, image_url, service_type, unit,
    base_price, surcharge_amount, estimated_duration_minutes, requires_tooth,
    is_paraclinical, is_bookable_online, warranty_months, description, status, created_at
)
SELECT sc.category_id, s.service_code, s.service_name, s.image_url, s.service_type, s.unit,
       s.base_price, 0.00, s.dur, s.req_tooth, s.is_para, 1, s.warranty, s.descr, 'ACTIVE', NOW()
FROM (
    -- 1. Khám tổng quát
    SELECT 'CAT_EXAM' ccode, 'DV-KHAM-TQ' service_code, 'Khám & Tư vấn tổng quát' service_name,
           '/uploads/images/clinic_room.png' image_url, 'CONSULTATION' service_type, 'Lượt' unit,
           150000.00 base_price, 20 dur, 0 req_tooth, 0 is_para, NULL warranty,
           'Khám tổng quát sức khỏe răng miệng và tư vấn phác đồ điều trị chuẩn Bộ Y Tế.' descr
    -- 2. Lấy cao răng
    UNION ALL SELECT 'CAT_EXAM', 'DV-CAOVOI', 'Lấy cao răng & Đánh bóng siêu âm',
           '/uploads/images/clinic_treatment.png', 'PROCEDURE', 'Hàm',
           300000.00, 30, 0, 0, NULL,
           'Làm sạch mảng bám vôi răng trên và dưới nướu bằng sóng siêu âm êm ái.'
    -- 3. Chụp X-quang
    UNION ALL SELECT 'CAT_XRAY', 'DV-XQ-QUANH', 'Chụp X-quang quanh chóp kỹ thuật số (NanoPix)',
           '/uploads/images/clinic_treatment.png', 'XRAY', 'Phim',
           100000.00, 15, 1, 1, NULL,
           'Chụp phim X-quang kỹ thuật số cận chóp khảo sát chân răng và vùng quanh chóp.'
    UNION ALL SELECT 'CAT_XRAY', 'DV-XQ-PANO', 'Chụp X-quang toàn cảnh Panorama kỹ thuật số',
           '/uploads/images/clinic_treatment.png', 'XRAY', 'Phim',
           300000.00, 15, 0, 1, NULL,
           'Chụp phim toàn cảnh 2 hàm đánh giá tổng thể răng khôn, xương hàm và khớp thái dương hàm.'
    -- 4. Trám răng
    UNION ALL SELECT 'CAT_ENDO', 'DV-TRAM-COMP', 'Trám răng thẩm mỹ Composite 3M',
           '/uploads/images/clinic_treatment.png', 'PROCEDURE', 'Răng',
           450000.00, 30, 1, 0, 12,
           'Phục hồi thân răng sâu bằng vật liệu Composite 3M Filtek Z350 XT bền màu tự nhiên.'
    -- 5. Điều trị tủy
    UNION ALL SELECT 'CAT_ENDO', 'DV-NOINHA-RHAM', 'Điều trị tủy răng hàm (Nội nha)',
           '/uploads/images/clinic_treatment.png', 'PROCEDURE', 'Răng',
           1800000.00, 45, 1, 0, 24,
           'Đo chiều dài ống tủy bằng Apex Locator, tạo hình trâm máy Ni-Ti và hàn kín Gutta Percha.'
    -- 6. Nhổ răng
    UNION ALL SELECT 'CAT_SURG', 'DV-NHO-THUONG', 'Nhổ răng vĩnh viễn lung lay / chân răng',
           '/uploads/images/clinic_treatment.png', 'PROCEDURE', 'Răng',
           500000.00, 30, 1, 0, NULL,
           'Nhổ răng vĩnh viễn lung lay hoặc chân răng còn sót bằng kỹ thuật gây tê tại chỗ an toàn.'
    UNION ALL SELECT 'CAT_SURG', 'DV-NHO-RKHON', 'Tiểu phẫu nhổ răng khôn mọc lệch Piezotome',
           '/uploads/images/clinic_treatment.png', 'PROCEDURE', 'Răng',
           2500000.00, 45, 1, 0, NULL,
           'Nhổ răng khôn không sang chấn bằng máy siêu âm Piezotome.'
    -- 7. Tẩy trắng răng
    UNION ALL SELECT 'CAT_AESTH', 'DV-TAY-TRANG', 'Tẩy trắng răng công nghệ Laser Whitening toàn hàm',
           '/uploads/images/whitening.png', 'PROCEDURE', 'Liệu trình',
           2500000.00, 60, 0, 0, 6,
           'Tẩy trắng răng hoạt hóa bằng ánh sáng Laser kết hợp thuốc tẩy Pola Office chính hãng.'
    -- 8. Bọc răng sứ
    UNION ALL SELECT 'CAT_PROSTH', 'DV-SU-TITAN', 'Bọc răng sứ hợp kim Titan công nghệ CAD/CAM',
           '/uploads/services/e4f1d111-0d3a-4642-95a9-79b669c75ff7.jpg', 'PROCEDURE', 'Răng',
           2500000.00, 60, 1, 0, 36,
           'Mão sứ sườn hợp kim Titan phủ sứ thẩm mỹ Vita Đức, chịu lực nhai tốt.'
    UNION ALL SELECT 'CAT_PROSTH', 'DV-SU-ZIRCO', 'Bọc răng sứ Zirconia cao cấp',
           '/uploads/services/e4f1d111-0d3a-4642-95a9-79b669c75ff7.jpg', 'PROCEDURE', 'Răng',
           4500000.00, 60, 1, 0, 60,
           'Phục hình mão răng toàn sứ Zirconia DDBio Đức thẩm mỹ cao.'
    UNION ALL SELECT 'CAT_PROSTH', 'DV-SU-CERCON', 'Bọc răng toàn sứ cao cấp Cercon HT (Đức)',
           '/uploads/services/e4f1d111-0d3a-4642-95a9-79b669c75ff7.jpg', 'PROCEDURE', 'Răng',
           6000000.00, 60, 1, 0, 120,
           'Dòng sứ nguyên khối Cercon HT chính hãng Dentsply Sirona Đức.'
    -- 9. Niềng răng
    UNION ALL SELECT 'CAT_ORTHO', 'DV-NIENG-KL', 'Niềng răng mắc cài kim loại tự buộc',
           '/uploads/images/clinic_room.png', 'PROCEDURE', 'Liệu trình',
           32000000.00, 60, 0, 0, 36,
           'Chỉnh nha toàn hàm với mắc cài tự buộc chuẩn quốc tế.'
    -- 10. Implant
    UNION ALL SELECT 'CAT_IMPLANT', 'DV-IMP-GER', 'Cấy ghép trụ Implant nha khoa MIS C1 (Đức)',
           '/uploads/images/clinic_treatment.png', 'PROCEDURE', 'Trụ',
           22000000.00, 90, 1, 0, 120,
           'Phẫu thuật cấy ghép trụ Implant Titanium nguyên chất tích hợp xương nhanh.'
) s
JOIN service_categories sc ON sc.category_code = s.ccode
ON DUPLICATE KEY UPDATE
    category_id = VALUES(category_id),
    service_name = VALUES(service_name),
    image_url = VALUES(image_url),
    service_type = VALUES(service_type),
    unit = VALUES(unit),
    base_price = VALUES(base_price),
    estimated_duration_minutes = VALUES(estimated_duration_minutes),
    requires_tooth = VALUES(requires_tooth),
    is_paraclinical = VALUES(is_paraclinical),
    warranty_months = VALUES(warranty_months),
    description = VALUES(description),
    status = VALUES(status);

-- ====================================================================================================
-- 8. QUY TRÌNH KỸ THUẬT LÂM SÀNG CHUẨN BỘ Y TẾ (CLINICAL WORKFLOWS - QĐ 3207/QĐ-BYT)
-- ====================================================================================================
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
    service_id = VALUES(service_id),
    status = VALUES(status),
    updated_at = NOW();

INSERT INTO clinical_workflow_versions (template_id, version_number, status, flow_mode, effective_from, change_summary, created_by, approved_by, created_at, published_at)
SELECT t.template_id, 1, 'PUBLISHED', 'CONTINUOUS', NOW(), 'Phiên bản chuẩn hóa theo Quyết định 3207/QĐ-BYT của Bộ Y Tế',
       (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1),
       (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1),
       NOW(), NOW()
FROM clinical_workflow_templates t
WHERE t.template_code IN ('QT-NHO-RKHON', 'QTKT-CAOVOI', 'QTKT-TRAM-RANG', 'QTKT-TUY-RH', 'QTKT-IMP-GER', 'QTKT-SU-TITAN')
  AND NOT EXISTS (
      SELECT 1 FROM clinical_workflow_versions v WHERE v.template_id = t.template_id AND v.version_number = 1
  );

INSERT INTO clinical_workflow_sections (version_id, section_code, section_title, content, display_order)
SELECT v.version_id, sec.section_code, sec.section_title, sec.content_text, sec.display_order
FROM clinical_workflow_versions v
JOIN clinical_workflow_templates t ON t.template_id = v.template_id AND v.version_number = 1
CROSS JOIN (
    SELECT 'I' section_code, 'I. ĐẠI CƯƠNG & CHỈ ĐỊNH ĐIỀU TRỊ' section_title,
           'Áp dụng cho người bệnh có bệnh lý răng hàm mặt đã được bác sĩ khám lâm sàng, chẩn đoán xác định theo mã ICD-10 và đồng ý thực hiện thủ thuật.' content_text, 1 display_order
    UNION ALL SELECT 'II', 'II. CHỐNG CHỈ ĐỊNH',
           'Chống chỉ định tạm thời với người bệnh đang sốt cao cấp tính, huyết áp tăng cao chưa kiểm soát (>160/100 mmHg), rối loạn đông máu nặng hoặc suy tim mất bù.', 2
    UNION ALL SELECT 'III', 'III. CHUẨN BỊ (NHÂN SỰ, PHƯƠNG TIỆN & NGƯỜI BỆNH)',
           '1. Người thực hiện: Bác sĩ Răng Hàm Mặt có chứng chỉ hành nghề và Trợ thủ nha khoa.\n2. Phương tiện: Bộ khám vô trùng, tay khoan, vật tư tiêu hao đạt chuẩn.\n3. Người bệnh: Được giải thích kỹ quy trình, chi phí và ký cam kết trước thủ thuật.', 3
    UNION ALL SELECT 'IV', 'IV. CÁC BƯỚC TIẾN HÀNH KỸ THUẬT',
           'Thực hiện tuần tự từng bước lâm sàng trong danh sách bước quy trình bên dưới, tuân thủ nghiêm ngặt quy tắc kiểm soát nhiễm khuẩn.', 4
    UNION ALL SELECT 'V', 'V. THEO DÕI & CHĂM SÓC SAU THỦ THUẬT',
           'Theo dõi toàn trạng tại phòng khám 15-30 phút sau can thiệp. Hướng dẫn vệ sinh răng miệng, chế độ ăn uống và lịch tái khám.', 5
    UNION ALL SELECT 'VI', 'VI. TAI BIẾN & HƯỚNG XỬ TRÍ',
           'Nếu có chảy máu kéo dài, đau nhức hoặc phản ứng dị ứng thuốc tê: xử trí theo phác đồ cấp cứu nha khoa tại chỗ và hội chẩn bác sĩ trưởng khoa.', 6
) sec
WHERE t.template_code IN ('QT-NHO-RKHON', 'QTKT-CAOVOI', 'QTKT-TRAM-RANG', 'QTKT-TUY-RH', 'QTKT-IMP-GER', 'QTKT-SU-TITAN')
  AND NOT EXISTS (
      SELECT 1 FROM clinical_workflow_sections s WHERE s.version_id = v.version_id AND s.section_code = sec.section_code
  );

INSERT INTO clinical_workflow_steps (
    version_id, step_code, step_name, step_description, step_type, sequence_order,
    is_optional, queue_priority, completion_criteria, requires_clinical_approval, estimated_duration_minutes, status
)
SELECT v.version_id, st.step_code, st.step_name, st.step_description, st.step_type, st.seq,
       0, 0, st.criteria, st.req_approval, st.duration_min, 'ACTIVE'
FROM (
    -- QT-NHO-RKHON
    SELECT 'QT-NHO-RKHON' tpl_code, 'STEP_1' step_code, 'Chụp X-quang & Đánh giá chân răng' step_name,
           'Kiểm tra hình thể chân răng khôn và khoảng cách tới ống thần kinh răng dưới' step_description,
           'PARACLINICAL' step_type, 1 seq, 'Phim X-quang rõ nét chóp chân răng và ống thần kinh' criteria, 0 req_approval, 15 duration_min
    UNION ALL SELECT 'QT-NHO-RKHON', 'STEP_2', 'Tiểu phẫu nhổ răng & Khâu vạt',
           'Sát khuẩn, gây tê vùng, mở xương chia cắt chân răng bằng sóng siêu âm Piezotome và khâu đóng vạt',
           'CLINICAL', 2, 'Lấy trọn vẹn chân răng khôn, cầm máu tốt, khâu vạt kín', 0, 30
    -- QTKT-CAOVOI
    UNION ALL SELECT 'QTKT-CAOVOI', 'CV-B1', 'Bước 1: Thăm khám và đánh giá tình trạng cao răng',
           'Khám khoang miệng tổng quát, xác định mức độ cao răng trên lợi, dưới lợi và mức độ viêm nướu.',
           'CLINICAL', 1, 'Xác định độ 1-3 của cao răng và giải thích quy trình cho bệnh nhân', 0, 5
    UNION ALL SELECT 'QTKT-CAOVOI', 'CV-B2', 'Bước 2: Lấy cao răng siêu âm trên và dưới lợi',
           'Dùng đầu rung máy siêu âm nghiêng góc 15-20 độ lấy sạch mảng bám và vôi răng dưới tia nước làm mát vô trùng.',
           'TREATMENT', 2, 'Sạch hoàn toàn mảng bám vôi răng bề mặt và dưới khe nướu', 0, 15
    UNION ALL SELECT 'QTKT-CAOVOI', 'CV-B3', 'Bước 3: Đánh bóng bề mặt men răng',
           'Dùng chổi cước mịn và bột đánh bóng Prophy paste làm nhẵn mịn toàn bộ mặt ngoài, mặt trong và kẽ răng.',
           'TREATMENT', 3, 'Bề mặt men răng trơn bóng, nhẵn mịn, không còn vết ố màu', 0, 5
    UNION ALL SELECT 'QTKT-CAOVOI', 'CV-B4', 'Bước 4: Bơm rửa sát khuẩn và dặn dò vệ sinh',
           'Bơm rửa khe nướu bằng dung dịch Chlorhexidine 0.12%, kiểm tra cầm máu và hướng dẫn chải răng đúng cách.',
           'FOLLOW_UP', 4, 'Vùng nướu cầm máu hoàn toàn, bệnh nhân nắm vững cách vệ sinh răng miệng', 0, 5
    -- QTKT-TRAM-RANG
    UNION ALL SELECT 'QTKT-TRAM-RANG', 'TR-B1', 'Bước 1: Thăm khám, làm sạch và so màu Composite',
           'Khám xác định độ sâu xoang sâu, kiểm tra tủy sống và dùng bảng so màu VITA chọn tone màu Composite phù hợp.',
           'CLINICAL', 1, 'Chọn đúng màu Composite hài hòa, xác định loại xoang Black (I-V)', 0, 5
    UNION ALL SELECT 'QTKT-TRAM-RANG', 'TR-B2', 'Bước 2: Sửa soạn xoang hàn và cách ly răng',
           'Nạo sạch toàn bộ ngà mủn sâu hoại tử, vát men bờ xoang 45 độ và đặt bông gòn/đê cao su cách ly nước bọt.',
           'TREATMENT', 2, 'Xoang sạch ngà sâu, vùng làm việc khô ráo tuyệt đối', 0, 10
    UNION ALL SELECT 'QTKT-TRAM-RANG', 'TR-B3', 'Bước 3: Xử lý bề mặt men ngà (Etching & Bonding)',
           'Etching bằng Acid Phosphoric 37%, rửa sạch, thổi khô giữ ẩm ngà, quét keo Bonding và chiếu đèn quang trùng hợp 20s.',
           'TREATMENT', 3, 'Lớp bonding phủ đều bóng nhẹ, quang trùng hợp cứng chắc', 0, 5
    UNION ALL SELECT 'QTKT-TRAM-RANG', 'TR-B4', 'Bước 4: Đắp Composite từng lớp và quang trùng hợp',
           'Đắp Composite từng lớp không quá 2mm, điêu khắc tái tạo hình thể giải phẫu múi rãnh và chiếu đèn LED 20-40s mỗi lớp.',
           'TREATMENT', 4, 'Lớp Composite bám dính khít sát bờ, đúng hình thái giải phẫu mặt nhai', 0, 15
    UNION ALL SELECT 'QTKT-TRAM-RANG', 'TR-B5', 'Bước 5: Kiểm tra khớp cắn và đánh bóng miếng trám',
           'Dùng giấy cắn kiểm tra tiếp xúc trung tâm và trượt sang bên, mài chỉnh điểm cộm và đánh bóng bằng đĩa Sof-Lex.',
           'TREATMENT', 5, 'Không cộm vướng khớp cắn, ranh giới miếng hàn láng mịn', 0, 10
    -- QTKT-TUY-RH
    UNION ALL SELECT 'QTKT-TUY-RH', 'DT-B1', 'Bước 1: Thăm khám lâm sàng, chụp X-quang & Gây tê',
           'Chụp phim X-quang cận chóp khảo sát số lượng/độ cong ống tủy và tiến hành gây tê tại chỗ/gây tê vùng.',
           'PARACLINICAL', 1, 'Vô cảm hoàn toàn vùng can thiệp, có phim X-quang rõ nét chóp chân răng', 0, 10
    UNION ALL SELECT 'QTKT-TUY-RH', 'DT-B2', 'Bước 2: Mở tủy và bộc lộ các lỗ ống tủy',
           'Mở lối vào buồng tủy, loại bỏ trần buồng tủy và dùng thám châm DG-16 xác định toàn bộ lỗ ống tủy.',
           'TREATMENT', 2, 'Mở thông thẳng góc vào ống tủy, thấy rõ toàn bộ các lỗ ống tủy, không thủng sàn', 1, 15
    UNION ALL SELECT 'QTKT-TUY-RH', 'DT-B3', 'Bước 3: Đo chiều dài làm việc và tạo hình ống tủy',
           'Dùng trâm K-file kết hợp máy định vị chóp Apex Locator xác định chiều dài làm việc và tạo hình bằng trâm máy Ni-Ti.',
           'TREATMENT', 3, 'Xác định chính xác chiều dài làm việc từng ống tủy, ống tủy thuôn đều', 1, 20
    UNION ALL SELECT 'QTKT-TUY-RH', 'DT-B4', 'Bước 4: Bơm rửa siêu âm và đặt thuốc sát trùng ống tủy',
           'Bơm rửa luân phiên bằng NaOCl 2.5% kích hoạt siêu âm nội nha, rửa nước muối sinh lý và thấm khô bằng côn giấy.',
           'TREATMENT', 4, 'Ống tủy sạch mùn ngà, dịch bơm trong, không còn mùi hôi hoại tử', 0, 15
    UNION ALL SELECT 'QTKT-TUY-RH', 'DT-B5', 'Bước 5: Thử côn Gutta Percha và hàn kín ống tủy 3 chiều',
           'Thử côn chính đạt tug-back, trộn xi măng nội nha AH-Plus/Bioceramic, lèn bít kín ống tủy và chụp phim kiểm tra.',
           'TREATMENT', 5, 'Phim X-quang kiểm tra cho thấy ống tủy bít kín sát chóp sinh học', 1, 15
    -- QTKT-IMP-GER
    UNION ALL SELECT 'QTKT-IMP-GER', 'IM-B1', 'Bước 1: Khảo sát phim X-quang, vô trùng phòng mổ & Gây tê',
           'Đo kích thước xương trên phim X-quang, chuẩn bị phẫu trường vô trùng tuyệt đối và gây tê tại chỗ vùng cấy ghép.',
           'PARACLINICAL', 1, 'Phòng mổ vô trùng tuyệt đối, xác định kích thước trụ Implant phù hợp', 1, 15
    UNION ALL SELECT 'QTKT-IMP-GER', 'IM-B2', 'Bước 2: Khoan tạo ổ xương và đặt trụ Implant đạt lực Torque',
           'Rạch tạo vạt, khoan tạo ổ xương theo tuần tự mũi khoan và đặt trụ Implant đạt độ ổn định sơ khởi 35-45 Ncm.',
           'TREATMENT', 2, 'Trụ Implant nằm dưới mào xương 0.5-1mm, lực Torque đạt chuẩn', 1, 30
    UNION ALL SELECT 'QTKT-IMP-GER', 'IM-B3', 'Bước 3: Đặt nắp lành thương (Healing Abutment) và khâu đóng vạt',
           'Lắp Cover screw hoặc Healing Abutment, khâu đóng vạt niêm mạc và chụp phim X-quang kiểm tra ngay sau cấy.',
           'FOLLOW_UP', 3, 'Vạt khâu kín ôm khít trụ, phim X-quang kiểm tra trục Implant chuẩn xác', 0, 15
    -- QTKT-SU-TITAN
    UNION ALL SELECT 'QTKT-SU-TITAN', 'SU-B1', 'Bước 1: Thăm khám khớp cắn, mài sửa soạn cùi răng & lấy dấu',
           'Chọn màu sứ VITA 3D Master, gây tê mài cùi răng chuẩn đường hoàn tất, lấy dấu Silicon 2 thì và gắn răng tạm.',
           'TREATMENT', 1, 'Dấu cao su rõ toàn bộ đường hoàn tất 360 độ, răng tạm thẩm mỹ', 1, 35
    UNION ALL SELECT 'QTKT-SU-TITAN', 'SU-B2', 'Bước 2: Thử sườn sứ và gắn cố định mão răng sứ hoàn tất',
           'Kiểm tra độ khít sát đường hoàn tất, điểm tiếp xúc bên, khớp cắn và gắn cố định bằng xi măng Fuji Plus / RelyX.',
           'FOLLOW_UP', 2, 'Mão sứ khít sát hoàn toàn bờ viền, sạch xi măng dư dưới nướu', 1, 25
) st
JOIN clinical_workflow_templates t ON t.template_code = st.tpl_code
JOIN clinical_workflow_versions v ON v.template_id = t.template_id AND v.version_number = 1
ON DUPLICATE KEY UPDATE
    step_name = VALUES(step_name),
    step_description = VALUES(step_description),
    step_type = VALUES(step_type),
    completion_criteria = VALUES(completion_criteria),
    requires_clinical_approval = VALUES(requires_clinical_approval),
    estimated_duration_minutes = VALUES(estimated_duration_minutes),
    status = VALUES(status);

-- Phân quyền vai trò thực hiện bước quy trình (clinical_workflow_step_roles)
INSERT INTO clinical_workflow_step_roles (step_id, role_id, is_primary)
SELECT s.step_id, r.role_id, 1
FROM clinical_workflow_steps s
JOIN roles r ON r.role_name = 'BAC_SI'
WHERE NOT EXISTS (
    SELECT 1 FROM clinical_workflow_step_roles sr
    WHERE sr.step_id = s.step_id AND sr.role_id = r.role_id
);

-- Liên kết dịch vụ cận lâm sàng X-quang (clinical_workflow_step_paraclinical)
INSERT INTO clinical_workflow_step_paraclinical (step_id, service_id, is_required, result_required)
SELECT s.step_id, sv.service_id, 1, 1
FROM clinical_workflow_steps s
JOIN services sv ON sv.service_code = 'DV-XQ-QUANH'
WHERE s.step_code IN ('STEP_1', 'DT-B1', 'IM-B1')
  AND NOT EXISTS (
      SELECT 1 FROM clinical_workflow_step_paraclinical sp
      WHERE sp.step_id = s.step_id AND sp.service_id = sv.service_id
  );

-- Quy tắc chuyển bước tuần tự (clinical_workflow_transition_rules)
INSERT INTO clinical_workflow_transition_rules (version_id, from_step_id, to_step_id, condition_expression, priority_order, is_default)
SELECT s1.version_id, s1.step_id, s2.step_id, '{"xrayResult":"CLEAR"}', 1, 1
FROM clinical_workflow_steps s1
JOIN clinical_workflow_steps s2
  ON s2.version_id = s1.version_id AND s2.sequence_order = s1.sequence_order + 1
WHERE NOT EXISTS (
    SELECT 1 FROM clinical_workflow_transition_rules tr
    WHERE tr.version_id = s1.version_id AND tr.from_step_id = s1.step_id AND tr.to_step_id = s2.step_id
);

-- ====================================================================================================
-- 9. DANH MỤC NHÀ CUNG CẤP, VẬT TƯ NHA KHOA & THUỐC BIỆT DƯỢC (MASTER CATALOG)
-- ====================================================================================================
INSERT INTO suppliers (supplier_code, supplier_name, tax_code, phone_number, address, is_active) VALUES
('NCC-3M',        'Công ty Thiết bị Nha khoa 3M Việt Nam',        '0101234567', '0901112233', 'Tầng 12, Quận 1, TP.HCM',            1),
('NCC-SEPTODONT', 'Công ty Dược & Vật tư Nha khoa Septodont',     '0102345678', '0902223344', '88 Nguyễn Chí Thanh, Đống Đa, HN',   1),
('NCC-LABO-AV',   'Xưởng Phục Hình Răng Sứ Labo An Việt',         '0109887766', '0909887766', '15 Hoàng Cầu, Đống Đa, Hà Nội',      1)
ON DUPLICATE KEY UPDATE
    supplier_name = VALUES(supplier_name),
    tax_code = VALUES(tax_code),
    phone_number = VALUES(phone_number),
    address = VALUES(address),
    is_active = VALUES(is_active);

INSERT INTO materials (material_code, material_name, category, unit, min_stock_threshold, is_active) VALUES
('VT-COMP-Z350',  'Composite đặc 3M Filtek Z350 XT (Màu A2)', 'Trám răng',  'Ống', 10.000, 1),
('VT-TE-SEPTO',   'Thuốc tê Septanest 1:100.000 (Hộp 50 ống)', 'Thuốc tê',   'Hộp',  5.000, 1),
('VT-TH-GANG',    'Găng tay y tế Nitrile không bột (Hộp 100c)', 'Tiêu hao',   'Hộp', 20.000, 1),
('TH-AUG-1G',     'Augmentin 1g (Amoxicillin + Clavulanic)',    'Kháng sinh', 'Viên',30.000, 1),
('TH-PARA-500',   'Efferalgan / Paracetamol 500mg',             'Giảm đau',   'Viên',50.000, 1)
ON DUPLICATE KEY UPDATE
    material_name = VALUES(material_name),
    category = VALUES(category),
    unit = VALUES(unit),
    min_stock_threshold = VALUES(min_stock_threshold),
    is_active = VALUES(is_active);

INSERT INTO medications (drug_code, drug_name, active_ingredient, unit, sale_price, usage_instruction, is_active, material_id)
SELECT 'MED-AUG1G', 'Augmentin 1g', 'Amoxicillin 875mg + Acid Clavulanic 125mg', 'Viên', 25000.00, 'Uống sau ăn sáng và tối', 1,
       (SELECT material_id FROM materials WHERE material_code = 'TH-AUG-1G' LIMIT 1)
WHERE NOT EXISTS (SELECT 1 FROM medications WHERE drug_code = 'MED-AUG1G');

INSERT INTO medications (drug_code, drug_name, active_ingredient, unit, sale_price, usage_instruction, is_active, material_id)
SELECT 'MED-PARA500', 'Efferalgan 500mg', 'Paracetamol 500mg', 'Viên', 5000.00, 'Uống sau ăn khi đau, cách nhau 4-6 giờ', 1,
       (SELECT material_id FROM materials WHERE material_code = 'TH-PARA-500' LIMIT 1)
WHERE NOT EXISTS (SELECT 1 FROM medications WHERE drug_code = 'MED-PARA500');

INSERT INTO medications (drug_code, drug_name, active_ingredient, unit, sale_price, usage_instruction, is_active, material_id)
SELECT 'MED-ALPHA', 'Alpha Choay 4.2mg', 'Alphachymotrypsin', 'Viên', 4000.00, 'Ngậm dưới lưỡi 4 viên/ngày chia 2 lần', 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM medications WHERE drug_code = 'MED-ALPHA');
