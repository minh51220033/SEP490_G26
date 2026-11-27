-- Gắn kết Kế hoạch điều trị (treatment_plans) với ca khám khởi tạo (visits)
-- Giúp phân tách rõ KHDT của ca khám hôm nay vs các ca khám trước theo từng visit
DELIMITER $$

CREATE PROCEDURE sp_upgrade_v127_treatment_plan_visit_link()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'treatment_plans'
          AND column_name = 'visit_id'
    ) THEN
        ALTER TABLE treatment_plans ADD COLUMN visit_id BIGINT NULL AFTER branch_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'treatment_plans'
          AND index_name = 'idx_treatment_plans_visit'
    ) THEN
        CREATE INDEX idx_treatment_plans_visit ON treatment_plans (visit_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_schema = DATABASE()
          AND table_name = 'treatment_plans'
          AND constraint_name = 'fk_treatment_plans_visit'
    ) THEN
        ALTER TABLE treatment_plans
            ADD CONSTRAINT fk_treatment_plans_visit
            FOREIGN KEY (visit_id) REFERENCES visits (visit_id)
            ON DELETE SET NULL;
    END IF;
END$$

DELIMITER ;

CALL sp_upgrade_v127_treatment_plan_visit_link();
DROP PROCEDURE IF EXISTS sp_upgrade_v127_treatment_plan_visit_link;
