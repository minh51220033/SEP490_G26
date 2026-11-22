-- ====================================================================================================
-- FLYWAY MIGRATION V117: KHUNG THAO TAC BUOC QUY TRINH, BUOC PHAT SINH & DONG BO KE HOACH DIEU TRI
-- Muc dich:
--   1. Bo sung frame_key, custom_fields_json, adhoc_execution_id cho clinical_workflow_steps.
--   2. Bo sung step_order cho workflow_step_executions de sap xep buoc phat sinh theo tung ca.
--   3. Bo sung plan_item_id cho workflow_executions de dong bo 2 chieu voi Ke hoach dieu tri & nhieu buoi.
--   4. Backfill frame_key cho du lieu quy trinh mau da seed o V111.
-- ====================================================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v117_workflow_frames$$
CREATE PROCEDURE sp_upgrade_v117_workflow_frames()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema
          AND table_name = 'clinical_workflow_steps'
          AND column_name = 'frame_key'
    ) THEN
        ALTER TABLE clinical_workflow_steps
            ADD COLUMN frame_key VARCHAR(40) NULL AFTER step_type;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema
          AND table_name = 'clinical_workflow_steps'
          AND column_name = 'custom_fields_json'
    ) THEN
        ALTER TABLE clinical_workflow_steps
            ADD COLUMN custom_fields_json TEXT NULL AFTER completion_criteria;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema
          AND table_name = 'clinical_workflow_steps'
          AND column_name = 'adhoc_execution_id'
    ) THEN
        ALTER TABLE clinical_workflow_steps
            ADD COLUMN adhoc_execution_id BIGINT NULL AFTER version_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = v_schema
          AND table_name = 'clinical_workflow_steps'
          AND index_name = 'idx_cws_adhoc_exec'
    ) THEN
        CREATE INDEX idx_cws_adhoc_exec ON clinical_workflow_steps (adhoc_execution_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema
          AND table_name = 'workflow_step_executions'
          AND column_name = 'step_order'
    ) THEN
        ALTER TABLE workflow_step_executions
            ADD COLUMN step_order INT NULL AFTER step_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema
          AND table_name = 'workflow_executions'
          AND column_name = 'plan_item_id'
    ) THEN
        ALTER TABLE workflow_executions
            ADD COLUMN plan_item_id BIGINT NULL AFTER branch_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = v_schema
          AND table_name = 'workflow_executions'
          AND index_name = 'idx_we_plan_item'
    ) THEN
        CREATE INDEX idx_we_plan_item ON workflow_executions (plan_item_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_schema = v_schema
          AND table_name = 'workflow_executions'
          AND constraint_name = 'fk_we_plan_item'
    ) THEN
        ALTER TABLE workflow_executions
            ADD CONSTRAINT fk_we_plan_item
            FOREIGN KEY (plan_item_id) REFERENCES treatment_plan_items (plan_item_id)
            ON DELETE SET NULL;
    END IF;
END$$

DELIMITER ;

CALL sp_upgrade_v117_workflow_frames();
DROP PROCEDURE IF EXISTS sp_upgrade_v117_workflow_frames;

-- Backfill frame_key thong minh cho cac buoc da ton tai ma chua gan khung
UPDATE clinical_workflow_steps ws
JOIN clinical_workflow_versions v ON v.version_id = ws.version_id
JOIN clinical_workflow_templates t ON t.template_id = v.template_id
SET ws.frame_key = CASE
    WHEN ws.step_type = 'PARACLINICAL'
         OR LOWER(ws.step_name) LIKE '%x-quang%'
         OR LOWER(ws.step_name) LIKE '%xquang%'
         OR LOWER(ws.step_name) LIKE '%phim%' THEN 'XRAY'
    WHEN t.template_code LIKE '%TUY%'
         OR LOWER(ws.step_name) LIKE '%nội nha%'
         OR LOWER(ws.step_name) LIKE '%ống tủy%'
         OR LOWER(ws.step_name) LIKE '%mở tủy%' THEN 'ENDODONTICS'
    WHEN LOWER(ws.step_name) LIKE '%thử sườn%'
         OR LOWER(ws.step_name) LIKE '%thử mão%'
         OR LOWER(ws.step_name) LIKE '%labo%'
         OR LOWER(ws.step_name) LIKE '%lấy dấu%'
         OR LOWER(ws.step_name) LIKE '%mão%' THEN 'PROSTHETICS_LABO'
    WHEN ws.step_type = 'CLINICAL'
         AND (LOWER(ws.step_name) LIKE '%thăm khám%' OR LOWER(ws.step_name) LIKE '%khám%') THEN 'CLINICAL_EXAM'
    ELSE 'PROCEDURE'
END
WHERE ws.frame_key IS NULL;

-- Backfill step_order cho cac ban ghi step execution da co
UPDATE workflow_step_executions wse
JOIN clinical_workflow_steps ws ON ws.step_id = wse.step_id
SET wse.step_order = ws.sequence_order
WHERE wse.step_order IS NULL;
