-- =============================================================================
-- SEED TEST: Phân quyền Combo dịch vụ + Chuyển Bác sĩ + Xét nghiệm máu ngoài viện
-- Mật khẩu chung của 2 tài khoản: Hieu1811
-- Tài khoản 1 (BS Tổng quát - thiếu quyền Nhổ răng khôn): bs_tongquat / Hieu1811
-- Tài khoản 2 (BS Chuyên khoa - đủ quyền & đang rảnh):    bs_chuyenkhoa / Hieu1811
-- Chạy lại file này bất cứ lúc nào để RESET sạch kịch bản test cho ngày hôm nay.
-- =============================================================================

SET NAMES utf8mb4;

-- 0. Tự động lấy ID thực tế trong DB (tránh lỗi 1452 khi ID master data khác nhau)
SET @branch_id      = COALESCE((SELECT branch_id FROM branches WHERE is_active = b'1' ORDER BY branch_id LIMIT 1), 1);
SET @doctor_role_id = COALESCE((SELECT role_id FROM roles WHERE role_code IN ('BAC_SI', 'DOCTOR', 'ROLE_DOCTOR') LIMIT 1), 2);

SET @roomA_id = COALESCE(
  (SELECT room_id FROM rooms WHERE branch_id = @branch_id AND room_type = 'EXAM' ORDER BY room_id LIMIT 1),
  (SELECT room_id FROM rooms WHERE branch_id = @branch_id ORDER BY room_id LIMIT 1),
  1
);
SET @roomB_id = COALESCE(
  (SELECT room_id FROM rooms WHERE branch_id = @branch_id AND room_type = 'TREATMENT' AND room_id <> @roomA_id ORDER BY room_id LIMIT 1),
  (SELECT room_id FROM rooms WHERE branch_id = @branch_id AND room_id <> @roomA_id ORDER BY room_id LIMIT 1),
  @roomA_id
);

SET @chairA_id = COALESCE(
  (SELECT chair_id FROM dental_chairs WHERE branch_id = @branch_id AND room_id = @roomA_id ORDER BY chair_id LIMIT 1),
  (SELECT chair_id FROM dental_chairs WHERE branch_id = @branch_id ORDER BY chair_id LIMIT 1),
  1
);
SET @chairB_id = COALESCE(
  (SELECT chair_id FROM dental_chairs WHERE branch_id = @branch_id AND room_id = @roomB_id AND chair_id <> @chairA_id ORDER BY chair_id LIMIT 1),
  (SELECT chair_id FROM dental_chairs WHERE branch_id = @branch_id AND chair_id <> @chairA_id ORDER BY chair_id LIMIT 1),
  @chairA_id
);

-- Tự động tra service_id thực tế trong bảng services
SET @svc_kham = COALESCE(
  (SELECT service_id FROM services WHERE service_code LIKE '%KHAM%' OR service_name LIKE '%Khám tổng quát%' ORDER BY service_id LIMIT 1),
  (SELECT service_id FROM services ORDER BY service_id LIMIT 1)
);
SET @svc_caovoi = COALESCE(
  (SELECT service_id FROM services WHERE service_code LIKE '%CAO%' OR service_name LIKE '%cao răng%' OR service_name LIKE '%cạo vôi%' ORDER BY service_id LIMIT 1),
  @svc_kham
);
SET @svc_tram = COALESCE(
  (SELECT service_id FROM services WHERE service_code LIKE '%TRAM%' OR service_name LIKE '%Trám%' ORDER BY service_id LIMIT 1),
  @svc_caovoi
);
SET @svc_nho_rk = COALESCE(
  (SELECT service_id FROM services WHERE service_code LIKE '%RK8%' OR service_name LIKE '%răng khôn%' OR service_name LIKE '%Nhổ răng%' ORDER BY service_id LIMIT 1),
  (SELECT service_id FROM services WHERE service_id NOT IN (@svc_kham, @svc_caovoi, @svc_tram) ORDER BY service_id LIMIT 1)
);
SET @svc_tuy = (
  SELECT service_id FROM services WHERE service_code LIKE '%TUY%' OR service_name LIKE '%tủy%' ORDER BY service_id LIMIT 1
);
SET @svc_implant = (
  SELECT service_id FROM services WHERE service_code LIKE '%IMPLANT%' OR service_name LIKE '%Implant%' ORDER BY service_id LIMIT 1
);
SET @svc_lab = COALESCE(
  (SELECT service_id FROM services WHERE service_name LIKE '%xét nghiệm%' OR service_name LIKE '%máu%' OR service_code LIKE '%XN%' OR service_code LIKE '%LAB%' ORDER BY service_id LIMIT 1),
  (SELECT service_id FROM services WHERE is_paraclinical = b'1' ORDER BY service_id LIMIT 1),
  @svc_kham
);

