-- V107: Nâng cấp module Chăm sóc khách hàng Realtime, Lịch sử chuyển BS và Notification Center
-- An toàn, không xóa dữ liệu cũ, mở rộng system_notifications và customer_care_tasks

ALTER TABLE system_notifications
    MODIFY COLUMN notification_type VARCHAR(40) NOT NULL,
    ADD COLUMN task_id BIGINT NULL,
    ADD COLUMN branch_id BIGINT NULL,
    ADD COLUMN read_at DATETIME(6) NULL,
    ADD COLUMN priority_level VARCHAR(20) NULL,
    ADD COLUMN action_url VARCHAR(255) NULL;

CREATE INDEX idx_sysnotif_user_read_created
    ON system_notifications (user_id, is_read, created_at);

CREATE INDEX idx_sysnotif_task_id
    ON system_notifications (task_id);

ALTER TABLE customer_care_tasks
    ADD COLUMN escalation_reason TEXT NULL,
    ADD COLUMN escalated_by_user_id BIGINT NULL;
