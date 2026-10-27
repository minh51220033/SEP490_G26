-- Dữ liệu thử nghiệm tùy chọn cho màn hình hồ sơ lễ tân.
-- Chạy sau database/migrations/V1__allow_unclaimed_patient_profiles.sql.
-- Cần có chi nhánh CN01 (theo src/main/resources/data.sql).
INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address,
     id_card_number, health_insurance_number,
     preferred_branch_id, is_default, medical_history_completed, deposit_balance, status)
SELECT NULL, 'BNPH01001', 'Nguyễn Minh Khôi', '2003-04-12', 'Nam', '0901112233',
       'Quận 1, TP.HCM', '079203001234', 'DN4010123456789',
       b.branch_id, 0, 0, 0, 'ACTIVE'
FROM branches b
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM patient_profiles p WHERE p.patient_code = 'BNPH01001');

INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address,
     guardian_name, guardian_phone, guardian_relationship,
     preferred_branch_id, is_default, medical_history_completed, deposit_balance, status)
SELECT NULL, 'BNPH01002', 'Nguyễn Minh An', '2011-09-21', 'Nữ', '0901112233',
       'Quận 1, TP.HCM', 'Nguyễn Minh Khôi', '0901112233', 'Cha',
       b.branch_id, 0, 0, 0, 'ACTIVE'
FROM branches b
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM patient_profiles p WHERE p.patient_code = 'BNPH01002');

-- Hai hồ sơ trùng tên nhưng khác ngày sinh để kiểm tra popup đối chiếu FT04.
INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address,
     id_card_number, health_insurance_number,
     preferred_branch_id, is_default, medical_history_completed, deposit_balance, status)
SELECT NULL, 'BNPH01003', 'Trần Thanh Hà', '1988-02-14', 'Nữ', '0912345678',
       'Quận 3, TP.HCM', '079188002345', 'DN4010234567890',
       b.branch_id, 0, 0, 0, 'ACTIVE'
FROM branches b
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM patient_profiles p WHERE p.patient_code = 'BNPH01003');

INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address,
     id_card_number, preferred_branch_id, is_default,
     medical_history_completed, deposit_balance, status)
SELECT NULL, 'BNPH01004', 'Trần Thanh Hà', '1992-11-06', 'Nữ', '0934567890',
       'Quận 5, TP.HCM', '079192003456', b.branch_id, 0, 0, 0, 'ACTIVE'
FROM branches b
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM patient_profiles p WHERE p.patient_code = 'BNPH01004');

-- Hồ sơ trẻ em dùng chung số điện thoại với người giám hộ.
INSERT INTO patient_profiles
    (user_id, patient_code, full_name, dob, gender, phone_number, address,
     health_insurance_number, guardian_name, guardian_phone, guardian_relationship,
     preferred_branch_id, is_default, medical_history_completed, deposit_balance, status)
SELECT NULL, 'BNPH01005', 'Lê Gia Bảo', '2018-08-09', 'Nam', '0988123456',
       'Thủ Đức, TP.HCM', 'TE4010345678901', 'Lê Hoàng Nam', '0988123456', 'Cha',
       b.branch_id, 0, 0, 0, 'ACTIVE'
FROM branches b
WHERE b.branch_code = 'CN01'
  AND NOT EXISTS (SELECT 1 FROM patient_profiles p WHERE p.patient_code = 'BNPH01005');
