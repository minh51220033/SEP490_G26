-- Optional, repeatable accounts for the Phase 0-3 role checks.
-- Password for the new receptionist account: Hieu1811 (same BCrypt hash as test_data.sql).
INSERT INTO users (username, password, email, phone_number, branch_id,
                   user_type, status, is_active, created_date)
SELECT 'reception_phase03',
       '$2a$10$5s12DPJUuk1DmfTPTZeAUOogKkXPlkSgVQ1TvTEI2NtnDOAlL4MBe',
       'reception.phase03@dentalclinic.com', '0903300033', b.branch_id,
       'STAFF', 'ACTIVE', 1, NOW()
FROM branches b
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM users u WHERE u.username = 'reception_phase03');

INSERT INTO user_roles (user_id, role_id)
SELECT u.user_id, r.role_id FROM users u JOIN roles r ON r.role_name = 'LE_TAN'
WHERE u.username = 'reception_phase03'
  AND NOT EXISTS (SELECT 1 FROM user_roles ur
                  WHERE ur.user_id = u.user_id AND ur.role_id = r.role_id);

-- The sample doctor exists in test_data.sql; assign the CN01 scope needed by FT03.
INSERT INTO staff_branch_assignments
    (staff_id, branch_id, role_id, is_primary, start_date, end_date, status, created_at)
SELECT sp.staff_id, b.branch_id, r.role_id, 1, CURDATE(), NULL, 'ACTIVE', NOW()
FROM staff_profiles sp JOIN users u ON u.user_id = sp.user_id
JOIN branches b ON b.branch_code = 'CN01'
JOIN roles r ON r.role_name = 'BAC_SI'
WHERE u.username = 'bacsi_nam'
  AND NOT EXISTS (
      SELECT 1 FROM staff_branch_assignments a
      WHERE a.staff_id = sp.staff_id AND a.branch_id = b.branch_id
        AND a.status = 'ACTIVE' AND a.start_date <= CURDATE()
        AND (a.end_date IS NULL OR a.end_date >= CURDATE())
  );
