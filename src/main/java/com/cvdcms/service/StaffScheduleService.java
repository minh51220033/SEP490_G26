package com.cvdcms.service;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.StaffBranchAssignment;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.StaffSchedule;
import com.cvdcms.entity.WorkShift;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.ScheduleStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.StaffScheduleRepository;
import com.cvdcms.repository.WorkShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// FT33 - Phân ca làm việc theo tuần & chi nhánh (SCR-33.1, SCR-33.3).
@Service
public class StaffScheduleService {

    private final WorkShiftRepository shiftRepository;
    private final StaffScheduleRepository scheduleRepository;
    private final StaffBranchAssignmentRepository assignmentRepository;
    private final StaffProfileRepository staffRepository;
    private final BranchRepository branchRepository;

    public StaffScheduleService(WorkShiftRepository shiftRepository,
                                StaffScheduleRepository scheduleRepository,
                                StaffBranchAssignmentRepository assignmentRepository,
                                StaffProfileRepository staffRepository,
                                BranchRepository branchRepository) {
        this.shiftRepository = shiftRepository;
        this.scheduleRepository = scheduleRepository;
        this.assignmentRepository = assignmentRepository;
        this.staffRepository = staffRepository;
        this.branchRepository = branchRepository;
    }

    // Thứ Hai của tuần chứa ngày được chọn.
    public static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ===================== CA LÀM VIỆC =====================

    // Chi nhánh chưa có ca nào -> tạo 3 ca chuẩn Sáng / Chiều / Tối.
    @Transactional
    public List<WorkShift> getShifts(Long branchId) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        if (shiftRepository.countByBranchBranchId(branchId) == 0) {
            createShift(branch, "SANG", "Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));
            createShift(branch, "CHIEU", "Ca chiều", LocalTime.of(13, 30), LocalTime.of(17, 30));
            createShift(branch, "TOI", "Ca tối", LocalTime.of(18, 0), LocalTime.of(20, 30));
        }
        return shiftRepository.findByBranchBranchIdAndIsActiveTrueOrderByStartTimeAsc(branchId);
    }

    private void createShift(Branch branch, String code, String name, LocalTime start, LocalTime end) {
        WorkShift s = new WorkShift();
        s.setBranch(branch);
        s.setShiftCode(code);
        s.setShiftName(name);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setIsActive(true);
        shiftRepository.save(s);
    }

    // ===================== SCR-33.1: MA TRẬN TUẦN =====================