-- 1. Tạo hoặc cập nhật 2 tài khoản bác sĩ (chỉ gán role BAC_SI, không gán ADMIN)
INSERT INTO users (
  username, password, email, phone_number, user_type,
  is_active, status, branch_id, custom_permissions_enabled, created_date, updated_at
) VALUES
  ('bs_tongquat',   '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bs.tongquat@dental.vn',   '0909100001', 'STAFF', b'1', 'ACTIVE', @branch_id, b'0', NOW(), NOW()),
  ('bs_chuyenkhoa', '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe', 'bs.chuyenkhoa@dental.vn', '0909100002', 'STAFF', b'1', 'ACTIVE', @branch_id, b'0', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  password = VALUES(password),
  is_active = b'1',
  status = 'ACTIVE',
  branch_id = @branch_id,
  updated_at = NOW();

SET @docA_user_id = (SELECT user_id FROM users WHERE username = 'bs_tongquat' LIMIT 1);
SET @docB_user_id = (SELECT user_id FROM users WHERE username = 'bs_chuyenkhoa' LIMIT 1);

DELETE FROM user_roles WHERE user_id IN (@docA_user_id, @docB_user_id);
INSERT INTO user_roles (user_id, role_id) VALUES (@docA_user_id, @doctor_role_id), (@docB_user_id, @doctor_role_id);

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

-- 3. Gán chi nhánh
DELETE FROM staff_branch_assignments WHERE staff_id IN (@docA_staff_id, @docB_staff_id);
INSERT INTO staff_branch_assignments (staff_id, branch_id, role_id, is_primary, start_date, status, created_at)
VALUES
  (@docA_staff_id, @branch_id, @doctor_role_id, 1, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 'ACTIVE', NOW()),
  (@docB_staff_id, @branch_id, @doctor_role_id, 1, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 'ACTIVE', NOW());

-- 4. Gán phòng mặc định: BS A -> Phòng Khám Tổng quát, BS B -> Phòng Tiểu phẫu
DELETE FROM doctor_room_assignments WHERE staff_id IN (@docA_staff_id, @docB_staff_id);
INSERT INTO doctor_room_assignments (effective_from, effective_to, is_primary, status, branch_id, room_id, staff_id)
VALUES
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), b'1', 'ACTIVE', @branch_id, @roomA_id, @docA_staff_id),
  (DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), b'1', 'ACTIVE', @branch_id, @roomB_id, @docB_staff_id);

-- 5. Lịch trực hôm nay & ngày mai (tự động lấy toàn bộ ca đang hoạt động trong work_shifts)
DELETE FROM doctor_shift_assignments WHERE staff_id IN (@docA_staff_id, @docB_staff_id);
DELETE FROM staff_schedules WHERE staff_id IN (@docA_staff_id, @docB_staff_id);

INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, note, created_at)
SELECT docs.staff_id, @branch_id, ws.shift_id, days.work_date, 'SCHEDULED',
       CONCAT(ws.shift_name, ' - ', docs.label), NOW()
FROM (
  SELECT @docA_staff_id AS staff_id, 'BS Tổng quát' AS label
  UNION ALL
  SELECT @docB_staff_id AS staff_id, 'BS Chuyên khoa' AS label
) docs
CROSS JOIN (
  SELECT CURDATE() AS work_date
  UNION ALL
  SELECT DATE_ADD(CURDATE(), INTERVAL 1 DAY) AS work_date
) days
CROSS JOIN work_shifts ws
WHERE ws.is_active = b'1'
  AND (
    ws.branch_id = @branch_id
    OR (ws.branch_id IS NULL AND NOT EXISTS (SELECT 1 FROM work_shifts w2 WHERE w2.branch_id = @branch_id AND w2.is_active = b'1'))
  );

INSERT INTO doctor_shift_assignments (
  active_slot_key, assignment_role, created_at, room_permission_overridden, status,
  updated_at, work_date, branch_id, chair_id, room_id, shift_id, staff_id, staff_schedule_id
)
SELECT
  CONCAT(ss.work_date, '|', ss.shift_id, '|', ss.staff_id),
  'PRIMARY', NOW(6), b'0', 'ACTIVE', NOW(6), ss.work_date, @branch_id,
  CASE WHEN ss.staff_id = @docA_staff_id THEN @chairA_id ELSE @chairB_id END,
  CASE WHEN ss.staff_id = @docA_staff_id THEN @roomA_id  ELSE @roomB_id  END,
  ss.shift_id, ss.staff_id, ss.schedule_id
