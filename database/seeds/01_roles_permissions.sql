-- ====================================================================================================
-- CV-DCMS: 01_roles_permissions.sql (NHÓM MASTER DATA: VAI TRÒ & PHÂN QUYỀN RBAC)
-- Đối chiếu 100% với Schema cv_dcms_db_backup.sql và PermissionDataInitializer.java
-- An toàn khi chạy nhiều lần (Idempotent - ON DUPLICATE KEY UPDATE / INSERT IGNORE)
-- ====================================================================================================

SET NAMES utf8mb4;

-- 1. KHỞI TẠO 6 VAI TRÒ HỆ THỐNG BẮT BUỘC (roles)
INSERT INTO roles (role_id, role_name, display_name, description, is_system, is_active) VALUES
    (1, 'ADMIN',       'Quản trị viên',          'Toàn quyền quản trị hệ thống, cấu hình chi nhánh, nhân sự và bảo mật', 1, 1),
    (2, 'BAC_SI',      'Bác sĩ',                 'Khám lâm sàng, lập kế hoạch điều trị, kê đơn thuốc và chỉ định cận lâm sàng', 1, 1),
    (3, 'LE_TAN',      'Lễ tân',                 'Tiếp đón bệnh nhân, quản lý lịch hẹn, điều phối hàng đợi và chăm sóc khách hàng', 1, 1),
    (4, 'QUAN_LY_KHO', 'Quản lý kho',            'Quản lý danh mục vật tư, nhà cung cấp, lô hàng, phiếu nhập/xuất kho và đặt Labo', 1, 1),
    (5, 'KE_TOAN',     'Kế toán',                'Thu ngân viện phí, quản lý hóa đơn, hoàn tiền, quỹ tiền mặt/ngân hàng và bảng lương', 1, 1),
    (6, 'BENH_NHAN',   'Bệnh nhân / Khách hàng', 'Đặt lịch khám trực tuyến, tra cứu hồ sơ bệnh án, đơn thuốc và hóa đơn cá nhân', 1, 1)
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    description  = VALUES(description),
    is_system    = VALUES(is_system),
    is_active    = VALUES(is_active);

