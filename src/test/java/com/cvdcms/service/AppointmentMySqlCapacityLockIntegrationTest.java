package com.cvdcms.service;

import com.cvdcms.entity.Branch;
import com.cvdcms.repository.AppointmentBookingLockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Read-only MySQL verification of the production branch-lock repository. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@EnabledIfSystemProperty(named = "appointment.mysql.integration", matches = "true")
class AppointmentMySqlCapacityLockIntegrationTest {
    @Autowired AppointmentBookingLockRepository locks;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;

    @Test
    void productionMySqlLockSerializesTwoRequestsForTheLastSlot() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql"));
        }
        List<Branch> branches = locks.findAll();
        assumeFalse(branches.isEmpty(), "MySQL integration database needs at least one branch");
        Long branchId = branches.get(0).getBranchId();
        AtomicInteger remainingSlots = new AtomicInteger(1);
        AtomicInteger successfulBookings = new AtomicInteger();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> booking = () -> {
            startTogether.await(5, TimeUnit.SECONDS);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                locks.findByIdForBooking(branchId).orElseThrow();
                if (remainingSlots.get() > 0) {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    remainingSlots.decrementAndGet();
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
        assertEquals(0, remainingSlots.get());
    }
}
