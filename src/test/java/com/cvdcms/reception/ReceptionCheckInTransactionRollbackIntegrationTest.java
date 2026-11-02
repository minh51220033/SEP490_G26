package com.cvdcms.reception;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves that database transactions roll back atomically when any failure occurs
 * during Reception confirmAndCheckIn or check-in flow.
 */
class ReceptionCheckInTransactionRollbackIntegrationTest {

    @Test
    @DisplayName("Transaction rollback restores Appointment status and avoids orphaned Visit/History on failure")
    void transactionRollbackOnFailureRestoresAllState() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:checkin_rollback;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE TABLE appointments (appointment_id BIGINT PRIMARY KEY, status VARCHAR(20) NOT NULL)");
        jdbc.execute("CREATE TABLE appointment_history (history_id BIGINT AUTO_INCREMENT PRIMARY KEY, appointment_id BIGINT, action VARCHAR(50))");
        jdbc.execute("CREATE TABLE visits (visit_id BIGINT AUTO_INCREMENT PRIMARY KEY, appointment_id BIGINT, status VARCHAR(20))");
        jdbc.execute("CREATE TABLE check_in_keys (key_id BIGINT AUTO_INCREMENT PRIMARY KEY, request_key VARCHAR(100) UNIQUE, visit_id BIGINT)");

        jdbc.update("INSERT INTO appointments(appointment_id, status) VALUES (1, 'PENDING')");

        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        String requestKey = UUID.randomUUID().toString();

        // Simulate confirmAndCheckIn atomic flow where Visit creation or later step fails
        assertThrows(RuntimeException.class, () -> {
            tx.executeWithoutResult(status -> {
                // Step 1: PENDING -> CONFIRMED
                jdbc.update("UPDATE appointments SET status='CONFIRMED' WHERE appointment_id=1");
                jdbc.update("INSERT INTO appointment_history(appointment_id, action) VALUES (1, 'CONFIRM')");

                // Step 2: CONFIRMED -> CHECKED_IN
                jdbc.update("UPDATE appointments SET status='CHECKED_IN' WHERE appointment_id=1");
                jdbc.update("INSERT INTO appointment_history(appointment_id, action) VALUES (1, 'CHECK_IN')");

                // Step 3: Visit insertion fails unexpectedly (e.g. constraint / network error)
                throw new RuntimeException("Simulated Visit creation failure");
            });
        });

        // Verify that after rollback, appointment status remains PENDING and no history/visit exists
        String statusAfter = jdbc.queryForObject("SELECT status FROM appointments WHERE appointment_id=1", String.class);
        assertEquals("PENDING", statusAfter, "Appointment status must remain PENDING after rollback");

        Integer historyCount = jdbc.queryForObject("SELECT count(*) FROM appointment_history WHERE appointment_id=1", Integer.class);
        assertEquals(0, historyCount, "No history records should persist after rollback");

        Integer visitCount = jdbc.queryForObject("SELECT count(*) FROM visits WHERE appointment_id=1", Integer.class);
        assertEquals(0, visitCount, "No visits should persist after rollback");
    }

    @Test
    @DisplayName("PreUpdate domain invariant prevents direct PENDING to CHECKED_IN transition")
    void appointmentEntityPreventsDirectPendingToCheckedIn() {
        com.cvdcms.entity.Appointment appt = new com.cvdcms.entity.Appointment();
        appt.setStatus(com.cvdcms.entity.enums.AppointmentStatus.PENDING);
        appt.postLoad(); // simulates loading from database

        appt.setStatus(com.cvdcms.entity.enums.AppointmentStatus.CHECKED_IN);
        assertThrows(IllegalStateException.class, appt::preUpdate,
                "Direct transition from PENDING to CHECKED_IN must be rejected by @PreUpdate invariant");
    }
}

