-- ====================================================================================================
-- FLYWAY MIGRATION V115: PATIENT ODONTOGRAM RECORDS
-- Lưu trữ sơ đồ tình trạng răng (FDI Người lớn 32 / Trẻ em 20) theo hồ sơ bệnh nhân & lượt khám.
-- ====================================================================================================

CREATE TABLE IF NOT EXISTS patient_odontograms (
    odontogram_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_id           BIGINT NOT NULL,
    visit_id             BIGINT NULL,
    chart_date           DATE NOT NULL,
    chart_type           VARCHAR(16) NOT NULL DEFAULT 'ADULT',
    note                 TEXT NULL,
    payload_json         LONGTEXT NOT NULL,
    status_count         INT NOT NULL DEFAULT 0,
    plan_count           INT NOT NULL DEFAULT 0,
    affected_teeth_count INT NOT NULL DEFAULT 0,
    created_by_user_id   BIGINT NULL,
    created_by_name      VARCHAR(150) NULL,
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_user_id   BIGINT NULL,
    updated_by_name      VARCHAR(150) NULL,
    updated_at           DATETIME NULL,
    is_deleted           TINYINT(1) NOT NULL DEFAULT 0,

    CONSTRAINT fk_odontogram_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles (profile_id),
    CONSTRAINT fk_odontogram_visit FOREIGN KEY (visit_id) REFERENCES visits (visit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_odontogram_profile_date
    ON patient_odontograms (profile_id, is_deleted, chart_date, created_at);

CREATE INDEX idx_odontogram_visit
    ON patient_odontograms (visit_id);
