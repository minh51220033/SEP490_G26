-- Migration V18: Ràng buộc duy nhất chống race condition trùng lặp SĐT và CCCD trên patient_profiles
UPDATE patient_profiles SET id_card_number = NULL WHERE id_card_number IS NOT NULL AND TRIM(id_card_number) = '';
UPDATE patient_profiles SET phone_number = NULL WHERE phone_number IS NOT NULL AND TRIM(phone_number) = '';

ALTER TABLE patient_profiles
    ADD CONSTRAINT uk_patient_phone UNIQUE (phone_number),
    ADD CONSTRAINT uk_patient_id_card UNIQUE (id_card_number);