-- 2. KHỞI TẠO TOÀN BỘ 61 MÃ QUYỀN HỆ THỐNG (permissions)
INSERT INTO permissions (permission_id, permission_code, module, description) VALUES
    (1,  'DASHBOARD_VIEW',                'DASHBOARD',     'Truy cập trang tổng quan'),
    (2,  'APPT_MANAGE',                   'APPOINTMENT',   'Đặt lịch và điều phối lịch hẹn'),
    (3,  'VISIT_RECEPTION',               'RECEPTION',     'Tiếp đón và Check-in'),
    (4,  'QUEUE_OPERATE',                 'RECEPTION',     'Điều phối hàng đợi'),
    (5,  'PATIENT_DIRECTORY',             'PATIENT',       'Quản lý danh sách bệnh nhân'),
    (6,  'CLINICAL_EXAM',                 'CLINICAL',      'Khám bệnh và lập bệnh án'),
    (7,  'PARACLINICAL_PROCESS',          'PARACLINICAL',  'Xử lý chỉ định cận lâm sàng'),
    (8,  'WORKFLOW_VIEW',                 'WORKFLOW',      'Xem quy trình điều trị chuẩn'),
    (9,  'WORKFLOW_MANAGE',               'WORKFLOW',      'Thiết lập quy trình điều trị'),
    (10, 'PRESCRIPTION_CREATE',           'PHARMACY',      'Kê đơn thuốc'),
    (11, 'LABO_MANAGE',                   'LABO',          'Theo dõi đơn đặt Labo'),
    (12, 'CSKH_FOLLOWUP',                 'CUSTOMER_CARE', 'Chăm sóc khách hàng'),
    (13, 'CHAT_SUPPORT',                  'COMMUNICATION', 'Tư vấn trực tuyến'),
    (14, 'BILLING_INVOICE',               'FINANCE',       'Quản lý hóa đơn và thu tiền'),
    (15, 'FINANCE_EXPENSE',               'FINANCE',       'Quản lý phiếu chi và công nợ'),
    (16, 'INVENTORY_STOCK',               'INVENTORY',     'Quản lý tồn kho và xuất nhập'),
    (17, 'SUPPLIER_MANAGE',               'INVENTORY',     'Quản lý nhà cung cấp'),
    (18, 'PROMOTION_MANAGE',              'MARKETING',     'Quản lý khuyến mãi và hạng thẻ'),
    (19, 'CONTENT_NEWS',                  'MARKETING',     'Quản lý tin tức và bình luận'),
    (20, 'HR_STAFF_MANAGE',               'HR',            'Quản lý hồ sơ nhân sự'),
    (21, 'SCHEDULE_MANAGE',               'HR',            'Phân ca và duyệt nghỉ phép'),
    (22, 'SCHEDULE_MY',                   'HR',            'Xem lịch làm việc cá nhân'),
    (23, 'PAYROLL_MANAGE',                'HR',            'Quản lý bảng lương'),
    (24, 'REPORT_ANALYTICS',              'REPORT',        'Xem báo cáo thống kê'),
    (25, 'SYSTEM_ADMIN',                  'SYSTEM',        'Quản trị hệ thống'),
    (26, 'PORTAL_ACCESS',                 'PORTAL',        'Cổng thông tin bệnh nhân'),
    (27, 'APPT_POLICY_MANAGE',            'APPOINTMENT',   'Cấu hình chính sách ca khám'),
    (28, 'CLINICAL_ODONTOGRAM_EDIT',      'CLINICAL',      'Cập nhật sơ đồ răng (Odontogram)'),
    (29, 'CLINICAL_TREATMENT_PLAN',       'CLINICAL',      'Lập và quản lý kế hoạch điều trị'),
    (30, 'CLINICAL_SERVICE_ORDER',        'CLINICAL',      'Chỉ định dịch vụ và thủ thuật'),
    (31, 'CLINICAL_EMR_UNLOCK',           'CLINICAL',      'Mở khóa bệnh án đã hoàn tất'),
    (32, 'PARACLINICAL_XRAY_UPLOAD',      'PARACLINICAL',  'Tải kết quả X-Quang / NanoPix'),
    (33, 'PRESCRIPTION_DISPENSE',         'PHARMACY',      'Xác nhận phát/bán thuốc theo đơn'),
    (34, 'MEDICATION_CATALOG_MANAGE',     'PHARMACY',      'Quản lý danh mục thuốc'),
    (35, 'CSKH_DOCTOR_RESPOND',           'CUSTOMER_CARE', 'Bác sĩ phản hồi chuyên môn CSKH'),
    (36, 'BILLING_REFUND',                'FINANCE',       'Thực hiện hoàn tiền cho bệnh nhân'),
    (37, 'FINANCE_CASHBOOK',              'FINANCE',       'Quản lý sổ quỹ tiền mặt & ngân hàng'),
    (38, 'FINANCE_HANDOVER',              'FINANCE',       'Bàn giao ca quỹ thu ngân'),
    (39, 'FINANCE_TAX_CONFIG',            'FINANCE',       'Cấu hình thuế chi nhánh'),
    (40, 'INVENTORY_RECEIPT_CREATE',      'INVENTORY',     'Lập phiếu nhập kho vật tư'),
    (41, 'INVENTORY_ISSUE_CREATE',        'INVENTORY',     'Lập phiếu xuất kho / điều chuyển'),
    (42, 'HR_MY_ATTENDANCE',              'HR',            'Chấm công & đơn từ cá nhân'),
    (43, 'HR_ATTENDANCE_MANAGE',          'HR',            'Quản lý bảng chấm công nhân viên'),
    (44, 'HR_LEAVE_APPROVE',              'HR',            'Phê duyệt đơn xin nghỉ phép'),
    (45, 'HR_SALARY_ADVANCE',             'HR',            'Quản lý ứng lương nhân viên'),
    (46, 'SYSTEM_BRANCH_MANAGE',          'SYSTEM',        'Quản lý chi nhánh phòng khám'),
    (47, 'SYSTEM_ROOM_CHAIR_MANAGE',      'SYSTEM',        'Quản lý phòng khám và ghế máy'),
    (48, 'SYSTEM_SERVICE_CATALOG',        'SYSTEM',        'Quản lý bảng giá dịch vụ nha khoa'),
    (49, 'SYSTEM_ROLE_PERMISSION',        'SYSTEM',        'Quản lý vai trò và phân quyền RBAC'),
    (50, 'SYSTEM_AUDIT_LOG_VIEW',         'SYSTEM',        'Tra cứu nhật ký bảo mật (Audit Log)'),
    (51, 'PORTAL_BOOKING',                'PORTAL',        'Đặt lịch khám trực tuyến (Bệnh nhân)'),
    (52, 'PORTAL_MEDICAL_RECORD_VIEW',    'PORTAL',        'Xem bệnh án & kết quả khám cá nhân'),
    (53, 'PORTAL_INVOICE_VIEW',           'PORTAL',        'Xem hóa đơn & lịch sử thanh toán cá nhân'),
    (54, 'PORTAL_FAMILY_PROFILE_MANAGE',  'PORTAL',        'Quản lý hồ sơ người thân'),
    (55, 'PORTAL_SERVICE_REVIEW',         'PORTAL',        'Đánh giá chất lượng dịch vụ sau khám'),
    (56, 'REPORT_REVENUE_VIEW',           'REPORT',        'Xem báo cáo doanh thu & tài chính'),
    (57, 'REPORT_CLINICAL_VIEW',          'REPORT',        'Xem báo cáo thống kê ca khám & bác sĩ'),
    (58, 'CHAT_HANDOVER',                 'COMMUNICATION', 'Chuyển tiếp hội thoại tư vấn cho Bác sĩ'),
    (59, 'QUEUE_TV_DISPLAY',              'RECEPTION',     'Mở màn hình TV gọi số phòng chờ'),
    (60, 'LABO_ORDER_CREATE',             'LABO',          'Tạo phiếu đặt hàng răng giả Labo'),
    (61, 'LABO_STATUS_UPDATE',            'LABO',          'Cập nhật trạng thái nhận/lắp mẫu Labo')