FROM staff_schedules ss
WHERE ss.staff_id IN (@docA_staff_id, @docB_staff_id);

-- 6. Phân quyền dịch vụ (lấy động từ bảng services nên không bao giờ lỗi 1452):
-- BS A (bs_tongquat): Khám, Cạo vôi, Trám (KHÔNG có Nhổ răng khôn)
-- BS B (bs_chuyenkhoa): Đủ quyền gồm cả Nhổ răng khôn, Tủy, Implant
DELETE FROM doctor_service_permissions WHERE staff_id IN (@docA_staff_id, @docB_staff_id);

INSERT INTO doctor_service_permissions (effective_from, effective_to, note, status, branch_id, service_id, staff_id)
SELECT DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY), 'Tổng quát', 'ACTIVE', @branch_id, s.service_id, @docA_staff_id
FROM services s
WHERE s.service_id IN (@svc_kham, @svc_caovoi, @svc_tram)
  AND s.service_id <> COALESCE(@svc_nho_rk, -1);

INSERT INTO doctor_service_permissions (effective_from, effective_to, note, status, branch_id, service_id, staff_id)
SELECT DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 365 DAY),
       CASE
         WHEN s.service_id = @svc_nho_rk  THEN 'Chuyên khoa tiểu phẫu'
         WHEN s.service_id = @svc_implant THEN 'Chuyên khoa Implant'
         ELSE 'Chuyên khoa'
       END,
       'ACTIVE', @branch_id, s.service_id, @docB_staff_id
FROM services s
WHERE s.service_id IN (@svc_kham, @svc_caovoi, @svc_tram, @svc_nho_rk, @svc_tuy, @svc_implant);

-- 7. Đổi tên bệnh nhân thành Phạm Hoàng Long & reset ca khám cho BS A + hàng đợi chờ
SET @patient_name = 'Phạm Hoàng Long';

SET @target_visit_id = COALESCE(
  (SELECT visit_id FROM visits WHERE visit_id = 627 LIMIT 1),
  (SELECT visit_id FROM visits WHERE visit_code = 'VIS-COMBO-TEST' LIMIT 1),
  (SELECT visit_id FROM visits WHERE branch_id = @branch_id ORDER BY visit_id DESC LIMIT 1)
);

SET @target_profile_id = COALESCE(
  (SELECT profile_id FROM visits WHERE visit_id = @target_visit_id LIMIT 1),
  (SELECT profile_id FROM patient_profiles ORDER BY profile_id LIMIT 1)
);

-- Nếu DB chưa có lượt khám nào, tạo mới lượt khám VIS-COMBO-TEST
INSERT INTO visits (
  visit_code, profile_id, branch_id, visit_type, is_emergency, is_booked_and_on_time,
  check_in_at, initial_symptoms, primary_doctor_id, room_id, chair_id, status, created_at
)
SELECT
  'VIS-COMBO-TEST', @target_profile_id, @branch_id, 'WALK_IN', b'0', b'0',
  NOW(), 'Đau răng khôn R48, sâu R16, cần cạo vôi răng', @docA_staff_id, @roomA_id, @chairA_id, 'IN_EXAM', NOW()
WHERE @target_visit_id IS NULL AND @target_profile_id IS NOT NULL;

SET @target_visit_id = COALESCE(
  @target_visit_id,
  (SELECT visit_id FROM visits WHERE visit_code = 'VIS-COMBO-TEST' LIMIT 1)
);

UPDATE patient_profiles
   SET full_name = @patient_name
 WHERE profile_id = @target_profile_id;

UPDATE visits
   SET primary_doctor_id = @docA_staff_id, room_id = @roomA_id, chair_id = @chairA_id,
       status = 'IN_EXAM', check_in_at = NOW(), completed_at = NULL
 WHERE visit_id = @target_visit_id;

UPDATE clinical_examinations SET doctor_id = @docA_staff_id WHERE visit_id = @target_visit_id;
UPDATE appointments
   SET doctor_id = @docA_staff_id, appointment_date = CURDATE()
 WHERE appointment_id = (SELECT appointment_id FROM visits WHERE visit_id = @target_visit_id LIMIT 1);

