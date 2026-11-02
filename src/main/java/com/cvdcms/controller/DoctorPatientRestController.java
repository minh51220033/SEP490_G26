package com.cvdcms.controller;

import com.cvdcms.DTO.PatientSearchResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.entity.enums.VisitType;
import com.cvdcms.repository.*;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ClinicalExaminationRepository clinicalExamRepository;

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
            @RequestParam(value = "q", required = false) String query) {

        List<PatientProfile> patients;
        if (query == null || query.trim().isEmpty()) {
            patients = patientProfileRepository.findTop15ByOrderByProfileIdDesc();
        } else {
            patients = patientProfileRepository.searchPatients(query.trim());
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
        PatientProfile patient = patientProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân với ID: " + profileId));

        User currentUser = getLoggedInUser(session);
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

        // 1. Kiểm tra xem bệnh nhân có ca khám nào đang hoạt động chưa
        Optional<Visit> activeVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdAndStatusIn(profileId, ACTIVE_STATUSES);
        if (activeVisitOpt.isPresent()) {
            Visit activeVisit = activeVisitOpt.get();
            return ResponseEntity.ok(Map.of(
                    "visitId", activeVisit.getVisitId(),
                    "visitCode", activeVisit.getVisitCode(),
                    "isNew", false,
                    "redirectUrl", "/doctor/examination/" + activeVisit.getVisitId() + "/diagnoses"
            ));
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

        // Gán bác sĩ
        StaffProfile doctor = staffProfileRepository.findAll().stream()
                .filter(s -> s.getStaffCode() != null && s.getStaffCode().startsWith("BS"))
                .findFirst().orElse(null);
        newVisit.setPrimaryDoctor(doctor);

        long visitCount = visitRepository.count() + 1;
        newVisit.setVisitCode("VIS" + (10000 + visitCount));

        newVisit = visitRepository.save(newVisit);
        ensureClinicalExamExists(newVisit, doctor);

        return ResponseEntity.ok(Map.of(
                "visitId", newVisit.getVisitId(),
                "visitCode", newVisit.getVisitCode(),
                "isNew", true,
                "redirectUrl", "/doctor/examination/" + newVisit.getVisitId() + "/diagnoses"
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
            vStatusDisplay = "Đang khám: " + (vCode != null ? vCode : "Ca #" + vId);
            hasActive = true;
            if (av.getPrimaryDoctor() != null) vDoctor = av.getPrimaryDoctor().getFullName();
            if (av.getCheckInAt() != null) vDate = av.getCheckInAt().format(DT_FORMAT);
        } else if (latestVisitOpt.isPresent()) {
            Visit lv = latestVisitOpt.get();
            vId = lv.getVisitId();
            vCode = lv.getVisitCode();
            vStatus = lv.getStatus().name();
            String time = lv.getCheckInAt() != null ? lv.getCheckInAt().format(DATE_FORMAT) : "";
            vStatusDisplay = "Ca gần nhất: " + (vCode != null ? vCode : "Ca #" + vId) + (time.isEmpty() ? "" : " (" + time + ")");
            if (lv.getPrimaryDoctor() != null) vDoctor = lv.getPrimaryDoctor().getFullName();
            if (lv.getCheckInAt() != null) vDate = lv.getCheckInAt().format(DT_FORMAT);
        }

        return PatientSearchResponseDTO.builder()
                .profileId(p.getProfileId())
                .patientCode(p.getPatientCode())
                .fullName(p.getFullName())
                .phoneNumber(p.getPhoneNumber())
                .gender(p.getGender())
                .dob(dobStr)
                .age(age)
                .address(p.getAddress())
                .avatarUrl(p.getAvatarUrl())
                .latestVisitId(vId)
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

        List<com.cvdcms.DTO.DailyVisitDTO> result = visits.stream()
                .map(v -> mapToDailyVisitDTO(v, currentVisitId))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    private com.cvdcms.DTO.DailyVisitDTO mapToDailyVisitDTO(Visit v, Long currentVisitId) {
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

        return com.cvdcms.DTO.DailyVisitDTO.builder()
                .visitId(v.getVisitId())
                .visitCode(v.getVisitCode() != null ? v.getVisitCode() : "VIS#" + v.getVisitId())
                .profileId(p != null ? p.getProfileId() : null)
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
                .doctorName(doctorName)
                .branchName(branchName)
                .chairName(chairName)
                .isCurrentVisit(isCurrent)
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

        User currentUser = getLoggedInUser(session);
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

        StaffProfile doctor = staffProfileRepository.findAll().stream()
                .filter(s -> s.getStaffCode() != null && s.getStaffCode().startsWith("BS"))
                .findFirst().orElse(null);
        visit.setPrimaryDoctor(doctor);

        long vCount = visitRepository.count() + 1;
        String vCode = "VIS" + (10000 + vCount);
        if (visitRepository.findByVisitCode(vCode).isPresent()) {
            vCode = "VIS" + System.currentTimeMillis();
        }
        visit.setVisitCode(vCode);
        visit = visitRepository.save(visit);
        ensureClinicalExamExists(visit, doctor);

        String redirectUrl = "/doctor/examination/" + visit.getVisitId() + "/diagnoses";
        if (branch != null) {
            redirectUrl += "?branchId=" + branch.getBranchId();
        }

        return ResponseEntity.ok(Map.of(
                "profileId", patient.getProfileId(),
                "patientCode", patient.getPatientCode(),
                "visitId", visit.getVisitId(),
                "visitCode", visit.getVisitCode(),
                "redirectUrl", redirectUrl
        ));
    }

    private void ensureClinicalExamExists(Visit visit, StaffProfile doctor) {
        if (visit == null || clinicalExamRepository == null) return;
        try {
            if (clinicalExamRepository.findByVisit_VisitId(visit.getVisitId()).isEmpty()) {
                ClinicalExamination exam = new ClinicalExamination();
                exam.setVisit(visit);
                exam.setPatientProfile(visit.getPatientProfile());
                StaffProfile effectiveDoc = doctor != null ? doctor : visit.getPrimaryDoctor();
                if (effectiveDoc == null) {
                    effectiveDoc = staffProfileRepository.findAll().stream()
                            .filter(s -> s.getStaffCode() != null && s.getStaffCode().startsWith("BS"))
                            .findFirst().orElse(null);
                }
                exam.setDoctor(effectiveDoc);
                exam.setChiefComplaint(visit.getInitialSymptoms() != null && !visit.getInitialSymptoms().isBlank()
                        ? visit.getInitialSymptoms() : "Tiếp nhận khám nha khoa");
                exam.setExamTime(LocalDateTime.now());
                clinicalExamRepository.save(exam);
            }
        } catch (Exception e) {
            log.warn("Không thể tạo ClinicalExamination cho ca khám {}: {}", visit.getVisitId(), e.getMessage());
        }
    }
}
