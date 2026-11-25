-- ====================================================================================================
-- FLYWAY MIGRATION V118: TANG 1 COMBO DICH VU MAU (VTTECH) & DONG BO GIAI DOAN KE HOACH DIEU TRI
-- ====================================================================================================

CREATE TABLE IF NOT EXISTS service_combos (
    combo_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    combo_code VARCHAR(40) NOT NULL,
    combo_name VARCHAR(200) NOT NULL,
    description VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NULL,
    CONSTRAINT uk_service_combo_code UNIQUE (combo_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS service_combo_items (
    combo_item_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    combo_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    sequence_order INT NOT NULL,
    default_quantity INT NOT NULL DEFAULT 1,
    notes VARCHAR(255) NULL,
    INDEX idx_sci_combo_order (combo_id, sequence_order),
    CONSTRAINT fk_sci_combo FOREIGN KEY (combo_id) REFERENCES service_combos (combo_id) ON DELETE CASCADE,
    CONSTRAINT fk_sci_service FOREIGN KEY (service_id) REFERENCES services (service_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v118_plan_stage_link$$
CREATE PROCEDURE sp_upgrade_v118_plan_stage_link()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema
          AND table_name = 'workflow_executions'
          AND column_name = 'plan_stage_id'
    ) THEN
        ALTER TABLE workflow_executions
            ADD COLUMN plan_stage_id BIGINT NULL AFTER plan_item_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = v_schema
          AND table_name = 'workflow_executions'
          AND index_name = 'idx_we_plan_stage'
    ) THEN
        CREATE INDEX idx_we_plan_stage ON workflow_executions (plan_stage_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_schema = v_schema
          AND table_name = 'workflow_executions'
          AND constraint_name = 'fk_we_plan_stage'
    ) THEN
        ALTER TABLE workflow_executions
            ADD CONSTRAINT fk_we_plan_stage
            FOREIGN KEY (plan_stage_id) REFERENCES treatment_plan_stages (stage_id)
            ON DELETE SET NULL;
    END IF;
END$$

DELIMITER ;

CALL sp_upgrade_v118_plan_stage_link();
DROP PROCEDURE IF EXISTS sp_upgrade_v118_plan_stage_link;

-- ====================================================================================================
-- Seed bo sung quy trinh chuan cho Kham tong quat, X-quang & Nieng rang (de Combo chay tron tru)
-- ====================================================================================================
INSERT IGNORE INTO clinical_workflow_templates (template_code, template_name, description, service_id, clinic_id, status, created_by, created_at, updated_at)
SELECT src.template_code, src.template_name, src.description, sv.service_id, b.branch_id, 'ACTIVE',
       (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1), NOW(), NOW()
FROM (
    SELECT 'QTKT-KHAM-TQ' template_code, 'Quy trình Khám & Tư vấn tổng quát' template_name,
           'Khám tổng quát răng miệng, ghi nhận chỉ số lâm sàng và tư vấn kế hoạch điều trị' description, 'DV-KHAM-TQ' service_code
    UNION ALL SELECT 'QTKT-XQUANG-PANO', 'Quy trình Chụp & Đọc phim Panorama',
           'Chỉ định, chụp và đọc phim X-quang toàn cảnh Panorama' , 'DV-XQ-PANO'
    UNION ALL SELECT 'QTKT-XQUANG-CHOP', 'Quy trình Chụp & Đọc phim X-quang quanh chóp',
           'Chỉ định, chụp và đánh giá vùng chóp chân răng', 'DV-XQ-QUANH'
    UNION ALL SELECT 'QTKT-NIENG-RANG', 'Quy trình Niềng răng Chỉnh nha mắc cài',
           'Quy trình chuẩn chỉnh nha gồm khám khớp cắn, đọc phim, gắn mắc cài và theo dõi lực', 'DV-NIENG-KL'
) src
JOIN services sv ON sv.service_code = src.service_code
JOIN branches b ON b.branch_code = 'CN01'
WHERE NOT EXISTS (SELECT 1 FROM clinical_workflow_templates t WHERE t.service_id = sv.service_id);

INSERT IGNORE INTO clinical_workflow_versions (template_id, version_number, status, flow_mode, effective_from, change_summary, created_by, approved_by, created_at, published_at)
SELECT t.template_id, 1, 'PUBLISHED', 'INTERRUPTIBLE', NOW(), 'Khởi tạo quy trình tiêu chuẩn đồng bộ Combo',
       (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1),
       (SELECT user_id FROM users WHERE username = 'admin' LIMIT 1),
       NOW(), NOW()
FROM clinical_workflow_templates t
WHERE t.template_code IN ('QTKT-KHAM-TQ', 'QTKT-XQUANG-PANO', 'QTKT-XQUANG-CHOP', 'QTKT-NIENG-RANG')
  AND NOT EXISTS (
      SELECT 1 FROM clinical_workflow_versions v WHERE v.template_id = t.template_id AND v.version_number = 1
  );

INSERT IGNORE INTO clinical_workflow_steps (
    version_id, step_code, step_name, step_description, step_type, frame_key, sequence_order,
    is_optional, queue_priority, completion_criteria, requires_clinical_approval, estimated_duration_minutes, status
)
SELECT v.version_id, st.step_code, st.step_name, st.step_description, st.step_type, st.frame_key, st.seq,
       st.is_opt, 0, st.criteria, 0, st.duration_min, 'ACTIVE'
FROM (
    SELECT 'QTKT-KHAM-TQ' tpl_code, 'KTQ-B1' step_code, 'Khám tổng quát khoang miệng & Ghi nhận triệu chứng' step_name,
           'Kiểm tra tổng thể răng, nướu, khớp cắn và tư vấn hướng điều trị' step_description,
           'CLINICAL' step_type, 'CLINICAL_EXAM' frame_key, 1 seq, 0 is_opt, 'Ghi nhận đầy đủ tình trạng răng và tư vấn bệnh nhân' criteria, 15 duration_min
    UNION ALL SELECT 'QTKT-XQUANG-PANO', 'XQP-B1', 'Chụp phim toàn cảnh Panorama & Đọc kết quả',
           'Thực hiện chụp phim Panorama kỹ thuật số và ghi nhận đánh giá tổng quát' ,
           'PARACLINICAL', 'XRAY', 1, 0, 'Có phim Panorama rõ nét toàn hàm', 15
    UNION ALL SELECT 'QTKT-XQUANG-CHOP', 'XQC-B1', 'Chụp phim X-quang quanh chóp & Đánh giá',
           'Thực hiện chụp phim chóp răng chỉ định và đọc tổn thương quanh chóp',
           'PARACLINICAL', 'XRAY', 1, 0, 'Có phim quanh chóp rõ nét chóp chân răng', 10
    UNION ALL SELECT 'QTKT-NIENG-RANG', 'NR-B1', 'Bước 1: Khám khớp cắn, lấy dấu mẫu hàm & Chụp ảnh chỉnh nha',
           'Đánh giá tương quan xương hàm, khớp cắn và lấy dấu lập kế hoạch chỉnh nha',
           'CLINICAL', 'CLINICAL_EXAM', 1, 0, 'Có đầy đủ mẫu hàm và chỉ số khớp cắn ban đầu', 25
    UNION ALL SELECT 'QTKT-NIENG-RANG', 'NR-B2', 'Bước 2: Đọc phim Panorama / Cephalo chỉnh nha',
           'Phân tích phim sọ nghiêng & toàn cảnh đánh giá trục răng, mầm răng khôn',
           'PARACLINICAL', 'XRAY', 2, 1, 'Xác định góc trục răng và kế hoạch di chuyển răng', 15
    UNION ALL SELECT 'QTKT-NIENG-RANG', 'NR-B3', 'Bước 3: Gắn mắc cài & Đi dây cung khởi tạo',
           'Làm sạch men răng, gắn mắc cài đúng vị trí giải phẫu và đặt dây cung đầu tiên',
           'TREATMENT', 'PROCEDURE', 3, 0, 'Mắc cài bám chắc đúng trục, dây cung ổn định', 45
    UNION ALL SELECT 'QTKT-NIENG-RANG', 'NR-B4', 'Bước 4: Tái khám điều chỉnh lực & Hoàn tất tháo mắc cài',
           'Theo dõi dịch chuyển răng định kỳ, siết lực dây cung và duy trì sau tháo',
           'FOLLOW_UP', 'PROCEDURE', 4, 0, 'Khớp cắn đạt chuẩn thẩm mỹ và chức năng', 30
) st
JOIN clinical_workflow_templates t ON t.template_code = st.tpl_code
JOIN clinical_workflow_versions v ON v.template_id = t.template_id AND v.version_number = 1
WHERE NOT EXISTS (
    SELECT 1 FROM clinical_workflow_steps s WHERE s.version_id = v.version_id AND s.step_code = st.step_code
);

-- ====================================================================================================
-- Seed 3 Combo dich vu mau kieu VTTech Dental
-- ====================================================================================================
INSERT IGNORE INTO service_combos (combo_code, combo_name, description, status, created_at) VALUES
('CB-NR-01', 'Combo Niềng răng 1 (Tiêu chuẩn)', 'Gói chỉnh nha cơ bản: Khám tổng quát + Chụp Panorama + Lấy cao răng + Niềng răng mắc cài', 'ACTIVE', NOW()),
('CB-NR-02', 'Combo Niềng răng 2 (Có nhổ răng khôn)', 'Gói chỉnh nha nâng cao: Khám tổng quát + Chụp Panorama + Nhổ răng khôn Piezotome + Niềng răng mắc cài', 'ACTIVE', NOW()),
('CB-TUY-SU', 'Combo Điều trị tủy & Bọc mão sứ', 'Gói điều trị phục hồi liên hoàn: Chụp X-quang chóp + Điều trị tủy + Bọc răng sứ Titan', 'ACTIVE', NOW());

INSERT INTO service_combo_items (combo_id, service_id, sequence_order, default_quantity, notes)
SELECT c.combo_id, sv.service_id, src.seq, 1, src.item_note
FROM (
    SELECT 'CB-NR-01' ccode, 'DV-KHAM-TQ' scode, 1 seq, 'Khám tư vấn ban đầu' item_note
    UNION ALL SELECT 'CB-NR-01', 'DV-XQ-PANO',   2, 'Chụp phim tổng quát trước niềng'
    UNION ALL SELECT 'CB-NR-01', 'DV-CAOVOI',    3, 'Vệ sinh cao răng trước khi gắn mắc cài'
    UNION ALL SELECT 'CB-NR-01', 'DV-NIENG-KL',  4, 'Liệu trình niềng răng mắc cài'
    UNION ALL SELECT 'CB-NR-02', 'DV-KHAM-TQ',   1, 'Khám tư vấn ban đầu'
    UNION ALL SELECT 'CB-NR-02', 'DV-XQ-PANO',   2, 'Đánh giá vị trí răng khôn'
    UNION ALL SELECT 'CB-NR-02', 'DV-NHO-RKHON', 3, 'Nhổ răng khôn tạo khoảng trước niềng'
    UNION ALL SELECT 'CB-NR-02', 'DV-NIENG-KL',  4, 'Liệu trình niềng răng mắc cài'
    UNION ALL SELECT 'CB-TUY-SU', 'DV-XQ-QUANH', 1, 'X-quang khảo sát chóp & ống tủy'
    UNION ALL SELECT 'CB-TUY-SU', 'DV-NOINHA-RHAM', 2, 'Điều trị tủy răng triệt để'
    UNION ALL SELECT 'CB-TUY-SU', 'DV-SU-TITAN', 3, 'Phục hình mão răng sứ bảo vệ thân răng'
) src
JOIN service_combos c ON c.combo_code = src.ccode
JOIN services sv ON sv.service_code = src.scode
WHERE NOT EXISTS (
    SELECT 1 FROM service_combo_items sci
    WHERE sci.combo_id = c.combo_id AND sci.sequence_order = src.seq
);