-- Nếu có các ca hàng đợi mẫu (628, 629) thì reset về WAITING
UPDATE visits
   SET primary_doctor_id = NULL, room_id = NULL, chair_id = NULL, status = 'WAITING', check_in_at = NOW()
 WHERE visit_id IN (628, 629) AND visit_id <> @target_visit_id;
UPDATE appointments SET doctor_id = NULL, is_doctor_requested = b'0', appointment_date = CURDATE() WHERE appointment_id IN (741, 742);

UPDATE queue_tickets
   SET queue_date = CURDATE(), status = 'IN_SERVICE', called_at = NOW(), completed_at = NULL
 WHERE visit_id = @target_visit_id;
UPDATE queue_tickets
   SET queue_date = CURDATE(), status = 'WAITING', called_at = NULL, completed_at = NULL
 WHERE visit_id IN (628, 629) AND visit_id <> @target_visit_id;

UPDATE dental_chairs SET status = 'IN_USE'    WHERE chair_id = @chairA_id;
UPDATE dental_chairs SET status = 'AVAILABLE' WHERE chair_id = @chairB_id AND @chairB_id <> @chairA_id;

-- Đảm bảo BS B đang rảnh hoàn toàn (0 ca đang khám / chờ)
UPDATE visits
   SET status = 'COMPLETED'
 WHERE primary_doctor_id = @docB_staff_id
   AND status IN ('IN_EXAM', 'WAITING', 'CALLED', 'PAUSED');

-- 8. Tạo lại Combo KHDT-COMBO-TEST cho bệnh nhân (Phạm Hoàng Long)
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
  'KHDT-COMBO-TEST', @target_profile_id, @branch_id, @docA_staff_id,
  'Combo Điều trị Tổng hợp: Cạo vôi + Nhổ R48 + Trám R16',
  2750000.00, 2750000.00, 'ACCEPTED',
  CURDATE(), DATE_ADD(CURDATE(), INTERVAL 14 DAY),
  'Combo test phân quyền: BS Tổng quát làm Cạo vôi & Trám, BS Chuyên khoa làm Nhổ răng khôn R48.',
  NOW(), b'1', NOW(6), b'0', 0.00, @target_visit_id
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
  (@st1, @plan_id, @svc_caovoi, NULL, 1.00,  300000.00, 'PLANNED', 'Cạo vôi siêu âm & đánh bóng 2 hàm'),
  (@st2, @plan_id, @svc_nho_rk, '48', 1.00, 2000000.00, 'PLANNED', 'Phẫu thuật nhổ răng khôn mọc lệch hàm dưới R48'),
  (@st3, @plan_id, @svc_tram,   '16', 1.00,  450000.00, 'PLANNED', 'Trám thẩm mỹ Composite R16');

-- 9. Tạo lại phiếu Xét nghiệm máu ngoài viện CD-LAB-TEST-01
SET @old_order_id = (SELECT order_id FROM paraclinical_orders WHERE order_code = 'CD-LAB-TEST-01' LIMIT 1);
DELETE leo FROM lab_external_orders leo
  JOIN paraclinical_order_items poi ON poi.order_item_id = leo.order_item_id
 WHERE poi.order_id = @old_order_id;
DELETE FROM paraclinical_order_items WHERE order_id = @old_order_id;
DELETE FROM paraclinical_orders WHERE order_id = @old_order_id;

INSERT INTO paraclinical_orders (order_code, visit_id, profile_id, doctor_id, status, order_time)
VALUES ('CD-LAB-TEST-01', @target_visit_id, @target_profile_id, @docA_staff_id, 'ORDERED', NOW());
SET @order_id = LAST_INSERT_ID();

INSERT INTO paraclinical_order_items (order_id, service_id, tooth_numbers, status, external_ref_id)
VALUES (@order_id, @svc_lab, '48', 'WAITING', 'EXT-MED-1127');
SET @order_item_id = LAST_INSERT_ID();

INSERT INTO lab_external_orders (
  order_item_id, lab_partner_name, sample_code, sample_type, sent_at, expected_result_date, lab_status, notes
) VALUES (
  @order_item_id, 'BV Đa khoa Medlatec', 'LAB-MAU-1127',
  'Máu toàn phần (PT/APTT/INR trước tiểu phẫu R48)',
  NOW(), DATE_ADD(NOW(), INTERVAL 2 HOUR), 'SENT',
  'Chờ upload file kết quả xét nghiệm máu PDF/ảnh'
);
