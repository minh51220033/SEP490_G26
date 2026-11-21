-- ====================================================================================================
-- FLYWAY MIGRATION V115: BOOKING CONSULTATION REQUESTS (GUEST BOOKING P0)
-- Mục đích:
--   Lưu yêu cầu tư vấn đặt lịch của khách chưa đăng nhập (SĐT chưa tồn tại) vào bảng riêng,
--   tách biệt hoàn toàn khỏi users, patient_profiles và appointments chính thức (không chiếm slot).
--   Hỗ trợ idempotency qua request_key UNIQUE.
-- ====================================================================================================

CREATE TABLE IF NOT EXISTS booking_consultation_requests (
    request_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_code VARCHAR(30) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    branch_id BIGINT NOT NULL,
    preferred_doctor_id BIGINT NULL,
    service_id BIGINT NULL,
    guest_name VARCHAR(150) NOT NULL,
    guest_phone VARCHAR(20) NOT NULL,
    preferred_date DATE NOT NULL,
    preferred_session VARCHAR(20) NOT NULL,
    preferred_time TIME NULL,
    notes VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    converted_appointment_id BIGINT NULL,
    handled_by_user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_bcr_request_code (request_code),
    UNIQUE KEY uk_bcr_request_key (request_key),
    INDEX idx_bcr_status_branch_created (status, branch_id, created_at),
    INDEX idx_bcr_phone_branch_date (guest_phone, branch_id, preferred_date, preferred_session, status),
    CONSTRAINT fk_bcr_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id),
    CONSTRAINT fk_bcr_doctor FOREIGN KEY (preferred_doctor_id) REFERENCES staff_profiles(staff_id),
    CONSTRAINT fk_bcr_service FOREIGN KEY (service_id) REFERENCES services(service_id),
    CONSTRAINT fk_bcr_converted_appt FOREIGN KEY (converted_appointment_id) REFERENCES appointments(appointment_id),
    CONSTRAINT fk_bcr_handler_user FOREIGN KEY (handled_by_user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
