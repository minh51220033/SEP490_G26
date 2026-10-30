-- =============================================================================
-- Migration V7: Đồng bộ danh mục thuốc (medications) với danh mục kho vật tư (materials)
-- Tương thích: MySQL 8.0+   |   Database: cv_dcms_db
-- Đặc điểm:
--   * Idempotent: chạy lại nhiều lần không lỗi, không nhân đôi dữ liệu.
--   * Chỉ THÊM cột / ràng buộc / bản ghi materials. KHÔNG DROP, TRUNCATE, DELETE, UPDATE dữ liệu cũ.
--   * KHÔNG tạo branch_inventory, material_batches hay stock_receipts (tồn kho giữ nguyên).
--   * Mã thuốc trùng mã vật tư không liên quan → chỉ BÁO CÁO để Admin đối soát, không tự ghép.
-- Lưu ý: ứng dụng dùng spring.jpa.hibernate.ddl-auto=update nên khi khởi động lại,
--        Hibernate có thể đã tự thêm cột/ràng buộc (bước 1-3 khi đó sẽ được bỏ qua).
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_v7_sync_medications_materials$$

CREATE PROCEDURE sp_v7_sync_medications_materials()
BEGIN
    DECLARE v_schema VARCHAR(64);
    DECLARE v_dup INT DEFAULT 0;
    SET v_schema = DATABASE();

    -- 1. Thêm cột materials.medication_id (NULL = vật tư thông thường) nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'materials' AND COLUMN_NAME = 'medication_id'
    ) THEN
        SET @sql = 'ALTER TABLE materials ADD COLUMN medication_id BIGINT NULL';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;

    -- 2. Đối soát trước khi thêm UNIQUE: nếu một thuốc đã bị liên kết với nhiều mặt hàng → dừng.
    SELECT COUNT(*) INTO v_dup FROM (
        SELECT medication_id FROM materials
        WHERE medication_id IS NOT NULL
        GROUP BY medication_id HAVING COUNT(*) > 1
    ) d;
    IF v_dup > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V7 dừng: có thuốc liên kết với nhiều mặt hàng kho. Hãy đối soát materials.medication_id trước.';
    END IF;

    -- 3a. UNIQUE trên medication_id (chỉ thêm nếu cột chưa có chỉ mục UNIQUE nào)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'materials'
          AND COLUMN_NAME = 'medication_id' AND NON_UNIQUE = 0
    ) THEN
        SET @sql = 'ALTER TABLE materials ADD CONSTRAINT uk_materials_medication UNIQUE (medication_id)';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;

    -- 3b. Khóa ngoại materials.medication_id → medications.medication_id (nếu chưa có FK nào trên cột)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.KEY_COLUMN_USAGE
        WHERE TABLE_SCHEMA = v_schema AND TABLE_NAME = 'materials'
          AND COLUMN_NAME = 'medication_id' AND REFERENCED_TABLE_NAME = 'medications'
    ) THEN
        -- Không có giá trị mồ côi thì mới thêm được FK; kiểm tra trước để báo lỗi rõ ràng.
        IF EXISTS (
            SELECT 1 FROM materials mt
            LEFT JOIN medications md ON md.medication_id = mt.medication_id
            WHERE mt.medication_id IS NOT NULL AND md.medication_id IS NULL
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'V7 dừng: materials.medication_id tham chiếu thuốc không tồn tại. Hãy đối soát trước.';
        END IF;
        SET @sql = 'ALTER TABLE materials ADD CONSTRAINT fk_materials_medication FOREIGN KEY (medication_id) REFERENCES medications(medication_id)';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;

    -- 4. BÁO CÁO XUNG ĐỘT (không sửa dữ liệu): thuốc chưa liên kết nhưng mã trùng mã của
    --    một mặt hàng kho khác. Admin cần đối soát thủ công; bước 5 sẽ bỏ qua các thuốc này.
    SELECT md.medication_id, md.drug_code, md.drug_name, md.unit AS drug_unit,
           mt.material_id, mt.material_code, mt.material_name, mt.category, mt.unit AS material_unit,
           'CONFLICT_CODE - cần Admin đối soát' AS note
    FROM medications md
    JOIN materials mt ON UPPER(TRIM(mt.material_code)) = UPPER(TRIM(md.drug_code))
    WHERE NOT EXISTS (SELECT 1 FROM materials x WHERE x.medication_id = md.medication_id)
      AND (mt.medication_id IS NULL OR mt.medication_id <> md.medication_id);

    -- 5. BACKFILL: tạo mặt hàng kho cho thuốc cũ chưa liên kết và KHÔNG có xung đột mã.
    --    Không tạo tồn kho, lô hàng hay phiếu nhập. Chạy lại an toàn nhờ NOT EXISTS + UNIQUE.
    INSERT INTO materials (material_code, material_name, category, unit, min_stock_threshold, is_active, medication_id)
    SELECT UPPER(TRIM(md.drug_code)), md.drug_name, 'Thuốc', md.unit, 10.000, md.is_active, md.medication_id
    FROM medications md
    WHERE NOT EXISTS (SELECT 1 FROM materials x WHERE x.medication_id = md.medication_id)
      AND NOT EXISTS (SELECT 1 FROM materials y WHERE UPPER(TRIM(y.material_code)) = UPPER(TRIM(md.drug_code)));

    SELECT ROW_COUNT() AS materials_created_by_v7;

    -- 6. Tổng kết sau migration
    SELECT
        (SELECT COUNT(*) FROM medications) AS total_medications,
        (SELECT COUNT(*) FROM materials WHERE medication_id IS NOT NULL) AS linked_materials,
        (SELECT COUNT(*) FROM medications md
          WHERE NOT EXISTS (SELECT 1 FROM materials x WHERE x.medication_id = md.medication_id)) AS unlinked_medications_need_review;
END$$

DELIMITER ;

CALL sp_v7_sync_medications_materials();
DROP PROCEDURE IF EXISTS sp_v7_sync_medications_materials;
