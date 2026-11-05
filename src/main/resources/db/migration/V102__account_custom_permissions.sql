-- =====================================================================
-- Migration V102: Quyền tùy chỉnh theo tài khoản (Account Custom Permissions)
-- Đồng bộ với User.customPermissionsEnabled và bảng user_permissions
-- Idempotent, an toàn cho MySQL 8.0+
-- =====================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_upgrade_v102_custom_permissions$$

CREATE PROCEDURE sp_upgrade_v102_custom_permissions()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'users'
          AND COLUMN_NAME = 'custom_permissions_enabled'
    ) THEN
        ALTER TABLE users
            ADD COLUMN custom_permissions_enabled BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;

    UPDATE users
    SET custom_permissions_enabled = FALSE
    WHERE custom_permissions_enabled IS NULL;

    CREATE TABLE IF NOT EXISTS user_permissions (
        user_id BIGINT NOT NULL,
        permission_id BIGINT NOT NULL,
        CONSTRAINT uk_user_permissions UNIQUE (user_id, permission_id),
        INDEX idx_user_permissions_permission (permission_id),
        CONSTRAINT fk_user_permissions_user
            FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
        CONSTRAINT fk_user_permissions_permission
            FOREIGN KEY (permission_id) REFERENCES permissions(permission_id) ON DELETE CASCADE
    ) ENGINE=InnoDB
      DEFAULT CHARSET=utf8mb4
      COLLATE=utf8mb4_unicode_ci;
END$$

DELIMITER ;

CALL sp_upgrade_v102_custom_permissions();
DROP PROCEDURE IF EXISTS sp_upgrade_v102_custom_permissions;
