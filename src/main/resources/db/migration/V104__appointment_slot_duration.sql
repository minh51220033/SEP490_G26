-- Existing development databases may already have these columns from Hibernate.
-- Verify each object before adding it so Flyway can adopt those databases safely.
DELIMITER $$
CREATE PROCEDURE sp_upgrade_v104_appointment_slots()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'services'
          AND column_name = 'estimated_duration_minutes'
    ) THEN
        ALTER TABLE services ADD COLUMN estimated_duration_minutes INT NOT NULL DEFAULT 30;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'appointments'
          AND column_name = 'duration_minutes'
    ) THEN
        ALTER TABLE appointments ADD COLUMN duration_minutes INT NOT NULL DEFAULT 30;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'appointments'
          AND index_name = 'idx_appt_slot_lookup'
    ) THEN
        CREATE INDEX idx_appt_slot_lookup ON appointments
            (branch_id, appointment_date, preferred_session, expected_time, doctor_id);
    END IF;
END$$
DELIMITER ;
CALL sp_upgrade_v104_appointment_slots();
DROP PROCEDURE sp_upgrade_v104_appointment_slots;
