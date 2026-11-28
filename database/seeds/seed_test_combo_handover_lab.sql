-- =============================================================================
-- SEED TEST: Phân quyền Combo dịch vụ + Chuyển Bác sĩ + Xét nghiệm máu ngoài viện
-- Mật khẩu chung của 2 tài khoản: Hieu1811
-- Tài khoản 1 (BS Tổng quát - thiếu quyền Nhổ răng khôn): bs_tongquat / Hieu1811
-- Tài khoản 2 (BS Chuyên khoa - đủ quyền & đang rảnh):    bs_chuyenkhoa / Hieu1811
-- Chạy lại file này bất cứ lúc nào để RESET sạch kịch bản test cho ngày hôm nay.
-- =============================================================================

SET NAMES utf8mb4;

-- 1. Tạo hoặc cập nhật 2 tài khoản bác sĩ (chỉ gán role BAC_SI, không gán ADMIN)
INSERT INTO users (
  username, password, email, phone_number, user_type,
  is_active, status, branch_id, custom_permissions_enabled, created_date, updated_at
) VALUES
  ('bs_tongquat',   '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bs.tongquat@dental.vn',   '0909100001', 'STAFF', b'1', 'ACTIVE', 1, b'0', NOW(), NOW()),
  ('bs_chuyenkhoa', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bs.chuyenkhoa@dental.vn', '0909100002', 'STAFF', b'1', 'ACTIVE', 1, b'0', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  password = VALUES(password),
  is_active = b'1',
  status = 'ACTIVE',
  branch_id = 1,
  updated_at = NOW();

SET @docA_user_id = (SELECT user_id FROM users WHERE username = 'bs_tongquat' LIMIT 1);
SET @docB_user_id = (SELECT user_id FROM users WHERE username = 'bs_chuyenkhoa' LIMIT 1);

DELETE FROM user_roles WHERE user_id IN (@docA_user_id, @docB_user_id);
INSERT INTO user_roles (user_id, role_id) VALUES (@docA_user_id, 2), (@docB_user_id, 2);

-- 2. Hồ sơ nhân sự bác sĩ
INSERT INTO staff_profiles (
  user_id, staff_code, full_name, gender, phone_number, email, department, position_title, specialty,
  status, base_salary, allowance_amount, insurance_salary, contract_type, dependent_count,
  has_personal_deduction, has_tax_commitment, is_tax_resident, insurance_participation_status, created_at, updated_at
) VALUES
  (@docA_user_id, 'BS-TQ-01', 'BS. Nguyễn Minh Tâm (Tổng quát)', 'MALE', '0909100001', 'bs.tongquat@dental.vn',
   'Khoa Khám Tổng quát', 'Bác sĩ điều trị', 'Nha khoa tổng quát', 'ACTIVE',
   18000000.00, 2000000.00, 18000000.00, 'FULL_TIME', 0, b'1', b'0', b'1', 'PARTICIPATING', NOW(), NOW()),
  (@docB_user_id, 'BS-CK-02', 'BS.CK1 Trần Quốc Bảo (Tiểu phẫu)', 'MALE', '0909100002', 'bs.chuyenkhoa@dental.vn',
   'Khoa Tiểu phẫu & Implant', 'Bác sĩ chuyên khoa', 'Phẫu thuật hàm mặt & Nhổ răng khôn', 'ACTIVE',
   25000000.00, 3000000.00, 25000000.00, 'FULL_TIME', 0, b'1', b'0', b'1', 'PARTICIPATING', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  user_id = VALUES(user_id),
  full_name = VALUES(full_name),
  specialty = VALUES(specialty),
  status = 'ACTIVE',
  updated_at = NOW();

SET @docA_staff_id = (SELECT staff_id FROM staff_profiles WHERE user_id = @docA_user_id LIMIT 1);
SET @docB_staff_id = (SELECT staff_id FROM staff_profiles WHERE user_id = @docB_user_id LIMIT 1);

-- 3. Gán chi nhánh (Branch 1)
DELETE FROM staff_branch_assignments WHERE staff_id IN (@docA_staff_id, @docB_staff_id);
INSERT INTO staff_branch_assignments (staff_id, branch_id, role_id, is_primary, start_date, status, created_at)
VALUES
  (@docA_staff_id, 1, 2, 1, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 'ACTIVE', NOW()),
  (@docB_staff_id, 1, 2, 1, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 'ACTIVE', NOW());

-- 4. Gán phòng mặc định: BS A -> Phòng 1 (Khám Tổng quát 1), BS B -> Phòng 2 (Tiểu phẫu & Cấy ghép)
DELETE FROM doctor_room_assignments WHERE staff_id IN (@docA_staff_id, @docB_staff_id);
INSERT INTO doctor_room_assignments (effective_from, effective_to, is_primary, status, branch_id, room_id, staff_id)
VALUES
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), b'1', 'ACTIVE', 1, 1, @docA_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), b'1', 'ACTIVE', 1, 2, @docB_staff_id);

