package com.cvdcms.receptionappointment;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patient.PatientAccess;
import com.cvdcms.patient.PatientProfileForm;
import com.cvdcms.patient.PatientService;
import com.cvdcms.patient.PatientView;
import com.cvdcms.patient.ReceptionPatientRecord;
import com.cvdcms.patient.ReceptionPatientRepository;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentConflictDetectionService;
import com.cvdcms.service.AppointmentLifecycleService;
import com.cvdcms.service.AppointmentSlotService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReceptionAppointmentRegressionTest {

    @Mock PatientAccess access;
    @Mock ReceptionAppointmentRepository appointments;
    @Mock ReceptionAppointmentPatientRepository patients;
    @Mock ReceptionPatientRepository receptionPatients;
    @Mock BranchRepository branches;
    @Mock AppointmentBookingLockRepository lockedBranches;
    @Mock ServiceRepository services;
    @Mock StaffProfileRepository staff;
    @Mock ReceptionAppointmentRules rules;
    @Mock AppointmentHistoryRepository histories;
    @Mock CustomerCareTaskRepository careTasks;
    @Mock AppointmentLifecycleService lifecycle;
    @Mock AppointmentConflictDetectionService conflictDetection;
    @Mock AppointmentSlotService slots;
    @Mock PatientService patientService;
    @Mock HttpSession session;

    ReceptionAppointmentService service;
    Branch branch;
    PatientProfile profile;
    User user;

    @BeforeEach
    void setUp() {
        service = new ReceptionAppointmentService(
                access, appointments, patients, receptionPatients,
                branches, lockedBranches, services, staff,
                rules, histories, careTasks, lifecycle,
                conflictDetection, slots, patientService,
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator()
        );

        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Chi nhánh Quận 1");
        branch.setIsActive(true);

        user = new User();
        user.setUserId(99L);
        user.setUsername("letan");

        profile = new PatientProfile();
        profile.setProfileId(10L);
        profile.setFullName("Nguyễn Văn A");
        profile.setPatientCode("BN00001");
        profile.setPhoneNumber("0912345678");

        PatientAccess.Scope scope = new PatientAccess.Scope(user, 1L, false);
        lenient().when(access.require(session)).thenReturn(scope);
        lenient().when(branches.findById(1L)).thenReturn(Optional.of(branch));
        lenient().when(lockedBranches.findByIdForBooking(1L)).thenReturn(Optional.of(branch));
    }

    // =========================================================================
    // FIX CV-01: Không tạo hồ sơ bệnh nhân bằng dữ liệu giả & tuân thủ BR-PAT-09
    // =========================================================================

    @Test
    @DisplayName("CV-01: Bệnh nhân mới thiếu ngày sinh hoặc giới tính phải bị từ chối, không sinh dữ liệu giả")
    void testCreateNewPatientMissingRequiredFieldsFails() {
        ReceptionAppointmentService.CreateRequest request = new ReceptionAppointmentService.CreateRequest(
                1L, null, LocalDate.now().plusDays(1), PreferredSession.MORNING,
                null, null, "Khám răng", LocalTime.of(9, 0),
                "Bệnh nhân Mới", "0912345678", "Hà Nội", "Vãng lai",
                false, null, null, null, null, null, null, null, false
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.create(session, request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Ngày sinh") || ex.getReason().contains("Giới tính"));
        verify(patientService, never()).create(any(), any());
        verify(appointments, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("CV-01: Bệnh nhân mới có số điện thoại không hợp lệ phải bị từ chối")
    void testCreateNewPatientInvalidPhoneFails() {
        ReceptionAppointmentService.CreateRequest request = new ReceptionAppointmentService.CreateRequest(
                1L, null, LocalDate.now().plusDays(1), PreferredSession.MORNING,
                null, null, "Khám răng", LocalTime.of(9, 0),
                "Bệnh nhân Mới", "12345", "Hà Nội", "Vãng lai",
                false, null, LocalDate.of(1995, 5, 20), "Nam", null, null, null, null, false
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.create(session, request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Số điện thoại"));
        verify(patientService, never()).create(any(), any());
    }

    @Test
    @DisplayName("CV-01: Bệnh nhân mới hợp lệ được tạo thông qua PatientService chuẩn và liên kết đặt lịch")
    void testCreateNewPatientValidDataDelegatesToPatientService() {
        LocalDate dob = LocalDate.of(1995, 5, 20);
        ReceptionAppointmentService.CreateRequest request = new ReceptionAppointmentService.CreateRequest(
                1L, null, LocalDate.now().plusDays(1), PreferredSession.MORNING,
                null, null, "Khám răng", null,
                "Lê Thị B", "0987654321", "TP.HCM", "Giới thiệu",
                false, null, dob, "Nữ", null, null, null, null, false
        );

        ReceptionPatientRecord record = new ReceptionPatientRecord();
        record.setProfileId(55L);
        record.setPatientCode("BN1234567890ABCDEF");
        record.setFullName("Lê Thị B");
        record.setDob(dob);
        record.setGender("Nữ");
        record.setPhoneNumber("0987654321");
        record.setPreferredBranch(branch);
        record.setStatus("ACTIVE");
        PatientView createdView = PatientView.from(record);
        when(patientService.create(eq(session), any(PatientProfileForm.class))).thenReturn(createdView);
        when(patients.findById(55L)).thenReturn(Optional.of(profile));

        ReceptionAppointmentService.AppointmentView result = service.create(session, request);

        assertNotNull(result);
        verify(patientService).create(eq(session), argThat(form ->
                form.fullName().equals("Lê Thị B") &&
                form.phoneNumber().equals("0987654321") &&
                form.gender().equals("Nữ") &&
                form.dob().equals(dob) &&
                form.branchId().equals(1L)
        ));
        verify(appointments).saveAndFlush(any(Appointment.class));
    }

    @Test
    @DisplayName("CV-01: Đặt lịch với hồ sơ đã có được tìm kiếm hợp lệ")
    void testCreateWithExistingProfile() {
        when(patients.findBookable(10L, 1L)).thenReturn(Optional.of(profile));
        ReceptionAppointmentService.CreateRequest request = new ReceptionAppointmentService.CreateRequest(
                1L, 10L, LocalDate.now().plusDays(1), PreferredSession.MORNING,
                null, null, "Khám định kỳ", null
        );

        ReceptionAppointmentService.AppointmentView result = service.create(session, request);
        assertNotNull(result);
        verify(patientService, never()).create(any(), any());
        verify(appointments).saveAndFlush(any(Appointment.class));
    }

    // =========================================================================
    // FIX CV-02: Không bỏ qua lỗi kiểm tra slot khi tạo lịch
    // =========================================================================

    @Test
    @DisplayName("CV-02: Khi ensureBookable ném lỗi (quá hạn/kín chỗ), không được bypass kể cả isTentative=true")
    void testEnsureBookableExceptionIsNotBypassedWhenTentative() {
        when(patients.findBookable(10L, 1L)).thenReturn(Optional.of(profile));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Ca khám đã đạt tối đa công suất tiếp nhận."))
                .when(rules).ensureBookable(any(), any(), any(), any(), any(), any());

        ReceptionAppointmentService.CreateRequest request = new ReceptionAppointmentService.CreateRequest(
                1L, 10L, LocalDate.now().plusDays(1), PreferredSession.MORNING,
                null, null, "Khám tổng quát", null,
                null, null, null, null, true, null
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.create(session, request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("công suất"));
        verify(appointments, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("CV-02: Khi resolveFlexibly ném lỗi (slot kín/bác sĩ trùng), không được nuốt exception fallback về raw time")
    void testSlotConflictThrowsExceptionAndIsNotSwallowed() {
        when(patients.findBookable(10L, 1L)).thenReturn(Optional.of(profile));
        LocalTime requestedTime = LocalTime.of(9, 30);
        when(slots.resolveFlexibly(any(), any(), any(), any(), anyInt(), any(), eq(requestedTime), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Khung giờ 09:30 đã đạt tối đa công suất."));

        ReceptionAppointmentService.CreateRequest request = new ReceptionAppointmentService.CreateRequest(
                1L, 10L, LocalDate.now().plusDays(1), PreferredSession.MORNING,
                null, null, "Trám răng", requestedTime,
                null, null, null, null, false, null
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.create(session, request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("công suất"));
        verify(appointments, never()).saveAndFlush(any());
    }

    // =========================================================================
    // FIX CV-03: Sửa Quick Status không bypass Lifecycle
    // =========================================================================

    @Test
    @DisplayName("CV-03: Quick Status cố đổi sang CHECKED_IN trực tiếp phải bị chặn và hướng dẫn sang FT07")
    void testQuickStatusCannotDirectlyCheckIn() {
        Appointment a = new Appointment();
        a.setAppointmentId(200L);
        a.setStatus(AppointmentStatus.CONFIRMED);
        a.setBranch(branch);
        when(appointments.findById(200L)).thenReturn(Optional.of(a));
        when(appointments.lockById(200L)).thenReturn(Optional.of(a));

        ReceptionAppointmentService.QuickStatusRequest req =
                new ReceptionAppointmentService.QuickStatusRequest("CHECKED_IN", "Khách đã đến");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.quickStatus(session, 200L, req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("FT07") || ex.getReason().contains("tiếp đón"));
        assertEquals(AppointmentStatus.CONFIRMED, a.getStatus());
    }

    @Test
    @DisplayName("CV-03: Quick Status cố đổi sang COMPLETED trực tiếp phải bị chặn")
    void testQuickStatusCannotDirectlyComplete() {
        Appointment a = new Appointment();
        a.setAppointmentId(201L);
        a.setStatus(AppointmentStatus.CHECKED_IN);
        a.setBranch(branch);
        when(appointments.findById(201L)).thenReturn(Optional.of(a));
        when(appointments.lockById(201L)).thenReturn(Optional.of(a));

        ReceptionAppointmentService.QuickStatusRequest req =
                new ReceptionAppointmentService.QuickStatusRequest("COMPLETED", "Đã khám xong");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.quickStatus(session, 201L, req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("hoàn tất"));
    }

    @Test
    @DisplayName("CV-03: Quick Status hủy lịch hợp lệ tuân thủ Lifecycle và hủy care tasks")
    void testQuickStatusCancelValid() {
        Appointment a = new Appointment();
        a.setAppointmentId(202L);
        a.setStatus(AppointmentStatus.CONFIRMED);
        a.setBranch(branch);
        a.setPatientProfile(profile);
        a.setAppointmentDate(LocalDate.now().plusDays(1));
        a.setPreferredSession(PreferredSession.MORNING);
        when(appointments.findById(202L)).thenReturn(Optional.of(a));
        when(appointments.lockById(202L)).thenReturn(Optional.of(a));
        when(lifecycle.canTransition(AppointmentStatus.CONFIRMED, AppointmentStatus.CANCELLED, AppointmentLifecycleAction.CANCEL))
                .thenReturn(true);

        ReceptionAppointmentService.QuickStatusRequest req =
                new ReceptionAppointmentService.QuickStatusRequest("CANCELLED", "Bệnh nhân bận việc đột xuất");

        service.quickStatus(session, 202L, req);

        verify(lifecycle).transition(a, AppointmentStatus.CANCELLED, AppointmentLifecycleAction.CANCEL);
        assertNotNull(a.getCancelledAt());
        assertEquals("Bệnh nhân bận việc đột xuất", a.getCancelReason());
        verify(appointments).saveAndFlush(a);
    }

    // =========================================================================
    // FIX CV-07: Validation số điện thoại và phòng chống chèn mã độc vào liên kết
    // =========================================================================

    @Test
    @DisplayName("CV-07: Số điện thoại chứa mã độc javascript/html phải bị từ chối")
    void testMaliciousPhoneInputRejected() {
        ReceptionAppointmentService.CreateRequest request = new ReceptionAppointmentService.CreateRequest(
                1L, null, LocalDate.now().plusDays(1), PreferredSession.MORNING,
                null, null, "Khám răng", LocalTime.of(9, 0),
                "Hacker", "javascript:alert(1)", "Hà Nội", "Vãng lai",
                false, null, LocalDate.of(1990, 1, 1), "Nam", null, null, null, null, false
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.create(session, request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Số điện thoại"));
        verify(patientService, never()).create(any(), any());
    }

    @Test
    @DisplayName("CV-07: Regex chuẩn hóa số điện thoại chấp nhận format hợp lệ và lọc ký tự an toàn")
    void testPhoneNumberRegexPattern() {
        String validPhone = "0987654321";
        assertTrue(validPhone.matches("^0\\d{9,10}$"));

        String rawWithFormat = "+84 987 654 321";
        String cleaned = rawWithFormat.replaceAll("[^\\d+]", "");
        assertTrue(cleaned.matches("^\\+?0?\\d{9,11}$"));

        String malicious = "tel:0987654321<script>alert(1)</script>";
        String maliciousCleaned = malicious.replaceAll("[^\\d+]", "");
        assertFalse(malicious.matches("^0\\d{9,10}$"));
        assertNotEquals(malicious, maliciousCleaned);
    }

    @Test
    @DisplayName("CV-01: Bệnh nhân mới trùng khớp đòi hỏi xác nhận confirmDuplicate=true để tiếp tục")
    void testCreateNewPatientDuplicateHandling() {
        LocalDate dob = LocalDate.of(1992, 8, 15);
        ReceptionAppointmentService.CreateRequest unconfirmedReq = new ReceptionAppointmentService.CreateRequest(
                1L, null, LocalDate.now().plusDays(1), PreferredSession.MORNING,
                null, null, "Khám tổng quát", null,
                "Trần Văn Trùng", "0911223344", "Hà Nội", "Vãng lai",
                false, null, dob, "Nam", null, null, null, null, false
        );

        when(patientService.create(eq(session), argThat(f -> !f.confirmDuplicate())))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                        "Phát hiện hồ sơ trùng khớp theo họ tên, ngày sinh hoặc số điện thoại. Vui lòng xác nhận để tiếp tục tạo mới."));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.create(session, unconfirmedReq));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("trùng khớp"));

        // Khi người dùng xác nhận tiếp tục (confirmDuplicate = true)
        ReceptionAppointmentService.CreateRequest confirmedReq = new ReceptionAppointmentService.CreateRequest(
                1L, null, LocalDate.now().plusDays(1), PreferredSession.MORNING,
                null, null, "Khám tổng quát", null,
                "Trần Văn Trùng", "0911223344", "Hà Nội", "Vãng lai",
                false, null, dob, "Nam", null, null, null, null, true
        );

        ReceptionPatientRecord record = new ReceptionPatientRecord();
        record.setProfileId(88L);
        record.setPatientCode("BNTRUNG01");
        record.setFullName("Trần Văn Trùng");
        record.setDob(dob);
        record.setGender("Nam");
        record.setPhoneNumber("0911223344");
        record.setPreferredBranch(branch);
        record.setStatus("ACTIVE");
        PatientView createdView = PatientView.from(record);

        when(patientService.create(eq(session), argThat(PatientProfileForm::confirmDuplicate)))
                .thenReturn(createdView);
        when(patients.findById(88L)).thenReturn(Optional.of(profile));

        ReceptionAppointmentService.AppointmentView result = service.create(session, confirmedReq);
        assertNotNull(result);
        verify(appointments).saveAndFlush(any(Appointment.class));
    }

    @Test
    void quickConfirmRejectsUnavailableDoctorOrSlot() {
        Appointment a = new Appointment();
        a.setAppointmentId(301L); a.setBranch(branch); a.setPatientProfile(profile);
        a.setStatus(AppointmentStatus.PENDING); a.setAppointmentDate(LocalDate.now().plusDays(1));
        a.setPreferredSession(PreferredSession.MORNING); a.setExpectedTime(LocalTime.of(9, 0));
        profile.setStatus("ACTIVE");
        when(appointments.findById(301L)).thenReturn(Optional.of(a));
        when(appointments.lockById(301L)).thenReturn(Optional.of(a));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Bác sĩ không khả dụng"))
                .when(rules).ensureBookable(any(), any(), any(), any(), any(), any());
        assertThrows(ResponseStatusException.class, () -> service.quickStatus(session, 301L,
                new ReceptionAppointmentService.QuickStatusRequest("CONFIRMED", null)));
        verify(appointments, never()).saveAndFlush(any());
    }

    @Test
    void quickNoShowBeforeSessionEndsIsRejected() {
        Appointment a = new Appointment();
        a.setAppointmentId(302L); a.setBranch(branch); a.setPatientProfile(profile);
        a.setStatus(AppointmentStatus.CONFIRMED); a.setAppointmentDate(LocalDate.now().plusDays(1));
        a.setPreferredSession(PreferredSession.MORNING);
        when(appointments.findById(302L)).thenReturn(Optional.of(a));
        when(appointments.lockById(302L)).thenReturn(Optional.of(a));
        when(lifecycle.canTransition(AppointmentStatus.CONFIRMED, AppointmentStatus.NO_SHOW,
                AppointmentLifecycleAction.MARK_NO_SHOW)).thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> service.quickStatus(session, 302L,
                new ReceptionAppointmentService.QuickStatusRequest("NO_SHOW", null)));
        verify(appointments, never()).saveAndFlush(any());
    }

    @Test
    void quickConfirmRejectsOccupiedSlot() {
        Appointment a = new Appointment();
        a.setAppointmentId(303L); a.setBranch(branch); a.setPatientProfile(profile);
        a.setStatus(AppointmentStatus.PENDING); a.setAppointmentDate(LocalDate.now().plusDays(1));
        a.setPreferredSession(PreferredSession.MORNING); a.setExpectedTime(LocalTime.of(9, 0));
        a.setDurationMinutes(30); profile.setStatus("ACTIVE");
        when(appointments.findById(303L)).thenReturn(Optional.of(a));
        when(appointments.lockById(303L)).thenReturn(Optional.of(a));
        when(slots.resolveFlexibly(any(), any(), any(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Slot đã kín"));
        assertThrows(ResponseStatusException.class, () -> service.quickStatus(session, 303L,
                new ReceptionAppointmentService.QuickStatusRequest("CONFIRMED", null)));
        verify(lifecycle, never()).transition(any(), any(), any());
        verify(appointments, never()).saveAndFlush(any());
    }

    @Test
    void quickNoShowAfterSessionEndsCancelsReminderAndCreatesRecall() {
        Appointment a = new Appointment();
        a.setAppointmentId(304L); a.setAppointmentCode("LH304"); a.setBranch(branch); a.setPatientProfile(profile);
        a.setStatus(AppointmentStatus.CONFIRMED); a.setAppointmentDate(LocalDate.now().minusDays(1));
        a.setPreferredSession(PreferredSession.MORNING);
        CustomerCareTask reminder = new CustomerCareTask();
        reminder.setTaskType(CustomerCareTaskType.APPOINTMENT_REMINDER);
        reminder.setStatus(CustomerCareTaskStatus.PENDING);
        when(appointments.findById(304L)).thenReturn(Optional.of(a));
        when(appointments.lockById(304L)).thenReturn(Optional.of(a));
        when(lifecycle.canTransition(AppointmentStatus.CONFIRMED, AppointmentStatus.NO_SHOW,
                AppointmentLifecycleAction.MARK_NO_SHOW)).thenReturn(true);
        when(rules.sessionEnded(any(), any(), any(), any())).thenReturn(true);
        when(careTasks.findByAppointment_AppointmentId(304L)).thenReturn(List.of(reminder));
        doAnswer(inv -> { a.setStatus(AppointmentStatus.NO_SHOW); return null; })
                .when(lifecycle).transition(a, AppointmentStatus.NO_SHOW, AppointmentLifecycleAction.MARK_NO_SHOW);
        service.quickStatus(session, 304L, new ReceptionAppointmentService.QuickStatusRequest("NO_SHOW", "Không đến"));
        assertEquals(CustomerCareTaskStatus.CANCELLED, reminder.getStatus());
        verify(careTasks).save(argThat(t -> t.getTaskType() == CustomerCareTaskType.NO_SHOW_RECALL));
        verify(histories).save(any());
    }

    @Test
    void invalidGuardianPhoneIsRejectedBeforeCreatingPatient() {
        var req = new ReceptionAppointmentService.CreateRequest(1L, null, LocalDate.now().plusDays(1),
                PreferredSession.MORNING, null, null, "Khám", null, "Người bệnh", "0912345678",
                "Hà Nội", "Vãng lai", false, null, LocalDate.now().minusYears(10), "Nam",
                "Người giám hộ", "123", "Cha", null, false);
        assertThrows(ResponseStatusException.class, () -> service.create(session, req));
        verify(patientService, never()).create(any(), any());
    }
}

