package com.cvdcms.controller;

import com.cvdcms.DTO.PatientSearchResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.QueueStatus;
import com.cvdcms.entity.enums.QueueType;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.entity.enums.VisitType;
import com.cvdcms.repository.*;
import jakarta.servlet.http.HttpSession;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/doctor/patients")
@RequiredArgsConstructor
public class DoctorPatientRestController {

    private final PatientProfileRepository patientProfileRepository;
    private final VisitRepository visitRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final UserRepository userRepository;
    private final CustomerCareTaskRepository customerCareTaskRepository;
    private final ClinicalExaminationBootstrapService examinations;
    private final ClinicalQueueTicketRepository clinicalQueueTicketRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.DoctorCoverGrantRepository doctorCoverGrantRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.UrlIdCodec urlIdCodec = new com.cvdcms.security.UrlIdCodec();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ClinicQueueCoordinatorService queueCoordinator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.OverdueVisitCleanupService overdueVisitCleanupService;

    private String encodeVisit(Long visitId) {
        return urlIdCodec != null && visitId != null ? urlIdCodec.encodeVisit(visitId) : "";
    }

    private String encodePatient(Long profileId) {
        return urlIdCodec != null && profileId != null ? urlIdCodec.encodePatient(profileId) : "";
    }

    public void setDoctorCoverGrantRepository(com.cvdcms.repository.DoctorCoverGrantRepository doctorCoverGrantRepository) {
        this.doctorCoverGrantRepository = doctorCoverGrantRepository;
    }

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final List<VisitStatus> ACTIVE_STATUSES = List.of(
            VisitStatus.IN_EXAM,
            VisitStatus.WAITING,
            VisitStatus.IN_TREATMENT,
            VisitStatus.WAITING_PARACLINICAL,
            VisitStatus.WAITING_RESULT,
            VisitStatus.WAITING_PAYMENT
    );

    private User getLoggedInUser(HttpSession session) {
        if (session == null) return null;
        Object principal = session.getAttribute("loggedInUser");
        if (principal instanceof User user && user.getUserId() != null) {
            return userRepository.findById(user.getUserId()).orElse(null);
        }
        return null;
    }

    private Branch getDoctorAssignedBranch(User user) {
        if (user == null) return null;
        if (user.getBranch() != null) {
            return user.getBranch();
        }
        List<Long> activeBranchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(user.getUserId(), AssignmentStatus.ACTIVE);
        if (activeBranchIds != null && !activeBranchIds.isEmpty()) {
            return branchRepository.findById(activeBranchIds.get(0)).orElse(null);
        }
        List<Long> explicitIds = customerCareTaskRepository.findExplicitBranchIdsByUserId(user.getUserId());
        if (explicitIds != null && !explicitIds.isEmpty()) {
            return branchRepository.findById(explicitIds.get(0)).orElse(null);
        }
        return null;
    }

    @GetMapping("/search")
    @Transactional(readOnly = true)
    public ResponseEntity<List<PatientSearchResponseDTO>> searchPatients(
            @RequestParam(value = "q", required = false) String query,
            HttpSession session) {
        User currentUser = doctorVisitAccess != null
                ? doctorVisitAccess.requireAuthenticatedUser(session)
                : getLoggedInUser(session);
        java.util.Set<Long> allowedBranches = (doctorVisitAccess != null && currentUser != null)
                ? doctorVisitAccess.resolveAccessibleBranchIds(currentUser)
                : null;

        List<PatientProfile> patients;
        if (query == null || query.trim().isEmpty()) {
            patients = patientProfileRepository.findTop15ByOrderByProfileIdDesc();
        } else {
            patients = patientProfileRepository.searchPatients(query.trim());
        }

        if (allowedBranches != null) {
            patients = patients.stream()
                    .filter(p -> {
                        if (p.getPreferredBranch() != null && allowedBranches.contains(p.getPreferredBranch().getBranchId())) {
                            return true;
                        }
                        return visitRepository.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(p.getProfileId()).stream()
                                .anyMatch(v -> v.getBranch() != null && allowedBranches.contains(v.getBranch().getBranchId()));
                    })
                    .toList();
        }

        List<PatientSearchResponseDTO> results = patients.stream()
                .map(this::mapToSearchDTO)
                .collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }

