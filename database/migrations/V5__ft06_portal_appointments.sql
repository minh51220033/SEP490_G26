-- FT06 tables are additive; existing appointment and queue schemas remain unchanged.
CREATE TABLE IF NOT EXISTS appointment_histories (
    history_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    appointment_id BIGINT NOT NULL,
    action VARCHAR(30) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    old_value VARCHAR(500) NULL,
    new_value VARCHAR(500) NULL,
    reason VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_appt_history (appointment_id, created_at),
    CONSTRAINT fk_appt_history_appointment FOREIGN KEY (appointment_id) REFERENCES appointments(appointment_id),
    CONSTRAINT fk_appt_history_actor FOREIGN KEY (actor_user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS appointment_session_policies (
    policy_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    appointment_date DATE NOT NULL,
    preferred_session VARCHAR(20) NOT NULL,
    branch_capacity INT NOT NULL,
    doctor_capacity INT NOT NULL,
    CONSTRAINT uk_appt_session_policy UNIQUE (branch_id, appointment_date, preferred_session),
    CONSTRAINT fk_appt_policy_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT chk_appt_policy_branch_capacity CHECK (branch_capacity >= 0),
    CONSTRAINT chk_appt_policy_doctor_capacity CHECK (doctor_capacity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS portal_booking_requests (
    request_key VARCHAR(36) NOT NULL PRIMARY KEY,
    appointment_id BIGINT NOT NULL,
    CONSTRAINT fk_portal_booking_appointment FOREIGN KEY (appointment_id) REFERENCES appointments(appointment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS appointment_session_windows (
    window_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    preferred_session VARCHAR(20) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_appt_session_window UNIQUE (branch_id, preferred_session),
    CONSTRAINT fk_appt_window_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT chk_appt_window_time CHECK (start_time < end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
