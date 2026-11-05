CREATE TABLE doctor_cover_grants (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    visit_id BIGINT NOT NULL,
    covering_doctor_id BIGINT NOT NULL,
    granted_by_user_id BIGINT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    granted_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    INDEX idx_cover_visit_doctor (visit_id, covering_doctor_id, expires_at),
    CONSTRAINT fk_cover_visit FOREIGN KEY (visit_id) REFERENCES visits(visit_id),
    CONSTRAINT fk_cover_doctor FOREIGN KEY (covering_doctor_id) REFERENCES staff_profiles(staff_id),
    CONSTRAINT fk_cover_granter FOREIGN KEY (granted_by_user_id) REFERENCES users(user_id)
);
