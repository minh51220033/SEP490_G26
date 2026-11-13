package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.CustomerCareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Triển khai nghiệp vụ Quản lý Chăm sóc khách hàng (FT44 - UC-26):
 * - Phân quyền chặt chẽ theo Vai trò (ADMIN, LE_TAN, BAC_SI) và theo Cơ sở (Branch):
 *   + ADMIN: Quản lý toàn bộ các cơ sở đang hoạt động.
 *   + LE_TAN / BAC_SI: Chỉ được xem và tiếp nhận chăm sóc bệnh nhân thuộc đúng cơ sở mình đang công tác.
 *   + Các vai trò khác (BENH_NHAN, QUAN_LY_KHO, Y_TA...): Từ chối truy cập (403 FORBIDDEN).
 * - SCR-44.1: Tổng quan Dashboard KPI Cards & Danh sách công việc CSKH.
 * - SCR-44.2: Bộ lọc & Phân nhóm bệnh nhân (Sau điều trị, Nhắc lịch hẹn, Nhắc trả góp, Lịch hẹn lỡ, Không làm dịch vụ, Phản ánh).
 * - SCR-44.3: Lập lịch / Phân công chăm sóc thủ công (chống tạo trùng) và Tự động tổng hợp công việc từ Ca khám / Đơn thuốc / Lịch hẹn.
 * - SCR-44.4: Chi tiết công việc & Ghi kết quả liên hệ (kiểm tra bắt buộc next_retry_at khi NO_ANSWER hoặc BUSY) + Kết nối dữ liệu Khám lâm sàng, Chẩn đoán, Lời dặn (#tab-instructions) & Đơn thuốc.
 * - SCR-44.5: Chuyển Bác sĩ hỗ trợ chuyên môn (ESCALATED_TO_DOCTOR) và Bác sĩ nhập phản hồi chuyên môn (doctor_response).
 * - SCR-44.6: Timeline lịch sử chăm sóc bệnh nhân.
 */
@Service
public class CustomerCareServiceImpl implements CustomerCareService {

    // Nhãn tiền tố lưu trong trường notes để phân biệt các nhóm nghiệp vụ mở rộng (SCR-44.2)
    // mà không làm thay đổi kiểu cột ENUM task_type của MySQL.
    private static final String TAG_INSTALLMENT = "[TRA_GOP] ";
    private static final String TAG_NO_SERVICE = "[KHONG_DICH_VU] ";
    private static final String TAG_FEEDBACK = "[PHAN_ANH] ";
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    // Bộ đếm tuần tự đảm bảo sinh mã công việc (task_code <= 20 ký tự) không bị trùng lặp
    private static final AtomicLong TASK_CODE_SEQ = new AtomicLong(System.currentTimeMillis() % 100000000L);

    @Autowired(required = false)
    private com.cvdcms.service.CustomerCareNotificationService notificationService;

    @Autowired(required = false)
    private com.cvdcms.service.SystemAuditLogService systemAuditLogService;

    private final CustomerCareTaskRepository taskRepository;
    private final CustomerCareInteractionRepository interactionRepository;
    private final PatientProfileRepository patientRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffRepository;
    private final VisitRepository visitRepository;
    private final AppointmentRepository appointmentRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final UserRepository userRepository;
    private final ClinicalExaminationRepository clinicalExaminationRepository;
    private final ExaminationDiagnosisRepository examinationDiagnosisRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.UrlIdCodec urlIdCodec = new com.cvdcms.security.UrlIdCodec();

    private String buildExaminationDetailUrl(Long visitId) {
        String ref = urlIdCodec != null ? urlIdCodec.encodeVisit(visitId) : "";
        return "/doctor/examination/" + ref + "/diagnoses#tab-instructions";
    }

    public CustomerCareServiceImpl(
            CustomerCareTaskRepository taskRepository,
            CustomerCareInteractionRepository interactionRepository,
            PatientProfileRepository patientRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffRepository,
            VisitRepository visitRepository,
            AppointmentRepository appointmentRepository,
            PrescriptionRepository prescriptionRepository,
            UserRepository userRepository) {
        this(
                taskRepository,
                interactionRepository,
                patientRepository,
                branchRepository,
                staffRepository,
                visitRepository,
                appointmentRepository,
                prescriptionRepository,
                userRepository,
                null,
                null
        );
    }

    @Autowired
    public CustomerCareServiceImpl(
            CustomerCareTaskRepository taskRepository,
            CustomerCareInteractionRepository interactionRepository,
            PatientProfileRepository patientRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffRepository,
            VisitRepository visitRepository,
            AppointmentRepository appointmentRepository,
            PrescriptionRepository prescriptionRepository,
            UserRepository userRepository,
            ClinicalExaminationRepository clinicalExaminationRepository,
            ExaminationDiagnosisRepository examinationDiagnosisRepository) {
        this.taskRepository = taskRepository;
        this.interactionRepository = interactionRepository;
        this.patientRepository = patientRepository;
        this.branchRepository = branchRepository;
        this.staffRepository = staffRepository;
        this.visitRepository = visitRepository;
        this.appointmentRepository = appointmentRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.userRepository = userRepository;
        this.clinicalExaminationRepository = clinicalExaminationRepository;
        this.examinationDiagnosisRepository = examinationDiagnosisRepository;
    }

    // =========================================================================
    // 0. KIỂM TRA PHÂN QUYỀN VAI TRÒ & CƠ SỞ (ROLE & BRANCH AUTHORIZATION)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public String requireCareRole(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ. Vui lòng đăng nhập lại.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Không tìm thấy tài khoản đăng nhập."));

        if (Boolean.FALSE.equals(user.getIsActive())
                || (user.getStatus() != null && !"ACTIVE".equalsIgnoreCase(user.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản của bạn đã bị khóa hoặc ngưng hoạt động.");
        }

        if (userRepository.hasActiveAdminRole(userId)) {
            return "ADMIN";
        }

        if (userRepository.hasActivePermission(userId, "CUSTOMER_CARE_MANAGE")) {
            List<String> userRoles = taskRepository.findActiveRoleNamesByUserId(userId);
            if (userRoles != null) {
                for (String r : userRoles) {
                    if ("BAC_SI".equalsIgnoreCase(r) || "ROLE_BAC_SI".equalsIgnoreCase(r) || "DOCTOR".equalsIgnoreCase(r)) {
                        return "BAC_SI";
                    }
                }
            }
            return "LE_TAN";
        }

        List<String> activeRoles = taskRepository.findActiveRoleNamesByUserId(userId);
        if (activeRoles != null && !activeRoles.isEmpty()) {
            for (String role : activeRoles) {
                if ("ADMIN".equalsIgnoreCase(role) || "ROLE_ADMIN".equalsIgnoreCase(role)) {
                    return "ADMIN";
                }
            }
            for (String role : activeRoles) {
                if ("BAC_SI".equalsIgnoreCase(role) || "ROLE_BAC_SI".equalsIgnoreCase(role) || "DOCTOR".equalsIgnoreCase(role)) {
                    return "BAC_SI";
                }
            }
            for (String role : activeRoles) {
                if ("LE_TAN".equalsIgnoreCase(role) || "ROLE_LE_TAN".equalsIgnoreCase(role)
                        || "CSKH".equalsIgnoreCase(role) || "RECEPTIONIST".equalsIgnoreCase(role)) {
                    return "LE_TAN";
                }
            }
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn không có quyền truy cập trang Quản lý Chăm sóc khách hàng. Chức năng này chỉ dành cho Admin, Lễ tân và Bác sĩ."
            );
        }

        Optional<StaffProfile> staffOpt = staffRepository.findByUser_UserId(userId);
        if (staffOpt.isPresent() && staffOpt.get().getStatus() == StaffStatus.ACTIVE) {
            StaffProfile sp = staffOpt.get();
            if (isDoctorStaff(sp)) {
                return "BAC_SI";
            }
            if (isReceptionistStaff(sp)) {
                return "LE_TAN";
            }
        }

        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Bạn không có quyền truy cập trang Quản lý Chăm sóc khách hàng. Chức năng này chỉ dành cho Admin, Lễ tân và Bác sĩ."
        );
    }

    private List<Branch> resolveAllowedBranches(Long userId, String careRole) {
        List<Branch> allActive = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
        if (allActive.isEmpty()) {
            allActive = branchRepository.findAll();
        }

        if ("ADMIN".equals(careRole)) {
            return allActive;
        }

        List<Long> explicitIds = taskRepository.findExplicitBranchIdsByUserId(userId);
        if (explicitIds != null && !explicitIds.isEmpty()) {
            Set<Long> allowedSet = new LinkedHashSet<>(explicitIds);
            return allActive.stream()
                    .filter(b -> allowedSet.contains(b.getBranchId()))
                    .toList();
        }

        Optional<StaffProfile> staffOpt = staffRepository.findByUser_UserId(userId);
        if (staffOpt.isPresent()) {
            List<Long> inferredIds = taskRepository.findInferredBranchIdsByStaffId(staffOpt.get().getStaffId());
            if (inferredIds != null && !inferredIds.isEmpty()) {
                Set<Long> allowedSet = new LinkedHashSet<>(inferredIds);
                List<Branch> matched = allActive.stream()
                        .filter(b -> allowedSet.contains(b.getBranchId()))
                        .toList();
                if (!matched.isEmpty()) {
                    return matched;
                }
            }
        }

        return allActive;
    }

    private Long resolveAndAuthorizeBranchId(Long requestedBranchId, Long userId, boolean requireSpecificBranch) {
        String careRole = requireCareRole(userId);
        validateBranchIdIfPresent(requestedBranchId);

        if ("ADMIN".equals(careRole)) {
            if (requireSpecificBranch && requestedBranchId == null) {
                bad("Vui lòng chọn một cơ sở / chi nhánh cụ thể để thực hiện thao tác này.");
            }
            return requestedBranchId;
        }

        List<Branch> allowedBranches = resolveAllowedBranches(userId, careRole);
        if (allowedBranches.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Tài khoản của bạn chưa được phân công cơ sở làm việc nên không thể tiếp nhận chăm sóc khách hàng."
            );
        }

        Set<Long> allowedIds = allowedBranches.stream()
                .map(Branch::getBranchId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (requestedBranchId == null) {
            return allowedIds.iterator().next();
        }

        if (!allowedIds.contains(requestedBranchId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn chỉ có quyền xem và tiếp nhận chăm sóc bệnh nhân tại cơ sở mình đang công tác."
            );
        }

        return requestedBranchId;
    }

    private String assertCanAccessTask(CustomerCareTask task, Long userId) {
        String careRole = requireCareRole(userId);
        if ("ADMIN".equals(careRole)) {
            return careRole;
        }
        Long taskBranchId = task.getBranch() != null ? task.getBranch().getBranchId() : null;
        List<Branch> allowedBranches = resolveAllowedBranches(userId, careRole);
        boolean canAccess = taskBranchId != null && allowedBranches.stream()
                .anyMatch(b -> taskBranchId.equals(b.getBranchId()));
        if (!canAccess) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Công việc CSKH này thuộc cơ sở khác. Bạn chỉ được xem và tiếp nhận chăm sóc bệnh nhân tại cơ sở của mình."
            );
        }
        return careRole;
    }

    // =========================================================================
    // 1. CHI NHÁNH & DANH MỤC TRA CỨU (LOOKUPS)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAccessibleBranches(Long userId) {
        String careRole = requireCareRole(userId);
        List<Branch> allowedBranches = resolveAllowedBranches(userId, careRole);

        List<BranchOptionDTO> options = allowedBranches.stream()
                .map(b -> new BranchOptionDTO(b.getBranchId(), b.getBranchCode(), b.getBranchName()))
                .toList();

        StaffProfile currentStaff = staffRepository.findByUser_UserId(userId).orElse(null);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("careRole", careRole);
        res.put("isAdmin", "ADMIN".equals(careRole));
        res.put("branchSelectionAllowed", "ADMIN".equals(careRole) || options.size() > 1);
        res.put("currentStaffId", currentStaff != null ? currentStaff.getStaffId() : null);
        res.put("currentStaffName", currentStaff != null ? currentStaff.getFullName() : null);
        res.put("branches", options);
        return res;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getLookups(Long branchId, Long userId) {
        Long effectiveBranchId = resolveAndAuthorizeBranchId(branchId, userId, false);

        List<StaffProfile> allStaff = staffRepository.findByStatus(StaffStatus.ACTIVE);
        if (allStaff.isEmpty()) {
            allStaff = staffRepository.findAll();
        }

        List<StaffProfile> branchStaff = allStaff;
        if (effectiveBranchId != null) {
            List<Long> staffIdsAtBranch = taskRepository.findActiveStaffIdsByBranch(effectiveBranchId);
            if (staffIdsAtBranch != null && !staffIdsAtBranch.isEmpty()) {
                Set<Long> idSet = new HashSet<>(staffIdsAtBranch);
                List<StaffProfile> filteredStaff = allStaff.stream()
                        .filter(s -> idSet.contains(s.getStaffId()))
                        .toList();
                if (!filteredStaff.isEmpty()) {
                    branchStaff = filteredStaff;
                }
            }
        }

        List<Map<String, Object>> staffOptions = new ArrayList<>();
        List<Map<String, Object>> doctorOptions = new ArrayList<>();

        for (StaffProfile s : branchStaff) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("staffId", s.getStaffId());
            item.put("staffCode", s.getStaffCode());
            item.put("fullName", s.getFullName());
            item.put("department", s.getDepartment());
            item.put("positionTitle", s.getPositionTitle());
            boolean isDoc = isDoctorStaff(s);
            item.put("isDoctor", isDoc);
            staffOptions.add(item);
            if (isDoc) {
                doctorOptions.add(item);
            }
        }
        if (doctorOptions.isEmpty()) {
            doctorOptions.addAll(staffOptions);
        }

        List<Appointment> appointments = appointmentRepository.findByBranchWithDetails(effectiveBranchId);
        List<Visit> visits = taskRepository.findValidVisitsByBranchForLookup(effectiveBranchId);
        List<Prescription> prescriptions = taskRepository.findValidPrescriptionsByBranchForCare(effectiveBranchId);

        List<PatientProfile> allPatients = patientRepository.findAll();
        Set<Long> branchPatientIds = new HashSet<>();
        if (effectiveBranchId != null) {
            for (Appointment a : appointments) {
                if (a.getPatientProfile() != null) branchPatientIds.add(a.getPatientProfile().getProfileId());
            }
            for (Visit v : visits) {
                if (v.getPatientProfile() != null) branchPatientIds.add(v.getPatientProfile().getProfileId());
            }
            for (Prescription pr : prescriptions) {
                if (pr.getPatientProfile() != null) branchPatientIds.add(pr.getPatientProfile().getProfileId());
            }
            for (CustomerCareTask t : taskRepository.findAllByBranchWithDetails(effectiveBranchId)) {
                if (t.getPatientProfile() != null) branchPatientIds.add(t.getPatientProfile().getProfileId());
            }
        }

        List<Map<String, Object>> patientOptions = allPatients.stream()
                .filter(p -> effectiveBranchId == null
                        || (p.getPreferredBranch() != null && effectiveBranchId.equals(p.getPreferredBranch().getBranchId()))
                        || branchPatientIds.contains(p.getProfileId()))
                .sorted(Comparator.comparing(PatientProfile::getProfileId).reversed())
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("profileId", p.getProfileId());
                    m.put("patientCode", p.getPatientCode());
                    m.put("fullName", p.getFullName());
                    m.put("phoneNumber", p.getPhoneNumber());
                    m.put("gender", p.getGender());
                    m.put("dob", p.getDob());
                    m.put("preferredBranchId", p.getPreferredBranch() != null ? p.getPreferredBranch().getBranchId() : null);
                    return m;
                })
                .toList();

        List<Map<String, Object>> appointmentOptions = appointments.stream()
                .limit(200)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("appointmentId", a.getAppointmentId());
                    m.put("appointmentCode", a.getAppointmentCode());
                    m.put("profileId", a.getPatientProfile().getProfileId());
                    m.put("patientName", a.getPatientProfile().getFullName());
                    m.put("appointmentDate", a.getAppointmentDate());
                    m.put("expectedTime", a.getExpectedTime());
                    m.put("status", a.getStatus() != null ? a.getStatus().name() : null);
                    m.put("serviceName", a.getService() != null ? a.getService().getServiceName() : null);
                    return m;
                })
                .toList();

        // Map prescriptions by visitId for quick lookup enrichment
        Map<Long, Prescription> latestPrescByVisit = new HashMap<>();
        for (Prescription pr : prescriptions) {
            if (pr.getVisit() != null && pr.getVisit().getVisitId() != null) {
                latestPrescByVisit.putIfAbsent(pr.getVisit().getVisitId(), pr);
            }
        }

        List<Map<String, Object>> visitOptions = visits.stream()
                .limit(200)
                .map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("visitId", v.getVisitId());
                    m.put("visitCode", v.getVisitCode());
                    m.put("profileId", v.getPatientProfile().getProfileId());
                    m.put("patientName", v.getPatientProfile().getFullName());
                    m.put("checkInAt", v.getCheckInAt());
                    m.put("status", v.getStatus() != null ? v.getStatus().name() : null);
                    m.put("doctorName", v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getFullName() : null);
                    m.put("initialSymptoms", v.getInitialSymptoms());
                    m.put("notes", v.getNotes());
                    Prescription pr = latestPrescByVisit.get(v.getVisitId());
                    if (pr != null) {
                        m.put("prescriptionId", pr.getPrescriptionId());
                        m.put("prescriptionCode", pr.getPrescriptionCode());
                        m.put("diagnosisText", pr.getDiagnosisText());
                        m.put("adviceNotes", pr.getAdviceNotes());
                    }
                    m.put("examinationDetailUrl", buildExaminationDetailUrl(v.getVisitId()));
                    return m;
                })
                .toList();

        List<Map<String, Object>> prescriptionOptions = prescriptions.stream()
                .limit(200)
                .map(pr -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("prescriptionId", pr.getPrescriptionId());
                    m.put("prescriptionCode", pr.getPrescriptionCode());
                    m.put("profileId", pr.getPatientProfile().getProfileId());
                    m.put("visitId", pr.getVisit() != null ? pr.getVisit().getVisitId() : null);
                    m.put("diagnosisText", pr.getDiagnosisText());
                    m.put("adviceNotes", pr.getAdviceNotes());
                    m.put("followupDate", pr.getFollowupDate());
                    return m;
                })
                .toList();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("staffList", staffOptions);
        res.put("doctorList", doctorOptions);
        res.put("patients", patientOptions);
        res.put("appointments", appointmentOptions);
        res.put("visits", visitOptions);
        res.put("prescriptions", prescriptionOptions);
        return res;
    }

    // =========================================================================
    // 2. THỐNG KÊ KPI SUMMARY CARDS & ĐẾM SỐ LƯỢNG THEO NHÓM (SCR-44.1 & SCR-44.2)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getSummary(Long branchId, Long userId) {
        Long effectiveBranchId = resolveAndAuthorizeBranchId(branchId, userId, false);

        List<CustomerCareTask> tasks = taskRepository.findAllByBranchWithDetails(effectiveBranchId);
        Map<Long, List<CustomerCareInteraction>> interactionsByTask = loadInteractionsMapByBranch(effectiveBranchId);
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        Long currentStaffId = userId != null
                ? staffRepository.findByUser_UserId(userId).map(StaffProfile::getStaffId).orElse(null)
                : null;

        long totalTasks = tasks.size();
        long pendingCount = 0L;
        long inProgressCount = 0L;
        long dueTodayCount = 0L;
        long overdueCount = 0L;
        long upcomingCount = 0L;
        long actionableOverdueCount = 0L;
        long actionableTodayCount = 0L;
        long actionableUpcomingCount = 0L;
        long myTasksCount = 0L;
        long retryPendingCount = 0L;
        long escalatedCount = 0L;
        long waitingDoctorResponseCount = 0L;
        long doctorRespondedCount = 0L;
        long completedCount = 0L;
        long cancelledCount = 0L;

        Map<String, Long> groupCounts = new LinkedHashMap<>();
        List<String> groups = List.of(
                "ALL", "ACTIONABLE", "TODAY", "OVERDUE", "POST_TREATMENT", "APPOINTMENT_REMINDER",
                "INSTALLMENT_REMINDER", "NO_SHOW_RECALL", "NO_SERVICE_FOLLOWUP",
                "FEEDBACK_COMPLAINT", "ESCALATED", "COMPLETED", "CANCELLED", "MY_TASKS"
        );
        groups.forEach(g -> groupCounts.put(g, 0L));

        for (CustomerCareTask t : tasks) {
            List<CustomerCareInteraction> taskInteractions = interactionsByTask.getOrDefault(t.getTaskId(), List.of());
            CustomerCareInteraction latest = taskInteractions.isEmpty() ? null : taskInteractions.get(0);

            CustomerCareTaskStatus st = t.getStatus();
            boolean isActionable = st == CustomerCareTaskStatus.PENDING || st == CustomerCareTaskStatus.IN_PROGRESS;
            boolean isActive = isActionable || st == CustomerCareTaskStatus.ESCALATED_TO_DOCTOR;

            if (st == CustomerCareTaskStatus.PENDING) pendingCount++;
            if (st == CustomerCareTaskStatus.IN_PROGRESS) inProgressCount++;
            if (st == CustomerCareTaskStatus.COMPLETED) completedCount++;
            if (st == CustomerCareTaskStatus.CANCELLED) cancelledCount++;

            if (st == CustomerCareTaskStatus.ESCALATED_TO_DOCTOR) {
                escalatedCount++;
                if (t.getDoctorResponse() != null && !t.getDoctorResponse().isBlank()) {
                    doctorRespondedCount++;
                } else {
                    waitingDoctorResponseCount++;
                }
            }

            if (isActive && t.getDueDate() != null) {
                if (t.getDueDate().isEqual(today)) {
                    dueTodayCount++;
                    if (isActionable) actionableTodayCount++;
                } else if (t.getDueDate().isBefore(today)) {
                    overdueCount++;
                    if (isActionable) actionableOverdueCount++;
                } else {
                    upcomingCount++;
                    if (isActionable) actionableUpcomingCount++;
                }
            }

            if (isActive && currentStaffId != null && isTaskAssignedToStaff(t, currentStaffId)) {
                myTasksCount++;
            }

            if (isActive && latest != null && latest.getNextRetryAt() != null) {
                retryPendingCount++;
            }

            for (String grp : groups) {
                if ("MY_TASKS".equals(grp)) {
                    if (isActive && currentStaffId != null && isTaskAssignedToStaff(t, currentStaffId)) {
                        groupCounts.put(grp, groupCounts.get(grp) + 1L);
                    }
                } else if (matchesTabGroup(t, latest, grp, today)) {
                    groupCounts.put(grp, groupCounts.get(grp) + 1L);
                }
            }
        }

        long dueOrOverdueTotal = dueTodayCount + overdueCount;
        long openAllCount = pendingCount + inProgressCount + escalatedCount;
        int completionRate = totalTasks > 0
                ? (int) Math.round((completedCount * 100.0) / totalTasks)
                : 0;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("branchId", effectiveBranchId);
        summary.put("totalTasks", totalTasks);
        summary.put("pendingCount", pendingCount);
        summary.put("inProgressCount", inProgressCount);
        summary.put("activeTasks", pendingCount + inProgressCount);
        summary.put("openAllCount", openAllCount);
        summary.put("dueOrOverdueTasks", dueOrOverdueTotal);
        summary.put("dueTodayTasks", dueTodayCount);
        summary.put("overdueTasks", overdueCount);
        summary.put("upcomingTasks", upcomingCount);
        summary.put("actionableOverdueCount", actionableOverdueCount);
        summary.put("actionableTodayCount", actionableTodayCount);
        summary.put("actionableUpcomingCount", actionableUpcomingCount);
        summary.put("myTasksCount", myTasksCount);
        summary.put("retryPendingCount", retryPendingCount);
        summary.put("escalatedTasks", escalatedCount);
        summary.put("waitingDoctorResponseCount", waitingDoctorResponseCount);
        summary.put("doctorRespondedCount", doctorRespondedCount);
        summary.put("completedTasks", completedCount);
        summary.put("cancelledTasks", cancelledCount);
        summary.put("completionRate", completionRate);
        summary.put("groupCounts", groupCounts);
        return summary;
    }

    // =========================================================================
    // 3. TÌM KIẾM, LỌC PHÂN NHÓM VÀ PHÂN TRANG CÔNG VIỆC CSKH (SCR-44.1, SCR-44.2, SCR-44.5)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerCareTaskDTO> searchTasks(
            Long branchId,
            String tabGroup,
            String status,
            Long assignedStaffId,
            Long escalatedToDoctorId,
            LocalDate fromDate,
            LocalDate toDate,
            String keyword,
            Pageable pageable,
            Long userId) {
        return searchTasks(branchId, tabGroup, status, assignedStaffId, escalatedToDoctorId,
                fromDate, toDate, keyword, null, null, null, false, pageable, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerCareTaskDTO> searchTasks(
            Long branchId,
            String tabGroup,
            String status,
            Long assignedStaffId,
            Long escalatedToDoctorId,
            LocalDate fromDate,
            LocalDate toDate,
            String keyword,
            String dueFilter,
            String careGroupFilter,
            String doctorResponseFilter,
            Boolean myTasksOnly,
            Pageable pageable,
            Long userId) {

        Long effectiveBranchId = resolveAndAuthorizeBranchId(branchId, userId, false);
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            bad("Từ ngày không được lớn hơn Đến ngày.");
        }

        List<CustomerCareTask> allTasks = taskRepository.findAllByBranchWithDetails(effectiveBranchId);
        Map<Long, List<CustomerCareInteraction>> interactionsByTask = loadInteractionsMapByBranch(effectiveBranchId);

        LocalDate today = LocalDate.now(CLINIC_ZONE);
        Long currentStaffId = Boolean.TRUE.equals(myTasksOnly) || "MY_TASKS".equalsIgnoreCase(tabGroup)
                ? (userId != null ? staffRepository.findByUser_UserId(userId).map(StaffProfile::getStaffId).orElse(null) : null)
                : null;

        List<CustomerCareTask> matchedTasks = filterTasks(
                allTasks, interactionsByTask, today,
                tabGroup, status, assignedStaffId, escalatedToDoctorId,
                fromDate, toDate, keyword, dueFilter, careGroupFilter,
                doctorResponseFilter, myTasksOnly, currentStaffId
        );

        // Sắp xếp ưu tiên: Quá hạn lên đầu, đến Hôm nay, rồi Sắp tới; sau đó theo taskId desc
        matchedTasks.sort((a, b) -> {
            int pA = resolveSortPriority(a, today);
            int pB = resolveSortPriority(b, today);
            if (pA != pB) return Integer.compare(pA, pB);
            if (a.getDueDate() != null && b.getDueDate() != null && !a.getDueDate().equals(b.getDueDate())) {
                return a.getDueDate().compareTo(b.getDueDate());
            }
            return Long.compare(b.getTaskId() != null ? b.getTaskId() : 0L, a.getTaskId() != null ? a.getTaskId() : 0L);
        });

        int total = matchedTasks.size();
        int start = (int) pageable.getOffset();
        if (start >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        int end = Math.min(start + pageable.getPageSize(), total);
        List<CustomerCareTaskDTO> pageContent = matchedTasks.subList(start, end).stream()
                .map(task -> toTaskDTO(task, interactionsByTask.getOrDefault(task.getTaskId(), List.of()), false))
                .toList();
        return new PageImpl<>(pageContent, pageable, total);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerCareTaskDTO> getCalendarTasks(
            Long branchId,
            LocalDate startDate,
            LocalDate endDate,
            Long assignedStaffId,
            Long escalatedToDoctorId,
            String careGroupFilter,
            String status,
            Boolean myTasksOnly,
            Long userId) {

        Long effectiveBranchId = resolveAndAuthorizeBranchId(branchId, userId, false);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            bad("Ngày bắt đầu không được lớn hơn ngày kết thúc.");
        }

        List<CustomerCareTask> allTasks = taskRepository.findAllByBranchWithDetails(effectiveBranchId);
        Map<Long, List<CustomerCareInteraction>> interactionsByTask = loadInteractionsMapByBranch(effectiveBranchId);
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        Long currentStaffId = Boolean.TRUE.equals(myTasksOnly)
                ? (userId != null ? staffRepository.findByUser_UserId(userId).map(StaffProfile::getStaffId).orElse(null) : null)
                : null;

        List<CustomerCareTask> matched = filterTasks(
                allTasks, interactionsByTask, today,
                "ALL", status, assignedStaffId, escalatedToDoctorId,
                startDate, endDate, null, null, careGroupFilter,
                null, myTasksOnly, currentStaffId
        );

        return matched.stream()
                .map(task -> toTaskDTO(task, interactionsByTask.getOrDefault(task.getTaskId(), List.of()), false))
                .toList();
    }

    private List<CustomerCareTask> filterTasks(
            List<CustomerCareTask> allTasks,
            Map<Long, List<CustomerCareInteraction>> interactionsByTask,
            LocalDate today,
            String tabGroup,
            String status,
            Long assignedStaffId,
            Long escalatedToDoctorId,
            LocalDate fromDate,
            LocalDate toDate,
            String keyword,
            String dueFilter,
            String careGroupFilter,
            String doctorResponseFilter,
            Boolean myTasksOnly,
            Long currentStaffId) {

        String normGroup = trimToNull(tabGroup) == null ? "ALL" : tabGroup.trim().toUpperCase(Locale.ROOT);
        String normStatus = trimToNull(status) == null ? null : status.trim().toUpperCase(Locale.ROOT);
        String normKeyword = trimToNull(keyword) == null ? null : keyword.trim().toLowerCase(Locale.ROOT);
        String normDue = trimToNull(dueFilter) == null ? null : dueFilter.trim().toUpperCase(Locale.ROOT);
        String normCareGroup = trimToNull(careGroupFilter) == null ? null : careGroupFilter.trim().toUpperCase(Locale.ROOT);
        String normDocResp = trimToNull(doctorResponseFilter) == null ? null : doctorResponseFilter.trim().toUpperCase(Locale.ROOT);

        List<CustomerCareTask> matchedTasks = new ArrayList<>();
        for (CustomerCareTask task : allTasks) {
            List<CustomerCareInteraction> taskInteractions = interactionsByTask.getOrDefault(task.getTaskId(), List.of());
            CustomerCareInteraction latest = taskInteractions.isEmpty() ? null : taskInteractions.get(0);

            if ("MY_TASKS".equals(normGroup)) {
                boolean isActive = task.getStatus() != CustomerCareTaskStatus.COMPLETED
                        && task.getStatus() != CustomerCareTaskStatus.CANCELLED;
                if (!isActive || currentStaffId == null || !isTaskAssignedToStaff(task, currentStaffId)) {
                    continue;
                }
            } else if (!matchesTabGroup(task, latest, normGroup, today)) {
                continue;
            }

            if (Boolean.TRUE.equals(myTasksOnly)) {
                if (currentStaffId == null || !isTaskAssignedToStaff(task, currentStaffId)) {
                    continue;
                }
            }
            if (normCareGroup != null && !"ALL".equals(normCareGroup)) {
                if (!matchesTabGroup(task, latest, normCareGroup, today)) {
                    continue;
                }
            }
            if (normDue != null && !"ALL".equals(normDue)) {
                String bucket = resolveDueBucket(task, today);
                if (!normDue.equals(bucket)) {
                    continue;
                }
            }
            if (normDocResp != null && !"ALL".equals(normDocResp)) {
                boolean hasResp = task.getDoctorResponse() != null && !task.getDoctorResponse().isBlank();
                if ("WAITING".equals(normDocResp) && hasResp) continue;
                if ("RESPONDED".equals(normDocResp) && !hasResp) continue;
            }
            if (normStatus != null && !task.getStatus().name().equalsIgnoreCase(normStatus)) {
                continue;
            }
            if (assignedStaffId != null && assignedStaffId > 0) {
                if (task.getAssignedStaff() == null || !assignedStaffId.equals(task.getAssignedStaff().getStaffId())) {
                    continue;
                }
            }
            if (escalatedToDoctorId != null && escalatedToDoctorId > 0) {
                if (task.getEscalatedToDoctor() == null || !escalatedToDoctorId.equals(task.getEscalatedToDoctor().getStaffId())) {
                    continue;
                }
            }
            if (fromDate != null && task.getDueDate() != null && task.getDueDate().isBefore(fromDate)) {
                continue;
            }
            if (toDate != null && task.getDueDate() != null && task.getDueDate().isAfter(toDate)) {
                continue;
            }
            if (normKeyword != null && !matchesKeyword(task, normKeyword)) {
                continue;
            }

            matchedTasks.add(task);
        }
        return matchedTasks;
    }

    private boolean isTaskAssignedToStaff(CustomerCareTask t, Long staffId) {
        if (staffId == null) return false;
        boolean isAssignee = t.getAssignedStaff() != null && staffId.equals(t.getAssignedStaff().getStaffId());
        boolean isEscalatedDoc = t.getEscalatedToDoctor() != null && staffId.equals(t.getEscalatedToDoctor().getStaffId());
        return isAssignee || isEscalatedDoc;
    }

    private String resolveDueBucket(CustomerCareTask t, LocalDate today) {
        if (t.getStatus() == CustomerCareTaskStatus.COMPLETED) return "COMPLETED";
        if (t.getStatus() == CustomerCareTaskStatus.CANCELLED) return "CANCELLED";
        if (t.getDueDate() == null) return "TODAY";
        if (t.getDueDate().isBefore(today)) return "OVERDUE";
        if (t.getDueDate().isEqual(today)) return "TODAY";
        return "UPCOMING";
    }

    private int resolveSortPriority(CustomerCareTask t, LocalDate today) {
        return switch (resolveDueBucket(t, today)) {
            case "OVERDUE" -> 1;
            case "TODAY" -> 2;
            case "UPCOMING" -> 3;
            case "COMPLETED" -> 4;
            default -> 5;
        };
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerCareTaskDTO getTaskDetail(Long taskId, Long userId) {
        if (taskId == null || taskId <= 0) {
            bad("Mã công việc CSKH không hợp lệ.");
        }
        CustomerCareTask task = taskRepository.findByIdWithDetails(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy công việc CSKH."));
        assertCanAccessTask(task, userId);

        List<CustomerCareInteraction> interactions = interactionRepository.findByTaskIdWithDetails(taskId);
        return toTaskDTO(task, interactions, true);
    }

    // =========================================================================
    // 4. LẬP LỊCH / PHÂN CÔNG THỦ CÔNG & TỰ ĐỘNG TỔNG HỢP CÔNG VIỆC (SCR-44.3)
    // =========================================================================

    @Override
    @Transactional
    public CustomerCareTaskDTO createTask(CustomerCareCreateDTO dto, Long userId) {
        if (dto == null) {
            bad("Dữ liệu lập lịch CSKH không được để trống.");
        }
        Long effectiveBranchId = resolveAndAuthorizeBranchId(dto.getBranchId(), userId, true);

        if (dto.getProfileId() == null || dto.getProfileId() <= 0) {
            bad("Vui lòng chọn hồ sơ bệnh nhân cần chăm sóc.");
        }
        if (dto.getDueDate() == null) {
            bad("Vui lòng chọn hạn hoàn thành (due_date) cho công việc CSKH.");
        }

        Branch branch = branchRepository.findById(effectiveBranchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        PatientProfile patient = patientRepository.findById(dto.getProfileId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));

        String requestedGroup = trimToNull(dto.getCareGroup());
        if (requestedGroup == null) {
            requestedGroup = trimToNull(dto.getTaskType());
        }
        if (requestedGroup == null) {
            bad("Vui lòng chọn phân nhóm / loại công việc chăm sóc khách hàng.");
        }

        CustomerCareTaskType resolvedType = mapCareGroupToDbType(requestedGroup);
        String formattedNotes = formatNotesWithGroupTag(requestedGroup, dto.getNotes());

        // Kiểm tra chống tạo trùng công việc CSKH đang hoạt động (PENDING, IN_PROGRESS, ESCALATED_TO_DOCTOR)
        assertNoActiveDuplicateTask(effectiveBranchId, dto.getProfileId(), requestedGroup, resolvedType,
                dto.getVisitId(), dto.getAppointmentId(), dto.getPrescriptionId(), dto.getDueDate());

        CustomerCareTask task = new CustomerCareTask();
        task.setTaskCode(generateUniqueTaskCode());
        task.setBranch(branch);
        task.setPatientProfile(patient);
        task.setTaskType(resolvedType);
        task.setDueDate(dto.getDueDate());
        task.setStatus(CustomerCareTaskStatus.PENDING);
        task.setNotes(formattedNotes);

        if (dto.getAssignedStaffId() != null && dto.getAssignedStaffId() > 0) {
            StaffProfile staff = staffRepository.findById(dto.getAssignedStaffId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhân viên được phân công."));
            task.setAssignedStaff(staff);
        } else {
            staffRepository.findByUser_UserId(userId).ifPresent(task::setAssignedStaff);
        }

        attachOptionalLinks(task, dto.getVisitId(), dto.getAppointmentId(), dto.getPrescriptionId());

        CustomerCareTask saved = taskRepository.save(task);
        CustomerCareTask resultTask = saved != null ? saved : task;
        if (notificationService != null) {
            String actorName = resolveActorDisplayName(userId);
            notificationService.notifyTaskCreated(resultTask, userId, actorName, false);
        }
        return toTaskDTO(resultTask, List.of(), true);
    }

    private void assertNoActiveDuplicateTask(
            Long branchId,
            Long profileId,
            String requestedGroup,
            CustomerCareTaskType resolvedType,
            Long visitId,
            Long appointmentId,
            Long prescriptionId,
            LocalDate dueDate) {

        List<CustomerCareTask> existingForPatient = taskRepository.findByPatientProfileIdWithDetails(profileId);
        if (existingForPatient == null || existingForPatient.isEmpty()) {
            return;
        }

        String normGroup = requestedGroup.trim().toUpperCase(Locale.ROOT);
        if ("POST_TREATMENT".equals(normGroup)) {
            normGroup = "POST_TREATMENT_FOLLOWUP";
        }

        for (CustomerCareTask ex : existingForPatient) {
            if (ex.getStatus() == CustomerCareTaskStatus.COMPLETED || ex.getStatus() == CustomerCareTaskStatus.CANCELLED) {
                continue;
            }
            if (ex.getBranch() != null && branchId != null && !branchId.equals(ex.getBranch().getBranchId())) {
                continue;
            }
            String exGroup = resolveCareGroup(ex);
            boolean sameGroupOrType = normGroup.equalsIgnoreCase(exGroup) || resolvedType == ex.getTaskType();
            if (!sameGroupOrType) {
                continue;
            }

            if (visitId != null && visitId > 0 && ex.getVisit() != null && visitId.equals(ex.getVisit().getVisitId())) {
                bad("Bệnh nhân đã có công việc CSKH đang hoạt động (" + ex.getTaskCode() + ") cho ca khám này. Không thể tạo trùng.");
            }
            if (appointmentId != null && appointmentId > 0 && ex.getAppointment() != null && appointmentId.equals(ex.getAppointment().getAppointmentId())) {
                bad("Bệnh nhân đã có công việc CSKH đang hoạt động (" + ex.getTaskCode() + ") cho lịch hẹn này. Không thể tạo trùng.");
            }
            if (prescriptionId != null && prescriptionId > 0 && ex.getPrescription() != null && prescriptionId.equals(ex.getPrescription().getPrescriptionId())) {
                bad("Bệnh nhân đã có công việc CSKH đang hoạt động (" + ex.getTaskCode() + ") cho đơn thuốc này. Không thể tạo trùng.");
            }
            if ((visitId == null || visitId <= 0)
                    && (appointmentId == null || appointmentId <= 0)
                    && (prescriptionId == null || prescriptionId <= 0)
                    && dueDate != null && dueDate.equals(ex.getDueDate())
                    && normGroup.equalsIgnoreCase(exGroup)) {
                bad("Bệnh nhân đã có công việc CSKH cùng phân nhóm và cùng hạn hoàn thành (" + ex.getTaskCode() + "). Không thể tạo trùng.");
            }
        }
    }

    @Override
    @Transactional
    public CustomerCareTaskDTO updateTask(Long taskId, CustomerCareCreateDTO dto, Long userId) {
        if (taskId == null || taskId <= 0) {
            bad("ID công việc không hợp lệ.");
        }
        CustomerCareTask task = taskRepository.findByIdWithDetails(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy công việc CSKH."));
        assertCanAccessTask(task, userId);
        assertTaskOpenForProcessing(task, "chỉnh sửa / phân công lại");

        if (dto.getProfileId() != null && dto.getProfileId() > 0) {
            PatientProfile patient = patientRepository.findById(dto.getProfileId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));
            task.setPatientProfile(patient);
        }
        if (dto.getDueDate() != null) {
            task.setDueDate(dto.getDueDate());
        }
        String requestedGroup = trimToNull(dto.getCareGroup());
        if (requestedGroup == null) {
            requestedGroup = trimToNull(dto.getTaskType());
        }
        if (requestedGroup != null) {
            task.setTaskType(mapCareGroupToDbType(requestedGroup));
            task.setNotes(formatNotesWithGroupTag(requestedGroup, dto.getNotes()));
        } else if (dto.getNotes() != null) {
            task.setNotes(dto.getNotes().trim());
        }

        if (dto.getAssignedStaffId() != null) {
            if (dto.getAssignedStaffId() > 0) {
                StaffProfile staff = staffRepository.findById(dto.getAssignedStaffId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhân viên phụ trách."));
                task.setAssignedStaff(staff);
            } else {
                task.setAssignedStaff(null);
            }
        }

        attachOptionalLinks(task, dto.getVisitId(), dto.getAppointmentId(), dto.getPrescriptionId());

        CustomerCareTask saved = taskRepository.save(task);
        CustomerCareTask resultTask = saved != null ? saved : task;
        if (notificationService != null) {
            notificationService.notifyTaskUpdated(resultTask, userId, resolveActorDisplayName(userId));
        }
        List<CustomerCareInteraction> interactions = interactionRepository.findByTaskIdWithDetails(taskId);
        return toTaskDTO(resultTask, interactions, true);
    }

    @Override
    @Transactional
    public Map<String, Object> autoGenerateTasks(Long branchId, Long userId) {
        Long effectiveBranchId = resolveAndAuthorizeBranchId(branchId, userId, true);

        Branch branch = branchRepository.findById(effectiveBranchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));

        StaffProfile defaultStaff = resolveDefaultCareStaff(userId, effectiveBranchId);
        LocalDate today = LocalDate.now();
        int createdPostTreatment = 0;
        int createdMedication = 0;
        int createdAppointment = 0;
        int createdNoShow = 0;

        // 1. Quét các ca khám đã COMPLETED hợp lệ tại cơ sở nhưng chưa có task POST_TREATMENT_FOLLOWUP
        List<Visit> completedVisits = taskRepository.findCompletedVisitsByBranchForCare(effectiveBranchId);
        for (Visit v : completedVisits) {
            if (v.getPatientProfile() == null || v.getPatientProfile().getProfileId() == null) {
                continue;
            }
            if (!taskRepository.existsByVisit_VisitIdAndTaskType(v.getVisitId(), CustomerCareTaskType.POST_TREATMENT_FOLLOWUP)) {
                CustomerCareTask t = new CustomerCareTask();
                t.setTaskCode(generateUniqueTaskCode());
                t.setBranch(branch);
                t.setPatientProfile(v.getPatientProfile());
                t.setTaskType(CustomerCareTaskType.POST_TREATMENT_FOLLOWUP);
                t.setVisit(v);
                t.setAssignedStaff(defaultStaff);
                LocalDate visitDate = v.getCheckInAt() != null ? v.getCheckInAt().toLocalDate() : today;
                t.setDueDate(visitDate.plusDays(1).isBefore(today) ? today : visitDate.plusDays(1));
                t.setStatus(CustomerCareTaskStatus.PENDING);

                StringBuilder noteBuilder = new StringBuilder("Hỏi thăm sức khỏe bệnh nhân sau ca khám ")
                        .append(v.getVisitCode());
                if (v.getInitialSymptoms() != null && !v.getInitialSymptoms().isBlank()) {
                    noteBuilder.append(" (").append(v.getInitialSymptoms().trim()).append(")");
                }
                if (v.getNotes() != null && !v.getNotes().isBlank()) {
                    noteBuilder.append(" — Ghi chú điều trị: ").append(v.getNotes().trim());
                }
                t.setNotes(noteBuilder.toString());
                taskRepository.save(t);
                createdPostTreatment++;
            }
        }

        // 2. Quét các đơn thuốc hợp lệ tại cơ sở nhưng chưa có task MEDICATION_REMINDER
        List<Prescription> prescriptions = taskRepository.findValidPrescriptionsByBranchForCare(effectiveBranchId);
        for (Prescription pr : prescriptions) {
            if (pr.getPatientProfile() == null || pr.getPatientProfile().getProfileId() == null) {
                continue;
            }
            if (!taskRepository.existsByPrescription_PrescriptionIdAndTaskType(pr.getPrescriptionId(), CustomerCareTaskType.MEDICATION_REMINDER)) {
                CustomerCareTask t = new CustomerCareTask();
                t.setTaskCode(generateUniqueTaskCode());
                t.setBranch(branch);
                t.setPatientProfile(pr.getPatientProfile());
                t.setTaskType(CustomerCareTaskType.MEDICATION_REMINDER);
                t.setPrescription(pr);
                t.setVisit(pr.getVisit());
                t.setAssignedStaff(defaultStaff);
                t.setDueDate(pr.getFollowupDate() != null ? pr.getFollowupDate() : today);
                t.setStatus(CustomerCareTaskStatus.PENDING);
                String note = "Nhắc bệnh nhân dùng thuốc theo đơn " + pr.getPrescriptionCode()
                        + " — Chẩn đoán: " + pr.getDiagnosisText();
                if (pr.getAdviceNotes() != null && !pr.getAdviceNotes().isBlank()) {
                    note += " — Lời dặn: " + pr.getAdviceNotes().trim();
                }
                t.setNotes(note);
                taskRepository.save(t);
                createdMedication++;
            }
        }

        // 3. Quét các lịch hẹn hợp lệ tại cơ sở để tạo nhắc hẹn (APPOINTMENT_REMINDER) hoặc gọi lại lịch hẹn lỡ (NO_SHOW_RECALL)
        List<Appointment> appointments = appointmentRepository.findByBranchWithDetails(effectiveBranchId);
        for (Appointment a : appointments) {
            if (a.getPatientProfile() == null || a.getPatientProfile().getProfileId() == null || a.getStatus() == null) {
                continue;
            }
            if (a.getStatus() == AppointmentStatus.CONFIRMED || a.getStatus() == AppointmentStatus.PENDING) {
                if (!taskRepository.existsByAppointment_AppointmentIdAndTaskType(a.getAppointmentId(), CustomerCareTaskType.APPOINTMENT_REMINDER)) {
                    CustomerCareTask t = new CustomerCareTask();
                    t.setTaskCode(generateUniqueTaskCode());
                    t.setBranch(branch);
                    t.setPatientProfile(a.getPatientProfile());
                    t.setTaskType(CustomerCareTaskType.APPOINTMENT_REMINDER);
                    t.setAppointment(a);
                    t.setAssignedStaff(defaultStaff);
                    LocalDate due = a.getAppointmentDate() != null ? a.getAppointmentDate().minusDays(1) : today;
                    t.setDueDate(due);
                    t.setStatus(CustomerCareTaskStatus.PENDING);
                    t.setNotes("Nhắc lịch hẹn khám mã " + a.getAppointmentCode()
                            + " ngày " + a.getAppointmentDate()
                            + (a.getService() != null ? " (" + a.getService().getServiceName() + ")" : ""));
                    taskRepository.save(t);
                    createdAppointment++;
                }
            } else if (a.getStatus() == AppointmentStatus.NO_SHOW) {
                if (!taskRepository.existsByAppointment_AppointmentIdAndTaskType(a.getAppointmentId(), CustomerCareTaskType.NO_SHOW_RECALL)) {
                    CustomerCareTask t = new CustomerCareTask();
                    t.setTaskCode(generateUniqueTaskCode());
                    t.setBranch(branch);
                    t.setPatientProfile(a.getPatientProfile());
                    t.setTaskType(CustomerCareTaskType.NO_SHOW_RECALL);
                    t.setAppointment(a);
                    t.setAssignedStaff(defaultStaff);
                    t.setDueDate(today);
                    t.setStatus(CustomerCareTaskStatus.PENDING);
                    t.setNotes("Liên hệ đặt lại lịch cho bệnh nhân lỡ hẹn mã " + a.getAppointmentCode());
                    taskRepository.save(t);
                    createdNoShow++;
                }
            }
        }

        int totalCreated = createdPostTreatment + createdMedication + createdAppointment + createdNoShow;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCreated", totalCreated);
        result.put("createdPostTreatment", createdPostTreatment);
        result.put("createdMedication", createdMedication);
        result.put("createdAppointment", createdAppointment);
        result.put("createdNoShow", createdNoShow);
        result.put("message", totalCreated > 0
                ? "Đã tự động tổng hợp và tạo mới " + totalCreated + " công việc CSKH tại cơ sở " + branch.getBranchName() + "."
                : "Tất cả ca khám, đơn thuốc và lịch hẹn hợp lệ tại cơ sở " + branch.getBranchName() + " đã có đầy đủ công việc CSKH.");
        return result;
    }

    // =========================================================================
    // 5. GHI KẾT QUẢ LIÊN HỆ & HẸN GỌI LẠI (SCR-44.4)
    // =========================================================================

    @Override
    @Transactional
    public CustomerCareTaskDTO recordInteraction(Long taskId, CustomerCareInteractionCreateDTO dto, Long userId) {
        if (taskId == null || taskId <= 0) {
            bad("Mã công việc CSKH không hợp lệ.");
        }
        if (dto == null) {
            bad("Dữ liệu kết quả liên hệ không được để trống.");
        }

        CustomerCareTask task = taskRepository.findByIdWithDetails(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy công việc CSKH."));
        assertCanAccessTask(task, userId);
        assertTaskOpenForProcessing(task, "ghi nhận thêm kết quả liên hệ");

        InteractionChannel channel = parseChannel(dto.getChannel());
        ContactResult contactResult = parseContactResult(dto.getContactResult());

        String notes = trimToNull(dto.getNotes());
        if (notes == null || notes.length() < 3) {
            bad("Nội dung ghi chú kết quả liên hệ phải có ít nhất 3 ký tự.");
        }
        if (notes.length() > 2000) {
            bad("Nội dung ghi chú kết quả liên hệ tối đa 2000 ký tự.");
        }

        if (contactResult == ContactResult.NO_ANSWER || contactResult == ContactResult.BUSY) {
            if (dto.getNextRetryAt() == null) {
                bad("Khi kết quả liên hệ là Không bắt máy (NO_ANSWER) hoặc Máy bận (BUSY), bắt buộc phải nhập Thời gian hẹn gọi lại (next_retry_at).");
            }
        }

        StaffProfile performer = null;
        if (dto.getStaffId() != null && dto.getStaffId() > 0) {
            performer = staffRepository.findById(dto.getStaffId()).orElse(null);
        }
        if (performer == null && userId != null) {
            performer = staffRepository.findByUser_UserId(userId).orElse(null);
        }
        if (performer == null && task.getAssignedStaff() != null) {
            performer = task.getAssignedStaff();
        }
        if (performer == null) {
            performer = resolveDefaultCareStaff(userId, task.getBranch().getBranchId());
        }

        CustomerCareInteraction interaction = new CustomerCareInteraction();
        interaction.setTask(task);
        interaction.setPatientProfile(task.getPatientProfile());
        interaction.setStaff(performer);
        interaction.setChannel(channel);
        interaction.setContactResult(contactResult);
        interaction.setNotes(notes);
        interaction.setNextRetryAt(dto.getNextRetryAt());

        interactionRepository.save(interaction);

        String requestedStatus = trimToNull(dto.getNextStatus());
        if (requestedStatus != null) {
            task.setStatus(parseTaskStatus(requestedStatus));
        } else {
            if (contactResult == ContactResult.NO_ANSWER || contactResult == ContactResult.BUSY) {
                task.setStatus(CustomerCareTaskStatus.IN_PROGRESS);
                if (dto.getNextRetryAt() != null) {
                    task.setDueDate(dto.getNextRetryAt().toLocalDate());
                }
            } else if (contactResult == ContactResult.CONNECTED || contactResult == ContactResult.SCHEDULED_APPOINTMENT) {
                task.setStatus(CustomerCareTaskStatus.COMPLETED);
            }
        }

        if (task.getAssignedStaff() == null && performer != null) {
            task.setAssignedStaff(performer);
        }

        taskRepository.save(task);

        if (notificationService != null) {
            notificationService.notifyTaskUpdated(task, userId, resolveActorDisplayName(userId));
        }

        List<CustomerCareInteraction> updatedInteractions = interactionRepository.findByTaskIdWithDetails(taskId);
        return toTaskDTO(task, updatedInteractions, true);
    }

    // =========================================================================
    // 6. CHUYỂN BÁC SĨ HỖ TRỢ CHUYÊN MÔN & NHẬP PHẢN HỒI BÁC SĨ (SCR-44.5)
    // =========================================================================

    @Override
    @Transactional
    public CustomerCareTaskDTO escalateToDoctor(Long taskId, CustomerCareEscalateDTO dto, Long userId) {
        if (taskId == null || taskId <= 0) {
            bad("Mã công việc CSKH không hợp lệ.");
        }
        if (dto == null || dto.getEscalatedToDoctorId() == null || dto.getEscalatedToDoctorId() <= 0) {
            bad("Vui lòng chọn Bác sĩ tiếp nhận hỗ trợ chuyên môn.");
        }

        String reason = trimToNull(dto.getEscalationReason());
        if (reason == null || reason.length() < 5) {
            bad("Vui lòng nhập triệu chứng / lý do cần Bác sĩ tư vấn chuyên môn (ít nhất 5 ký tự).");
        }
        if (reason.length() > 2000) {
            bad("Lý do chuyển Bác sĩ tối đa 2000 ký tự.");
        }

        CustomerCareTask task = taskRepository.findByIdWithDetails(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy công việc CSKH."));
        assertCanAccessTask(task, userId);
        assertTaskOpenForProcessing(task, "chuyển Bác sĩ");

        StaffProfile doctor = staffRepository.findById(dto.getEscalatedToDoctorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ Bác sĩ được chọn."));
        if (doctor.getStatus() != StaffStatus.ACTIVE) {
            bad("Bác sĩ được chọn hiện không ở trạng thái hoạt động (ACTIVE).");
        }

        task.setEscalatedToDoctor(doctor);
        task.setEscalatedAt(LocalDateTime.now(CLINIC_ZONE));
        task.setStatus(CustomerCareTaskStatus.ESCALATED_TO_DOCTOR);
        task.setEscalationReason(reason);
        task.setEscalatedByUserId(userId);
        // Bảo toàn task.notes (ghi chú gốc / lời dặn ban đầu) - không ghi đè bằng lý do chuyển BS!

        taskRepository.save(task);

        // Ghi nhận mốc lịch sử chuyển Bác sĩ vào customer_care_interactions để không mất dữ liệu các lần chuyển trước
        StaffProfile performer = userId != null ? staffRepository.findByUser_UserId(userId).orElse(null) : null;
        if (performer == null) {
            performer = task.getAssignedStaff() != null ? task.getAssignedStaff() : resolveDefaultCareStaff(userId, task.getBranch().getBranchId());
        }
        if (performer != null) {
            CustomerCareInteraction escalationLog = new CustomerCareInteraction();
            escalationLog.setTask(task);
            escalationLog.setPatientProfile(task.getPatientProfile());
            escalationLog.setStaff(performer);
            escalationLog.setChannel(InteractionChannel.IN_PERSON);
            escalationLog.setContactResult(ContactResult.CONNECTED);
            escalationLog.setNotes("[Chuyển BS: " + doctor.getFullName() + "] " + reason);
            interactionRepository.save(escalationLog);
        }

        if (notificationService != null) {
            notificationService.notifyEscalatedToDoctor(task, doctor, userId, resolveActorDisplayName(userId));
        }

        List<CustomerCareInteraction> interactions = interactionRepository.findByTaskIdWithDetails(taskId);
        return toTaskDTO(task, interactions, true);
    }

    @Override
    @Transactional
    public CustomerCareTaskDTO submitDoctorResponse(Long taskId, CustomerCareEscalateDTO dto, Long userId) {
        if (taskId == null || taskId <= 0) {
            bad("Mã công việc CSKH không hợp lệ.");
        }
        if (dto == null) {
            bad("Dữ liệu phản hồi chuyên môn không hợp lệ.");
        }

        String responseText = trimToNull(dto.getDoctorResponse());
        if (responseText == null || responseText.length() < 5) {
            bad("Vui lòng nhập nội dung phản hồi / chỉ định chuyên môn của Bác sĩ (ít nhất 5 ký tự).");
        }
        if (responseText.length() > 2000) {
            bad("Nội dung phản hồi chuyên môn tối đa 2000 ký tự.");
        }

        CustomerCareTask task = taskRepository.findByIdWithDetails(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy công việc CSKH."));

        String careRole = assertCanAccessTask(task, userId);
        if ("LE_TAN".equals(careRole)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Chỉ Bác sĩ hoặc Admin tại cơ sở mới có quyền nhập phản hồi / chỉ định chuyên môn (doctor_response)."
            );
        }
        assertTaskOpenForProcessing(task, "cập nhật phản hồi chuyên môn");

        task.setDoctorResponse(responseText);
        if (task.getEscalatedAt() == null) {
            task.setEscalatedAt(LocalDateTime.now());
        }
        if (task.getEscalatedToDoctor() == null) {
            if (dto.getEscalatedToDoctorId() != null && dto.getEscalatedToDoctorId() > 0) {
                staffRepository.findById(dto.getEscalatedToDoctorId()).ifPresent(task::setEscalatedToDoctor);
            } else {
                staffRepository.findByUser_UserId(userId).ifPresent(task::setEscalatedToDoctor);
            }
        }

        String nextStatus = trimToNull(dto.getNextStatus());
        if (nextStatus != null) {
            task.setStatus(parseTaskStatus(nextStatus));
        }

        taskRepository.save(task);

        // Ghi nhận mốc lịch sử phản hồi của Bác sĩ vào customer_care_interactions (không ghi đè lịch sử gọi cũ)
        StaffProfile responder = userId != null ? staffRepository.findByUser_UserId(userId).orElse(null) : null;
        if (responder == null) {
            responder = task.getEscalatedToDoctor() != null ? task.getEscalatedToDoctor() : resolveDefaultCareStaff(userId, task.getBranch().getBranchId());
        }
        if (responder != null) {
            CustomerCareInteraction docLog = new CustomerCareInteraction();
            docLog.setTask(task);
            docLog.setPatientProfile(task.getPatientProfile());
            docLog.setStaff(responder);
            docLog.setChannel(InteractionChannel.IN_PERSON);
            docLog.setContactResult(ContactResult.CONNECTED);
            docLog.setNotes("[Phản hồi BS - " + formatStatusLabel(task.getStatus()) + "] " + responseText);
            interactionRepository.save(docLog);
        }

        if (notificationService != null) {
            String docName = responder != null ? responder.getFullName() : resolveActorDisplayName(userId);
            notificationService.notifyDoctorResponded(task, userId, docName);
        }

        List<CustomerCareInteraction> interactions = interactionRepository.findByTaskIdWithDetails(taskId);
        return toTaskDTO(task, interactions, true);
    }

    /**
     * Khóa xử lý đối với công việc đã đóng (COMPLETED / CANCELLED).
     * Chỉ chặn đúng công việc có taskId tương ứng, không ảnh hưởng các công việc khác của cùng bệnh nhân.
     */
    private void assertTaskOpenForProcessing(CustomerCareTask task, String actionLabel) {
        if (task == null || task.getStatus() == null) return;
        if (task.getStatus() == CustomerCareTaskStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Công việc " + task.getTaskCode() + " đã hoàn thành và được đóng, không thể " + actionLabel
                            + ". Nếu thực sự cần, vui lòng liên hệ Quản trị viên để mở lại công việc.");
        }
        if (task.getStatus() == CustomerCareTaskStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Công việc " + task.getTaskCode() + " đã bị hủy, không thể " + actionLabel + ".");
        }
    }

    // =========================================================================
    // 6B. MỞ LẠI CÔNG VIỆC ĐÃ HOÀN THÀNH (CHỈ ADMIN) - CÓ LÝ DO & AUDIT LOG
    // =========================================================================

    @Override
    @Transactional
    public CustomerCareTaskDTO reopenTask(Long taskId, String reason, Long userId, String ipAddress) {
        if (taskId == null || taskId <= 0) {
            bad("Mã công việc CSKH không hợp lệ.");
        }
        String safeReason = trimToNull(reason);
        if (safeReason == null || safeReason.length() < 5) {
            bad("Vui lòng nhập lý do mở lại công việc (ít nhất 5 ký tự).");
        }
        if (safeReason.length() > 1000) {
            bad("Lý do mở lại công việc tối đa 1000 ký tự.");
        }

        CustomerCareTask task = taskRepository.findByIdWithDetails(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy công việc CSKH."));
        String careRole = assertCanAccessTask(task, userId);
        if (!"ADMIN".equals(careRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Chỉ Quản trị viên (Admin) mới có quyền mở lại công việc CSKH đã hoàn thành.");
        }
        if (task.getStatus() != CustomerCareTaskStatus.COMPLETED) {
            bad("Chỉ có thể mở lại công việc đang ở trạng thái Đã hoàn thành.");
        }

        String oldStatus = task.getStatus().name();
        LocalDateTime reopenedAt = LocalDateTime.now(CLINIC_ZONE);
        task.setStatus(CustomerCareTaskStatus.IN_PROGRESS);
        taskRepository.save(task);

        String actorName = resolveActorDisplayName(userId);

        // Ghi nhận mốc mở lại vào lịch sử công việc (bản ghi độc lập, không ghi đè lịch sử cũ)
        StaffProfile performer = userId != null ? staffRepository.findByUser_UserId(userId).orElse(null) : null;
        if (performer == null) {
            performer = task.getAssignedStaff();
        }
        if (performer != null) {
            CustomerCareInteraction reopenLog = new CustomerCareInteraction();
            reopenLog.setTask(task);
            reopenLog.setPatientProfile(task.getPatientProfile());
            reopenLog.setStaff(performer);
            reopenLog.setChannel(InteractionChannel.IN_PERSON);
            reopenLog.setContactResult(ContactResult.CONNECTED);
            reopenLog.setNotes("[Mở lại công việc - " + actorName + "] " + safeReason);
            interactionRepository.save(reopenLog);
        }

        if (systemAuditLogService != null) {
            User actor = userId != null ? userRepository.findById(userId).orElse(null) : null;
            Map<String, Object> oldVal = new LinkedHashMap<>();
            oldVal.put("taskCode", task.getTaskCode());
            oldVal.put("status", oldStatus);
            Map<String, Object> newVal = new LinkedHashMap<>();
            newVal.put("taskCode", task.getTaskCode());
            newVal.put("status", task.getStatus().name());
            newVal.put("reopenReason", safeReason);
            newVal.put("reopenedBy", actorName);
            newVal.put("reopenedAt", reopenedAt.toString());
            systemAuditLogService.logAction(actor, task.getBranch(), "CUSTOMER_CARE_TASK_REOPEN",
                    "customer_care_tasks", task.getTaskId(), ipAddress, oldVal, newVal);
        }

        if (notificationService != null) {
            notificationService.notifyTaskReopened(task, safeReason, userId, actorName);
        }

        List<CustomerCareInteraction> interactions = interactionRepository.findByTaskIdWithDetails(taskId);
        return toTaskDTO(task, interactions, true);
    }

    // =========================================================================
    // 7. LỊCH SỬ CHĂM SÓC BỆNH NHÂN TIMELINE (SCR-44.6) & TÓM TẮT CA KHÁM LÂM SÀNG
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerCareInteractionDTO> searchInteractions(
            Long branchId,
            Long profileId,
            String keyword,
            String timeRange,
            LocalDate fromDate,
            LocalDate toDate,
            Long staffId,
            Long doctorId,
            String channel,
            String contactResult,
            String careGroupFilter,
            Pageable pageable,
            Long userId) {

        String careRole = requireCareRole(userId);
        Long effectiveBranchId = resolveAndAuthorizeBranchId(branchId, userId, false);

        List<CustomerCareInteraction> list = interactionRepository.findInteractionsWithDetails(effectiveBranchId, profileId);

        if (!"ADMIN".equals(careRole)) {
            Set<Long> allowedBranchIds = resolveAllowedBranches(userId, careRole).stream()
                    .map(Branch::getBranchId)
                    .collect(Collectors.toSet());
            list = list.stream()
                    .filter(i -> i.getTask() != null && i.getTask().getBranch() != null
                            && allowedBranchIds.contains(i.getTask().getBranch().getBranchId()))
                    .toList();
        }

        // Tính toán khoảng thời gian theo múi giờ Việt Nam (Asia/Ho_Chi_Minh)
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        LocalDate rangeStart = null;
        LocalDate rangeEnd = null;

        String tr = timeRange != null ? timeRange.trim().toUpperCase(Locale.ROOT) : "ALL";
        switch (tr) {
            case "TODAY" -> {
                rangeStart = today;
                rangeEnd = today;
            }
            case "YESTERDAY" -> {
                rangeStart = today.minusDays(1);
                rangeEnd = today.minusDays(1);
            }
            case "THIS_WEEK" -> {
                int daysFromMon = (today.getDayOfWeek().getValue() + 6) % 7;
                rangeStart = today.minusDays(daysFromMon);
                rangeEnd = rangeStart.plusDays(6);
            }
            case "LAST_WEEK" -> {
                int daysFromMon = (today.getDayOfWeek().getValue() + 6) % 7;
                rangeStart = today.minusDays(daysFromMon + 7);
                rangeEnd = rangeStart.plusDays(6);
            }
            case "THIS_MONTH" -> {
                rangeStart = today.withDayOfMonth(1);
                rangeEnd = today.withDayOfMonth(today.lengthOfMonth());
            }
            case "LAST_MONTH" -> {
                LocalDate lastMonth = today.minusMonths(1);
                rangeStart = lastMonth.withDayOfMonth(1);
                rangeEnd = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth());
            }
            case "CUSTOM" -> {
                rangeStart = fromDate;
                rangeEnd = toDate;
            }
            default -> {
                rangeStart = fromDate;
                rangeEnd = toDate;
            }
        }

        if (rangeStart != null && rangeEnd != null && rangeStart.isAfter(rangeEnd)) {
            LocalDate temp = rangeStart;
            rangeStart = rangeEnd;
            rangeEnd = temp;
        }

        final LocalDate finalStart = rangeStart;
        final LocalDate finalEnd = rangeEnd;
        final String normKeyword = keyword != null && !keyword.isBlank() ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        final String normChannel = channel != null && !channel.isBlank() && !"ALL".equalsIgnoreCase(channel) ? channel.trim() : null;
        final String normResult = contactResult != null && !contactResult.isBlank() && !"ALL".equalsIgnoreCase(contactResult) ? contactResult.trim() : null;
        final String normCareGroup = careGroupFilter != null && !careGroupFilter.isBlank() && !"ALL".equalsIgnoreCase(careGroupFilter) ? careGroupFilter.trim() : null;

        List<CustomerCareInteraction> matched = list.stream().filter(i -> {
            // Lọc ngày
            if (i.getCreatedAt() != null) {
                LocalDate createdDate = i.getCreatedAt().atZone(ZoneId.systemDefault())
                        .withZoneSameInstant(CLINIC_ZONE).toLocalDate();
                if (finalStart != null && createdDate.isBefore(finalStart)) return false;
                if (finalEnd != null && createdDate.isAfter(finalEnd)) return false;
            }

            // Lọc nhân viên thực hiện
            if (staffId != null && staffId > 0) {
                if (i.getStaff() == null || !staffId.equals(i.getStaff().getStaffId())) return false;
            }

            // Lọc Bác sĩ (Bác sĩ nhận chuyển giao hoặc Bác sĩ điều trị/kê đơn/lịch hẹn liên kết với ca)
            if (doctorId != null && doctorId > 0) {
                CustomerCareTask t = i.getTask();
                boolean isDoctor = t != null && (
                        (t.getEscalatedToDoctor() != null && doctorId.equals(t.getEscalatedToDoctor().getStaffId()))
                        || (t.getVisit() != null && t.getVisit().getPrimaryDoctor() != null && doctorId.equals(t.getVisit().getPrimaryDoctor().getStaffId()))
                        || (t.getPrescription() != null && t.getPrescription().getDoctor() != null && doctorId.equals(t.getPrescription().getDoctor().getStaffId()))
                        || (t.getAppointment() != null && t.getAppointment().getDoctor() != null && doctorId.equals(t.getAppointment().getDoctor().getStaffId()))
                );
                if (!isDoctor) return false;
            }

            // Lọc Kênh / Hình thức
            if (normChannel != null) {
                if (i.getChannel() == null || !i.getChannel().name().equalsIgnoreCase(normChannel)) return false;
            }

            // Lọc Kết quả
            if (normResult != null) {
                if (i.getContactResult() == null || !i.getContactResult().name().equalsIgnoreCase(normResult)) return false;
            }

            // Lọc Nhóm CSKH
            if (normCareGroup != null) {
                if (i.getTask() == null) return false;
                String group = resolveCareGroup(i.getTask());
                if (!group.equalsIgnoreCase(normCareGroup)) {
                    if ("POST_TREATMENT".equalsIgnoreCase(normCareGroup) && !"POST_TREATMENT_FOLLOWUP".equalsIgnoreCase(group)) return false;
                    else if (!group.equalsIgnoreCase(normCareGroup)) return false;
                }
            }

            // Lọc từ khóa: Tên BN, Mã BN, SĐT, Mã công việc, Ghi chú
            if (normKeyword != null) {
                boolean hit = false;
                PatientProfile p = i.getPatientProfile();
                if (p != null) {
                    if (p.getFullName() != null && p.getFullName().toLowerCase(Locale.ROOT).contains(normKeyword)) hit = true;
                    else if (p.getPatientCode() != null && p.getPatientCode().toLowerCase(Locale.ROOT).contains(normKeyword)) hit = true;
                    else if (p.getPhoneNumber() != null && p.getPhoneNumber().toLowerCase(Locale.ROOT).contains(normKeyword)) hit = true;
                }
                if (!hit && i.getTask() != null && i.getTask().getTaskCode() != null
                        && i.getTask().getTaskCode().toLowerCase(Locale.ROOT).contains(normKeyword)) {
                    hit = true;
                }
                if (!hit && i.getNotes() != null && i.getNotes().toLowerCase(Locale.ROOT).contains(normKeyword)) {
                    hit = true;
                }
                if (!hit && i.getStaff() != null && i.getStaff().getFullName() != null
                        && i.getStaff().getFullName().toLowerCase(Locale.ROOT).contains(normKeyword)) {
                    hit = true;
                }
                if (!hit) return false;
            }

            return true;
        }).toList();

        int total = matched.size();
        int page = pageable != null ? pageable.getPageNumber() : 0;
        int size = pageable != null ? pageable.getPageSize() : 10;
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);

        List<CustomerCareInteractionDTO> dtos = matched.subList(fromIndex, toIndex).stream()
                .map(this::toInteractionDTO)
                .toList();

        return new PageImpl<>(dtos, pageable != null ? pageable : PageRequest.of(page, size), total);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getPatientCareHistory(Long profileId, Long branchId, Long userId) {
        if (profileId == null || profileId <= 0) {
            bad("Vui lòng chọn bệnh nhân để xem lịch sử chăm sóc.");
        }

        String careRole = requireCareRole(userId);
        Long effectiveBranchId = resolveAndAuthorizeBranchId(branchId, userId, false);

        PatientProfile patient = patientRepository.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));

        List<CustomerCareTask> patientTasks = taskRepository.findByPatientProfileIdWithDetails(profileId);
        List<CustomerCareInteraction> patientInteractions = interactionRepository.findByProfileIdWithDetails(profileId);

        if (!"ADMIN".equals(careRole)) {
            Set<Long> allowedBranchIds = resolveAllowedBranches(userId, careRole).stream()
                    .map(Branch::getBranchId)
                    .collect(Collectors.toSet());
            patientTasks = patientTasks.stream()
                    .filter(t -> t.getBranch() != null && allowedBranchIds.contains(t.getBranch().getBranchId()))
                    .toList();
            patientInteractions = patientInteractions.stream()
                    .filter(i -> i.getTask() != null && i.getTask().getBranch() != null
                            && allowedBranchIds.contains(i.getTask().getBranch().getBranchId()))
                    .toList();
            boolean preferredInScope = patient.getPreferredBranch() != null
                    && allowedBranchIds.contains(patient.getPreferredBranch().getBranchId());
            boolean hasVisitInScope = visitRepository.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(profileId).stream()
                    .anyMatch(v -> v.getBranch() != null && allowedBranchIds.contains(v.getBranch().getBranchId()));
            if (!preferredInScope && !hasVisitInScope && patientTasks.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Không có quyền xem lịch sử chăm sóc bệnh nhân của cơ sở khác."
                );
            }
        } else if (effectiveBranchId != null) {
            patientTasks = patientTasks.stream()
                    .filter(t -> t.getBranch() != null && effectiveBranchId.equals(t.getBranch().getBranchId()))
                    .toList();
            patientInteractions = patientInteractions.stream()
                    .filter(i -> i.getTask() != null && i.getTask().getBranch() != null
                            && effectiveBranchId.equals(i.getTask().getBranch().getBranchId()))
                    .toList();
        }

        Map<Long, List<CustomerCareInteraction>> byTask = patientInteractions.stream()
                .collect(Collectors.groupingBy(i -> i.getTask().getTaskId()));

        List<CustomerCareTaskDTO> taskDTOs = patientTasks.stream()
                .map(t -> toTaskDTO(t, byTask.getOrDefault(t.getTaskId(), List.of()), true))
                .toList();

        List<CustomerCareInteractionDTO> timelineDTOs = patientInteractions.stream()
                .map(this::toInteractionDTO)
                .toList();

        Map<String, Object> patientInfo = new LinkedHashMap<>();
        patientInfo.put("profileId", patient.getProfileId());
        patientInfo.put("patientCode", patient.getPatientCode());
        patientInfo.put("fullName", patient.getFullName());
        patientInfo.put("phoneNumber", patient.getPhoneNumber());
        patientInfo.put("gender", patient.getGender());
        patientInfo.put("dob", patient.getDob());
        patientInfo.put("address", patient.getAddress());
        patientInfo.put("hasAllergies", patient.getHasAllergies());
        patientInfo.put("allergyDetails", patient.getAllergyDetails());
        patientInfo.put("underlyingDiseaseDetails", patient.getUnderlyingDiseaseDetails());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patient", patientInfo);
        result.put("tasks", taskDTOs);
        result.put("interactions", timelineDTOs);
        result.put("totalTasks", taskDTOs.size());
        result.put("totalInteractions", timelineDTOs.size());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getVisitExaminationSummary(Long visitId, Long userId) {
        if (visitId == null || visitId <= 0) {
            bad("Mã lượt khám (visitId) không hợp lệ.");
        }
        String careRole = requireCareRole(userId);
        Visit visit = visitRepository.findWithDetailsById(visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt khám phù hợp."));

        if (!"ADMIN".equals(careRole) && visit.getBranch() != null) {
            Set<Long> allowedBranchIds = resolveAllowedBranches(userId, careRole).stream()
                    .map(Branch::getBranchId)
                    .collect(Collectors.toSet());
            if (!allowedBranchIds.contains(visit.getBranch().getBranchId())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Lượt khám này thuộc cơ sở khác. Bạn chỉ được xem thông tin khám của bệnh nhân tại cơ sở mình đang công tác."
                );
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("visitId", visit.getVisitId());
        summary.put("visitCode", visit.getVisitCode());
        summary.put("visitStatus", visit.getStatus() != null ? visit.getStatus().name() : null);
        summary.put("checkInAt", visit.getCheckInAt());
        summary.put("completedAt", visit.getCompletedAt());
        summary.put("initialSymptoms", visit.getInitialSymptoms());
        summary.put("visitNotes", visit.getNotes());
        summary.put("examinationDetailUrl", buildExaminationDetailUrl(visit.getVisitId()));

        if (visit.getPatientProfile() != null) {
            PatientProfile p = visit.getPatientProfile();
            Map<String, Object> pMap = new LinkedHashMap<>();
            pMap.put("profileId", p.getProfileId());
            pMap.put("patientCode", p.getPatientCode());
            pMap.put("fullName", p.getFullName());
            pMap.put("phoneNumber", p.getPhoneNumber());
            pMap.put("gender", p.getGender());
            pMap.put("dob", p.getDob());
            summary.put("patient", pMap);
        }

        if (visit.getPrimaryDoctor() != null) {
            StaffProfile d = visit.getPrimaryDoctor();
            Map<String, Object> dMap = new LinkedHashMap<>();
            dMap.put("staffId", d.getStaffId());
            dMap.put("staffCode", d.getStaffCode());
            dMap.put("fullName", d.getFullName());
            dMap.put("positionTitle", d.getPositionTitle());
            summary.put("doctor", dMap);
        }

        ClinicalExamination exam = clinicalExaminationRepository != null
                ? clinicalExaminationRepository.findByVisit_VisitId(visitId).orElse(null)
                : null;
        if (exam != null) {
            summary.put("examId", exam.getExamId());
            summary.put("examTime", exam.getExamTime());
            summary.put("chiefComplaint", exam.getChiefComplaint());
            summary.put("historyIllness", exam.getHistoryIllness());
            summary.put("extraoralExam", exam.getExtraoralExam());
            summary.put("intraoralExam", exam.getIntraoralExam());
            summary.put("periodontalStatus", exam.getPeriodontalStatus());
            summary.put("summaryAssessment", exam.getSummaryAssessment());
        }

        List<ExaminationDiagnosis> diagEntities = examinationDiagnosisRepository != null
                ? examinationDiagnosisRepository.findByVisitIdWithDetails(visitId)
                : List.of();
        List<Map<String, Object>> diagnoses = new ArrayList<>();
        List<String> diagTexts = new ArrayList<>();
        for (ExaminationDiagnosis ed : diagEntities) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("diagnosisId", ed.getDiagnosisId());
            dm.put("diagnosisType", ed.getDiagnosisType() != null ? ed.getDiagnosisType().name() : "MAIN");
            String icdCode = ed.getIcd10Disease() != null ? ed.getIcd10Disease().getIcd10Code() : null;
            dm.put("icd10Code", icdCode);
            dm.put("diagnosisText", ed.getDiagnosisText());
            dm.put("toothNumbers", ed.getToothNumbers());
            dm.put("notes", ed.getNotes());
            diagnoses.add(dm);

            String line = (icdCode != null ? "[" + icdCode + "] " : "")
                    + (ed.getDiagnosisText() != null ? ed.getDiagnosisText() : "")
                    + (ed.getToothNumbers() != null && !ed.getToothNumbers().isBlank() ? " (Răng: " + ed.getToothNumbers() + ")" : "");
            if (!line.isBlank()) {
                diagTexts.add(line.trim());
            }
        }
        summary.put("diagnoses", diagnoses);

        List<Prescription> prescriptions = prescriptionRepository != null
                ? prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(visitId)
                : List.of();
        List<Map<String, Object>> prescItems = new ArrayList<>();
        List<String> adviceParts = new ArrayList<>();
        LocalDate followupDate = null;

        if (!prescriptions.isEmpty()) {
            Prescription latestPresc = prescriptionRepository.findByIdWithDetails(prescriptions.get(0).getPrescriptionId())
                    .orElse(prescriptions.get(0));
            summary.put("prescriptionId", latestPresc.getPrescriptionId());
            summary.put("prescriptionCode", latestPresc.getPrescriptionCode());
            summary.put("prescriptionDiagnosis", latestPresc.getDiagnosisText());
            summary.put("prescriptionAdvice", latestPresc.getAdviceNotes());
            followupDate = latestPresc.getFollowupDate();

            if (diagTexts.isEmpty() && latestPresc.getDiagnosisText() != null && !latestPresc.getDiagnosisText().isBlank()) {
                diagTexts.add(latestPresc.getDiagnosisText().trim());
            }
            if (latestPresc.getAdviceNotes() != null && !latestPresc.getAdviceNotes().isBlank()) {
                adviceParts.add(latestPresc.getAdviceNotes().trim());
            }
            if (latestPresc.getItems() != null) {
                for (PrescriptionItem item : latestPresc.getItems()) {
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("itemId", item.getItemId());
                    im.put("drugName", item.getDrugName());
                    im.put("dosage", item.getDosage());
                    im.put("durationDays", item.getDurationDays());
                    im.put("quantity", item.getQuantity());
                    im.put("unit", item.getUnit());
                    im.put("usageInstruction", item.getUsageInstruction());
                    prescItems.add(im);
                }
            }
        }

        if (visit.getNotes() != null && !visit.getNotes().isBlank()) {
            String vn = visit.getNotes().trim();
            if (adviceParts.stream().noneMatch(a -> a.contains(vn))) {
                adviceParts.add("Ghi chú bác sĩ: " + vn);
            }
        }
        if (exam != null && exam.getSummaryAssessment() != null && !exam.getSummaryAssessment().isBlank()) {
            String sa = exam.getSummaryAssessment().trim();
            if (adviceParts.stream().noneMatch(a -> a.contains(sa))) {
                adviceParts.add("Kết luận khám: " + sa);
            }
        }

        summary.put("diagnosisSummary", diagTexts.isEmpty() ? null : String.join("; ", diagTexts));
        summary.put("treatmentInstructions", adviceParts.isEmpty() ? null : String.join(" | ", adviceParts));
        summary.put("prescriptionItems", prescItems);
        summary.put("followupDate", followupDate);
        return summary;
    }

    @Override
    @Transactional
    public CustomerCareTaskDTO recordVisitInteraction(Long visitId, CustomerCareInteractionCreateDTO dto, Long userId) {
        if (visitId == null || visitId <= 0) {
            bad("Mã ca khám không hợp lệ.");
        }
        if (dto == null) {
            bad("Dữ liệu kết quả liên hệ không được để trống.");
        }

        String careRole = requireCareRole(userId);

        Visit visit = visitRepository.findWithDetailsById(visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca khám phù hợp."));

        if (!"ADMIN".equals(careRole)) {
            Set<Long> allowedBranchIds = resolveAllowedBranches(userId, careRole).stream()
                    .map(Branch::getBranchId)
                    .collect(Collectors.toSet());
            Long visitBranchId = visit.getBranch() != null ? visit.getBranch().getBranchId() : null;
            if (visitBranchId == null || !allowedBranchIds.contains(visitBranchId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Không có quyền ghi nhận tương tác cho ca khám của cơ sở khác."
                );
            }
        }

        if (visit.getPatientProfile() == null) {
            bad("Ca khám không có thông tin hồ sơ bệnh nhân tương ứng.");
        }

        // 1. Tìm task CSKH đang mở gắn trực tiếp với ca khám này
        List<CustomerCareTask> visitTasks = taskRepository.findByVisit_VisitId(visitId);
        CustomerCareTask task = visitTasks.stream()
                .filter(t -> t.getStatus() != CustomerCareTaskStatus.COMPLETED && t.getStatus() != CustomerCareTaskStatus.CANCELLED)
                .findFirst().orElse(null);

        // 2. Nếu không có task gắn visitId, tìm task đang mở gần nhất của bệnh nhân tại cùng cơ sở
        if (task == null) {
            Long visitBranchId = visit.getBranch() != null ? visit.getBranch().getBranchId() : null;
            List<CustomerCareTask> pTasks = taskRepository.findByPatientProfileIdWithDetails(visit.getPatientProfile().getProfileId());
            task = pTasks.stream()
                    .filter(t -> t.getStatus() != CustomerCareTaskStatus.COMPLETED && t.getStatus() != CustomerCareTaskStatus.CANCELLED)
                    .filter(t -> visitBranchId == null || (t.getBranch() != null && visitBranchId.equals(t.getBranch().getBranchId())))
                    .findFirst().orElse(null);
        }

        // 3. Nếu vẫn chưa có task nào, tự động khởi tạo 1 task sau điều trị gắn với ca khám này
        if (task == null) {
            task = new CustomerCareTask();
            task.setTaskCode("CSKH" + (System.currentTimeMillis() % 10000000L));
            task.setPatientProfile(visit.getPatientProfile());
            task.setBranch(visit.getBranch());
            task.setVisit(visit);
            task.setTaskType(CustomerCareTaskType.POST_TREATMENT_FOLLOWUP);
            task.setDueDate(LocalDate.now());
            task.setStatus(CustomerCareTaskStatus.IN_PROGRESS);
            task.setNotes("Tương tác CSKH trực tiếp tại ca khám " + (visit.getVisitCode() != null ? visit.getVisitCode() : ""));
            task.setAssignedStaff(visit.getPrimaryDoctor());
            task = taskRepository.save(task);
        }

        // 4. Ghi nhận tương tác thông qua hàm recordInteraction chuẩn
        return recordInteraction(task.getTaskId(), dto, userId);
    }

    // =========================================================================
    // CÁC HÀM BỔ TRỢ NGHIỆP VỤ & CHUYỂN ĐỔI DTO
    // =========================================================================

    private Map<Long, List<CustomerCareInteraction>> loadInteractionsMapByBranch(Long branchId) {
        List<CustomerCareInteraction> list = interactionRepository.findAllByBranchOrderByCreatedAtDesc(branchId);
        Map<Long, List<CustomerCareInteraction>> map = new HashMap<>();
        for (CustomerCareInteraction i : list) {
            map.computeIfAbsent(i.getTask().getTaskId(), k -> new ArrayList<>()).add(i);
        }
        return map;
    }

    private String resolveCareGroup(CustomerCareTask task) {
        String rawNotes = task.getNotes() != null ? task.getNotes() : "";
        String lowerNotes = rawNotes.toLowerCase(Locale.ROOT);

        if (rawNotes.startsWith(TAG_INSTALLMENT) || lowerNotes.contains("[tra_gop]") || lowerNotes.contains("trả góp")) {
            return "INSTALLMENT_REMINDER";
        }
        if (rawNotes.startsWith(TAG_NO_SERVICE) || lowerNotes.contains("[khong_dich_vu]") || lowerNotes.contains("không làm dịch vụ")) {
            return "NO_SERVICE_FOLLOWUP";
        }
        if (rawNotes.startsWith(TAG_FEEDBACK) || lowerNotes.contains("[phan_anh]") || lowerNotes.contains("phản ánh") || lowerNotes.contains("khiếu nại")) {
            return "FEEDBACK_COMPLAINT";
        }
        if (task.getTaskType() == null) {
            return "POST_TREATMENT_FOLLOWUP";
        }
        return task.getTaskType().name();
    }

    private String resolveCareGroupLabel(String careGroup, CustomerCareTaskType taskType) {
        if ("INSTALLMENT_REMINDER".equals(careGroup)) return "Nhắc trả góp";
        if ("NO_SERVICE_FOLLOWUP".equals(careGroup)) return "Không làm dịch vụ";
        if ("FEEDBACK_COMPLAINT".equals(careGroup)) return "Phản ánh / Khiếu nại";
        if (taskType == CustomerCareTaskType.MEDICATION_REMINDER) return "Nhắc dùng thuốc (Sau ĐT)";
        if (taskType == CustomerCareTaskType.POST_TREATMENT_FOLLOWUP) return "Chăm sóc sau điều trị";
        if (taskType == CustomerCareTaskType.APPOINTMENT_REMINDER) return "Nhắc lịch hẹn";
        if (taskType == CustomerCareTaskType.NO_SHOW_RECALL) return "Lịch hẹn lỡ (Gọi lại)";
        return careGroup;
    }

    private boolean matchesTabGroup(CustomerCareTask task, CustomerCareInteraction latest, String tabGroup, LocalDate today) {
        if (tabGroup == null || "ALL".equalsIgnoreCase(tabGroup)) {
            return true;
        }
        String careGroup = resolveCareGroup(task);
        String upperTab = tabGroup.toUpperCase(Locale.ROOT);

        return switch (upperTab) {
            case "ACTIONABLE" -> task.getStatus() == CustomerCareTaskStatus.PENDING
                    || task.getStatus() == CustomerCareTaskStatus.IN_PROGRESS;
            case "COMPLETED" -> task.getStatus() == CustomerCareTaskStatus.COMPLETED;
            case "CANCELLED" -> task.getStatus() == CustomerCareTaskStatus.CANCELLED;
            case "TODAY" -> {
                boolean active = task.getStatus() != CustomerCareTaskStatus.COMPLETED
                        && task.getStatus() != CustomerCareTaskStatus.CANCELLED;
                boolean dueTodayOrOverdue = task.getDueDate() == null || !task.getDueDate().isAfter(today);
                boolean retryTodayOrOverdue = latest != null && latest.getNextRetryAt() != null
                        && !latest.getNextRetryAt().toLocalDate().isAfter(today);
                yield active && (dueTodayOrOverdue || retryTodayOrOverdue);
            }
            case "OVERDUE" -> {
                boolean active = task.getStatus() != CustomerCareTaskStatus.COMPLETED
                        && task.getStatus() != CustomerCareTaskStatus.CANCELLED;
                boolean isOverdue = task.getDueDate() != null && task.getDueDate().isBefore(today);
                boolean retryOverdue = latest != null && latest.getNextRetryAt() != null
                        && latest.getNextRetryAt().toLocalDate().isBefore(today);
                yield active && (isOverdue || retryOverdue);
            }
            case "POST_TREATMENT" -> "POST_TREATMENT_FOLLOWUP".equals(careGroup)
                    || "MEDICATION_REMINDER".equals(careGroup);
            case "APPOINTMENT_REMINDER" -> "APPOINTMENT_REMINDER".equals(careGroup);
            case "INSTALLMENT_REMINDER" -> "INSTALLMENT_REMINDER".equals(careGroup);
            case "NO_SHOW_RECALL" -> "NO_SHOW_RECALL".equals(careGroup);
            case "NO_SERVICE_FOLLOWUP" -> "NO_SERVICE_FOLLOWUP".equals(careGroup);
            case "FEEDBACK_COMPLAINT" -> "FEEDBACK_COMPLAINT".equals(careGroup)
                    || task.getStatus() == CustomerCareTaskStatus.ESCALATED_TO_DOCTOR;
            case "ESCALATED" -> task.getStatus() == CustomerCareTaskStatus.ESCALATED_TO_DOCTOR;
            default -> careGroup.equalsIgnoreCase(upperTab);
        };
    }

    private boolean matchesKeyword(CustomerCareTask task, String kw) {
        PatientProfile p = task.getPatientProfile();
        if (task.getTaskCode() != null && task.getTaskCode().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (p != null) {
            if (p.getFullName() != null && p.getFullName().toLowerCase(Locale.ROOT).contains(kw)) return true;
            if (p.getPatientCode() != null && p.getPatientCode().toLowerCase(Locale.ROOT).contains(kw)) return true;
            if (p.getPhoneNumber() != null && p.getPhoneNumber().toLowerCase(Locale.ROOT).contains(kw)) return true;
        }
        if (task.getVisit() != null && task.getVisit().getVisitCode() != null
                && task.getVisit().getVisitCode().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (task.getPrescription() != null && task.getPrescription().getPrescriptionCode() != null
                && task.getPrescription().getPrescriptionCode().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (task.getAppointment() != null && task.getAppointment().getAppointmentCode() != null
                && task.getAppointment().getAppointmentCode().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (task.getNotes() != null && task.getNotes().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (task.getDoctorResponse() != null && task.getDoctorResponse().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (task.getAssignedStaff() != null && task.getAssignedStaff().getFullName() != null
                && task.getAssignedStaff().getFullName().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (task.getEscalatedToDoctor() != null && task.getEscalatedToDoctor().getFullName() != null
                && task.getEscalatedToDoctor().getFullName().toLowerCase(Locale.ROOT).contains(kw)) return true;
        return false;
    }

    private CustomerCareTaskType mapCareGroupToDbType(String careGroup) {
        String upper = careGroup.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "APPOINTMENT_REMINDER", "INSTALLMENT_REMINDER" -> CustomerCareTaskType.APPOINTMENT_REMINDER;
            case "MEDICATION_REMINDER" -> CustomerCareTaskType.MEDICATION_REMINDER;
            case "NO_SHOW_RECALL", "NO_SERVICE_FOLLOWUP" -> CustomerCareTaskType.NO_SHOW_RECALL;
            case "POST_TREATMENT", "POST_TREATMENT_FOLLOWUP", "FEEDBACK_COMPLAINT" -> CustomerCareTaskType.POST_TREATMENT_FOLLOWUP;
            default -> {
                bad("Loại công việc CSKH không hợp lệ: " + careGroup);
                yield CustomerCareTaskType.POST_TREATMENT_FOLLOWUP;
            }
        };
    }

    private String formatNotesWithGroupTag(String careGroup, String rawNotes) {
        String clean = stripGroupTag(rawNotes);
        String upper = careGroup != null ? careGroup.trim().toUpperCase(Locale.ROOT) : "";
        if ("INSTALLMENT_REMINDER".equals(upper)) {
            return TAG_INSTALLMENT + (clean.isEmpty() ? "Nhắc lịch thanh toán trả góp định kỳ" : clean);
        }
        if ("NO_SERVICE_FOLLOWUP".equals(upper)) {
            return TAG_NO_SERVICE + (clean.isEmpty() ? "Chăm sóc khách hàng thăm khám nhưng chưa thực hiện dịch vụ" : clean);
        }
        if ("FEEDBACK_COMPLAINT".equals(upper)) {
            return TAG_FEEDBACK + (clean.isEmpty() ? "Tiếp nhận và xử lý phản ánh của khách hàng" : clean);
        }
        return clean.isEmpty() ? null : clean;
    }

    private String stripGroupTag(String notes) {
        if (notes == null) return "";
        String s = notes.trim();
        if (s.startsWith(TAG_INSTALLMENT.trim())) {
            return s.substring(TAG_INSTALLMENT.trim().length()).trim();
        }
        if (s.startsWith(TAG_NO_SERVICE.trim())) {
            return s.substring(TAG_NO_SERVICE.trim().length()).trim();
        }
        if (s.startsWith(TAG_FEEDBACK.trim())) {
            return s.substring(TAG_FEEDBACK.trim().length()).trim();
        }
        return s;
    }

    private void attachOptionalLinks(CustomerCareTask task, Long visitId, Long appointmentId, Long prescriptionId) {
        if (visitId != null && visitId > 0) {
            visitRepository.findWithDetailsById(visitId)
                    .filter(v -> v.getPatientProfile() != null)
                    .ifPresent(task::setVisit);
        }
        if (appointmentId != null && appointmentId > 0) {
            appointmentRepository.findById(appointmentId).ifPresent(task::setAppointment);
        }
        if (prescriptionId != null && prescriptionId > 0) {
            prescriptionRepository.findById(prescriptionId).ifPresent(task::setPrescription);
        }
    }

    private String generateUniqueTaskCode() {
        String code;
        do {
            long seq = TASK_CODE_SEQ.incrementAndGet();
            code = "TASK" + seq;
            if (code.length() > 20) {
                code = code.substring(0, 20);
            }
        } while (taskRepository.existsByTaskCode(code));
        return code;
    }

    private StaffProfile resolveDefaultCareStaff(Long userId, Long branchId) {
        if (userId != null) {
            Optional<StaffProfile> byUser = staffRepository.findByUser_UserId(userId);
            if (byUser.isPresent()) {
                return byUser.get();
            }
        }
        if (branchId != null) {
            List<Long> staffIds = taskRepository.findActiveStaffIdsByBranch(branchId);
            if (staffIds != null && !staffIds.isEmpty()) {
                for (Long id : staffIds) {
                    Optional<StaffProfile> sp = staffRepository.findById(id);
                    if (sp.isPresent() && !isDoctorStaff(sp.get())) {
                        return sp.get();
                    }
                }
                Optional<StaffProfile> firstAtBranch = staffRepository.findById(staffIds.get(0));
                if (firstAtBranch.isPresent()) {
                    return firstAtBranch.get();
                }
            }
        }
        List<StaffProfile> active = staffRepository.findByStatus(StaffStatus.ACTIVE);
        if (!active.isEmpty()) {
            return active.get(0);
        }
        return staffRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Chưa có hồ sơ nhân viên trong hệ thống để thực hiện CSKH."));
    }

    private boolean isDoctorStaff(StaffProfile s) {
        String pos = (s.getPositionTitle() != null ? s.getPositionTitle() : "").toLowerCase(Locale.ROOT);
        String dept = (s.getDepartment() != null ? s.getDepartment() : "").toLowerCase(Locale.ROOT);
        String code = (s.getStaffCode() != null ? s.getStaffCode() : "").toLowerCase(Locale.ROOT);
        return pos.contains("bác sĩ") || pos.contains("doctor") || pos.contains("bs")
                || dept.contains("lâm sàng") || dept.contains("bác sĩ") || dept.contains("điều trị")
                || code.startsWith("bs");
    }

    private boolean isReceptionistStaff(StaffProfile s) {
        String pos = (s.getPositionTitle() != null ? s.getPositionTitle() : "").toLowerCase(Locale.ROOT);
        String dept = (s.getDepartment() != null ? s.getDepartment() : "").toLowerCase(Locale.ROOT);
        String code = (s.getStaffCode() != null ? s.getStaffCode() : "").toLowerCase(Locale.ROOT);
        return pos.contains("lễ tân") || pos.contains("cskh") || pos.contains("chăm sóc") || pos.contains("tiếp đón")
                || dept.contains("lễ tân") || dept.contains("cskh") || dept.contains("chăm sóc")
                || code.startsWith("lt") || code.startsWith("nv");
    }

    private CustomerCareTaskDTO toTaskDTO(
            CustomerCareTask t,
            List<CustomerCareInteraction> interactions,
            boolean includeFullInteractions) {

        CustomerCareTaskDTO dto = new CustomerCareTaskDTO();
        dto.setTaskId(t.getTaskId());
        dto.setTaskCode(t.getTaskCode());

        PatientProfile p = t.getPatientProfile();
        if (p != null) {
            dto.setProfileId(p.getProfileId());
            dto.setPatientCode(p.getPatientCode());
            dto.setPatientName(p.getFullName());
            dto.setPatientPhone(p.getPhoneNumber());
            dto.setPatientGender(p.getGender());
            dto.setPatientDob(p.getDob());
            dto.setPatientAddress(p.getAddress());
        }

        Branch b = t.getBranch();
        if (b != null) {
            dto.setBranchId(b.getBranchId());
            dto.setBranchCode(b.getBranchCode());
            dto.setBranchName(b.getBranchName());
        }

        String careGroup = resolveCareGroup(t);
        dto.setTaskType(t.getTaskType() != null ? t.getTaskType().name() : null);
        dto.setCareGroup(careGroup);
        dto.setCareGroupLabel(resolveCareGroupLabel(careGroup, t.getTaskType()));

        Visit visit = t.getVisit();
        if (visit == null && t.getPrescription() != null && t.getPrescription().getVisit() != null) {
            visit = t.getPrescription().getVisit();
        }

        if (visit != null) {
            dto.setVisitId(visit.getVisitId());
            dto.setVisitCode(visit.getVisitCode());
            dto.setVisitCheckInAt(visit.getCheckInAt());
            dto.setVisitSymptoms(visit.getInitialSymptoms());
            dto.setVisitStatus(visit.getStatus() != null ? visit.getStatus().name() : null);
            dto.setVisitNotes(visit.getNotes());
            dto.setExaminationDetailUrl(buildExaminationDetailUrl(visit.getVisitId()));
            if (visit.getPrimaryDoctor() != null) {
                dto.setDoctorId(visit.getPrimaryDoctor().getStaffId());
                dto.setDoctorCode(visit.getPrimaryDoctor().getStaffCode());
                dto.setDoctorName(visit.getPrimaryDoctor().getFullName());
            }
        }

        if (t.getAppointment() != null) {
            dto.setAppointmentId(t.getAppointment().getAppointmentId());
            dto.setAppointmentCode(t.getAppointment().getAppointmentCode());
            dto.setAppointmentDate(t.getAppointment().getAppointmentDate());
            if (t.getAppointment().getService() != null) {
                dto.setAppointmentServiceName(t.getAppointment().getService().getServiceName());
            }
        }

        Prescription linkedPresc = t.getPrescription();
        if (linkedPresc == null && visit != null && visit.getVisitId() != null && prescriptionRepository != null) {
            List<Prescription> visitPrescs = prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(visit.getVisitId());
            if (visitPrescs != null && !visitPrescs.isEmpty()) {
                linkedPresc = visitPrescs.get(0);
            }
        }

        if (linkedPresc != null) {
            dto.setPrescriptionId(linkedPresc.getPrescriptionId());
            dto.setPrescriptionCode(linkedPresc.getPrescriptionCode());
            dto.setPrescriptionDiagnosis(linkedPresc.getDiagnosisText());
            dto.setPrescriptionAdvice(linkedPresc.getAdviceNotes());
            dto.setFollowupDate(linkedPresc.getFollowupDate());
            if (dto.getDoctorName() == null && linkedPresc.getDoctor() != null) {
                dto.setDoctorId(linkedPresc.getDoctor().getStaffId());
                dto.setDoctorCode(linkedPresc.getDoctor().getStaffCode());
                dto.setDoctorName(linkedPresc.getDoctor().getFullName());
            }
        }

        // Bổ sung thông tin Khám lâm sàng, Chẩn đoán ICD-10, Lời dặn điều trị (#tab-instructions) & Đơn thuốc
        populateClinicalExaminationContext(dto, visit, linkedPresc, includeFullInteractions);

        if (t.getAssignedStaff() != null) {
            dto.setAssignedStaffId(t.getAssignedStaff().getStaffId());
            dto.setAssignedStaffCode(t.getAssignedStaff().getStaffCode());
            dto.setAssignedStaffName(t.getAssignedStaff().getFullName());
        }

        if (t.getEscalatedToDoctor() != null) {
            dto.setEscalatedToDoctorId(t.getEscalatedToDoctor().getStaffId());
            dto.setEscalatedToDoctorCode(t.getEscalatedToDoctor().getStaffCode());
            dto.setEscalatedToDoctorName(t.getEscalatedToDoctor().getFullName());
        }
        dto.setEscalatedAt(t.getEscalatedAt());
        dto.setDoctorResponse(t.getDoctorResponse());
        dto.setEscalationReason(t.getEscalationReason());
        dto.setEscalatedByUserId(t.getEscalatedByUserId());
        if (t.getEscalatedByUserId() != null) {
            dto.setEscalatedByName(resolveActorDisplayName(t.getEscalatedByUserId()));
        }

        LocalDate today = LocalDate.now(CLINIC_ZONE);
        dto.setDueDate(t.getDueDate());
        boolean isActive = t.getStatus() != CustomerCareTaskStatus.COMPLETED
                && t.getStatus() != CustomerCareTaskStatus.CANCELLED;
        dto.setOverdue(isActive && t.getDueDate() != null && t.getDueDate().isBefore(today));
        dto.setDueToday(isActive && t.getDueDate() != null && t.getDueDate().isEqual(today));
        dto.setDueBucket(resolveDueBucket(t, today));

        dto.setStatus(t.getStatus() != null ? t.getStatus().name() : "PENDING");
        dto.setStatusLabel(formatStatusLabel(t.getStatus()));
        dto.setNotes(stripGroupTag(t.getNotes()));

        List<CustomerCareInteraction> safeList = interactions != null ? interactions : List.of();
        dto.setInteractionCount(safeList.size());
        if (!safeList.isEmpty()) {
            CustomerCareInteraction latest = safeList.get(0);
            dto.setLastChannel(latest.getChannel() != null ? latest.getChannel().name() : null);
            dto.setLastContactResult(latest.getContactResult() != null ? latest.getContactResult().name() : null);
            dto.setLastInteractionNotes(latest.getNotes());
            dto.setLastContactAt(latest.getCreatedAt());
            dto.setNextRetryAt(latest.getNextRetryAt());
        }

        if (includeFullInteractions) {
            dto.setInteractions(safeList.stream().map(this::toInteractionDTO).toList());
        }
        return dto;
    }

    private void populateClinicalExaminationContext(
            CustomerCareTaskDTO dto,
            Visit visit,
            Prescription linkedPresc,
            boolean includeFullDetails) {

        List<String> diagParts = new ArrayList<>();
        List<String> instructionParts = new ArrayList<>();
        List<String> medParts = new ArrayList<>();

        if (visit != null && visit.getVisitId() != null) {
            Long visitId = visit.getVisitId();
            if (clinicalExaminationRepository != null) {
                ClinicalExamination exam = clinicalExaminationRepository.findByVisit_VisitId(visitId).orElse(null);
                if (exam != null) {
                    dto.setExamId(exam.getExamId());
                    dto.setExamTime(exam.getExamTime());
                    dto.setChiefComplaint(exam.getChiefComplaint());
                    dto.setSummaryAssessment(exam.getSummaryAssessment());
                    if (dto.getDoctorName() == null && exam.getDoctor() != null) {
                        dto.setDoctorId(exam.getDoctor().getStaffId());
                        dto.setDoctorCode(exam.getDoctor().getStaffCode());
                        dto.setDoctorName(exam.getDoctor().getFullName());
                    }
                }
            }

            if (examinationDiagnosisRepository != null) {
                List<ExaminationDiagnosis> diagEntities = examinationDiagnosisRepository.findByVisitIdWithDetails(visitId);
                if (diagEntities != null && !diagEntities.isEmpty()) {
                    List<Map<String, Object>> diagMaps = new ArrayList<>();
                    for (ExaminationDiagnosis ed : diagEntities) {
                        String icd = ed.getIcd10Disease() != null ? ed.getIcd10Disease().getIcd10Code() : null;
                        String text = ed.getDiagnosisText() != null ? ed.getDiagnosisText().trim() : "";
                        String teeth = ed.getToothNumbers() != null && !ed.getToothNumbers().isBlank()
                                ? " (Răng: " + ed.getToothNumbers().trim() + ")"
                                : "";
                        String line = (icd != null ? "[" + icd + "] " : "") + text + teeth;
                        if (!line.isBlank()) {
                            diagParts.add(line.trim());
                        }
                        if (includeFullDetails) {
                            Map<String, Object> dm = new LinkedHashMap<>();
                            dm.put("diagnosisId", ed.getDiagnosisId());
                            dm.put("diagnosisType", ed.getDiagnosisType() != null ? ed.getDiagnosisType().name() : "MAIN");
                            dm.put("icd10Code", icd);
                            dm.put("diagnosisText", ed.getDiagnosisText());
                            dm.put("toothNumbers", ed.getToothNumbers());
                            dm.put("notes", ed.getNotes());
                            diagMaps.add(dm);
                        }
                    }
                    if (includeFullDetails) {
                        dto.setDiagnoses(diagMaps);
                    }
                }
            }
        }

        if (linkedPresc != null) {
            if (diagParts.isEmpty() && linkedPresc.getDiagnosisText() != null && !linkedPresc.getDiagnosisText().isBlank()) {
                diagParts.add(linkedPresc.getDiagnosisText().trim());
            }
            if (linkedPresc.getAdviceNotes() != null && !linkedPresc.getAdviceNotes().isBlank()) {
                instructionParts.add(linkedPresc.getAdviceNotes().trim());
            }

            Prescription detailedPresc = linkedPresc;
            if (includeFullDetails && linkedPresc.getPrescriptionId() != null && prescriptionRepository != null) {
                detailedPresc = prescriptionRepository.findByIdWithDetails(linkedPresc.getPrescriptionId()).orElse(linkedPresc);
            }
            if (detailedPresc.getItems() != null && !detailedPresc.getItems().isEmpty()) {
                List<Map<String, Object>> itemMaps = new ArrayList<>();
                for (PrescriptionItem item : detailedPresc.getItems()) {
                    String medStr = item.getDrugName()
                            + (item.getQuantity() != null ? " x" + item.getQuantity().stripTrailingZeros().toPlainString() : "")
                            + (item.getUnit() != null ? " " + item.getUnit() : "")
                            + (item.getDosage() != null ? " (" + item.getDosage() + ")" : "");
                    medParts.add(medStr);
                    if (includeFullDetails) {
                        Map<String, Object> im = new LinkedHashMap<>();
                        im.put("itemId", item.getItemId());
                        im.put("drugName", item.getDrugName());
                        im.put("dosage", item.getDosage());
                        im.put("durationDays", item.getDurationDays());
                        im.put("quantity", item.getQuantity());
                        im.put("unit", item.getUnit());
                        im.put("usageInstruction", item.getUsageInstruction());
                        itemMaps.add(im);
                    }
                }
                if (includeFullDetails) {
                    dto.setPrescriptionItems(itemMaps);
                }
            }
        }

        if (visit != null && visit.getNotes() != null && !visit.getNotes().isBlank()) {
            String vn = visit.getNotes().trim();
            if (instructionParts.stream().noneMatch(s -> s.contains(vn))) {
                instructionParts.add(vn);
            }
        }
        if (dto.getSummaryAssessment() != null && !dto.getSummaryAssessment().isBlank()) {
            String sa = dto.getSummaryAssessment().trim();
            if (instructionParts.stream().noneMatch(s -> s.contains(sa))) {
                instructionParts.add(sa);
            }
        }

        dto.setDiagnosisSummary(diagParts.isEmpty() ? dto.getPrescriptionDiagnosis() : String.join("; ", diagParts));
        dto.setTreatmentInstructions(instructionParts.isEmpty() ? dto.getPrescriptionAdvice() : String.join(" | ", instructionParts));
        dto.setPrescriptionSummary(medParts.isEmpty() ? null : String.join(", ", medParts));
    }

    private CustomerCareInteractionDTO toInteractionDTO(CustomerCareInteraction i) {
        CustomerCareInteractionDTO dto = new CustomerCareInteractionDTO();
        dto.setInteractionId(i.getInteractionId());
        if (i.getTask() != null) {
            dto.setTaskId(i.getTask().getTaskId());
            dto.setTaskCode(i.getTask().getTaskCode());
            dto.setTaskType(i.getTask().getTaskType() != null ? i.getTask().getTaskType().name() : null);
            String grp = resolveCareGroup(i.getTask());
            dto.setCareGroupLabel(resolveCareGroupLabel(grp, i.getTask().getTaskType()));
            dto.setTaskStatus(i.getTask().getStatus() != null ? i.getTask().getStatus().name() : null);
            if (i.getTask().getBranch() != null) {
                dto.setBranchId(i.getTask().getBranch().getBranchId());
                dto.setBranchName(i.getTask().getBranch().getBranchName());
            }
        }
        if (i.getPatientProfile() != null) {
            dto.setProfileId(i.getPatientProfile().getProfileId());
            dto.setPatientCode(i.getPatientProfile().getPatientCode());
            dto.setPatientName(i.getPatientProfile().getFullName());
            dto.setPatientPhone(i.getPatientProfile().getPhoneNumber());
        }
        if (i.getStaff() != null) {
            dto.setStaffId(i.getStaff().getStaffId());
            dto.setStaffCode(i.getStaff().getStaffCode());
            dto.setStaffName(i.getStaff().getFullName());
            dto.setStaffPosition(i.getStaff().getPositionTitle());
        }
        dto.setChannel(i.getChannel() != null ? i.getChannel().name() : "PHONE_CALL");
        dto.setChannelLabel(formatChannelLabel(i.getChannel()));
        dto.setContactResult(i.getContactResult() != null ? i.getContactResult().name() : null);
        dto.setContactResultLabel(formatContactResultLabel(i.getContactResult()));
        dto.setNotes(i.getNotes());
        dto.setNextRetryAt(i.getNextRetryAt());
        dto.setCreatedAt(i.getCreatedAt());
        return dto;
    }

    private static String formatStatusLabel(CustomerCareTaskStatus status) {
        if (status == null) return "Chờ liên hệ";
        return switch (status) {
            case PENDING -> "Chờ liên hệ";
            case IN_PROGRESS -> "Đang chăm sóc / Hẹn gọi lại";
            case ESCALATED_TO_DOCTOR -> "Chuyển Bác sĩ chuyên môn";
            case COMPLETED -> "Đã hoàn thành";
            case CANCELLED -> "Đã hủy";
        };
    }

    private static String formatChannelLabel(InteractionChannel ch) {
        if (ch == null) return "Gọi điện thoại";
        return switch (ch) {
            case PHONE_CALL -> "Gọi điện thoại";
            case ZALO -> "Nhắn tin Zalo";
            case SMS -> "Tin nhắn SMS";
            case IN_PERSON -> "Trực tiếp tại PK";
        };
    }

    private static String formatContactResultLabel(ContactResult r) {
        if (r == null) return "—";
        return switch (r) {
            case CONNECTED -> "Đã kết nối thành công";
            case NO_ANSWER -> "Không bắt máy";
            case BUSY -> "Máy bận";
            case SCHEDULED_APPOINTMENT -> "Đã đặt / đổi lịch hẹn";
        };
    }

    private InteractionChannel parseChannel(String value) {
        if (value == null || value.isBlank()) {
            return InteractionChannel.PHONE_CALL;
        }
        try {
            return InteractionChannel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            bad("Kênh liên hệ không hợp lệ (chỉ chấp nhận PHONE_CALL, ZALO, SMS, IN_PERSON).");
            return InteractionChannel.PHONE_CALL;
        }
    }

    private ContactResult parseContactResult(String value) {
        if (value == null || value.isBlank()) {
            bad("Vui lòng chọn kết quả liên hệ.");
        }
        try {
            return ContactResult.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            bad("Kết quả liên hệ không hợp lệ (chỉ chấp nhận CONNECTED, NO_ANSWER, BUSY, SCHEDULED_APPOINTMENT).");
            return ContactResult.CONNECTED;
        }
    }

    private CustomerCareTaskStatus parseTaskStatus(String value) {
        try {
            return CustomerCareTaskStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            bad("Trạng thái công việc không hợp lệ.");
            return CustomerCareTaskStatus.PENDING;
        }
    }

    private void validateBranchIdIfPresent(Long branchId) {
        if (branchId != null && branchId <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String resolveActorDisplayName(Long userId) {
        if (userId == null) return "Hệ thống";
        return staffRepository.findByUser_UserId(userId)
                .map(StaffProfile::getFullName)
                .orElseGet(() -> userRepository.findById(userId).map(User::getUsername).orElse("Nhân viên #" + userId));
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
