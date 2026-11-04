package com.cvdcms;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.LeaveStatus;
import com.cvdcms.entity.enums.LeaveType;
import com.cvdcms.entity.enums.ScheduleStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.LeaveRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorLeaveRequestUnitTest {

    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private StaffScheduleRepository scheduleRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private StaffProfileRepository staffRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private WorkShiftRepository workShiftRepository;
    @Mock private UserRepository userRepository;
    @Mock private SystemNotificationRepository notificationRepository;
    @Mock private StaffBranchAssignmentRepository assignmentRepository;

    private LeaveRequestService leaveService;

    private StaffProfile sampleDoctor;
    private User doctorUser;
    private User adminUser;
    private Branch sampleBranch;
    private WorkShift morningShift;

    @BeforeEach
    void setUp() {
        leaveService = new LeaveRequestService(
                leaveRequestRepository,
                scheduleRepository,
                appointmentRepository,
                staffRepository,
                branchRepository,
                workShiftRepository,
                userRepository,
                notificationRepository,
                assignmentRepository
        );

        doctorUser = new User();
        doctorUser.setUserId(10L);
        doctorUser.setUsername("doctor_minh");

        sampleDoctor = new StaffProfile();
        sampleDoctor.setStaffId(1L);
        sampleDoctor.setStaffCode("BS001");
        sampleDoctor.setFullName("Bác sĩ Nguyễn Văn Minh");
        sampleDoctor.setUser(doctorUser);

        adminUser = new User();
        adminUser.setUserId(99L);
        adminUser.setUsername("admin");

        sampleBranch = new Branch();
        sampleBranch.setBranchId(2L);
        sampleBranch.setBranchName("Cơ sở Đống Đa");

        morningShift = new WorkShift();
        morningShift.setShiftId(5L);
        morningShift.setShiftName("Ca sáng");
        morningShift.setStartTime(LocalTime.of(8, 0));
        morningShift.setEndTime(LocalTime.of(12, 0));
        morningShift.setBranch(sampleBranch);
    }

    @Test
    @DisplayName("SCR-42.1 / Rule R8: Nộp đơn sát ngày (< 3 ngày) mà không đánh dấu Khẩn cấp phải ném ngoại lệ")
    void submitLeave_ViolatingRuleR8_ShouldThrowException() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(sampleDoctor));
        when(branchRepository.findById(2L)).thenReturn(Optional.of(sampleBranch));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            leaveService.submitDoctorLeave(
                    10L, 2L, tomorrow, tomorrow, null, LeaveType.ANNUAL, false, "Có việc đột xuất cần nghỉ"
            );
        });

        assertTrue(ex.getMessage().contains("R8"), "Thông báo phải nhắc tới Quy tắc R8");
        assertTrue(ex.getMessage().contains("ít nhất 3 ngày"));
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("SCR-42.1 / Rule R8: Nộp đơn sát ngày (< 3 ngày) có đánh dấu Khẩn cấp & lý do rõ ràng phải thành công")
    void submitLeave_EmergencyShortNotice_ShouldSucceed() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(sampleDoctor));
        when(branchRepository.findById(2L)).thenReturn(Optional.of(sampleBranch));
        when(leaveRequestRepository.existsOverlapping(any(), any(), any(), any(), any())).thenReturn(false);
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(i -> {
            LeaveRequest req = i.getArgument(0);
            req.setLeaveId(101L);
            return req;
        });

        LeaveRequest result = leaveService.submitDoctorLeave(
                10L, 2L, tomorrow, tomorrow, null, LeaveType.SICK, true, "Bị sốt xuất huyết đột xuất cần nhập viện"
        );

        assertNotNull(result);
        assertEquals(101L, result.getLeaveId());
        assertTrue(result.getIsEmergency());
        assertEquals(LeaveStatus.PENDING, result.getStatus());
        verify(leaveRequestRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("SCR-42.1: Báo trước >= 3 ngày theo đúng chuẩn R8 thì tạo đơn PENDING thành công")
    void submitLeave_StandardNotice_ShouldSucceed() {
        LocalDate futureDate = LocalDate.now().plusDays(5);

        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(sampleDoctor));
        when(branchRepository.findById(2L)).thenReturn(Optional.of(sampleBranch));
        when(leaveRequestRepository.existsOverlapping(any(), any(), any(), any(), any())).thenReturn(false);
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(i -> {
            LeaveRequest req = i.getArgument(0);
            req.setLeaveId(102L);
            return req;
        });

        LeaveRequest result = leaveService.submitDoctorLeave(
                10L, 2L, futureDate, futureDate.plusDays(2), null, LeaveType.ANNUAL, false, "Nghỉ phép năm đi du lịch cùng gia đình"
        );

        assertNotNull(result);
        assertEquals(LeaveStatus.PENDING, result.getStatus());
        assertFalse(result.getIsEmergency());
    }

    @Test
    @DisplayName("SCR-42.1: Chặn nộp đơn nếu đã có đơn trùng lịch đang PENDING hoặc APPROVED")
    void submitLeave_OverlappingActiveLeave_ShouldThrowException() {
        LocalDate futureDate = LocalDate.now().plusDays(5);

        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(sampleDoctor));
        when(branchRepository.findById(2L)).thenReturn(Optional.of(sampleBranch));
        when(leaveRequestRepository.existsOverlapping(eq(1L), eq(futureDate), eq(futureDate), isNull(), any()))
                .thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            leaveService.submitDoctorLeave(
                    10L, 2L, futureDate, futureDate, null, LeaveType.ANNUAL, false, "Nghỉ phép năm gia đình"
            );
        });

        assertTrue(ex.getMessage().contains("khoảng thời gian"), "Phải chứa thông báo trùng lịch: " + ex.getMessage());
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("SCR-42.2: Bác sĩ tự hủy đơn khi đơn đang PENDING")
    void cancelLeaveRequest_WhenPending_ShouldSucceed() {
        LeaveRequest req = new LeaveRequest();
        req.setLeaveId(200L);
        req.setStaff(sampleDoctor);
        req.setStatus(LeaveStatus.PENDING);

        when(leaveRequestRepository.findById(200L)).thenReturn(Optional.of(req));
        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(sampleDoctor));

        leaveService.cancelLeaveRequest(200L, 10L);

        assertEquals(LeaveStatus.CANCELLED, req.getStatus());
        verify(leaveRequestRepository, times(1)).save(req);
    }

    @Test
    @DisplayName("SCR-42.4: Admin phê duyệt đơn -> Tự động khóa lịch trực StaffSchedule sang ON_LEAVE")
    void approveLeaveRequest_ShouldLockScheduleAndNotifyDoctor() {
        LocalDate leaveDate = LocalDate.now().plusDays(4);

        LeaveRequest req = new LeaveRequest();
        req.setLeaveId(300L);
        req.setStaff(sampleDoctor);
        req.setBranch(sampleBranch);
        req.setStartDate(leaveDate);
        req.setEndDate(leaveDate);
        req.setStatus(LeaveStatus.PENDING);

        StaffSchedule schedule = new StaffSchedule();
        schedule.setScheduleId(88L);
        schedule.setStaff(sampleDoctor);
        schedule.setWorkDate(leaveDate);
        schedule.setStatus(ScheduleStatus.SCHEDULED);

        when(leaveRequestRepository.findByIdWithDetails(300L)).thenReturn(Optional.of(req));
        when(userRepository.findById(99L)).thenReturn(Optional.of(adminUser));
        when(scheduleRepository.findByStaffStaffIdAndWorkDateBetween(sampleDoctor.getStaffId(), leaveDate, leaveDate))
                .thenReturn(List.of(schedule));

        leaveService.approveLeaveRequest(300L, 99L, "Đã chấp thuận cho bác sĩ nghỉ");

        assertEquals(LeaveStatus.APPROVED, req.getStatus());
        assertEquals(adminUser, req.getReviewedBy());
        assertNotNull(req.getReviewedAt());

        // Kiểm tra ca trực được tự động khóa sang ON_LEAVE
        assertEquals(ScheduleStatus.ON_LEAVE, schedule.getStatus());
        assertTrue(schedule.getNote().contains("#LR-300"));

        // Kiểm tra gửi thông báo hệ thống cho Bác sĩ
        verify(notificationRepository, times(1)).save(any(SystemNotification.class));
    }

    @Test
    @DisplayName("SCR-42.4: Admin từ chối đơn phải có lý do reviewNote")
    void rejectLeaveRequest_WithoutNote_ShouldThrowException() {
        LeaveRequest req = new LeaveRequest();
        req.setLeaveId(301L);
        req.setStatus(LeaveStatus.PENDING);

        when(leaveRequestRepository.findByIdWithDetails(301L)).thenReturn(Optional.of(req));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            leaveService.rejectLeaveRequest(301L, 99L, "   ");
        });

        assertTrue(ex.getMessage().contains("lý do"));
    }

    @Test
    @DisplayName("SCR-42.5: Quét danh sách lịch hẹn bệnh nhân bị ảnh hưởng khi Bác sĩ nghỉ theo ca")
    void getAffectedAppointments_ShouldFilterByShiftTime() {
        LocalDate leaveDate = LocalDate.now().plusDays(4);

        LeaveRequest req = new LeaveRequest();
        req.setLeaveId(400L);
        req.setStaff(sampleDoctor);
        req.setStartDate(leaveDate);
        req.setEndDate(leaveDate);
        req.setShift(morningShift); // 08:00 -> 12:00

        Appointment apptMorning = new Appointment();
        apptMorning.setAppointmentId(1L);
        apptMorning.setAppointmentDate(leaveDate);
        apptMorning.setExpectedTime(LocalTime.of(9, 30)); // Nằm trong ca sáng -> Bị ảnh hưởng

        Appointment apptAfternoon = new Appointment();
        apptAfternoon.setAppointmentId(2L);
        apptAfternoon.setAppointmentDate(leaveDate);
        apptAfternoon.setExpectedTime(LocalTime.of(14, 0)); // Nằm ngoài ca sáng -> Không bị ảnh hưởng

        when(leaveRequestRepository.findByIdWithDetails(400L)).thenReturn(Optional.of(req));
        when(appointmentRepository.findAffectedAppointments(any(), any(), any(), any()))
                .thenReturn(List.of(apptMorning, apptAfternoon));

        List<Appointment> affected = leaveService.getAffectedAppointments(400L);

        assertEquals(1, affected.size());
        assertEquals(1L, affected.get(0).getAppointmentId());
    }
}

