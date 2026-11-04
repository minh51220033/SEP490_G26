package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.LeaveStatus;
import com.cvdcms.entity.enums.LeaveType;
import com.cvdcms.entity.enums.NotificationType;
import com.cvdcms.entity.enums.ScheduleStatus;
import com.cvdcms.repository.*;
import com.cvdcms.entity.SystemAuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class LeaveRequestService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final StaffScheduleRepository scheduleRepository;
    private final AppointmentRepository appointmentRepository;
    private final StaffProfileRepository staffRepository;
    private final BranchRepository branchRepository;
    private final WorkShiftRepository workShiftRepository;
    private final UserRepository userRepository;
    private final SystemNotificationRepository notificationRepository;
    private final StaffBranchAssignmentRepository assignmentRepository;

    @Autowired(required = false)
    private SystemAuditLogRepository systemAuditLogRepository;

    public LeaveRequestService(LeaveRequestRepository leaveRequestRepository,
                               StaffScheduleRepository scheduleRepository,
                               AppointmentRepository appointmentRepository,
                               StaffProfileRepository staffRepository,
                               BranchRepository branchRepository,
                               WorkShiftRepository workShiftRepository,
                               UserRepository userRepository,
                               SystemNotificationRepository notificationRepository,
                               StaffBranchAssignmentRepository assignmentRepository) {
        this.leaveRequestRepository = leaveRequestRepository;
        this.scheduleRepository = scheduleRepository;
        this.appointmentRepository = appointmentRepository;
        this.staffRepository = staffRepository;
        this.branchRepository = branchRepository;
        this.workShiftRepository = workShiftRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.assignmentRepository = assignmentRepository;
    }

    // =========================================================================
    // 1. DOCTOR: GỬI YÊU CẦU NGHỈ / KHÓA LỊCH (SCR-42.1)
    // =========================================================================

    @Transactional
    public LeaveRequest submitDoctorLeave(Long userId, Long branchId, LocalDate startDate, LocalDate endDate,
                                          Long shiftId, LeaveType leaveType, boolean isEmergency, String reason) {
        if (userId == null) {
            throw new IllegalArgumentException("Người dùng chưa đăng nhập.");
        }
        StaffProfile staff = staffRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Tài khoản chưa được liên kết với hồ sơ bác sĩ/nhân viên."));

        if (branchId == null) {
            throw new IllegalArgumentException("Vui lòng chọn cơ sở/chi nhánh áp dụng.");
        }
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Cơ sở không tồn tại trong hệ thống."));

        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("Vui lòng chọn ngày bắt đầu và kết thúc.");
        }
        LocalDate today = LocalDate.now();
        if (startDate.isBefore(today)) {
            throw new IllegalArgumentException("Ngày bắt đầu nghỉ không được ở trong quá khứ.");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("Ngày kết thúc không được trước ngày bắt đầu.");
        }

        WorkShift shift = null;
        if (shiftId != null && shiftId > 0) {
            shift = workShiftRepository.findById(shiftId)
                    .orElseThrow(() -> new IllegalArgumentException("Ca làm việc đã chọn không hợp lệ."));
            if (!startDate.isEqual(endDate)) {
                throw new IllegalArgumentException("Nếu xin nghỉ theo ca làm việc cụ thể, ngày bắt đầu và kết thúc phải cùng một ngày.");
            }
            if (startDate.isEqual(today) && shift.getEndTime() != null && shift.getEndTime().isBefore(LocalTime.now())) {
                throw new IllegalArgumentException("Ca làm việc này trong ngày hôm nay đã kết thúc (" + shift.getEndTime() + "). Không thể nộp đơn xin nghỉ cho ca đã qua.");
            }
        }

        if (leaveType == null) {
            leaveType = LeaveType.ANNUAL;
        }

        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("Vui lòng nhập lý do xin nghỉ rõ ràng (tối thiểu 5 ký tự).");
        }

        // QUY TẮC R8: Phải báo trước ít nhất 3 ngày (tính từ ngày nộp tới startDate)
        long daysNotice = ChronoUnit.DAYS.between(today, startDate);
        if (daysNotice < 3) {
            if (!isEmergency) {
                throw new IllegalArgumentException("Theo quy định R8, đơn nghỉ phép phải gửi trước ít nhất 3 ngày (bạn đang gửi trước " 
                        + daysNotice + " ngày). Nếu là trường hợp khẩn cấp/đột xuất, vui lòng đánh dấu chọn 'Nghỉ đột xuất' và giải trình rõ lý do.");
            }
            if (reason.trim().length() < 10) {
                throw new IllegalArgumentException("Trường hợp nghỉ đột xuất yêu cầu ghi rõ lý do chi tiết (tối thiểu 10 ký tự).");
            }
        }

        // KIỂM TRA TRÙNG LẶP ĐƠN NGHỈ
        boolean isOverlapping = leaveRequestRepository.existsOverlapping(
                staff.getStaffId(), startDate, endDate, shift != null ? shift.getShiftId() : null,
                List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED)
        );
        if (isOverlapping) {
            throw new IllegalArgumentException("Bạn đã có đơn nghỉ phép trùng lặp trong khoảng thời gian/ca làm việc này đang chờ duyệt hoặc đã được phê duyệt.");
        }

        LeaveRequest leave = new LeaveRequest();
        leave.setStaff(staff);
        leave.setBranch(branch);
        leave.setShift(shift);
        leave.setStartDate(startDate);
        leave.setEndDate(endDate);
        leave.setLeaveType(leaveType);
        leave.setIsEmergency(isEmergency);
        leave.setReason(reason.trim());
        leave.setStatus(LeaveStatus.PENDING);

        return leaveRequestRepository.save(leave);
    }

    // =========================================================================
    // 2. DOCTOR: DANH SÁCH & TRẠNG THÁI YÊU CẦU NGHỈ (SCR-42.2)
    // =========================================================================

    @Transactional(readOnly = true)
    public List<LeaveRequest> getDoctorLeaveRequests(Long userId) {
        StaffProfile staff = staffRepository.findByUser_UserId(userId).orElse(null);
        if (staff == null) {
            return Collections.emptyList();
        }
        return leaveRequestRepository.findByStaffStaffIdOrderBySubmittedAtDesc(staff.getStaffId());
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getDoctorSummaryCounts(Long userId) {
        StaffProfile staff = staffRepository.findByUser_UserId(userId).orElse(null);
        Map<String, Long> summary = new HashMap<>();
        if (staff == null) {
            summary.put("total", 0L);
            summary.put("pending", 0L);
            summary.put("approved", 0L);
            summary.put("rejected", 0L);
            return summary;
        }
        Long staffId = staff.getStaffId();
        List<LeaveRequest> list = leaveRequestRepository.findByStaffStaffIdOrderBySubmittedAtDesc(staffId);
        long pending = list.stream().filter(l -> l.getStatus() == LeaveStatus.PENDING).count();
        long approved = list.stream().filter(l -> l.getStatus() == LeaveStatus.APPROVED).count();
        long rejected = list.stream().filter(l -> l.getStatus() == LeaveStatus.REJECTED).count();

        summary.put("total", (long) list.size());
        summary.put("pending", pending);
        summary.put("approved", approved);
        summary.put("rejected", rejected);
        return summary;
    }

    @Transactional
    public void cancelLeaveRequest(Long leaveId, Long userId) {
        LeaveRequest leave = leaveRequestRepository.findById(leaveId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn xin nghỉ."));

        StaffProfile staff = staffRepository.findByUser_UserId(userId).orElse(null);
        if (staff == null || !leave.getStaff().getStaffId().equals(staff.getStaffId())) {
            throw new IllegalArgumentException("Bạn không có quyền thao tác trên đơn nghỉ này.");
        }

        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new IllegalArgumentException("Chỉ có thể hủy đơn nghỉ phép khi đang ở trạng thái 'Chờ duyệt'.");
        }

        leave.setStatus(LeaveStatus.CANCELLED);
        leaveRequestRepository.save(leave);
    }

    // =========================================================================
    // 3. ADMIN: DANH SÁCH DUYỆT ĐƠN (SCR-42.3)
    // =========================================================================

    @Transactional(readOnly = true)
    public List<LeaveRequest> filterAdminLeaveRequests(Long branchId, LeaveStatus status, Long staffId,
                                                      LocalDate startDate, LocalDate endDate, String keyword) {
        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        return leaveRequestRepository.filterAdmin(branchId, status, staffId, startDate, endDate, kw);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getAdminSummaryCounts() {
        Map<String, Long> summary = new HashMap<>();
        summary.put("total", leaveRequestRepository.count());
        summary.put("pending", leaveRequestRepository.countByStatus(LeaveStatus.PENDING));
        summary.put("approved", leaveRequestRepository.countByStatus(LeaveStatus.APPROVED));
        summary.put("rejected", leaveRequestRepository.countByStatus(LeaveStatus.REJECTED));
        return summary;
    }

    @Transactional(readOnly = true)
    public LeaveRequest getLeaveRequestDetail(Long leaveId) {
        return leaveRequestRepository.findByIdWithDetails(leaveId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin đơn nghỉ (#" + leaveId + ")."));
    }

    // =========================================================================
    // 4. ADMIN: PHÊ DUYỆT / TỪ CHỐI & TỰ ĐỘNG KHÓA LỊCH (SCR-42.4)
    // =========================================================================

    @Transactional
    public void approveLeaveRequest(Long leaveId, Long reviewerUserId, String reviewNote) {
        LeaveRequest leave = leaveRequestRepository.findByIdWithDetails(leaveId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn xin nghỉ."));

        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new IllegalArgumentException("Đơn nghỉ này đã được xử lý từ trước (Trạng thái: " + leave.getStatus() + ").");
        }

        User reviewer = userRepository.findById(reviewerUserId)
                .orElseThrow(() -> new IllegalArgumentException("Người duyệt không hợp lệ."));

        leave.setStatus(LeaveStatus.APPROVED);
        leave.setReviewedBy(reviewer);
        leave.setReviewedAt(LocalDateTime.now());
        leave.setReviewNote(reviewNote != null ? reviewNote.trim() : "Đã duyệt");
        leaveRequestRepository.save(leave);

        if (systemAuditLogRepository != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> oldVal = Map.of("status", "PENDING", "doctorName", leave.getStaff() != null ? leave.getStaff().getFullName() : "");
                Map<String, Object> newVal = Map.of("status", "APPROVED", "doctorName", leave.getStaff() != null ? leave.getStaff().getFullName() : "", "reviewNote", leave.getReviewNote() != null ? leave.getReviewNote() : "");
                SystemAuditLog audit = new SystemAuditLog();
                audit.setUser(reviewer);
                audit.setBranch(leave.getBranch());
                audit.setAction("APPROVE_LEAVE_REQUEST");
                audit.setEntityTable("leave_requests");
                audit.setEntityId(leave.getLeaveId());
                audit.setOldValuesJson(mapper.writeValueAsString(oldVal));
                audit.setNewValuesJson(mapper.writeValueAsString(newVal));
                audit.setIpAddress("127.0.0.1");
                systemAuditLogRepository.save(audit);
            } catch (Exception ignored) {}
        }

        // TỰ ĐỘNG KHÓA LỊCH TRỰC: Cập nhật các ca làm việc của bác sĩ trong khoảng ngày sang ON_LEAVE
        List<StaffSchedule> schedules;
        if (leave.getShift() != null) {
            schedules = scheduleRepository.findByStaffStaffIdAndShiftShiftIdAndWorkDateBetween(
                    leave.getStaff().getStaffId(), leave.getShift().getShiftId(), leave.getStartDate(), leave.getEndDate()
            );
        } else {
            schedules = scheduleRepository.findByStaffStaffIdAndWorkDateBetween(
                    leave.getStaff().getStaffId(), leave.getStartDate(), leave.getEndDate()
            );
        }

        for (StaffSchedule s : schedules) {
            if (s.getStatus() == ScheduleStatus.SCHEDULED) {
                s.setStatus(ScheduleStatus.ON_LEAVE);
                String note = "Nghỉ phép #LR-" + leave.getLeaveId();
                if (reviewNote != null && !reviewNote.isBlank()) {
                    note += ": " + reviewNote.trim();
                }
                s.setNote(note);
            }
        }
        scheduleRepository.saveAll(schedules);

        // Gửi thông báo hệ thống cho Bác sĩ
        if (leave.getStaff().getUser() != null) {
            SystemNotification notif = new SystemNotification();
            notif.setUser(leave.getStaff().getUser());
            notif.setTitle("Đơn nghỉ phép đã được phê duyệt");
            notif.setMessage("Yêu cầu nghỉ phép từ ngày " + leave.getStartDate() + " đến " + leave.getEndDate()
                    + " của bạn đã được phê duyệt bởi " + reviewer.getUsername() + ".");
            notif.setNotificationType(NotificationType.LEAVE_APPROVAL);
            notificationRepository.save(notif);
        }
    }

    @Transactional
    public void rejectLeaveRequest(Long leaveId, Long reviewerUserId, String reviewNote) {
        LeaveRequest leave = leaveRequestRepository.findByIdWithDetails(leaveId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn xin nghỉ."));

        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new IllegalArgumentException("Đơn nghỉ này đã được xử lý từ trước.");
        }

        if (reviewNote == null || reviewNote.trim().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập lý do từ chối đơn nghỉ phép.");
        }

        User reviewer = userRepository.findById(reviewerUserId)
                .orElseThrow(() -> new IllegalArgumentException("Người duyệt không hợp lệ."));

        leave.setStatus(LeaveStatus.REJECTED);
        leave.setReviewedBy(reviewer);
        leave.setReviewedAt(LocalDateTime.now());
        leave.setReviewNote(reviewNote.trim());
        leaveRequestRepository.save(leave);

        // Gửi thông báo từ chối cho Bác sĩ
        if (leave.getStaff().getUser() != null) {
            SystemNotification notif = new SystemNotification();
            notif.setUser(leave.getStaff().getUser());
            notif.setTitle("Đơn nghỉ phép bị từ chối");
            notif.setMessage("Yêu cầu nghỉ phép từ ngày " + leave.getStartDate() + " đến " + leave.getEndDate()
                    + " của bạn đã bị từ chối. Lý do: " + reviewNote.trim());
            notif.setNotificationType(NotificationType.LEAVE_APPROVAL);
            notificationRepository.save(notif);
        }
    }

    // =========================================================================
    // 5. DANH SÁCH LỊCH HẸN BỆNH NHÂN BỊ ẢNH HƯỞNG (SCR-42.5)
    // =========================================================================

    @Transactional(readOnly = true)
    public List<Appointment> getAffectedAppointments(Long leaveId) {
        LeaveRequest leave = leaveRequestRepository.findByIdWithDetails(leaveId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn xin nghỉ."));

        List<Appointment> allInDateRange = appointmentRepository.findAffectedAppointments(
                leave.getStaff().getStaffId(), leave.getStartDate(), leave.getEndDate(),
                List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED)
        );

        if (leave.getShift() == null) {
            // Nghỉ cả ngày -> Toàn bộ lịch hẹn trong khoảng ngày đều bị ảnh hưởng
            return allInDateRange;
        }

        // Nghỉ theo ca cụ thể -> Lọc các lịch hẹn có giờ hẹn nằm trong khung giờ của ca trực
        LocalTime shiftStart = leave.getShift().getStartTime();
        LocalTime shiftEnd = leave.getShift().getEndTime();

        List<Appointment> affected = new ArrayList<>();
        for (Appointment a : allInDateRange) {
            LocalTime apptTime = a.getExpectedTime();
            if (apptTime == null) {
                // Nếu không ghi giờ cụ thể thì mặc định tính là bị ảnh hưởng
                affected.add(a);
            } else if (!apptTime.isBefore(shiftStart) && apptTime.isBefore(shiftEnd)) {
                affected.add(a);
            }
        }
        return affected;
    }

    // =========================================================================
    // HELPER METHODS: CHI NHÁNH, CA LÀM VIỆC, BÁC SĨ
    // =========================================================================

    @Transactional(readOnly = true)
    public List<Branch> getDoctorBranches(Long userId) {
        StaffProfile staff = staffRepository.findByUser_UserId(userId).orElse(null);
        if (staff == null) {
            return Collections.emptyList();
        }
        List<StaffBranchAssignment> assignments = assignmentRepository.findByStaffStaffIdAndStatus(
                staff.getStaffId(), AssignmentStatus.ACTIVE
        );
        List<Branch> branches = new ArrayList<>();
        for (StaffBranchAssignment a : assignments) {
            if (a.getBranch() != null && !branches.contains(a.getBranch())) {
                branches.add(a.getBranch());
            }
        }
        if (branches.isEmpty()) {
            return branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
        }
        return branches;
    }

    @Transactional(readOnly = true)
    public List<WorkShift> getShiftsByBranch(Long branchId) {
        if (branchId == null) {
            return Collections.emptyList();
        }
        return workShiftRepository.findByBranchBranchIdAndIsActiveTrueOrderByStartTimeAsc(branchId);
    }

    @Transactional(readOnly = true)
    public List<StaffProfile> getAllDoctors() {
        return staffRepository.findAllByOrderByStaffCodeAsc().stream()
                .filter(s -> s.getStaffCode() != null && s.getStaffCode().startsWith("BS"))
                .toList();
    }

    @Transactional(readOnly = true)
    public StaffProfile getStaffByUserId(Long userId) {
        return staffRepository.findByUser_UserId(userId).orElse(null);
    }
}

