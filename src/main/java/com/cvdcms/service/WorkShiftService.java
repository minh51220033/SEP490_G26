package com.cvdcms.service;

import com.cvdcms.entity.AppointmentSessionWindow;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.WorkShift;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.repository.AppointmentSessionWindowRepository;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.WorkShiftRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

// FT33 - Cấu hình ca làm việc (Work Shifts) & Ca khám (Appointment Session Windows) - SCR-33.0
@Service
public class WorkShiftService {

    public static final long MIN_SHIFT_MINUTES = 60L;
    public static final long MAX_SHIFT_MINUTES = 720L;          // Điều 105 & 107 BLLĐ: tối đa 12h/ngày gồm cả OT
    public static final long MAX_BOOKING_WINDOW_MINUTES = 360L; // Mỗi buổi khám tối đa 6h
    public static final long MIN_MIDDAY_BREAK_MINUTES = 30L;    // Điều 109 BLLĐ: nghỉ giữa giờ tối thiểu 30 phút

    private static final List<PreferredSession> BOOKABLE_SESSIONS = List.of(
            PreferredSession.MORNING, PreferredSession.AFTERNOON, PreferredSession.EVENING);

    private final WorkShiftRepository workShiftRepository;
    private final BranchRepository branchRepository;
    private final AppointmentSessionWindowRepository sessionWindowRepository;

    @Autowired
    public WorkShiftService(WorkShiftRepository workShiftRepository,
                            BranchRepository branchRepository,
                            @Autowired(required = false) AppointmentSessionWindowRepository sessionWindowRepository) {
        this.workShiftRepository = workShiftRepository;
        this.branchRepository = branchRepository;
        this.sessionWindowRepository = sessionWindowRepository;
    }

    public WorkShiftService(WorkShiftRepository workShiftRepository,
                            BranchRepository branchRepository) {
        this(workShiftRepository, branchRepository, null);
    }

    // Lấy toàn bộ ca của 1 chi nhánh (kể cả đang tắt) để hiển thị bảng quản lý.
    // Tự động vô hiệu hóa các ca nội bộ/demo vượt quá 12h/ngày theo Luật Lao động.
    @Transactional
    public List<WorkShift> getShiftsByBranch(Long branchId) {
        Branch branch = findBranch(branchId);
        if (workShiftRepository.countByBranchBranchId(branchId) == 0) {
            createDefault(branch);
        }
        List<WorkShift> branchShifts = findBranchShifts(branchId);
        for (WorkShift ws : branchShifts) {
            if (Boolean.TRUE.equals(ws.getIsActive()) && isOverLaborLimitShift(ws)) {
                ws.setIsActive(false);
                workShiftRepository.save(ws);
            }
        }
        return branchShifts;
    }

    // Tạo ca mới
    @Transactional
    public WorkShift create(Long branchId, String shiftCode, String shiftName, LocalTime startTime, LocalTime endTime) {
        Branch branch = findBranch(branchId);
        String finalCode = shiftCode == null ? "" : shiftCode.trim().toUpperCase(Locale.ROOT);
        if (finalCode.isEmpty() || shiftName == null || shiftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Mã ca và tên ca không được để trống.");
        }
        boolean exists = findBranchShifts(branchId).stream()
                .anyMatch(s -> finalCode.equalsIgnoreCase(s.getShiftCode()));
        if (exists) {
            throw new IllegalArgumentException("Mã ca '" + finalCode + "' đã tồn tại trong chi nhánh này.");
        }
        validateTime(startTime, endTime);
        WorkShift shift = new WorkShift();
        shift.setBranch(branch);
        shift.setShiftCode(finalCode);
        shift.setShiftName(shiftName.trim());
        shift.setStartTime(startTime);
        shift.setEndTime(endTime);
        shift.setIsActive(true);
        WorkShift saved = workShiftRepository.save(shift);
        syncSessionWindowForShift(saved);
        return saved;
    }

