package com.cvdcms.testguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class TestDatabaseIsolationGuardTest {

    @Test
    @DisplayName("Chặn fail-fast URL trỏ cv_dcms_db, schema lạ, createDatabaseIfNotExist=true, ddl-auto=update trên MySQL và thiếu URL")
    void guardRejectsUnsafeConfigurationsFailFast() {
        assertThrows(IllegalStateException.class, () ->
                TestDatabaseIsolationInitializer.validateJdbcUrlAndSettings("", "validate", "default"));

        assertThrows(IllegalStateException.class, () ->
                TestDatabaseIsolationInitializer.validateJdbcUrlAndSettings(
                        "jdbc:mysql://localhost:3306/cv_dcms_db?useSSL=false", "validate", "default"));

        assertThrows(IllegalStateException.class, () ->
                TestDatabaseIsolationInitializer.validateJdbcUrlAndSettings(
                        "jdbc:mysql://localhost:3306/some_other_schema?useSSL=false", "validate", "test"));

        assertThrows(IllegalStateException.class, () ->
                TestDatabaseIsolationInitializer.validateJdbcUrlAndSettings(
                        "jdbc:mysql://localhost:3306/cv_dcms_v118_verify?createDatabaseIfNotExist=true", "validate", "v118test"));

        assertThrows(IllegalStateException.class, () ->
                TestDatabaseIsolationInitializer.validateJdbcUrlAndSettings(
                        "jdbc:mysql://localhost:3306/cv_dcms_v118_verify?createDatabaseIfNotExist=false", "update", "v118test"));

        MockEnvironment h2Env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:cv_dcms_test;MODE=MySQL")
                .withProperty("spring.jpa.hibernate.ddl-auto", "create-drop");
        assertThrows(IllegalStateException.class, () ->
                TestDatabaseIsolationInitializer.validateEnvironment(
                        h2Env,
                        "jdbc:mysql://localhost:3306/cv_dcms_db?useSSL=false",
                        null, null, null));
    }

    @Test
    @DisplayName("Chặn khi conn.getCatalog() hoặc SELECT DATABASE() trả về cv_dcms_db hoặc không khớp allowlist")
    void guardRejectsUnsafeLiveConnectionCatalogAndSelectDatabase() throws Exception {
        Connection badConn = Mockito.mock(Connection.class);
        DatabaseMetaData badMeta = Mockito.mock(DatabaseMetaData.class);
        Statement badStmt = Mockito.mock(Statement.class);
        ResultSet badRs = Mockito.mock(ResultSet.class);

        when(badConn.getMetaData()).thenReturn(badMeta);
        when(badMeta.getDatabaseProductName()).thenReturn("MySQL");
        when(badMeta.getURL()).thenReturn("jdbc:mysql://localhost:3306/cv_dcms_v118_verify");
        when(badConn.getCatalog()).thenReturn("cv_dcms_db");
        when(badConn.createStatement()).thenReturn(badStmt);
        when(badStmt.executeQuery("SELECT DATABASE()")).thenReturn(badRs);
        when(badRs.next()).thenReturn(true);
        when(badRs.getString(1)).thenReturn("cv_dcms_db");

        assertThrows(IllegalStateException.class, () ->
                TestDatabaseIsolationInitializer.validateLiveConnection(badConn));
    }

    @Test
    @DisplayName("Cho phép H2 in-memory và MySQL cv_dcms_v118_verify khi catalog và SELECT DATABASE() khớp")
    void guardAllowsIsolatedH2AndAllowlistedMySql() throws Exception {
        assertDoesNotThrow(() ->
                TestDatabaseIsolationInitializer.validateJdbcUrlAndSettings(
                        "jdbc:h2:mem:cv_dcms_test;MODE=MySQL", "create-drop", "test"));

        assertDoesNotThrow(() ->
                TestDatabaseIsolationInitializer.validateJdbcUrlAndSettings(
                        "jdbc:mysql://localhost:3306/cv_dcms_v118_verify?createDatabaseIfNotExist=false", "validate", "v118test"));

        Connection okConn = Mockito.mock(Connection.class);
        DatabaseMetaData okMeta = Mockito.mock(DatabaseMetaData.class);
        Statement okStmt = Mockito.mock(Statement.class);
        ResultSet okRs = Mockito.mock(ResultSet.class);

        when(okConn.getMetaData()).thenReturn(okMeta);
        when(okMeta.getDatabaseProductName()).thenReturn("MySQL");
        when(okMeta.getURL()).thenReturn("jdbc:mysql://localhost:3306/cv_dcms_v118_verify?createDatabaseIfNotExist=false");
        when(okConn.getCatalog()).thenReturn("cv_dcms_v118_verify");
        when(okConn.createStatement()).thenReturn(okStmt);
        when(okStmt.executeQuery("SELECT DATABASE()")).thenReturn(okRs);
        when(okRs.next()).thenReturn(true);
        when(okRs.getString(1)).thenReturn("cv_dcms_v118_verify");

        assertDoesNotThrow(() ->
                TestDatabaseIsolationInitializer.validateLiveConnection(okConn));
    }
}
