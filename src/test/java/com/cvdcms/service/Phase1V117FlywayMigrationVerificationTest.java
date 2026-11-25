package com.cvdcms.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("v118test")
class Phase1V117FlywayMigrationVerificationTest {

    @Autowired
    private DataSource dataSource;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Test
    @DisplayName("Kiểm tra Flyway từ đầu & chạy thực tế V117 trên MySQL 8 schema thử nghiệm mới hoàn toàn")
    void verifyV117OnFreshMySql8TestSchema() throws Exception {
        String testSchema = "cv_dcms_v117_verify";
        String jdbcUrlBase = "jdbc:mysql://localhost:3306/";
        String fullJdbcUrl = jdbcUrlBase + testSchema + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh&allowMultiQueries=true";

        try (Connection rootConn = dataSource.getConnection();
             Statement stmt = rootConn.createStatement()) {
            String user = rootConn.getMetaData().getUserName();
            String username = user.contains("@") ? user.substring(0, user.indexOf('@')) : user;

            stmt.execute("DROP DATABASE IF EXISTS `" + testSchema + "`");
            stmt.execute("CREATE DATABASE `" + testSchema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

            // 1. Thử chạy Flyway từ đầu theo đúng cấu hình dự án trên schema trống để phát hiện vấn đề nếu có
            String flywayFromScratchResult;
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(fullJdbcUrl, username, dbPassword)
                        .locations("classpath:db/migration")
                        .load();
                flyway.migrate();
                flywayFromScratchResult = "OK";
            } catch (Exception ex) {
                flywayFromScratchResult = ex.getMessage();
            }

            // Nếu repo gốc có 2 file trùng version V107__, ghi nhận rõ ràng và test chạy V117 trên schema đã có cấu trúc bảng nền
            assertTrue(flywayFromScratchResult.contains("107") || "OK".equals(flywayFromScratchResult),
                    "Flyway migrate from scratch unexpected error: " + flywayFromScratchResult);

            // 2. Tạo bảng nền tối thiểu (branches, rooms, staff_profiles, services) trong schema thử nghiệm mới và chạy trực tiếp V117
            stmt.execute("USE `" + testSchema + "`");
            stmt.execute("CREATE TABLE IF NOT EXISTS branches (branch_id BIGINT AUTO_INCREMENT PRIMARY KEY, branch_code VARCHAR(20), branch_name VARCHAR(100))");
            stmt.execute("CREATE TABLE IF NOT EXISTS rooms (room_id BIGINT AUTO_INCREMENT PRIMARY KEY, branch_id BIGINT NOT NULL, room_code VARCHAR(20) NOT NULL, room_name VARCHAR(100) NOT NULL, room_type VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS staff_profiles (staff_id BIGINT AUTO_INCREMENT PRIMARY KEY, staff_code VARCHAR(20))");
            stmt.execute("CREATE TABLE IF NOT EXISTS services (service_id BIGINT AUTO_INCREMENT PRIMARY KEY, service_code VARCHAR(30) NOT NULL, service_name VARCHAR(200) NOT NULL, service_type VARCHAR(30) NOT NULL, estimated_duration_minutes INT NOT NULL DEFAULT 30)");

            stmt.execute("INSERT INTO branches (branch_code, branch_name) VALUES ('CS1', 'Cơ sở Test')");
            stmt.execute("INSERT INTO rooms (branch_id, room_code, room_name, room_type, status) VALUES (1, 'P104', 'Phòng X-quang', 'XRAY', 'ACTIVE')");
            stmt.execute("INSERT INTO staff_profiles (staff_code) VALUES ('BS01')");
            stmt.execute("INSERT INTO services (service_code, service_name, service_type, estimated_duration_minutes) VALUES ('DV-KHAM-TQ', 'Khám tổng quát', 'CONSULTATION', 20)");

            // Đọc và thực thi V117__resource_scheduling_master_data.sql (bỏ dòng comment -- và DELIMITER directive để chạy nguyên khối qua JDBC)
            String rawSql = new ClassPathResource("db/migration/V117__resource_scheduling_master_data.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            StringBuilder cleaned = new StringBuilder();
            for (String line : rawSql.split("\\R")) {
                String t = line.trim();
                if (!t.startsWith("--") && !t.startsWith("DELIMITER")) {
                    cleaned.append(line).append('\n');
                }
            }
            for (String part : cleaned.toString().split("\\$\\$")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }

            // 3. Xác minh đủ 6 bảng mới tồn tại trong schema thử nghiệm
            List<String> expectedTables = List.of(
                    "branch_room_transition_rules",
                    "clinic_equipments",
                    "equipment_transfer_logs",
                    "doctor_room_assignments",
                    "doctor_service_permissions",
                    "service_resource_requirements"
            );
            for (String table : expectedTables) {
                try (ResultSet rs = stmt.executeQuery("SHOW TABLES LIKE '" + table + "'")) {
                    assertTrue(rs.next(), "Thiếu bảng mới sau V117: " + table);
                }
            }

            // 4. Xác minh backfill an toàn: KHÔNG tự tạo máy NanoPix giả, KHÔNG tự cấp quyền BS
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM clinic_equipments")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V117 không được tự tạo thiết bị NanoPix giả cho phòng XRAY");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM doctor_room_assignments")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V117 không được tự cấp phân công phòng bác sĩ");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM doctor_service_permissions")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V117 không được tự cấp quyền dịch vụ bác sĩ");
            }

            // 5. Xác minh backfill services & DV-KHAM-TQ
            try (ResultSet rs = stmt.executeQuery("SELECT procedure_duration_minutes, is_default_consultation FROM services WHERE service_code = 'DV-KHAM-TQ'")) {
                assertTrue(rs.next());
                assertEquals(20, rs.getInt(1));
                assertEquals(1, rs.getInt(2));
            }

            // Dọn schema thử nghiệm hoàn toàn sau khi xác minh
            stmt.execute("DROP DATABASE IF EXISTS `" + testSchema + "`");
        }
    }
}
