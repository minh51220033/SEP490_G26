-- =============================================================================
-- V126: Xóa dữ liệu PENDING_REVIEW cũ & đặt mặc định ACTIVE cho phân công BS
-- =============================================================================

DELETE FROM doctor_room_assignments WHERE status = 'PENDING_REVIEW';
DELETE FROM doctor_service_permissions WHERE status = 'PENDING_REVIEW';

ALTER TABLE doctor_room_assignments
    MODIFY COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE doctor_service_permissions
    MODIFY COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE';
