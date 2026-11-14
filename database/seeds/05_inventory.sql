-- ====================================================================================================
-- CV-DCMS: 05_inventory.sql (NHÓM QUẢN LÝ KHO: DƯỢC PHẨM, NHÀ CUNG CẤP, VẬT TƯ, TỒN KHO, LÔ HÀNG, PHIẾU NHẬP/XUẤT)
-- Đối chiếu 100% với Schema cv_dcms_db_backup.sql (Bảng branch_inventory, material_batches, stock_receipts, stock_issues)
-- An toàn khi chạy nhiều lần (Idempotent - ON DUPLICATE KEY UPDATE)
-- ====================================================================================================

SET NAMES utf8mb4;

-- 1. DANH MỤC THUỐC NHA KHOA (medications)
INSERT INTO medications (medication_id, drug_code, drug_name, active_ingredient, unit, sale_price, usage_instruction, is_active) VALUES
    (1, 'TH-AUG-1G',   'Augmentin 1g (GSK)',                        'Amoxicillin 875mg + Acid Clavulanic 125mg', 'Viên', 25000.00, 'Uống sau ăn sáng và tối, mỗi lần 1 viên', 1),
    (2, 'TH-RODO-125', 'Rodogyl (Sanofi)',                          'Spiramycin 750.000 IU + Metronidazole 125mg','Viên',  6000.00, 'Uống sau ăn sáng và tối, mỗi lần 2 viên (Kháng sinh chuyên khoa RHM)', 1),
    (3, 'TH-DALACIN',  'Dalacin C 300mg (Pfizer)',                  'Clindamycin 300mg',                         'Viên', 12000.00, 'Uống sau ăn ngày 2 lần, mỗi lần 1 viên (Dùng cho bệnh nhân dị ứng Penicillin)', 1),
    (4, 'TH-EFFE-500', 'Efferalgan 500mg Sủi',                      'Paracetamol 500mg',                         'Viên',  5000.00, 'Hòa tan trong 200ml nước uống khi đau, cách nhau ít nhất 4-6 giờ', 1),
    (5, 'TH-CATA-50',  'Cataflam 50mg (Novartis)',                  'Diclofenac Potassium 50mg',                 'Viên',  8000.00, 'Uống sau ăn no khi đau nhức nhiều, ngày 2 lần mỗi lần 1 viên', 1),
    (6, 'TH-ALPHA-42', 'Alpha Choay (Sanofi)',                      'Alphachymotrypsin 4.2mg',                   'Viên',  4000.00, 'Ngậm dưới lưỡi ngày 4 viên chia 2 lần chống phù nề', 1),
    (7, 'TH-KIN-250',  'Nước súc miệng diệt khuẩn Kin Gingival 250ml','Chlorhexidine Digluconate 0.12%',         'Chai',115000.00, 'Ngậm súc miệng 15ml trong 30 giây sáng và tối, không súc lại bằng nước thường', 1)
ON DUPLICATE KEY UPDATE
    drug_name         = VALUES(drug_name),
    active_ingredient = VALUES(active_ingredient),
    sale_price        = VALUES(sale_price),
    usage_instruction = VALUES(usage_instruction),
    is_active         = VALUES(is_active);

-- 2. NHÀ CUNG CẤP VẬT TƯ & LABO (suppliers - Không có cột email)
INSERT INTO suppliers (supplier_id, supplier_code, supplier_name, tax_code, phone_number, address, is_active) VALUES
    (1, 'NCC-3M',        'Công ty TNHH Thiết bị Nha khoa 3M Việt Nam',        '0101234567', '0901112233', 'Tầng 12, Tòa nhà Keangnam, Quận Cầu Giấy, Hà Nội', 1),
    (2, 'NCC-SEPTODONT', 'Công ty Dược & Vật tư Nha khoa Septodont Việt Nam', '0102345678', '0902223344', '88 Nguyễn Chí Thanh, Quận Đống Đa, Hà Nội',         1),
    (3, 'NCC-DENTSPLY',  'Công ty Vật tư Nha khoa Dentsply Sirona',           '0103456789', '0903334455', '25 Lê Duẩn, Quận 1, TP.HCM',                        1),
    (4, 'NCC-LABO-VIET', 'Xưởng Chế tác Răng sứ Kỹ thuật số Labo Việt Đức',   '0104567890', '0904445566', 'Số 18 Hoàng Quốc Việt, Quận Cầu Giấy, Hà Nội',      1)
