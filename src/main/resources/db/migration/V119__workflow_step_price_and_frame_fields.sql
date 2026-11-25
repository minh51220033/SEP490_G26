-- ====================================================================================================
-- FLYWAY MIGRATION V119: BO SUNG COT STEP_PRICE & BANG CAU HINH TRUONG KHUNG THAO TAC
-- Muc dich:
--   1. Bo sung cot step_price cho clinical_workflow_steps (khop voi Entity ClinicalWorkflowStep khi Hibernate validate).
--   2. Tao bang clinical_workflow_frame_fields phuc vu cau hinh truong mac dinh theo 5 khung thao tac.
--   (Du lieu demo da duoc tach rieng sang db/demo/demo_workflow_frames_dataset.sql de an toan cho moi truong Production.)
-- ====================================================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v119_workflow_step_price$$
CREATE PROCEDURE sp_upgrade_v119_workflow_step_price()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema
          AND table_name = 'clinical_workflow_steps'
          AND column_name = 'step_price'
    ) THEN
        ALTER TABLE clinical_workflow_steps
            ADD COLUMN step_price DECIMAL(15, 2) NULL AFTER estimated_duration_minutes;
    END IF;
END$$

DELIMITER ;

CALL sp_upgrade_v119_workflow_step_price();
DROP PROCEDURE IF EXISTS sp_upgrade_v119_workflow_step_price;

CREATE TABLE IF NOT EXISTS clinical_workflow_frame_fields (
    frame_key VARCHAR(40) NOT NULL PRIMARY KEY,
    custom_fields_json TEXT NULL,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