ON DUPLICATE KEY UPDATE
    module      = VALUES(module),
    description = VALUES(description);

-- 3. GÁN QUYỀN MẶC ĐỊNH CHO TỪNG ROLE (role_permissions)
-- 3.1. ADMIN: Toàn bộ 61 quyền
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name = 'ADMIN';

-- 3.2. BAC_SI
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id FROM roles r JOIN permissions p ON p.permission_code IN (
    'DASHBOARD_VIEW', 'APPT_MANAGE', 'PATIENT_DIRECTORY', 'CLINICAL_EXAM',
    'CLINICAL_ODONTOGRAM_EDIT', 'CLINICAL_TREATMENT_PLAN', 'CLINICAL_SERVICE_ORDER',
    'PARACLINICAL_PROCESS', 'PARACLINICAL_XRAY_UPLOAD', 'WORKFLOW_VIEW',
    'PRESCRIPTION_CREATE', 'LABO_MANAGE', 'LABO_ORDER_CREATE', 'LABO_STATUS_UPDATE',
    'CSKH_FOLLOWUP', 'CSKH_DOCTOR_RESPOND', 'CHAT_SUPPORT', 'CHAT_HANDOVER',
    'SCHEDULE_MY', 'HR_MY_ATTENDANCE', 'REPORT_CLINICAL_VIEW'
) WHERE r.role_name = 'BAC_SI';

-- 3.3. LE_TAN
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id FROM roles r JOIN permissions p ON p.permission_code IN (
    'DASHBOARD_VIEW', 'APPT_MANAGE', 'VISIT_RECEPTION', 'QUEUE_OPERATE', 'QUEUE_TV_DISPLAY',
    'PATIENT_DIRECTORY', 'PRESCRIPTION_DISPENSE', 'CSKH_FOLLOWUP',
    'CHAT_SUPPORT', 'CHAT_HANDOVER', 'BILLING_INVOICE', 'SCHEDULE_MY', 'HR_MY_ATTENDANCE'
) WHERE r.role_name = 'LE_TAN';

-- 3.4. QUAN_LY_KHO
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id FROM roles r JOIN permissions p ON p.permission_code IN (
    'DASHBOARD_VIEW', 'INVENTORY_STOCK', 'INVENTORY_RECEIPT_CREATE', 'INVENTORY_ISSUE_CREATE',
    'SUPPLIER_MANAGE', 'MEDICATION_CATALOG_MANAGE', 'LABO_MANAGE', 'LABO_STATUS_UPDATE',
    'SCHEDULE_MY', 'HR_MY_ATTENDANCE'
) WHERE r.role_name = 'QUAN_LY_KHO';

-- 3.5. KE_TOAN
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id FROM roles r JOIN permissions p ON p.permission_code IN (
    'DASHBOARD_VIEW', 'PATIENT_DIRECTORY', 'PRESCRIPTION_DISPENSE',
    'BILLING_INVOICE', 'BILLING_REFUND', 'FINANCE_EXPENSE', 'FINANCE_CASHBOOK',
    'FINANCE_HANDOVER', 'FINANCE_TAX_CONFIG', 'SUPPLIER_MANAGE',
    'PAYROLL_MANAGE', 'HR_SALARY_ADVANCE', 'REPORT_ANALYTICS', 'REPORT_REVENUE_VIEW',
    'SCHEDULE_MY', 'HR_MY_ATTENDANCE'
) WHERE r.role_name = 'KE_TOAN';

-- 3.6. BENH_NHAN
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id FROM roles r JOIN permissions p ON p.permission_code IN (
    'PORTAL_ACCESS', 'PORTAL_BOOKING', 'PORTAL_MEDICAL_RECORD_VIEW',
    'PORTAL_INVOICE_VIEW', 'PORTAL_FAMILY_PROFILE_MANAGE', 'PORTAL_SERVICE_REVIEW'
) WHERE r.role_name = 'BENH_NHAN';