ON DUPLICATE KEY UPDATE
    supplier_name = VALUES(supplier_name),
    phone_number  = VALUES(phone_number),
    address       = VALUES(address),
    is_active     = VALUES(is_active);

-- 3. DANH MỤC VẬT TƯ NHA KHOA (materials)
INSERT INTO materials (material_id, material_code, material_name, category, unit, min_stock_threshold, medication_id, is_active) VALUES
    (1, 'VT-COMP-Z350',  'Composite đặc 3M Filtek Z350 XT (Màu A2)',  'Trám răng',  'Ống',  10.000, NULL, 1),
    (2, 'VT-COMP-A3',    'Composite lỏng 3M Filtek Supreme (Màu A3)', 'Trám răng',  'Ống',  10.000, NULL, 1),
    (3, 'VT-TE-SEPTO',   'Thuốc tê Septanest 1:100.000 (Hộp 50 ống)', 'Thuốc tê',   'Hộp',   5.000, NULL, 1),
    (4, 'VT-MC-KL3M',    'Bộ mắc cài kim loại tự buộc 3M Unitek',     'Chỉnh nha',  'Bộ',    5.000, NULL, 1),
    (5, 'VT-DC-NITI',    'Dây cung NiTi chỉnh nha 016',               'Chỉnh nha',  'Gói',  15.000, NULL, 1),
    (6, 'VT-TH-GANG',    'Găng tay y tế Nitrile không bột (Hộp 100c)','Tiêu hao',   'Hộp',  20.000, NULL, 1),
    (7, 'VT-TH-KIM30G',  'Kim tiêm nha khoa siêu nhỏ 30G (Hộp 100c)', 'Tiêu hao',   'Hộp',  15.000, NULL, 1),
    (8, 'VT-NN-GUTTA',   'Côn chính Gutta Percha nội nha F1-F3',      'Nội nha',    'Hộp',  10.000, NULL, 1),
    (9, 'VT-PH-ALGIN',   'Bột lấy dấu răng Alginate Cavex',           'Phục hình',  'Gói',  12.000, NULL, 1),
    (10,'VT-THUOC-AUG1G','Thuốc kháng sinh Augmentin 1g',             'Dược phẩm',  'Viên', 50.000, 1,    1),
    (11,'VT-THUOC-KIN',  'Nước súc miệng Kin Gingival 250ml',         'Dược phẩm',  'Chai', 10.000, 7,    1)
ON DUPLICATE KEY UPDATE
    material_name       = VALUES(material_name),
    category            = VALUES(category),
    unit                = VALUES(unit),
    min_stock_threshold = VALUES(min_stock_threshold),
    is_active           = VALUES(is_active);

UPDATE medications SET material_id = 10 WHERE medication_id = 1;
UPDATE medications SET material_id = 11 WHERE medication_id = 7;

-- 4. TỒN KHO CHI NHÁNH (branch_inventory) - Khớp toán học với lô hàng & lịch sử nhập/xuất
INSERT INTO branch_inventory (inventory_id, branch_id, material_id, current_stock, last_updated) VALUES
    (1,  1, 1,  25.000, NOW()),
    (2,  1, 2,  18.000, NOW()),
    (3,  1, 3,  12.000, NOW()),
    (4,  1, 4,   8.000, NOW()),
    (5,  1, 5,  30.000, NOW()),
    (6,  1, 6,  45.000, NOW()),
    (7,  1, 7,  35.000, NOW()),
    (8,  1, 8,  20.000, NOW()),
    (9,  1, 9,  24.000, NOW()),
    (10, 1, 10, 200.000,NOW()),
    (11, 1, 11,  30.000,NOW()),
    (12, 2, 1,  15.000, NOW()),
    (13, 2, 3,  10.000, NOW()),
    (14, 2, 6,  30.000, NOW())
