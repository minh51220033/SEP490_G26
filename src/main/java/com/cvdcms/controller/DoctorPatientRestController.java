package com.cvdcms.controller;

import com.cvdcms.DTO.PatientSearchResponseDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.entity.enums.VisitType;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    public ResponseEntity<Map<String, Object>> openOrCreateVisitForPatient(@PathVariable Long profileId) {
        PatientProfile patient = patientProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân với ID: " + profileId));

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
        Branch branch = patient.getPreferredBranch();
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
            @RequestParam(value = "date", required = false) String dateStr,
            @RequestParam(value = "startDate", required = false) String startDateStr,
            @RequestParam(value = "endDate", required = false) String endDateStr,
            @RequestParam(value = "status", required = false) String statusStr,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "currentVisitId", required = false) Long currentVisitId) {

        LocalDateTime start = null;
        LocalDateTime end = null;
        boolean hasDateFilter = false;

        if (startDateStr != null && !startDateStr.trim().isEmpty() && !startDateStr.equalsIgnoreCase("ALL")) {
            try {
                LocalDate sDate = LocalDate.parse(startDateStr.trim());
                start = sDate.atStartOfDay();
                hasDateFilter = true;
            } catch (Exception e) {
                log.warn("Invalid startDate param: {}", startDateStr);
            }
        }

        if (endDateStr != null && !endDateStr.trim().isEmpty() && !endDateStr.equalsIgnoreCase("ALL")) {
            try {
                LocalDate eDate = LocalDate.parse(endDateStr.trim());
                end = eDate.atTime(java.time.LocalTime.MAX);
                hasDateFilter = true;
            } catch (Exception e) {
                log.warn("Invalid endDate param: {}", endDateStr);
            }
        }

        if (start == null && end == null && dateStr != null && !dateStr.trim().isEmpty() && !dateStr.equalsIgnoreCase("ALL")) {
            try {
                LocalDate localDate = LocalDate.parse(dateStr.trim());
                start = localDate.atStartOfDay();
                end = localDate.atTime(java.time.LocalTime.MAX);
                hasDateFilter = true;
            } catch (Exception e) {
                log.warn("Invalid date param: {}", dateStr);
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

        List<Visit> visits = visitRepository.findFilteredVisits(start, end, status, searchPattern);

        // Fallback: nếu không có ca khám nào và người dùng chưa chọn bộ lọc ngày cụ thể, hiển thị danh sách gần nhất
        if (visits.isEmpty() && !hasDateFilter && (searchPattern == null || searchPattern.isEmpty()) && (status == null)) {
            visits = visitRepository.findAll().stream()
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
                .doctorName(doctorName)
                .branchName(branchName)
                .chairName(chairName)
                .isCurrentVisit(isCurrent)
                .build();
    }
}
