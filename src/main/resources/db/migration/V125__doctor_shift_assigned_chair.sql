-- Gán ghế khám cố định (tùy chọn) cho phân công trực phòng của bác sĩ theo ca
ALTER TABLE doctor_shift_assignments
    ADD COLUMN chair_id BIGINT NULL AFTER room_id,
    ADD CONSTRAINT fk_dsa_chair FOREIGN KEY (chair_id) REFERENCES dental_chairs (chair_id);
