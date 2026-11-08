-- V110: Thêm trường step_name cho treatment_plan_items và performed_services
-- Phục vụ hiển thị từng bước thủ thuật lâm sàng và tính tiền chi tiết cho từng ca khám bàn giao Kế toán

DELIMITER $$
DROP PROCEDURE IF EXISTS sp_upgrade_v110_step_name$$
CREATE PROCEDURE sp_upgrade_v110_step_name()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'treatment_plan_items' AND COLUMN_NAME = 'step_name'
    ) THEN
        ALTER TABLE treatment_plan_items ADD COLUMN step_name VARCHAR(255) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'performed_services' AND COLUMN_NAME = 'step_name'
    ) THEN
        ALTER TABLE performed_services ADD COLUMN step_name VARCHAR(255) NULL;
    END IF;
END$$
DELIMITER ;
CALL sp_upgrade_v110_step_name();
DROP PROCEDURE IF EXISTS sp_upgrade_v110_step_name;

