package com.cvdcms.service;

import com.cvdcms.security.PortalAccess;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.ReceptionVisitRepository;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.AppointmentLifecycleServiceImpl;
import com.cvdcms.service.impl.AppointmentPolicyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử tích hợp JPA/H2 (transaction thật + PESSIMISTIC_WRITE) cho Mục VIII.2 và VIII.4:
 * - Chặn đặt nhiều lịch cùng buổi (cùng cơ sở & xuyên cơ sở), cho phép buổi khác, hồ sơ khác, đã hủy hoặc WAITING_PAYMENT.
 * - Chống race condition khi 2 request đồng thời đặt cùng buổi xuyên chi nhánh cho 1 hồ sơ.
 * - Nhiều hồ sơ cùng đặt 15:30 khi còn sức chứa và race condition tại chỗ cuối cùng của buổi.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:appt_cross_branch_concurrency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "appointments.branch-capacity=24",
        "appointments.doctor-capacity=10",
        "appointments.doctor-concurrent-per-slot=3"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PortalAccess.class,
        PortalAppointmentService.class,
        AppointmentPolicyServiceImpl.class,
        AppointmentLifecycleServiceImpl.class,
        AppointmentSlotService.class,
        com.cvdcms.service.impl.PatientProfileServiceImpl.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppointmentCrossBranchConcurrencyIntegrationTest {

    @jakarta.annotation.Resource PortalAppointmentService portalService;
    @jakarta.annotation.Resource AppointmentSlotService slotService;
    @jakarta.annotation.Resource AppointmentPolicyService policyService;
    @jakarta.annotation.Resource PatientProfileService patientProfileService;

    @jakarta.annotation.Resource UserRepository users;
    @jakarta.annotation.Resource RoleRepository roles;
    @jakarta.annotation.Resource BranchRepository branches;
    @jakarta.annotation.Resource PatientProfileRepository profiles;
    @jakarta.annotation.Resource AccountPatientProfileRepository accountLinks;
    @jakarta.annotation.Resource StaffProfileRepository staffProfiles;
    @jakarta.annotation.Resource StaffBranchAssignmentRepository branchAssignments;
    @jakarta.annotation.Resource WorkShiftRepository workShifts;
    @jakarta.annotation.Resource StaffScheduleRepository staffSchedules;
    @jakarta.annotation.Resource AppointmentSessionPolicyRepository sessionPolicies;
    @jakarta.annotation.Resource PortalAppointmentRepository appointments;
    @jakarta.annotation.Resource ReceptionVisitRepository visits;

    private User familyAccount;
    private PatientProfile profileA;
    private PatientProfile profileB;
    private Branch branch1;
    private Branch branch2;
    private StaffProfile docBranch1;
    private StaffProfile docBranch2;
    private LocalDate futureDate;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        futureDate = LocalDate.now(AppointmentPolicyService.CLINIC_ZONE).plusDays(5);

        Role doctorRole = roles.findByRoleName("BAC_SI").orElseGet(() -> {
            Role r = new Role(); r.setRoleName("BAC_SI"); r.setIsActive(true); return roles.saveAndFlush(r);
        });

        branch1 = createBranch("CN1_" + uid, "Cơ sở Quận 1 " + uid);
        branch2 = createBranch("CN2_" + uid, "Cơ sở Cầu Giấy " + uid);

        familyAccount = new User();
        familyAccount.setUsername("pat_" + uid);
        familyAccount.setPassword("secret");
        familyAccount.setEmail("pat_" + uid + "@test.local");
        familyAccount.setPhoneNumber("091" + uid.substring(0, 7));
        familyAccount.setUserType(UserType.PATIENT);
        familyAccount.setIsActive(true);
        familyAccount.setStatus("ACTIVE");
        familyAccount.setBranch(branch1);
        familyAccount = users.saveAndFlush(familyAccount);

        profileA = createLinkedProfile("BNA_" + uid, "Đinh Xuân Hiếu", "0981" + uid.substring(0, 6), branch1, AccountPatientProfile.AccessType.SELF, true);
        profileB = createLinkedProfile("BNB_" + uid, "Đinh Xuân Hoàn", "0982" + uid.substring(0, 6), branch1, AccountPatientProfile.AccessType.DEPENDENT, false);

        docBranch1 = createDoctorWithSchedule("DOC1_" + uid, "BS. Trần Minh Hoàng", doctorRole, branch1);
        docBranch2 = createDoctorWithSchedule("DOC2_" + uid, "BS. Nguyễn Lan Anh", doctorRole, branch2);

        session = new MockHttpSession();
        session.setAttribute("loggedInUser", familyAccount);
    }

    @Test
    @DisplayName("VIII.2: Bao phủ đầy đủ 8 kịch bản chống đặt nhiều lịch cùng buổi toàn hệ thống")
    void singleActiveBookingPerProfilePerSessionAcrossAllBranchesAndStatuses() {
        // 1. Hồ sơ A đặt buổi sáng tại CN1 -> Thành công
        var amBranch1 = portalService.book(session, new PortalAppointmentService.BookingRequest(
                profileA.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.MORNING,
                docBranch1.getStaffId(), null, "Khám tổng quát", UUID.randomUUID().toString(), LocalTime.of(9, 0)));
        assertEquals(AppointmentStatus.PENDING, amBranch1.status());

        // 2. Hồ sơ A đặt tiếp buổi sáng tại CN1 -> Bị từ chối 409 CONFLICT
        ResponseStatusException dupSameBranch = assertThrows(ResponseStatusException.class, () ->
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileA.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.MORNING,
                        null, null, "Đặt thêm sáng CN1", UUID.randomUUID().toString(), LocalTime.of(10, 0))));
        assertTrue(dupSameBranch.getReason().contains("đã có lịch hẹn"));

        // 3. Hồ sơ A đặt tiếp buổi sáng tại CN2 -> Bị từ chối 409 CONFLICT (kiểm tra xuyên cơ sở)
        ResponseStatusException dupCrossBranch = assertThrows(ResponseStatusException.class, () ->
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileA.getProfileId(), branch2.getBranchId(), futureDate, PreferredSession.MORNING,
                        docBranch2.getStaffId(), null, "Đặt thêm sáng CN2", UUID.randomUUID().toString(), LocalTime.of(9, 30))));
        assertTrue(dupCrossBranch.getReason().contains("đã có lịch hẹn"));

        // 4. Hồ sơ A đặt buổi CHIỀU cùng ngày khi lịch sáng chưa hoàn thành -> Bị từ chối 409 CONFLICT (quy tắc cả ngày)
        ResponseStatusException dupDifferentSession = assertThrows(ResponseStatusException.class, () ->
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileA.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.AFTERNOON,
                        docBranch1.getStaffId(), null, "Khám chiều", UUID.randomUUID().toString(), LocalTime.of(15, 30))));
        assertTrue(dupDifferentSession.getReason().contains("chưa hoàn thành"));

        // 5. Hồ sơ B (cùng tài khoản gia đình!) đặt buổi sáng tại CN1 -> Cho phép (không khóa nhầm hồ sơ khác)
        var profBMorning = portalService.book(session, new PortalAppointmentService.BookingRequest(
                profileB.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.MORNING,
                docBranch1.getStaffId(), null, "Khám cho người thân", UUID.randomUUID().toString(), LocalTime.of(9, 0)));
        assertEquals(profileB.getProfileId(), profBMorning.profileId());

        // 6. Chuyển lịch sáng CN1 của A sang CHECKED_IN với Visit đang WAITING (CALLED), IN_EXAM, WAITING_PAYMENT -> đều CHẶN
        Appointment apptA = appointments.findById(amBranch1.id()).orElseThrow();
        apptA.setStatus(AppointmentStatus.CONFIRMED);
        appointments.saveAndFlush(apptA);
        apptA.setStatus(AppointmentStatus.CHECKED_IN);
        appointments.saveAndFlush(apptA);

        Visit activeVisit = new Visit();
        activeVisit.setVisitCode("VIS_" + System.nanoTime());
        activeVisit.setPatientProfile(profileA);
        activeVisit.setBranch(branch1);
        activeVisit.setAppointmentId(apptA.getAppointmentId());
        activeVisit.setVisitType(VisitType.APPOINTMENT);
        activeVisit.setStatus(VisitStatus.WAITING);
        visits.saveAndFlush(activeVisit);

        assertThrows(ResponseStatusException.class, () ->
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileA.getProfileId(), branch2.getBranchId(), futureDate, PreferredSession.AFTERNOON,
                        null, null, "Đặt khi đang WAITING/CALLED", UUID.randomUUID().toString(), LocalTime.of(15, 0))));

        activeVisit.setStatus(VisitStatus.IN_EXAM);
        visits.saveAndFlush(activeVisit);
        assertThrows(ResponseStatusException.class, () ->
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileA.getProfileId(), branch2.getBranchId(), futureDate, PreferredSession.MORNING,
                        null, null, "Đặt khi đang IN_EXAM", UUID.randomUUID().toString(), LocalTime.of(10, 30))));

        activeVisit.setStatus(VisitStatus.WAITING_PAYMENT);
        activeVisit.setActualEndTime(futureDate.atTime(9, 20));
        visits.saveAndFlush(activeVisit);
        assertThrows(ResponseStatusException.class, () ->
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileA.getProfileId(), branch2.getBranchId(), futureDate, PreferredSession.MORNING,
                        null, null, "Đặt khi đang WAITING_PAYMENT chưa thanh toán xong", UUID.randomUUID().toString(), LocalTime.of(10, 30))));

        // 7. Khi Visit thực sự COMPLETED -> CHO PHÉP đặt lịch thứ 2 tại CN2 trong cùng ngày
        activeVisit.setStatus(VisitStatus.COMPLETED);
        visits.saveAndFlush(activeVisit);
        apptA.setStatus(AppointmentStatus.COMPLETED);
        appointments.saveAndFlush(apptA);

        var secondBooking = portalService.book(session, new PortalAppointmentService.BookingRequest(
                profileA.getProfileId(), branch2.getBranchId(), futureDate, PreferredSession.MORNING,
                null, null, "Lượt 2 sau khi hoàn thành lượt 1", UUID.randomUUID().toString(), LocalTime.of(10, 30)));
        assertNotNull(secondBooking.id());

        // 8. Lịch thứ 2 chưa hoàn thành -> đặt lịch thứ 3 phải bị CHẶN
        assertThrows(ResponseStatusException.class, () ->
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileA.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.AFTERNOON,
                        null, null, "Lượt 3 khi lượt 2 chưa xong", UUID.randomUUID().toString(), LocalTime.of(14, 0))));

        // 9. Hoàn thành lịch thứ 2 -> CHO PHÉP đặt lịch thứ 3 trong cùng ngày
        Appointment appt2 = appointments.findById(secondBooking.id()).orElseThrow();
        appt2.setStatus(AppointmentStatus.COMPLETED);
        appointments.saveAndFlush(appt2);

        var thirdBooking = portalService.book(session, new PortalAppointmentService.BookingRequest(
                profileA.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.AFTERNOON,
                null, null, "Lượt 3 sau khi hoàn thành lượt 2", UUID.randomUUID().toString(), LocalTime.of(14, 0)));
        assertNotNull(thirdBooking.id());

        // 10. Hủy lịch thứ 3 (CANCELLED) -> giải phóng quyền đặt lịch -> đặt lại thành công
        portalService.cancel(session, thirdBooking.id(),
                new PortalAppointmentService.CancelRequest("Đổi kế hoạch"));
        var rebookedAfterCancel = portalService.book(session, new PortalAppointmentService.BookingRequest(
                profileA.getProfileId(), branch2.getBranchId(), futureDate, PreferredSession.MORNING,
                null, null, "Đặt lại sau khi hủy", UUID.randomUUID().toString(), LocalTime.of(11, 0)));
        assertNotNull(rebookedAfterCancel.id());

        // 11. NO_SHOW -> giải phóng quyền đặt lịch -> đặt tiếp thành công
        Appointment apptNoShow = appointments.findById(rebookedAfterCancel.id()).orElseThrow();
        apptNoShow.setStatus(AppointmentStatus.NO_SHOW);
        appointments.saveAndFlush(apptNoShow);
        var rebookedAfterNoShow = portalService.book(session, new PortalAppointmentService.BookingRequest(
                profileA.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.AFTERNOON,
                null, null, "Đặt lại sau NO_SHOW", UUID.randomUUID().toString(), LocalTime.of(15, 0)));
        assertNotNull(rebookedAfterNoShow.id());

        // 12. Đổi lịch (reschedule): đổi chính lịch đó sang buổi khác vẫn cho phép (excludeAppointmentId),
        //     nhưng đổi một lịch khác sang chi nhánh B vào ngày đã có lịch chưa hoàn thành -> CHẶN
        var rescheduled = portalService.reschedule(session, rebookedAfterNoShow.id(),
                new PortalAppointmentService.RescheduleRequest(
                        futureDate, PreferredSession.MORNING, null, "Đổi sang buổi sáng", LocalTime.of(11, 0)));
        assertEquals(PreferredSession.MORNING, rescheduled.session());
        assertThrows(ResponseStatusException.class, () ->
                policyService.ensureBookable(branch2, profileA, futureDate, PreferredSession.AFTERNOON,
                        null, 999999L, AppointmentActorType.RECEPTIONIST));

        // 13. Chuyển tiếp ngày (23:59 ngày D sang 00:00 ngày D+1 theo múi giờ Việt Nam):
        //     Lịch chưa hoàn thành ở futureDate KHÔNG chặn đặt lịch ở futureDate.plusDays(1)
        LocalDate nextDay = futureDate.plusDays(1);
        var conflictNextDay = policyService.findSameSessionConflict(
                profileA.getProfileId(), nextDay, PreferredSession.MORNING, null);
        assertFalse(conflictNextDay.hasConflict(),
                "Lịch chưa hoàn thành ngày D (23:59) không được chặn ngày D+1 (00:00) theo múi giờ VN");
    }

    @Test
    @DisplayName("VIII.2: Hai request đồng thời đặt cùng buổi (CN1 và CN2) cho cùng hồ sơ A -> chỉ 1 thành công")
    void concurrentCrossBranchBookingForSameProfileAllowsOnlyOne() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        Callable<Void> bookCN1 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileA.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.MORNING,
                        null, null, "Tab 1 CN1", UUID.randomUUID().toString(), LocalTime.of(9, 0)));
                successCount.incrementAndGet();
            } catch (Exception ex) {
                conflictCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> bookCN2 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileA.getProfileId(), branch2.getBranchId(), futureDate, PreferredSession.MORNING,
                        null, null, "Tab 2 CN2", UUID.randomUUID().toString(), LocalTime.of(9, 0)));
                successCount.incrementAndGet();
            } catch (Exception ex) {
                conflictCount.incrementAndGet();
            }
            return null;
        };

        try {
            Future<Void> f1 = executor.submit(bookCN1);
            Future<Void> f2 = executor.submit(bookCN2);
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, successCount.get(), "Chỉ đúng 1 lịch hẹn được phép tạo cho cùng hồ sơ trong cùng buổi");
        assertEquals(1, conflictCount.get(), "Request còn lại phải bị khóa và từ chối");
    }

    @Test
    @DisplayName("VIII.4: Nhiều bệnh nhân cùng chọn 15:30 khi còn sức chứa & tranh chấp chỗ cuối chỉ 1 request thành công")
    void sharedArrivalTime1530AndConcurrentLastSlotRace() throws Exception {
        // Hồ sơ A đặt 15:30 ca chiều tại CN1 với BS 1
        var first = portalService.book(session, new PortalAppointmentService.BookingRequest(
                profileA.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.AFTERNOON,
                docBranch1.getStaffId(), null, "Người 1 lúc 15:30", UUID.randomUUID().toString(), LocalTime.of(15, 30)));
        assertEquals(LocalTime.of(15, 30), first.expectedTime());

        // Mở lại danh sách giờ trống: 15:30 VẪN HIỂN THỊ với số chỗ giảm 3 -> 2
        var afterFirst = slotService.available(branch1, futureDate, PreferredSession.AFTERNOON,
                docBranch1.getStaffId(), 30, null, AppointmentActorType.PATIENT);
        var slot1530 = afterFirst.stream().filter(s -> s.time().equals(LocalTime.of(15, 30))).findFirst().orElseThrow();
        assertEquals(2, slot1530.remaining(), "Sau khi 1 người đặt, 15:30 vẫn còn 2 suất cùng giờ");

        // Hồ sơ B vẫn đặt được đúng 15:30
        var second = portalService.book(session, new PortalAppointmentService.BookingRequest(
                profileB.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.AFTERNOON,
                docBranch1.getStaffId(), null, "Người 2 cùng lúc 15:30", UUID.randomUUID().toString(), LocalTime.of(15, 30)));
        assertEquals(LocalTime.of(15, 30), second.expectedTime());

        // Đặt giới hạn sức chứa buổi chiều ngày futureDate+1 còn đúng 1 chỗ (branchCapacity = 1) để kiểm thử concurrency last-slot
        LocalDate raceDate = futureDate.plusDays(1);
        AppointmentSessionPolicy tightPolicy = new AppointmentSessionPolicy();
        tightPolicy.setBranch(branch1);
        tightPolicy.setAppointmentDate(raceDate);
        tightPolicy.setPreferredSession(PreferredSession.AFTERNOON);
        tightPolicy.setBranchCapacity(1);
        tightPolicy.setDoctorCapacity(1);
        sessionPolicies.saveAndFlush(tightPolicy);

        // Cấp lịch trực cho BS1 vào raceDate
        StaffSchedule schRace = new StaffSchedule();
        schRace.setStaff(docBranch1);
        schRace.setBranch(branch1);
        schRace.setShift(workShifts.findAll().stream()
                .filter(w -> w.getBranch().getBranchId().equals(branch1.getBranchId()) && w.getShiftCode().startsWith("PM"))
                .findFirst().orElseThrow());
        schRace.setWorkDate(raceDate);
        schRace.setStatus(ScheduleStatus.SCHEDULED);
        staffSchedules.saveAndFlush(schRace);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger losses = new AtomicInteger();

        Callable<Void> racerA = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileA.getProfileId(), branch1.getBranchId(), raceDate, PreferredSession.AFTERNOON,
                        null, null, "Racer A", UUID.randomUUID().toString(), LocalTime.of(15, 30)));
                wins.incrementAndGet();
            } catch (Exception e) {
                losses.incrementAndGet();
            }
            return null;
        };

        Callable<Void> racerB = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                portalService.book(session, new PortalAppointmentService.BookingRequest(
                        profileB.getProfileId(), branch1.getBranchId(), raceDate, PreferredSession.AFTERNOON,
                        null, null, "Racer B", UUID.randomUUID().toString(), LocalTime.of(15, 30)));
                wins.incrementAndGet();
            } catch (Exception e) {
                losses.incrementAndGet();
            }
            return null;
        };

        try {
            Future<Void> fa = executor.submit(racerA);
            Future<Void> fb = executor.submit(racerB);
            fa.get(15, TimeUnit.SECONDS);
            fb.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, wins.get(), "Chỉ đúng 1 người lấy được chỗ cuối cùng của buổi");
        assertEquals(1, losses.get(), "Người thứ hai phải bị từ chối khi hết sức chứa");
    }

    @Test
    @DisplayName("I & III (H2): Nhiều bệnh nhân cùng chọn 16:00 giữ nguyên 16:00-16:30 & chuyển cơ sở trực tiếp đồng bộ toàn bộ hồ sơ, không sửa lịch cũ")
    void sharedArrivalAt1600AndSwitchBranchPreservesHistoricalAppointmentsAndVisits() {
        var first = portalService.book(session, new PortalAppointmentService.BookingRequest(
                profileA.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.AFTERNOON,
                docBranch1.getStaffId(), null, "Khách 1 lúc 16:00", UUID.randomUUID().toString(), LocalTime.of(16, 0)));
        assertEquals(LocalTime.of(16, 0), first.expectedTime());

        var afterFirst = slotService.available(branch1, futureDate, PreferredSession.AFTERNOON,
                docBranch1.getStaffId(), 30, null, AppointmentActorType.PATIENT);
        var slot1600 = afterFirst.stream().filter(s -> s.time().equals(LocalTime.of(16, 0))).findFirst().orElseThrow();
        assertEquals(2, slot1600.remaining(), "16:00 phải giảm sức chứa 3 -> 2 để người thứ 2 cùng đặt được");

        var second = portalService.book(session, new PortalAppointmentService.BookingRequest(
                profileB.getProfileId(), branch1.getBranchId(), futureDate, PreferredSession.AFTERNOON,
                docBranch1.getStaffId(), null, "Khách 2 cùng lúc 16:00", UUID.randomUUID().toString(), LocalTime.of(16, 0)));
        assertEquals(LocalTime.of(16, 0), second.expectedTime());

        Appointment savedFirst = appointments.findById(first.id()).orElseThrow();
        assertEquals(LocalTime.of(16, 0), savedFirst.getExpectedTime());
        assertEquals(LocalTime.of(16, 30), savedFirst.getExpectedEndTime(), "Thời lượng thực tế 30p -> 16:30, không cộng ±15p vào slot");

        Visit oldVisit = new Visit();
        oldVisit.setVisitCode("VS1600_" + savedFirst.getAppointmentId());
        oldVisit.setAppointmentId(savedFirst.getAppointmentId());
        oldVisit.setBranch(branch1);
        oldVisit.setPatientProfile(profileA);
        oldVisit.setPrimaryDoctor(docBranch1);
        oldVisit.setStatus(VisitStatus.WAITING);
        oldVisit.setCheckInAt(LocalDateTime.of(futureDate, LocalTime.of(16, 5)));
        oldVisit = visits.saveAndFlush(oldVisit);

        // Chuyển cơ sở ưa thích từ CN1 sang CN2
        var switchResult = patientProfileService.switchUserPreferredBranch(familyAccount.getUserId(), branch2.getBranchId());
        assertEquals(branch2.getBranchId(), switchResult.branch().getBranchId());

        User reloadedUser = users.findById(familyAccount.getUserId()).orElseThrow();
        PatientProfile reloadedA = profiles.findById(profileA.getProfileId()).orElseThrow();
        PatientProfile reloadedB = profiles.findById(profileB.getProfileId()).orElseThrow();
        assertEquals(branch2.getBranchId(), reloadedUser.getBranch().getBranchId());
        assertEquals(branch2.getBranchId(), reloadedA.getPreferredBranch().getBranchId());
        assertEquals(branch2.getBranchId(), reloadedB.getPreferredBranch().getBranchId());

        // Dữ liệu lịch sử (Appointment cũ & Visit cũ tại CN1) giữ nguyên chi nhánh gốc CN1
        Appointment reloadedAppt = appointments.findById(savedFirst.getAppointmentId()).orElseThrow();
        Visit reloadedVisit = visits.findById(oldVisit.getVisitId()).orElseThrow();
        assertEquals(branch1.getBranchId(), reloadedAppt.getBranch().getBranchId());
        assertEquals(branch1.getBranchId(), reloadedVisit.getBranch().getBranchId());
    }

    private Branch createBranch(String code, String name) {
        Branch b = new Branch();
        b.setBranchCode(code);
        b.setBranchName(name);
        b.setAddress("123 Test Street");
        b.setOpeningTime(LocalTime.of(8, 0));
        b.setClosingTime(LocalTime.of(20, 0));
        b.setWorkingDays("2,3,4,5,6,7,CN");
        b.setIsActive(true);
        b = branches.saveAndFlush(b);

        WorkShift am = new WorkShift();
        am.setBranch(b); am.setShiftCode("AM_" + code); am.setShiftName("Ca Sáng");
        am.setStartTime(LocalTime.of(8, 0)); am.setEndTime(LocalTime.of(12, 0)); am.setIsActive(true);
        workShifts.saveAndFlush(am);

        WorkShift pm = new WorkShift();
        pm.setBranch(b); pm.setShiftCode("PM_" + code); pm.setShiftName("Ca Chiều");
        pm.setStartTime(LocalTime.of(13, 30)); pm.setEndTime(LocalTime.of(17, 30)); pm.setIsActive(true);
        workShifts.saveAndFlush(pm);
        return b;
    }

    private PatientProfile createLinkedProfile(
            String code, String name, String phone, Branch branch,
            AccountPatientProfile.AccessType accessType, boolean isDefault) {
        PatientProfile p = new PatientProfile();
        p.setUser(familyAccount);
        p.setPatientCode(code);
        p.setFullName(name);
        p.setGender("Nam");
        p.setPhoneNumber(phone);
        p.setAddress("HCM");
        p.setPreferredBranch(branch);
        p.setIsDefault(isDefault);
        p.setStatus("ACTIVE");
        p = profiles.saveAndFlush(p);

        AccountPatientProfile link = new AccountPatientProfile();
        link.setAccount(familyAccount);
        link.setProfile(p);
        link.setAccessType(accessType);
        accountLinks.saveAndFlush(link);
        return p;
    }

    private StaffProfile createDoctorWithSchedule(String code, String name, Role doctorRole, Branch branch) {
        User docUser = new User();
        docUser.setUsername("u_" + code);
        docUser.setPassword("secret");
        docUser.setEmail("u_" + code + "@test.local");
        docUser.setPhoneNumber("097" + String.format("%07d", Math.abs(code.hashCode() % 10000000)));
        docUser.setUserType(UserType.STAFF);
        docUser.setIsActive(true);
        docUser.setStatus("ACTIVE");
        docUser.setBranch(branch);
        docUser = users.saveAndFlush(docUser);

        StaffProfile sp = new StaffProfile();
        sp.setUser(docUser);
        sp.setStaffCode(code);
        sp.setFullName(name);
        sp.setStatus(StaffStatus.ACTIVE);
        sp.setBaseSalary(new BigDecimal("20000000"));
        sp = staffProfiles.saveAndFlush(sp);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(branch);
        sba.setRole(doctorRole);
        sba.setIsPrimary(true);
        sba.setStartDate(futureDate.minusMonths(1));
        sba.setStatus(AssignmentStatus.ACTIVE);
        branchAssignments.saveAndFlush(sba);

        for (WorkShift ws : workShifts.findAll()) {
            if (ws.getBranch() != null && branch.getBranchId().equals(ws.getBranch().getBranchId())) {
                StaffSchedule sch = new StaffSchedule();
                sch.setStaff(sp);
                sch.setBranch(branch);
                sch.setShift(ws);
                sch.setWorkDate(futureDate);
                sch.setStatus(ScheduleStatus.SCHEDULED);
                staffSchedules.saveAndFlush(sch);
            }
        }
        return sp;
    }
}