    // Ma trận: ngày -> (shiftId -> danh sách lịch).
    @Transactional(readOnly = true)
    public Map<LocalDate, Map<Long, List<StaffSchedule>>> buildWeekMatrix(Long branchId,
                                                                        LocalDate monday,
                                                                        List<WorkShift> shifts) {
        Map<LocalDate, Map<Long, List<StaffSchedule>>> matrix = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            Map<Long, List<StaffSchedule>> row = new LinkedHashMap<>();
            for (WorkShift sh : shifts) {
                row.put(sh.getShiftId(), new ArrayList<>());
            }
            matrix.put(monday.plusDays(i), row);
        }
        for (StaffSchedule s : scheduleRepository.findByBranchAndRange(branchId, monday, monday.plusDays(6))) {
            Map<Long, List<StaffSchedule>> row = matrix.get(s.getWorkDate());
            if (row != null && row.containsKey(s.getShift().getShiftId())) {
                row.get(s.getShift().getShiftId()).add(s);
            }
        }
        return matrix;
    }

    // Nhân sự đang được phân công đang hiệu lực tại chi nhánh (để chọn xếp ca).
    @Transactional(readOnly = true)
    public List<StaffBranchAssignment> getAssignableStaff(Long branchId) {
        List<StaffBranchAssignment> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        LocalDate today = LocalDate.now();
        for (StaffBranchAssignment a : assignmentRepository.findByBranchAndStatus(branchId, AssignmentStatus.ACTIVE)) {
            boolean notExpired = (a.getStartDate() == null || !a.getStartDate().isAfter(today))
                    && (a.getEndDate() == null || !a.getEndDate().isBefore(today));
            boolean isOperationalRole = a.getRole() != null
                    && !"ADMIN".equalsIgnoreCase(a.getRole().getRoleName())
                    && !"ROLE_ADMIN".equalsIgnoreCase(a.getRole().getRoleName());
            if (notExpired && isOperationalRole && a.getStaff().getStatus() == StaffStatus.ACTIVE && seen.add(a.getStaff().getStaffId())) {
                result.add(a);
            }
        }
        return result;
    }

    // ===================== XẾP / HỦY CA =====================

    @Transactional
    public void assign(Long branchId, Long staffId, Long shiftId, LocalDate workDate, String note) {
        if (workDate == null || workDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Không thể xếp ca cho ngày đã qua.");
        }
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        StaffProfile staff = staffRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên."));
        WorkShift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca làm việc."));

        if (shift.getBranch() != null && !shift.getBranch().getBranchId().equals(branchId)) {
            throw new IllegalArgumentException("Ca làm việc không thuộc chi nhánh đã chọn.");
        }
        if (staff.getStatus() != StaffStatus.ACTIVE) {
            throw new IllegalArgumentException("Nhân viên không ở trạng thái đang làm việc.");
        }
        if (!assignmentRepository.hasActiveAssignment(staffId, branchId, AssignmentStatus.ACTIVE)) {
            throw new IllegalArgumentException("Nhân viên chưa được phân công làm việc tại chi nhánh này.");
        }
        boolean isStaffAdmin = assignmentRepository.findByStaffStaffIdAndStatus(staffId, AssignmentStatus.ACTIVE)
                .stream()
                .anyMatch(a -> a.getRole() != null && ("ADMIN".equalsIgnoreCase(a.getRole().getRoleName()) || "ROLE_ADMIN".equalsIgnoreCase(a.getRole().getRoleName())));
        if (isStaffAdmin) {
            throw new IllegalArgumentException("Không thể xếp ca làm việc cho nhân sự có vai trò Quản trị hệ thống.");
        }
        // Chống xếp trùng giờ: một nhân sự không thể trực 2 ca có khung giờ giao nhau trong cùng một ngày.
        LocalTime newStart = shift.getStartTime();
        LocalTime newEnd = shift.getEndTime();
        List<StaffSchedule> existingSchedules = scheduleRepository
                .findByStaffStaffIdAndWorkDateAndStatusNot(staffId, workDate, ScheduleStatus.CANCELLED);

        for (StaffSchedule ex : existingSchedules) {
            WorkShift exShift = ex.getShift();
            LocalTime exStart = exShift.getStartTime();
            LocalTime exEnd = exShift.getEndTime();

            // Hai khoảng thời gian [start, end] giao nhau khi: newStart < exEnd && newEnd > exStart
            if (newStart.isBefore(exEnd) && newEnd.isAfter(exStart)) {
                String timeRange = exStart + " - " + exEnd;
                if (ex.getBranch().getBranchId().equals(branchId)) {
                    throw new IllegalArgumentException("Nhân viên đã được xếp vào \"" + exShift.getShiftName()
                            + "\" (" + timeRange + ") tại chi nhánh này.");
                } else {
                    throw new IllegalArgumentException("Nhân viên đã có lịch làm việc tại chi nhánh \""
                            + ex.getBranch().getBranchName() + "\" trong ca \"" + exShift.getShiftName()
                            + "\" (" + timeRange + "). Không thể xếp trùng giờ ở hai chi nhánh.");
                }
            }
        }

        StaffSchedule s = new StaffSchedule();
        s.setStaff(staff);
        s.setBranch(branch);
        s.setShift(shift);
        s.setWorkDate(workDate);
        s.setStatus(ScheduleStatus.SCHEDULED);
        s.setNote(note == null || note.isBlank() ? null : note.trim());
        scheduleRepository.save(s);
    }

    @Transactional
    public void remove(Long scheduleId) {
        StaffSchedule s = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch phân ca."));
        if (s.getWorkDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Không thể xóa ca đã qua.");
        }
        scheduleRepository.delete(s);
    }

    // Sao chép lịch tuần trước sang tuần đang xem; bỏ qua ca trùng và nhân viên không còn hiệu lực.
    @Transactional
    public int copyWeek(Long branchId, LocalDate fromMonday, LocalDate toMonday) {
        int copied = 0;
        for (StaffSchedule src : scheduleRepository.findByBranchAndRange(branchId, fromMonday, fromMonday.plusDays(6))) {
            if (src.getStatus() != ScheduleStatus.SCHEDULED) {
                continue;
            }
            LocalDate target = toMonday.plusDays(java.time.temporal.ChronoUnit.DAYS.between(fromMonday, src.getWorkDate()));
            if (target.isBefore(LocalDate.now())) {
                continue;
            }
            try {
                assign(branchId, src.getStaff().getStaffId(), src.getShift().getShiftId(), target, src.getNote());
                copied++;
            } catch (IllegalArgumentException ignored) {
                // Trùng ca hoặc nhân viên không còn phù hợp -> bỏ qua.
            }
        }
        return copied;
    }

    // ===================== SCR-33.3: LỊCH CÁ NHÂN =====================

    @Transactional(readOnly = true)
    public StaffProfile findStaffByUserId(Long userId) {
        return staffRepository.findByUserUserId(userId).orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<LocalDate, List<StaffSchedule>> getMyWeek(Long staffId, LocalDate monday) {
        Map<LocalDate, List<StaffSchedule>> week = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            week.put(monday.plusDays(i), new ArrayList<>());
        }
        for (StaffSchedule s : scheduleRepository.findByStaffAndRange(staffId, monday, monday.plusDays(6))) {
            week.get(s.getWorkDate()).add(s);
        }
        return week;
    }

    // ===================== API: BÁC SĨ KHẢ DỤNG =====================

    // Bác sĩ có ca SCHEDULED tại chi nhánh trong ngày (bàn giao cho luồng đặt lịch).
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAvailableDoctors(Long branchId, LocalDate date) {
        Set<Long> doctorIds = new LinkedHashSet<>();
        for (StaffBranchAssignment a : assignmentRepository.findByBranchAndStatus(branchId, AssignmentStatus.ACTIVE)) {
            if ("BAC_SI".equalsIgnoreCase(a.getRole().getRoleName())) {
                doctorIds.add(a.getStaff().getStaffId());
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (StaffSchedule s : scheduleRepository.findByBranchAndDateAndStatus(branchId, date, ScheduleStatus.SCHEDULED)) {
            if (!doctorIds.contains(s.getStaff().getStaffId())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("staffId", s.getStaff().getStaffId());
            m.put("staffCode", s.getStaff().getStaffCode());
            m.put("fullName", s.getStaff().getFullName());
            m.put("specialty", s.getStaff().getSpecialty());
            m.put("shiftId", s.getShift().getShiftId());
            m.put("shiftName", s.getShift().getShiftName());
            m.put("startTime", s.getShift().getStartTime().toString());
            m.put("endTime", s.getShift().getEndTime().toString());
            result.add(m);
        }
        return result;
    }
}
