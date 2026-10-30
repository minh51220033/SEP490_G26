CREATE TABLE IF NOT EXISTS patient_transfer_requests (
    request_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    profile_id BIGINT NOT NULL,
    from_account_id BIGINT NOT NULL,
    target_email VARCHAR(100) NOT NULL,
    target_account_id BIGINT NULL,
    owner_code_hash VARCHAR(100) NOT NULL,
    target_code_hash VARCHAR(100) NOT NULL,
    expires_at DATETIME NOT NULL,
    consumed_at DATETIME NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ptr_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id),
    CONSTRAINT fk_ptr_from FOREIGN KEY (from_account_id) REFERENCES users(user_id),
    CONSTRAINT fk_ptr_target FOREIGN KEY (target_account_id) REFERENCES users(user_id),
    INDEX idx_ptr_profile (profile_id, created_at)
);