ON DUPLICATE KEY UPDATE
    current_stock = VALUES(current_stock),
    last_updated  = NOW();

-- 5. LÔ HÀNG VẬT TƯ (material_batches)
INSERT INTO material_batches (batch_id, branch_id, material_id, supplier_id, batch_number, expiry_date, import_price, quantity_remaining, status) VALUES
    (1, 1, 1,  1, 'LOT-Z350-2026A', '2027-12-31', 450000.00, 25.000,  'ACTIVE'),
    (2, 1, 2,  1, 'LOT-A3-2026A',   '2027-10-31', 420000.00, 18.000,  'ACTIVE'),
    (3, 1, 3,  2, 'LOT-SEP-2026B',  '2027-06-30', 550000.00, 12.000,  'ACTIVE'),
    (4, 1, 4,  1, 'LOT-3M-MC2026',  '2028-12-31', 3200000.00, 8.000,  'ACTIVE'),
    (5, 1, 6,  3, 'LOT-GLOVE-26',   '2028-05-31', 110000.00, 45.000,  'ACTIVE'),
    (6, 1, 10, 2, 'LOT-AUG-2026',   '2027-08-31',  18000.00, 200.000, 'ACTIVE'),
    (7, 2, 1,  1, 'LOT-Z350-CN2',   '2027-12-31', 450000.00, 15.000,  'ACTIVE'),
    (8, 2, 3,  2, 'LOT-SEP-CN2',    '2027-06-30', 550000.00, 10.000,  'ACTIVE')
ON DUPLICATE KEY UPDATE
    quantity_remaining = VALUES(quantity_remaining),
    import_price       = VALUES(import_price),
    status             = VALUES(status);

-- 6. PHIẾU NHẬP KHO DEMO (stock_receipts & stock_receipt_items)
-- Toán học: 25 * 450.000 (11.250.000) + 10 * 550.000 (5.500.000) = 16.750.000đ
INSERT INTO stock_receipts (receipt_id, receipt_code, branch_id, supplier_id, receiver_id, receipt_date, total_amount, supplier_invoice_no, notes, signed_by_user_id, signed_by_name, signed_at) VALUES
    (501, 'PNK-SEED-001', 1, 1, 5, CURDATE(), 16750000.00, 'VAT-3M-0089', 'Nhập vật tư Composite 3M và thuốc tê đầu kỳ cho Cơ sở 1', 5, 'Đỗ Minh Quân', NOW())
ON DUPLICATE KEY UPDATE
    total_amount = VALUES(total_amount),
    notes        = VALUES(notes);

INSERT INTO stock_receipt_items (receipt_item_id, receipt_id, material_id, batch_number, expiry_date, quantity, unit_price) VALUES
    (5011, 501, 1, 'LOT-Z350-2026A', '2027-12-31', 25.000, 450000.00),
    (5012, 501, 3, 'LOT-SEP-2026B',  '2027-06-30', 10.000, 550000.00)
ON DUPLICATE KEY UPDATE
    quantity   = VALUES(quantity),
    unit_price = VALUES(unit_price);

-- 7. PHIẾU XUẤT KHO DEMO (stock_issues & stock_issue_items)
INSERT INTO stock_issues (issue_id, issue_code, branch_id, issuer_id, issue_type, issue_date, issue_reason, destination_branch_id, notes, signed_by_user_id, signed_by_name, signed_at) VALUES
    (501, 'PXK-SEED-001', 1, 5, 'INTERNAL_USE', CURDATE(), 'Xuất vật tư tiêu hao cho Phòng Tiểu phẫu P102', NULL, 'Xuất ca sáng', 5, 'Đỗ Minh Quân', NOW())
ON DUPLICATE KEY UPDATE
    issue_reason = VALUES(issue_reason);

INSERT INTO stock_issue_items (issue_item_id, issue_id, material_id, batch_id, quantity, notes) VALUES
    (5011, 501, 3, 3, 1.000, 'Xuất 1 hộp thuốc tê Septanest cho P102'),
    (5012, 501, 6, 5, 2.000, 'Xuất 2 hộp găng tay y tế Nitrile')
ON DUPLICATE KEY UPDATE
    quantity = VALUES(quantity);
