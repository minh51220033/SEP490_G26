-- Bổ sung hai thành phần xuất/in cho các phiên bản EMR đã tồn tại.
-- Bản ghi mới được tạo trực tiếp trong EmrModuleService.capture().
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO emr_documents
    (version_id, document_code, title, document_type, template_version,
     sort_order, content_json, document_hash)
SELECT version.version_id, component.document_code, component.title,
       component.document_type, 1, component.sort_order, snapshot.source_json,
       SHA2(CONCAT(component.document_code, '|', CAST(snapshot.source_json AS CHAR)), 256)
FROM emr_record_versions version
JOIN emr_source_snapshots snapshot ON snapshot.version_id = version.version_id
CROSS JOIN (
    SELECT 'ATTACHMENT_INDEX' document_code, 'Danh mục tài liệu đính kèm' title,
           'ATTACHMENT_INDEX' document_type, 40 sort_order
    UNION ALL
    SELECT 'VERIFICATION', 'Thông tin xác thực hồ sơ', 'VERIFICATION', 50
) component
WHERE NOT EXISTS (
    SELECT 1 FROM emr_documents document
    WHERE document.version_id = version.version_id
      AND document.document_code = component.document_code
);
