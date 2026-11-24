-- Add booking_group_code to group multi-service appointments created in a single confirmation
ALTER TABLE appointments
    ADD COLUMN booking_group_code VARCHAR(40) NULL,
    ADD INDEX idx_appt_booking_group (booking_group_code);
