-- V15: Thêm cột points_redeemed vào bảng invoices để lưu chính xác số điểm tích lũy thực tế đã dùng
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS points_redeemed INT DEFAULT 0 COMMENT 'Số điểm tích lũy loyalty thực tế đã sử dụng để giảm giá';

