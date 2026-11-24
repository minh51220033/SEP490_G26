package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppointmentSlotServiceTest {
    private static final ZoneId ZONE = AppointmentPolicyService.CLINIC_ZONE;
    private final AppointmentPolicyRepository appointments = mock(AppointmentPolicyRepository.class);
    private final AppointmentSessionWindowRepository windows = mock(AppointmentSessionWindowRepository.class);
    private final AppointmentPolicyService policy = mock(AppointmentPolicyService.class);
    private final AppointmentSlotService slots = new AppointmentSlotService(appointments, windows, policy);
    private final LocalDate date = LocalDate.now(ZONE).plusDays(2);

    private Branch branch() {
        Branch branch = new Branch(); branch.setBranchId(4L);
        when(windows.findByBranch_BranchIdAndPreferredSession(eq(4L), any()))
                .thenReturn(Optional.empty());
        when(policy.availability(eq(branch), eq(date), eq(PreferredSession.MORNING), nullable(Long.class),
                eq(AppointmentActorType.RECEPTIONIST))).thenReturn(new AppointmentPolicyService.Availability(
                true, null, 10, List.of(new AppointmentPolicyService.DoctorOption(7L, "A", null, 5))));
        return branch;
    }

    @Test
    void sharedArrivalAllowsMultiplePatientsUpToWaveAndSessionCapacityWhileEnforcingSessionBoundary() {
        slots.setMaxConcurrentPerDoctorSlot(3);
        Branch branch = branch();
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(7L);
        Appointment booked = new Appointment(); booked.setExpectedTime(LocalTime.of(8, 30));
        booked.setDurationMinutes(60); booked.setDoctor(doctor);
        when(appointments.findOccupyingForSession(eq(4L), eq(date), eq(PreferredSession.MORNING), anyList()))
                .thenReturn(List.of(booked));

        // Cho phép bệnh nhân tiếp theo chọn cùng/giao khoảng giờ đến nếu bác sĩ còn công suất wave (mặc định 3/khung)
        assertEquals(LocalTime.of(9, 15), slots.resolve(branch, date,
                PreferredSession.MORNING, null, 30, null, LocalTime.of(9, 15), AppointmentActorType.RECEPTIONIST));
        // Vượt ranh giới nghỉ trưa (11:30 + 60p > 12:00) vẫn bị chặn
        assertThrows(ResponseStatusException.class, () -> slots.resolve(branch, date,
                PreferredSession.MORNING, null, 60, null, LocalTime.of(11, 30), AppointmentActorType.RECEPTIONIST));
        assertEquals(LocalTime.of(9, 30), slots.resolve(branch, date,
                PreferredSession.MORNING, null, 30, null, LocalTime.of(9, 30), AppointmentActorType.RECEPTIONIST));
    }

    @Test
    void requestedDoctorAllowsConcurrentArrivalUpToCapAndBlocksWhenWaveCapReached() {
        slots.setMaxConcurrentPerDoctorSlot(3);
        Branch branch = branch();
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(7L);
        List<Appointment> existing = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Appointment b = new Appointment();
            b.setAppointmentId((long) (i + 1));
            b.setExpectedTime(LocalTime.of(8, 0));
            b.setDurationMinutes(45);
            b.setDoctor(doctor);
            b.setStatus(AppointmentStatus.CONFIRMED);
            existing.add(b);
        }
        when(appointments.findOccupyingForSession(eq(4L), eq(date), eq(PreferredSession.MORNING), anyList()))
                .thenReturn(existing);

        // Đạt ngưỡng tiếp nhận cùng khung giờ (3 ca) cho BS 7 -> từ chối ca thứ 4 ở 08:15
        assertThrows(ResponseStatusException.class, () -> slots.resolve(branch, date,
                PreferredSession.MORNING, 7L, 30, null, LocalTime.of(8, 15), AppointmentActorType.RECEPTIONIST));

        // Nếu mới có 1 ca ở 08:00 -> vẫn cho phép đặt thêm lúc 08:00
        when(appointments.findOccupyingForSession(eq(4L), eq(date), eq(PreferredSession.MORNING), anyList()))
                .thenReturn(List.of(existing.get(0)));
        assertEquals(LocalTime.of(8, 0), slots.resolve(branch, date,
                PreferredSession.MORNING, 7L, 30, null, LocalTime.of(8, 0), AppointmentActorType.RECEPTIONIST));
    }

    @Test
    void resolveFlexiblyAllowsNullExpectedTimeAndHandlesNullSessionRangeGracefully() {
        Branch branch = branch();
        StaffProfile doctor = new StaffProfile(); doctor.setStaffId(7L);
        Appointment booked = new Appointment(); booked.setExpectedTime(LocalTime.of(8, 0));
        booked.setDurationMinutes(45); booked.setDoctor(doctor);
        when(appointments.findOccupyingForSession(eq(4L), eq(date), eq(PreferredSession.MORNING), anyList()))
                .thenReturn(List.of(booked));

        // 1. policy.sessionRange() trả null không được ném NullPointerException
        assertNull(slots.resolveFlexibly(branch, date, PreferredSession.MORNING, 7L, 30, null, null, AppointmentActorType.RECEPTIONIST));

        // 2. Giờ nằm trong ca và còn sức chứa được chấp nhận
        assertEquals(LocalTime.of(9, 0), slots.resolveFlexibly(branch, date,
                PreferredSession.MORNING, 7L, 30, null, LocalTime.of(9, 0), AppointmentActorType.RECEPTIONIST));
    }

    @Test
    void availableReturnsRealRemainingCapacityAcrossConcurrentArrivalsAndBalancesAutoAssign() {
        Branch branch = branch();
        branch.setOpeningTime(LocalTime.of(8, 0));
        branch.setClosingTime(LocalTime.of(20, 0));
        when(policy.sessionRange(branch, PreferredSession.AFTERNOON))
                .thenReturn(new AppointmentPolicyService.SessionRange(LocalTime.of(13, 30), LocalTime.of(17, 30), true));

        StaffProfile doc1 = new StaffProfile(); doc1.setStaffId(1L); doc1.setFullName("BS A"); doc1.setStatus(StaffStatus.ACTIVE);
        StaffProfile doc2 = new StaffProfile(); doc2.setStaffId(2L); doc2.setFullName("BS B"); doc2.setStatus(StaffStatus.ACTIVE);
        WorkShift pm = new WorkShift(); pm.setShiftId(2L); pm.setIsActive(true);
        pm.setStartTime(LocalTime.of(13, 30)); pm.setEndTime(LocalTime.of(17, 30));

        StaffScheduleRepository schRepo = mock(StaffScheduleRepository.class);
        LeaveRequestRepository leaveRepo = mock(LeaveRequestRepository.class);
        VisitRepository visitRepo = mock(VisitRepository.class);
        StaffProfileRepository staffRepo = mock(StaffProfileRepository.class);
        AppointmentSlotService richSlots = new AppointmentSlotService(
                appointments, windows, policy, schRepo, leaveRepo, visitRepo, staffRepo);
        richSlots.setMaxConcurrentPerDoctorSlot(3);

        StaffSchedule s1 = new StaffSchedule(); s1.setStaff(doc1); s1.setBranch(branch); s1.setShift(pm); s1.setWorkDate(date); s1.setStatus(ScheduleStatus.SCHEDULED);
        StaffSchedule s2 = new StaffSchedule(); s2.setStaff(doc2); s2.setBranch(branch); s2.setShift(pm); s2.setWorkDate(date); s2.setStatus(ScheduleStatus.SCHEDULED);
        when(schRepo.findDoctorSchedulesForDate(1L, date, ScheduleStatus.SCHEDULED)).thenReturn(List.of(s1));
        when(schRepo.findDoctorSchedulesForDate(2L, date, ScheduleStatus.SCHEDULED)).thenReturn(List.of(s2));
        when(leaveRepo.findApprovedForStaffOnDate(anyLong(), eq(date), eq(LeaveStatus.APPROVED))).thenReturn(List.of());

        when(policy.availability(eq(branch), eq(date), eq(PreferredSession.AFTERNOON), isNull(), eq(AppointmentActorType.PATIENT)))
                .thenReturn(new AppointmentPolicyService.Availability(true, null, 6, List.of(
                        new AppointmentPolicyService.DoctorOption(1L, "BS A", null, 5),
                        new AppointmentPolicyService.DoctorOption(2L, "BS B", null, 5))));

        // BS 1 đã có 1 ca lúc 15:30; BS 2 chưa có ca nào -> auto-assign phải chọn BS 2 (ít tải tại 15:30 nhất)
        Appointment appt1 = new Appointment(); appt1.setAppointmentId(11L); appt1.setDoctor(doc1);
        appt1.setExpectedTime(LocalTime.of(15, 30)); appt1.setDurationMinutes(30); appt1.setStatus(AppointmentStatus.CONFIRMED);
        when(appointments.findOccupyingForSession(eq(4L), eq(date), eq(PreferredSession.AFTERNOON), anyList())).thenReturn(List.of(appt1));
        when(appointments.findDoctorAppointmentsForDate(1L, date, AppointmentSlotService.OCCUPYING)).thenReturn(List.of(appt1));
        when(appointments.findDoctorAppointmentsForDate(2L, date, AppointmentSlotService.OCCUPYING)).thenReturn(List.of());

        List<AppointmentSlotService.Slot> open = richSlots.available(branch, date, PreferredSession.AFTERNOON, null, 30, null, AppointmentActorType.PATIENT);
        var slot1530 = open.stream().filter(s -> s.time().equals(LocalTime.of(15, 30))).findFirst().orElseThrow();
        // Tổng chỗ lúc 15:30 = min(6, (3-1) + (3-0)) = 5 chỗ thực tế
        assertEquals(5, slot1530.remaining());

        when(staffRepo.lockById(2L)).thenReturn(Optional.of(doc2));
        when(staffRepo.findById(2L)).thenReturn(Optional.of(doc2));
        when(appointments.lockDoctorAppointmentsForDate(2L, date, AppointmentSlotService.OCCUPYING)).thenReturn(List.of());
        StaffProfile chosen = richSlots.autoAssignDoctorForSlot(branch, date, PreferredSession.AFTERNOON, LocalTime.of(15, 30), 30, null);
        assertNotNull(chosen);
        assertEquals(2L, chosen.getStaffId());
    }

    @Test
    void actualEndTimeReleaseAndGrace15MinReleaseFreeWorkloadImmediately() {
        LocalDate fixedToday = LocalDate.of(2026, 11, 20);
        Clock fixedClock = Clock.fixed(LocalDateTime.of(fixedToday, LocalTime.of(8, 16)).atZone(ZONE).toInstant(), ZONE);

        StaffScheduleRepository schRepo = mock(StaffScheduleRepository.class);
        LeaveRequestRepository leaveRepo = mock(LeaveRequestRepository.class);
        VisitRepository visitRepo = mock(VisitRepository.class);
        StaffProfileRepository staffRepo = mock(StaffProfileRepository.class);
        AppointmentSlotService richSlots = new AppointmentSlotService(
                appointments, windows, policy, schRepo, leaveRepo, visitRepo, staffRepo);
        richSlots.setMaxConcurrentPerDoctorSlot(3);
        richSlots.setClock(fixedClock);
        richSlots.setMaxConcurrentPerDoctorSlot(1); // Cô lập test giải phóng sớm và grace 15p

        Branch b = branch();
        b.setOpeningTime(LocalTime.of(8, 0)); b.setClosingTime(LocalTime.of(20, 0));
        StaffProfile doc = new StaffProfile(); doc.setStaffId(7L); doc.setStatus(StaffStatus.ACTIVE);
        WorkShift am = new WorkShift(); am.setShiftId(1L); am.setIsActive(true);
        am.setStartTime(LocalTime.of(8, 0)); am.setEndTime(LocalTime.of(12, 0));
        StaffSchedule sch = new StaffSchedule(); sch.setStaff(doc); sch.setBranch(b); sch.setShift(am); sch.setWorkDate(fixedToday); sch.setStatus(ScheduleStatus.SCHEDULED);
        when(schRepo.findDoctorSchedulesForDate(7L, fixedToday, ScheduleStatus.SCHEDULED)).thenReturn(List.of(sch));
        when(leaveRepo.findApprovedForStaffOnDate(7L, fixedToday, LeaveStatus.APPROVED)).thenReturn(List.of());

        // Ca lúc 08:00 chưa check-in quá 15p tại 08:16 -> được giải phóng; ca 09:00-10:00 hoàn tất sớm lúc 09:20 -> giải phóng [09:20, 10:00)
        Appointment missed = new Appointment(); missed.setAppointmentId(1L); missed.setDoctor(doc);
        missed.setExpectedTime(LocalTime.of(8, 0)); missed.setDurationMinutes(30); missed.setStatus(AppointmentStatus.CONFIRMED);
        Appointment doneEarly = new Appointment(); doneEarly.setAppointmentId(2L); doneEarly.setDoctor(doc);
        doneEarly.setExpectedTime(LocalTime.of(9, 0)); doneEarly.setDurationMinutes(60); doneEarly.setStatus(AppointmentStatus.CHECKED_IN);
        Visit v2 = new Visit(); v2.setVisitId(200L); v2.setAppointmentId(2L);
        v2.setActualEndTime(LocalDateTime.of(fixedToday, LocalTime.of(9, 20)));

        when(appointments.findDoctorAppointmentsForDate(7L, fixedToday, AppointmentSlotService.OCCUPYING)).thenReturn(List.of(missed, doneEarly));
        when(visitRepo.findByAppointmentIdIn(List.of(2L))).thenReturn(List.of(v2));

        var result = richSlots.getDoctorAvailability(4L, 7L, fixedToday, 30, null);
        assertTrue(result.suggestedSlots().stream().anyMatch(s -> s.startTime().equals(LocalTime.of(9, 20)) || s.startTime().equals(LocalTime.of(9, 30))),
                "Phần thời gian còn lại sau actual_end_time (09:20) phải được giải phóng");

        // Nếu mở lại ca khám xóa actualEndTime -> khung 09:30 bị chiếm lại
        v2.setActualEndTime(null);
        var reopenedResult = richSlots.getDoctorAvailability(4L, 7L, fixedToday, 30, null);
        assertTrue(reopenedResult.suggestedSlots().stream().noneMatch(s -> s.startTime().equals(LocalTime.of(9, 30))),
                "Khi reopen xóa actualEndTime, slot 09:30 phải bị chiếm giữ trở lại");
    }
}
