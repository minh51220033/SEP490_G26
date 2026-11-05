-- Queue pause/resume metadata.  Existing development databases may already
-- have these columns from Hibernate, so adopt them safely before release.
DELIMITER $$
CREATE PROCEDURE sp_upgrade_v106_queue_pause_resume()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'queue_tickets'
          AND column_name = 'paused_at'
    ) THEN
        ALTER TABLE queue_tickets ADD COLUMN paused_at DATETIME NULL;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'queue_tickets'
          AND column_name = 'resumed_at'
    ) THEN
        ALTER TABLE queue_tickets ADD COLUMN resumed_at DATETIME NULL;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'queue_tickets'
          AND column_name = 'pause_reason'
    ) THEN
        ALTER TABLE queue_tickets ADD COLUMN pause_reason VARCHAR(255) NULL;
    END IF;
END$$
DELIMITER ;
CALL sp_upgrade_v106_queue_pause_resume();
DROP PROCEDURE sp_upgrade_v106_queue_pause_resume;
