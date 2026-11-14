-- ====================================================================================================
-- CV-DCMS: 08_finance_demo.sql (NHÓM TÀI CHÍNH DEMO: HÓA ĐƠN, THANH TOÁN, PHIẾU CHI, BẢNG LƯƠNG & TIN TỨC)
-- Đối chiếu 100% với Schema cv_dcms_db_backup.sql
-- Ràng buộc toán học:
--   Hóa đơn 501: Khám 100.000 + X-Quang 200.000 + Nhổ R48 2.000.000 = 2.300.000đ (Đã thanh toán 2.300.000đ, Nợ 0đ)
--   Hóa đơn 502: Điều trị tủy R36 1.500.000đ (Tạm ứng 500.000đ, Còn nợ 1.000.000đ - PARTIAL)
-- An toàn khi chạy nhiều lần (Idempotent - ON DUPLICATE KEY UPDATE)
-- ====================================================================================================

SET NAMES utf8mb4;

-- 1. HÓA ĐƠN VIỆN PHÍ (invoices & invoice_items)
INSERT INTO invoices (invoice_id, invoice_code, visit_id, profile_id, branch_id, created_by, total_service_amount, discount_amount, points_redeemed, points_discount_amount, tax_amount, final_amount, paid_amount, status, due_date, notes, created_at, updated_at) VALUES
    (501, 'HD-SEED-001', 501, 501, 1, 6, 2300000.00, 0.00, 0, 0.00, 0.00, 2300000.00, 2300000.00, 'PAID',    CURDATE(),                          'Đã thanh toán đủ viện phí ca tiểu phẫu nhổ răng khôn R48', DATE_SUB(NOW(), INTERVAL 70 MINUTE), NOW()),
    (502, 'HD-SEED-002', 502, 502, 1, 6, 1500000.00, 0.00, 0, 0.00, 0.00, 1500000.00,  500000.00, 'PARTIAL', DATE_ADD(CURDATE(), INTERVAL 5 DAY),'Thu tạm ứng đợt 1 điều trị tủy răng R36 (còn lại 1.000.000đ thanh toán ở lần hẹn hàn ống tủy)', DATE_SUB(NOW(), INTERVAL 15 MINUTE), NOW())
ON DUPLICATE KEY UPDATE
    total_service_amount = VALUES(total_service_amount),
    final_amount         = VALUES(final_amount),
    paid_amount          = VALUES(paid_amount),
    status               = VALUES(status),
    updated_at           = NOW();

INSERT INTO invoice_items (invoice_item_id, invoice_id, performed_id, item_name, quantity, unit_price, discount_amount, tax_rate, tax_amount, total_price) VALUES
    (5011, 501, 501, 'Khám tổng quát & Tư vấn phác đồ điều trị',        1, 100000.00,  0.00, 0.00, 0.00, 100000.00),
    (5012, 501, 502, 'Chụp X-Quang Toàn cảnh Kỹ thuật số (Panorama)',   1, 200000.00,  0.00, 0.00, 0.00, 200000.00),
    (5013, 501, 503, 'Tiểu phẫu nhổ răng khôn mọc lệch bằng Piezotome', 1, 2000000.00, 0.00, 0.00, 0.00, 2000000.00),
    (5021, 502, 504, 'Điều trị tủy răng hàm công nghệ máy X-Smart',     1, 1500000.00, 0.00, 0.00, 0.00, 1500000.00)
ON DUPLICATE KEY UPDATE
    unit_price  = VALUES(unit_price),
    total_price = VALUES(total_price);

-- 2. PHIẾU THU THANH TOÁN (payments)
INSERT INTO payments (payment_id, payment_code, invoice_id, profile_id, branch_id, cashier_id, payment_type, payment_method, amount, reference_number, bank_name, notes, paid_at) VALUES
    (501, 'PT-SEED-001', 501, 501, 1, 6, 'INVOICE_PAYMENT', 'BANK_TRANSFER', 2300000.00, 'FT26112008899', 'MBBank', 'Chuyển khoản VietQR thanh toán hóa đơn HD-SEED-001', DATE_SUB(NOW(), INTERVAL 65 MINUTE)),
    (502, 'PT-SEED-002', 502, 502, 1, 6, 'INVOICE_PAYMENT', 'CASH',           500000.00, NULL,            NULL,     'Thu tiền mặt tạm ứng đợt 1 hóa đơn HD-SEED-002',     DATE_SUB(NOW(), INTERVAL 10 MINUTE))
ON DUPLICATE KEY UPDATE
    amount         = VALUES(amount),
    payment_method = VALUES(payment_method);

