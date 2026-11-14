-- ====================================================================================================
-- CV-DCMS: 04_services_workflows.sql (NHÓM DỊCH VỤ NHA KHOA, ICD-10 & QUY TRÌNH KHÁM LÂM SÀNG)
-- Đối chiếu 100% với Schema cv_dcms_db_backup.sql
-- An toàn khi chạy nhiều lần (Idempotent - ON DUPLICATE KEY UPDATE)
-- ====================================================================================================

SET NAMES utf8mb4;

-- 1. DANH MỤC BỆNH LÝ NHA KHOA CHUẨN QUỐC TẾ (icd10_diseases)
INSERT INTO icd10_diseases (icd10_code, disease_name_vi, disease_name_en, is_dental_common, is_active) VALUES
    ('K00.6', 'Rối loạn mọc răng (Răng sữa tồn tại lâu)',         'Disturbances in tooth eruption',            1, 1),
    ('K01.1', 'Răng ngầm / Răng khôn mọc lệch kẹt',               'Impacted teeth',                            1, 1),
    ('K02.0', 'Sâu men răng giai đoạn sớm',                       'Caries limited to enamel',                  1, 1),
    ('K02.1', 'Sâu ngà răng tiến triển',                          'Caries of dentine',                         1, 1),
    ('K03.6', 'Cao răng (Vôi răng) và mảng bám răng',             'Deposits [accretions] on teeth',            1, 1),
    ('K04.0', 'Viêm tủy răng cấp / mạn tính',                     'Pulpitis',                                  1, 1),
    ('K04.1', 'Hoại tử tủy răng',                                 'Necrosis of pulp',                          1, 1),
    ('K04.4', 'Viêm quanh chóp răng cấp tính có nguồn gốc tủy',   'Acute apical periodontitis of pulpal origin',1, 1),
    ('K05.0', 'Viêm nướu (lợi) cấp tính',                         'Acute gingivitis',                          1, 1),
    ('K05.1', 'Viêm nướu (lợi) mạn tính',                         'Chronic gingivitis',                        1, 1),
    ('K05.3', 'Viêm nha chu mạn tính',                            'Chronic periodontitis',                     1, 1),
    ('K07.2', 'Sai khớp cắn (Hô, móm, cắn sâu, cắn hở)',          'Anomalies of dental arch relationship',     1, 1),
    ('K07.3', 'Răng mọc lệch lạc, chen chúc, thưa kẽ',            'Anomalies of tooth position',               1, 1),
    ('K08.1', 'Mất răng do tai nạn, nhổ răng hoặc bệnh nha chu',  'Loss of teeth due to accident, extraction', 1, 1)
ON DUPLICATE KEY UPDATE
    disease_name_vi  = VALUES(disease_name_vi),
    disease_name_en  = VALUES(disease_name_en),
    is_dental_common = VALUES(is_dental_common),
    is_active        = VALUES(is_active);

-- 2. NHÓM DỊCH VỤ NHA KHOA (service_categories)
INSERT INTO service_categories (category_id, category_code, category_name, category_type, display_order, is_active) VALUES
    (1, 'CAT-KHAM',   'Khám & Tư vấn Nha khoa',             'CONSULTATION', 1, 1),
    (2, 'CAT-XQUANG', 'Chẩn đoán hình ảnh & X-Quang',       'XRAY',         2, 1),
    (3, 'CAT-TONGQUAT','Nha khoa Tổng quát & Dự phòng',     'PROCEDURE',    3, 1),
    (4, 'CAT-NOINHA', 'Điều trị Nội nha (Chữa tủy)',        'PROCEDURE',    4, 1),
    (5, 'CAT-TIEUPHAU','Tiểu phẫu & Nhổ răng khôn',         'PROCEDURE',    5, 1),
    (6, 'CAT-THAMMY', 'Nha khoa Thẩm mỹ & Tẩy trắng',       'PROCEDURE',    6, 1),
    (7, 'CAT-PHUCHINH','Bọc răng sứ & Phục hình Cố định',   'LAB',          7, 1),
    (8, 'CAT-CHINHNHA','Niềng răng - Chỉnh nha chuyên sâu', 'PROCEDURE',    8, 1),
    (9, 'CAT-IMPLANT','Cấy ghép Implant Kỹ thuật số',       'PROCEDURE',    9, 1)
ON DUPLICATE KEY UPDATE
    category_name = VALUES(category_name),
    category_type = VALUES(category_type),
    display_order = VALUES(display_order),
    is_active     = VALUES(is_active);

