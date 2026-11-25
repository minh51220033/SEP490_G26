package com.cvdcms.testguard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;

/**
 * Global Fail-Fast Database Isolation Guard cho mọi Spring Boot / DataJpa test.
 * Chặn tuyệt đối kết nối tới cv_dcms_db, schema ngoài allowlist, ddl-auto=update trên MySQL,
 * và createDatabaseIfNotExist=true trước khi Hibernate/Flyway thực hiện bất kỳ DDL/DML nào.
 */
public class TestDatabaseIsolationInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(TestDatabaseIsolationInitializer.class);

    public static final Set<String> ALLOWED_MYSQL_TEST_SCHEMAS = Set.of("cv_dcms_v118_verify");
    public static final Set<String> FORBIDDEN_SCHEMAS = Set.of(
            "cv_dcms_db", "cv_dcms", "cv_dcms_1", "mysql", "information_schema", "performance_schema", "sys"
    );

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment env = applicationContext.getEnvironment();
        validateEnvironment(
                env,
                System.getenv("SPRING_DATASOURCE_URL"),
                System.getProperty("spring.datasource.url"),
                System.getenv("SPRING_JPA_HIBERNATE_DDL_AUTO"),
                System.getProperty("spring.jpa.hibernate.ddl-auto")
        );

        applicationContext.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DataSource dataSource) {
                    validateActualDataSourceConnection(dataSource);
                }
                return bean;
            }
        });
    }

    public static void validateEnvironment(
            Environment env,
            String envDatasourceUrlOverride,
            String sysPropDatasourceUrlOverride,
            String envDdlAutoOverride,
            String sysPropDdlAutoOverride) {
        String configuredUrl = trimToEmpty(env.getProperty("spring.datasource.url"));
        String effectiveUrl = firstNonBlank(sysPropDatasourceUrlOverride, envDatasourceUrlOverride, configuredUrl);
        String ddlAuto = firstNonBlank(sysPropDdlAutoOverride, envDdlAutoOverride, trimToEmpty(env.getProperty("spring.jpa.hibernate.ddl-auto")));

        validateJdbcUrlAndSettings(effectiveUrl, ddlAuto, trimToEmpty(String.join(",", env.getActiveProfiles())));
    }

    public static void validateJdbcUrlAndSettings(String jdbcUrl, String ddlAuto, String activeProfilesLabel) {
        String url = trimToEmpty(jdbcUrl);
        if (url.isBlank()) {
            throw new IllegalStateException(
                    "[TEST-DB-GUARD] ABORT: Thiếu cấu hình spring.datasource.url trong test (profiles="
                            + activeProfilesLabel + "). Không cho phép fallback.");
        }

        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains("cv_dcms_db")) {
            throw new IllegalStateException(
                    "[TEST-DB-GUARD] ABORT: JDBC URL chứa schema cấm 'cv_dcms_db': " + url);
        }

        if (lowerUrl.startsWith("jdbc:h2:mem:")) {
            log.info("[TEST-DB-GUARD] Pre-context check PASS (H2 in-memory): profiles=[{}], ddl-auto={}, url={}",
                    activeProfilesLabel, ddlAuto, url);
            return;
        }

        if (lowerUrl.startsWith("jdbc:mysql:")) {
            if (lowerUrl.contains("createdatabaseifnotexist=true")) {
                throw new IllegalStateException(
                        "[TEST-DB-GUARD] ABORT: Cấm createDatabaseIfNotExist=true trên test MySQL: " + url);
            }
            if ("update".equalsIgnoreCase(trimToEmpty(ddlAuto))) {
                throw new IllegalStateException(
                        "[TEST-DB-GUARD] ABORT: Cấm spring.jpa.hibernate.ddl-auto=update trên test MySQL (url=" + url + ")");
            }
            String schema = extractMysqlSchema(url);
            if (schema == null || FORBIDDEN_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))
                    || !ALLOWED_MYSQL_TEST_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                        "[TEST-DB-GUARD] ABORT: Schema MySQL '" + schema + "' không nằm trong allowlist "
                                + ALLOWED_MYSQL_TEST_SCHEMAS + " (url=" + url + ")");
            }
            log.info("[TEST-DB-GUARD] Pre-context check PASS (MySQL allowlisted): profiles=[{}], schema={}, ddl-auto={}, url={}",
                    activeProfilesLabel, schema, ddlAuto, url);
            return;
        }

        throw new IllegalStateException(
                "[TEST-DB-GUARD] ABORT: Datasource URL không được hỗ trợ cho automated test: " + url);
    }

    public static void validateActualDataSourceConnection(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            validateLiveConnection(conn);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("[TEST-DB-GUARD] ABORT: Không thể xác minh kết nối DataSource thực tế", ex);
        }
    }

    public static void validateLiveConnection(Connection conn) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        String url = trimToEmpty(meta.getURL());
        String product = trimToEmpty(meta.getDatabaseProductName());
        String catalog = trimToEmpty(conn.getCatalog());
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        String lowerProduct = product.toLowerCase(Locale.ROOT);

        if (lowerProduct.contains("h2") || lowerUrl.startsWith("jdbc:h2:mem:")) {
            if (!lowerUrl.startsWith("jdbc:h2:mem:")) {
                throw new IllegalStateException(
                        "[TEST-DB-GUARD] ABORT: H2 test bắt buộc dùng jdbc:h2:mem: in-memory, phát hiện: " + url);
            }
            log.info("[TEST-DB-GUARD] Verified live DataSource: product={}, catalog={}, url={}",
                    product, catalog, url);
            return;
        }

        if (lowerProduct.contains("mysql") || lowerUrl.startsWith("jdbc:mysql:")) {
            if (lowerUrl.contains("createdatabaseifnotexist=true")) {
                throw new IllegalStateException(
                        "[TEST-DB-GUARD] ABORT: Live MySQL connection chứa createDatabaseIfNotExist=true: " + url);
            }
            String selectDatabase = null;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT DATABASE()")) {
                if (rs.next()) {
                    selectDatabase = trimToEmpty(rs.getString(1));
                }
            }
            if (catalog.isBlank() || selectDatabase == null || selectDatabase.isBlank()
                    || !catalog.equalsIgnoreCase(selectDatabase)) {
                throw new IllegalStateException(
                        "[TEST-DB-GUARD] ABORT: Catalog không khớp SELECT DATABASE() (catalog="
                                + catalog + ", SELECT DATABASE()=" + selectDatabase + ")");
            }
            String normalizedDb = selectDatabase.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_SCHEMAS.contains(normalizedDb) || !ALLOWED_MYSQL_TEST_SCHEMAS.contains(normalizedDb)) {
                throw new IllegalStateException(
                        "[TEST-DB-GUARD] ABORT: Kết nối MySQL thực tế '" + selectDatabase
                                + "' bị chặn hoặc ngoài allowlist " + ALLOWED_MYSQL_TEST_SCHEMAS);
            }
            log.info("[TEST-DB-GUARD] Verified live DataSource: product={}, catalog={}, SELECT DATABASE()={}, url={}",
                    product, catalog, selectDatabase, url);
            return;
        }

        throw new IllegalStateException(
                "[TEST-DB-GUARD] ABORT: Database product không hợp lệ trong test: " + product + " (" + url + ")");
    }

    public static String extractMysqlSchema(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        int schemeIdx = jdbcUrl.indexOf("://");
        if (schemeIdx < 0) {
            return null;
        }
        int pathStart = jdbcUrl.indexOf('/', schemeIdx + 3);
        if (pathStart < 0 || pathStart + 1 >= jdbcUrl.length()) {
            return null;
        }
        String afterSlash = jdbcUrl.substring(pathStart + 1);
        int queryIdx = afterSlash.indexOf('?');
        String schema = (queryIdx >= 0 ? afterSlash.substring(0, queryIdx) : afterSlash).trim();
        return schema.isEmpty() ? null : schema;
    }

    private static String firstNonBlank(String a, String b, String c) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return c != null ? c.trim() : "";
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
