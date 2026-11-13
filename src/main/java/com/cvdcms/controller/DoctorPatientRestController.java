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

        // 1. Kiểm tra xem bệnh nhân có ca khám nào đang hoạt động tại cơ sở hợp lệ chưa
        Optional<Visit> activeVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdAndStatusIn(profileId, ACTIVE_STATUSES);
        if (activeVisitOpt.isPresent()) {
            Visit activeVisit = activeVisitOpt.get();
            Long activeBranchId = activeVisit.getBranch() != null ? activeVisit.getBranch().getBranchId() : null;
            if (isAdmin || assignedBranch == null || (activeBranchId != null && activeBranchId.equals(assignedBranch.getBranchId()))) {
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
        newVisit.setCheckInAt(LocalDateTime.now());
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

        // Tìm ca khám gần nhất hoặc ca đang hoạt động
        Optional<Visit> activeVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdAndStatusIn(p.getProfileId(), ACTIVE_STATUSES);
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
            HttpSession session) {

        User currentUser = getLoggedInUser(session);
        boolean isAdmin = currentUser != null && userRepository.hasActiveAdminRole(currentUser.getUserId());
        StaffProfile currentDoctor = (currentUser != null && !isAdmin)
                ? staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null)
                : null;
        Branch assignedBranch = !isAdmin ? getDoctorAssignedBranch(currentUser) : null;
        if (!isAdmin && assignedBranch != null) {
            branchId = assignedBranch.getBranchId();
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
        if (statusStr != null && !statusStr.trim().isEmpty() && !statusStr.equalsIgnoreCase("ALL")) {
            try {
                status = VisitStatus.valueOf(statusStr.trim().toUpperCase());
            } catch (Exception e) {
                log.warn("Invalid status param: {}", statusStr);
            }
        }

        String searchPattern = (search != null && !search.trim().isEmpty()) ? search.trim() : null;

        List<Visit> visits = visitRepository.findFilteredVisits(start, end, status, branchId, searchPattern);

        // Fallback: nếu không có ca khám nào và người dùng chưa chọn bộ lọc ngày cụ thể, hiển thị danh sách gần nhất
        if (visits.isEmpty() && !hasDateFilter && (searchPattern == null || searchPattern.isEmpty()) && (status == null)) {
            final Long filterBranchId = branchId;
            visits = visitRepository.findAll().stream().filter(v -> filterBranchId == null || (v.getBranch() != null && filterBranchId.equals(v.getBranch().getBranchId())))
                    .sorted((a, b) -> b.getCheckInAt().compareTo(a.getCheckInAt()))
                    .limit(20)
                    .collect(Collectors.toList());
        }

        // LỌC CỨNG (Cách 1): Bác sĩ chỉ thấy danh sách ca khám thuộc về mình hoặc được ủy quyền khám thay
        if (currentDoctor != null) {
            final Long docStaffId = currentDoctor.getStaffId();
            Set<Long> coveredIds = doctorCoverGrantRepository != null
                    ? doctorCoverGrantRepository.findActiveCoveredVisitIds(docStaffId, LocalDateTime.now())
                    : Collections.emptySet();
            visits = visits.stream()
                    .filter(v -> (v.getPrimaryDoctor() != null && docStaffId.equals(v.getPrimaryDoctor().getStaffId()))
                            || coveredIds.contains(v.getVisitId()))
                    .collect(Collectors.toList());
        }

        Map<Long, ResolvedTurnInfo> turnMap = resolveDailyTurnNumbers(visits, branchId);

        List<com.cvdcms.DTO.DailyVisitDTO> result = visits.stream()
                .map(v -> mapToDailyVisitDTO(v, currentVisitId, turnMap.get(v.getVisitId())))
                .sorted(Comparator
                        .comparing((com.cvdcms.DTO.DailyVisitDTO d) -> d.getCheckInIso() != null ? d.getCheckInIso() : "", Comparator.reverseOrder())
                        .thenComparingInt(this::getVisitStatusSortRank)
                        .thenComparing(d -> Boolean.TRUE.equals(d.getIsEmergency()), Comparator.reverseOrder())
                        .thenComparing(d -> Boolean.TRUE.equals(d.getIsPriority()), Comparator.reverseOrder())
                        .thenComparing(d -> d.getTicketNumber() != null ? d.getTicketNumber() : Integer.MAX_VALUE)
                        .thenComparing(d -> d.getVisitId() != null ? d.getVisitId() : Long.MAX_VALUE))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    private int getVisitStatusSortRank(com.cvdcms.DTO.DailyVisitDTO d) {
        if (d == null || d.getStatus() == null) return 2;
        String s = d.getStatus().toUpperCase();
        if (s.contains("EXAM") || s.contains("TREATMENT") || s.equals("ORDERED")) {
            return 0; // Đang khám / Điều trị: Ưu tiên cao nhất
        }
        if (s.equals("WAITING")) {
            return 1; // Đang chờ khám: Ưu tiên kế tiếp
        }
        if (s.equals("WAITING_PAYMENT") || s.equals("COMPLETED")) {
            return 2; // Đã hoàn thành / Chờ thu ngân: Nhóm sau
        }
        if (s.equals("CANCELLED")) {
            return 3; // Đã hủy
        }
        return 2;
    }

    private record ResolvedTurnInfo(
            Long ticketId,
            Integer ticketNumber,
            String displayNumber,
            boolean isPriority,
            boolean isEmergency,
            String priorityReason
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
                            t.getPriorityReason()
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

                boolean isEmergency = v.getVisitType() == VisitType.EMERGENCY;
                boolean isReturningCls = v.getStatus() == VisitStatus.WAITING_RESULT
                        || v.getStatus() == VisitStatus.WAITING_PARACLINICAL;
                boolean isAgePriority = false;
                String reason = null;

                if (isEmergency) {
                    reason = (v.getInitialSymptoms() != null && !v.getInitialSymptoms().isBlank())
                            ? v.getInitialSymptoms()
                            : "Ca cấp cứu khẩn cấp (FT10)";
                } else if (isReturningCls) {
                    reason = "Bệnh nhân quay lại sau cận lâm sàng chờ đọc kết quả (FT09)";
                } else if (v.getPatientProfile() != null && v.getPatientProfile().getDob() != null) {
                    LocalDate refDate = v.getCheckInAt() != null ? v.getCheckInAt().toLocalDate() : LocalDate.now();
                    int age = Period.between(v.getPatientProfile().getDob(), refDate).getYears();
                    if (age >= 65) {
                        isAgePriority = true;
                        reason = "Ưu tiên người cao tuổi (65+ tuổi - FT10)";
                    } else if (age >= 0 && age <= 6) {
                        isAgePriority = true;
                        reason = "Ưu tiên trẻ nhỏ (<= 6 tuổi - FT10)";
                    }
                }

                boolean isPriority = isEmergency || isReturningCls || isAgePriority;
                if (isPriority) {
                    while (usedPriority.contains(nextPriority)) {
                        nextPriority++;
                    }
                    usedPriority.add(nextPriority);
                    String disp = "P-" + String.format("%02d", nextPriority);
                    resolvedMap.put(v.getVisitId(), new ResolvedTurnInfo(
                            null, nextPriority, disp, true, isEmergency, reason
                    ));
                } else {
                    while (usedNormal.contains(nextNormal)) {
                        nextNormal++;
                    }
                    usedNormal.add(nextNormal);
                    String disp = String.format("%02d", nextNormal);
                    resolvedMap.put(v.getVisitId(), new ResolvedTurnInfo(
                            null, nextNormal, disp, false, false, null
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
        boolean isEmergency = visit.getVisitType() == VisitType.EMERGENCY;
        boolean isPriority = isEmergency;
        String priorityReason = isEmergency
                ? (visit.getInitialSymptoms() != null && !visit.getInitialSymptoms().isBlank() ? visit.getInitialSymptoms() : "Ca cấp cứu khẩn cấp (FT10)")
                : null;

        PatientProfile p = visit.getPatientProfile();
        if (!isPriority && p != null && p.getDob() != null) {
            int age = Period.between(p.getDob(), day).getYears();
            if (age >= 65) {
                isPriority = true;
                priorityReason = "Ưu tiên người cao tuổi (65+ tuổi - FT10)";
            } else if (age >= 0 && age <= 6) {
                isPriority = true;
                priorityReason = "Ưu tiên trẻ nhỏ (<= 6 tuổi - FT10)";
            }
        }

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
        ticket.setPriorityScore(isEmergency ? 100 : (isPriority ? 50 : 0));
        ticket.setPriorityReason(priorityReason);
        ticket.setStatus(visit.getStatus() == VisitStatus.IN_EXAM ? QueueStatus.IN_SERVICE : QueueStatus.WAITING);
        ticket.setIssuedAt(LocalDateTime.now());
        ticket.setCalledAt(null);
        ticket.setPausedAt(null);
        ticket.setResumedAt(null);
        ticket.setCompletedAt(null);
        return clinicalQueueTicketRepository.save(ticket);
    }

    private com.cvdcms.DTO.DailyVisitDTO mapToDailyVisitDTO(Visit v, Long currentVisitId, ResolvedTurnInfo turnInfo) {
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
        boolean isEmergency = (turnInfo != null && turnInfo.isEmergency()) || v.getVisitType() == VisitType.EMERGENCY;
        String priorityReason = turnInfo != null ? turnInfo.priorityReason() : null;
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
                .isPriority(isPriority)
                .isEmergency(isEmergency)
                .priorityReason(priorityReason)
                .visitType(visitTypeName)
                .build();
    }

    @PostMapping("/quick-create")
    @Transactional
    public ResponseEntity<Map<String, Object>> quickCreatePatientAndVisit(
            @RequestBody Map<String, String> payload,
            HttpSession session) {
        String fullName = payload.get("fullName");
        String phoneNumber = payload.get("phoneNumber");
        String gender = payload.getOrDefault("gender", "Nam");
        String dobStr = payload.get("dob");
        String symptoms = payload.getOrDefault("symptoms", "Khám & Điều trị tại ghế");
        String branchIdStr = payload.get("branchId");

        if (fullName == null || fullName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Họ tên bệnh nhân không được để trống"));
        }
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Số điện thoại không được để trống"));
        }

        User currentUser = doctorVisitAccess != null
                ? doctorVisitAccess.requireAuthenticatedUser(session)
                : getLoggedInUser(session);
        boolean isAdmin = currentUser != null && userRepository.hasActiveAdminRole(currentUser.getUserId());
        Branch assignedBranch = !isAdmin ? getDoctorAssignedBranch(currentUser) : null;

        Long branchId = null;
        if (branchIdStr != null && !branchIdStr.trim().isEmpty()) {
            try {
                branchId = Long.parseLong(branchIdStr.trim());
            } catch (Exception ignored) {}
        }

        if (!isAdmin && assignedBranch != null) {
            if (branchId != null && !branchId.equals(assignedBranch.getBranchId())) {
                String fullBranchName = (assignedBranch.getBranchCode() != null ? assignedBranch.getBranchCode() + " - " : "") + assignedBranch.getBranchName();
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "message", "Cảnh báo: Bác sĩ chỉ được phép tiếp nhận bệnh nhân tại cơ sở đang làm việc (" + fullBranchName + ")!"
                ));
            }
            branchId = assignedBranch.getBranchId();
        }

        Branch branch = null;
        if (branchId != null) {
            branch = branchRepository.findById(branchId).orElse(null);
        }
        if (branch == null && assignedBranch != null) {
            branch = assignedBranch;
        }
        if (branch == null) {
            branch = branchRepository.findAll().stream().findFirst().orElse(null);
        }

        LocalDate dob = null;
        if (dobStr != null && !dobStr.trim().isEmpty()) {
            try {
                dob = LocalDate.parse(dobStr.trim());
            } catch (Exception ignored) {}
        }

        PatientProfile patient = new PatientProfile();
        patient.setFullName(fullName.trim());
        patient.setPhoneNumber(phoneNumber.trim());
        patient.setGender(gender);
        patient.setDob(dob);
        patient.setPreferredBranch(branch);
        patient.setStatus("ACTIVE");
        patient.setDepositBalance(java.math.BigDecimal.ZERO);

        long pCount = patientProfileRepository.count() + 1;
        patient.setPatientCode("BN" + String.format("%05d", pCount));
        patient = patientProfileRepository.save(patient);

        Visit visit = new Visit();
        visit.setPatientProfile(patient);
        visit.setCheckInAt(LocalDateTime.now());
        visit.setStatus(VisitStatus.IN_EXAM);
        visit.setVisitType(VisitType.WALK_IN);
        visit.setInitialSymptoms(symptoms);
        visit.setBranch(branch);

        StaffProfile doctor = null;
        if (currentUser != null) {
            doctor = staffProfileRepository.findByUser_UserId(currentUser.getUserId()).orElse(null);
        }
        if (doctor == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Chỉ tài khoản bác sĩ đã liên kết hồ sơ nhân sự (StaffProfile) mới được tạo nhanh ca khám.");
        }
        visit.setPrimaryDoctor(doctor);

        long vCount = visitRepository.count() + 1;
        String vCode = "VIS" + (10000 + vCount);
        if (visitRepository.findByVisitCode(vCode).isPresent()) {
            vCode = "VIS" + System.currentTimeMillis();
        }
        visit.setVisitCode(vCode);
        visit = visitRepository.save(visit);
        examinations.ensureForAssignedVisit(visit.getVisitId());
        ensureQueueTicketForVisit(visit);

        String createdVisitToken = encodeVisit(visit.getVisitId());
        String redirectUrl = "/doctor/examination/" + createdVisitToken + "/diagnoses";
        if (branch != null) {
            redirectUrl += "?branchId=" + branch.getBranchId();
        }

        return ResponseEntity.ok(Map.of(
                "profileId", patient.getProfileId(),
                "patientToken", encodePatient(patient.getProfileId()),
                "patientCode", patient.getPatientCode(),
                "visitId", visit.getVisitId(),
                "visitToken", createdVisitToken,
                "visitCode", visit.getVisitCode(),
                "redirectUrl", redirectUrl
        ));
    }

}