    // Cập nhật ca
    @Transactional
    public WorkShift update(Long shiftId, String shiftCode, String shiftName, LocalTime startTime, LocalTime endTime) {
        WorkShift shift = findShift(shiftId);
        Long branchId = shift.getBranch() != null ? shift.getBranch().getBranchId() : null;
        String finalCode = shiftCode == null ? "" : shiftCode.trim().toUpperCase(Locale.ROOT);
        if (finalCode.isEmpty() || shiftName == null || shiftName.trim().isEmpty()) {
            throw new IllegalArgumentException("Mã ca và tên ca không được để trống.");
        }
        boolean exists = workShiftRepository.findAll().stream()
                .anyMatch(s -> s.getBranch() != null && Objects.equals(s.getBranch().getBranchId(), branchId)
                        && !s.getShiftId().equals(shiftId)
                        && finalCode.equalsIgnoreCase(s.getShiftCode()));
        if (exists) {
            throw new IllegalArgumentException("Mã ca '" + finalCode + "' đã được dùng bởi ca khác trong chi nhánh này.");
        }
        validateTime(startTime, endTime);
        shift.setShiftCode(finalCode);
        shift.setShiftName(shiftName.trim());
        shift.setStartTime(startTime);
        shift.setEndTime(endTime);
        WorkShift saved = workShiftRepository.save(shift);
        syncSessionWindowForShift(saved);
        return saved;
    }

    // Bật / Tắt trạng thái ca
    @Transactional
    public WorkShift toggleActive(Long shiftId) {
        WorkShift shift = findShift(shiftId);
        boolean nextActive = !Boolean.TRUE.equals(shift.getIsActive());
        if (nextActive && isOverLaborLimitShift(shift)) {
            throw new IllegalArgumentException("Không thể bật ca '" + shift.getShiftName()
                    + "' vì thời lượng vượt quá 12 giờ/ngày theo Điều 105 & 107 Bộ luật Lao động.");
        }
        shift.setIsActive(nextActive);
        WorkShift saved = workShiftRepository.save(shift);
        syncSessionWindowForShift(saved);
        return saved;
    }

    // Xóa ca
    @Transactional
    public void delete(Long shiftId) {
        WorkShift shift = findShift(shiftId);
        Long branchId = shift.getBranch() != null ? shift.getBranch().getBranchId() : null;
        PreferredSession session = resolveSession(shift);
        workShiftRepository.delete(shift);
        if (branchId != null && session != null && sessionWindowRepository != null) {
            syncSingleSessionFromBranchShifts(findBranch(branchId), session, false);
        }
    }

    // ===================== QUẢN LÝ CẤU HÌNH CA KHÁM (APPOINTMENT SESSION WINDOWS) =====================

    @Transactional
    public List<AppointmentSessionWindow> getSessionWindowsByBranch(Long branchId) {
        Branch branch = findBranch(branchId);
        if (sessionWindowRepository == null) {
            return List.of();
        }
        for (PreferredSession session : BOOKABLE_SESSIONS) {
            Optional<AppointmentSessionWindow> existing =
                    sessionWindowRepository.findByBranch_BranchIdAndPreferredSession(branchId, session);
            if (existing.isEmpty()) {
                AppointmentSessionWindow created = buildInitialWindow(branch, session);
                sessionWindowRepository.save(created);
            }
        }
        return sessionWindowRepository.findByBranch_BranchIdOrderByStartTimeAsc(branchId).stream()
                .filter(w -> BOOKABLE_SESSIONS.contains(w.getPreferredSession()))
                .sorted(Comparator.comparingInt(w -> BOOKABLE_SESSIONS.indexOf(w.getPreferredSession())))
                .collect(Collectors.toList());
    }