-- 3. DANH MỤC DỊCH VỤ NHA KHOA (services)
INSERT INTO services (service_id, category_id, service_code, service_name, service_type, unit, base_price, surcharge_amount, estimated_duration_minutes, warranty_months, is_bookable_online, is_paraclinical, requires_tooth, status, image_url, description, technical_requirements, result_template_json, created_at) VALUES
    (1,  1, 'DV-KHAM-TQ',   'Khám tổng quát & Tư vấn phác đồ điều trị',        'CONSULTATION', 'Lần',  100000.00, 0.00, 20, 0,   1, 0, 0, 'ACTIVE', '/images/services/kham-tong-quat.jpg', 'Khám tổng quát răng miệng, kiểm tra khớp cắn, tầm soát sâu răng và bệnh lý nha chu.', 'Bộ khay khám vô khuẩn, đèn soi nội nha.', NULL, NOW()),
    (2,  3, 'DV-CAO-RANG',  'Lấy cao răng & Đánh bóng siêu âm 2 hàm',          'PROCEDURE',    'Lần',  300000.00, 0.00, 30, 0,   1, 0, 0, 'ACTIVE', '/images/services/lay-cao-rang.jpg',   'Loại bỏ mảng bám, vôi răng dưới nướu bằng sóng siêu âm Cavitron êm ái, đánh bóng mặt răng.', 'Máy lấy cao siêu âm, chổi đánh bóng, sò đánh bóng.', NULL, NOW()),
    (3,  3, 'DV-TRAM-COMP', 'Trám răng thẩm mỹ Composite 3M Filtek Z350',      'PROCEDURE',    'Răng', 400000.00, 0.00, 30, 12,  1, 0, 1, 'ACTIVE', '/images/services/tram-rang.jpg',      'Phục hồi xoang sâu hoặc mẻ cổ răng bằng vật liệu Composite 3M quang trùng hợp tự nhiên.', 'Đèn quang trùng hợp LED, keo dán Single Bond, Composite 3M.', NULL, NOW()),
    (4,  5, 'DV-NHO-THUONG','Nhổ răng vĩnh viễn / chân răng lung lay',         'PROCEDURE',    'Răng', 500000.00, 0.00, 30, 0,   1, 0, 1, 'ACTIVE', '/images/services/nho-rang.jpg',       'Nhổ răng vĩnh viễn không còn khả năng bảo tồn hoặc chân răng lung lay gây tê an toàn.', 'Thuốc tê Septanest, kìm bẩy nha khoa vô trùng.', NULL, NOW()),
    (5,  5, 'DV-NHO-RK8',   'Tiểu phẫu nhổ răng khôn mọc lệch bằng Piezotome', 'PROCEDURE',    'Răng', 2000000.00,0.00, 60, 0,   1, 0, 1, 'ACTIVE', '/images/services/nho-rang-khon.jpg',  'Tiểu phẫu nhổ răng số 8 mọc lệch/ngầm bằng máy siêu âm Piezotome ít sang chấn, mau lành thương.', 'Máy phẫu thuật siêu âm Piezotome, chỉ khâu 4.0, phim X-Quang khảo sát trước nhổ.', NULL, NOW()),
    (6,  4, 'DV-TUY-RANG',  'Điều trị tủy răng hàm công nghệ máy X-Smart',     'PROCEDURE',    'Răng', 1500000.00,0.00, 60, 12,  1, 0, 1, 'ACTIVE', '/images/services/dieu-tri-tuy.jpg',   'Lấy sạch tủy viêm, tạo hình ống tủy bằng trâm máy ProTaper và hàn kín ống tủy chuẩn 3 chiều.', 'Máy định vị chóp, máy nội nha X-Smart, côn Gutta Percha.', NULL, NOW()),
    (7,  6, 'DV-TAY-TRANG', 'Tẩy trắng răng công nghệ Laser Whitening',        'PROCEDURE',    'Liệu trình', 2500000.00, 0.00, 60, 6, 1, 0, 0, 'ACTIVE', '/images/services/tay-trang-rang.jpg', 'Hoạt hóa thuốc tẩy trắng Pola Office bằng ánh sáng Laser lạnh giúp bật 2-3 tông màu răng.', 'Đèn tẩy trắng Laser, banh miệng, gel cách ly nướu.', NULL, NOW()),
    (8,  7, 'DV-SU-ZIRCON', 'Bọc răng sứ toàn sứ Đức Zirconia CAD/CAM',        'LAB',          'Răng', 4500000.00,0.00, 60, 120, 1, 0, 1, 'ACTIVE', '/images/services/boc-rang-su.jpg',    'Phục hình mão/cầu răng toàn sứ Zirconia chính hãng Đức thiết kế CAD/CAM chịu lực gấp 5 lần răng thật.', 'Lấy dấu kỹ thuật số hoặc Silicone, gửi xưởng Labo chế tác.', NULL, NOW()),
    (9,  8, 'DV-NIENG-3M',  'Niềng răng mắc cài kim loại tự buộc 3M Unitek',   'PROCEDURE',    '2 Hàm',35000000.00,0.00,90, 60,  1, 0, 0, 'ACTIVE', '/images/services/nieng-rang.jpg',     'Chỉnh nha toàn diện khắc phục răng hô, móm, lệch lạc bằng mắc cài tự buộc 3M Unitek chính hãng Mỹ.', 'Phim Panorama + Cephalometric, bộ mắc cài 3M Unitek, dây cung NiTi.', NULL, NOW()),
    (10, 9, 'DV-IMPLANT',   'Cấy ghép trụ Implant Straumann / MIS C1 trọn gói','PROCEDURE',    'Trụ',  18000000.00,0.00,90, 180, 1, 0, 1, 'ACTIVE', '/images/services/cay-implant.jpg',    'Phẫu thuật đặt trụ Implant tích hợp xương nhanh, bao gồm trụ Titanium, khớp nối Abutment và mão sứ.', 'Phòng phẫu thuật vô trùng, máy cắm Implant, phim CT ConeBeam 3D.', NULL, NOW()),
    (11, 2, 'DV-XQ-PANO',   'Chụp X-Quang Toàn cảnh Kỹ thuật số (Panorama)',   'XRAY',         'Phim', 200000.00, 0.00, 15, 0,   0, 1, 0, 'ACTIVE', '/images/services/chup-xquang.jpg',    'Chụp phim X-quang toàn cảnh 2 hàm kỹ thuật số NanoPix bức xạ thấp, khảo sát răng ngầm và xương hàm.', 'Máy X-Quang kỹ thuật số, áo chì bảo hộ.', '{"modality":"PANORAMA","fields":["findings","boneDensity","impactionStatus","conclusion"]}', NOW()),
    (12, 2, 'DV-XQ-CT3D',   'Chụp phim CT ConeBeam 3D khảo sát xương hàm',     'XRAY',         'Phim', 500000.00, 0.00, 20, 0,   0, 1, 0, 'ACTIVE', '/images/services/chup-ct3d.jpg',      'Chụp cắt lớp vi tính 3 chiều khảo sát mật độ xương, ống thần kinh hàm dưới phục vụ cấy Implant & nhổ răng khôn.', 'Máy CT ConeBeam 3D.', '{"modality":"CBCT_3D","fields":["boneHeightMm","boneWidthMm","nerveDistanceMm","sinusStatus","conclusion"]}', NOW())
