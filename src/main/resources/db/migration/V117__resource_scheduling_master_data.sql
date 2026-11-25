-- ====================================================================================================
-- FLYWAY MIGRATION V117: RESOURCE SCHEDULING MASTER DATA (PHASE 1)
-- Mục đích:
--   1. Mở rộng bảng rooms (floor_label, zone_label, floor_order) và bảng services (3 pha Prep/Proc/Turnover,
--      requires_chair, is_room_exclusive, doctor_required_in_prep/turnover, is_default_consultation).
--   2. Tạo đủ 6 bảng Master Data nguồn lực:
--      - branch_room_transition_rules
--      - clinic_equipments
--      - equipment_transfer_logs
--      - doctor_room_assignments
--      - doctor_service_permissions
--      - service_resource_requirements
--   3. Backfill AN TOÀN dữ liệu dịch vụ hiện hữu (3 pha + đánh dấu DV-KHAM-TQ + service_resource_requirements).
--      TUYỆT ĐỐI KHÔNG tự tạo thiết bị NanoPix bừa cho mọi phòng XRAY, KHÔNG gán serial mặc định giả,
--      KHÔNG tự cấp quyền ACTIVE cho bác sĩ từ staff_branch_assignments.
--
-- Truy vấn đối soát trước/sau migration:
--   SELECT COUNT(*) AS total_services, SUM(is_default_consultation) AS default_consult FROM services;
--   SELECT service_type, COUNT(*) FROM service_resource_requirements GROUP BY resource_kind, service_id;
--   SELECT device_serial_number, COUNT(*) FROM clinic_equipments WHERE device_serial_number IS NOT NULL GROUP BY device_serial_number HAVING COUNT(*) > 1;
--   SELECT status, COUNT(*) FROM doctor_room_assignments GROUP BY status;
--   SELECT status, COUNT(*) FROM doctor_service_permissions GROUP BY status;
--
-- Hướng dẫn Rollback an toàn (khi chưa bật Phase 2/enforcing):
--   DROP TABLE IF EXISTS service_resource_requirements;
--   DROP TABLE IF EXISTS doctor_service_permissions;
--   DROP TABLE IF EXISTS doctor_room_assignments;
--   DROP TABLE IF EXISTS equipment_transfer_logs;
--   DROP TABLE IF EXISTS clinic_equipments;
--   DROP TABLE IF EXISTS branch_room_transition_rules;
--   ALTER TABLE services DROP COLUMN preparation_duration_minutes, DROP COLUMN procedure_duration_minutes,
--       DROP COLUMN turnover_duration_minutes, DROP COLUMN requires_chair, DROP COLUMN is_room_exclusive,
--       DROP COLUMN doctor_required_in_prep, DROP COLUMN doctor_required_in_turnover, DROP COLUMN is_default_consultation;
--   ALTER TABLE rooms DROP COLUMN floor_label, DROP COLUMN zone_label, DROP COLUMN floor_order;
-- ====================================================================================================

DELIMITER $$

