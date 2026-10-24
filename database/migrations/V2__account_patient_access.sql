-- Run once after V1. Existing portal ownership is preserved.
CREATE TABLE IF NOT EXISTS account_patient_profiles (
    access_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    access_type VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_account_patient_profile UNIQUE (profile_id),
    CONSTRAINT fk_app_account FOREIGN KEY (account_id) REFERENCES users(user_id),
    CONSTRAINT fk_app_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id),
    INDEX idx_app_account (account_id)
);

INSERT INTO account_patient_profiles (account_id, profile_id, access_type)
SELECT p.user_id, p.profile_id,
       CASE WHEN p.is_default = 1 THEN 'SELF' ELSE 'DEPENDENT' END
FROM patient_profiles p
WHERE p.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM account_patient_profiles a WHERE a.profile_id = p.profile_id);