ON DUPLICATE KEY UPDATE
    service_name               = VALUES(service_name),
    base_price                 = VALUES(base_price),
    estimated_duration_minutes = VALUES(estimated_duration_minutes),
    warranty_months            = VALUES(warranty_months),
    is_bookable_online         = VALUES(is_bookable_online),
    is_paraclinical            = VALUES(is_paraclinical),
    requires_tooth             = VALUES(requires_tooth),
    status                     = VALUES(status),
    description                = VALUES(description);

-- 4. QUY TRÌNH KHÁM VÀ ĐIỀU TRỊ CHUẨN (CLINICAL WORKFLOW ENGINE)
INSERT INTO clinical_workflow_templates (template_id, workflow_code, workflow_name, category, service_id, description, current_version_id, status, is_deleted, created_by, updated_by, created_at, updated_at) VALUES
    (1, 'WF-NHO-RK8', 'Quy trình Tiểu phẫu Nhổ răng khôn mọc lệch chuẩn Y khoa', 'Tiểu phẫu RHM',   5,  'Quy trình chuẩn 6 bước dành cho ca khám, chụp phim X-Quang, tiểu phẫu nhổ răng khôn và theo dõi hậu phẫu.', NULL, 'ACTIVE', 0, 1, 1, NOW(), NOW()),
    (2, 'WF-NOI-NHA', 'Quy trình Điều trị Nội nha (Chữa tủy răng) chuẩn Quốc tế', 'Nội nha',         6,  'Quy trình khám, chụp phim kiểm tra chóp, lấy tủy và hàn kín ống tủy.', NULL, 'ACTIVE', 0, 1, 1, NOW(), NOW()),
    (3, 'WF-IMPLANT', 'Quy trình Cấy ghép Implant Kỹ thuật số vô khuẩn',          'Cấy ghép Implant',10, 'Quy trình khảo sát CT ConeBeam 3D, lập kế hoạch cắm trụ Implant và phục hình răng sứ trên Implant.', NULL, 'ACTIVE', 0, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    workflow_name = VALUES(workflow_name),
    description   = VALUES(description),
    status        = VALUES(status),
    updated_at    = NOW();

INSERT INTO clinical_workflow_versions (version_id, template_id, version_number, version_status, change_notes, effective_from, is_current, approved_by, approved_at, created_by, created_at) VALUES
    (1, 1, 1, 'PUBLISHED', 'Phiên bản chuẩn hóa quy trình tiểu phẫu nhổ răng khôn v1.0', '2025-01-01', 1, 1, NOW(), 1, NOW()),
    (2, 2, 1, 'PUBLISHED', 'Phiên bản chuẩn hóa quy trình điều trị nội nha v1.0',        '2025-01-01', 1, 1, NOW(), 1, NOW()),
    (3, 3, 1, 'PUBLISHED', 'Phiên bản chuẩn hóa quy trình cấy ghép Implant v1.0',        '2025-01-01', 1, 1, NOW(), 1, NOW())
ON DUPLICATE KEY UPDATE
    version_status = VALUES(version_status),
    is_current     = VALUES(is_current),
    change_notes   = VALUES(change_notes);

UPDATE clinical_workflow_templates SET current_version_id = 1 WHERE template_id = 1;
UPDATE clinical_workflow_templates SET current_version_id = 2 WHERE template_id = 2;
UPDATE clinical_workflow_templates SET current_version_id = 3 WHERE template_id = 3;

INSERT INTO clinical_workflow_sections (section_id, version_id, section_code, section_title, display_order, content) VALUES
    (1, 1, 'I',   'Mục đích & Phạm vi áp dụng', 1, 'Đảm bảo an toàn tuyệt đối trong tiểu phẫu nhổ răng khôn mọc lệch/ngầm, kiểm soát nhiễm khuẩn và biến chứng thần kinh ống răng dưới.'),
    (2, 1, 'II',  'Chỉ định & Chống chỉ định',  2, 'Chỉ định cho răng số 8 mọc lệch, lợi trùm tái phát, sâu mặt xa răng số 7. Chống chỉ định tạm thời khi đang viêm cấp tính nặng hoặc huyết áp > 160/100 mmHg.'),
    (3, 1, 'III', 'Chuẩn bị thiết bị & Vật tư', 3, 'Máy phẫu thuật siêu âm Piezotome, bộ tiểu phẫu vô khuẩn, thuốc tê Septanest 1:100.000, chỉ khâu 4.0, gạc vô trùng.'),
    (4, 1, 'IV',  'Các bước thực hiện quy trình',4, '1. Khám lâm sàng & đo sinh hiệu -> 2. Chụp phim X-Quang Panorama/CT 3D -> 3. Đọc phim & ký cam kết -> 4. Gây tê & Tiểu phẫu Piezotome -> 5. Kê đơn & Dặn dò hậu phẫu -> 6. CSKH hỏi thăm & Cắt chỉ.'),
    (5, 1, 'V',   'Theo dõi & Xử trí tai biến', 5, 'Theo dõi cầm máu tại chỗ 30-45 phút sau nhổ, hướng dẫn chườm lạnh 24h đầu và liên hệ bác sĩ ngay nếu chảy máu kéo dài.'),
    (6, 1, 'VI',  'Tài liệu tham khảo',         6, 'Hướng dẫn Chẩn đoán và Điều trị chuyên ngành Răng Hàm Mặt - Bộ Y tế.')
ON DUPLICATE KEY UPDATE
    section_title = VALUES(section_title),
    content       = VALUES(content);

INSERT INTO clinical_workflow_steps (step_id, version_id, parent_step_id, step_code, step_name, step_type, sequence_order, estimated_duration_minutes, step_price, target_queue_type, queue_priority, is_optional, requires_clinical_approval, status, step_description, completion_criteria) VALUES
    (1, 1, NULL, 'RK8-B1', 'Khám lâm sàng & Kiểm tra sinh hiệu',          'CLINICAL',     1, 15, 100000.00,  'EXAM',           10, 0, 0, 'ACTIVE', 'Khám trong miệng, đánh giá mức độ mọc lệch và kiểm tra huyết áp, mạch, tiền sử dị ứng.', 'Đã nhập đầy đủ sinh hiệu và khám lâm sàng.'),
    (2, 1, NULL, 'RK8-B2', 'Chụp phim X-Quang khảo sát chân răng & ống TK','PARACLINICAL', 2, 15, 200000.00,  'PARACLINICAL',   20, 0, 0, 'ACTIVE', 'Chụp phim Panorama hoặc CT ConeBeam 3D xác định tương quan chân răng số 8 với ống thần kinh hàm dưới.', 'Đã tải lên ảnh phim X-Quang kỹ thuật số.'),
    (3, 1, NULL, 'RK8-B3', 'Đọc kết quả phim & Tư vấn ký cam kết thủ thuật','DECISION',    3, 10, 0.00,       'RESULT_READING', 30, 0, 1, 'ACTIVE', 'Bác sĩ phân tích phim X-Quang, giải thích phác đồ tiểu phẫu và bệnh nhân ký phiếu đồng thuận.', 'Đã xác nhận chẩn đoán ICD-10 và đồng thuận điều trị.'),
    (4, 1, NULL, 'RK8-B4', 'Tiểu phẫu nhổ răng khôn bằng máy Piezotome',   'TREATMENT',    4, 45, 2000000.00, 'TREATMENT',      25, 0, 1, 'ACTIVE', 'Sát khuẩn, gây tê vùng/tại chỗ, mở xương/chia cắt răng bằng sóng siêu âm Piezotome, lấy răng và khâu đóng vạt.', 'Đã hoàn tất thủ thuật, cầm máu tốt tại ổ răng.'),
    (5, 1, NULL, 'RK8-B5', 'Kê đơn thuốc kháng sinh, giảm đau & Dặn dò',   'CLINICAL',     5, 10, 0.00,       'PAYMENT',        10, 0, 0, 'ACTIVE', 'Lập đơn thuốc hậu phẫu và hướng dẫn chi tiết chế độ ăn uống, chườm lạnh.', 'Đã phát hành đơn thuốc và lời dặn hậu phẫu.'),
    (6, 1, NULL, 'RK8-B6', 'Tái khám kiểm tra ổ răng & Cắt chỉ sau 7 ngày','FOLLOW_UP',    6, 15, 0.00,       'EXAM',           10, 1, 0, 'ACTIVE', 'Gọi điện CSKH sau 24h và hẹn tái khám cắt chỉ, kiểm tra lành thương sau 7 ngày.', 'Ổ răng lành thương tốt, đã cắt chỉ.')
ON DUPLICATE KEY UPDATE
    step_name                  = VALUES(step_name),
    step_type                  = VALUES(step_type),
    estimated_duration_minutes = VALUES(estimated_duration_minutes),
    step_price                 = VALUES(step_price),
    status                     = VALUES(status);

INSERT INTO clinical_workflow_step_roles (step_role_id, step_id, role_id, is_primary) VALUES
    (1, 1, 2, 1),
    (2, 2, 2, 1),
    (3, 3, 2, 1),
    (4, 4, 2, 1),
    (5, 5, 2, 1),
    (6, 6, 3, 0),
    (7, 6, 2, 1)
ON DUPLICATE KEY UPDATE is_primary = VALUES(is_primary);

INSERT INTO clinical_workflow_step_paraclinical (step_paraclinical_id, step_id, service_id, is_required, result_required) VALUES
    (1, 2, 11, 1, 1),
    (2, 2, 12, 0, 1)
ON DUPLICATE KEY UPDATE is_required = VALUES(is_required), result_required = VALUES(result_required);

INSERT INTO clinical_workflow_transition_rules (rule_id, version_id, from_step_id, to_step_id, priority_order, is_default, condition_expression) VALUES
    (1, 1, 1, 2, 1, 1, '{"condition":"ALWAYS","description":"Chuyển sang chụp phim X-Quang sau khi khám lâm sàng"}'),
    (2, 1, 2, 3, 1, 1, '{"condition":"PARACLINICAL_COMPLETED","description":"Quay lại bác sĩ đọc kết quả sau khi có phim"}'),
    (3, 1, 3, 4, 1, 1, '{"condition":"CONSENT_APPROVED","description":"Tiến hành tiểu phẫu sau khi bệnh nhân đồng thuận"}'),
    (4, 1, 4, 5, 1, 1, '{"condition":"PROCEDURE_COMPLETED","description":"Kê đơn thuốc và dặn dò sau tiểu phẫu"}'),
    (5, 1, 5, 6, 1, 1, '{"condition":"DISPENSED","description":"Tạo lịch hẹn tái khám cắt chỉ và CSKH hậu phẫu"}')
ON DUPLICATE KEY UPDATE
    priority_order       = VALUES(priority_order),
    condition_expression = VALUES(condition_expression);