-- 3. HOA HỒNG BÁC SĨ & PHIẾU CHI (service_commissions & expense_vouchers)
INSERT INTO service_commissions (commission_id, performed_id, staff_id, role_type, commission_amount, status, calculated_at) VALUES
    (501, 503, 2, 'MAIN_DOCTOR', 300000.00, 'APPROVED', DATE_SUB(NOW(), INTERVAL 60 MINUTE))
ON DUPLICATE KEY UPDATE
    commission_amount = VALUES(commission_amount),
    status            = VALUES(status);

INSERT INTO expense_vouchers (expense_id, voucher_code, branch_id, created_by, approved_by, expense_category, payment_method, amount, recipient_name, description, status, expense_date, created_at) VALUES
    (501, 'PC-SEED-001', 1, 6, 1, 'UTILITIES', 'BANK_TRANSFER', 4500000.00, 'Công ty Điện lực Cầu Giấy & Nước sạch Hà Nội', 'Thanh toán tiền điện, nước và xử lý chất thải y tế định kỳ Cơ sở 1', 'APPROVED', CURDATE(), NOW())
ON DUPLICATE KEY UPDATE
    amount = VALUES(amount),
    status = VALUES(status);

-- 4. LỊCH TRỰC, CHẤM CÔNG & BẢNG LƯƠNG THÁNG (staff_schedules, attendance_logs, payroll_periods, payroll_items)
INSERT INTO staff_schedules (schedule_id, staff_id, branch_id, shift_id, work_date, status, note, created_at) VALUES
    (501, 2, 1, 4, CURDATE(), 'SCHEDULED', 'Bác sĩ Lê Hoàng Nam trực khám & tiểu phẫu cả ngày tại CN01', NOW()),
    (502, 3, 1, 4, CURDATE(), 'SCHEDULED', 'Bác sĩ Trần Mai Hoa trực khám chỉnh nha & trẻ em tại CN01',  NOW()),
    (503, 4, 1, 4, CURDATE(), 'SCHEDULED', 'Lễ tân Hoàng Thu Trang trực quầy tiếp đón CN01',             NOW()),
    (504, 7, 2, 7, CURDATE(), 'SCHEDULED', 'Bác sĩ Nguyễn Quốc Tuấn trực chuyên môn tại CN02 Đống Đa',   NOW())
ON DUPLICATE KEY UPDATE
    status = VALUES(status),
    note   = VALUES(note);

INSERT INTO attendance_logs (attendance_id, staff_id, branch_id, shift_id, work_date, check_in_time, check_out_time, check_in_ip, status, late_minutes, early_leave_minutes, work_hours, is_manual_override, created_at) VALUES
    (501, 2, 1, 4, CURDATE(), CONCAT(CURDATE(), ' 07:55:00'), NULL, '127.0.0.1', 'PRESENT', 0, 0, 8.00, 0, NOW()),
    (502, 4, 1, 4, CURDATE(), CONCAT(CURDATE(), ' 07:50:00'), NULL, '127.0.0.1', 'PRESENT', 0, 0, 8.00, 0, NOW())
ON DUPLICATE KEY UPDATE
    status = VALUES(status);