-- 5. Lịch trực hôm nay & ngày mai (đủ 3 ca: Sáng 7, Chiều 9, Tối 11)
DELETE FROM doctor_shift_assignments WHERE staff_id IN (@docA_staff_id, @docB_staff_id);
DELETE FROM staff_schedules WHERE staff_id IN (@docA_staff_id, @docB_staff_id);

INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, note, created_at)
VALUES
  (@docA_staff_id, 1, 7,  CURDATE(), 'SCHEDULED', 'Ca sáng BS Tổng quát', NOW()),
  (@docA_staff_id, 1, 9,  CURDATE(), 'SCHEDULED', 'Ca chiều BS Tổng quát', NOW()),
  (@docA_staff_id, 1, 11, CURDATE(), 'SCHEDULED', 'Ca tối BS Tổng quát', NOW()),
  (@docA_staff_id, 1, 7,  DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'SCHEDULED', 'Ca sáng BS Tổng quát', NOW()),
  (@docA_staff_id, 1, 9,  DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'SCHEDULED', 'Ca chiều BS Tổng quát', NOW()),
  (@docB_staff_id, 1, 7,  CURDATE(), 'SCHEDULED', 'Ca sáng BS Chuyên khoa', NOW()),
  (@docB_staff_id, 1, 9,  CURDATE(), 'SCHEDULED', 'Ca chiều BS Chuyên khoa', NOW()),
  (@docB_staff_id, 1, 11, CURDATE(), 'SCHEDULED', 'Ca tối BS Chuyên khoa', NOW()),
  (@docB_staff_id, 1, 7,  DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'SCHEDULED', 'Ca sáng BS Chuyên khoa', NOW()),
  (@docB_staff_id, 1, 9,  DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'SCHEDULED', 'Ca chiều BS Chuyên khoa', NOW());

INSERT INTO doctor_shift_assignments (
  active_slot_key, assignment_role, created_at, room_permission_overridden, status,
  updated_at, work_date, branch_id, chair_id, room_id, shift_id, staff_id, staff_schedule_id
)
SELECT
  CONCAT(ss.work_date, '|', ss.shift_id, '|', ss.staff_id),
  'PRIMARY', NOW(6), b'0', 'ACTIVE', NOW(6), ss.work_date, 1,
  CASE WHEN ss.staff_id = @docA_staff_id THEN 1 ELSE 3 END,
  CASE WHEN ss.staff_id = @docA_staff_id THEN 1 ELSE 2 END,
  ss.shift_id, ss.staff_id, ss.schedule_id
FROM staff_schedules ss
WHERE ss.staff_id IN (@docA_staff_id, @docB_staff_id);

