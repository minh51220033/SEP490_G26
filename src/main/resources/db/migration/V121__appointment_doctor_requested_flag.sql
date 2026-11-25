-- ====================================================================================================
-- FLYWAY MIGRATION V121: PHÂN BIỆT BÁC SĨ ĐẶT RIÊNG (BOOKED/REQUESTED) VS BÁC SĨ TỰ GÁN
-- ====================================================================================================

ALTER TABLE appointments
    ADD COLUMN is_doctor_requested TINYINT(1) NOT NULL DEFAULT 0 AFTER doctor_id;