INSERT INTO payroll_periods (period_id, period_code, branch_id, month_num, year_num, standard_work_days, status, approved_by, approved_at, created_by, created_at, updated_at) VALUES
    (501, 'BL-CN01-202610', 1, 10, 2026, 26, 'APPROVED', 1, NOW(), 6, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    status     = VALUES(status),
    updated_at = NOW();

INSERT INTO payroll_items (item_id, period_id, staff_id, actual_work_days, paid_leave_days, unpaid_leave_days, late_count, total_late_minutes, base_salary_snapshot, pro_rated_salary, allowance_amount, commission_amount, bonus_amount, gross_income, insurance_deduction, taxable_income, pit_tax_amount, penalty_deduction, salary_advance_deduction, other_deduction, net_salary, payment_status, paid_at) VALUES
    (501, 501, 2, 26.0, 0.0, 0.0, 0, 0, 28000000.00, 28000000.00, 4000000.00, 3500000.00, 1000000.00, 36500000.00, 2100000.00, 14600000.00, 1440000.00, 0.00, 0.00, 0.00, 32960000.00, 'PAID', NOW()),
    (502, 501, 4, 26.0, 0.0, 0.0, 0, 0, 11000000.00, 11000000.00, 1500000.00, 0.00,       500000.00,  13000000.00, 1050000.00,  950000.00,    47500.00, 0.00, 0.00, 0.00, 11902500.00, 'PAID', NOW())
ON DUPLICATE KEY UPDATE
    net_salary     = VALUES(net_salary),
    payment_status = VALUES(payment_status);

-- 5. TIN TỨC NHA KHOA & ĐÁNH GIÁ DỊCH VỤ (news_articles, comments, service_reviews, notifications)
INSERT INTO news_articles (article_id, title, slug, summary, content, thumbnail_url, category, status, is_featured, view_count, author_id, published_at, created_at, updated_at) VALUES
    (501, '5 Lưu ý Vàng Sau Khi Tiểu Phẫu Nhổ Răng Khôn Bằng Máy Piezotome', '5-luu-y-vang-sau-khi-nho-rang-khon-piezotome',
     'Hướng dẫn chi tiết cách cầm máu, chườm lạnh, chế độ ăn uống và vệ sinh răng miệng giúp vết thương nhổ răng số 8 lành nhanh, không sưng đau.',
     '<p>Nhổ răng khôn bằng công nghệ sóng siêu âm <strong>Piezotome</strong> giúp bảo tồn mô mềm tối đa. Để quá trình lành thương diễn ra thuận lợi nhất, Quý khách cần lưu ý:</p><ol><li>Cắn chặt gạc vô trùng trong 45-60 phút đầu sau tiểu phẫu.</li><li>Chườm lạnh ngoài má vùng góc hàm trong 24 giờ đầu.</li><li>Ăn thức ăn mềm, nguội như cháo, súp trong 2-3 ngày đầu.</li><li>Uống thuốc đúng đơn của Bác sĩ và tái khám cắt chỉ sau 7 ngày.</li></ol>',
     '/images/news/cham-soc-sau-nho-rang-khon.jpg', 'Kiến thức Nha khoa', 'PUBLISHED', 1, 128, 1, NOW(), NOW(), NOW()),
    (502, 'So Sánh Bọc Răng Sứ Zirconia Đức Và Dán Sứ Veneer Siêu Mỏng', 'so-sanh-boc-rang-su-zirconia-va-dan-su-veneer',
     'Khi nào nên chọn mão toàn sứ Zirconia CAD/CAM chịu lực cao và khi nào phù hợp với mặt dán sứ Veneer bảo tồn răng thật?',
     '<p>Phục hình răng sứ thẩm mỹ hiện đại mang lại nụ cười rạng rỡ tự nhiên. Bác sĩ Chuyên khoa tại Cường Việt Dental sẽ tư vấn giải pháp tối ưu dựa trên tình trạng men răng và khớp cắn của từng khách hàng.</p>',
     '/images/news/so-sanh-rang-su.jpg', 'Nha khoa Thẩm mỹ', 'PUBLISHED', 1, 95, 1, NOW(), NOW(), NOW())
ON DUPLICATE KEY UPDATE
    title      = VALUES(title),
    status     = VALUES(status),
    updated_at = NOW();

INSERT INTO comments (comment_id, article_id, user_id, parent_id, content, status, is_deleted, created_at, updated_at) VALUES
    (501, 501, 12, NULL, 'Bài viết rất hữu ích! Mình vừa nhổ răng khôn R48 bằng máy Piezotome của BS Nam sáng nay, trộm vía rất êm và hầu như không đau.', 'VISIBLE', 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    content    = VALUES(content),
    updated_at = NOW();

INSERT INTO service_reviews (review_id, visit_id, profile_id, branch_id, rating, tags, comment, clinic_response, created_at) VALUES
    (501, 501, 501, 1, 5, 'Bác sĩ mát tay,Phòng khám sạch đẹp,Nhổ răng không đau',
     'BS Lê Hoàng Nam nhổ răng khôn R48 bằng máy siêu âm rất nhẹ nhàng, bạn lễ tân Thu Trang dặn dò chu đáo.',
     'Nha khoa Quốc tế Cường Việt chân thành cảm ơn anh Phạm Quốc Bảo đã tin tưởng dịch vụ. Chúc anh mau lành thương!', NOW())
ON DUPLICATE KEY UPDATE
    rating  = VALUES(rating),
    comment = VALUES(comment);

INSERT INTO notifications (notification_id, user_id, title, message, notification_type, reference_url, is_read, created_at) VALUES
    (501, 2,  'Phản hồi CSKH ca khám VIS-SEED-002', 'Bệnh nhân Lê Hoàng Phương Thảo cần bác sĩ tư vấn thêm về đơn thuốc nội nha R36.', 'CSKH_ESCALATION', '/customer-care', 0, NOW()),
    (502, 12, 'Hoàn tất ca khám VIS-SEED-001',      'Đơn thuốc và hướng dẫn chăm sóc sau nhổ răng khôn R48 của bạn đã sẵn sàng trên Cổng thông tin bệnh nhân.', 'VISIT_COMPLETED', '/portal/medical-records', 0, NOW())
ON DUPLICATE KEY UPDATE
    title   = VALUES(title),
    message = VALUES(message);