-- 6. Phân quyền dịch vụ:
-- BS A (bs_tongquat): chỉ làm được 1, 4, 5, 15 (KHÔNG có 7: Nhổ răng khôn)
-- BS B (bs_chuyenkhoa): làm được 1, 4, 5, 6, 7, 8, 15 (CÓ 7: Nhổ răng khôn)
DELETE FROM doctor_service_permissions WHERE staff_id IN (@docA_staff_id, @docB_staff_id);
INSERT INTO doctor_service_permissions (effective_from, effective_to, note, status, branch_id, service_id, staff_id)
VALUES
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Tổng quát', 'ACTIVE', 1, 1,  @docA_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Tổng quát', 'ACTIVE', 1, 4,  @docA_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Tổng quát', 'ACTIVE', 1, 5,  @docA_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Tổng quát', 'ACTIVE', 1, 15, @docA_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Chuyên khoa',           'ACTIVE', 1, 1,  @docB_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Chuyên khoa',           'ACTIVE', 1, 4,  @docB_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Chuyên khoa',           'ACTIVE', 1, 5,  @docB_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Chuyên khoa',           'ACTIVE', 1, 6,  @docB_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Chuyên khoa tiểu phẫu', 'ACTIVE', 1, 7,  @docB_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Chuyên khoa Implant',   'ACTIVE', 1, 8,  @docB_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Chuyên khoa',           'ACTIVE', 1, 15, @docB_staff_id);

-- 7. Reset ca khám Nguyễn Văn An (visit 627) cho BS A và hàng đợi chờ (628, 629)
UPDATE visits
   SET primary_doctor_id = @docA_staff_id, room_id = 1, chair_id = 1, status = 'IN_EXAM'
 WHERE visit_id = 627;

UPDATE clinical_examinations SET doctor_id = @docA_staff_id WHERE visit_id = 627;
UPDATE appointments SET doctor_id = @docA_staff_id WHERE appointment_id = 740;

UPDATE visits
   SET primary_doctor_id = NULL, room_id = NULL, chair_id = NULL, status = 'WAITING'
 WHERE visit_id IN (628, 629);
UPDATE appointments SET doctor_id = NULL, is_doctor_requested = b'0' WHERE appointment_id IN (741, 742);

UPDATE queue_tickets
   SET queue_date = CURDATE(), status = 'IN_SERVICE', called_at = NOW(), completed_at = NULL
 WHERE visit_id = 627;
UPDATE queue_tickets
   SET queue_date = CURDATE(), status = 'WAITING', called_at = NULL, completed_at = NULL
 WHERE visit_id IN (628, 629);

UPDATE dental_chairs SET status = 'IN_USE' WHERE chair_id = 1;
UPDATE dental_chairs SET status = 'AVAILABLE' WHERE chair_id = 3;

-- Đảm bảo BS B đang rảnh hoàn toàn (0 ca đang khám / chờ)
UPDATE visits
   SET status = 'COMPLETED'
 WHERE primary_doctor_id = @docB_staff_id
   AND status IN ('IN_EXAM', 'WAITING', 'CALLED', 'PAUSED');

-- 8. Tạo lại Combo KHDT-COMBO-TEST cho Nguyễn Văn An (profile_id = 1, visit_id = 627)
SET @old_plan_id = (SELECT plan_id FROM treatment_plans WHERE plan_code = 'KHDT-COMBO-TEST' LIMIT 1);
DELETE FROM treatment_plan_items WHERE plan_id = @old_plan_id;
DELETE FROM treatment_plan_stages WHERE plan_id = @old_plan_id;
DELETE FROM treatment_plans WHERE plan_id = @old_plan_id;

INSERT INTO treatment_plans (
  plan_code, profile_id, branch_id, doctor_id, plan_name,
  total_estimated_cost, final_estimated_cost, status,
  start_date, expected_end_date, notes, created_at,
  sent_to_accounting, sent_to_accounting_at, is_installment, total_paid_amount, visit_id
) VALUES (
  'KHDT-COMBO-TEST', 1, 1, @docA_staff_id,
  'Combo Điều trị Tổng hợp: Cạo vôi + Nhổ R48 + Trám R16',
  2750000.00, 2750000.00, 'ACCEPTED',
  CURDATE(), DATE_ADD(CURDATE(), INTERVAL 14 DAY),
  'Combo test phân quyền: BS Tổng quát làm Cạo vôi & Trám, BS Chuyên khoa làm Nhổ răng khôn R48.',
  NOW(), b'1', NOW(6), b'0', 0.00, 627
);
SET @plan_id = LAST_INSERT_ID();

