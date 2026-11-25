-- ====================================================================================================
-- FLYWAY MIGRATION V124: BO SUNG DANH MUC DICH VU THUC TE PHONG KHAM RANG HAM MAT CUONG VIET
-- Nguon: Bang gia niem yet tai Phong kham chuyen khoa RHM Cuong Viet (53 dich vu thuoc 6 nhom).
-- Idempotent 100%: Su dung INSERT IGNORE / ON DUPLICATE KEY UPDATE, an toan khi chay lai nhieu lan.
-- Khong xoa cac dich vu mau cu de bao toan khoa ngoai (workflows, combo, appointments, invoices).
-- ====================================================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1. DAM BAO CAC NHOM DANH MUC DICH VU CHUYEN KHOA
INSERT IGNORE INTO service_categories (category_code, category_name, category_type, display_order, is_active) VALUES
('CAT_EXAM',   'Khám & Tư vấn',               'CONSULTATION', 1, 1),
('CAT_ENDO',   'Điều trị tủy & Trám răng',    'PROCEDURE',    3, 1),
('CAT_SURG',   'Nhổ răng & Tiểu phẫu',        'PROCEDURE',    4, 1),
('CAT_PROSTH', 'Phục hình răng sứ & Labo',    'PROCEDURE',    5, 1),
('CAT_ORTHO',  'Chỉnh nha - Niềng răng',      'PROCEDURE',    6, 1),
('CAT_AESTH',  'Tẩy trắng & Nha khoa thẩm mỹ','PROCEDURE',    8, 1);

INSERT INTO service_categories (category_code, category_name, category_type, display_order, is_active) VALUES
('CAT_PEDO',    'Răng trẻ em',  'PROCEDURE', 10, 1),
('CAT_DENTURE', 'Hàm tháo lắp', 'PROCEDURE', 11, 1)
ON DUPLICATE KEY UPDATE
    category_name = VALUES(category_name),
    category_type = VALUES(category_type),
    display_order = VALUES(display_order),
    is_active     = VALUES(is_active);

-- 2. BO SUNG 53 DICH VU CHINH THUC THEO BANG GIA PHONG KHAM CUONG VIET
INSERT INTO services (
    category_id, service_code, service_name, image_url, service_type, unit,
    base_price, surcharge_amount, estimated_duration_minutes,
    preparation_duration_minutes, procedure_duration_minutes, turnover_duration_minutes,
    requires_chair, is_room_exclusive, doctor_required_in_prep, doctor_required_in_turnover,
    is_default_consultation, requires_tooth, is_paraclinical, is_bookable_online,
    warranty_months, description, status, created_at
)
SELECT sc.category_id, s.service_code, s.service_name, NULL, 'PROCEDURE', s.unit,
       s.base_price, 0.00, s.dur,
       0, s.dur, 0,
       1, 0, 0, 0,
       0, s.req_tooth, 0, 1,
       s.warranty, s.descr, 'ACTIVE', NOW()
