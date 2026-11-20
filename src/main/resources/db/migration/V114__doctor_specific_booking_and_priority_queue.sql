-- ====================================================================================================
-- FLYWAY MIGRATION V114: DOCTOR-SPECIFIC BOOKING & 4-TIER RECEPTION PRIORITY QUEUE
-- Mục đích:
--   1. Bổ sung expected_end_time cho bảng appointments phục vụ tính Dynamic Available Slots & overlap [start, end).
--   2. Bổ sung is_emergency, is_booked_and_on_time, actual_start_time, actual_end_time cho bảng visits
--      phục vụ hàng đợi 4 cấp ưu tiên, Grace Period 15 phút và giải phóng slot khi kết thúc khám lâm sàng.
--   3. Thêm index tối ưu hóa truy vấn đặt lịch theo bác sĩ và hàng đợi bác sĩ (tránh trùng index đã có).
--   4. Idempotent 100%, an toàn với dữ liệu cũ (không DROP TABLE / DROP COLUMN, không xóa dữ liệu).
--
-- Hướng dẫn Rollback có kiểm soát (chỉ thực hiện khi cần khôi phục):
--   DROP INDEX idx_appt_doctor_date_status_time ON appointments;
--   DROP INDEX idx_appt_noshow_scan ON appointments;
--   DROP INDEX idx_visit_doctor_queue ON visits;
--   DROP INDEX idx_visit_appointment ON visits;
--   ALTER TABLE appointments DROP COLUMN expected_end_time;
--   ALTER TABLE visits DROP COLUMN is_emergency, DROP COLUMN is_booked_and_on_time,
--                      DROP COLUMN actual_start_time, DROP COLUMN actual_end_time;
-- ====================================================================================================

DELIMITER $$

CREATE PROCEDURE sp_upgrade_v114_doctor_booking_and_queue()
BEGIN
    -- 1. Kiểm tra an toàn cột estimated_duration_minutes trên bảng services (đã tạo từ V104)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'services'
          AND column_name = 'estimated_duration_minutes'
    ) THEN
        ALTER TABLE services
            ADD COLUMN estimated_duration_minutes INT NOT NULL DEFAULT 30;
    END IF;

    -- 2. Bảng appointments: bổ sung expected_end_time nếu chưa có
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'appointments'
          AND column_name = 'expected_end_time'
    ) THEN
        ALTER TABLE appointments
            ADD COLUMN expected_end_time TIME NULL AFTER expected_time;
    END IF;

    -- Backfill an toàn expected_end_time cho dữ liệu cũ đang có expected_time
    UPDATE appointments
    SET expected_end_time = TIME(TIMESTAMP(appointment_date, expected_time) + INTERVAL COALESCE(duration_minutes, 30) MINUTE)
    WHERE expected_time IS NOT NULL
      AND expected_end_time IS NULL;

    -- 3. Bảng visits: bổ sung các cờ ưu tiên và mốc thời gian khám thực tế
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'visits'
          AND column_name = 'is_emergency'
    ) THEN
        ALTER TABLE visits
            ADD COLUMN is_emergency TINYINT(1) NOT NULL DEFAULT 0 AFTER visit_type;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'visits'
          AND column_name = 'is_booked_and_on_time'
    ) THEN
        ALTER TABLE visits
            ADD COLUMN is_booked_and_on_time TINYINT(1) NOT NULL DEFAULT 0 AFTER is_emergency;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'visits'
          AND column_name = 'actual_start_time'
    ) THEN
        ALTER TABLE visits
            ADD COLUMN actual_start_time DATETIME NULL AFTER check_in_at;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'visits'
          AND column_name = 'actual_end_time'
    ) THEN
        ALTER TABLE visits
            ADD COLUMN actual_end_time DATETIME NULL AFTER completed_at;
    END IF;

    -- Backfill an toàn cho dữ liệu visits cũ mà không làm sai lệch lịch sử
    UPDATE visits
    SET is_emergency = 1
    WHERE visit_type = 'EMERGENCY'
      AND is_emergency = 0;

    UPDATE visits v
    INNER JOIN appointments a ON v.appointment_id = a.appointment_id
    SET v.is_booked_and_on_time = 1
    WHERE v.visit_type = 'APPOINTMENT'
      AND v.is_booked_and_on_time = 0
      AND a.expected_time IS NOT NULL
      AND v.check_in_at <= (TIMESTAMP(a.appointment_date, a.expected_time) + INTERVAL 15 MINUTE);

    UPDATE visits
    SET actual_end_time = completed_at
    WHERE status = 'COMPLETED'
      AND completed_at IS NOT NULL
      AND actual_end_time IS NULL;

    -- 4. Index phục vụ truy vấn slot bác sĩ, quét NO_SHOW và hàng đợi 4 cấp ưu tiên
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'appointments'
          AND index_name = 'idx_appt_doctor_date_status_time'
    ) THEN
        CREATE INDEX idx_appt_doctor_date_status_time
            ON appointments (doctor_id, appointment_date, status, expected_time, expected_end_time);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'appointments'
          AND index_name = 'idx_appt_noshow_scan'
    ) THEN
        CREATE INDEX idx_appt_noshow_scan
            ON appointments (status, appointment_date, expected_time);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'visits'
          AND index_name = 'idx_visit_doctor_queue'
    ) THEN
        CREATE INDEX idx_visit_doctor_queue
            ON visits (primary_doctor_id, status, check_in_at);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'visits'
          AND index_name = 'idx_visit_appointment'
    ) THEN
        CREATE INDEX idx_visit_appointment
            ON visits (appointment_id);
    END IF;
END$$

DELIMITER ;

CALL sp_upgrade_v114_doctor_booking_and_queue();
DROP PROCEDURE sp_upgrade_v114_doctor_booking_and_queue;