    @Transactional
    public AppointmentSessionWindow updateSessionWindow(Long windowId, LocalTime startTime, LocalTime endTime) {
        if (sessionWindowRepository == null) {
            throw new IllegalStateException("Chưa cấu hình AppointmentSessionWindowRepository.");
        }
        AppointmentSessionWindow window = findSessionWindow(windowId);
        Branch branch = window.getBranch();
        PreferredSession session = window.getPreferredSession();

        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("Giờ kết thúc ca khám phải sau giờ bắt đầu.");
        }
        long mins = Duration.between(startTime, endTime).toMinutes();
        if (mins < MIN_SHIFT_MINUTES || mins > MAX_BOOKING_WINDOW_MINUTES) {
            throw new IllegalArgumentException("Thời lượng mỗi ca khám phải từ 1 giờ (60 phút) đến tối đa 6 giờ (360 phút).");
        }
        if (branch.getOpeningTime() != null && startTime.isBefore(branch.getOpeningTime())) {
            throw new IllegalArgumentException("Giờ bắt đầu ca khám (" + formatTime(startTime)
                    + ") không được sớm hơn giờ mở cửa chi nhánh (" + formatTime(branch.getOpeningTime()) + ").");
        }
        if (branch.getClosingTime() != null && endTime.isAfter(branch.getClosingTime())) {
            throw new IllegalArgumentException("Giờ kết thúc ca khám (" + formatTime(endTime)
                    + ") không được muộn hơn giờ đóng cửa chi nhánh (" + formatTime(branch.getClosingTime()) + ").");
        }

        List<AppointmentSessionWindow> others = sessionWindowRepository
                .findByBranch_BranchIdOrderByStartTimeAsc(branch.getBranchId()).stream()
                .filter(w -> !Objects.equals(w.getWindowId(), windowId))
                .toList();

        for (AppointmentSessionWindow other : others) {
            if (other.getStartTime() == null || other.getEndTime() == null) continue;
            if (startTime.isBefore(other.getEndTime()) && endTime.isAfter(other.getStartTime())) {
                throw new IllegalArgumentException("Khung giờ " + formatTime(startTime) + " – " + formatTime(endTime)
                        + " bị chồng lấn với " + sessionLabel(other.getPreferredSession())
                        + " (" + formatTime(other.getStartTime()) + " – " + formatTime(other.getEndTime()) + ").");
            }
            if (session == PreferredSession.MORNING && other.getPreferredSession() == PreferredSession.AFTERNOON) {
                if (endTime.isAfter(other.getStartTime())) {
                    throw new IllegalArgumentException("Ca Sáng phải kết thúc trước giờ bắt đầu Ca Chiều.");
                }
                if (Duration.between(endTime, other.getStartTime()).toMinutes() < MIN_MIDDAY_BREAK_MINUTES) {
                    throw new IllegalArgumentException("Theo Điều 109 Bộ luật Lao động, cần bố trí khoảng nghỉ trưa tối thiểu 30 phút giữa Ca Sáng và Ca Chiều.");
                }
            } else if (session == PreferredSession.AFTERNOON && other.getPreferredSession() == PreferredSession.MORNING) {
                if (startTime.isBefore(other.getEndTime())) {
                    throw new IllegalArgumentException("Ca Chiều phải bắt đầu sau giờ kết thúc Ca Sáng.");
                }
                if (Duration.between(other.getEndTime(), startTime).toMinutes() < MIN_MIDDAY_BREAK_MINUTES) {
                    throw new IllegalArgumentException("Theo Điều 109 Bộ luật Lao động, cần bố trí khoảng nghỉ trưa tối thiểu 30 phút giữa Ca Sáng và Ca Chiều.");
                }
            } else if (session == PreferredSession.AFTERNOON && other.getPreferredSession() == PreferredSession.EVENING) {
                if (endTime.isAfter(other.getStartTime())) {
                    throw new IllegalArgumentException("Ca Chiều phải kết thúc trước hoặc đúng giờ bắt đầu Ca Tối.");
                }
            } else if (session == PreferredSession.EVENING && (other.getPreferredSession() == PreferredSession.MORNING
                    || other.getPreferredSession() == PreferredSession.AFTERNOON)) {
                if (startTime.isBefore(other.getEndTime())) {
                    throw new IllegalArgumentException("Ca Tối phải bắt đầu sau giờ kết thúc của " + sessionLabel(other.getPreferredSession()) + ".");
                }
            } else if (session == PreferredSession.MORNING && other.getPreferredSession() == PreferredSession.EVENING) {
                if (endTime.isAfter(other.getStartTime())) {
                    throw new IllegalArgumentException("Ca Sáng phải kết thúc trước giờ bắt đầu Ca Tối.");
                }
            }
        }