FROM (
    -- =================================================================================
    -- I. DICH VU TONG QUAT / DIEU TRI CO BAN (ANH 1 - PHAN 1: 7 DICH VU)
    -- =================================================================================
    SELECT 'CAT_EXAM' ccode, 'CV-LAYCAORANG' service_code, 'Lấy cao răng' service_name,
           'Lần' unit, 100000.00 base_price, 30 dur, 0 req_tooth, NULL warranty,
           'Lấy cao răng làm sạch mảng bám theo bảng giá niêm yết Phòng khám RHM Cường Việt.' descr
    UNION ALL SELECT 'CAT_ENDO', 'CV-TUY-1CHAN', 'Điều trị tủy răng 01 chân',
           'Lần', 400000.00, 45, 1, NULL,
           'Điều trị nội nha làm sạch và trám bít ống tủy cho răng 01 chân.'
    UNION ALL SELECT 'CAT_ENDO', 'CV-TUY-NHIEUCHAN', 'Điều trị tủy răng nhiều chân',
           'Lần', 500000.00, 60, 1, NULL,
           'Điều trị nội nha làm sạch và trám bít hệ thống ống tủy cho răng nhiều chân.'
    UNION ALL SELECT 'CAT_ENDO', 'CV-TUY-LAI', 'Điều trị tủy lại',
           'Lần', 700000.00, 60, 1, NULL,
           'Tháo bỏ vật liệu cũ, làm sạch lại hệ thống ống tủy và trám bít phục hồi.'
    UNION ALL SELECT 'CAT_ENDO', 'CV-HANRANGSAU', 'Hàn răng sâu',
           'Răng', 100000.00, 30, 1, NULL,
           'Giá niêm yết: 100.000 - 200.000 đ/Răng tùy kích thước và mức độ sâu.'
    UNION ALL SELECT 'CAT_AESTH', 'CV-GANDA', 'Gắn đá',
           'Răng', 300000.00, 20, 1, NULL,
           'Giá niêm yết: 300.000 - 500.000 đ/Răng tùy loại đá thẩm mỹ.'
    UNION ALL SELECT 'CAT_AESTH', 'CV-TAYTRANG-PLASMA', 'Tẩy trắng răng có sử dụng đèn Plasma',
           'Hàm', 1500000.00, 60, 0, NULL,
           'Tẩy trắng răng hoạt hóa bằng đèn Plasma tại phòng khám.'

    -- =================================================================================
    -- II. RANG TRE EM (ANH 1 - PHAN 2: 7 DICH VU)
    -- =================================================================================
    UNION ALL SELECT 'CAT_PEDO', 'CV-TE-TUY-SUA', 'Điều trị tủy răng sữa',
           'Răng', 300000.00, 30, 1, NULL,
           'Điều trị tủy bảo tồn răng sữa cho trẻ em.'
    UNION ALL SELECT 'CAT_PEDO', 'CV-TE-NHOSUA', 'Nhổ răng sữa',
           'Răng', 0.00, 15, 1, NULL,
           'Nhổ răng sữa đến tuổi thay cho trẻ em (miễn phí theo bảng giá phòng khám).'
    UNION ALL SELECT 'CAT_PEDO', 'CV-TE-NHOCHANSUA', 'Nhổ chân răng sữa',
           'Răng', 0.00, 15, 1, NULL,
           'Nhổ chân răng sữa còn sót cho trẻ em (miễn phí theo bảng giá phòng khám).'
    UNION ALL SELECT 'CAT_PEDO', 'CV-TE-HANRANG', 'Hàn răng sâu trẻ em (răng sữa)',
           'Răng', 50000.00, 20, 1, NULL,
           'Giá niêm yết: 50.000 - 100.000 đ/Răng tùy mức độ sâu răng sữa.'
    UNION ALL SELECT 'CAT_PEDO', 'CV-TE-CHICHAPXE', 'Chích Apxe lợi trẻ em',
           'Răng', 0.00, 15, 1, NULL,
           'Xử lý chích rạch dẫn lưu áp-xe lợi răng sữa trẻ em (miễn phí theo bảng giá phòng khám).'
    UNION ALL SELECT 'CAT_PEDO', 'CV-TE-GELFLUOR', 'Dự phòng sâu răng bằng Gel Fluor',
           'Hàm', 150000.00, 20, 0, NULL,
           'Bôi Gel Fluor tái khoáng men răng phòng ngừa sâu răng cho trẻ.'
    UNION ALL SELECT 'CAT_PEDO', 'CV-TE-CHUPKL', 'Chụp kim loại bảo tồn răng sữa',
           'Răng', 400000.00, 30, 1, NULL,
           'Phục hồi chụp kim loại chế tác sẵn bảo tồn thân răng sữa bị vỡ lớn.'

    -- =================================================================================
    -- III. HAM THAO LAP (ANH 1 - PHAN 3: 6 DICH VU)
    -- =================================================================================
    UNION ALL SELECT 'CAT_DENTURE', 'CV-HL-BPCUNG', 'Hàm giả tháo lắp bán phần nhựa cứng',
           'Hàm', 2500000.00, 45, 0, NULL,
           'Phục hình hàm giả tháo lắp bán phần nền nhựa cứng.'
    UNION ALL SELECT 'CAT_DENTURE', 'CV-HL-TPCUNG', 'Hàm giả tháo lắp toàn phần nhựa cứng',
           'Hàm', 5000000.00, 60, 0, NULL,
           'Phục hình hàm giả tháo lắp toàn hàm nền nhựa cứng.'
    UNION ALL SELECT 'CAT_DENTURE', 'CV-HL-BPDEO', 'Hàm giả tháo lắp bán phần nền nhựa dẻo',
           'Hàm', 2500000.00, 45, 0, NULL,
           'Phục hình hàm giả tháo lắp bán phần nền nhựa dẻo thẩm mỹ.'
    UNION ALL SELECT 'CAT_DENTURE', 'CV-HL-TPDEO', 'Hàm giả tháo lắp toàn phần nền nhựa dẻo',
           'Hàm', 5000000.00, 60, 0, NULL,
           'Phục hình hàm giả tháo lắp toàn hàm nền nhựa dẻo.'
    UNION ALL SELECT 'CAT_DENTURE', 'CV-HL-THEMRANG', 'Thêm răng cho hàm giả tháo lắp',
           'Răng', 500000.00, 30, 1, NULL,
           'Bổ sung thêm đơn vị răng vào nền hàm giả tháo lắp.'
    UNION ALL SELECT 'CAT_DENTURE', 'CV-HL-THEMMOC', 'Thêm móc cho hàm giả tháo lắp',
           'Răng', 500000.00, 30, 1, NULL,
           'Bổ sung thêm móc giữ vào hàm giả tháo lắp.'

    -- =================================================================================
    -- IV. PHAU THUAT NHO RANG (ANH 1 - PHAN 4: 8 DICH VU)
    -- =================================================================================
    UNION ALL SELECT 'CAT_SURG', 'CV-PT-LACCHO', 'Phẫu thuật nhổ răng lạc chỗ',
           'Răng', 500000.00, 45, 1, NULL,
           'Phẫu thuật nhổ răng mọc lạc chỗ ra khỏi cung hàm.'
    UNION ALL SELECT 'CAT_SURG', 'CV-PT-RANGNGAM', 'Phẫu thuật nhổ răng ngầm',
           'Răng', 500000.00, 45, 1, NULL,
           'Phẫu thuật bộc lộ và nhổ bỏ răng mọc ngầm trong xương.'
    UNION ALL SELECT 'CAT_SURG', 'CV-PT-KHONTREN', 'Phẫu thuật nhổ răng khôn hàm trên',
           'Răng', 1000000.00, 45, 1, NULL,
           'Tiểu phẫu nhổ răng số 8 (răng khôn) hàm trên.'
    UNION ALL SELECT 'CAT_SURG', 'CV-PT-KHONDUOI', 'Phẫu thuật nhổ răng khôn hàm dưới',
           'Răng', 2000000.00, 60, 1, NULL,
           'Tiểu phẫu nhổ răng số 8 (răng khôn) hàm dưới.'
    UNION ALL SELECT 'CAT_SURG', 'CV-PT-NHOVINHVIEN', 'Nhổ răng vĩnh viễn',
           'Răng', 300000.00, 30, 1, NULL,
           'Giá niêm yết: 300.000 - 500.000 đ/Răng tùy độ khó chân răng.'
    UNION ALL SELECT 'CAT_SURG', 'CV-PT-VETTHUONGPM', 'Phẫu thuật điều trị vết thương phần mềm vùng hàm mặt không thiếu tổ chức',
           'Lần', 500000.00, 45, 0, NULL,
           'Xử lý khâu phục hồi vết thương phần mềm vùng hàm mặt không thiếu tổ chức.'
    UNION ALL SELECT 'CAT_SURG', 'CV-PT-KHOPTDH', 'Nắn sai khớp thái dương hàm',
           'Lần', 500000.00, 30, 0, NULL,
           'Thủ thuật nắn chỉnh đưa khớp thái dương hàm về đúng vị trí giải phẫu.'
    UNION ALL SELECT 'CAT_SURG', 'CV-PT-CATLOICHUM', 'Cắt lợi chùm',
           'Lần', 300000.00, 30, 1, NULL,
           'Tiểu phẫu cắt bỏ phần lợi trùm gây viêm quanh thân răng.'

    -- =================================================================================
    -- V. NAN CHINH RANG & CHINH HINH HAM MAT (ANH 2 - PHAN 1: 3 DICH VU)
    -- =================================================================================
    UNION ALL SELECT 'CAT_ORTHO', 'CV-NC-THEP2HAM', 'Nắn chỉnh răng bằng mắc cài thép 02 hàm',
           'Hàm', 20000000.00, 60, 0, NULL,
           'Giá niêm yết: 20.000.000 - 30.000.000 đ / liệu trình nắn chỉnh mắc cài thép 02 hàm.'
    UNION ALL SELECT 'CAT_ORTHO', 'CV-NC-SU2HAM', 'Nắn chỉnh răng bằng mắc cài sứ 02 hàm',
           'Hàm', 30000000.00, 60, 0, NULL,
           'Giá niêm yết: 30.000.000 - 40.000.000 đ / liệu trình nắn chỉnh mắc cài sứ 02 hàm.'
    UNION ALL SELECT 'CAT_ORTHO', 'CV-NC-DUYTRI1HAM', 'Duy trì kết quả điều trị (nắn chỉnh cố định) 01 hàm',
           'Hàm', 1000000.00, 30, 0, NULL,
           'Hàm duy trì giữ ổn định kết quả sau tháo mắc cài chỉnh nha cố định (01 hàm).'

    -- =================================================================================
    -- VI. PHUC HINH CO DINH (ANH 2 - PHAN 2: 22 DICH VU RANG SU & KIM LOAI)
    -- =================================================================================
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-ORO-BLEACH', 'Orodent-Bleach Zirconia',
           'Răng', 14000000.00, 60, 1, 228,
           'Phục hình cố định Orodent-Bleach Zirconia · Xuất xứ: ITALY · Bảo hành: 19 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-ORO-BLEACH-FULL', 'Orodent-Bleach Zirconia full',
           'Răng', 15000000.00, 60, 1, 228,
           'Phục hình cố định Orodent-Bleach Zirconia full · Xuất xứ: ITALY · Bảo hành: 19 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-ORO-WMATT', 'Orodent-White Matt Zirconia',
           'Răng', 12000000.00, 60, 1, 228,
           'Phục hình cố định Orodent-White Matt Zirconia · Xuất xứ: ITALY · Bảo hành: 19 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-ORO-WMATT-FULL', 'Orodent-White Matt Zirconia full',
           'Răng', 13000000.00, 60, 1, 228,
           'Phục hình cố định Orodent-White Matt Zirconia full · Xuất xứ: ITALY · Bảo hành: 19 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-ORO-HT', 'Orodent-High Translucent Zirconia',
           'Răng', 10000000.00, 60, 1, 228,
           'Phục hình cố định Orodent-High Translucent Zirconia · Xuất xứ: ITALY · Bảo hành: 19 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-ORO-HT-FULL', 'Orodent-High Translucent Zirconia full',
           'Răng', 11000000.00, 60, 1, 228,
           'Phục hình cố định Orodent-High Translucent Zirconia full · Xuất xứ: ITALY · Bảo hành: 19 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-ORO-GOLD', 'Orodent-Gold Zirconia',
           'Răng', 10000000.00, 60, 1, 228,
           'Phục hình cố định Orodent-Gold Zirconia · Xuất xứ: ITALY · Bảo hành: 19 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-ORO-GOLD-FULL', 'Orodent-Gold Zirconia full',
           'Răng', 11000000.00, 60, 1, 228,
           'Phục hình cố định Orodent-Gold Zirconia full · Xuất xứ: ITALY · Bảo hành: 19 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-NACERA', 'Nacera',
           'Răng', 7000000.00, 60, 1, 120,
           'Phục hình răng toàn sứ Nacera · Xuất xứ: ĐỨC · Bảo hành: 10 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-NACERA-Q3', 'Nacera Q3',
           'Răng', 8000000.00, 60, 1, 180,
           'Phục hình răng toàn sứ Nacera Q3 · Xuất xứ: ĐỨC · Bảo hành: 15 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-LAVA-3M', 'Lava 3M',
           'Răng', 7000000.00, 60, 1, 180,
           'Phục hình răng toàn sứ Lava 3M · Xuất xứ: MỸ · Bảo hành: 15 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-WHITEPEAK', 'Whitepeak',
           'Răng', 0.00, 60, 1, 120,
           'Phục hình răng toàn sứ Whitepeak · Xuất xứ: ĐỨC · Bảo hành: 10 năm (vui lòng cập nhật lại đơn giá niêm yết).'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-ZIRCONIA', 'Zirconia',
           'Răng', 2500000.00, 60, 1, 120,
           'Phục hình răng toàn sứ Zirconia · Xuất xứ: ĐỨC · Bảo hành: 10 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-ZIRCONIA-FULL', 'Zirconia full',
           'Răng', 3000000.00, 60, 1, 120,
           'Phục hình răng toàn sứ Zirconia full · Xuất xứ: ĐỨC · Bảo hành: 10 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-KATANA', 'Katana',
           'Răng', 2500000.00, 60, 1, 120,
           'Phục hình răng toàn sứ Katana · Xuất xứ: NHẬT · Bảo hành: 10 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-KATANA-FULL', 'Katana full',
           'Răng', 3000000.00, 60, 1, 120,
           'Phục hình răng toàn sứ Katana full · Xuất xứ: NHẬT · Bảo hành: 10 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-HT-SMILE', 'HT Smile',
           'Răng', 5000000.00, 60, 1, 120,
           'Phục hình răng toàn sứ HT Smile · Xuất xứ: ĐỨC · Bảo hành: 10 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-CERCON', 'Cercon',
           'Răng', 5000000.00, 60, 1, 84,
           'Phục hình răng toàn sứ Cercon · Xuất xứ: ĐỨC · Bảo hành: 7 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-SU-CRCO', 'Sứ kim loại chrome colbalt',
           'Răng', 2000000.00, 45, 1, 60,
           'Phục hình răng sứ kim loại Chrome Cobalt · Xuất xứ: ĐỨC · Bảo hành: 5 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-SU-TITAN', 'Sứ kim loại Titan',
           'Răng', 1500000.00, 45, 1, 60,
           'Phục hình răng sứ kim loại Titan · Xuất xứ: ĐỨC · Bảo hành: 5 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-SU-THUONG', 'Sứ kim loại thường',
           'Răng', 800000.00, 45, 1, 36,
           'Phục hình răng sứ kim loại thường · Xuất xứ: MỸ · Bảo hành: 3 năm.'
    UNION ALL SELECT 'CAT_PROSTH', 'CV-PH-KL-TOANBO', 'Kim loại toàn bộ',
           'Răng', 500000.00, 45, 1, 60,
           'Phục hình mão kim loại toàn bộ · Bảo hành: 5 năm.'
) s
JOIN service_categories sc ON sc.category_code = s.ccode
ON DUPLICATE KEY UPDATE
    category_id                = VALUES(category_id),
    service_name               = VALUES(service_name),
    service_type               = VALUES(service_type),
    unit                       = VALUES(unit),
    base_price                 = VALUES(base_price),
    estimated_duration_minutes = VALUES(estimated_duration_minutes),
    preparation_duration_minutes = VALUES(preparation_duration_minutes),
    procedure_duration_minutes = VALUES(procedure_duration_minutes),
    turnover_duration_minutes  = VALUES(turnover_duration_minutes),
    requires_chair             = VALUES(requires_chair),
    is_room_exclusive          = VALUES(is_room_exclusive),
    doctor_required_in_prep    = VALUES(doctor_required_in_prep),
    doctor_required_in_turnover = VALUES(doctor_required_in_turnover),
    is_default_consultation    = VALUES(is_default_consultation),
    requires_tooth             = VALUES(requires_tooth),
    is_bookable_online         = VALUES(is_bookable_online),
    warranty_months            = VALUES(warranty_months),
    description                = VALUES(description),
    status                     = VALUES(status);
