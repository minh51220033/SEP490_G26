package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.DoctorShiftAssignment;
import com.cvdcms.entity.DoctorShiftReplacementLog;
import com.cvdcms.entity.Room;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.StaffSchedule;
import com.cvdcms.entity.User;
import com.cvdcms.entity.WorkShift;
import com.cvdcms.entity.enums.DoctorShiftAssignmentRole;
import com.cvdcms.entity.enums.DoctorShiftAssignmentStatus;
import com.cvdcms.entity.enums.RoomStatus;
import com.cvdcms.entity.enums.ScheduleStatus;
import com.cvdcms.repository.RoomRepository;
import com.cvdcms.service.BranchService;
import com.cvdcms.service.DoctorShiftAssignmentService;
import com.cvdcms.service.StaffScheduleService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.*;

// FT33 - Phân ca làm việc: SCR-33.1 (bảng phân ca tuần - ADMIN), SCR-33.3 (lịch cá nhân - mọi nhân viên).
@Controller
public class StaffScheduleController {

    private final StaffScheduleService scheduleService;
    private final BranchService branchService;
    private final com.cvdcms.repository.UserRepository userRepository;
    private final com.cvdcms.repository.AttendanceRecordRepository attendanceRepository;

    @Autowired(required = false)
    private DoctorShiftAssignmentService doctorShiftAssignmentService;

    @Autowired(required = false)
    private RoomRepository roomRepository;

    @Autowired(required = false)
    private com.cvdcms.repository.DentalChairRepository dentalChairRepository;

