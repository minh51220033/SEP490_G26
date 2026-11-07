-- V109: Cập nhật người duyệt tạm ứng lương (approved_by) tham chiếu tới users(user_id) thay vì staff_profiles(staff_id)
-- Lý do: Quản trị viên (Admin) duyệt tạm ứng lương có thể là tài khoản quản trị hệ thống không gắn với StaffProfile.

DELIMITER $$
DROP PROCEDURE IF EXISTS sp_upgrade_v109_salary_advance_approver$$
CREATE PROCEDURE sp_upgrade_v109_salary_advance_approver()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- Chuyển đổi dữ liệu cũ nếu approved_by đang tham chiếu staff_profiles(staff_id)
    UPDATE salary_advances sa
    JOIN staff_profiles sp ON sa.approved_by = sp.staff_id
    SET sa.approved_by = sp.user_id
    WHERE sa.approved_by IS NOT NULL;

    -- Xóa khóa ngoại cũ nếu tồn tại
    IF EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'salary_advances' AND CONSTRAINT_NAME = 'fk_adv_approved_by'
    ) THEN
        ALTER TABLE salary_advances DROP FOREIGN KEY fk_adv_approved_by;
    END IF;

    -- Thêm khóa ngoại mới tham chiếu users(user_id)
    ALTER TABLE salary_advances
    ADD CONSTRAINT fk_adv_approved_by FOREIGN KEY (approved_by) REFERENCES users(user_id);
END$$
DELIMITER ;
CALL sp_upgrade_v109_salary_advance_approver();
DROP PROCEDURE IF EXISTS sp_upgrade_v109_salary_advance_approver;

