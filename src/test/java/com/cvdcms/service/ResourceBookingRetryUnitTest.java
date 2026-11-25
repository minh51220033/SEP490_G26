package com.cvdcms.service;

import com.cvdcms.DTO.availability.ResourceBookingRequest;
import com.cvdcms.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResourceBookingRetryUnitTest {

    private ResourceBookingTransactionService createServiceWithMocks(
            PlatformTransactionManager txManager,
            ServiceRepository serviceRepo) {
        return new ResourceBookingTransactionService(
                txManager,
                Mockito.mock(PatientProfileRepository.class),
                Mockito.mock(BranchRepository.class),
                serviceRepo,
                Mockito.mock(StaffProfileRepository.class),
                Mockito.mock(StaffScheduleRepository.class),
                Mockito.mock(DoctorShiftAssignmentRepository.class),
                Mockito.mock(DoctorRoomAssignmentRepository.class),
                Mockito.mock(DoctorServicePermissionRepository.class),
                Mockito.mock(LeaveRequestRepository.class),
                Mockito.mock(RoomRepository.class),
                Mockito.mock(DentalChairRepository.class),
                Mockito.mock(ClinicEquipmentRepository.class),
                Mockito.mock(ServiceResourceRequirementRepository.class),
                Mockito.mock(AppointmentRepository.class),
                Mockito.mock(AppointmentResourceReservationRepository.class),
                Mockito.mock(BranchTransitionRuleService.class));
    }

    @Test
    @DisplayName("Retry tối đa 2 lần (tổng 3 attempts) khi gặp PessimisticLockingFailureException, mỗi lần mở transaction mới")
    void retriesAtMostTwiceOnPessimisticLockingFailure() {
        PlatformTransactionManager txManager = Mockito.mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(inv -> new SimpleTransactionStatus());

        ServiceRepository serviceRepo = Mockito.mock(ServiceRepository.class);
        AtomicInteger attempts = new AtomicInteger(0);
        when(serviceRepo.findById(1L)).thenAnswer(inv -> {
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("Simulated MySQL lock timeout");
        });

        ResourceBookingTransactionService service = createServiceWithMocks(txManager, serviceRepo);
        ResourceBookingRequest req = ResourceBookingRequest.newBooking(
                1L, 1L, 1L, LocalDate.now().plusDays(1), LocalTime.of(9, 0), 1L, false, "K-RETRY-1");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.bookWithRetry(req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(3, attempts.get(), "Phải thử đúng 1 + 2 = 3 attempts");
        verify(txManager, times(3)).getTransaction(any());
        verify(txManager, times(3)).rollback(any(TransactionStatus.class));
        verify(txManager, never()).commit(any());
    }

    @Test
    @DisplayName("Không retry lỗi nghiệp vụ (404/409/IllegalArgument) - chỉ chạy đúng 1 attempt")
    void doesNotRetryBusinessOrValidationErrors() {
        PlatformTransactionManager txManager = Mockito.mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(inv -> new SimpleTransactionStatus());

        ServiceRepository serviceRepo = Mockito.mock(ServiceRepository.class);
        when(serviceRepo.findById(99L)).thenReturn(Optional.empty());

        ResourceBookingTransactionService service = createServiceWithMocks(txManager, serviceRepo);
        ResourceBookingRequest req = ResourceBookingRequest.newBooking(
                1L, 1L, 99L, LocalDate.now().plusDays(1), LocalTime.of(9, 0), 1L, false, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.bookWithRetry(req));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(serviceRepo, times(1)).findById(99L);
        verify(txManager, times(1)).rollback(any(TransactionStatus.class));
    }

    @Test
    @DisplayName("Chặn fail-fast nếu bookWithRetry bị gọi bên trong transaction đang mở")
    void rejectsCallInsideActiveTransaction() {
        PlatformTransactionManager txManager = Mockito.mock(PlatformTransactionManager.class);
        ResourceBookingTransactionService service = createServiceWithMocks(txManager, Mockito.mock(ServiceRepository.class));
        ResourceBookingRequest req = ResourceBookingRequest.newBooking(
                1L, 1L, 1L, LocalDate.now().plusDays(1), LocalTime.of(9, 0), 1L, false, null);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.bookWithRetry(req));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
