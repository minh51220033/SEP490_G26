package com.cvdcms.service;

import com.cvdcms.testguard.TestDatabaseIsolationInitializer;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Helper chuẩn bị schema MySQL 8 cô lập tuyệt đối (cv_dcms_v118_verify) trước khi Spring Boot khởi động
 * với ddl-auto=validate. Tuyệt đối không kết nối hoặc đụng tới cv_dcms_db.
 */
final class IsolatedVerifySchemaHelper {

    static final String TARGET_VERIFY_SCHEMA = "cv_dcms_v118_verify";
    private static final Set<String> ALLOWED_VERIFY_SCHEMAS = Set.of(TARGET_VERIFY_SCHEMA);
    private static final Set<String> FORBIDDEN_SCHEMAS = Set.of(
            "cv_dcms_db", "cv_dcms", "cv_dcms_1", "mysql", "information_schema", "performance_schema", "sys"
    );

    private IsolatedVerifySchemaHelper() {
    }

    static Connection openVerifiedConnection() throws Exception {
        assertNoForbiddenDatasourceOverrides();

        Properties testProps = new Properties();
        try (InputStream is = new ClassPathResource("application-v118test.properties").getInputStream()) {
            testProps.load(is);
        }
        String testUrl = testProps.getProperty("spring.datasource.url", "");
        String ddlAuto = testProps.getProperty("spring.jpa.hibernate.ddl-auto", "");
        TestDatabaseIsolationInitializer.validateJdbcUrlAndSettings(testUrl, ddlAuto, "v118test");
        if (!"validate".equalsIgnoreCase(ddlAuto)) {
            throw new IllegalStateException("ABORT: application-v118test.properties must enforce ddl-auto=validate");
        }

        Properties baseProps = new Properties();
        try (InputStream is = new ClassPathResource("application.properties").getInputStream()) {
            baseProps.load(is);
        }
        String user = baseProps.getProperty("spring.datasource.username", "");
        String pass = baseProps.getProperty("spring.datasource.password", "");
        Connection conn = DriverManager.getConnection(testUrl, user, pass);
        try {
            TestDatabaseIsolationInitializer.validateLiveConnection(conn);
        } catch (Exception ex) {
            conn.close();
            throw ex;
        }
        return conn;
    }

    static void assertNoForbiddenDatasourceOverrides() {
        for (String val : List.of(
                safeStr(System.getenv("SPRING_DATASOURCE_URL")),
                safeStr(System.getProperty("spring.datasource.url")))) {
            if (!val.isBlank()) {
                String lower = val.toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_SCHEMAS) {
                    if (lower.contains("/" + forbidden)) {
                        throw new IllegalStateException("ABORT: Datasource override targets forbidden schema: " + forbidden);
                    }
                }
                if (!lower.contains("/" + TARGET_VERIFY_SCHEMA)) {
                    throw new IllegalStateException("ABORT: Datasource override does not target " + TARGET_VERIFY_SCHEMA);
                }
            }
        }
        for (String ddlOverride : List.of(
                safeStr(System.getenv("SPRING_JPA_HIBERNATE_DDL_AUTO")),
                safeStr(System.getProperty("spring.jpa.hibernate.ddl-auto")))) {
            if (!ddlOverride.isBlank() && !"validate".equalsIgnoreCase(ddlOverride)) {
                throw new IllegalStateException("ABORT: Hibernate ddl-auto override must be validate, got: " + ddlOverride);
            }
        }
    }

    static void assertConnectedDatabaseAllowlisted(Statement stmt) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT DATABASE()")) {
            if (!rs.next()) {
                throw new IllegalStateException("ABORT: SELECT DATABASE() returned no row");
            }
            String activeDb = rs.getString(1);
            if (activeDb == null || !ALLOWED_VERIFY_SCHEMAS.contains(activeDb)
                    || FORBIDDEN_SCHEMAS.contains(activeDb.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("ABORT: Connected database is not allowlisted verify schema: " + activeDb);
            }
        }
    }

    static void resetAllTablesInVerifySchema(Statement stmt) throws Exception {
        assertConnectedDatabaseAllowlisted(stmt);
        List<String> existingTables = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = '" + TARGET_VERIFY_SCHEMA + "' AND TABLE_TYPE = 'BASE TABLE'")) {
            while (rs.next()) {
                existingTables.add(rs.getString(1));
            }
        }
        if (!existingTables.isEmpty()) {
            assertConnectedDatabaseAllowlisted(stmt);
            stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : existingTables) {
                stmt.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    static void executeSqlScriptStatements(Statement stmt, String classpathResource, String delimiter) throws Exception {
        assertConnectedDatabaseAllowlisted(stmt);
        String rawSql = new ClassPathResource(classpathResource).getContentAsString(StandardCharsets.UTF_8);
        StringBuilder current = new StringBuilder();
        for (String line : rawSql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.startsWith("DELIMITER") || (trimmed.isEmpty() && current.isEmpty())) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(delimiter)) {
                String sql = current.toString().trim();
                sql = sql.substring(0, sql.length() - delimiter.length()).trim();
                current.setLength(0);
                if (!sql.isEmpty()) {
                    stmt.execute(sql);
                }
            }
        }
        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            stmt.execute(remaining);
        }
    }

    private static String safeStr(String s) {
        return s == null ? "" : s.trim();
    }
}
