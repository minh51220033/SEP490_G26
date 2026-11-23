-- ====================================================================================================
-- FLYWAY MIGRATION V121: BO SUNG PHAN LOAI SAN PHAM (PRODUCT) TRONG DANH MUC DICH VU
-- ====================================================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v121_product_service_type$$
CREATE PROCEDURE sp_upgrade_v121_product_service_type()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema
          AND TABLE_NAME = 'service_categories'
          AND COLUMN_NAME = 'category_type'
          AND LOWER(DATA_TYPE) <> 'varchar'
    ) THEN
        ALTER TABLE service_categories
            MODIFY COLUMN category_type VARCHAR(30) NOT NULL DEFAULT 'PROCEDURE';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = v_schema
          AND TABLE_NAME = 'services'
          AND COLUMN_NAME = 'service_type'
          AND LOWER(DATA_TYPE) <> 'varchar'
    ) THEN
        ALTER TABLE services
            MODIFY COLUMN service_type VARCHAR(30) NOT NULL DEFAULT 'PROCEDURE';
    END IF;
END$$

DELIMITER ;

CALL sp_upgrade_v121_product_service_type();
DROP PROCEDURE IF EXISTS sp_upgrade_v121_product_service_type;

INSERT INTO service_categories (category_code, category_name, category_type, display_order, is_active) VALUES
('CAT_PRODUCT', 'Sản phẩm', 'PRODUCT', 9, 1)
ON DUPLICATE KEY UPDATE
    category_name = VALUES(category_name),
    category_type = VALUES(category_type),
    display_order = VALUES(display_order),
    is_active = VALUES(is_active);

INSERT INTO services (
    category_id, service_code, service_name, image_url, service_type, unit,
    base_price, surcharge_amount, estimated_duration_minutes, requires_tooth,
    is_paraclinical, is_bookable_online, warranty_months, description, status, created_at
)
SELECT sc.category_id, s.service_code, s.service_name, NULL, 'PRODUCT', s.unit,
       s.base_price, 0.00, 15, 0, 0, 0, NULL, s.descr, 'ACTIVE', NOW()
FROM (
    SELECT 'CAT_PRODUCT' ccode, 'SP-CHINK-ORALB' service_code, 'Chỉ nha khoa Oral-B' service_name,
           'Hộp' unit, 100000.00 base_price,
           'Chỉ nha khoa sợi mảnh giúp làm sạch kẽ răng và mảng bám dưới viền nướu.' descr
    UNION ALL SELECT 'CAT_PRODUCT', 'SP-KEO-FIXODENT', 'Fixodent (Keo dán hàm)',
           'Tuýp', 100000.00,
           'Keo dán cố định hàm giả tháo lắp hỗ trợ ăn nhai chắc chắn cả ngày.'
    UNION ALL SELECT 'CAT_PRODUCT', 'SP-NSM-CHX250', 'Nước súc miệng HMU Chlorhexidine 1,2% 250ml',
           'Chai', 150000.00,
           'Dung dịch súc miệng sát khuẩn khoang miệng dùng trước và sau thủ thuật nha khoa.'
    UNION ALL SELECT 'CAT_PRODUCT', 'SP-NSM-CHX500', 'Nước súc miệng HMU Chlorhexidine 1,2% 500ml',
           'Chai', 150000.00,
           'Dung dịch súc miệng sát khuẩn Chlorhexidine 1,2% dung tích lớn 500ml.'
    UNION ALL SELECT 'CAT_PRODUCT', 'SP-NSM-MUOI', 'Nước muối súc miệng',
           'Chai', 150000.00,
           'Nước muối sinh lý hỗ trợ vệ sinh khoang miệng hàng ngày và giảm viêm nướu nhẹ.'
    UNION ALL SELECT 'CAT_PRODUCT', 'SP-SAP-CHINHNHA', 'Sáp chỉnh nha',
           'Hộp', 150000.00,
           'Sáp nha khoa bảo vệ niêm mạc môi má khỏi cọ xát mắc cài khi niềng răng.'
) s
JOIN service_categories sc ON sc.category_code = s.ccode
WHERE NOT EXISTS (
    SELECT 1 FROM services existing WHERE existing.service_code = s.service_code
);