    public StaffScheduleController(StaffScheduleService scheduleService, BranchService branchService, com.cvdcms.repository.UserRepository userRepository, com.cvdcms.repository.AttendanceRecordRepository attendanceRepository) {
        this.scheduleService = scheduleService;
        this.branchService = branchService;
        this.userRepository = userRepository;
        this.attendanceRepository = attendanceRepository;
    }

    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("roleNames", session.getAttribute("roleNames"));
        if (session.getAttribute("loggedInUser") instanceof User u) {
            model.addAttribute("displayName", u.getUsername());
        }
    }

    private boolean isLoggedIn(HttpSession session) {
        return session.getAttribute("loggedInUser") instanceof User;
    }

    private void requireAdmin(HttpSession session) {
        if (!isLoggedIn(session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        User user = (User) session.getAttribute("loggedInUser");
        boolean isAdmin = userRepository.hasActiveAdminRole(user.getUserId());
        if (!isAdmin && !userRepository.hasActivePermission(user.getUserId(), "SCHEDULE_MANAGE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xếp lịch làm việc.");
        }
    }

    // ===================== SCR-33.1: BẢNG PHÂN CA TUẦN =====================

    @GetMapping("/admin/schedules")
    public String weeklySchedule(@RequestParam(required = false) Long branchId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate filterDate,
                                 @RequestParam(required = false) Long filterShiftId,
                                 @RequestParam(required = false) Long filterRoomId,
                                 @RequestParam(required = false) Long filterStaffId,
                                 @RequestParam(required = false, defaultValue = "ALL") String filterStatus,
                                 @RequestParam(required = false, defaultValue = "ALL") String viewType,
                                 Model model, HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        addLayoutAttributes(session, model);

        User currentUser = (User) session.getAttribute("loggedInUser");
        boolean isAdminUser = currentUser != null && userRepository.hasActiveAdminRole(currentUser.getUserId());
        model.addAttribute("isAdminUser", isAdminUser);

        List<Branch> branches = branchService.getActiveBranches();
        model.addAttribute("branches", branches);

        LocalDate monday = StaffScheduleService.mondayOf(week != null ? week : LocalDate.now());
        model.addAttribute("monday", monday);
        model.addAttribute("prevWeek", monday.minusWeeks(1));
        model.addAttribute("nextWeek", monday.plusWeeks(1));
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("filterDate", filterDate);
        model.addAttribute("filterShiftId", filterShiftId);
        model.addAttribute("filterRoomId", filterRoomId);
        model.addAttribute("filterStaffId", filterStaffId);
        model.addAttribute("filterStatus", filterStatus);
        model.addAttribute("viewType", viewType);

        // Đảm bảo branchId hợp lệ thuộc danh sách chi nhánh đang hoạt động; không cho phép Toàn hệ thống
        if (branchId != null) {
            Long targetBranchId = branchId;
            if (branches.stream().noneMatch(b -> b.getBranchId().equals(targetBranchId))) {
                branchId = null;
            }
        }
        if (branchId == null) {
            Object sb = session.getAttribute("currentBranchId");
            if (sb instanceof Long l && branches.stream().anyMatch(b -> b.getBranchId().equals(l))) {
                branchId = l;
            } else if (sb instanceof String s && !s.isBlank()) {
                try {
                    Long l = Long.parseLong(s);
                    if (branches.stream().anyMatch(b -> b.getBranchId().equals(l))) {
                        branchId = l;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        if (branchId == null && !branches.isEmpty()) {
            branchId = branches.get(0).getBranchId();
        }
        model.addAttribute("selectedBranchId", branchId);
        model.addAttribute("currentBranchId", branchId);
        model.addAttribute("activeMenu", "schedules");

        if (branchId != null) {
            Long finalId = branchId;
            Branch currentBranch = branches.stream().filter(b -> b.getBranchId().equals(finalId)).findFirst().orElse(null);
            model.addAttribute("currentBranch", currentBranch);
            model.addAttribute("selectedBranch", currentBranch);
            session.setAttribute("currentBranchId", branchId);

            List<WorkShift> shifts = scheduleService.getShifts(branchId);
            Map<LocalDate, Map<Long, List<StaffSchedule>>> matrix = scheduleService.buildWeekMatrix(branchId, monday, shifts);
            model.addAttribute("shifts", shifts);
            model.addAttribute("matrix", matrix);
            model.addAttribute("assignableStaff", scheduleService.getAssignableStaff(branchId));

            List<LocalDate> weekDays = new ArrayList<>(matrix.keySet());
            model.addAttribute("weekDays", weekDays);
            List<LocalDate> displayedDays = (filterDate != null && weekDays.contains(filterDate))
                    ? List.of(filterDate) : weekDays;
            List<WorkShift> displayedShifts = (filterShiftId != null)
                    ? shifts.stream().filter(sh -> filterShiftId.equals(sh.getShiftId())).toList()
                    : shifts;
            model.addAttribute("displayedDays", displayedDays);
            model.addAttribute("displayedShifts", displayedShifts);

            List<Room> activeRooms = roomRepository != null
                    ? roomRepository.findByBranchBranchIdAndStatusOrderByRoomCodeAsc(branchId, RoomStatus.ACTIVE)
                    : List.of();
            model.addAttribute("activeRooms", activeRooms);
            Map<Long, Room> roomByIdMap = new LinkedHashMap<>();
            for (Room rm : activeRooms) {
                roomByIdMap.put(rm.getRoomId(), rm);
            }
            model.addAttribute("roomByIdMap", roomByIdMap);

            Map<Long, Long> activeChairCountByRoom = new LinkedHashMap<>();
            Map<Long, List<Map<String, Object>>> chairsByRoomMap = new LinkedHashMap<>();
            if (dentalChairRepository != null) {
                for (com.cvdcms.entity.DentalChair ch : dentalChairRepository.findByBranchBranchIdOrderByChairCodeAsc(branchId)) {
                    if (ch.getRoom() == null || ch.getRoom().getRoomId() == null) continue;
                    Long rid = ch.getRoom().getRoomId();
                    boolean isAvail = ch.getStatus() == com.cvdcms.entity.enums.ChairStatus.AVAILABLE
                            || ch.getStatus() == com.cvdcms.entity.enums.ChairStatus.IN_USE
                            || ch.getStatus() == com.cvdcms.entity.enums.ChairStatus.TURNOVER;
                    if (isAvail) {
                        activeChairCountByRoom.merge(rid, 1L, Long::sum);
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chairId", ch.getChairId());
                    item.put("chairCode", ch.getChairCode());
                    item.put("chairName", ch.getChairName());
                    item.put("status", ch.getStatus() != null ? ch.getStatus().name() : "AVAILABLE");
                    item.put("operational", isAvail);
                    chairsByRoomMap.computeIfAbsent(rid, k -> new ArrayList<>()).add(item);
                }
            }
            model.addAttribute("activeChairCountByRoom", activeChairCountByRoom);
            String roomChairsJson = "{}";
            try {
                roomChairsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(chairsByRoomMap);
            } catch (Exception ignored) {}
            model.addAttribute("roomChairsJson", roomChairsJson);

            List<StaffProfile> branchDoctors = doctorShiftAssignmentService != null
                    ? doctorShiftAssignmentService.findBranchDoctors(branchId)
                    : List.of();
            model.addAttribute("branchDoctors", branchDoctors);
            Set<Long> doctorStaffIds = new HashSet<>();
            for (StaffProfile d : branchDoctors) {
                doctorStaffIds.add(d.getStaffId());
            }

            // Cấu trúc Chi nhánh -> Ngày -> Ca -> Phòng -> Danh sách bác sĩ
            List<DoctorShiftAssignment> weekDoctorAssignments = doctorShiftAssignmentService != null
                    ? doctorShiftAssignmentService.findWeekAssignments(branchId, monday, monday.plusDays(6))
                    : List.of();

            Set<Long> roomAssignedScheduleIds = new HashSet<>();
            Map<LocalDate, Map<Long, Map<Long, List<DoctorShiftAssignment>>>> doctorRoomMatrix = new LinkedHashMap<>();
            Map<LocalDate, Map<Long, List<Room>>> emptyRoomsMatrix = new LinkedHashMap<>();

            for (LocalDate d : displayedDays) {
                Map<Long, Map<Long, List<DoctorShiftAssignment>>> shiftMap = new LinkedHashMap<>();
                Map<Long, List<Room>> emptyShiftMap = new LinkedHashMap<>();
                for (WorkShift sh : displayedShifts) {
                    Map<Long, List<DoctorShiftAssignment>> roomMap = new LinkedHashMap<>();
                    for (Room rm : activeRooms) {
                        if (filterRoomId != null && !filterRoomId.equals(rm.getRoomId())) {
                            continue;
                        }
                        roomMap.put(rm.getRoomId(), new ArrayList<>());
                    }
                    shiftMap.put(sh.getShiftId(), roomMap);
                    emptyShiftMap.put(sh.getShiftId(), new ArrayList<>());
                }
                doctorRoomMatrix.put(d, shiftMap);
                emptyRoomsMatrix.put(d, emptyShiftMap);
            }

            for (DoctorShiftAssignment dsa : weekDoctorAssignments) {
                if (dsa.getStaffSchedule() != null) {
                    roomAssignedScheduleIds.add(dsa.getStaffSchedule().getScheduleId());
                }
                Map<Long, Map<Long, List<DoctorShiftAssignment>>> shiftMap = doctorRoomMatrix.get(dsa.getWorkDate());
                if (shiftMap == null) continue;
                Map<Long, List<DoctorShiftAssignment>> roomMap = shiftMap.get(dsa.getShift().getShiftId());
                if (roomMap == null) continue;
                if (filterRoomId != null && !filterRoomId.equals(dsa.getRoom().getRoomId())) continue;
                if (filterStaffId != null && !filterStaffId.equals(dsa.getStaff().getStaffId())) continue;
                if (!matchesStatusFilter(dsa.getStatus(), filterStatus)) continue;
                roomMap.computeIfAbsent(dsa.getRoom().getRoomId(), k -> new ArrayList<>()).add(dsa);
            }

            // Chỉ giữ card phòng nếu: có bác sĩ trong ca đó HOẶC đang lọc phòng cụ thể HOẶC lọc EMPTY_ROOM;
            // đồng thời gom danh sách phòng chưa có bác sĩ ACTIVE vào emptyRoomsMatrix để hiển thị cảnh báo gọn.
            for (LocalDate d : displayedDays) {
                for (WorkShift sh : displayedShifts) {
                    Map<Long, List<DoctorShiftAssignment>> roomMap = doctorRoomMatrix.get(d).get(sh.getShiftId());
                    List<Room> emptyRooms = emptyRoomsMatrix.get(d).get(sh.getShiftId());
                    Iterator<Map.Entry<Long, List<DoctorShiftAssignment>>> it = roomMap.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Long, List<DoctorShiftAssignment>> e = it.next();
                        List<DoctorShiftAssignment> list = e.getValue();
                        boolean hasAnyActiveDoctor = list.stream()
                                .anyMatch(item -> item.getStatus() == DoctorShiftAssignmentStatus.ACTIVE);
                        if (!hasAnyActiveDoctor) {
                            activeRooms.stream().filter(r -> Objects.equals(r.getRoomId(), e.getKey())).findFirst()
                                    .ifPresent(emptyRooms::add);
                        }
                        boolean showEmptyExplicit = "EMPTY_ROOM".equalsIgnoreCase(filterStatus) || filterRoomId != null;
                        if (list.isEmpty() && !showEmptyExplicit) {
                            it.remove();
                        } else if ("EMPTY_ROOM".equalsIgnoreCase(filterStatus) && hasAnyActiveDoctor) {
                            it.remove();
                        }
                    }
                }
            }

            // Nhân sự hỗ trợ ca (Lễ tân / Kế toán / Kho / Điều dưỡng... & lịch trực cũ chưa gắn phòng)
            Map<LocalDate, Map<Long, List<StaffSchedule>>> supportMatrix = new LinkedHashMap<>();
            for (LocalDate d : displayedDays) {
                Map<Long, List<StaffSchedule>> shMap = new LinkedHashMap<>();
                for (WorkShift sh : displayedShifts) {
                    List<StaffSchedule> rawList = matrix.getOrDefault(d, Map.of()).getOrDefault(sh.getShiftId(), List.of());
                    List<StaffSchedule> filtered = new ArrayList<>();
                    for (StaffSchedule sc : rawList) {
                        if (roomAssignedScheduleIds.contains(sc.getScheduleId())) {
                            continue;
                        }
                        if (filterStaffId != null && (sc.getStaff() == null || !filterStaffId.equals(sc.getStaff().getStaffId()))) {
                            continue;
                        }
                        if ("ON_LEAVE".equalsIgnoreCase(filterStatus) && sc.getStatus() != ScheduleStatus.ON_LEAVE) {
                            continue;
                        }
                        if ("ACTIVE".equalsIgnoreCase(filterStatus) && sc.getStatus() != ScheduleStatus.SCHEDULED) {
                            continue;
                        }
                        if ("REPLACED".equalsIgnoreCase(filterStatus) || "EMPTY_ROOM".equalsIgnoreCase(filterStatus)) {
                            continue;
                        }
                        filtered.add(sc);
                    }
                    shMap.put(sh.getShiftId(), filtered);
                }
                supportMatrix.put(d, shMap);
            }

            model.addAttribute("doctorRoomMatrix", doctorRoomMatrix);
            model.addAttribute("emptyRoomsMatrix", emptyRoomsMatrix);
            model.addAttribute("supportMatrix", supportMatrix);
            model.addAttribute("doctorStaffIds", doctorStaffIds);
            model.addAttribute("replacementLogs", doctorShiftAssignmentService != null
                    ? doctorShiftAssignmentService.findReplacementLogs(branchId, monday, monday.plusDays(6))
                    : List.<DoctorShiftReplacementLog>of());

            // Danh sách nhân viên đã chấm công hôm nay tại chi nhánh (staffId -> giờ check-in HH:mm)
            java.util.Map<Long, String> checkedInStaffTimesToday = new java.util.HashMap<>();
            LocalDate today = LocalDate.now();
            for (com.cvdcms.entity.AttendanceRecord a : attendanceRepository.findByBranchAndRange(branchId, today, today)) {
                if (a.getCheckInAt() != null) {
                    checkedInStaffTimesToday.put(a.getStaff().getStaffId(),
                            a.getCheckInAt().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
                }
            }
            model.addAttribute("checkedInStaffTimesToday", checkedInStaffTimesToday);
        }
        return "schedule/weekly";
    }

    private static boolean matchesStatusFilter(DoctorShiftAssignmentStatus status, String filterStatus) {
        if (filterStatus == null || "ALL".equalsIgnoreCase(filterStatus) || "EMPTY_ROOM".equalsIgnoreCase(filterStatus)) {
            return true;
        }
        return status != null && status.name().equalsIgnoreCase(filterStatus.trim());
    }

    @PostMapping("/admin/schedules/assign")
    public String assign(@RequestParam Long branchId,
                         @RequestParam Long staffId,
                         @RequestParam Long shiftId,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
                         @RequestParam(required = false) String note,
                         RedirectAttributes ra, HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        try {
            scheduleService.assign(branchId, staffId, shiftId, workDate, note);
            ra.addFlashAttribute("successMessage", "Đã xếp ca làm việc.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return redirectWeek(branchId, workDate);
    }

    @PostMapping("/admin/schedules/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam Long branchId,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                         RedirectAttributes ra, HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        try {
            boolean hadAttendance = scheduleService.remove(id);
            if (hadAttendance) {
                ra.addFlashAttribute("successMessage", "Đã gỡ nhân viên khỏi ca trực và tự động hủy lượt chấm công nhầm hôm nay.");
            } else {
                ra.addFlashAttribute("successMessage", "Đã gỡ nhân viên khỏi ca trực.");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return redirectWeek(branchId, week);
    }

    @PostMapping("/admin/schedules/copy-week")
    public String copyWeek(@RequestParam Long branchId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                           RedirectAttributes ra, HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        LocalDate monday = StaffScheduleService.mondayOf(week);
        int n = scheduleService.copyWeek(branchId, monday.minusWeeks(1), monday);
        if (n > 0) {
            ra.addFlashAttribute("successMessage", "Đã sao chép " + n + " ca từ tuần trước.");
        } else {
            ra.addFlashAttribute("errorMessage", "Tuần trước không có ca nào để sao chép (hoặc đã được xếp đủ).");
        }
        return redirectWeek(branchId, monday);
    }

    // ===================== PHÂN CÔNG & THAY THẾ BÁC SĨ THEO PHÒNG / CA =====================

    @PostMapping("/admin/schedules/doctor-shifts/assign")
    public String assignDoctorsToRoom(
            @RequestParam Long branchId,
            @RequestParam Long shiftId,
            @RequestParam Long roomId,
            @RequestParam(required = false) Long chairId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
            @RequestParam(name = "staffIds", required = false) List<Long> staffIds,
            @RequestParam(required = false, defaultValue = "CO_ATTENDING") DoctorShiftAssignmentRole role,
            @RequestParam(required = false) String note,
            RedirectAttributes ra,
            HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        User user = (User) session.getAttribute("loggedInUser");
        try {
            if (doctorShiftAssignmentService == null) {
                throw new IllegalStateException("Dịch vụ phân công bác sĩ theo phòng chưa sẵn sàng.");
            }
            List<DoctorShiftAssignment> created = doctorShiftAssignmentService.assignDoctorsToRoom(
                    branchId, workDate, shiftId, roomId, chairId, staffIds, role, note, user != null ? user.getUserId() : null);
            ra.addFlashAttribute("successMessage", "Đã phân công " + created.size() + " bác sĩ vào phòng trực.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return redirectWeek(branchId, workDate);
    }

    @PostMapping("/admin/schedules/doctor-shifts/{id}/remove")
    public String removeDoctorFromRoom(
            @PathVariable Long id,
            @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
            RedirectAttributes ra,
            HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        User user = (User) session.getAttribute("loggedInUser");
        try {
            doctorShiftAssignmentService.removeDoctorFromRoom(id, user != null ? user.getUserId() : null);
            ra.addFlashAttribute("successMessage", "Đã gỡ bác sĩ khỏi phòng trực trong ca.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return redirectWeek(branchId, week);
    }

    @PostMapping("/admin/schedules/doctor-shifts/{id}/move-room")
    public String moveDoctorToRoom(
            @PathVariable Long id,
            @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
            @RequestParam Long targetRoomId,
            RedirectAttributes ra,
            HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        User user = (User) session.getAttribute("loggedInUser");
        try {
            doctorShiftAssignmentService.moveDoctorToRoom(id, targetRoomId, user != null ? user.getUserId() : null);
            ra.addFlashAttribute("successMessage", "Đã chuyển bác sĩ sang phòng trực mới.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return redirectWeek(branchId, week);
    }

    @GetMapping("/admin/schedules/doctor-shifts/{id}/replacement-preview")
    @ResponseBody
    public ResponseEntity<DoctorShiftAssignmentService.ReplacementPreview> previewReplacement(
            @PathVariable Long id,
            HttpSession session) {
        if (!isLoggedIn(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        requireAdmin(session);
        return ResponseEntity.ok(doctorShiftAssignmentService.previewReplacement(id));
    }

    @PostMapping("/admin/schedules/doctor-shifts/{id}/replace")
    public String replaceDoctorInShift(
            @PathVariable Long id,
            @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
            @RequestParam Long replacementStaffId,
            @RequestParam(required = false, defaultValue = "false") boolean overrideRoomPermission,
            @RequestParam String replacementReason,
            RedirectAttributes ra,
            HttpSession session) {
        if (!isLoggedIn(session)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        User user = (User) session.getAttribute("loggedInUser");
        try {
            DoctorShiftAssignmentService.ReplacementResult res = doctorShiftAssignmentService.replaceDoctorInShift(
                    id, replacementStaffId, overrideRoomPermission, replacementReason,
                    user != null ? user.getUserId() : null);
            String msg = "Đã thay thế bác sĩ trực. Chuyển giao: " + res.transferredAppointments()
                    + " lịch hẹn; Cần điều phối riêng (NEEDS_REASSIGNMENT): " + res.needsReassignmentAppointments() + " lịch hẹn.";
            ra.addFlashAttribute("successMessage", msg);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return redirectWeek(branchId, week);
    }

    private String redirectWeek(Long branchId, LocalDate anyDayInWeek) {
        return "redirect:/admin/schedules?branchId=" + branchId
                + "&week=" + StaffScheduleService.mondayOf(anyDayInWeek);
    }

    // ===================== SCR-33.3: LỊCH LÀM VIỆC CÁ NHÂN =====================

    @GetMapping("/schedules/my")
    public String mySchedule(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                             Model model, HttpSession session) {
        if (!(session.getAttribute("loggedInUser") instanceof User user)) {
            return "redirect:/login";
        }
        Object roleNamesObj = session.getAttribute("roleNames");
        boolean isAdmin = roleNamesObj instanceof Collection<?> c
                && c.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(String.valueOf(r)) || "ROLE_ADMIN".equalsIgnoreCase(String.valueOf(r)));
        boolean isStaffRole = roleNamesObj instanceof Collection<?> c
                && c.stream().anyMatch(r -> "BAC_SI".equalsIgnoreCase(String.valueOf(r))
                        || "LE_TAN".equalsIgnoreCase(String.valueOf(r))
                        || "KE_TOAN".equalsIgnoreCase(String.valueOf(r))
                        || "QUAN_LY_KHO".equalsIgnoreCase(String.valueOf(r))
                        || "QUAN_LY_kHO".equalsIgnoreCase(String.valueOf(r)));
        if (isAdmin && !isStaffRole) {
            return "redirect:/admin/schedules";
        }
        if (!isAdmin && !userRepository.hasActivePermission(user.getUserId(), "SCHEDULE_MY")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem lịch trực cá nhân.");
        }
        if (week != null) {
            return "redirect:/attendance/my?week=" + week;
        }
        return "redirect:/attendance/my";
    }

    // ===================== API: BÁC SĨ KHẢ DỤNG THEO NGÀY =====================

    @GetMapping("/api/schedules/available-doctors")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> availableDoctors(
            @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpSession session) {
        if (!isLoggedIn(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(scheduleService.getAvailableDoctors(branchId, date));
    }
}
