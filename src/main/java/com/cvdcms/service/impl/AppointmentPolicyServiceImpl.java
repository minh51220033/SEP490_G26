package com.cvdcms.service.impl;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.WorkShiftService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class AppointmentPolicyServiceImpl implements AppointmentPolicyService {
    private static final Pattern WAREHOUSE_WORD_PATTERN =
            Pattern.compile("(?<![\\p{L}\\p{N}])kho(?![\\p{L}\\p{N}])");
    private static final List<AppointmentStatus> OCCUPYING = List.of(
            AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED,
            AppointmentStatus.CHECKED_IN);

    private final AppointmentPolicyRepository appointments;
    private final AppointmentSessionWindowRepository windows;
    private final AppointmentSessionPolicyRepository policies;
    private final StaffBranchAssignmentRepository assignments;
    private final StaffScheduleRepository schedules;
    private final LeaveRequestRepository leaves;
    private final StaffProfileRepository staffProfiles;
    private final WorkShiftRepository workShifts;
    private final int defaultBranchCapacity;
    private final int defaultDoctorCapacity;

    @Autowired(required = false)
    private VisitRepository visitRepository;

    private Clock clock = Clock.system(CLINIC_ZONE);

    @Autowired
    public AppointmentPolicyServiceImpl(AppointmentPolicyRepository appointments,
            AppointmentSessionWindowRepository windows, AppointmentSessionPolicyRepository policies,
            StaffBranchAssignmentRepository assignments, StaffScheduleRepository schedules,
            LeaveRequestRepository leaves,
            @Autowired(required = false) StaffProfileRepository staffProfiles,
            @Autowired(required = false) WorkShiftRepository workShifts,
            @Value("${appointments.branch-capacity:24}") int defaultBranchCapacity,
            @Value("${appointments.doctor-capacity:10}") int defaultDoctorCapacity) {
        this.appointments = appointments;
        this.windows = windows;
        this.policies = policies;
        this.assignments = assignments;
        this.schedules = schedules;
        this.leaves = leaves;
        this.staffProfiles = staffProfiles;
        this.workShifts = workShifts;
        this.defaultBranchCapacity = defaultBranchCapacity;
        this.defaultDoctorCapacity = defaultDoctorCapacity;
    }

    public AppointmentPolicyServiceImpl(AppointmentPolicyRepository appointments,
            AppointmentSessionWindowRepository windows, AppointmentSessionPolicyRepository policies,
            StaffBranchAssignmentRepository assignments, StaffScheduleRepository schedules,
            LeaveRequestRepository leaves,
            StaffProfileRepository staffProfiles,
            int defaultBranchCapacity,
            int defaultDoctorCapacity) {
        this(appointments, windows, policies, assignments, schedules, leaves, staffProfiles, null,
                defaultBranchCapacity, defaultDoctorCapacity);
    }

    public AppointmentPolicyServiceImpl(AppointmentPolicyRepository appointments,
            AppointmentSessionWindowRepository windows, AppointmentSessionPolicyRepository policies,
            StaffBranchAssignmentRepository assignments, StaffScheduleRepository schedules,
            LeaveRequestRepository leaves,
            int defaultBranchCapacity,
            int defaultDoctorCapacity) {
        this(appointments, windows, policies, assignments, schedules, leaves, null, null,
                defaultBranchCapacity, defaultDoctorCapacity);
    }

    @Override
    public Availability availability(Branch branch, LocalDate date, PreferredSession session,
                                     Long excludeAppointmentId, AppointmentActorType actorType) {
        String invalid = invalidSession(branch, date, session, actorType);
        if (invalid != null) return new Availability(false, invalid, 0, List.of());
        List<Appointment> booked = booked(branch.getBranchId(), date, session, excludeAppointmentId);
        List<StaffProfile> doctors = eligibleDoctors(branch, date, session);
        if (doctors.isEmpty()) {
            return new Availability(false, "Không có bác sĩ trực khả dụng trong buổi đã chọn.", 0, List.of());
        }
        AppointmentSessionPolicy policy = policy(branch.getBranchId(), date, session);
        int doctorCap = doctorCapacity(policy);
        int capacity = Math.min(branchCapacity(policy), doctors.size() * doctorCap);
        int remaining = Math.max(0, capacity - booked.size());
        List<DoctorOption> options = doctors.stream().map(doctor -> {
            long doctorBooked = booked.stream().filter(a -> a.getDoctor() != null
                    && doctor.getStaffId().equals(a.getDoctor().getStaffId())).count();
            return new DoctorOption(doctor.getStaffId(), doctor.getFullName(), doctor.getSpecialty(),
                    Math.max(0, doctorCap - (int) doctorBooked));
        }).filter(option -> option.remaining() > 0).toList();
        String reason = remaining == 0 ? "Buổi khám đã đầy." : null;
        return new Availability(remaining > 0, reason, remaining, options);
    }

    @Override
    public List<SessionLoadSummary> dayLoadOverview(Branch branch, LocalDate date,
                                                    AppointmentActorType actorType) {
        if (branch == null || date == null) return List.of();
        List<PreferredSession> sessions = List.of(PreferredSession.MORNING,
                PreferredSession.AFTERNOON, PreferredSession.EVENING);
        Map<PreferredSession, String> names = Map.of(
                PreferredSession.MORNING, "Buổi sáng",
                PreferredSession.AFTERNOON, "Buổi chiều",
                PreferredSession.EVENING, "Buổi tối"
        );

        record RawLoad(PreferredSession session, String name, int booked, int capacity, int remaining,
                       String status, boolean active) {}
        List<RawLoad> rawList = new ArrayList<>();

        for (PreferredSession session : sessions) {
            String name = names.getOrDefault(session, session.name());
            String invalid = invalidSession(branch, date, session, actorType);
            List<StaffProfile> doctors = eligibleDoctors(branch, date, session);
            if (invalid != null || doctors.isEmpty()) {
                rawList.add(new RawLoad(session, name, 0, 0, 0, "OFF", false));
                continue;
            }
            AppointmentSessionPolicy policy = policy(branch.getBranchId(), date, session);
            int capacity = Math.min(branchCapacity(policy), doctors.size() * doctorCapacity(policy));
            int booked = booked(branch.getBranchId(), date, session, null).size();
            int remaining = Math.max(0, capacity - booked);
            double ratio = capacity == 0 ? 1.0 : (double) booked / capacity;
            String status = remaining == 0 ? "FULL" : ratio >= 0.75 ? "HIGH" : ratio <= 0.35 ? "LOW" : "BALANCED";
            rawList.add(new RawLoad(session, name, booked, capacity, remaining, status, true));
        }

        Optional<RawLoad> lightSession = rawList.stream()
                .filter(r -> r.active && "LOW".equals(r.status) && r.remaining >= 2)
                .findFirst();

        List<SessionLoadSummary> result = new ArrayList<>();
        for (RawLoad r : rawList) {
            String rec;
            if (!r.active) {
                rec = "Không có ca trực";
            } else if ("FULL".equals(r.status)) {
                rec = "Ca đã đủ định mức tiếp nhận (" + r.booked + "/" + r.capacity + " ca). Vui lòng chọn ca khác.";
            } else if ("HIGH".equals(r.status) && lightSession.isPresent() && !lightSession.get().session.equals(r.session)) {
                rec = "Ca " + r.name + " sắp đầy (" + r.booked + "/" + r.capacity + " ca). Khuyến nghị điều phối sang "
                        + lightSession.get().name + " (còn " + lightSession.get().remaining + " chỗ) để tránh dồn lịch.";
            } else if ("LOW".equals(r.status)) {
                rec = "Ca " + r.name + " đang vắng (mới " + r.booked + "/" + r.capacity + " ca), rất thích hợp tiếp nhận thêm.";
            } else {
                rec = "Ca " + r.name + " đang tiếp nhận ổn định (" + r.booked + "/" + r.capacity + " ca).";
            }
            result.add(new SessionLoadSummary(r.session, r.name, r.booked, r.capacity, r.remaining, r.status, rec));
        }
        return result;
    }

    @Override
    public void ensureBookable(Branch branch, PatientProfile profile, LocalDate date,
            PreferredSession session, Long doctorId, Long excludeAppointmentId,
            AppointmentActorType actorType) {
        String invalid = invalidSession(branch, date, session, actorType);
        if (invalid != null) throw conflict(invalid);
        if (profile != null && profile.getProfileId() != null) {
            PatientSessionConflict dup = findSameSessionConflict(
                    profile.getProfileId(),
                    branch != null ? branch.getBranchId() : null,
                    date,
                    session,
                    excludeAppointmentId
            );
            if (dup.hasConflict()) {
                throw conflict(dup.message());
            }
        }
        List<StaffProfile> doctors = eligibleDoctors(branch, date, session);
        if (doctors.isEmpty()) {
            throw conflict("Không có bác sĩ trực khả dụng trong buổi đã chọn.");
        }
        List<Appointment> booked = booked(branch.getBranchId(), date, session, excludeAppointmentId);
        AppointmentSessionPolicy policy = policy(branch.getBranchId(), date, session);
        int maxCapacity = Math.min(branchCapacity(policy), doctors.size() * doctorCapacity(policy));
        if (booked.size() >= maxCapacity) {
            throw conflict("Buổi khám đã đủ định mức tiếp nhận (" + booked.size() + "/" + maxCapacity + " ca).");
        }
        if (doctorId != null) {
            if (doctors.isEmpty() || doctors.stream().noneMatch(d -> doctorId.equals(d.getStaffId()))) {
                throw conflict("Bác sĩ không khả dụng trong ngày và buổi đã chọn.");
            }
            long doctorBooked = booked.stream().filter(a -> a.getDoctor() != null
                    && doctorId.equals(a.getDoctor().getStaffId())).count();
            if (doctorBooked >= doctorCapacity(policy)) {
                throw conflict("Bác sĩ đã hết khả năng nhận lịch trong buổi (" + doctorBooked + "/" + doctorCapacity(policy) + " ca).");
            }
        }
    }

    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    @Override
    public PatientSessionConflict findSameSessionConflict(Long profileId, LocalDate date,
                                                          PreferredSession session, Long excludeAppointmentId) {
        return findSameSessionConflict(profileId, null, date, session, excludeAppointmentId);
    }

    private PatientSessionConflict findSameSessionConflict(Long profileId, Long branchId, LocalDate date,
                                                           PreferredSession session, Long excludeAppointmentId) {
        if (profileId == null || date == null || session == null) {
            return new PatientSessionConflict(false, null, null, null, null);
        }
        Map<Long, Appointment> uniqueCandidates = new LinkedHashMap<>();
        List<Appointment> across = appointments.findOccupyingForPatientDayAcrossBranches(profileId, date, OCCUPYING);
        if (across != null) {
            for (Appointment a : across) {
                if (a != null) uniqueCandidates.put(a.getAppointmentId(), a);
            }
        }
        if (uniqueCandidates.isEmpty() && branchId != null) {
            List<Appointment> branchScoped = appointments.findOccupyingForPatientDay(profileId, branchId, date, OCCUPYING);
            if (branchScoped != null) {
                for (Appointment a : branchScoped) {
                    if (a != null) uniqueCandidates.put(a.getAppointmentId(), a);
                }
            }
        }

        List<Appointment> sessionMatches = uniqueCandidates.values().stream()
                .filter(a -> excludeAppointmentId == null || !Objects.equals(a.getAppointmentId(), excludeAppointmentId))
                .filter(a -> session == PreferredSession.ANY
                        || a.getPreferredSession() == session
                        || a.getPreferredSession() == PreferredSession.ANY)
                .toList();
        if (sessionMatches.isEmpty()) {
            return new PatientSessionConflict(false, null, null, null, null);
        }

        Map<Long, Visit> visitByAppt = Collections.emptyMap();
        List<Long> checkedInIds = sessionMatches.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.CHECKED_IN && a.getAppointmentId() != null)
                .map(Appointment::getAppointmentId)
                .toList();
        if (!checkedInIds.isEmpty() && visitRepository != null) {
            List<Visit> linked = visitRepository.findByAppointmentIdIn(checkedInIds);
            if (linked != null && !linked.isEmpty()) {
                visitByAppt = new HashMap<>();
                for (Visit v : linked) {
                    if (v != null && v.getAppointmentId() != null) {
                        visitByAppt.putIfAbsent(v.getAppointmentId(), v);
                    }
                }
            }
        }

        for (Appointment existing : sessionMatches) {
            if (existing.getStatus() == AppointmentStatus.CHECKED_IN && visitRepository != null) {
                Visit linkedVisit = visitByAppt.get(existing.getAppointmentId());
                if (linkedVisit != null && isClinicalExamFinished(linkedVisit)) {
                    // Bệnh nhân đã hoàn thành khám lâm sàng (WAITING_PAYMENT với actual_end_time / COMPLETED / CANCELLED)
                    // -> Được phép đặt thêm lịch khám tiếp trong cùng buổi nếu còn sức chứa.
                    continue;
                }
            }
            String existingBranchName = existing.getBranch() != null ? existing.getBranch().getBranchName() : "cơ sở khác";
            String sessionText = switch (session) {
                case MORNING -> "buổi sáng";
                case AFTERNOON -> "buổi chiều";
                case EVENING -> "buổi tối";
                default -> "buổi khám này";
            };
            String dateText = date.equals(LocalDate.now(clock))
                    ? "hôm nay (" + date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ")"
                    : "ngày " + date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            String codeSuffix = existing.getAppointmentCode() != null ? " (Mã " + existing.getAppointmentCode() + ")" : "";
            String msg = "Người khám này đã có lịch hẹn trong " + sessionText + " " + dateText
                    + " tại " + existingBranchName + codeSuffix
                    + ". Vui lòng kiểm tra lịch hẹn hiện tại trước khi đặt lịch mới.";
            return new PatientSessionConflict(true, existing.getAppointmentId(), existing.getAppointmentCode(), existingBranchName, msg);
        }
        return new PatientSessionConflict(false, null, null, null, null);
    }

    private static boolean isClinicalExamFinished(Visit visit) {
        if (visit == null || visit.getStatus() == null) {
            return false;
        }
        return switch (visit.getStatus()) {
            case COMPLETED, CANCELLED, WAITING_PAYMENT -> true;
            case WAITING, IN_EXAM, WAITING_PARACLINICAL, WAITING_RESULT, IN_TREATMENT -> false;
        };
    }

    @Override
    public List<DoctorOption> doctorsForDate(Branch branch, LocalDate date,
                                             AppointmentActorType actorType) {
        if (branch == null || date == null) return List.of();
        Map<Long, StaffProfile> doctors = new LinkedHashMap<>();
        for (PreferredSession session : List.of(PreferredSession.MORNING,
                PreferredSession.AFTERNOON, PreferredSession.EVENING)) {
            if (invalidSession(branch, date, session, actorType) == null) {
                eligibleDoctors(branch, date, session)
                        .forEach(doctor -> doctors.put(doctor.getStaffId(), doctor));
            }
        }
        return doctors.values().stream().sorted(Comparator.comparing(StaffProfile::getFullName))
                .map(d -> new DoctorOption(d.getStaffId(), d.getFullName(), d.getSpecialty(), 0))
                .toList();
    }

    @Override
    public boolean doctorAvailable(Branch branch, LocalDate date, PreferredSession session,
                                   Long doctorId) {
        return doctorId == null || invalidSession(branch, date, session,
                AppointmentActorType.RECEPTIONIST) == null
                && eligibleDoctors(branch, date, session).stream()
                .anyMatch(d -> doctorId.equals(d.getStaffId()));
    }

    @Override
    public boolean sessionEnded(Branch branch, LocalDate date, PreferredSession session,
                                LocalDateTime now) {
        if (date == null || session == null || session == PreferredSession.ANY) return false;
        SessionRange range = sessionRange(branch, session);
        if (branch != null && branch.getClosingTime() != null && !now.toLocalTime().isBefore(branch.getClosingTime())) {
            return true;
        }
        return !now.isBefore(LocalDateTime.of(date, range.end()));
    }

    @Override
    public void ensureModificationCutoff(Appointment appointment, AppointmentActorType actorType) {
        // Cho phép bệnh nhân hủy, sửa lịch hẹn linh hoạt như bên lễ tân, không áp dụng giới hạn thời gian trước 2 tiếng
    }

    private String invalidSession(Branch branch, LocalDate date, PreferredSession session,
                                  AppointmentActorType actorType) {
        if (branch == null || !Boolean.TRUE.equals(branch.getIsActive())) return "Chi nhánh không hoạt động.";
        if (date == null || session == null || session == PreferredSession.ANY) {
            return "Vui lòng chọn ngày và ca khám cụ thể.";
        }
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        if (date.isBefore(today) || date.isAfter(today.plusDays(90))) {
            return "Ngày khám phải nằm trong 90 ngày tới.";
        }
        String day = date.getDayOfWeek() == DayOfWeek.SUNDAY ? "CN"
                : String.valueOf(date.getDayOfWeek().getValue() + 1);
        if (branch.getWorkingDays() == null || Arrays.stream(branch.getWorkingDays().split(","))
                .map(String::trim).noneMatch(day::equalsIgnoreCase)) {
            return "Chi nhánh không làm việc trong ngày này.";
        }
        SessionRange range = sessionRange(branch, session);
        if (!range.active() || !range.start().isBefore(range.end())) {
            return "Chi nhánh không nhận lịch trong ca này.";
        }
        if (branch.getOpeningTime() != null && range.end().isBefore(branch.getOpeningTime())) {
            return "Chi nhánh chưa mở cửa trong ca được chọn.";
        }
        if (branch.getClosingTime() != null && range.start().isAfter(branch.getClosingTime())) {
            return "Chi nhánh đã đóng cửa trước ca được chọn.";
        }
        // Tuân thủ nghiêm ngặt giờ làm việc và ca khám: nếu hôm nay đã quá giờ kết ca hoặc đóng cửa phòng khám
        if (date.equals(today)) {
            LocalTime now = LocalTime.now(CLINIC_ZONE);
            if (branch.getClosingTime() != null && !now.isBefore(branch.getClosingTime())) {
                return "Phòng khám đã đóng cửa trong ngày hôm nay.";
            }
            if (!now.isBefore(range.end())) {
                return "Ca khám trong ngày đã kết thúc.";
            }
        }
        return null;
    }

    private List<Appointment> booked(Long branchId, LocalDate date, PreferredSession session,
                                     Long excludeAppointmentId) {
        return appointments.findOccupyingForSession(branchId, date, session, OCCUPYING).stream()
                .filter(a -> !Objects.equals(a.getAppointmentId(), excludeAppointmentId)).toList();
    }

    private List<StaffProfile> eligibleDoctors(Branch branch, LocalDate date,
                                               PreferredSession session) {
        if (branch == null || date == null) return List.of();
        SessionRange range = sessionRange(branch, session);
        Set<Long> assigned = new HashSet<>();
        Map<Long, StaffProfile> activeBranchDoctors = new LinkedHashMap<>();

        // 1. Chỉ lấy các bác sĩ thuộc biên chế phân công chính thức tại ĐÚNG CHI NHÁNH NÀY
        for (StaffBranchAssignment assignment : assignments.findByBranchAndStatus(
                branch.getBranchId(), AssignmentStatus.ACTIVE)) {
            if (assignment.getStartDate() != null && assignment.getStartDate().isAfter(date)) continue;
            if (assignment.getEndDate() != null && assignment.getEndDate().isBefore(date)) continue;
            StaffProfile staff = assignment.getStaff();
            if (staff == null || staff.getStatus() != StaffStatus.ACTIVE) continue;

            if (isDoctorAssignment(assignment, staff)) {
                assigned.add(staff.getStaffId());
                activeBranchDoctors.put(staff.getStaffId(), staff);
            }
        }

        // Tuyệt đối KHÔNG quét toàn bộ bác sĩ của hệ thống khi activeBranchDoctors rỗng.
        // Cơ sở nào chỉ hiển thị bác sĩ thuộc cơ sở đó!

        List<LeaveRequest> approved = leaves.findApprovedOn(branch.getBranchId(), date, LeaveStatus.APPROVED);
        Map<Long, StaffProfile> result = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        LocalTime now = LocalTime.now(CLINIC_ZONE);

        List<StaffSchedule> branchSchedules = schedules.findForBooking(branch.getBranchId(), date, ScheduleStatus.SCHEDULED);
        for (StaffSchedule schedule : branchSchedules) {
            StaffProfile doctor = schedule.getStaff();
            WorkShift shift = schedule.getShift();
            if (doctor == null || !assigned.contains(doctor.getStaffId()) || shift == null || !Boolean.TRUE.equals(shift.getIsActive())) continue;
            if (isInternalDutyShift(shift)) continue;
            if (!shift.getStartTime().isBefore(range.end()) || !shift.getEndTime().isAfter(range.start())) continue;

            // Nếu ngày khám là hôm nay và ca làm việc của bác sĩ (hoặc phiên khám) đã qua thời gian kết ca hiệu lực
            LocalTime effectiveEnd = shift.getEndTime().isBefore(range.end()) ? shift.getEndTime() : range.end();
            if (date.equals(today) && !now.isBefore(effectiveEnd)) {
                continue;
            }

            boolean absent = approved.stream().anyMatch(leave ->
                    leave.getStaff().getStaffId().equals(doctor.getStaffId())
                            && (leave.getShift() == null || leave.getShift().getShiftId().equals(shift.getShiftId())));
            if (!absent) result.put(doctor.getStaffId(), doctor);
        }

        // Không âm thầm fallback sang toàn bộ bác sĩ biên chế khi ngày đó chưa có lịch phân ca (StaffSchedule).
        // Nếu branchSchedules rỗng hoặc không có bác sĩ nào có ca trực hợp lệ trong buổi, trả về danh sách rỗng.
        return result.values().stream().sorted(Comparator.comparing(StaffProfile::getFullName)).toList();
    }

    /** Xác định nhân sự được phân công tại chi nhánh có phải là bác sĩ lâm sàng hay không. */
    private boolean isDoctorAssignment(StaffBranchAssignment assignment, StaffProfile staff) {
        String assignmentRole = null;
        if (assignment.getRole() != null && Boolean.TRUE.equals(assignment.getRole().getIsActive())
                && assignment.getRole().getRoleName() != null) {
            assignmentRole = assignment.getRole().getRoleName().trim().toUpperCase(Locale.ROOT);
        }
        if (assignmentRole != null) {
            if (assignmentRole.contains("LE_TAN") || assignmentRole.contains("KE_TOAN")
                    || assignmentRole.contains("QUAN_LY_KHO") || assignmentRole.contains("THU_KHO")
                    || "KHO".equals(assignmentRole) || assignmentRole.contains("ADMIN")
                    || assignmentRole.contains("PHU_TA") || assignmentRole.contains("BAO_VE")
                    || assignmentRole.contains("THU_NGAN")) {
                return false;
            }
            if (assignmentRole.contains("BAC_SI") || assignmentRole.contains("DOCTOR")
                    || assignmentRole.contains("BÁC SĨ")) {
                return true;
            }
        }

        boolean isDoctor = false;
        if (staff.getUser() != null && staff.getUser().getRoles() != null) {
            for (Role r : staff.getUser().getRoles()) {
                if (Boolean.TRUE.equals(r.getIsActive()) && r.getRoleName() != null) {
                    String rUpper = r.getRoleName().trim().toUpperCase(Locale.ROOT);
                    if (rUpper.contains("BAC_SI") || rUpper.contains("DOCTOR") || rUpper.contains("BÁC SĨ")) {
                        isDoctor = true;
                        break;
                    }
                }
            }
        }
        if (!isDoctor && staff.getPositionTitle() != null) {
            String pos = staff.getPositionTitle().toLowerCase(Locale.ROOT);
            if (pos.contains("bác sĩ") || pos.contains("doctor") || pos.contains("nha sĩ")) {
                isDoctor = true;
            }
        }
        if (!isDoctor && staff.getFullName() != null) {
            String nameUpper = staff.getFullName().toUpperCase(Locale.ROOT);
            if (nameUpper.startsWith("BS") || nameUpper.startsWith("THS. BS") || nameUpper.startsWith("THS.BS")
                    || nameUpper.startsWith("TS. BS") || nameUpper.startsWith("TS.BS")
                    || nameUpper.startsWith("BÁC SĨ")) {
                isDoctor = true;
            }
        }
        // Chỉ loại trừ từ "kho" đứng riêng (thủ kho, quản lý kho), không loại nhầm "nha khoa" / "chuyên khoa" / "trưởng khoa".
        String titleLower = ((staff.getPositionTitle() != null ? staff.getPositionTitle() : "")
                + " " + (staff.getSpecialty() != null ? staff.getSpecialty() : "")).toLowerCase(Locale.ROOT);
        if (titleLower.contains("lễ tân") || titleLower.contains("phụ tá")
                || titleLower.contains("kế toán") || titleLower.contains("bảo vệ")
                || WAREHOUSE_WORD_PATTERN.matcher(titleLower).find()) {
            isDoctor = false;
        }
        return isDoctor;
    }

    @Override
    public SessionRange sessionRange(Branch branch, PreferredSession session) {
        if (branch == null || session == null || session == PreferredSession.ANY) {
            return new SessionRange(LocalTime.MIN, LocalTime.MIN, false);
        }
        LocalTime start = null;
        LocalTime end = null;
        boolean active = true;
        boolean decidedByWindow = false;

        // 1. Ưu tiên 1: Cấu hình Ca khám chuyên biệt (appointment_session_windows) của chi nhánh
        if (windows != null && branch.getBranchId() != null) {
            var winOpt = windows.findByBranch_BranchIdAndPreferredSession(branch.getBranchId(), session);
            if (winOpt.isPresent()) {
                var w = winOpt.get();
                start = w.getStartTime();
                end = w.getEndTime();
                active = Boolean.TRUE.equals(w.getIsActive());
                decidedByWindow = true;
                if (!active) {
                    return new SessionRange(start != null ? start : LocalTime.MIN,
                            end != null ? end : LocalTime.MIN, false);
                }
            }
        }

        // 2. Ưu tiên 2 (Fallback): Kiểm tra cấu hình ca làm việc (work_shifts) thực tế của chi nhánh
        if (!decidedByWindow && workShifts != null && branch.getBranchId() != null) {
            try {
                List<WorkShift> branchActiveShifts = workShifts.findByBranchBranchIdAndIsActiveTrueOrderByStartTimeAsc(branch.getBranchId());
                long totalBranchShifts = workShifts.countByBranchBranchId(branch.getBranchId());
                boolean hasBranchShiftConfig = !branchActiveShifts.isEmpty() || totalBranchShifts > 0;
                List<WorkShift> shifts = !branchActiveShifts.isEmpty()
                        ? branchActiveShifts
                        : (!hasBranchShiftConfig ? workShifts.findByBranchIsNullAndIsActiveTrueOrderByStartTimeAsc() : List.of());

                boolean matched = false;
                for (WorkShift ws : shifts) {
                    if (Boolean.TRUE.equals(ws.getIsActive()) && !isInternalDutyShift(ws)
                            && mapShiftToSession(ws) == session
                            && ws.getStartTime() != null && ws.getEndTime() != null) {
                        start = ws.getStartTime();
                        end = ws.getEndTime();
                        active = true;
                        matched = true;
                        break;
                    }
                }
                if (!matched && hasBranchShiftConfig) {
                    active = false;
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback theo khung giờ mặc định nếu chưa cấu hình
        if (start == null || end == null) {
            switch (session) {
                case MORNING -> { start = LocalTime.of(8, 0); end = LocalTime.NOON; }
                case AFTERNOON -> { start = LocalTime.of(13, 30); end = LocalTime.of(17, 30); }
                case EVENING -> {
                    start = LocalTime.of(18, 0);
                    end = (branch.getClosingTime() != null && branch.getClosingTime().isAfter(start))
                            ? branch.getClosingTime() : LocalTime.of(20, 0);
                }
                default -> { start = LocalTime.MAX; end = LocalTime.MIN; active = false; }
            }
        }

        // 4. Tuân thủ nghiêm ngặt giờ mở cửa và giờ đóng cửa của chi nhánh
        if (branch.getClosingTime() != null && end != null && branch.getClosingTime().isBefore(end)) {
            end = branch.getClosingTime();
        }
        if (branch.getOpeningTime() != null && start != null && branch.getOpeningTime().isAfter(start)) {
            start = branch.getOpeningTime();
        }

        boolean valid = active && start != null && end != null && start.isBefore(end);
        return new SessionRange(start, end, valid);
    }

    private boolean isInternalDutyShift(WorkShift shift) {
        if (shift == null || shift.getStartTime() == null || shift.getEndTime() == null
                || !shift.getEndTime().isAfter(shift.getStartTime())) {
            return true;
        }
        long mins = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes();
        if (mins > WorkShiftService.MAX_SHIFT_MINUTES) {
            return true;
        }
        String code = shift.getShiftCode() != null ? shift.getShiftCode().toUpperCase(Locale.ROOT) : "";
        return code.contains("ALL") || code.contains("FULL") || code.contains("DEMO") || code.contains("SEED");
    }

    private PreferredSession mapShiftToSession(WorkShift shift) {
        if (isInternalDutyShift(shift)) return null;
        long mins = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes();
        if (mins <= 0 || mins > WorkShiftService.MAX_BOOKING_WINDOW_MINUTES) {
            return null;
        }
        String name = (shift.getShiftName() != null ? shift.getShiftName() : "").toUpperCase(Locale.ROOT);
        String code = (shift.getShiftCode() != null ? shift.getShiftCode() : "").toUpperCase(Locale.ROOT);
        if (code.contains("HC") || code.contains("HANH_CHINH")
                || name.contains("CẢ NGÀY") || name.contains("CA NGAY")
                || name.contains("HÀNH CHÍNH") || name.contains("HANH CHINH")) {
            return null;
        }
        if (code.contains("AM") || code.contains("SANG") || name.contains("SÁNG") || name.contains("SANG") || name.contains("MORNING")) {
            return PreferredSession.MORNING;
        }
        if (code.contains("PM") || code.contains("CHIEU") || name.contains("CHIỀU") || name.contains("CHIEU") || name.contains("AFTERNOON")) {
            return PreferredSession.AFTERNOON;
        }
        if (code.contains("EV") || code.contains("TOI") || name.contains("TỐI") || name.contains("TOI") || name.contains("EVENING") || name.contains("NIGHT")) {
            return PreferredSession.EVENING;
        }
        LocalTime s = shift.getStartTime();
        if (s != null) {
            if (s.isBefore(LocalTime.NOON)) return PreferredSession.MORNING;
            if (s.isBefore(LocalTime.of(17, 30))) return PreferredSession.AFTERNOON;
            return PreferredSession.EVENING;
        }
        return null;
    }

    @Override
    public List<SessionWindowInfo> sessionWindows(Branch branch) {
        if (branch == null) return List.of();
        List<SessionWindowInfo> list = new ArrayList<>();
        for (PreferredSession sess : List.of(PreferredSession.MORNING, PreferredSession.AFTERNOON, PreferredSession.EVENING)) {
            SessionRange range = sessionRange(branch, sess);
            String name = switch (sess) {
                case MORNING -> "Ca Sáng";
                case AFTERNOON -> "Ca Chiều";
                case EVENING -> "Ca Tối";
                default -> sess.name();
            };
            String startStr = range.start() != null ? range.start().toString().substring(0, 5) : "--:--";
            String endStr = range.end() != null ? range.end().toString().substring(0, 5) : "--:--";
            String label = name + " (" + startStr + " – " + endStr + ")";
            list.add(new SessionWindowInfo(sess, name, range.start(), range.end(), label, range.active()));
        }
        return list;
    }

    private AppointmentSessionPolicy policy(Long branchId, LocalDate date, PreferredSession session) {
        return policies.findByBranch_BranchIdAndAppointmentDateAndPreferredSession(branchId, date, session)
                .orElse(null);
    }

    private int branchCapacity(AppointmentSessionPolicy policy) {
        return policy == null ? defaultBranchCapacity : policy.getBranchCapacity();
    }

    private int doctorCapacity(AppointmentSessionPolicy policy) {
        return policy == null ? defaultDoctorCapacity : policy.getDoctorCapacity();
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
