-- Module bệnh án điện tử độc lập.
-- Không thay đổi cấu trúc các bảng FT03/FT04/FT11 hiện hữu.

CREATE TABLE IF NOT EXISTS emr_records (
    record_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    record_code VARCHAR(40) NOT NULL,
    visit_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
    current_version_no INT NOT NULL DEFAULT 1,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_emr_record_code UNIQUE (record_code),
    CONSTRAINT uk_emr_record_visit UNIQUE (visit_id),
    CONSTRAINT fk_emr_record_visit FOREIGN KEY (visit_id) REFERENCES visits(visit_id) ON DELETE RESTRICT,
    CONSTRAINT fk_emr_record_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
    CONSTRAINT fk_emr_record_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT,
    CONSTRAINT fk_emr_record_creator FOREIGN KEY (created_by) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_emr_patient_created (profile_id, created_at),
    INDEX idx_emr_branch_status (branch_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS emr_record_versions (
    version_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    template_code VARCHAR(50) NOT NULL DEFAULT 'OUTPATIENT_DENTAL',
    template_version INT NOT NULL DEFAULT 1,
    amendment_reason VARCHAR(500) NULL,
    content_hash VARCHAR(64) NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at DATETIME NULL,
    signed_at DATETIME NULL,
    CONSTRAINT uk_emr_record_version UNIQUE (record_id, version_no),
    CONSTRAINT fk_emr_version_record FOREIGN KEY (record_id) REFERENCES emr_records(record_id) ON DELETE RESTRICT,
    CONSTRAINT fk_emr_version_creator FOREIGN KEY (created_by) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_emr_version_record (record_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS emr_source_snapshots (
    snapshot_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    source_json JSON NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    captured_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_emr_snapshot_version UNIQUE (version_id),
    CONSTRAINT fk_emr_snapshot_version FOREIGN KEY (version_id) REFERENCES emr_record_versions(version_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS emr_documents (
    document_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    document_code VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    document_type VARCHAR(40) NOT NULL,
    template_version INT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    content_json JSON NOT NULL,
    document_hash VARCHAR(64) NOT NULL,
    CONSTRAINT uk_emr_version_document UNIQUE (version_id, document_code),
    CONSTRAINT fk_emr_document_version FOREIGN KEY (version_id) REFERENCES emr_record_versions(version_id) ON DELETE RESTRICT,
    INDEX idx_emr_document_version (version_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS emr_signatures (
    signature_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    signer_type VARCHAR(20) NOT NULL,
    signer_name VARCHAR(150) NOT NULL,
    signer_user_id BIGINT NULL,
    provider VARCHAR(40) NOT NULL,
    external_transaction_id VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL,
    signature_hash VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    signed_at DATETIME NULL,
    CONSTRAINT fk_emr_signature_version FOREIGN KEY (version_id) REFERENCES emr_record_versions(version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_emr_signature_user FOREIGN KEY (signer_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_emr_signature_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS emr_audit_events (
    event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL,
    version_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    action VARCHAR(50) NOT NULL,
    details VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_emr_audit_record FOREIGN KEY (record_id) REFERENCES emr_records(record_id) ON DELETE RESTRICT,
    CONSTRAINT fk_emr_audit_version FOREIGN KEY (version_id) REFERENCES emr_record_versions(version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_emr_audit_actor FOREIGN KEY (actor_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_emr_audit_record_time (record_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
