package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.AppointmentHistory;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AppointmentSource;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.entity.enums.UserType;
import com.cvdcms.patientportal.PortalAccess;
import com.cvdcms.patientportal.PortalAppointmentService;
import com.cvdcms.patientportal.PortalBookingRequestRepository;
import com.cvdcms.reception.ReceptionAccess;
import com.cvdcms.reception.ReceptionCheckInAppointmentRepository;
import com.cvdcms.reception.ReceptionCheckInKeyRepository;
import com.cvdcms.reception.ReceptionService;
import com.cvdcms.reception.ReceptionVisitRepository;
import com.cvdcms.repository.AppointmentHistoryRepository;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.impl.AppointmentLifecycleServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:appointment_phase3;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReceptionService.class, PortalAppointmentService.class,
        AppointmentLifecycleServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppointmentPhase3IntegrationTest {

    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @MockitoBean ReceptionAccess access;
    @MockitoBean PortalAccess portalAccess;
    @MockitoBean AppointmentPolicyService policy;
    @MockitoBean ObjectMapper objectMapper;

    @jakarta.annotation.Resource ReceptionService receptionService;
    @jakarta.annotation.Resource PortalAppointmentService portalAppointmentService;
    @jakarta.annotation.Resource UserRepository users;
    @jakarta.annotation.Resource BranchRepository branches;
    @jakarta.annotation.Resource PatientProfileRepository profiles;
    @jakarta.annotation.Resource ReceptionCheckInAppointmentRepository appointments;
    @jakarta.annotation.Resource ReceptionVisitRepository visits;
    @jakarta.annotation.Resource ReceptionCheckInKeyRepository keys;
    @jakarta.annotation.Resource AppointmentHistoryRepository histories;
    @jakarta.annotation.Resource PortalBookingRequestRepository bookingRequests;

    @BeforeEach
    void resetMocks() throws Exception {
        reset(access, portalAccess, policy, objectMapper);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    }

    @Test
    void pendingAppointmentConfirmsAndChecksInThroughRealJpaTransaction() {
        Fixture fixture = fixture(AppointmentStatus.PENDING);

        ReceptionService.VisitResult result = receptionService.confirmAndCheckIn(
                new MockHttpSession(), fixture.appointmentId(), "Đau răng khi nhai",
                UUID.randomUUID().toString());

        Appointment saved = appointments.findById(fixture.appointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.CHECKED_IN, saved.getStatus());
        assertEquals(fixture.appointmentId(), visits.findById(result.id()).orElseThrow().getAppointmentId());
        assertEquals(List.of("CONFIRM", "CHECK_IN"), histories
                .findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(fixture.appointmentId())
                .stream().map(AppointmentHistory::getAction).toList());
    }

    @Test
    void auditFailureRollsBackAppointmentVisitKeyAndHistory() throws Exception {
        Fixture fixture = fixture(AppointmentStatus.PENDING);
        String requestKey = UUID.randomUUID().toString();
        when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("audit failure") { });

        assertThrows(IllegalStateException.class, () -> receptionService.confirmAndCheckIn(
                new MockHttpSession(), fixture.appointmentId(), "Đau răng khi nhai",
                requestKey));

        assertEquals(AppointmentStatus.PENDING,
                appointments.findById(fixture.appointmentId()).orElseThrow().getStatus());
        assertEquals(0, visitCountFor(fixture.appointmentId()));
        assertTrue(histories.findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(
                fixture.appointmentId()).isEmpty());
        assertEquals(0, keys.findAll().stream()
                .filter(key -> requestKey.equals(key.getRequestKey())).count());
    }

    @Test
    void concurrentDoubleClickCreatesOnlyOneVisit() throws Exception {
        Fixture fixture = fixture(AppointmentStatus.CONFIRMED);
        String requestKey = UUID.randomUUID().toString();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var task = (java.util.concurrent.Callable<ReceptionService.VisitResult>) () -> {
                ready.countDown();
                start.await();
                return receptionService.checkInAppointment(new MockHttpSession(), fixture.appointmentId(),
                        "Đau răng khi nhai", requestKey);
            };
            Future<ReceptionService.VisitResult> first = executor.submit(task);
            Future<ReceptionService.VisitResult> second = executor.submit(task);
            ready.await();
            start.countDown();

            assertEquals(first.get().id(), second.get().id());
            assertEquals(1, visitCountFor(fixture.appointmentId()));
            assertEquals(1, keys.findAll().stream()
                    .filter(key -> requestKey.equals(key.getRequestKey())).count());
            assertEquals(1, histories
                    .findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(fixture.appointmentId())
                    .stream().filter(h -> "CHECK_IN".equals(h.getAction())).count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void directPendingToCheckedInRemainsRejected() {
        Fixture fixture = fixture(AppointmentStatus.PENDING);

        assertThrows(ResponseStatusException.class, () -> receptionService.checkInAppointment(
                new MockHttpSession(), fixture.appointmentId(), "Đau răng",
                UUID.randomUUID().toString()));

        assertEquals(AppointmentStatus.PENDING,
                appointments.findById(fixture.appointmentId()).orElseThrow().getStatus());
        assertEquals(0, visitCountFor(fixture.appointmentId()));
    }

    @Test
    void ft06OnlineBookingIsPendingIdempotentAndRejectsKeyReuseForDifferentContent() {
        Fixture fixture = fixture(AppointmentStatus.COMPLETED);
        String requestKey = UUID.randomUUID().toString();
        LocalDate date = LocalDate.now(CLINIC_ZONE).plusDays(1);
        var request = new PortalAppointmentService.BookingRequest(fixture.profileId(), fixture.branchId(),
                date, PreferredSession.MORNING, null, null, "Đau răng", requestKey);

        var first = portalAppointmentService.book(new MockHttpSession(), request);
        var repeated = portalAppointmentService.book(new MockHttpSession(), request);

        assertEquals(first.id(), repeated.id());
        assertEquals(AppointmentStatus.PENDING, first.status());
        assertEquals(1, bookingRequests.findAll().stream()
                .filter(item -> requestKey.equals(item.getRequestKey())).count());

        var conflicting = new PortalAppointmentService.BookingRequest(fixture.profileId(), fixture.branchId(),
                date, PreferredSession.MORNING, null, null, "Nội dung khác", requestKey);
        assertThrows(ResponseStatusException.class, () ->
                portalAppointmentService.book(new MockHttpSession(), conflicting));
    }

    @Test
    void ft06PatientRescheduleConfirmedAppointmentReturnsToPending() {
        Fixture fixture = fixture(AppointmentStatus.CONFIRMED);
        LocalDate newDate = LocalDate.now(CLINIC_ZONE).plusDays(3);

        var result = portalAppointmentService.reschedule(new MockHttpSession(), fixture.appointmentId(),
                new PortalAppointmentService.RescheduleRequest(
                        newDate, PreferredSession.AFTERNOON, null, "Bệnh nhân đổi kế hoạch"));

        assertEquals(AppointmentStatus.PENDING, result.status());
        assertEquals(newDate, result.date());
        assertEquals(1, result.rescheduleCount());
        assertEquals("RESCHEDULE", histories
                .findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(fixture.appointmentId())
                .get(0).getAction());
    }

    private Fixture fixture(AppointmentStatus status) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User actor = new User();
        actor.setUsername("phase3_" + token);
        actor.setPassword("test-only");
        actor.setEmail(token + "@phase3.test");
        actor.setPhoneNumber("09" + token.substring(0, 8));
        actor.setUserType(UserType.STAFF);
        actor = users.saveAndFlush(actor);

        Branch branch = new Branch();
        branch.setBranchCode("P3" + token);
        branch.setBranchName("Phase 3 " + token);
        branch.setAddress("Integration test");
        branch.setOpeningTime(LocalTime.of(7, 0));
        branch.setClosingTime(LocalTime.of(21, 0));
        branch.setWorkingDays("2,3,4,5,6,7,CN");
        branch = branches.saveAndFlush(branch);

        PatientProfile patient = new PatientProfile();
        patient.setUser(actor);
        patient.setPatientCode("BN" + token);
        patient.setFullName("Bệnh nhân Phase 3");
        patient.setGender("Khác");
        patient.setPhoneNumber("08" + token.substring(0, 8));
        patient.setAddress("Integration test");
        patient.setPreferredBranch(branch);
        patient = profiles.saveAndFlush(patient);

        Appointment appointment = new Appointment();
        appointment.setAppointmentCode("P3" + token);
        appointment.setPatientProfile(patient);
        appointment.setBranch(branch);
        appointment.setAppointmentDate(LocalDate.now(CLINIC_ZONE));
        appointment.setPreferredSession(PreferredSession.MORNING);
        appointment.setInitialSymptoms("Đau răng khi nhai");
        appointment.setSource(AppointmentSource.ONLINE);
        appointment.setStatus(status);
        appointment = appointments.saveAndFlush(appointment);

        when(access.require(any())).thenReturn(new ReceptionAccess.Scope(actor, true, List.of()));
        when(portalAccess.requireUser(any())).thenReturn(actor);
        when(portalAccess.requireProfile(eq(actor), eq(patient.getProfileId()))).thenReturn(patient);
        return new Fixture(actor.getUserId(), patient.getProfileId(), branch.getBranchId(),
                appointment.getAppointmentId());
    }

    private long visitCountFor(Long appointmentId) {
        return visits.findAll().stream()
                .filter(visit -> appointmentId.equals(visit.getAppointmentId()))
                .count();
    }

    private record Fixture(Long actorId, Long profileId, Long branchId, Long appointmentId) { }
}