CREATE PROCEDURE sp_upgrade_v117_resource_scheduling_master_data()
BEGIN
    -- 1. Mở rộng bảng rooms
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'rooms' AND column_name = 'floor_label'
    ) THEN
        ALTER TABLE rooms ADD COLUMN floor_label VARCHAR(50) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'rooms' AND column_name = 'zone_label'
    ) THEN
        ALTER TABLE rooms ADD COLUMN zone_label VARCHAR(50) NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'rooms' AND column_name = 'floor_order'
    ) THEN
        ALTER TABLE rooms ADD COLUMN floor_order INT NOT NULL DEFAULT 1;
    END IF;

    -- 2. Mở rộng bảng services (3 pha thời lượng & cờ điều phối)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'services' AND column_name = 'preparation_duration_minutes'
    ) THEN
        ALTER TABLE services ADD COLUMN preparation_duration_minutes INT NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'services' AND column_name = 'procedure_duration_minutes'
    ) THEN
        ALTER TABLE services ADD COLUMN procedure_duration_minutes INT NOT NULL DEFAULT 30;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'services' AND column_name = 'turnover_duration_minutes'
    ) THEN
        ALTER TABLE services ADD COLUMN turnover_duration_minutes INT NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'services' AND column_name = 'requires_chair'
    ) THEN
        ALTER TABLE services ADD COLUMN requires_chair TINYINT(1) NOT NULL DEFAULT 1;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'services' AND column_name = 'is_room_exclusive'
    ) THEN
        ALTER TABLE services ADD COLUMN is_room_exclusive TINYINT(1) NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'services' AND column_name = 'doctor_required_in_prep'
    ) THEN
        ALTER TABLE services ADD COLUMN doctor_required_in_prep TINYINT(1) NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'services' AND column_name = 'doctor_required_in_turnover'
    ) THEN
        ALTER TABLE services ADD COLUMN doctor_required_in_turnover TINYINT(1) NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'services' AND column_name = 'is_default_consultation'
    ) THEN
        ALTER TABLE services ADD COLUMN is_default_consultation TINYINT(1) NOT NULL DEFAULT 0;
    END IF;

    -- 3. Tạo 6 bảng Master Data mới
    CREATE TABLE IF NOT EXISTS branch_room_transition_rules (
        rule_id BIGINT AUTO_INCREMENT PRIMARY KEY,
        branch_id BIGINT NOT NULL,
        rule_scope VARCHAR(30) NOT NULL,
        from_floor_label VARCHAR(50) NULL,
        to_floor_label VARCHAR(50) NULL,
        from_room_id BIGINT NULL,
        to_room_id BIGINT NULL,
        travel_buffer_minutes INT NOT NULL DEFAULT 0,
        is_active TINYINT(1) NOT NULL DEFAULT 1,
        rule_signature VARCHAR(150) NOT NULL,
        active_guard TINYINT NULL,
        CONSTRAINT uk_transition_rule_active UNIQUE (rule_signature, active_guard),
        CONSTRAINT fk_transition_branch FOREIGN KEY (branch_id) REFERENCES branches (branch_id),
        CONSTRAINT fk_transition_from_room FOREIGN KEY (from_room_id) REFERENCES rooms (room_id),
        CONSTRAINT fk_transition_to_room FOREIGN KEY (to_room_id) REFERENCES rooms (room_id),
        INDEX idx_transition_branch_scope (branch_id, rule_scope, is_active)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    CREATE TABLE IF NOT EXISTS clinic_equipments (
        equipment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
        branch_id BIGINT NOT NULL,
        allocation_mode VARCHAR(30) NOT NULL DEFAULT 'FIXED_ROOM',
        fixed_room_id BIGINT NULL,
        current_room_id BIGINT NULL,
        equipment_code VARCHAR(30) NOT NULL,
        equipment_name VARCHAR(150) NOT NULL,
        equipment_type VARCHAR(50) NOT NULL DEFAULT 'OTHER',
        device_serial_number VARCHAR(100) NULL,
        status VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
        CONSTRAINT uk_equip_branch_code UNIQUE (branch_id, equipment_code),
        CONSTRAINT uk_equip_serial UNIQUE (device_serial_number),
        CONSTRAINT fk_equip_branch FOREIGN KEY (branch_id) REFERENCES branches (branch_id),
        CONSTRAINT fk_equip_fixed_room FOREIGN KEY (fixed_room_id) REFERENCES rooms (room_id),
        CONSTRAINT fk_equip_current_room FOREIGN KEY (current_room_id) REFERENCES rooms (room_id),
        INDEX idx_equip_branch_mode_status (branch_id, allocation_mode, status)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    CREATE TABLE IF NOT EXISTS equipment_transfer_logs (
        transfer_id BIGINT AUTO_INCREMENT PRIMARY KEY,
        equipment_id BIGINT NOT NULL,
        transfer_type VARCHAR(30) NOT NULL,
        from_branch_id BIGINT NOT NULL,
        to_branch_id BIGINT NOT NULL,
        from_room_id BIGINT NULL,
        to_room_id BIGINT NULL,
        reason VARCHAR(255) NOT NULL,
        transferred_by BIGINT NOT NULL,
        transferred_at DATETIME NOT NULL,
        effective_from DATETIME NOT NULL,
        CONSTRAINT fk_equip_transfer_equip FOREIGN KEY (equipment_id) REFERENCES clinic_equipments (equipment_id),
        INDEX idx_equip_transfer_equip (equipment_id, transferred_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    CREATE TABLE IF NOT EXISTS doctor_room_assignments (
        assignment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
        branch_id BIGINT NOT NULL,
        staff_id BIGINT NOT NULL,
        room_id BIGINT NOT NULL,
        is_primary TINYINT(1) NOT NULL DEFAULT 0,
        effective_from DATE NOT NULL,
        effective_to DATE NULL,
        status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW',
        CONSTRAINT fk_doc_room_branch FOREIGN KEY (branch_id) REFERENCES branches (branch_id),
        CONSTRAINT fk_doc_room_staff FOREIGN KEY (staff_id) REFERENCES staff_profiles (staff_id),
        CONSTRAINT fk_doc_room_room FOREIGN KEY (room_id) REFERENCES rooms (room_id),
        INDEX idx_doc_room_branch_staff_status (branch_id, staff_id, status),
        INDEX idx_doc_room_room_status (room_id, status)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    CREATE TABLE IF NOT EXISTS doctor_service_permissions (
        permission_id BIGINT AUTO_INCREMENT PRIMARY KEY,
        staff_id BIGINT NOT NULL,
        service_id BIGINT NOT NULL,
        branch_id BIGINT NULL,
        effective_from DATE NOT NULL,
        effective_to DATE NULL,
        status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW',
        note VARCHAR(255) NULL,
        CONSTRAINT fk_doc_svc_staff FOREIGN KEY (staff_id) REFERENCES staff_profiles (staff_id),
        CONSTRAINT fk_doc_svc_service FOREIGN KEY (service_id) REFERENCES services (service_id),
        CONSTRAINT fk_doc_svc_branch FOREIGN KEY (branch_id) REFERENCES branches (branch_id),
        INDEX idx_doc_svc_perm_lookup (service_id, status, branch_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    CREATE TABLE IF NOT EXISTS service_resource_requirements (
        requirement_id BIGINT AUTO_INCREMENT PRIMARY KEY,
        service_id BIGINT NOT NULL,
        resource_kind VARCHAR(30) NOT NULL,
        room_type VARCHAR(30) NULL,
        room_id BIGINT NULL,
        equipment_type VARCHAR(50) NULL,
        equipment_id BIGINT NULL,
        usage_phase VARCHAR(20) NOT NULL DEFAULT 'PROCEDURE',
        is_required TINYINT(1) NOT NULL DEFAULT 1,
        CONSTRAINT fk_svc_req_service FOREIGN KEY (service_id) REFERENCES services (service_id),
        CONSTRAINT fk_svc_req_room FOREIGN KEY (room_id) REFERENCES rooms (room_id),
        CONSTRAINT fk_svc_req_equip FOREIGN KEY (equipment_id) REFERENCES clinic_equipments (equipment_id),
        INDEX idx_svc_res_req_service (service_id, resource_kind)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    -- 4. Backfill an toàn bảng services (giữ nguyên dữ liệu cũ, không ghi đè giá trị đã cấu hình)
    UPDATE services
       SET procedure_duration_minutes = COALESCE(estimated_duration_minutes, 30),
           preparation_duration_minutes = 0,
           turnover_duration_minutes = 0,
           requires_chair = CASE WHEN service_type = 'XRAY' THEN 0 ELSE 1 END
     WHERE procedure_duration_minutes = 30
       AND preparation_duration_minutes = 0
       AND turnover_duration_minutes = 0
       AND estimated_duration_minutes IS NOT NULL;

    UPDATE services
       SET is_default_consultation = 1
     WHERE service_code = 'DV-KHAM-TQ';

    -- Backfill yêu cầu phòng mặc định theo service_type cho các dịch vụ chưa có requirement nào (idempotent)
    INSERT INTO service_resource_requirements (service_id, resource_kind, room_type, usage_phase, is_required)
    SELECT s.service_id,
           'ROOM_TYPE',
           CASE
               WHEN s.service_type = 'CONSULTATION' THEN 'EXAM'
               WHEN s.service_type = 'XRAY' THEN 'XRAY'
               WHEN s.service_type = 'LAB' THEN 'LAB'
               ELSE 'TREATMENT'
           END,
           'ALL',
           1
      FROM services s
     WHERE NOT EXISTS (
         SELECT 1 FROM service_resource_requirements r
          WHERE r.service_id = s.service_id AND r.resource_kind = 'ROOM_TYPE'
     );

    INSERT INTO service_resource_requirements (service_id, resource_kind, equipment_type, usage_phase, is_required)
    SELECT s.service_id, 'EQUIPMENT_TYPE', 'XRAY', 'PROCEDURE', 1
      FROM services s
     WHERE s.service_type = 'XRAY'
       AND NOT EXISTS (
           SELECT 1 FROM service_resource_requirements r
            WHERE r.service_id = s.service_id AND r.resource_kind = 'EQUIPMENT_TYPE' AND r.equipment_type = 'XRAY'
       );
END $$

CALL sp_upgrade_v117_resource_scheduling_master_data() $$
DROP PROCEDURE IF EXISTS sp_upgrade_v117_resource_scheduling_master_data $$

DELIMITER ;