        window.setStartTime(startTime);
        window.setEndTime(endTime);
        AppointmentSessionWindow saved = sessionWindowRepository.save(window);

        // Đồng bộ ngược khung giờ sang các ca làm việc Sáng/Chiều/Tối tương ứng của chi nhánh
        for (WorkShift ws : findBranchShifts(branch.getBranchId())) {
            if (resolveSession(ws) == session) {
                ws.setStartTime(startTime);
                ws.setEndTime(endTime);
                workShiftRepository.save(ws);
            }
        }
        return saved;
    }

    @Transactional
    public AppointmentSessionWindow toggleSessionWindow(Long windowId) {
        if (sessionWindowRepository == null) {
            throw new IllegalStateException("Chưa cấu hình AppointmentSessionWindowRepository.");
        }
        AppointmentSessionWindow window = findSessionWindow(windowId);
        window.setIsActive(!Boolean.TRUE.equals(window.getIsActive()));
        return sessionWindowRepository.save(window);
    }

    @Transactional
    public void syncSessionWindowsFromShifts(Long branchId) {
        Branch branch = findBranch(branchId);
        if (sessionWindowRepository == null) {
            return;
        }
        for (PreferredSession session : BOOKABLE_SESSIONS) {
            syncSingleSessionFromBranchShifts(branch, session, true);
        }
    }

    // Xác định phiên khám (Sáng/Chiều/Tối) tương ứng với ca làm việc; trả null nếu là ca hành chính/trực nội bộ.
    public PreferredSession resolveSession(WorkShift shift) {
        if (shift == null || shift.getStartTime() == null || shift.getEndTime() == null) {
            return null;
        }
        long mins = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes();
        if (mins <= 0 || mins > MAX_BOOKING_WINDOW_MINUTES) {
            return null;
        }
        String code = shift.getShiftCode() != null ? shift.getShiftCode().toUpperCase(Locale.ROOT) : "";
        String name = shift.getShiftName() != null ? shift.getShiftName().toUpperCase(Locale.ROOT) : "";
        if (code.contains("ALL") || code.contains("FULL") || code.contains("DEMO") || code.contains("SEED")
                || code.contains("HC") || code.contains("HANH_CHINH")
                || name.contains("CẢ NGÀY") || name.contains("CA NGAY")
                || name.contains("HÀNH CHÍNH") || name.contains("HANH CHINH")) {
            return null;
        }
        if (code.contains("AM") || code.contains("SANG")
                || name.contains("SÁNG") || name.contains("SANG") || name.contains("MORNING")) {
            return PreferredSession.MORNING;
        }
        if (code.contains("PM") || code.contains("CHIEU")
                || name.contains("CHIỀU") || name.contains("CHIEU") || name.contains("AFTERNOON")) {
            return PreferredSession.AFTERNOON;
        }
        if (code.contains("EV") || code.contains("TOI")
                || name.contains("TỐI") || name.contains("TOI") || name.contains("EVENING") || name.contains("NIGHT")) {
            return PreferredSession.EVENING;
        }
        LocalTime start = shift.getStartTime();
        if (start.isBefore(LocalTime.NOON)) {
            return PreferredSession.MORNING;
        }
        if (start.isBefore(LocalTime.of(17, 30))) {
            return PreferredSession.AFTERNOON;
        }
        return PreferredSession.EVENING;
    }

    // ===== PRIVATE HELPERS =====

    private void syncSessionWindowForShift(WorkShift shift) {
        if (sessionWindowRepository == null || shift == null || shift.getBranch() == null) {
            return;
        }
        PreferredSession session = resolveSession(shift);
        if (session == null) {
            return;
        }
        syncSingleSessionFromBranchShifts(shift.getBranch(), session, false);
    }

    private void syncSingleSessionFromBranchShifts(Branch branch, PreferredSession session, boolean deactivateIfMissing) {
        Long branchId = branch.getBranchId();
        List<WorkShift> matching = findBranchShifts(branchId).stream()
                .filter(ws -> resolveSession(ws) == session)
                .toList();

        Optional<WorkShift> activeShift = matching.stream()
                .filter(ws -> Boolean.TRUE.equals(ws.getIsActive()))
                .findFirst();
        Optional<WorkShift> chosen = activeShift.isPresent() ? activeShift : matching.stream().findFirst();

        AppointmentSessionWindow window = sessionWindowRepository
                .findByBranch_BranchIdAndPreferredSession(branchId, session)
                .orElseGet(() -> buildDefaultWindow(branch, session));

        if (chosen.isPresent()) {
            WorkShift ws = chosen.get();
            LocalTime[] bounded = clampToBranchHours(branch, ws.getStartTime(), ws.getEndTime());
            if (bounded != null) {
                window.setStartTime(bounded[0]);
                window.setEndTime(bounded[1]);
                window.setIsActive(Boolean.TRUE.equals(ws.getIsActive()));
                sessionWindowRepository.save(window);
                return;
            }
        }

        if (deactivateIfMissing) {
            window.setIsActive(false);
            sessionWindowRepository.save(window);
        } else if (window.getWindowId() != null && activeShift.isEmpty()) {
            window.setIsActive(false);
            sessionWindowRepository.save(window);
        }
    }

    private AppointmentSessionWindow buildInitialWindow(Branch branch, PreferredSession session) {
        List<WorkShift> matching = findBranchShifts(branch.getBranchId()).stream()
                .filter(ws -> resolveSession(ws) == session)
                .toList();
        Optional<WorkShift> activeShift = matching.stream()
                .filter(ws -> Boolean.TRUE.equals(ws.getIsActive()))
                .findFirst();
        Optional<WorkShift> chosen = activeShift.isPresent() ? activeShift : matching.stream().findFirst();

        if (chosen.isPresent()) {
            WorkShift ws = chosen.get();
            LocalTime[] bounded = clampToBranchHours(branch, ws.getStartTime(), ws.getEndTime());
            if (bounded != null) {
                AppointmentSessionWindow w = new AppointmentSessionWindow();
                w.setBranch(branch);
                w.setPreferredSession(session);
                w.setStartTime(bounded[0]);
                w.setEndTime(bounded[1]);
                w.setIsActive(Boolean.TRUE.equals(ws.getIsActive()));
                return w;
            }
        }
        return buildDefaultWindow(branch, session);
    }

    private AppointmentSessionWindow buildDefaultWindow(Branch branch, PreferredSession session) {
        LocalTime start;
        LocalTime end;
        boolean active = true;
        switch (session) {
            case MORNING -> {
                start = LocalTime.of(8, 0);
                end = LocalTime.of(12, 0);
            }
            case AFTERNOON -> {
                start = LocalTime.of(13, 30);
                end = LocalTime.of(17, 30);
            }
            case EVENING -> {
                start = LocalTime.of(18, 0);
                LocalTime defaultEveningEnd = LocalTime.of(20, 30);
                if (branch.getClosingTime() != null && branch.getClosingTime().isBefore(defaultEveningEnd)) {
                    end = branch.getClosingTime();
                } else {
                    end = defaultEveningEnd;
                }
            }
            default -> {
                start = LocalTime.of(8, 0);
                end = LocalTime.of(12, 0);
                active = false;
            }
        }
        LocalTime[] bounded = clampToBranchHours(branch, start, end);
        AppointmentSessionWindow w = new AppointmentSessionWindow();
        w.setBranch(branch);
        w.setPreferredSession(session);
        if (bounded != null) {
            w.setStartTime(bounded[0]);
            w.setEndTime(bounded[1]);
            w.setIsActive(active);
        } else {
            w.setStartTime(start);
            w.setEndTime(end.isAfter(start) ? end : LocalTime.of(20, 0));
            w.setIsActive(false);
        }
        return w;
    }

    private LocalTime[] clampToBranchHours(Branch branch, LocalTime rawStart, LocalTime rawEnd) {
        if (rawStart == null || rawEnd == null || !rawEnd.isAfter(rawStart)) {
            return null;
        }
        LocalTime start = rawStart;
        LocalTime end = rawEnd;
        if (branch.getOpeningTime() != null && branch.getOpeningTime().isAfter(start)) {
            start = branch.getOpeningTime();
        }
        if (branch.getClosingTime() != null && branch.getClosingTime().isBefore(end)) {
            end = branch.getClosingTime();
        }
        if (!start.isBefore(end)) {
            return null;
        }
        return new LocalTime[]{start, end};
    }

    private List<WorkShift> findBranchShifts(Long branchId) {
        return workShiftRepository.findAll().stream()
                .filter(s -> s.getBranch() != null && Objects.equals(branchId, s.getBranch().getBranchId()))
                .sorted(Comparator.comparing(WorkShift::getStartTime))
                .collect(Collectors.toList());
    }

    private boolean isOverLaborLimitShift(WorkShift shift) {
        if (shift == null || shift.getStartTime() == null || shift.getEndTime() == null) {
            return false;
        }
        long mins = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes();
        return mins > MAX_SHIFT_MINUTES;
    }

    private WorkShift findShift(Long shiftId) {
        return workShiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca làm việc #" + shiftId));
    }

    private AppointmentSessionWindow findSessionWindow(Long windowId) {
        return sessionWindowRepository.findById(windowId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cấu hình ca khám #" + windowId));
    }

    private Branch findBranch(Long branchId) {
        return branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh #" + branchId));
    }

    private void validateTime(LocalTime start, LocalTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("Giờ kết thúc phải sau giờ bắt đầu.");
        }
        long mins = Duration.between(start, end).toMinutes();
        if (mins < MIN_SHIFT_MINUTES) {
            throw new IllegalArgumentException("Thời lượng ca làm việc tối thiểu phải từ 1 giờ (60 phút) trở lên.");
        }
        if (mins > MAX_SHIFT_MINUTES) {
            throw new IllegalArgumentException("Theo Điều 105 & 107 Bộ luật Lao động, một ca làm việc không được kéo dài quá 12 giờ/ngày (không tạo ca 24/24).");
        }
    }

    private String sessionLabel(PreferredSession session) {
        if (session == null) return "";
        return switch (session) {
            case MORNING -> "Ca Sáng";
            case AFTERNOON -> "Ca Chiều";
            case EVENING -> "Ca Tối";
            default -> session.name();
        };
    }

    private String formatTime(LocalTime time) {
        return time == null ? "--:--" : String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private void createDefault(Branch branch) {
        createShift(branch, "SANG",  "Ca sáng (08:00 - 12:00)",  LocalTime.of(8, 0),  LocalTime.of(12, 0));
        createShift(branch, "CHIEU", "Ca chiều (13:30 - 17:30)", LocalTime.of(13, 30), LocalTime.of(17, 30));
        createShift(branch, "TOI",   "Ca tối (18:00 - 20:30)",   LocalTime.of(18, 0), LocalTime.of(20, 30));
    }

    private void createShift(Branch branch, String code, String name, LocalTime start, LocalTime end) {
        WorkShift s = new WorkShift();
        s.setBranch(branch);
        s.setShiftCode(code);
        s.setShiftName(name);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setIsActive(true);
        workShiftRepository.save(s);
    }
}
