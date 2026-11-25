package com.cvdcms.service;

import com.cvdcms.DTO.PortalProfileView;
import com.cvdcms.security.PortalAccess;
import com.cvdcms.security.ReceptionAccess;

import com.cvdcms.controller.CustomerPortalController;
import com.cvdcms.controller.ProfileController;
import com.cvdcms.controller.PublicBookingRestController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.AppointmentLifecycleServiceImpl;
import com.cvdcms.service.impl.PatientProfileServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kiểm thử tổng hợp luồng đặt lịch, giờ hẹn (15:30 & 16:00), giao diện tinh gọn,
 * chống IDOR và chuyển cơ sở trực tiếp tại Trang chủ.
 */
class AppointmentFlowRemediationTest {
    private static final ZoneId ZONE = AppointmentPolicyService.CLINIC_ZONE;

    // =========================================================================
    // 1. LIÊN KẾT HỒ SƠ BỆNH NHÂN & CHỐNG SỬA THÔNG TIN / IDOR
    // =========================================================================

    @Test
    @DisplayName("Drawer context trả về danh sách hồ sơ thuộc tài khoản; ẩn hoàn toàn với khách")
    void bookingContextReturnsOwnedProfilesForLoggedInPatient() {
        Branch b1 = branch(1L, "Cơ sở Quận 1");
        User u = patientUser(100L, "hieudx", b1);
        PortalProfileView selfView = new PortalProfileView(10L, "BN001", null, "Đinh Xuân Hiếu",
                LocalDate.of(1999, 1, 1), "Nam", "0987654271", "HCM", 1L, "Cơ sở Quận 1",
                null, null, null, null, null, null, null, true, true, "ACTIVE");
        PortalProfileView depView = new PortalProfileView(11L, "BN002", null, "Đinh Xuân Hoàn",
                LocalDate.of(2019, 1, 28), "Nam", "0976583211", "HCM", 1L, "Cơ sở Quận 1",
                null, null, "Đinh Xuân Hiếu", "0987654271", "Con", null, null, false, false, "ACTIVE");

        BranchRepository branchRepo = mock(BranchRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        PortalPatientService portalPatientService = mock(PortalPatientService.class);
        PublicBookingRestController controller = new PublicBookingRestController(
                branchRepo, mock(ServiceRepository.class), mock(StaffProfileRepository.class),
                userRepo, mock(PatientProfileRepository.class), mock(AppointmentRepository.class),
                mock(AppointmentHistoryRepository.class), mock(RoleRepository.class), mock(PasswordEncoder.class),
                mock(AppointmentPolicyService.class), mock(AppointmentSlotService.class));
        ReflectionTestUtils.setField(controller, "portalPatientService", portalPatientService);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", u);
        when(userRepo.findById(100L)).thenReturn(Optional.of(u));
        when(portalPatientService.list(session)).thenReturn(List.of(depView, selfView));

        ResponseEntity<Map<String, Object>> resp = controller.bookingContext(session);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(Boolean.TRUE, resp.getBody().get("authenticated"));
        assertEquals(Boolean.TRUE, resp.getBody().get("isPatientUser"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) resp.getBody().get("profiles");
        assertEquals(2, profiles.size());
        assertEquals(11L, profiles.get(0).get("id"));
        assertEquals("Đinh Xuân Hoàn", profiles.get(0).get("fullName"));

        ResponseEntity<Map<String, Object>> guestResp = controller.bookingContext(new MockHttpSession());
        assertEquals(Boolean.FALSE, guestResp.getBody().get("authenticated"));
        assertTrue(((List<?>) guestResp.getBody().get("profiles")).isEmpty());
    }

    @Test
    @DisplayName("Đặt lịch cho người phụ thuộc lưu đúng profileId gốc, từ chối profileId tài khoản khác")
    void quickBookingLinksSelectedDependentProfileAndRejectsForeignProfile() {
        Branch b1 = branch(1L, "Cơ sở Quận 1");
        User account = patientUser(100L, "hieudx", b1);
        PatientProfile dep = profile(11L, account, "Đinh Xuân Hoàn", "0976583211", b1);

        BranchRepository branchRepo = mock(BranchRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        PatientProfileRepository profileRepo = mock(PatientProfileRepository.class);
        AppointmentRepository apptRepo = mock(AppointmentRepository.class);
        AppointmentPolicyService policyService = mock(AppointmentPolicyService.class);
        AppointmentSlotService slotService = mock(AppointmentSlotService.class);
        PortalAccess portalAccess = mock(PortalAccess.class);
        AppointmentBookingLockRepository lockRepo = mock(AppointmentBookingLockRepository.class);

        PublicBookingRestController controller = new PublicBookingRestController(
                branchRepo, mock(ServiceRepository.class), mock(StaffProfileRepository.class),
                userRepo, profileRepo, apptRepo, mock(AppointmentHistoryRepository.class),
                mock(RoleRepository.class), mock(PasswordEncoder.class), policyService, slotService);
        ReflectionTestUtils.setField(controller, "portalAccess", portalAccess);
        ReflectionTestUtils.setField(controller, "appointmentBookingLockRepository", lockRepo);

        LocalDate bookDate = LocalDate.now(ZONE).plusDays(1);
        when(branchRepo.findById(1L)).thenReturn(Optional.of(b1));
        when(userRepo.findById(100L)).thenReturn(Optional.of(account));
        when(lockRepo.findByIdForBooking(1L)).thenReturn(Optional.of(b1));
        when(portalAccess.requireProfile(account, 11L)).thenReturn(dep);
        when(portalAccess.requireProfile(account, 999L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(profileRepo.findByIdForUpdate(11L)).thenReturn(Optional.of(dep));
        when(slotService.resolveFlexibly(any(), eq(bookDate), eq(PreferredSession.AFTERNOON), isNull(), anyInt(), isNull(), eq(LocalTime.of(16, 0)), eq(AppointmentActorType.PATIENT)))
                .thenReturn(LocalTime.of(16, 0));
        when(apptRepo.saveAndFlush(any())).thenAnswer(inv -> {
            Appointment a = inv.getArgument(0);
            a.setAppointmentId(501L);
            return a;
        });

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", account);

        PublicBookingRestController.QuickBookingForm form = new PublicBookingRestController.QuickBookingForm();
        form.setBranchId(1L);
        form.setProfileId(11L);
        form.setAppointmentDate(bookDate.toString());
        form.setPreferredSession("AFTERNOON");
        form.setSlotTime("16:00");
        form.setFullName("Tên Bị Sửa Trên Form");
        form.setPhoneNumber("0999999999");

        ResponseEntity<Map<String, Object>> ok = controller.submitBooking(session, form);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertEquals(Boolean.TRUE, ok.getBody().get("success"));
        verify(apptRepo).saveAndFlush(argThat(a -> a.getPatientProfile().getProfileId().equals(11L)
                && "Đinh Xuân Hoàn".equals(a.getPatientProfile().getFullName())
                && LocalTime.of(16, 0).equals(a.getExpectedTime())));

        PublicBookingRestController.QuickBookingForm tampered = new PublicBookingRestController.QuickBookingForm();
        tampered.setBranchId(1L);
        tampered.setProfileId(999L);
        tampered.setAppointmentDate(bookDate.toString());
        tampered.setPreferredSession("AFTERNOON");
        tampered.setSlotTime("16:00");
        ResponseEntity<Map<String, Object>> rejected = controller.submitBooking(session, tampered);
        assertEquals(HttpStatus.FORBIDDEN, rejected.getStatusCode());
        assertEquals(Boolean.FALSE, rejected.getBody().get("success"));
    }

    // =========================================================================
    // 2. KIỂM THỬ MỐC GIỜ HẸN 15:30 VÀ 16:00 VỚI CLOCK CỐ ĐỊNH
    // =========================================================================

    @Test
    @DisplayName("Giờ hẹn 15:30 - Check-in đúng giờ trong [-15p, +15p], sớm <15p & muộn 16..30p mất ưu tiên, >30p NO_SHOW")
    void checkInAndNoShowBoundariesAt1530WithFixedClock() {
        LocalDate visitDate = LocalDate.of(2026, 11, 20);
        Branch b1 = branch(1L, "Cơ sở Quận 1");
        b1.setClosingTime(LocalTime.of(21, 0));
        User staff = new User();
        staff.setUserId(9L);

        LocalTime expected = LocalTime.of(15, 30);
        assertEquals(Boolean.FALSE, runCheckInAt(visitDate, expected, LocalTime.of(15, 14), b1, staff));
        assertEquals(Boolean.TRUE, runCheckInAt(visitDate, expected, LocalTime.of(15, 15), b1, staff));
        assertEquals(Boolean.TRUE, runCheckInAt(visitDate, expected, LocalTime.of(15, 30), b1, staff));
        assertEquals(Boolean.TRUE, runCheckInAt(visitDate, expected, LocalTime.of(15, 45), b1, staff));
        assertEquals(Boolean.FALSE, runCheckInAt(visitDate, expected, LocalTime.of(15, 46), b1, staff));
        assertEquals(Boolean.FALSE, runCheckInAt(visitDate, expected, LocalTime.of(16, 0), b1, staff));

        ResponseStatusException noShowEx = assertThrows(ResponseStatusException.class,
                () -> runCheckInAt(visitDate, expected, LocalTime.of(16, 1), b1, staff));
        assertEquals(HttpStatus.CONFLICT, noShowEx.getStatusCode());
        assertTrue(noShowEx.getReason().contains("30 phút"));

        assertNoShowCutoff(visitDate, expected, LocalTime.of(16, 0), LocalTime.of(16, 1), b1, staff);
    }

    @Test
    @DisplayName("Giờ hẹn 16:00 - Mốc biên 15:44 / 15:45 / 16:00 / 16:15 / 16:16 / 16:30 / 16:31 chính xác tuyệt đối")
    void checkInAndNoShowBoundariesAt1600WithFixedClock() {
        LocalDate visitDate = LocalDate.of(2026, 11, 20);
        Branch b1 = branch(1L, "Cơ sở Quận 1");
        b1.setClosingTime(LocalTime.of(21, 0));
        User staff = new User();
        staff.setUserId(9L);

        LocalTime expected = LocalTime.of(16, 0);
        assertEquals(Boolean.FALSE, runCheckInAt(visitDate, expected, LocalTime.of(15, 44), b1, staff),
                "15:44 (sớm 16 phút) KHÔNG thuộc nhóm đúng giờ");
        assertEquals(Boolean.TRUE, runCheckInAt(visitDate, expected, LocalTime.of(15, 45), b1, staff),
                "15:45 (biên -15 phút) PHẢI tính đúng giờ");
        assertEquals(Boolean.TRUE, runCheckInAt(visitDate, expected, LocalTime.of(16, 0), b1, staff),
                "16:00 (đúng giờ hẹn) PHẢI tính đúng giờ");
        assertEquals(Boolean.TRUE, runCheckInAt(visitDate, expected, LocalTime.of(16, 15), b1, staff),
                "16:15 (biên +15 phút) PHẢI tính đúng giờ");
        assertEquals(Boolean.FALSE, runCheckInAt(visitDate, expected, LocalTime.of(16, 16), b1, staff),
                "16:16 (trễ 16 phút) mất ưu tiên đúng giờ (Priority 4)");
        assertEquals(Boolean.FALSE, runCheckInAt(visitDate, expected, LocalTime.of(16, 30), b1, staff),
                "16:30 (biên trễ đúng 30 phút) vẫn check-in được theo nhóm muộn (Priority 4)");

        ResponseStatusException noShowEx = assertThrows(ResponseStatusException.class,
                () -> runCheckInAt(visitDate, expected, LocalTime.of(16, 31), b1, staff),
                "16:31 (quá 30 phút) bắt buộc từ chối check-in");
        assertEquals(HttpStatus.CONFLICT, noShowEx.getStatusCode());
        assertTrue(noShowEx.getReason().contains("30 phút"));

        assertNoShowCutoff(visitDate, expected, LocalTime.of(16, 30), LocalTime.of(16, 31), b1, staff);
    }

    @Test
    @DisplayName("API /api/public/booking/slots giữ mốc giờ đến 16:00 và đổi thời lượng dịch vụ 20/30/45/60 phút")
    void publicSlotsRespondWithArrivalTimesAcrossServiceDurations() {
        Branch b1 = branch(1L, "Cơ sở Quận 1");
        BranchRepository branchRepo = mock(BranchRepository.class);
        ServiceRepository svcRepo = mock(ServiceRepository.class);
        AppointmentSlotService slotSvc = mock(AppointmentSlotService.class);

        PublicBookingRestController controller = new PublicBookingRestController(
                branchRepo, svcRepo, mock(StaffProfileRepository.class),
                mock(UserRepository.class), mock(PatientProfileRepository.class), mock(AppointmentRepository.class),
                mock(AppointmentHistoryRepository.class), mock(RoleRepository.class), mock(PasswordEncoder.class),
                mock(AppointmentPolicyService.class), slotSvc);

        LocalDate date = LocalDate.now(ZONE).plusDays(2);
        when(branchRepo.findById(1L)).thenReturn(Optional.of(b1));

        for (int mins : List.of(20, 30, 45, 60)) {
            long svcId = mins;
            com.cvdcms.entity.Service svc = new com.cvdcms.entity.Service();
            svc.setServiceId(svcId);
            svc.setEstimatedDurationMinutes(mins);
            svc.setIsBookableOnline(true);
            svc.setStatus(ServiceStatus.ACTIVE);
            when(svcRepo.findById(svcId)).thenReturn(Optional.of(svc));

            LocalTime end = LocalTime.of(16, 0).plusMinutes(mins);
            when(slotSvc.available(b1, date, PreferredSession.AFTERNOON, 1L, mins, null, AppointmentActorType.PATIENT))
                    .thenReturn(List.of(new AppointmentSlotService.Slot(LocalTime.of(16, 0), 3)));

            ResponseEntity<List<Map<String, Object>>> resp = controller.getAvailableSlots(
                    1L, date, PreferredSession.AFTERNOON, 1L, svcId);
            List<Map<String, Object>> result = resp.getBody();
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("16:00", result.get(0).get("time"));
            assertEquals(end.toString().substring(0, 5), result.get(0).get("endTime"));
            assertEquals(3, result.get(0).get("remaining"));
        }
    }

    // =========================================================================
    // 3. CHUYỂN CƠ SỞ DÙNG CHUNG SERVICE (/portal/switch-branch/ajax & /profile/change-branch)
    // =========================================================================

    @Test
    @DisplayName("PatientProfileService.switchUserPreferredBranch cập nhật đồng bộ User + tất cả hồ sơ (direct & linked)")
    void switchUserPreferredBranchUpdatesUserAndAllDirectAndLinkedProfilesAtomically() {
        Branch b1 = branch(1L, "Cơ sở Quận 1");
        Branch b2 = branch(2L, "Cơ sở Cầu Giấy");
        Branch b3Inactive = branch(3L, "Cơ sở Ngừng");
        b3Inactive.setIsActive(false);

        User user = patientUser(100L, "hieudx", b1);
        PatientProfile p1 = profile(10L, user, "Đinh Xuân Hiếu", "0987654271", b1);
        p1.setIsDefault(true);
        PatientProfile p2 = profile(11L, user, "Đinh Xuân Hoàn", "0976583211", b1);
        p2.setIsDefault(false);

        PatientProfileRepository profileRepo = mock(PatientProfileRepository.class);
        BranchRepository branchRepo = mock(BranchRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        AccountPatientProfileRepository linkRepo = mock(AccountPatientProfileRepository.class);

        PatientProfileServiceImpl service = new PatientProfileServiceImpl(
                profileRepo, branchRepo, userRepo, linkRepo);

        when(userRepo.findById(100L)).thenReturn(Optional.of(user));
        when(branchRepo.findById(2L)).thenReturn(Optional.of(b2));
        when(branchRepo.findById(3L)).thenReturn(Optional.of(b3Inactive));
        when(branchRepo.findById(99L)).thenReturn(Optional.empty());
        when(profileRepo.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(100L)).thenReturn(List.of(p1));
        AccountPatientProfile depLink = new AccountPatientProfile();
        depLink.setAccount(user);
        depLink.setProfile(p2);
        depLink.setAccessType(AccountPatientProfile.AccessType.DEPENDENT);
        when(linkRepo.findByAccount_UserIdOrderByProfile_ProfileIdDesc(100L)).thenReturn(List.of(depLink));

        assertThrows(IllegalArgumentException.class, () -> service.switchUserPreferredBranch(100L, 3L));
        assertThrows(IllegalArgumentException.class, () -> service.switchUserPreferredBranch(100L, 99L));
        assertSame(b1, user.getBranch());
        assertSame(b1, p1.getPreferredBranch());
        assertSame(b1, p2.getPreferredBranch());

        PatientProfileService.BranchSwitchResult result = service.switchUserPreferredBranch(100L, 2L);
        assertSame(b2, result.branch());
        assertSame(b2, user.getBranch());
        assertSame(b2, p1.getPreferredBranch());
        assertSame(b2, p2.getPreferredBranch());
        assertEquals(2, result.syncedProfiles().size());
        assertSame(p1, result.defaultProfile());
        verify(userRepo).save(user);
        verify(profileRepo).saveAll(argThat(list -> ((Collection<?>) list).size() == 2));
    }

    @Test
    @DisplayName("POST /portal/switch-branch/ajax kiểm tra đăng nhập (401), cơ sở không hợp lệ (400), đổi thành công (200)")
    void customerPortalSwitchBranchAjaxEnforcesAuthValidationAndUpdatesSession() {
        CustomerPortalService portalService = mock(CustomerPortalService.class);
        ServiceCatalogService catalogService = mock(ServiceCatalogService.class);
        PatientProfileService patientProfileService = mock(PatientProfileService.class);

        CustomerPortalController controller = new CustomerPortalController(portalService, catalogService);
        ReflectionTestUtils.setField(controller, "patientProfileService", patientProfileService);

        // 1. Chưa đăng nhập -> 401 UNAUTHORIZED + /login (AJAX) và redirect:/login (form POST)
        ResponseEntity<Map<String, Object>> guestAjax = controller.switchPortalBranchAjax(2L, new MockHttpSession());
        assertEquals(HttpStatus.UNAUTHORIZED, guestAjax.getStatusCode());
        assertEquals(Boolean.FALSE, guestAjax.getBody().get("success"));
        assertEquals(Boolean.TRUE, guestAjax.getBody().get("loginRequired"));
        assertEquals("/login", guestAjax.getBody().get("loginUrl"));
        assertEquals("redirect:/login", controller.switchPortalBranch(2L, "/", new MockHttpSession(), new RedirectAttributesModelMap()));

        Branch b1 = branch(1L, "Cơ sở Quận 1");
        Branch b2 = branch(2L, "Cơ sở Cầu Giấy");
        b2.setAddress("88 Trần Thái Tông, Cầu Giấy, Hà Nội");
        b2.setPhone("0908889999");

        User user = patientUser(100L, "hieudx", b1);
        PatientProfile defaultProf = profile(10L, user, "Đinh Xuân Hiếu", "0987654271", b2);
        defaultProf.setIsDefault(true);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);

        when(patientProfileService.switchUserPreferredBranch(100L, 999L))
                .thenThrow(new IllegalArgumentException("Cơ sở không tồn tại hoặc hiện không hoạt động."));
        user.setBranch(b2);
        when(patientProfileService.switchUserPreferredBranch(100L, 2L))
                .thenReturn(new PatientProfileService.BranchSwitchResult(user, b2, List.of(defaultProf), defaultProf));

        // 2. Cơ sở không hợp lệ / ngừng hoạt động -> 400 BAD_REQUEST, không cập nhật session
        ResponseEntity<Map<String, Object>> badAjax = controller.switchPortalBranchAjax(999L, session);
        assertEquals(HttpStatus.BAD_REQUEST, badAjax.getStatusCode());
        assertEquals(Boolean.FALSE, badAjax.getBody().get("success"));
        assertNull(session.getAttribute("selectedPortalBranchId"));

        // 3. Hợp lệ -> 200 OK, gọi service dùng chung & cập nhật đồng bộ toàn bộ session
        ResponseEntity<Map<String, Object>> okAjax = controller.switchPortalBranchAjax(2L, session);
        assertEquals(HttpStatus.OK, okAjax.getStatusCode());
        assertEquals(Boolean.TRUE, okAjax.getBody().get("success"));
        assertEquals(2L, okAjax.getBody().get("branchId"));
        assertEquals("Cơ sở Cầu Giấy", okAjax.getBody().get("branchName"));
        assertEquals("0908889999", okAjax.getBody().get("branchPhone"));
        assertEquals(2L, session.getAttribute("selectedPortalBranchId"));
        assertEquals("Cơ sở Cầu Giấy", session.getAttribute("currentBranchName"));
        assertSame(defaultProf, session.getAttribute("defaultPatientProfile"));
        verify(patientProfileService).switchUserPreferredBranch(100L, 2L);
    }

    @Test
    @DisplayName("GET/POST /profile/change-branch dùng chung PatientProfileService & đồng bộ session khi xác nhận")
    void changeBranchPreselectsOnGetAndPersistsAllOwnedProfilesOnConfirmOnly() {
        Branch b1 = branch(1L, "Cơ sở Quận 1");
        Branch b2 = branch(2L, "Cơ sở Cầu Giấy");

        User user = patientUser(100L, "hieudx", b1);
        PatientProfile p1 = profile(10L, user, "Đinh Xuân Hiếu", "0987654271", b1);
        p1.setIsDefault(true);
        PatientProfile p2 = profile(11L, user, "Đinh Xuân Hoàn", "0976583211", b1);
        p2.setIsDefault(false);

        UserRepository userRepo = mock(UserRepository.class);
        BranchRepository branchRepo = mock(BranchRepository.class);
        PatientProfileRepository profileRepo = mock(PatientProfileRepository.class);
        PatientProfileService patientProfileService = mock(PatientProfileService.class);
        AccountPatientProfileRepository linkRepo = mock(AccountPatientProfileRepository.class);
        TokenRepository tokenRepo = mock(TokenRepository.class);
        when(tokenRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProfileController controller = new ProfileController(
                userRepo, mock(PasswordEncoder.class), branchRepo, profileRepo,
                patientProfileService, mock(StaffProfileRepository.class),
                mock(StaffBranchAssignmentRepository.class), linkRepo, tokenRepo);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
        when(userRepo.findById(100L)).thenReturn(Optional.of(user));
        when(branchRepo.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(b1, b2));
        when(profileRepo.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(100L)).thenReturn(List.of(p1, p2));
        AccountPatientProfile link1 = new AccountPatientProfile(); link1.setAccount(user); link1.setProfile(p1); link1.setAccessType(AccountPatientProfile.AccessType.SELF);
        AccountPatientProfile link2 = new AccountPatientProfile(); link2.setAccount(user); link2.setProfile(p2); link2.setAccessType(AccountPatientProfile.AccessType.DEPENDENT);
        when(linkRepo.findByProfile_ProfileId(10L)).thenReturn(Optional.of(link1));
        when(linkRepo.findByProfile_ProfileId(11L)).thenReturn(Optional.of(link2));
        when(linkRepo.findByAccount_UserIdOrderByProfile_ProfileIdDesc(100L)).thenReturn(List.of(link2, link1));

        // 1. GET /profile/change-branch?branchId=2 -> pre-select b2, KHÔNG tự lưu DB!
        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.showChangeBranch(2L, session, model);
        assertEquals("profile/change-branch", view);
        assertEquals(2L, model.getAttribute("preselectedBranchId"));
        assertSame(b1, user.getBranch());

        // 2. POST với cơ sở không hoạt động (3L) -> từ chối
        when(patientProfileService.switchUserPreferredBranch(100L, 3L))
                .thenThrow(new IllegalArgumentException("Cơ sở không tồn tại hoặc hiện không hoạt động."));
        RedirectAttributesModelMap badRa = new RedirectAttributesModelMap();
        assertEquals("redirect:/profile/change-branch", controller.changeBranch(3L, session, badRa));
        assertNotNull(badRa.getFlashAttributes().get("error"));

        // 3. POST xác nhận chuyển sang 2L -> gọi service dùng chung & đồng bộ session
        user.setBranch(b2);
        p1.setPreferredBranch(b2);
        p2.setPreferredBranch(b2);
        when(patientProfileService.switchUserPreferredBranch(100L, 2L))
                .thenReturn(new PatientProfileService.BranchSwitchResult(user, b2, List.of(p1, p2), p1));

        RedirectAttributesModelMap okRa = new RedirectAttributesModelMap();
        assertEquals("redirect:/profile", controller.changeBranch(2L, session, okRa));
        assertEquals(2L, session.getAttribute("selectedPortalBranchId"));
        assertEquals("Cơ sở Cầu Giấy", session.getAttribute("currentBranchName"));
        assertSame(p1, session.getAttribute("defaultPatientProfile"));
        verify(patientProfileService).switchUserPreferredBranch(100L, 2L);
    }

    // =========================================================================
    // 4. KIỂM CHỨNG TEMPLATE & JS ĐẶT LỊCH TINH GỌN + SWITCH TRỰC TIẾP TRÊN HOME
    // =========================================================================

    @Test
    @DisplayName("UI templates/JS chỉ hiển thị 1 mốc giờ cụ thể, dropdown Người khám gọn, và switch trực tiếp tại Home")
    void bookingTemplatesAndJsEnforceCompactSlotsProfileAndDirectHomeSwitch() throws Exception {
        String homeHtml = Files.readString(Path.of("src/main/resources/templates/portal/home.html"), StandardCharsets.UTF_8);
        String portalJs = Files.readString(Path.of("src/main/resources/static/js/portal-appointments.js"), StandardCharsets.UTF_8);
        String formHtml = Files.readString(Path.of("src/main/resources/templates/portal/appointments/form.html"), StandardCharsets.UTF_8);

        // I. Giờ hẹn chỉ hiển thị 1 mốc cụ thể, bỏ khoảng giờ & "còn X chỗ" & "Linh hoạt"
        assertTrue(homeHtml.contains("opt.textContent = slot.time;"));
        assertFalse(homeHtml.contains("slot.remaining} chỗ"));
        assertFalse(homeHtml.contains("Linh hoạt trong buổi"));
        assertTrue(portalJs.contains("select.appendChild(option(start, start));"));
        assertFalse(portalJs.contains("còn ${slot.remaining} chỗ"));
        assertFalse(portalJs.contains("option('', 'Linh hoạt trong buổi"));

        // II. Người khám gọn: bỏ link Hồ sơ gia đình và hộp #bkProfileSummary
        assertTrue(homeHtml.contains("id=\"bkProfile\""));
        assertFalse(homeHtml.contains("id=\"bkProfileSummary\""));
        assertFalse(homeHtml.contains("Hồ sơ gia đình</a>"));

        // III. Chuyển cơ sở trực tiếp tại Home, không điều hướng sang /profile/change-branch và không tự reload trang
        assertTrue(homeHtml.contains("data-direct-switch-branch"));
        assertTrue(homeHtml.contains("/portal/switch-branch/ajax"));
        assertTrue(homeHtml.contains("Cơ sở hiện tại"));
        assertTrue(homeHtml.contains("isBranchSwitchInFlight"));
        assertTrue(homeHtml.contains("syncHomeBranchSections"));
        assertTrue(homeHtml.contains("slide.querySelector('[data-direct-switch-branch]')"));
        assertFalse(homeHtml.contains("slide.querySelector('.cv-visit__content .d-flex.flex-wrap')"));
        assertFalse(homeHtml.contains("window.location.reload"));
        assertFalse(homeHtml.contains("sessionStorage.setItem('cv_branch_switched_msg'"));
        assertFalse(homeHtml.contains("/profile/change-branch(branchId="));
        assertFalse(homeHtml.contains("id=\"switchBranchModal\""));

        // Đồng bộ branch mặc định vào /portal/appointments/new
        assertTrue(formHtml.contains("data-default-branch-id=${defaultBranchId}"));
        assertTrue(portalJs.contains("form.dataset.defaultBranchId"));
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private void assertNoShowCutoff(
            LocalDate visitDate, LocalTime expectedTime, LocalTime beforeCutoff,
            LocalTime afterCutoff, Branch b1, User staff) {
        ReceptionAppointmentRepository apptRepo = mock(ReceptionAppointmentRepository.class);
        AppointmentBookingLockRepository lockRepo = mock(AppointmentBookingLockRepository.class);
        ReceptionAppointmentService recService = new ReceptionAppointmentService(
                mock(com.cvdcms.security.PatientAccess.class),
                apptRepo,
                mock(ReceptionAppointmentPatientRepository.class),
                mock(com.cvdcms.repository.ReceptionPatientRepository.class),
                mock(BranchRepository.class),
                lockRepo,
                mock(ServiceRepository.class),
                mock(StaffProfileRepository.class),
                mock(ReceptionAppointmentRules.class),
                mock(AppointmentHistoryRepository.class),
                mock(CustomerCareTaskRepository.class),
                new AppointmentLifecycleServiceImpl(),
                mock(AppointmentConflictDetectionService.class),
                mock(AppointmentSlotService.class),
                mock(com.cvdcms.service.PatientService.class),
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());

        Appointment scheduled = new Appointment();
        scheduled.setAppointmentId(88L);
        scheduled.setAppointmentCode("LH" + expectedTime.toString().replace(":", ""));
        scheduled.setBranch(b1);
        scheduled.setPatientProfile(profile(10L, staff, "Test", "0912345678", b1));
        scheduled.setAppointmentDate(visitDate);
        scheduled.setPreferredSession(PreferredSession.AFTERNOON);
        scheduled.setExpectedTime(expectedTime);
        scheduled.setStatus(AppointmentStatus.CONFIRMED);

        when(apptRepo.findById(88L)).thenReturn(Optional.of(scheduled));
        lenient().when(lockRepo.findByIdForBooking(1L)).thenReturn(Optional.of(b1));
        lenient().when(apptRepo.lockById(88L)).thenReturn(Optional.of(scheduled));

        recService.markNoShow(88L, LocalDateTime.of(visitDate, beforeCutoff));
        assertEquals(AppointmentStatus.CONFIRMED, scheduled.getStatus(),
                "Đúng hạn 30 phút (" + beforeCutoff + ") CHƯA được chuyển NO_SHOW");

        recService.markNoShow(88L, LocalDateTime.of(visitDate, afterCutoff));
        assertEquals(AppointmentStatus.NO_SHOW, scheduled.getStatus(),
                "Sau 30 phút (" + afterCutoff + ") PHẢI chuyển NO_SHOW");
    }

    private Boolean runCheckInAt(LocalDate date, LocalTime expectedTime, LocalTime checkInTime, Branch branch, User actor) {
        ReceptionAccess access = mock(ReceptionAccess.class);
        ReceptionProfileRepository profiles = mock(ReceptionProfileRepository.class);
        ReceptionCheckInAppointmentRepository appts = mock(ReceptionCheckInAppointmentRepository.class);
        ReceptionVisitRepository visits = mock(ReceptionVisitRepository.class);
        ReceptionCheckInKeyRepository keys = mock(ReceptionCheckInKeyRepository.class);
        ReceptionAuditRepository audits = mock(ReceptionAuditRepository.class);
        BranchRepository branchRepo = mock(BranchRepository.class);
        AppointmentBookingLockRepository lockRepo = mock(AppointmentBookingLockRepository.class);
        AppointmentHistoryRepository histories = mock(AppointmentHistoryRepository.class);
        HttpSession session = mock(HttpSession.class);

        ReceptionService service = new ReceptionService(
                access, profiles, appts, visits, keys, audits, branchRepo,
                new ObjectMapper(), lockRepo, new AppointmentLifecycleServiceImpl(), histories);
        service.setClock(Clock.fixed(LocalDateTime.of(date, checkInTime).atZone(ZONE).toInstant(), ZONE));

        PatientProfile p = profile(7L, actor, "Bệnh nhân Hẹn", "0911223344", branch);
        Appointment a = new Appointment();
        a.setAppointmentId(20L);
        a.setAppointmentCode("LH20");
        a.setBranch(branch);
        a.setPatientProfile(p);
        a.setAppointmentDate(date);
        a.setPreferredSession(PreferredSession.AFTERNOON);
        a.setExpectedTime(expectedTime);
        a.setStatus(AppointmentStatus.CONFIRMED);
        a.setInitialSymptoms("Đau răng");

        when(access.require(session)).thenReturn(new ReceptionAccess.Scope(actor, false, List.of(1L)));
        when(branchRepo.findById(1L)).thenReturn(Optional.of(branch));
        when(lockRepo.findByIdForBooking(1L)).thenReturn(Optional.of(branch));
        when(profiles.lockById(7L)).thenReturn(Optional.of(p));
        when(appts.findById(20L)).thenReturn(Optional.of(a));
        when(appts.lockById(20L)).thenReturn(Optional.of(a));
        when(visits.findByAppointmentId(20L)).thenReturn(Optional.empty());
        when(visits.activeForProfile(eq(7L), anySet())).thenReturn(List.of());
        when(keys.findByRequestKey(anyString())).thenReturn(Optional.empty());

        List<Visit> savedVisits = new ArrayList<>();
        when(visits.saveAndFlush(any())).thenAnswer(inv -> {
            Visit v = inv.getArgument(0);
            if (v.getVisitId() == null) v.setVisitId(99L);
            savedVisits.add(v);
            return v;
        });

        service.checkInAppointment(session, 20L, "Đau răng", UUID.randomUUID().toString());
        return savedVisits.get(0).getIsBookedAndOnTime();
    }

    private static Branch branch(Long id, String name) {
        Branch b = new Branch();
        b.setBranchId(id);
        b.setBranchCode("CN" + id);
        b.setBranchName(name);
        b.setIsActive(true);
        b.setWorkingDays("2,3,4,5,6,7,CN");
        b.setOpeningTime(LocalTime.of(8, 0));
        b.setClosingTime(LocalTime.of(20, 0));
        return b;
    }

    private static User patientUser(Long id, String username, Branch branch) {
        User u = new User();
        u.setUserId(id);
        u.setUsername(username);
        u.setUserType(UserType.PATIENT);
        u.setStatus("ACTIVE");
        u.setIsActive(true);
        u.setBranch(branch);
        return u;
    }

    private static PatientProfile profile(Long id, User user, String fullName, String phone, Branch branch) {
        PatientProfile p = new PatientProfile();
        p.setProfileId(id);
        p.setUser(user);
        p.setFullName(fullName);
        p.setPhoneNumber(phone);
        p.setPatientCode("BN" + id);
        p.setGender("Nam");
        p.setAddress("HCM");
        p.setPreferredBranch(branch);
        p.setStatus("ACTIVE");
        return p;
    }
}