    @PostMapping("/{profileId}/open-visit")
    @Transactional
    public ResponseEntity<Map<String, Object>> openOrCreateVisitForPatient(
            @PathVariable Long profileId,
            @RequestParam(value = "branchId", required = false) Long branchId,
            HttpSession session) {
        if (doctorVisitAccess != null) {
            doctorVisitAccess.requirePatientWrite(session, profileId);
        }
        PatientProfile patient = patientProfileRepository.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân phù hợp."));

        User currentUser = doctorVisitAccess != null
                ? doctorVisitAccess.requireAuthenticatedUser(session)
                : getLoggedInUser(session);
        boolean isAdmin = currentUser != null && userRepository.hasActiveAdminRole(currentUser.getUserId());
        Branch assignedBranch = !isAdmin ? getDoctorAssignedBranch(currentUser) : null;
        if (!isAdmin && assignedBranch != null) {
            if (branchId != null && !branchId.equals(assignedBranch.getBranchId())) {
                String fullBranchName = (assignedBranch.getBranchCode() != null ? assignedBranch.getBranchCode() + " - " : "") + assignedBranch.getBranchName();
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "message", "Cảnh báo: Bác sĩ chỉ được phép tiếp nhận ca khám tại cơ sở đang làm việc (" + fullBranchName + ")!"
                ));
            }
            branchId = assignedBranch.getBranchId();
        }

        // Đóng các ca quá hạn từ ngày trước của bệnh nhân trước khi kiểm tra ca đang chờ/hoạt động
        LocalDateTime nowClinic = LocalDateTime.now(com.cvdcms.service.ClinicQueueCoordinatorService.CLINIC_ZONE);
        if (overdueVisitCleanupService != null) {
            overdueVisitCleanupService.closeOverdueVisitsForPatient(profileId, nowClinic);
        }
        LocalDateTime startOfToday = nowClinic.toLocalDate().atStartOfDay();

        // 0. Chặn mở/tạo ca bỏ qua hàng chờ nếu bệnh nhân đang ở trạng thái Chờ khám / Chờ kết quả
        Optional<Visit> waitingVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdAndStatusIn(
                profileId, List.of(VisitStatus.WAITING, VisitStatus.WAITING_RESULT))
                .filter(v -> v.getCheckInAt() == null || !v.getCheckInAt().isBefore(startOfToday));
        if (waitingVisitOpt.isPresent()) {
            Visit wv = waitingVisitOpt.get();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "Bệnh nhân đang nằm trong hàng chờ khám (" + wv.getVisitCode()
                            + "). Không thể mở khám trực tiếp bỏ qua hàng chờ; vui lòng đợi hệ thống gọi số và bấm nút 'Vào khám'."
            ));
        }

        // 1. Kiểm tra xem bệnh nhân có ca khám nào đang hoạt động tại cơ sở hợp lệ chưa
        Optional<Visit> activeVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdAndStatusIn(profileId, ACTIVE_STATUSES)
                .filter(v -> v.getStatus() == VisitStatus.WAITING_PAYMENT
                        || v.getCheckInAt() == null
                        || !v.getCheckInAt().isBefore(startOfToday));
        if (activeVisitOpt.isPresent()) {
            Visit activeVisit = activeVisitOpt.get();
            Long activeBranchId = activeVisit.getBranch() != null ? activeVisit.getBranch().getBranchId() : null;
            if (isAdmin || assignedBranch == null || (activeBranchId != null && activeBranchId.equals(assignedBranch.getBranchId()))) {
                StaffProfile actingDoctor = (!isAdmin && currentUser != null)
                        ? staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null)
                        : null;
                boolean canAccessVisit = isAdmin || (actingDoctor != null && (
                        (activeVisit.getPrimaryDoctor() != null && actingDoctor.getStaffId().equals(activeVisit.getPrimaryDoctor().getStaffId()))
                        || (doctorCoverGrantRepository != null && doctorCoverGrantRepository.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                                activeVisit.getVisitId(), actingDoctor.getStaffId(), LocalDateTime.now()))
                ));
                if (!canAccessVisit) {
                    String ownerName = activeVisit.getPrimaryDoctor() != null && activeVisit.getPrimaryDoctor().getFullName() != null
                            ? activeVisit.getPrimaryDoctor().getFullName()
                            : "bác sĩ khác";
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                            "message", "Bệnh nhân đang có ca khám hoạt động (" + activeVisit.getVisitCode() + ") do " + ownerName + " phụ trách và bạn chưa được ủy quyền khám thay."
                    ));
                }
                if (activeVisit.getStatus() == VisitStatus.WAITING || activeVisit.getStatus() == VisitStatus.WAITING_RESULT) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                            "message", "Bệnh nhân đang nằm trong hàng chờ khám (" + activeVisit.getVisitCode()
                                    + "). Vui lòng chờ hệ thống gọi số và bấm nút 'Vào khám'."
                    ));
                }
                String visitToken = encodeVisit(activeVisit.getVisitId());
                return ResponseEntity.ok(Map.of(
                        "visitId", activeVisit.getVisitId(),
                        "visitToken", visitToken,
                        "visitCode", activeVisit.getVisitCode(),
                        "isNew", false,
                        "redirectUrl", "/doctor/examination/" + visitToken + "/diagnoses"
                ));
            }
        }

        // 2. Nếu chưa có ca hoạt động, tạo mới ca khám cho hôm nay
        Visit newVisit = new Visit();
        newVisit.setPatientProfile(patient);
        LocalDateTime nowStart = LocalDateTime.now();
        newVisit.setCheckInAt(nowStart);
        newVisit.setActualStartTime(nowStart);
        newVisit.setStatus(VisitStatus.IN_EXAM);
        newVisit.setVisitType(VisitType.WALK_IN);
        newVisit.setInitialSymptoms("Khám và điều trị tại ghế theo yêu cầu");

        // Gán cơ sở
        Branch branch = branchId != null ? branchRepository.findById(branchId).orElse(null) : null;
        if (branch == null && assignedBranch != null) {
            branch = assignedBranch;
        }
        if (branch == null) {
            branch = patient.getPreferredBranch();
        }
        if (branch == null) {
            branch = branchRepository.findAll().stream().findFirst().orElse(null);
        }
        newVisit.setBranch(branch);

        // Gán bác sĩ đang đăng nhập nếu có
        StaffProfile doctor = null;
        if (currentUser != null) {
            doctor = staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null);
        }
        if (doctor == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Chỉ tài khoản bác sĩ đã liên kết hồ sơ nhân sự (StaffProfile) mới được mở hoặc tiếp nhận ca khám.");
        }
        newVisit.setPrimaryDoctor(doctor);

        long visitCount = visitRepository.count() + 1;
        newVisit.setVisitCode("VIS" + (10000 + visitCount));

        newVisit = visitRepository.save(newVisit);
        examinations.ensureForAssignedVisit(newVisit.getVisitId());
        ensureQueueTicketForVisit(newVisit);

        String newVisitToken = encodeVisit(newVisit.getVisitId());
        return ResponseEntity.ok(Map.of(
                "visitId", newVisit.getVisitId(),
                "visitToken", newVisitToken,
                "visitCode", newVisit.getVisitCode(),
                "isNew", true,
                "redirectUrl", "/doctor/examination/" + newVisitToken + "/diagnoses"
        ));
    }

    private PatientSearchResponseDTO mapToSearchDTO(PatientProfile p) {
        Integer age = null;
        String dobStr = null;
        if (p.getDob() != null) {
            age = Period.between(p.getDob(), LocalDate.now()).getYears();
            dobStr = p.getDob().format(DATE_FORMAT);
        }

        // Tìm ca khám gần nhất hoặc ca đang hoạt động (bỏ qua ca lâm sàng đã quá hạn từ ngày trước)
        LocalDateTime startOfToday = LocalDate.now(com.cvdcms.service.ClinicQueueCoordinatorService.CLINIC_ZONE).atStartOfDay();
        Optional<Visit> activeVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdAndStatusIn(p.getProfileId(), ACTIVE_STATUSES)
                .filter(v -> v.getStatus() == VisitStatus.WAITING_PAYMENT
                        || v.getCheckInAt() == null
                        || !v.getCheckInAt().isBefore(startOfToday));
        Optional<Visit> latestVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdOrderByCheckInAtDesc(p.getProfileId());

        Long vId = null;
        String vCode = null;
        String vStatus = null;
        String vStatusDisplay = "Chưa có ca khám";
        String vDoctor = null;
        String vDate = null;
        boolean hasActive = false;

        if (activeVisitOpt.isPresent()) {
            Visit av = activeVisitOpt.get();
            vId = av.getVisitId();
            vCode = av.getVisitCode();
            vStatus = av.getStatus().name();
            vStatusDisplay = "Đang khám: " + (vCode != null ? vCode : "Ca khám");
            hasActive = true;
            if (av.getPrimaryDoctor() != null) vDoctor = av.getPrimaryDoctor().getFullName();
            if (av.getCheckInAt() != null) vDate = av.getCheckInAt().format(DT_FORMAT);
        } else if (latestVisitOpt.isPresent()) {
            Visit lv = latestVisitOpt.get();
            vId = lv.getVisitId();
            vCode = lv.getVisitCode();
            vStatus = lv.getStatus().name();
            String time = lv.getCheckInAt() != null ? lv.getCheckInAt().format(DATE_FORMAT) : "";
            vStatusDisplay = "Ca gần nhất: " + (vCode != null ? vCode : "Ca khám") + (time.isEmpty() ? "" : " (" + time + ")");
            if (lv.getPrimaryDoctor() != null) vDoctor = lv.getPrimaryDoctor().getFullName();
            if (lv.getCheckInAt() != null) vDate = lv.getCheckInAt().format(DT_FORMAT);
        }

        return PatientSearchResponseDTO.builder()
                .profileId(p.getProfileId())
                .patientToken(encodePatient(p.getProfileId()))
                .patientCode(p.getPatientCode())
                .fullName(p.getFullName())
                .phoneNumber(p.getPhoneNumber())
                .gender(p.getGender())
                .dob(dobStr)
                .age(age)
                .address(p.getAddress())
                .avatarUrl(p.getAvatarUrl())
                .latestVisitId(vId)
                .latestVisitToken(vId != null ? encodeVisit(vId) : null)
                .latestVisitCode(vCode)
                .latestVisitStatus(vStatus)
                .latestVisitStatusDisplay(vStatusDisplay)
                .latestVisitDoctor(vDoctor)
                .latestVisitDate(vDate)
                .hasActiveVisit(hasActive)
                .build();
    }

    @GetMapping("/daily-visits")
    @Transactional(readOnly = true)
    public ResponseEntity<List<com.cvdcms.DTO.DailyVisitDTO>> getDailyVisits(
            @RequestParam(value = "date", required = false) String datePreset,
            @RequestParam(value = "startDate", required = false) String startDateStr,
            @RequestParam(value = "endDate", required = false) String endDateStr,
            @RequestParam(value = "status", required = false) String statusStr,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "currentVisitId", required = false) Long currentVisitId,
            @RequestParam(value = "branchId", required = false) Long branchId,
            @RequestParam(value = "waitingOnly", required = false, defaultValue = "false") boolean waitingOnly,
            HttpSession session) {

        User currentUser = doctorVisitAccess != null
                ? doctorVisitAccess.requireAuthenticatedUser(session)
                : getLoggedInUser(session);
        if (currentUser == null || currentUser.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        boolean isAdmin = doctorVisitAccess != null
                ? doctorVisitAccess.isAdmin(currentUser)
                : userRepository.hasActiveAdminRole(currentUser.getUserId());
        StaffProfile currentDoctor = !isAdmin
                ? staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null)
                : null;
        Set<Long> allowedBranches = null;
        if (!isAdmin) {
            Set<Long> resolved = doctorVisitAccess != null
                    ? doctorVisitAccess.resolveAccessibleBranchIds(currentUser)
                    : Collections.emptySet();
            allowedBranches = new LinkedHashSet<>(resolved != null ? resolved : Collections.emptySet());
            if (allowedBranches.isEmpty()) {
                Branch assignedBranch = getDoctorAssignedBranch(currentUser);
                if (assignedBranch != null && assignedBranch.getBranchId() != null) {
                    allowedBranches.add(assignedBranch.getBranchId());
                }
            }
            if (allowedBranches.isEmpty()) {
                return ResponseEntity.ok(Collections.emptyList());
            }
            if (branchId != null) {
                if (!allowedBranches.contains(branchId)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập dữ liệu của chi nhánh khác.");
                }
            } else if (allowedBranches.size() == 1) {
                branchId = allowedBranches.iterator().next();
            }
        }

        LocalDateTime start = null;
        LocalDateTime end = null;
        boolean hasDateFilter = false;

        LocalDate today = LocalDate.now();

        if (datePreset != null && !datePreset.trim().isEmpty() && !datePreset.equalsIgnoreCase("ALL")) {
            hasDateFilter = true;
            switch (datePreset.toUpperCase()) {
                case "TODAY":
                    start = today.atStartOfDay();
                    end = today.atTime(23, 59, 59);
                    break;
                case "YESTERDAY":
                    LocalDate yesterday = today.minusDays(1);
                    start = yesterday.atStartOfDay();
                    end = yesterday.atTime(23, 59, 59);
                    break;
                case "THIS_WEEK":
                    LocalDate startOfWeek = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                    start = startOfWeek.atStartOfDay();
                    end = today.atTime(23, 59, 59);
                    break;
                case "THIS_MONTH":
                    LocalDate startOfMonth = today.withDayOfMonth(1);
                    start = startOfMonth.atStartOfDay();
                    end = today.atTime(23, 59, 59);
                    break;
            }
        } else if (startDateStr != null && !startDateStr.trim().isEmpty()) {
            hasDateFilter = true;
            try {
                LocalDate sDate = LocalDate.parse(startDateStr.trim());
                start = sDate.atStartOfDay();
                if (endDateStr != null && !endDateStr.trim().isEmpty()) {
                    LocalDate eDate = LocalDate.parse(endDateStr.trim());
                    end = eDate.atTime(23, 59, 59);
                } else {
                    end = sDate.atTime(23, 59, 59);
                }
            } catch (Exception e) {
                log.warn("Invalid date range params: {} -> {}", startDateStr, endDateStr);
            }
        }

        VisitStatus status = null;
        boolean waitingQueueFilter = waitingOnly;
        if (statusStr != null && !statusStr.trim().isEmpty() && !statusStr.equalsIgnoreCase("ALL")) {
            if ("WAITING_QUEUE".equalsIgnoreCase(statusStr.trim())) {
                waitingQueueFilter = true;
            } else {
                try {
                    status = VisitStatus.valueOf(statusStr.trim().toUpperCase());
                } catch (Exception e) {
                    log.warn("Invalid status param: {}", statusStr);
                }
            }
        }

        String searchPattern = (search != null && !search.trim().isEmpty()) ? search.trim() : null;

        List<Visit> visits = visitRepository.findFilteredVisits(start, end, status, branchId, searchPattern);

        // Fallback: chỉ áp dụng khi không bật chế độ chỉ lấy hàng chờ khám
        if (!waitingQueueFilter && visits.isEmpty() && !hasDateFilter && (searchPattern == null || searchPattern.isEmpty()) && (status == null)) {
            final Long filterBranchId = branchId;
            visits = visitRepository.findAll().stream()
                    .filter(v -> filterBranchId == null || (v.getBranch() != null && filterBranchId.equals(v.getBranch().getBranchId())))
                    .filter(v -> v.getStatus() != VisitStatus.CANCELLED)
                    .sorted((a, b) -> b.getCheckInAt().compareTo(a.getCheckInAt()))
                    .limit(20)
                    .collect(Collectors.toList());
        }

        if (waitingQueueFilter) {
            visits = visits.stream()
                    .filter(v -> v.getStatus() == VisitStatus.WAITING || v.getStatus() == VisitStatus.WAITING_RESULT)
                    .collect(Collectors.toList());
        }

        if (allowedBranches != null) {
            final Set<Long> branchFilter = allowedBranches;
            visits = visits.stream()
                    .filter(v -> v.getBranch() != null && branchFilter.contains(v.getBranch().getBranchId()))
                    .collect(Collectors.toList());
        }

        // LỌC CỨNG (Cách 1): Bác sĩ chỉ thấy danh sách ca khám thuộc về mình hoặc được ủy quyền khám thay
        final Set<Long> coveredIds;
        if (currentDoctor != null) {
            final Long docStaffId = currentDoctor.getStaffId();
            coveredIds = doctorCoverGrantRepository != null
                    ? doctorCoverGrantRepository.findActiveCoveredVisitIds(docStaffId, LocalDateTime.now())
                    : Collections.emptySet();
            visits = visits.stream()
                    .filter(v -> (v.getPrimaryDoctor() != null && docStaffId.equals(v.getPrimaryDoctor().getStaffId()))
                            || coveredIds.contains(v.getVisitId()))
                    .collect(Collectors.toList());
        } else {
            coveredIds = Collections.emptySet();
        }

        Map<Long, ResolvedTurnInfo> turnMap = resolveDailyTurnNumbers(visits, branchId);

        final Long currentDocStaffId = currentDoctor != null ? currentDoctor.getStaffId() : null;
        Map<Long, Visit> visitMap = new HashMap<>();
        Map<Long, LocalDateTime> checkInMap = new HashMap<>();
        for (Visit v : visits) {
            if (v.getVisitId() != null) {
                visitMap.put(v.getVisitId(), v);
                checkInMap.put(v.getVisitId(), v.getCheckInAt());
            }
        }
        List<com.cvdcms.DTO.DailyVisitDTO> result = visits.stream()
                .map(v -> {
                    boolean isPrimary = currentDocStaffId != null
                            && v.getPrimaryDoctor() != null
                            && currentDocStaffId.equals(v.getPrimaryDoctor().getStaffId());
                    boolean isCovered = currentDocStaffId != null
                            && !isPrimary
                            && coveredIds.contains(v.getVisitId());
                    boolean isAccessible = isAdmin || currentDocStaffId == null || isPrimary || isCovered;
                    return mapToDailyVisitDTO(v, currentVisitId, turnMap.get(v.getVisitId()), isCovered, isAccessible);
                })
                .sorted((d1, d2) -> {
                    int g1 = getDailyVisitDeskGroup(d1);
                    int g2 = getDailyVisitDeskGroup(d2);
                    if (g1 != g2) return Integer.compare(g1, g2);

                    if (g1 == 1) {
                        // Đang gọi: so sánh calledAt tăng dần rồi visitId
                        LocalDateTime c1 = turnMap.get(d1.getVisitId()) != null ? turnMap.get(d1.getVisitId()).calledAt() : null;
                        LocalDateTime c2 = turnMap.get(d2.getVisitId()) != null ? turnMap.get(d2.getVisitId()).calledAt() : null;
                        if (c1 != null && c2 != null) {
                            int cmp = c1.compareTo(c2);
                            if (cmp != 0) return cmp;
                        }
                    }

                    if (g1 <= 3) {
                        Visit v1 = visitMap.get(d1.getVisitId());
                        Visit v2 = visitMap.get(d2.getVisitId());
                        QueueTicket t1 = turnMap.get(d1.getVisitId()) != null ? turnMap.get(d1.getVisitId()).ticket() : null;
                        QueueTicket t2 = turnMap.get(d2.getVisitId()) != null ? turnMap.get(d2.getVisitId()).ticket() : null;
                        if (queueCoordinator != null) {
                            return queueCoordinator.compareVisitsForWaitingOrder(v1, t1, v2, t2);
                        }
                    }

                    LocalDateTime ci1 = checkInMap.get(d1.getVisitId());
                    LocalDateTime ci2 = checkInMap.get(d2.getVisitId());
                    if (ci1 != null && ci2 != null) {
                        int cmp = g1 >= 4 ? ci2.compareTo(ci1) : ci1.compareTo(ci2);
                        if (cmp != 0) return cmp;
                    }
                    Long id1 = d1.getVisitId() != null ? d1.getVisitId() : Long.MAX_VALUE;
                    Long id2 = d2.getVisitId() != null ? d2.getVisitId() : Long.MAX_VALUE;
                    return Long.compare(id1, id2);
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    private int getDailyVisitDeskGroup(com.cvdcms.DTO.DailyVisitDTO d) {
        String st = d.getStatus();
        String qs = d.getQueueStatus();
        if ("IN_EXAM".equals(st) || "IN_TREATMENT".equals(st)) return 0;
        if ("CALLED".equals(qs) && ("WAITING".equals(st) || "WAITING_RESULT".equals(st))) return 1;
        if (("WAITING".equals(st) || "WAITING_RESULT".equals(st)) && !"PAUSED".equals(qs)) return 2;
        if ("PAUSED".equals(qs) || "WAITING_PARACLINICAL".equals(st)) return 3;
        if ("WAITING_PAYMENT".equals(st)) return 4;
        if ("COMPLETED".equals(st)) return 5;
        return 6;
    }

    /**
     * API hàng đợi 4 cấp ưu tiên chính thức của bác sĩ (WAITING tách riêng với WAITING_RESULT).
     * Chống IDOR: Bác sĩ chỉ xem được hàng đợi của chính mình.
     */
    @GetMapping("/waiting-queue")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getDoctorWaitingQueue(
            @RequestParam(value = "doctorId", required = false) Long requestedDoctorId,
            HttpSession session) {
        User currentUser = doctorVisitAccess != null
                ? doctorVisitAccess.requireAuthenticatedUser(session)
                : getLoggedInUser(session);
        if (currentUser == null || currentUser.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        boolean isAdmin = doctorVisitAccess != null
                ? doctorVisitAccess.isAdmin(currentUser)
                : userRepository.hasActiveAdminRole(currentUser.getUserId());
        StaffProfile currentDoctor = staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null);

        Long effectiveDoctorId;
        if (!isAdmin) {
            if (currentDoctor == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không phải bác sĩ.");
            }
            if (requestedDoctorId != null && !requestedDoctorId.equals(currentDoctor.getStaffId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập hàng đợi của bác sĩ khác.");
            }
            effectiveDoctorId = currentDoctor.getStaffId();
        } else {
            effectiveDoctorId = requestedDoctorId != null ? requestedDoctorId
                    : (currentDoctor != null ? currentDoctor.getStaffId() : null);
        }
        if (effectiveDoctorId == null) {
            return ResponseEntity.ok(Map.of("waitingQueue", List.of(), "waitingResultQueue", List.of()));
        }

        List<Visit> waitingVisits = visitRepository.findWaitingQueueByDoctorId(effectiveDoctorId);
        List<Visit> waitingResultVisits = visitRepository.findWaitingResultQueueByDoctorId(effectiveDoctorId);

        List<Visit> combined = new ArrayList<>(waitingVisits);
        combined.addAll(waitingResultVisits);
        Map<Long, ResolvedTurnInfo> turnMap = resolveDailyTurnNumbers(combined, null);

        List<com.cvdcms.DTO.DailyVisitDTO> waitingDtos = waitingVisits.stream()
                .map(v -> mapToDailyVisitDTO(v, null, turnMap.get(v.getVisitId()), false, true))
                .toList();
        List<com.cvdcms.DTO.DailyVisitDTO> waitingResultDtos = waitingResultVisits.stream()
                .map(v -> mapToDailyVisitDTO(v, null, turnMap.get(v.getVisitId()), false, true))
                .toList();

        return ResponseEntity.ok(Map.of(
                "doctorId", effectiveDoctorId,
                "waitingQueue", waitingDtos,
                "waitingResultQueue", waitingResultDtos
        ));
    }

    private int getVisitStatusSortRank(com.cvdcms.DTO.DailyVisitDTO d) {
        if (d == null || d.getStatus() == null) return 3;
        String s = d.getStatus().toUpperCase();
        if (s.equals("IN_EXAM") || s.equals("IN_TREATMENT") || s.equals("WAITING_PARACLINICAL") || s.equals("ORDERED")) {
            return 0; // Đang khám / Điều trị tại ghế
        }
        if (s.equals("WAITING_RESULT")) {
            return 1; // Tách riêng bệnh nhân quay lại đọc kết quả cận lâm sàng
        }
        if (s.equals("WAITING")) {
            return 2; // Hàng đợi mới theo 4 cấp ưu tiên
        }
        if (s.equals("WAITING_PAYMENT") || s.equals("COMPLETED")) {
            return 3; // Đã hoàn thành / Chờ thu ngân
        }
        if (s.equals("CANCELLED")) {
            return 4; // Đã hủy
        }
        return 3;
    }

    private record ResolvedTurnInfo(
            Long ticketId,
            Integer ticketNumber,
            String displayNumber,
            boolean isPriority,
            boolean isEmergency,
            String priorityReason,
            String queueStatus,
            LocalDateTime calledAt,
            QueueTicket ticket
    ) {}

    private Map<Long, ResolvedTurnInfo> resolveDailyTurnNumbers(List<Visit> visits, Long filterBranchId) {
        if (visits == null || visits.isEmpty()) {
            return Collections.emptyMap();
        }

        // Lấy đầy đủ toàn bộ ca khám của các ngày liên quan để STT lượt khám cố định 100%, không bị nhảy số khi lọc hoặc tìm kiếm
        LocalDate minDate = null;
        LocalDate maxDate = null;
        for (Visit v : visits) {
            if (v.getCheckInAt() != null) {
                LocalDate d = v.getCheckInAt().toLocalDate();
                if (minDate == null || d.isBefore(minDate)) minDate = d;
                if (maxDate == null || d.isAfter(maxDate)) maxDate = d;
            }
        }

        Map<Long, Visit> allDayVisitMap = new LinkedHashMap<>();
        if (minDate != null && maxDate != null) {
            try {
                List<Visit> fullRangeVisits = visitRepository.findFilteredVisits(
                        minDate.atStartOfDay(),
                        maxDate.atTime(23, 59, 59),
                        null,
                        filterBranchId,
                        null
                );
                if (fullRangeVisits != null) {
                    for (Visit fv : fullRangeVisits) {
                        if (fv.getVisitId() != null) {
                            allDayVisitMap.put(fv.getVisitId(), fv);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not load full day visits for turn resolution: {}", e.getMessage());
            }
        }
        for (Visit v : visits) {
            if (v.getVisitId() != null) {
                allDayVisitMap.putIfAbsent(v.getVisitId(), v);
            }
        }

        Map<Long, QueueTicket> ticketByVisitId = new HashMap<>();
        if (clinicalQueueTicketRepository != null && !allDayVisitMap.isEmpty()) {
            try {
                List<QueueTicket> existingTickets = clinicalQueueTicketRepository
                        .findByVisitVisitIdInAndQueueTypeOrderByTicketIdDesc(allDayVisitMap.keySet(), QueueType.EXAM);
                if (existingTickets != null) {
                    for (QueueTicket t : existingTickets) {
                        if (t.getVisit() == null || t.getVisit().getVisitId() == null) continue;
                        Long vid = t.getVisit().getVisitId();
                        QueueTicket prev = ticketByVisitId.get(vid);
                        if (prev == null || (prev.getStatus() == QueueStatus.CANCELLED && t.getStatus() != QueueStatus.CANCELLED)) {
                            ticketByVisitId.put(vid, t);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not query QueueTickets for daily visits: {}", e.getMessage());
            }
        }

        // Nhóm theo (branchId + ngày khám) để đánh số tăng dần từ 01 cho mỗi ngày tại từng cơ sở
        Map<String, List<Visit>> groupedByBranchAndDate = new LinkedHashMap<>();
        for (Visit v : allDayVisitMap.values()) {
            Long bId = v.getBranch() != null ? v.getBranch().getBranchId() : 0L;
            String dateKey = v.getCheckInAt() != null ? v.getCheckInAt().toLocalDate().toString() : "UNKNOWN";
            String groupKey = bId + "_" + dateKey;
            groupedByBranchAndDate.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(v);
        }

        Map<Long, ResolvedTurnInfo> resolvedMap = new HashMap<>();

        for (List<Visit> groupVisits : groupedByBranchAndDate.values()) {
            groupVisits.sort(Comparator
                    .comparing(Visit::getCheckInAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Visit::getVisitId, Comparator.nullsLast(Comparator.naturalOrder())));

            Set<Integer> usedNormal = new HashSet<>();
            Set<Integer> usedPriority = new HashSet<>();

            // Bước 1: Ghi nhận các ca đã có Số phiếu chờ chính thức từ Lễ tân / Điều phối (FT09 & FT10)
            for (Visit v : groupVisits) {
                QueueTicket t = ticketByVisitId.get(v.getVisitId());
                if (t != null && t.getDisplayNumber() != null && !t.getDisplayNumber().isBlank()) {
                    boolean isEmergency = Boolean.TRUE.equals(t.getIsEmergency()) || v.getVisitType() == VisitType.EMERGENCY;
                    boolean isPriority = Boolean.TRUE.equals(t.getIsPriority())
                            || (t.getPriorityScore() != null && t.getPriorityScore() >= 50)
                            || t.getDisplayNumber().startsWith("P-");
                    int num = t.getTicketNumber() != null ? t.getTicketNumber() : 1;
                    if (isPriority) {
                        usedPriority.add(num);
                    } else {
                        usedNormal.add(num);
                    }
                    resolvedMap.put(v.getVisitId(), new ResolvedTurnInfo(
                            t.getTicketId(),
                            num,
                            t.getDisplayNumber(),
                            isPriority,
                            isEmergency,
                            t.getPriorityReason(),
                            t.getStatus() != null ? t.getStatus().name() : null,
                            t.getCalledAt(),
                            t
                    ));
                }
            }

            // Bước 2: Tự động lấp đầy (Fallback) cho các ca chưa qua bước in phiếu để 100% ca khám đều có STT lượt khám hợp lệ
            int nextNormal = 1;
            int nextPriority = 1;

            for (Visit v : groupVisits) {
                if (resolvedMap.containsKey(v.getVisitId())) {
                    continue;
                }

                boolean isEmergency = Boolean.TRUE.equals(v.getIsEmergency()) || v.getVisitType() == VisitType.EMERGENCY;
                boolean isBookedOnTime = Boolean.TRUE.equals(v.getIsBookedAndOnTime())
                        && (v.getAppointmentId() != null || v.getVisitType() == VisitType.APPOINTMENT);
                boolean isReturningCls = v.getStatus() == VisitStatus.WAITING_RESULT
                        || v.getStatus() == VisitStatus.WAITING_PARACLINICAL;
                String reason = null;

                if (isEmergency) {
                    reason = (v.getInitialSymptoms() != null && !v.getInitialSymptoms().isBlank())
                            ? v.getInitialSymptoms()
                            : "Ca cấp cứu khẩn cấp";
                } else if (isReturningCls) {
                    reason = "Bệnh nhân quay lại sau cận lâm sàng chờ đọc kết quả";
                } else if (isBookedOnTime) {
                    reason = "Lịch hẹn check-in đúng giờ (<= 15 phút)";
                }

                boolean isPriority = isEmergency || isReturningCls || isBookedOnTime;
                if (isPriority) {
                    while (usedPriority.contains(nextPriority)) {
                        nextPriority++;
                    }
                    usedPriority.add(nextPriority);
                    String disp = "P-" + String.format("%02d", nextPriority);
                    resolvedMap.put(v.getVisitId(), new ResolvedTurnInfo(
                            null, nextPriority, disp, true, isEmergency, reason, null, null, null
                    ));
                } else {
                    while (usedNormal.contains(nextNormal)) {
                        nextNormal++;
                    }
                    usedNormal.add(nextNormal);
                    String disp = String.format("%02d", nextNormal);
                    resolvedMap.put(v.getVisitId(), new ResolvedTurnInfo(
                            null, nextNormal, disp, false, false, null, null, null, null
                    ));
                }
            }
        }

        return resolvedMap;
    }

    private QueueTicket ensureQueueTicketForVisit(Visit visit) {
        if (visit == null || visit.getVisitId() == null || visit.getBranch() == null || clinicalQueueTicketRepository == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lượt khám hoặc chi nhánh không hợp lệ để cấp vé hàng chờ.");
        }
        List<QueueTicket> existing = clinicalQueueTicketRepository.findByVisitVisitIdAndQueueType(visit.getVisitId(), QueueType.EXAM);
        if (existing != null && !existing.isEmpty()) {
            for (QueueTicket t : existing) {
                if (t.getStatus() != QueueStatus.CANCELLED) {
                    return t;
                }
            }
        }

        LocalDate day = visit.getCheckInAt() != null ? visit.getCheckInAt().toLocalDate() : LocalDate.now();
        boolean isEmergency = Boolean.TRUE.equals(visit.getIsEmergency()) || visit.getVisitType() == VisitType.EMERGENCY;
        boolean isBookedOnTime = Boolean.TRUE.equals(visit.getIsBookedAndOnTime())
                && (visit.getAppointmentId() != null || visit.getVisitType() == VisitType.APPOINTMENT);
        boolean isWalkIn = visit.getAppointmentId() == null && visit.getVisitType() != VisitType.APPOINTMENT;
        boolean isPriority = isEmergency || isBookedOnTime;
        String priorityReason = isEmergency
                ? (visit.getInitialSymptoms() != null && !visit.getInitialSymptoms().isBlank() ? visit.getInitialSymptoms() : "Ca cấp cứu khẩn cấp")
                : (isBookedOnTime ? "Lịch hẹn check-in đúng giờ" : null);

        // Khóa bi quan chi nhánh để đồng bộ tuần tự hóa cấp số
        branchRepository.findByIdForUpdate(visit.getBranch().getBranchId());

        List<QueueTicket> doubleCheck = clinicalQueueTicketRepository.findByVisitVisitIdAndQueueType(visit.getVisitId(), QueueType.EXAM);
        if (doubleCheck != null) {
            for (QueueTicket t : doubleCheck) {
                if (t.getStatus() != QueueStatus.CANCELLED) {
                    return t;
                }
            }
        }

        QueueTicket ticket = (existing != null && !existing.isEmpty() && existing.get(0).getStatus() == QueueStatus.CANCELLED)
                ? existing.get(0)
                : new QueueTicket();

        int next = clinicalQueueTicketRepository.maxTicketNumber(visit.getBranch().getBranchId(), day, QueueType.EXAM, isPriority) + 1;
        if (next > 999) {
            log.error("Đã hết dải số hàng chờ trong ngày cho chi nhánh {}", visit.getBranch().getBranchId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đã hết dải số hàng chờ trong ngày cho chi nhánh này.");
        }
        ticket.setVisit(visit);
        ticket.setBranch(visit.getBranch());
        ticket.setQueueDate(day);
        ticket.setQueueType(QueueType.EXAM);
        ticket.setTicketNumber(next);
        ticket.setDisplayNumber((isPriority ? "P-" : "") + String.format("%02d", next));

        ticket.setIsPriority(isPriority);
        ticket.setIsEmergency(isEmergency);
        ticket.setPriorityScore(isEmergency ? 100 : (isBookedOnTime ? 75 : (isWalkIn ? 50 : 25)));
        ticket.setPriorityReason(priorityReason);
        ticket.setStatus(visit.getStatus() == VisitStatus.IN_EXAM ? QueueStatus.IN_SERVICE : QueueStatus.WAITING);
        ticket.setIssuedAt(LocalDateTime.now());
        ticket.setCalledAt(null);
        ticket.setPausedAt(null);
        ticket.setResumedAt(null);
        ticket.setCompletedAt(null);
        return clinicalQueueTicketRepository.save(ticket);
    }

    private com.cvdcms.DTO.DailyVisitDTO mapToDailyVisitDTO(
            Visit v,
            Long currentVisitId,
            ResolvedTurnInfo turnInfo,
            boolean coveredByCurrentDoctor,
            boolean accessibleByCurrentDoctor) {
        PatientProfile p = v.getPatientProfile();

        String patientCode = p != null ? p.getPatientCode() : "N/A";
        String fullName = p != null ? p.getFullName() : "Khách vãng lai";
        String phone = p != null ? p.getPhoneNumber() : "";
        String gender = p != null ? p.getGender() : "Khác";

        String age = "";
        if (p != null && p.getDob() != null) {
            age = String.valueOf(Period.between(p.getDob(), LocalDate.now()).getYears());
        }

        String time = v.getCheckInAt() != null ? v.getCheckInAt().format(DateTimeFormatter.ofPattern("HH:mm")) : "--:--";
        String date = v.getCheckInAt() != null ? v.getCheckInAt().format(DATE_FORMAT) : "";
        String iso = v.getCheckInAt() != null ? v.getCheckInAt().toLocalDate().toString() : "";

        Long doctorId = v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getStaffId() : null;
        String doctorName = v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getFullName() : "BS trực";
        String branchName = v.getBranch() != null ? v.getBranch().getBranchName() : "";
        String chairName = v.getChairId() != null ? "Ghế " + v.getChairId() : "Ghế khám";

        String statusName = v.getStatus() != null ? v.getStatus().name() : "WAITING";
        String statusDisplay;
        String badgeClass;

        switch (statusName) {
            case "IN_EXAM":
                statusDisplay = "Đang khám";
                badgeClass = "bg-primary text-white";
                break;
            case "IN_TREATMENT":
                statusDisplay = "Đang điều trị";
                badgeClass = "bg-info text-dark";
                break;
            case "WAITING":
                statusDisplay = "Đang chờ";
                badgeClass = "bg-warning text-dark";
                break;
            case "WAITING_PARACLINICAL":
                statusDisplay = "Chờ CLS";
                badgeClass = "bg-secondary text-white";
                break;
            case "WAITING_RESULT":
                statusDisplay = "Chờ đọc KQ";
                badgeClass = "bg-warning text-dark";
                break;
            case "WAITING_PAYMENT":
                statusDisplay = "Chờ thanh toán";
                badgeClass = "bg-dark text-white";
                break;
            case "COMPLETED":
                statusDisplay = "Hoàn thành";
                badgeClass = "bg-success text-white";
                break;
            case "CANCELLED":
                statusDisplay = "Đã hủy";
                badgeClass = "bg-danger text-white";
                break;
            default:
                statusDisplay = statusName;
                badgeClass = "bg-light text-secondary border";
                break;
        }

        boolean isCurrent = currentVisitId != null && currentVisitId.equals(v.getVisitId());

        Long ticketId = turnInfo != null ? turnInfo.ticketId() : null;
        Integer ticketNumber = turnInfo != null ? turnInfo.ticketNumber() : 1;
        String ticketDisplayNumber = turnInfo != null && turnInfo.displayNumber() != null ? turnInfo.displayNumber() : "01";
        boolean isPriority = turnInfo != null && turnInfo.isPriority();
        boolean isEmergency = (turnInfo != null && turnInfo.isEmergency())
                || Boolean.TRUE.equals(v.getIsEmergency())
                || v.getVisitType() == VisitType.EMERGENCY;
        boolean isBookedOnTime = Boolean.TRUE.equals(v.getIsBookedAndOnTime())
                && (v.getAppointmentId() != null || v.getVisitType() == VisitType.APPOINTMENT);
        String priorityReason = turnInfo != null ? turnInfo.priorityReason() : null;
        int priorityRank;
        String priorityLabel;
        boolean isEarlyWaiting = queueCoordinator != null
                ? queueCoordinator.isEarlyWaitingReason(priorityReason)
                : (priorityReason != null && priorityReason.toUpperCase(Locale.ROOT).contains("EARLY_WAITING"));
        if (isEmergency) {
            priorityRank = 1;
            priorityLabel = "Ưu tiên 1 · Cấp cứu";
        } else if (isBookedOnTime) {
            priorityRank = 2;
            priorityLabel = "Ưu tiên 2 · Lịch hẹn đúng giờ";
        } else if (v.getAppointmentId() == null && v.getVisitType() != VisitType.APPOINTMENT) {
            priorityRank = 3;
            priorityLabel = "Ưu tiên 3 · Vãng lai (Walk-in)";
        } else if (isEarlyWaiting) {
            priorityRank = 3;
            priorityLabel = "Ưu tiên 3 · Đặt lịch đến sớm (Chờ giờ)";
        } else {
            priorityRank = 4;
            priorityLabel = "Ưu tiên 4 · Lịch hẹn đến trễ";
        }
        String visitTypeName = v.getVisitType() != null ? v.getVisitType().name() : "WALK_IN";

        return com.cvdcms.DTO.DailyVisitDTO.builder()
                .visitId(v.getVisitId())
                .visitToken(encodeVisit(v.getVisitId()))
                .visitCode(v.getVisitCode() != null ? v.getVisitCode() : "Ca khám")
                .profileId(p != null ? p.getProfileId() : null)
                .patientToken(p != null ? encodePatient(p.getProfileId()) : null)
                .patientCode(patientCode)
                .fullName(fullName)
                .phoneNumber(phone)
                .gender(gender)
                .age(age)
                .checkInTime(time)
                .checkInDate(date)
                .checkInIso(iso)
                .status(statusName)
                .statusDisplayName(statusDisplay)
                .statusBadgeClass(badgeClass)
                .initialSymptoms(v.getInitialSymptoms() != null ? v.getInitialSymptoms() : "Khám theo yêu cầu")
                .branchId(v.getBranch() != null ? v.getBranch().getBranchId() : null)
                .branchCode(v.getBranch() != null ? v.getBranch().getBranchCode() : null)
                .doctorId(doctorId)
                .doctorName(doctorName)
                .branchName(branchName)
                .chairName(chairName)
                .isCurrentVisit(isCurrent)
                .ticketId(ticketId)
                .ticketNumber(ticketNumber)
                .ticketDisplayNumber(ticketDisplayNumber)
                .queueStatus(turnInfo != null ? turnInfo.queueStatus() : null)
                .calledAt(turnInfo != null && turnInfo.calledAt() != null ? turnInfo.calledAt().format(DateTimeFormatter.ofPattern("HH:mm")) : null)
                .isPriority(isPriority)
                .isEmergency(isEmergency)
                .priorityReason(priorityReason)
                .visitType(visitTypeName)
                .appointmentId(v.getAppointmentId())
                .isBookedAndOnTime(isBookedOnTime)
                .queuePriorityRank(priorityRank)
                .queuePriorityLabel(priorityLabel)
                .actualStartTime(v.getActualStartTime() != null ? v.getActualStartTime().format(DateTimeFormatter.ofPattern("HH:mm")) : null)
                .actualEndTime(v.getActualEndTime() != null ? v.getActualEndTime().format(DateTimeFormatter.ofPattern("HH:mm")) : null)
                .coveredByCurrentDoctor(coveredByCurrentDoctor)
                .accessibleByCurrentDoctor(accessibleByCurrentDoctor)
                .build();
    }

}