INSERT INTO treatment_plan_stages (plan_id, stage_order, stage_name, status, description, expected_end_date)
VALUES
  (@plan_id, 1, '[SV #1] Cạo vôi răng & Đánh bóng',       'PENDING', 'Bước 1 — BS Tổng quát thực hiện được', CURDATE()),
  (@plan_id, 2, '[SV #2] Nhổ răng khôn mọc lệch R48',       'PENDING', 'Bước 2 — Yêu cầu BS Chuyên khoa (BS Tổng quát không đủ quyền)', CURDATE()),
  (@plan_id, 3, '[SV #3] Trám răng thẩm mỹ Composite R16', 'PENDING', 'Bước 3 — BS Tổng quát hoàn thiện sau khi chuyển lại', DATE_ADD(CURDATE(), INTERVAL 7 DAY));

SET @st1 = (SELECT stage_id FROM treatment_plan_stages WHERE plan_id = @plan_id AND stage_order = 1 LIMIT 1);
SET @st2 = (SELECT stage_id FROM treatment_plan_stages WHERE plan_id = @plan_id AND stage_order = 2 LIMIT 1);
SET @st3 = (SELECT stage_id FROM treatment_plan_stages WHERE plan_id = @plan_id AND stage_order = 3 LIMIT 1);

INSERT INTO treatment_plan_items (stage_id, plan_id, service_id, tooth_numbers, quantity, estimated_price, status, step_name)
VALUES
  (@st1, @plan_id, 4, NULL, 1.00,  300000.00, 'PLANNED', 'Cạo vôi siêu âm & đánh bóng 2 hàm'),
  (@st2, @plan_id, 7, '48', 1.00, 2000000.00, 'PLANNED', 'Phẫu thuật nhổ răng khôn mọc lệch hàm dưới R48'),
  (@st3, @plan_id, 5, '16', 1.00,  450000.00, 'PLANNED', 'Trám thẩm mỹ Composite R16');

-- 9. Tạo lại phiếu Xét nghiệm máu ngoài viện CD-LAB-TEST-01 trên visit 627
SET @old_order_id = (SELECT order_id FROM paraclinical_orders WHERE order_code = 'CD-LAB-TEST-01' LIMIT 1);
DELETE leo FROM lab_external_orders leo
  JOIN paraclinical_order_items poi ON poi.order_item_id = leo.order_item_id
 WHERE poi.order_id = @old_order_id;
DELETE FROM paraclinical_order_items WHERE order_id = @old_order_id;
DELETE FROM paraclinical_orders WHERE order_id = @old_order_id;

INSERT INTO paraclinical_orders (order_code, visit_id, profile_id, doctor_id, status, order_time)
VALUES ('CD-LAB-TEST-01', 627, 1, @docA_staff_id, 'ORDERED', NOW());
SET @order_id = LAST_INSERT_ID();

INSERT INTO paraclinical_order_items (order_id, service_id, tooth_numbers, status, external_ref_id)
VALUES (@order_id, 13, '48', 'WAITING', 'EXT-MED-1127');
SET @order_item_id = LAST_INSERT_ID();

INSERT INTO lab_external_orders (
  order_item_id, lab_partner_name, sample_code, sample_type, sent_at, expected_result_date, lab_status, notes
) VALUES (
  @order_item_id, 'BV Đa khoa Medlatec', 'LAB-MAU-1127',
  'Máu toàn phần (PT/APTT/INR trước tiểu phẫu R48)',
  NOW(), DATE_ADD(NOW(), INTERVAL 2 HOUR), 'SENT',
  'Chờ upload file kết quả xét nghiệm máu PDF/ảnh'
);
