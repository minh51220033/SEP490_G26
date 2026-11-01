-- =====================================================================
-- CV-DCMS MIGRATION V13: QUYEN TUY CHINH THEO TAI KHOAN
-- Dong bo voi User.customPermissionsEnabled va User.customPermissions.
-- Migration co the chay lap lai tren ca database cu va database moi.
-- =====================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_v13_account_custom_permissions $$
CREATE PROCEDURE sp_v13_account_custom_permissions()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'users'
          AND COLUMN_NAME = 'custom_permissions_enabled'
    ) THEN
        ALTER TABLE users
            ADD COLUMN custom_permissions_enabled BOOLEAN NOT NULL DEFAULT FALSE
            AFTER last_login_at;
    END IF;

    -- Gia tri Java mac dinh la false; backfill de tranh tai khoan cu bi NULL.
    UPDATE users
    SET custom_permissions_enabled = FALSE
    WHERE custom_permissions_enabled IS NULL;

    ALTER TABLE users
        MODIFY COLUMN custom_permissions_enabled BOOLEAN NOT NULL DEFAULT FALSE;

    CREATE TABLE IF NOT EXISTS user_permissions (
        user_id BIGINT NOT NULL,
        permission_id BIGINT NOT NULL,
        CONSTRAINT uk_user_permissions UNIQUE (user_id, permission_id),
        INDEX idx_user_permissions_permission (permission_id),
        CONSTRAINT fk_user_permissions_user
            FOREIGN KEY (user_id) REFERENCES users(user_id),
        CONSTRAINT fk_user_permissions_permission
            FOREIGN KEY (permission_id) REFERENCES permissions(permission_id)
    ) ENGINE=InnoDB
      DEFAULT CHARSET=utf8mb4
      COLLATE=utf8mb4_unicode_ci;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'user_permissions'
          AND INDEX_NAME = 'uk_user_permissions'
    ) THEN
        ALTER TABLE user_permissions
            ADD CONSTRAINT uk_user_permissions UNIQUE (user_id, permission_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'user_permissions'
          AND COLUMN_NAME = 'permission_id'
    ) THEN
        ALTER TABLE user_permissions
            ADD INDEX idx_user_permissions_permission (permission_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.KEY_COLUMN_USAGE
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'user_permissions'
          AND COLUMN_NAME = 'user_id'
          AND REFERENCED_TABLE_NAME = 'users'
          AND REFERENCED_COLUMN_NAME = 'user_id'
    ) THEN
        ALTER TABLE user_permissions
            ADD CONSTRAINT fk_user_permissions_user
            FOREIGN KEY (user_id) REFERENCES users(user_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.KEY_COLUMN_USAGE
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'user_permissions'
          AND COLUMN_NAME = 'permission_id'
          AND REFERENCED_TABLE_NAME = 'permissions'
          AND REFERENCED_COLUMN_NAME = 'permission_id'
    ) THEN
        ALTER TABLE user_permissions
            ADD CONSTRAINT fk_user_permissions_permission
            FOREIGN KEY (permission_id) REFERENCES permissions(permission_id);
    END IF;
END $$

DELIMITER ;

CALL sp_v13_account_custom_permissions();
DROP PROCEDURE IF EXISTS sp_v13_account_custom_permissions;
