-- Sửa dữ liệu demo FT07/FT08 đã từng được nạp bằng sai bảng mã.
-- Chỉ cập nhật các mã demo xác định; không đụng dữ liệu vận hành.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET time_zone = '+07:00';

UPDATE patient_profiles
SET full_name = CASE patient_code
    WHEN 'BNFT0701' THEN 'An Lịch Hẹn'
    WHEN 'BNFT0702' THEN 'Bình Chờ Xác Nhận'
    WHEN 'BNFT0703' THEN 'Chi Trùng Tên'
    WHEN 'BNFT0704' THEN 'Chi Trùng Tên'
    WHEN 'BNFT0705' THEN 'Dũng Lịch Hủy'
    WHEN 'BNFT0706' THEN 'Em Lỡ Hẹn'
    WHEN 'BNFT0707' THEN 'Giang Hẹn Ngày Mai'
    WHEN 'BNFT0708' THEN 'Hải Đã Có Lượt'
    WHEN 'BNFT0709' THEN 'Khánh Hồ Sơ Khóa'
    WHEN 'BNFT0710' THEN 'Lan Đã Check-in'
    WHEN 'BNFT0711' THEN 'Minh Khác Chi Nhánh'
    WHEN 'BNFT0801' THEN 'Thử phân công mới'
    WHEN 'BNFT0802' THEN 'Thử số thường'
    WHEN 'BNFT0803' THEN 'Thử số cấp cứu'
    WHEN 'BNFT0804' THEN 'Thử số ưu tiên'
    ELSE full_name
END,
address = CASE
    WHEN patient_code LIKE 'BNFT07%' THEN 'Dữ liệu thử FT07'
    WHEN patient_code LIKE 'BNFT08%' THEN 'Dữ liệu thử FT08/FT09'
    ELSE address
END
WHERE patient_code IN ('BNFT0701','BNFT0702','BNFT0703','BNFT0704','BNFT0705',
                       'BNFT0706','BNFT0707','BNFT0708','BNFT0709','BNFT0710',
                       'BNFT0711','BNFT0801','BNFT0802','BNFT0803','BNFT0804');

UPDATE visits
SET initial_symptoms = CASE visit_code
    WHEN 'VISFT0708' THEN 'Đang chờ khám từ trước'
    WHEN 'VISFT0710' THEN 'Khám tổng quát'
    WHEN 'VISFT0801' THEN 'Đau răng cần phân bác sĩ'
    WHEN 'VISFT0802' THEN 'Đã phân công và cấp số thường'
    WHEN 'VISFT0803' THEN 'Cấp cứu chảy máu răng kéo dài'
    WHEN 'VISFT0804' THEN 'Cần ưu tiên vì hạn chế vận động'
    ELSE initial_symptoms
END
WHERE visit_code IN ('VISFT0708','VISFT0710','VISFT0801','VISFT0802','VISFT0803','VISFT0804');

UPDATE staff_profiles
SET full_name = CASE staff_code
    WHEN 'BSFT0801' THEN 'Bác sĩ FT08 đang làm việc'
    WHEN 'BSFT0802' THEN 'Bác sĩ FT08 ngoài ca'
    ELSE full_name
END
WHERE staff_code IN ('BSFT0801','BSFT0802');

SELECT patient_code, full_name, address
FROM patient_profiles
WHERE patient_code IN ('BNFT0710','BNFT0801','BNFT0802','BNFT0803','BNFT0804')
ORDER BY patient_code;
