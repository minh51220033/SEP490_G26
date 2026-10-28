-- FT07 follows the appointment migrations V5 and V6 on feature/appointments.
-- Preflight:
-- SELECT appointment_id, COUNT(*) FROM visits WHERE appointment_id IS NOT NULL GROUP BY appointment_id HAVING COUNT(*) > 1;
-- SELECT v.visit_id FROM visits v LEFT JOIN appointments a ON a.appointment_id = v.appointment_id
-- WHERE v.appointment_id IS NOT NULL AND a.appointment_id IS NULL;

ALTER TABLE visits
    ADD CONSTRAINT uk_ft07_visit_appointment UNIQUE (appointment_id),
    ADD CONSTRAINT fk_ft07_visit_appointment FOREIGN KEY (appointment_id)
        REFERENCES appointments (appointment_id);

CREATE INDEX idx_ft07_profile_status ON visits (profile_id, status);

CREATE TABLE IF NOT EXISTS reception_checkin_keys (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_key VARCHAR(36) NOT NULL,
    visit_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_reception_checkin_key UNIQUE (request_key),
    CONSTRAINT uk_reception_checkin_visit UNIQUE (visit_id),
    CONSTRAINT fk_reception_checkin_visit FOREIGN KEY (visit_id) REFERENCES visits (visit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
