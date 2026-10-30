-- =========================================================================
-- MIGRATION V8: FT12 - QUẢN LÝ KẾT QUẢ PHIM X-QUANG NANOPIX CHO CV-DCMS
-- =========================================================================

CREATE TABLE IF NOT EXISTS nanopix_xray_sync_logs (
    sync_log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_study_uid VARCHAR(100) NOT NULL,
    external_patient_id VARCHAR(50) NULL,
    raw_patient_name VARCHAR(100) NULL,
    image_url VARCHAR(500) NOT NULL,
    sync_status VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED',
    matched_profile_id BIGINT NULL,
    matched_visit_id BIGINT NULL,
    matched_item_id BIGINT NULL,
    branch_id BIGINT NULL,
    tooth_numbers VARCHAR(50) NULL,
    is_manual_linked BOOLEAN NOT NULL DEFAULT FALSE,
    linked_by BIGINT NULL,
    linked_at DATETIME NULL,
    captured_at DATETIME NOT NULL,
    device_serial_number VARCHAR(100) NULL DEFAULT 'EN1E0408A032',
    device_model VARCHAR(50) NULL DEFAULT 'NanoPix1',
    error_message VARCHAR(500) NULL,
    notes VARCHAR(500) NULL,
    sync_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_nanopix_uid UNIQUE (external_study_uid),
    CONSTRAINT fk_nanopix_profile FOREIGN KEY (matched_profile_id) REFERENCES patient_profiles(profile_id) ON DELETE SET NULL,
    CONSTRAINT fk_nanopix_visit FOREIGN KEY (matched_visit_id) REFERENCES visits(visit_id) ON DELETE SET NULL,
    CONSTRAINT fk_nanopix_item FOREIGN KEY (matched_item_id) REFERENCES paraclinical_order_items(order_item_id) ON DELETE SET NULL,
    CONSTRAINT fk_nanopix_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE SET NULL,
    CONSTRAINT fk_nanopix_staff FOREIGN KEY (linked_by) REFERENCES staff_profiles(staff_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_nanopix_sync_status ON nanopix_xray_sync_logs(sync_status);
CREATE INDEX idx_nanopix_captured_at ON nanopix_xray_sync_logs(captured_at);
CREATE INDEX idx_nanopix_profile ON nanopix_xray_sync_logs(matched_profile_id);
CREATE INDEX idx_nanopix_visit ON nanopix_xray_sync_logs(matched_visit_id);

CREATE INDEX idx_nanopix_branch ON nanopix_xray_sync_logs(branch_id);
