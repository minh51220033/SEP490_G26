-- Chạy một lần trên cv_dcms_db trước khi dùng màn hình hồ sơ bệnh nhân của lễ tân.
-- Giữ nguyên mọi hồ sơ và khóa ngoại hiện có. Hồ sơ chưa có tài khoản có user_id = NULL.
ALTER TABLE patient_profiles MODIFY COLUMN user_id BIGINT NULL;
