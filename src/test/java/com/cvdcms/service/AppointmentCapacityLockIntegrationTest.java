package com.cvdcms.service;

import com.cvdcms.repository.AppointmentBookingLockRepository;
import jakarta.persistence.LockModeType;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Self-contained transaction integration test for the last-slot race. */
class AppointmentCapacityLockIntegrationTest {
    @Test
    void pessimisticRowLockAllowsOnlyOneConcurrentRequestToTakeLastSlot() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:appointment_lock;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE booking_branch_lock (branch_id BIGINT PRIMARY KEY, remaining_slots INT NOT NULL)");
        jdbc.update("INSERT INTO booking_branch_lock(branch_id, remaining_slots) VALUES (1, 1)");
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        AtomicInteger successfulBookings = new AtomicInteger();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> booking = () -> {
            startTogether.await(5, TimeUnit.SECONDS);
            transactions.executeWithoutResult(status -> {
                Integer remaining = jdbc.queryForObject(
                        "SELECT remaining_slots FROM booking_branch_lock WHERE branch_id=1 FOR UPDATE",
                        Integer.class);
                if (remaining != null && remaining > 0) {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    jdbc.update("UPDATE booking_branch_lock SET remaining_slots=remaining_slots-1 WHERE branch_id=1");
                    successfulBookings.incrementAndGet();
                }
            });
            return null;
        };

        try {
            Future<Void> first = executor.submit(booking);
            Future<Void> second = executor.submit(booking);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, successfulBookings.get());
        assertEquals(0, jdbc.queryForObject(
                "SELECT remaining_slots FROM booking_branch_lock WHERE branch_id=1", Integer.class));
    }

    @Test
    void productionBookingLockRepositoryUsesPessimisticWrite() throws Exception {
        Method method = AppointmentBookingLockRepository.class
                .getMethod("findByIdForBooking", Long.class);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, method.getAnnotation(Lock.class).value());
    }
}
