CREATE TABLE IF NOT EXISTS emr_attachments (
  attachment_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  version_id BIGINT NOT NULL,
  source_photo_id BIGINT NULL,
  original_name VARCHAR(255) NOT NULL,
  mime_type VARCHAR(100) NOT NULL,
  byte_size BIGINT NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  storage_name VARCHAR(100) NOT NULL,
  caption VARCHAR(255) NULL,
  captured_at DATETIME NULL,
  CONSTRAINT fk_emr_attachment_version FOREIGN KEY (version_id) REFERENCES emr_record_versions(version_id) ON DELETE RESTRICT,
  CONSTRAINT uk_emr_attachment_version_photo UNIQUE (version_id, source_photo_id),
  CONSTRAINT uk_emr_attachment_storage UNIQUE (storage_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
