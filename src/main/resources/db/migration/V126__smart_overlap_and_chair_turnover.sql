-- Smart Appointment Overlap Warning acknowledgment & Dental Chair Turnover tracking

SET @col_appt_ack = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'overlap_warning_acknowledged'
);
SET @sql_appt_ack = IF(@col_appt_ack = 0,
    'ALTER TABLE appointments ADD COLUMN overlap_warning_acknowledged TINYINT(1) NOT NULL DEFAULT 0 AFTER is_doctor_requested',
    'SELECT 1');
PREPARE stmt FROM @sql_appt_ack; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_appt_note = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'appointments' AND column_name = 'overlap_warning_note'
);
SET @sql_appt_note = IF(@col_appt_note = 0,
    'ALTER TABLE appointments ADD COLUMN overlap_warning_note VARCHAR(500) NULL AFTER overlap_warning_acknowledged',
    'SELECT 1');
PREPARE stmt FROM @sql_appt_note; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_chair_ts = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'dental_chairs' AND column_name = 'turnover_started_at'
);
SET @sql_chair_ts = IF(@col_chair_ts = 0,
    'ALTER TABLE dental_chairs ADD COLUMN turnover_started_at DATETIME NULL AFTER status',
    'SELECT 1');
PREPARE stmt FROM @sql_chair_ts; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_chair_td = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'dental_chairs' AND column_name = 'turnover_duration_minutes'
);
SET @sql_chair_td = IF(@col_chair_td = 0,
    'ALTER TABLE dental_chairs ADD COLUMN turnover_duration_minutes INT NULL AFTER turnover_started_at',
    'SELECT 1');
PREPARE stmt FROM @sql_chair_td; EXECUTE stmt; DEALLOCATE PREPARE stmt;
