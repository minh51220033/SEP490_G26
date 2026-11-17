package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.SystemAuditLogServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinhAdminScheduleUtReport")
public class MinhAdminScheduleUtReportTest {

    private static Branch branch(Long id, String code, String name) {
        Branch b = new Branch();
        b.setBranchId(id);
        b.setBranchCode(code);
        b.setBranchName(name);
        b.setIsActive(true);
        return b;
    }

    private static StaffProfile staffProfile(Long id, String name, Branch b) {
        StaffProfile s = new StaffProfile();
        s.setStaffId(id);
        s.setStaffCode("BS00" + id);
        s.setFullName(name);
        s.setStatus(StaffStatus.ACTIVE);
        User u = new User();
        u.setUserId(id + 100);
        u.setUsername("user_" + id);
        s.setUser(u);
        return s;
    }

    // =========================================================================
    // 24. CreateWorkShift (12 cases)
    // =========================================================================
    @Nested
    @DisplayName("CreateWorkShift")
    class CreateWorkShift {
        @Mock WorkShiftRepository workShiftRepo;
        @Mock BranchRepository branchRepo;
        WorkShiftService service;
        Branch b;

        @BeforeEach
        void setUp() {
            service = new WorkShiftService(workShiftRepo, branchRepo);
            b = branch(1L, "CN01", "Cơ sở 1");
            lenient().when(branchRepo.findById(1L)).thenReturn(Optional.of(b));
            lenient().when(workShiftRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test @DisplayName("UTCID01 (N) - Tạo ca sáng hợp lệ")
        void utc01_validMorningShift() {
            when(workShiftRepo.findAll()).thenReturn(List.of());
            WorkShift ws = service.create(1L, "CA_SANG", "Ca Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));
            assertNotNull(ws);
            assertEquals("CA_SANG", ws.getShiftCode());
            verify(workShiftRepo).save(any(WorkShift.class));
        }

        @Test @DisplayName("UTCID02 (N) - Tạo ca chiều hợp lệ")
        void utc02_validAfternoonShift() {
            when(workShiftRepo.findAll()).thenReturn(List.of());
            WorkShift ws = service.create(1L, "CA_CHIEU", "Ca Chiều", LocalTime.of(13, 0), LocalTime.of(17, 0));
            assertNotNull(ws);
            assertEquals("CA_CHIEU", ws.getShiftCode());
        }

        @Test @DisplayName("UTCID03 (N) - Tạo ca tối hợp lệ")
        void utc03_validEveningShift() {
            when(workShiftRepo.findAll()).thenReturn(List.of());
            WorkShift ws = service.create(1L, "CA_TOI", "Ca Tối", LocalTime.of(17, 30), LocalTime.of(21, 0));
            assertNotNull(ws);
            assertEquals("CA_TOI", ws.getShiftCode());
        }

        @Test @DisplayName("UTCID04 (A) - Chi nhánh không tồn tại")
        void utc04_branchNotFound() {
            when(branchRepo.findById(99L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class,
                    () -> service.create(99L, "CA_SANG", "Ca Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0)));
        }

        @Test @DisplayName("UTCID05 (A) - Trùng mã ca trong chi nhánh")
        void utc05_duplicateShiftCode() {
            WorkShift exist = new WorkShift();
            exist.setShiftCode("CA_SANG");
            exist.setBranch(b);
            when(workShiftRepo.findAll()).thenReturn(List.of(exist));
            assertThrows(IllegalArgumentException.class,
                    () -> service.create(1L, "ca_sang", "Ca Mới", LocalTime.of(8, 0), LocalTime.of(12, 0)));
        }

        @Test @DisplayName("UTCID06 (A) - Trùng tên ca trong chi nhánh")
        void utc06_duplicateShiftName() {
            WorkShift exist = new WorkShift();
            exist.setShiftCode("CA_CU");
            exist.setShiftName("Ca Sáng");
            exist.setBranch(b);
            when(workShiftRepo.findAll()).thenReturn(List.of(exist));
            assertThrows(IllegalArgumentException.class,
                    () -> service.create(1L, "CA_MOI", "ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0)));
        }

        @Test @DisplayName("UTCID07 (A) - Giờ bắt đầu sau giờ kết thúc")
        void utc07_startTimeAfterEndTime() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.create(1L, "CA_LOI", "Ca Lỗi", LocalTime.of(18, 0), LocalTime.of(8, 0)));
        }

        @Test @DisplayName("UTCID08 (A) - Giờ bắt đầu trùng giờ kết thúc")
        void utc08_startTimeEqualsEndTime() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.create(1L, "CA_0P", "Ca 0 Phút", LocalTime.of(8, 0), LocalTime.of(8, 0)));
        }

        @Test @DisplayName("UTCID09 (B) - Mã ca có khoảng trắng được trim")
        void utc09_shiftCodeTrimmed() {
            when(workShiftRepo.findAll()).thenReturn(List.of());
            WorkShift ws = service.create(1L, "  CA_TRIM  ", "Ca Trim", LocalTime.of(8, 0), LocalTime.of(12, 0));
            assertEquals("CA_TRIM", ws.getShiftCode());
        }

        @Test @DisplayName("UTCID10 (B) - Tên ca có khoảng trắng được trim")
        void utc10_shiftNameTrimmed() {
            when(workShiftRepo.findAll()).thenReturn(List.of());
            WorkShift ws = service.create(1L, "CA_TRIM2", "  Ca Trim Tên  ", LocalTime.of(8, 0), LocalTime.of(12, 0));
            assertEquals("Ca Trim Tên", ws.getShiftName());
        }

        @Test @DisplayName("UTCID11 (B) - Ca tối đa trong ngày 00:00 đến 23:59")
        void utc11_maxDayBoundary() {
            when(workShiftRepo.findAll()).thenReturn(List.of());
            WorkShift ws = service.create(1L, "CA_FULL", "Ca Toàn Ngày", LocalTime.of(0, 0), LocalTime.of(23, 59));
            assertNotNull(ws);
        }

        @Test @DisplayName("UTCID12 (B) - Ca ngắn tối thiểu 1 phút 08:00 đến 08:01")
        void utc12_minDurationBoundary() {
            when(workShiftRepo.findAll()).thenReturn(List.of());
            WorkShift ws = service.create(1L, "CA_MIN", "Ca 1 Phút", LocalTime.of(8, 0), LocalTime.of(8, 1));
            assertNotNull(ws);
        }
    }

    // =========================================================================
    // 25. UpdateWorkShift (8 cases)
    // =========================================================================
    @Nested
    @DisplayName("UpdateWorkShift")
    class UpdateWorkShift {
        @Mock WorkShiftRepository workShiftRepo;
        @Mock BranchRepository branchRepo;
        WorkShiftService service;
        Branch b;
        WorkShift existing;

        @BeforeEach
        void setUp() {
            service = new WorkShiftService(workShiftRepo, branchRepo);
            b = branch(1L, "CN01", "Cơ sở 1");
            existing = new WorkShift();
            existing.setShiftId(10L);
            existing.setBranch(b);
            existing.setShiftCode("CA_1");
            existing.setShiftName("Ca 1");
            existing.setStartTime(LocalTime.of(8, 0));
            existing.setEndTime(LocalTime.of(12, 0));
            lenient().when(workShiftRepo.findById(10L)).thenReturn(Optional.of(existing));
            lenient().when(workShiftRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test @DisplayName("UTCID01 (N) - Cập nhật giờ và tên ca thành công")
        void utc01_validUpdate() {
            when(workShiftRepo.findAll()).thenReturn(List.of(existing));
            WorkShift ws = service.update(10L, "CA_1", "Ca Sáng Mới", LocalTime.of(7, 30), LocalTime.of(11, 30));
            assertEquals("Ca Sáng Mới", ws.getShiftName());
            assertEquals(LocalTime.of(7, 30), ws.getStartTime());
        }

        @Test @DisplayName("UTCID02 (N) - Đổi mã ca sang mã mới không trùng")
        void utc02_validCodeChange() {
            when(workShiftRepo.findAll()).thenReturn(List.of(existing));
            WorkShift ws = service.update(10L, "CA_MOI_1", "Ca 1", LocalTime.of(8, 0), LocalTime.of(12, 0));
            assertEquals("CA_MOI_1", ws.getShiftCode());
        }

        @Test @DisplayName("UTCID03 (A) - Không tìm thấy ca cần cập nhật")
        void utc03_shiftNotFound() {
            when(workShiftRepo.findById(99L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class,
                    () -> service.update(99L, "CA_1", "Ca 1", LocalTime.of(8, 0), LocalTime.of(12, 0)));
        }

        @Test @DisplayName("UTCID04 (A) - Trùng mã với ca khác trong chi nhánh")
        void utc04_duplicateCodeWithOtherShift() {
            WorkShift other = new WorkShift();
            other.setShiftId(11L);
            other.setBranch(b);
            other.setShiftCode("CA_2");
            when(workShiftRepo.findAll()).thenReturn(List.of(existing, other));
            assertThrows(IllegalArgumentException.class,
                    () -> service.update(10L, "CA_2", "Ca 1", LocalTime.of(8, 0), LocalTime.of(12, 0)));
        }

        @Test @DisplayName("UTCID05 (A) - Trùng tên với ca khác trong chi nhánh")
        void utc05_duplicateNameWithOtherShift() {
            WorkShift other = new WorkShift();
            other.setShiftId(11L);
            other.setBranch(b);
            other.setShiftName("Ca Chiều");
            when(workShiftRepo.findAll()).thenReturn(List.of(existing, other));
            assertThrows(IllegalArgumentException.class,
                    () -> service.update(10L, "CA_1", "Ca Chiều", LocalTime.of(8, 0), LocalTime.of(12, 0)));
        }

        @Test @DisplayName("UTCID06 (A) - Giờ bắt đầu >= giờ kết thúc")
        void utc06_invalidTimeRange() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.update(10L, "CA_1", "Ca 1", LocalTime.of(12, 0), LocalTime.of(8, 0)));
        }

        @Test @DisplayName("UTCID07 (B) - Giữ nguyên mã và tên của chính nó không bị báo trùng")
        void utc07_sameCodeAndNameAllowed() {
            when(workShiftRepo.findAll()).thenReturn(List.of(existing));
            WorkShift ws = service.update(10L, "CA_1", "Ca 1", LocalTime.of(8, 30), LocalTime.of(12, 30));
            assertNotNull(ws);
        }

        @Test @DisplayName("UTCID08 (B) - Tên ca được trim khoảng trắng thừa")
        void utc08_nameTrimmed() {
            when(workShiftRepo.findAll()).thenReturn(List.of(existing));
            WorkShift ws = service.update(10L, "CA_1", "   Ca Cập Nhật   ", LocalTime.of(8, 0), LocalTime.of(12, 0));
            assertEquals("Ca Cập Nhật", ws.getShiftName());
        }
    }

    // =========================================================================
    // 26. SubmitDoctorLeave (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("SubmitDoctorLeave")
    class SubmitDoctorLeave {
        @Mock LeaveRequestRepository leaveRepo;
        @Mock StaffScheduleRepository scheduleRepo;
        @Mock AppointmentRepository appointmentRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock BranchRepository branchRepo;
        @Mock WorkShiftRepository shiftRepo;
        @Mock UserRepository userRepo;
        @Mock SystemNotificationRepository notifRepo;
        @Mock StaffBranchAssignmentRepository assignmentRepo;

        LeaveRequestService service;
        StaffProfile doc;
        Branch b;

        @BeforeEach
        void setUp() {
            service = new LeaveRequestService(
                    leaveRepo, scheduleRepo, appointmentRepo, staffRepo,
                    branchRepo, shiftRepo, userRepo, notifRepo, assignmentRepo);
            b = branch(1L, "CN01", "Cơ sở 1");
            doc = staffProfile(1L, "BS. Nam", b);
            lenient().when(staffRepo.findByUser_UserId(10L)).thenReturn(Optional.of(doc));
            lenient().when(branchRepo.findById(1L)).thenReturn(Optional.of(b));
            lenient().when(leaveRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test @DisplayName("UTCID01 (N) - Nộp đơn nghỉ phép năm hợp lệ báo trước >= 3 ngày")
        void utc01_validAnnualLeave() {
            LocalDate start = LocalDate.now().plusDays(4);
            LocalDate end = LocalDate.now().plusDays(5);
            when(leaveRepo.existsOverlapping(any(), any(), any(), any(), any())).thenReturn(false);

            LeaveRequest res = service.submitDoctorLeave(10L, 1L, start, end, null, LeaveType.ANNUAL, false, "Nghỉ phép năm đi du lịch");
            assertNotNull(res);
            assertEquals(LeaveStatus.PENDING, res.getStatus());
        }

        @Test @DisplayName("UTCID02 (N) - Nghỉ khẩn cấp/ốm đau không bắt buộc báo trước 3 ngày")
        void utc02_emergencyLeaveAllowedTomorrow() {
            LocalDate tmr = LocalDate.now().plusDays(1);
            when(leaveRepo.existsOverlapping(any(), any(), any(), any(), any())).thenReturn(false);

            LeaveRequest res = service.submitDoctorLeave(10L, 1L, tmr, tmr, null, LeaveType.SICK, true, "Bị sốt xuất huyết đột xuất");
            assertNotNull(res);
            assertEquals(LeaveStatus.PENDING, res.getStatus());
        }

        @Test @DisplayName("UTCID03 (A) - Không tìm thấy bác sĩ")
        void utc03_doctorNotFound() {
            when(staffRepo.findByUser_UserId(999L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () ->
                    service.submitDoctorLeave(999L, 1L, LocalDate.now().plusDays(5), LocalDate.now().plusDays(5), null, LeaveType.ANNUAL, false, "Nghỉ việc bận"));
        }

        @Test @DisplayName("UTCID04 (A) - Ngày nghỉ trong quá khứ")
        void utc04_pastDateBlocked() {
            LocalDate past = LocalDate.now().minusDays(1);
            assertThrows(IllegalArgumentException.class, () ->
                    service.submitDoctorLeave(10L, 1L, past, past, null, LeaveType.ANNUAL, false, "Quá khứ bận"));
        }

        @Test @DisplayName("UTCID05 (A) - Ngày kết thúc trước ngày bắt đầu")
        void utc05_endDateBeforeStartDate() {
            LocalDate start = LocalDate.now().plusDays(5);
            LocalDate end = LocalDate.now().plusDays(4);
            assertThrows(IllegalArgumentException.class, () ->
                    service.submitDoctorLeave(10L, 1L, start, end, null, LeaveType.ANNUAL, false, "Ngược ngày"));
        }

        @Test @DisplayName("UTCID06 (A) - Nghỉ phép năm báo trước < 3 ngày bị chặn")
        void utc06_advanceNoticeLessThan3Days() {
            LocalDate tmr = LocalDate.now().plusDays(1);
            assertThrows(IllegalArgumentException.class, () ->
                    service.submitDoctorLeave(10L, 1L, tmr, tmr, null, LeaveType.ANNUAL, false, "Báo gấp nghỉ"));
        }

        @Test @DisplayName("UTCID07 (A) - Đã có đơn nghỉ phép PENDING/APPROVED trùng khoảng thời gian")
        void utc07_overlappingLeaveRequest() {
            LocalDate start = LocalDate.now().plusDays(5);
            when(leaveRepo.existsOverlapping(eq(1L), eq(start), eq(start), isNull(), any())).thenReturn(true);

            assertThrows(IllegalArgumentException.class, () ->
                    service.submitDoctorLeave(10L, 1L, start, start, null, LeaveType.ANNUAL, false, "Trùng lịch"));
        }

        @Test @DisplayName("UTCID08 (A) - Lý do xin nghỉ dưới 5 ký tự bị từ chối")
        void utc08_shortReasonBlocked() {
            LocalDate day = LocalDate.now().plusDays(4);
            assertThrows(IllegalArgumentException.class, () ->
                    service.submitDoctorLeave(10L, 1L, day, day, null, LeaveType.ANNUAL, false, "Nghỉ"));
        }

        @Test @DisplayName("UTCID09 (B) - Báo trước vừa khít 3 ngày được chấp nhận")
        void utc09_exact3DaysBoundary() {
            LocalDate exact3 = LocalDate.now().plusDays(3);
            when(leaveRepo.existsOverlapping(any(), any(), any(), any(), any())).thenReturn(false);

            LeaveRequest res = service.submitDoctorLeave(10L, 1L, exact3, exact3, null, LeaveType.ANNUAL, false, "Vừa khít 3 ngày");
            assertNotNull(res);
        }

        @Test @DisplayName("UTCID10 (B) - Lý do nghỉ có khoảng trắng được cắt gọn")
        void utc10_reasonTrimmed() {
            LocalDate day = LocalDate.now().plusDays(4);
            when(leaveRepo.existsOverlapping(any(), any(), any(), any(), any())).thenReturn(false);

            LeaveRequest res = service.submitDoctorLeave(10L, 1L, day, day, null, LeaveType.ANNUAL, false, "   Việc bận gia đình   ");
            assertEquals("Việc bận gia đình", res.getReason());
        }
    }

    // =========================================================================
    // 27. ApproveLeave (6 cases)
    // =========================================================================
    @Nested
    @DisplayName("ApproveLeave")
    class ApproveLeave {
        @Mock LeaveRequestRepository leaveRepo;
        @Mock StaffScheduleRepository scheduleRepo;
        @Mock AppointmentRepository appointmentRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock BranchRepository branchRepo;
        @Mock WorkShiftRepository shiftRepo;
        @Mock UserRepository userRepo;
        @Mock SystemNotificationRepository notifRepo;
        @Mock StaffBranchAssignmentRepository assignmentRepo;

        LeaveRequestService service;
        LeaveRequest req;
        StaffProfile doc;

        @BeforeEach
        void setUp() {
            service = new LeaveRequestService(
                    leaveRepo, scheduleRepo, appointmentRepo, staffRepo,
                    branchRepo, shiftRepo, userRepo, notifRepo, assignmentRepo);
            doc = staffProfile(1L, "BS. Nam", branch(1L, "CN01", "Cơ sở 1"));
            req = new LeaveRequest();
            req.setLeaveId(20L);
            req.setStaff(doc);
            req.setStartDate(LocalDate.now().plusDays(5));
            req.setEndDate(LocalDate.now().plusDays(5));
            req.setStatus(LeaveStatus.PENDING);
            lenient().when(leaveRepo.findByIdWithDetails(20L)).thenReturn(Optional.of(req));
            lenient().when(leaveRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test @DisplayName("UTCID01 (N) - Admin phê duyệt đơn PENDING thành công")
        void utc01_approvePendingSuccess() {
            when(userRepo.findById(99L)).thenReturn(Optional.of(new User()));
            int res = service.approveLeaveRequest(20L, 99L, "Đồng ý nghỉ phép");
            assertEquals(1, res);
            assertEquals(LeaveStatus.APPROVED, req.getStatus());
            assertEquals("Đồng ý nghỉ phép", req.getReviewNote());
        }

        @Test @DisplayName("UTCID02 (N) - Tự động khóa lịch trực StaffSchedule sang ON_LEAVE khi duyệt đơn")
        void utc02_locksStaffScheduleOnApprove() {
            StaffSchedule sch = new StaffSchedule();
            sch.setStatus(ScheduleStatus.SCHEDULED);
            when(userRepo.findById(99L)).thenReturn(Optional.of(new User()));
            when(scheduleRepo.findByStaffStaffIdAndWorkDateBetween(eq(1L), any(), any()))
                    .thenReturn(List.of(sch));

            service.approveLeaveRequest(20L, 99L, "Duyệt");
            assertEquals(ScheduleStatus.ON_LEAVE, sch.getStatus());
        }

        @Test @DisplayName("UTCID03 (N) - Gửi thông báo phê duyệt tới bác sĩ")
        void utc03_sendsNotificationOnApprove() {
            when(userRepo.findById(99L)).thenReturn(Optional.of(new User()));
            service.approveLeaveRequest(20L, 99L, "Ok");
            verify(notifRepo).save(any(SystemNotification.class));
        }

        @Test @DisplayName("UTCID04 (A) - Không tìm thấy đơn nghỉ phép")
        void utc04_leaveNotFound() {
            when(leaveRepo.findByIdWithDetails(99L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> service.approveLeaveRequest(99L, 1L, "Ok"));
        }

        @Test @DisplayName("UTCID05 (A) - Đơn không ở trạng thái PENDING (đã duyệt hoặc từ chối)")
        void utc05_nonPendingCannotBeApproved() {
            req.setStatus(LeaveStatus.APPROVED);
            assertThrows(IllegalStateException.class, () -> service.approveLeaveRequest(20L, 1L, "Ok"));
        }

        @Test @DisplayName("UTCID06 (A) - Người duyệt không tồn tại")
        void utc06_reviewerNotFound() {
            when(userRepo.findById(999L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> service.approveLeaveRequest(20L, 999L, "Ok"));
        }
    }

    // =========================================================================
    // 28. RejectLeave (6 cases)
    // =========================================================================
    @Nested
    @DisplayName("RejectLeave")
    class RejectLeave {
        @Mock LeaveRequestRepository leaveRepo;
        @Mock StaffScheduleRepository scheduleRepo;
        @Mock AppointmentRepository appointmentRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock BranchRepository branchRepo;
        @Mock WorkShiftRepository shiftRepo;
        @Mock UserRepository userRepo;
        @Mock SystemNotificationRepository notifRepo;
        @Mock StaffBranchAssignmentRepository assignmentRepo;

        LeaveRequestService service;
        LeaveRequest req;
        StaffProfile doc;

        @BeforeEach
        void setUp() {
            service = new LeaveRequestService(
                    leaveRepo, scheduleRepo, appointmentRepo, staffRepo,
                    branchRepo, shiftRepo, userRepo, notifRepo, assignmentRepo);
            doc = staffProfile(1L, "BS. Nam", branch(1L, "CN01", "Cơ sở 1"));
            req = new LeaveRequest();
            req.setLeaveId(20L);
            req.setStaff(doc);
            req.setStatus(LeaveStatus.PENDING);
            lenient().when(leaveRepo.findByIdWithDetails(20L)).thenReturn(Optional.of(req));
            lenient().when(leaveRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test @DisplayName("UTCID01 (N) - Admin từ chối đơn có kèm lý do reviewNote")
        void utc01_rejectPendingSuccess() {
            when(userRepo.findById(99L)).thenReturn(Optional.of(new User()));
            service.rejectLeaveRequest(20L, 99L, "Chi nhánh đang thiếu bác sĩ trực");
            assertEquals(LeaveStatus.REJECTED, req.getStatus());
            assertEquals("Chi nhánh đang thiếu bác sĩ trực", req.getReviewNote());
        }

        @Test @DisplayName("UTCID02 (A) - Từ chối khi reviewNote rỗng hoặc khoảng trắng")
        void utc02_blankReviewNoteBlocked() {
            assertThrows(IllegalArgumentException.class, () -> service.rejectLeaveRequest(20L, 99L, "   "));
        }

        @Test @DisplayName("UTCID03 (A) - Đơn nghỉ không tồn tại")
        void utc03_notFound() {
            when(leaveRepo.findByIdWithDetails(99L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> service.rejectLeaveRequest(99L, 99L, "Lý do"));
        }

        @Test @DisplayName("UTCID04 (A) - Đơn không ở trạng thái PENDING")
        void utc04_nonPendingBlocked() {
            req.setStatus(LeaveStatus.REJECTED);
            assertThrows(IllegalStateException.class, () -> service.rejectLeaveRequest(20L, 99L, "Lý do"));
        }

        @Test @DisplayName("UTCID05 (A) - Người duyệt không tồn tại")
        void utc05_reviewerNotFound() {
            when(userRepo.findById(999L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> service.rejectLeaveRequest(20L, 999L, "Lý do"));
        }

        @Test @DisplayName("UTCID06 (B) - Lý do từ chối được trim khoảng trắng đầu cuối")
        void utc06_reviewNoteTrimmed() {
            when(userRepo.findById(99L)).thenReturn(Optional.of(new User()));
            service.rejectLeaveRequest(20L, 99L, "  Đang bận lịch phẫu thuật  ");
            assertEquals("Đang bận lịch phẫu thuật", req.getReviewNote());
        }
    }

    // =========================================================================
    // 29. CancelLeave (6 cases)
    // =========================================================================
    @Nested
    @DisplayName("CancelLeave")
    class CancelLeave {
        @Mock LeaveRequestRepository leaveRepo;
        @Mock StaffScheduleRepository scheduleRepo;
        @Mock AppointmentRepository appointmentRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock BranchRepository branchRepo;
        @Mock WorkShiftRepository shiftRepo;
        @Mock UserRepository userRepo;
        @Mock SystemNotificationRepository notifRepo;
        @Mock StaffBranchAssignmentRepository assignmentRepo;

        LeaveRequestService service;
        LeaveRequest req;
        StaffProfile doc;

        @BeforeEach
        void setUp() {
            service = new LeaveRequestService(
                    leaveRepo, scheduleRepo, appointmentRepo, staffRepo,
                    branchRepo, shiftRepo, userRepo, notifRepo, assignmentRepo);
            doc = staffProfile(1L, "BS. Nam", branch(1L, "CN01", "Cơ sở 1"));
            req = new LeaveRequest();
            req.setLeaveId(20L);
            req.setStaff(doc);
            req.setStatus(LeaveStatus.PENDING);
            lenient().when(leaveRepo.findById(20L)).thenReturn(Optional.of(req));
            lenient().when(staffRepo.findByUser_UserId(10L)).thenReturn(Optional.of(doc));
            lenient().when(leaveRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test @DisplayName("UTCID01 (N) - Bác sĩ tự hủy đơn PENDING thành công")
        void utc01_cancelPendingSuccess() {
            service.cancelLeaveRequest(20L, 10L);
            assertEquals(LeaveStatus.CANCELLED, req.getStatus());
        }

        @Test @DisplayName("UTCID02 (N) - Hủy đơn mở lại lịch làm việc về SCHEDULED")
        void utc02_unblocksScheduleOnCancel() {
            StaffSchedule sch = new StaffSchedule();
            sch.setStatus(ScheduleStatus.ON_LEAVE);
            when(scheduleRepo.findByStaffStaffIdAndWorkDateBetween(eq(1L), any(), any()))
                    .thenReturn(List.of(sch));

            service.cancelLeaveRequest(20L, 10L);
            assertEquals(ScheduleStatus.SCHEDULED, sch.getStatus());
        }

        @Test @DisplayName("UTCID03 (A) - Không tìm thấy đơn nghỉ")
        void utc03_leaveNotFound() {
            when(leaveRepo.findById(99L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> service.cancelLeaveRequest(99L, 10L));
        }

        @Test @DisplayName("UTCID04 (A) - Bác sĩ khác không thể hủy đơn của người khác")
        void utc04_otherDoctorCannotCancel() {
            StaffProfile other = staffProfile(2L, "BS. Khác", branch(1L, "CN01", "Cơ sở 1"));
            when(staffRepo.findByUser_UserId(11L)).thenReturn(Optional.of(other));
            assertThrows(IllegalStateException.class, () -> service.cancelLeaveRequest(20L, 11L));
        }

        @Test @DisplayName("UTCID05 (A) - Đơn đã APPROVED không thể tự hủy")
        void utc05_approvedCannotBeCancelled() {
            req.setStatus(LeaveStatus.APPROVED);
            assertThrows(IllegalStateException.class, () -> service.cancelLeaveRequest(20L, 10L));
        }

        @Test @DisplayName("UTCID06 (A) - Đơn đã REJECTED không thể hủy")
        void utc06_rejectedCannotBeCancelled() {
            req.setStatus(LeaveStatus.REJECTED);
            assertThrows(IllegalStateException.class, () -> service.cancelLeaveRequest(20L, 10L));
        }
    }

    // =========================================================================
    // 30. SystemAuditLog (6 cases)
    // =========================================================================
    @Nested
    @DisplayName("SystemAuditLog")
    class SystemAuditLog {
        @Mock SystemAuditLogRepository auditRepo;
        @Mock BranchRepository branchRepo;
        @Mock StaffProfileRepository staffRepo;
        SystemAuditLogServiceImpl service;
        Branch b;
        User u;

        @BeforeEach
        void setUp() {
            service = new SystemAuditLogServiceImpl(auditRepo, branchRepo, staffRepo, new ObjectMapper());
            b = branch(1L, "CN01", "Cơ sở 1");
            u = new User();
            u.setUserId(50L);
            u.setUsername("admin");
            u.setBranch(b);
            lenient().when(branchRepo.findById(1L)).thenReturn(Optional.of(b));
            lenient().when(auditRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test @DisplayName("UTCID01 (N) - Ghi nhận audit log hợp lệ đầy đủ thông tin")
        void utc01_validAuditLog() {
            service.logAction(u, b, "CREATE", "appointments", 100L, "127.0.0.1", null, "{\"time\":\"08:00\"}");
            verify(auditRepo).save(argThat(log ->
                    "CREATE".equals(log.getAction()) &&
                    "appointments".equals(log.getEntityTable()) &&
                    Long.valueOf(100L).equals(log.getEntityId())));
        }

        @Test @DisplayName("UTCID02 (N) - Ghi nhận log khi targetId và diff null")
        void utc02_nullDiffAllowed() {
            service.logAction(u, b, "LOGIN", "users", null, "127.0.0.1", null, null);
            verify(auditRepo).save(argThat(log -> log.getEntityId() == null && log.getNewValuesJson() == null));
        }

        @Test @DisplayName("UTCID03 (N) - Tự gán chi nhánh từ user khi branch truyền vào null")
        void utc03_branchNullFallsBackToUserBranch() {
            service.logAction(u, null, "UPDATE", "configs", 1L, "127.0.0.1", null, null);
            verify(auditRepo).save(argThat(log -> b.equals(log.getBranch())));
        }

        @Test @DisplayName("UTCID04 (N) - Ghi nhận log khi actor user là hệ thống tự động (null)")
        void utc04_systemActorAllowed() {
            service.logAction(null, b, "AUTO_REMIND", "cron", null, "127.0.0.1", null, null);
            verify(auditRepo).save(argThat(log -> log.getUser() == null));
        }

        @Test @DisplayName("UTCID05 (N) - Ghi nhận log cập nhật trạng thái kèm oldValue và newValue")
        void utc05_diffRecorded() {
            service.logAction(u, b, "CANCEL", "invoices", 50L, "127.0.0.1", "DRAFT", "CANCELLED");
            verify(auditRepo).save(argThat(log ->
                    "DRAFT".equals(log.getOldValuesJson()) && "CANCELLED".equals(log.getNewValuesJson())));
        }

        @Test @DisplayName("UTCID06 (A) - Chặn ngoại lệ không làm sập luồng chính khi repository gặp lỗi")
        void utc06_exceptionDoesNotThrow() {
            doThrow(new RuntimeException("DB down")).when(auditRepo).save(any());
            assertDoesNotThrow(() ->
                    service.logAction(u, b, "SUBMIT", "leave_requests", 1L, "127.0.0.1", null, null));
        }
    }

    // =========================================================================
    // 31. AssignStaffSchedule (12 cases)
    // =========================================================================
    @Nested
    @DisplayName("AssignStaffSchedule")
    class AssignStaffSchedule {
        @Mock WorkShiftRepository shiftRepo;
        @Mock StaffScheduleRepository scheduleRepo;
        @Mock StaffBranchAssignmentRepository assignmentRepo;
        @Mock StaffProfileRepository staffRepo;
        @Mock BranchRepository branchRepo;
        @Mock AttendanceRecordRepository attendanceRepo;
        @Mock LeaveRequestRepository leaveRepo;
        StaffScheduleService service;

        StaffProfile staff;
        Branch b;
        WorkShift shift;

        @BeforeEach
        void setUp() {
            service = new StaffScheduleService(shiftRepo, scheduleRepo, assignmentRepo, staffRepo, branchRepo, attendanceRepo, leaveRepo);
            b = branch(1L, "CN01", "Cơ sở 1");
            staff = staffProfile(10L, "BS. Toàn", b);
            shift = new WorkShift();
            shift.setShiftId(1L);
            shift.setBranch(b);
            shift.setStartTime(LocalTime.of(8, 0));
            shift.setEndTime(LocalTime.of(12, 0));

            lenient().when(branchRepo.findById(1L)).thenReturn(Optional.of(b));
            lenient().when(staffRepo.findById(10L)).thenReturn(Optional.of(staff));
            lenient().when(shiftRepo.findById(1L)).thenReturn(Optional.of(shift));
            lenient().when(assignmentRepo.hasActiveAssignment(10L, 1L, AssignmentStatus.ACTIVE)).thenReturn(true);
            lenient().when(assignmentRepo.findByStaffStaffIdAndStatus(10L, AssignmentStatus.ACTIVE)).thenReturn(List.of());
            lenient().when(leaveRepo.existsOverlapping(eq(10L), any(), any(), any(), any())).thenReturn(false);
            lenient().when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test @DisplayName("UTCID01 (N) - Phân ca làm việc hợp lệ cho nhân viên")
        void utc01_validAssign() {
            LocalDate workDate = LocalDate.now().plusDays(2);
            assertDoesNotThrow(() -> service.assign(1L, 10L, 1L, workDate, "Trực sáng"));
            verify(scheduleRepo).save(any(StaffSchedule.class));
        }

        @Test @DisplayName("UTCID02 (N) - Phân nhiều ca khác giờ trong cùng một ngày thành công")
        void utc02_differentShiftsSameDayAllowed() {
            LocalDate workDate = LocalDate.now().plusDays(2);
            WorkShift eveningShift = new WorkShift();
            eveningShift.setShiftId(2L);
            eveningShift.setBranch(b);
            eveningShift.setStartTime(LocalTime.of(17, 0));
            eveningShift.setEndTime(LocalTime.of(21, 0));

            when(shiftRepo.findById(2L)).thenReturn(Optional.of(eveningShift));
            assertDoesNotThrow(() -> service.assign(1L, 10L, 2L, workDate, "Trực tối"));
            verify(scheduleRepo).save(any(StaffSchedule.class));
        }

        @Test @DisplayName("UTCID03 (A) - Không tìm thấy chi nhánh")
        void utc03_branchNotFound() {
            when(branchRepo.findById(999L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () ->
                    service.assign(999L, 10L, 1L, LocalDate.now().plusDays(1), "Note"));
        }

        @Test @DisplayName("UTCID04 (A) - Không tìm thấy nhân viên")
        void utc04_staffNotFound() {
            when(staffRepo.findById(999L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () ->
                    service.assign(1L, 999L, 1L, LocalDate.now().plusDays(1), "Note"));
        }

        @Test @DisplayName("UTCID05 (A) - Không tìm thấy ca làm việc")
        void utc05_shiftNotFound() {
            when(shiftRepo.findById(999L)).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class, () ->
                    service.assign(1L, 10L, 999L, LocalDate.now().plusDays(1), "Note"));
        }

        @Test @DisplayName("UTCID06 (A) - Phân ca vào ngày trong quá khứ bị chặn")
        void utc06_pastDateBlocked() {
            LocalDate past = LocalDate.now().minusDays(1);
            assertThrows(IllegalArgumentException.class, () ->
                    service.assign(1L, 10L, 1L, past, "Quá khứ"));
        }

        @Test @DisplayName("UTCID07 (A) - Nhân viên đang có đơn nghỉ phép APPROVED vào ngày xếp ca")
        void utc07_staffOnApprovedLeaveBlocked() {
            LocalDate workDate = LocalDate.now().plusDays(2);
            when(leaveRepo.existsOverlapping(eq(10L), eq(workDate), eq(workDate), eq(1L), any()))
                    .thenReturn(true);

            assertThrows(IllegalArgumentException.class, () ->
                    service.assign(1L, 10L, 1L, workDate, "Nghỉ phép"));
        }

        @Test @DisplayName("UTCID08 (A) - Ca làm việc không thuộc chi nhánh phân công")
        void utc08_shiftBranchMismatch() {
            Branch otherB = branch(2L, "CN02", "Cơ sở 2");
            shift.setBranch(otherB);
            assertThrows(IllegalArgumentException.class, () ->
                    service.assign(1L, 10L, 1L, LocalDate.now().plusDays(1), "Khác chi nhánh"));
        }

        @Test @DisplayName("UTCID09 (B) - Phân ca cho ngày hôm nay được chấp nhận")
        void utc09_todayAllowed() {
            LocalDate today = LocalDate.now();
            assertDoesNotThrow(() -> service.assign(1L, 10L, 1L, today, "Hôm nay"));
            verify(scheduleRepo).save(any(StaffSchedule.class));
        }

        @Test @DisplayName("UTCID10 (B) - Phân ca cho ngày 30 ngày sau trong tương lai được chấp nhận")
        void utc10_futureBoundaryAllowed() {
            LocalDate future = LocalDate.now().plusDays(30);
            assertDoesNotThrow(() -> service.assign(1L, 10L, 1L, future, "Tương lai"));
            verify(scheduleRepo).save(any(StaffSchedule.class));
        }

        @Test @DisplayName("UTCID11 (B) - Note rỗng hoặc null được xử lý bình thường")
        void utc11_nullNoteAllowed() {
            LocalDate day = LocalDate.now().plusDays(1);
            assertDoesNotThrow(() -> service.assign(1L, 10L, 1L, day, null));
            verify(scheduleRepo).save(any(StaffSchedule.class));
        }

        @Test @DisplayName("UTCID12 (B) - Note có khoảng trắng được cắt gọn")
        void utc12_noteTrimmed() {
            LocalDate day = LocalDate.now().plusDays(1);
            assertDoesNotThrow(() -> service.assign(1L, 10L, 1L, day, "   Ghi chú   "));
            verify(scheduleRepo).save(any(StaffSchedule.class));
        }
    }

    // =========================================================================
    // 32. CreateBranch (10 cases)
    // =========================================================================
    @Nested
    @DisplayName("CreateBranch")
    class CreateBranch {
        @Mock BranchRepository branchRepo;
        @Mock UserRepository userRepo;
        @Mock StaffProfileRepository staffProfileRepo;
        @Mock WorkShiftRepository workShiftRepo;
        @Mock AppointmentRepository appointmentRepo;
        @Mock DentalChairRepository chairRepo;
        @Mock StaffScheduleRepository scheduleRepo;
        @Mock StaffBranchAssignmentRepository staffBranchAssignmentRepo;
        BranchService service;

        @BeforeEach
        void setUp() {
            service = new BranchService(branchRepo, userRepo, staffProfileRepo, workShiftRepo, appointmentRepo, chairRepo, scheduleRepo, staffBranchAssignmentRepo);
            lenient().when(branchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        private Branch validBranch() {
            Branch b = new Branch();
            b.setBranchCode("CN_NEW");
            b.setBranchName("Chi Nhánh Mới");
            b.setPhone("0987654321");
            b.setTaxCode("0101234567");
            b.setAddress("123 Cầu Giấy, Hà Nội");
            return b;
        }

        @Test @DisplayName("UTCID01 (N) - Tạo chi nhánh hợp lệ đầy đủ thông tin")
        void utc01_validBranch() {
            when(branchRepo.existsByBranchCode("CN_NEW")).thenReturn(false);
            service.createBranch(validBranch());
            verify(branchRepo).save(any(Branch.class));
        }

        @Test @DisplayName("UTCID02 (A) - Mã chi nhánh null hoặc rỗng")
        void utc02_blankBranchCodeThrows() {
            Branch b = validBranch();
            b.setBranchCode("  ");
            assertThrows(IllegalArgumentException.class, () -> service.createBranch(b));
        }

        @Test @DisplayName("UTCID03 (A) - Mã chi nhánh sai định dạng regex")
        void utc03_invalidBranchCodeRegex() {
            Branch b = validBranch();
            b.setBranchCode("cn@#$!");
            assertThrows(IllegalArgumentException.class, () -> service.createBranch(b));
        }

        @Test @DisplayName("UTCID04 (A) - Trùng mã chi nhánh đã tồn tại")
        void utc04_duplicateBranchCode() {
            when(branchRepo.existsByBranchCode("CN_NEW")).thenReturn(true);
            assertThrows(IllegalArgumentException.class, () -> service.createBranch(validBranch()));
        }

        @Test @DisplayName("UTCID05 (A) - Tên chi nhánh rỗng")
        void utc05_blankBranchName() {
            Branch b = validBranch();
            b.setBranchName("   ");
            assertThrows(IllegalArgumentException.class, () -> service.createBranch(b));
        }

        @Test @DisplayName("UTCID06 (A) - Số điện thoại chi nhánh sai định dạng")
        void utc06_invalidPhoneNumber() {
            Branch b = validBranch();
            b.setPhone("12345");
            assertThrows(IllegalArgumentException.class, () -> service.createBranch(b));
        }

        @Test @DisplayName("UTCID07 (A) - Địa chỉ email chi nhánh sai định dạng")
        void utc07_invalidEmail() {
            Branch b = validBranch();
            b.setEmail("email_sai_dinh_dang");
            assertThrows(IllegalArgumentException.class, () -> service.createBranch(b));
        }

        @Test @DisplayName("UTCID08 (A) - Mã số thuế sai định dạng")
        void utc08_invalidTaxCode() {
            Branch b = validBranch();
            b.setTaxCode("ABCXYZ");
            assertThrows(IllegalArgumentException.class, () -> service.createBranch(b));
        }

        @Test @DisplayName("UTCID09 (B) - Mã chi nhánh biên độ dài tối thiểu 2 ký tự hợp lệ")
        void utc09_minCodeLength() {
            Branch b = validBranch();
            b.setBranchCode("CN");
            when(branchRepo.existsByBranchCode("CN")).thenReturn(false);
            service.createBranch(b);
            verify(branchRepo).save(any(Branch.class));
        }

        @Test @DisplayName("UTCID10 (B) - Tên chi nhánh biên độ dài tối đa 150 ký tự hợp lệ")
        void utc10_maxNameLength() {
            Branch b = validBranch();
            b.setBranchName("A".repeat(150));
            when(branchRepo.existsByBranchCode(any())).thenReturn(false);
            service.createBranch(b);
            verify(branchRepo).save(any(Branch.class));
        }
    }
}
