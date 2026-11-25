package com.cvdcms.service;

import com.cvdcms.security.ReceptionAccess;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.security.PatientAccess;
import com.cvdcms.util.PatientSearchPolicy;
import com.cvdcms.DTO.PatientView;
import com.cvdcms.repository.ReceptionAppointmentPatientRepository;
import com.cvdcms.repository.ReceptionAppointmentRepository;
import com.cvdcms.service.ReceptionAppointmentService;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.AppointmentSlotService;
import com.cvdcms.service.PublicConsultationService;
import com.cvdcms.service.SystemAuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Nghiệp vụ quản lý yêu cầu tư vấn Guest booking tại Lễ tân (Đợt 2B):
 * - Danh sách, tóm tắt badge (không PII), chi tiết, lịch sử bất biến.
 * - Nhận xử lý độc quyền (lock + chống ghi đè), bàn giao có kiểm tra phạm vi chi nhánh, ghi nhận liên hệ, đóng.
 * - Xác minh bệnh nhân theo PatientSearchPolicy (không auto-pick khi trùng/nhiều hồ sơ).
 * - Chuyển đổi thành Appointment chính thức tái sử dụng ReceptionAppointmentService / Policy / Slot trong cùng transaction.
 */
@Service
public class ReceptionConsultationService {

    public static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReceptionAccess receptionAccess;
    private final PatientAccess patientAccess;
    private final UserRepository users;
    private final ReceptionAssignmentRepository receptionAssignments;
    private final BranchRepository branches;
    private final ServiceRepository services;
    private final BookingConsultationRequestRepository consultations;
    private final BookingConsultationLogRepository consultationLogs;
    private final ReceptionAppointmentPatientRepository patients;
    private final ReceptionAppointmentRepository appointments;
    private final ReceptionAppointmentService receptionAppointmentService;
    private final AppointmentPolicyService appointmentPolicyService;

    @Autowired(required = false)
    private SystemAuditLogService systemAuditLogService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private StaffProfileRepository staffProfiles;

    @Autowired
    public ReceptionConsultationService(
            ReceptionAccess receptionAccess,
            PatientAccess patientAccess,
            UserRepository users,
            ReceptionAssignmentRepository receptionAssignments,
            BranchRepository branches,
            ServiceRepository services,
            BookingConsultationRequestRepository consultations,
            BookingConsultationLogRepository consultationLogs,
            ReceptionAppointmentPatientRepository patients,
            ReceptionAppointmentRepository appointments,
            ReceptionAppointmentService receptionAppointmentService,
            AppointmentPolicyService appointmentPolicyService
    ) {
        this.receptionAccess = receptionAccess;
        this.patientAccess = patientAccess;
        this.users = users;
        this.receptionAssignments = receptionAssignments;
        this.branches = branches;
        this.services = services;
        this.consultations = consultations;
        this.consultationLogs = consultationLogs;
        this.patients = patients;
        this.appointments = appointments;
        this.receptionAppointmentService = receptionAppointmentService;
        this.appointmentPolicyService = appointmentPolicyService;
    }

    public record ConsultationConvertedEvent(Long requestId, Long appointmentId) {}

    public record ClaimRequest(String note) {}

    public record ContactRequest(ConsultationContactResult contactResult, String notes) {}

    public record HandoverRequest(Long targetUserId, String reason) {}

    public record CloseRequest(String reason) {}

    public record ConvertRequest(
            String requestKey,
            Long branchId,
            Long profileId,
            Boolean createNewPatient,
            String patientName,
            String patientPhone,
            LocalDate patientDob,
            String patientGender,
            String patientAddress,
            String idCardNumber,
            String guardianName,
            String guardianPhone,
            String guardianRelationship,
            Boolean confirmDuplicate,
            LocalDate date,
            PreferredSession session,
            LocalTime expectedTime,
            Boolean flexibleSessionTime,
            Long serviceId,
            Boolean clearService,
            Long doctorId,
            String symptoms,
            Boolean isTentative,
            String note
    ) {}

    public record ConsultationSummary(
            long newCount,
            long unclaimedNewCount,
            long contactedCount,
            long openCount,
            long convertedCount,
            long closedCount,
            LocalDateTime latestUpdatedAt,
            LocalDateTime serverTime
    ) {}

    public record ConsultationItem(
            Long requestId,
            String requestCode,
            String guestName,
            String guestPhone,
            Long branchId,
            String branchName,
            Long serviceId,
            String serviceName,
            Long preferredDoctorId,
            String preferredDoctorName,
            LocalDate preferredDate,
            PreferredSession preferredSession,
            LocalTime preferredTime,
            String notes,
            String status,
            Long handledByUserId,
            String handledByName,
            Long convertedAppointmentId,
            String convertedAppointmentCode,
            LocalDateTime claimedAt,
            LocalDateTime lastContactedAt,
            LocalDateTime convertedAt,
            LocalDateTime closedAt,
            String closeReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public record LogItem(
            Long logId,
            ConsultationLogAction action,
            String oldStatus,
            String newStatus,
            ConsultationContactResult contactResult,
            String notes,
            Long actorUserId,
            String actorName,
            Long targetUserId,
            String targetName,
            LocalDateTime createdAt
    ) {}

    public record ConsultationPage(
            List<ConsultationItem> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            ConsultationSummary summary
    ) {}

    public record ConsultationDetail(
            ConsultationItem item,
            List<LogItem> logs,
            Boolean preferredDoctorEligible,
            boolean canManage,
            boolean canAssignDoctor
    ) {}

    public record CandidateProfile(
            Long profileId,
            String patientCode,
            String fullName,
            String phoneNumber,
            LocalDate dob,
            String gender,
            Long branchId,
            String branchName,
            boolean crossBranch
    ) {}

    public record VerificationResult(
            String status,
            String query,
            String message,
            List<CandidateProfile> candidates
    ) {}

    public record AssigneeOption(
            Long userId,
            String fullName,
            String username
    ) {}

    public record BranchOption(Long id, String name) {}

    public record ServiceOption(Long id, String name, int durationMinutes, boolean bookableOnline) {}

    public record DoctorOption(Long id, String name, String specialty) {}

    public record ReferenceOptions(
            List<BranchOption> branches,
            List<ServiceOption> services,
            boolean canManage,
            boolean canAssignDoctor
    ) {}

    public record ConvertResult(
            boolean replayed,
            String message,
            ConsultationDetail detail,
            ReceptionAppointmentService.AppointmentView appointment
    ) {}

    @Transactional(readOnly = true)
    public ReferenceOptions options(HttpSession session) {
        ReceptionAccess.Scope scope = requireViewScope(session);
        List<Branch> allowedBranches = scope.admin()
                ? branches.findByIsActiveTrueOrderByBranchNameAsc()
                : branches.findAllById(scope.branchIds()).stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .sorted(Comparator.comparing(Branch::getBranchName))
                .toList();

        List<BranchOption> branchOptions = allowedBranches.stream()
                .map(b -> new BranchOption(b.getBranchId(), b.getBranchName()))
                .toList();

        List<ServiceOption> serviceOptions = services.findByStatusOrderByCategory_DisplayOrderAscServiceNameAsc(ServiceStatus.ACTIVE)
                .stream()
                .filter(s -> s.getCategory() != null
                        && Boolean.TRUE.equals(s.getCategory().getIsActive())
                        && s.getServiceType() != com.cvdcms.entity.enums.ServiceType.PRODUCT)
                .map(s -> new ServiceOption(
                        s.getServiceId(),
                        s.getServiceName(),
                        AppointmentSlotService.duration(s),
                        Boolean.TRUE.equals(s.getIsBookableOnline())
                ))
                .toList();

        return new ReferenceOptions(branchOptions, serviceOptions, scope.canManage(), canAssignDoctor(scope));
    }

    @Transactional(readOnly = true)
    public ConsultationPage list(
            HttpSession session,
            Long branchId,
            String status,
            String keyword,
            LocalDate createdFrom,
            LocalDate createdTo,
            int page,
            int size
    ) {
        ReceptionAccess.Scope scope = requireViewScope(session);
        List<Long> scopedBranches = resolveScopedBranchIds(scope, branchId);
        boolean filterByBranches = scopedBranches != null;
        List<Long> branchQueryList = filterByBranches ? scopedBranches : List.of(-1L);

        List<ConsultationRequestStatus> targetStatuses = parseFilterStatuses(status);
        boolean filterByStatuses = !targetStatuses.isEmpty();
        List<ConsultationRequestStatus> statusQueryList = filterByStatuses
                ? targetStatuses
                : List.of(ConsultationRequestStatus.NEW);

        String cleanKeyword = keyword != null && !keyword.isBlank() ? keyword.trim() : null;
        LocalDateTime fromDt = createdFrom != null ? createdFrom.atStartOfDay() : null;
        LocalDateTime toDt = createdTo != null ? createdTo.plusDays(1).atStartOfDay().minusNanos(1) : null;
        if (fromDt != null && toDt != null && toDt.isBefore(fromDt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khoảng ngày tạo không hợp lệ.");
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.min(50, Math.max(1, size));

        Page<BookingConsultationRequest> paged = consultations.searchPaged(
                filterByBranches,
                branchQueryList,
                filterByStatuses,
                statusQueryList,
                cleanKeyword,
                fromDt,
                toDt,
                PageRequest.of(safePage, safeSize)
        );

        List<ConsultationItem> items = paged.getContent().stream().map(this::toItem).toList();
        ConsultationSummary summary = buildSummary(filterByBranches, branchQueryList);
        return new ConsultationPage(items, paged.getNumber(), paged.getSize(), paged.getTotalElements(), paged.getTotalPages(), summary);
    }

    /**
     * Chỉ trả số đếm trạng thái cho badge/polling — tuyệt đối không chứa SĐT, tên khách hay dữ liệu cá nhân.
     */
    @Transactional(readOnly = true)
    public ConsultationSummary summary(HttpSession session, Long branchId) {
        ReceptionAccess.Scope scope = requireViewScope(session);
        List<Long> scopedBranches = resolveScopedBranchIds(scope, branchId);
        boolean filterByBranches = scopedBranches != null;
        List<Long> branchQueryList = filterByBranches ? scopedBranches : List.of(-1L);
        return buildSummary(filterByBranches, branchQueryList);
    }

    @Transactional(readOnly = true)
    public ConsultationDetail detail(HttpSession session, Long requestId) {
        ReceptionAccess.Scope scope = requireViewScope(session);
        BookingConsultationRequest r = consultations.findDetailById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu tư vấn."));
        if (!scope.permits(r.getBranch().getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập yêu cầu tư vấn của chi nhánh khác.");
        }
        return toDetail(r, scope.canManage(), canAssignDoctor(scope));
    }

    @Transactional(readOnly = true)
    public List<AssigneeOption> assignees(HttpSession session, Long requestId) {
        ReceptionAccess.Scope scope = requireViewScope(session);
        BookingConsultationRequest r = consultations.findDetailById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu tư vấn."));
        Long branchId = r.getBranch().getBranchId();
        if (!scope.permits(branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập chi nhánh này.");
        }
        List<User> candidates = users.findActiveReceptionistsAndAdminsByBranch(branchId);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(u -> isEligibleAssignee(u, branchId))
                .sorted(Comparator.comparing(this::userDisplayName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> new AssigneeOption(u.getUserId(), userDisplayName(u), u.getUsername()))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConsultationDetail claim(HttpSession session, Long requestId, ClaimRequest req, HttpServletRequest httpRequest) {
        ReceptionAccess.Scope scope = requireManageScope(session);
        BookingConsultationRequest r = lockAndAuthorizeConsultation(scope, requestId);

        if (r.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Yêu cầu tư vấn đã kết thúc (" + displayStatus(r.getStatus()) + "), không thể nhận xử lý.");
        }
        if (r.getHandledByUser() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Yêu cầu này đã có " + userDisplayName(r.getHandledByUser()) + " nhận phụ trách. Không thể ghi đè người phụ trách.");
        }

        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        ConsultationRequestStatus currentStatus = r.getStatus();
        String note = req != null ? cleanNote(req.note(), 1000) : null;

        Map<String, Object> oldAudit = snapshotForAudit(r);
        r.setHandledByUser(scope.user());
        r.setClaimedAt(now);
        consultations.saveAndFlush(r);

        saveLog(r, ConsultationLogAction.CLAIM, currentStatus, currentStatus, null,
                note != null ? note : "Nhận xử lý yêu cầu tư vấn", scope.user(), null);
        writeAuditLog(scope.user(), r.getBranch(), "CONSULTATION_CLAIM", r.getRequestId(), httpRequest,
                oldAudit, snapshotForAudit(r));

        return toDetail(r, scope.canManage(), canAssignDoctor(scope));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConsultationDetail contact(HttpSession session, Long requestId, ContactRequest req, HttpServletRequest httpRequest) {
        ReceptionAccess.Scope scope = requireManageScope(session);
        if (req == null || req.contactResult() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn kết quả liên hệ.");
        }
        String notes = cleanNote(req.notes(), 1000);
        if (notes == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng nhập ghi chú nội dung liên hệ.");
        }

        BookingConsultationRequest r = lockAndAuthorizeConsultation(scope, requestId);
        if (r.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Yêu cầu tư vấn đã kết thúc (" + displayStatus(r.getStatus()) + "), không thể cập nhật liên hệ.");
        }
        if (r.getHandledByUser() != null && !scope.admin()
                && !Objects.equals(r.getHandledByUser().getUserId(), scope.user().getUserId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Yêu cầu đang do " + userDisplayName(r.getHandledByUser()) + " phụ trách. Vui lòng bàn giao trước khi thao tác.");
        }

        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        Map<String, Object> oldAudit = snapshotForAudit(r);
        ConsultationRequestStatus oldStatus = r.getStatus();
        ConsultationRequestStatus nextStatus = ConsultationRequestStatus.CONTACTED;

        if (r.getHandledByUser() == null) {
            r.setHandledByUser(scope.user());
            r.setClaimedAt(now);
        }
        // Không bao giờ chuyển ngược từ CONTACTED về NEW
        if (r.getStatus() == ConsultationRequestStatus.NEW) {
            r.setStatus(nextStatus);
        }
        r.setLastContactedAt(now);
        consultations.saveAndFlush(r);

        saveLog(r, ConsultationLogAction.CONTACT, oldStatus, r.getStatus(), req.contactResult(),
                notes, scope.user(), null);
        writeAuditLog(scope.user(), r.getBranch(), "CONSULTATION_CONTACT", r.getRequestId(), httpRequest,
                oldAudit, snapshotForAudit(r));

        return toDetail(r, scope.canManage(), canAssignDoctor(scope));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConsultationDetail handover(HttpSession session, Long requestId, HandoverRequest req, HttpServletRequest httpRequest) {
        ReceptionAccess.Scope scope = requireManageScope(session);
        if (req == null || req.targetUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn nhân sự nhận bàn giao.");
        }
        String reason = cleanNote(req.reason(), 1000);
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng nhập lý do bàn giao.");
        }

        BookingConsultationRequest r = lockAndAuthorizeConsultation(scope, requestId);
        if (r.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Yêu cầu tư vấn đã kết thúc (" + displayStatus(r.getStatus()) + "), không thể bàn giao.");
        }
        if (r.getHandledByUser() != null && !scope.admin()
                && !Objects.equals(r.getHandledByUser().getUserId(), scope.user().getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Chỉ người đang phụ trách (" + userDisplayName(r.getHandledByUser()) + ") hoặc Quản trị viên mới được phép bàn giao.");
        }

        User targetUser = users.findById(req.targetUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhân sự nhận bàn giao."));
        Long branchId = r.getBranch().getBranchId();
        if (!isEligibleAssignee(targetUser, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Nhân sự nhận bàn giao không hoạt động hoặc không thuộc phạm vi chi nhánh của yêu cầu này.");
        }
        if (r.getHandledByUser() != null && Objects.equals(r.getHandledByUser().getUserId(), targetUser.getUserId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nhân sự này hiện đã là người phụ trách yêu cầu.");
        }

        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        Map<String, Object> oldAudit = snapshotForAudit(r);
        r.setHandledByUser(targetUser);
        if (r.getClaimedAt() == null) {
            r.setClaimedAt(now);
        }
        consultations.saveAndFlush(r);

        saveLog(r, ConsultationLogAction.HANDOVER, r.getStatus(), r.getStatus(), null,
                reason, scope.user(), targetUser);
        writeAuditLog(scope.user(), r.getBranch(), "CONSULTATION_HANDOVER", r.getRequestId(), httpRequest,
                oldAudit, snapshotForAudit(r));

        return toDetail(r, scope.canManage(), canAssignDoctor(scope));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConsultationDetail close(HttpSession session, Long requestId, CloseRequest req, HttpServletRequest httpRequest) {
        ReceptionAccess.Scope scope = requireManageScope(session);
        String reason = req != null ? cleanNote(req.reason(), 500) : null;
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng nhập lý do đóng yêu cầu tư vấn.");
        }

        BookingConsultationRequest r = lockAndAuthorizeConsultation(scope, requestId);
        if (r.getStatus() == ConsultationRequestStatus.CONVERTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Yêu cầu đã chuyển thành lịch hẹn, không thể đóng.");
        }
        if (r.getStatus().isClosedEquivalent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Yêu cầu tư vấn này đã được đóng trước đó.");
        }
        if (r.getHandledByUser() != null && !scope.admin()
                && !Objects.equals(r.getHandledByUser().getUserId(), scope.user().getUserId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Yêu cầu đang do " + userDisplayName(r.getHandledByUser()) + " phụ trách. Vui lòng bàn giao trước khi đóng.");
        }

        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        Map<String, Object> oldAudit = snapshotForAudit(r);
        ConsultationRequestStatus oldStatus = r.getStatus();

        if (r.getHandledByUser() == null) {
            r.setHandledByUser(scope.user());
            r.setClaimedAt(now);
        }
        r.setStatus(ConsultationRequestStatus.CLOSED);
        r.setCloseReason(reason);
        r.setClosedAt(now);
        consultations.saveAndFlush(r);

        saveLog(r, ConsultationLogAction.CLOSE, oldStatus, ConsultationRequestStatus.CLOSED, null,
                reason, scope.user(), null);
        writeAuditLog(scope.user(), r.getBranch(), "CONSULTATION_CLOSE", r.getRequestId(), httpRequest,
                oldAudit, snapshotForAudit(r));

        return toDetail(r, scope.canManage(), canAssignDoctor(scope));
    }

    /**
     * Bước 3: Tra cứu & xác minh hồ sơ bệnh nhân theo chính sách PatientSearchPolicy.
     * Tuyệt đối không tự chọn phần tử đầu tiên khi trùng nhiều hồ sơ hoặc dữ liệu không nhất quán.
     */
    @Transactional(readOnly = true)
    public VerificationResult verifyPatientCandidates(HttpSession session, Long requestId, String queryOverride) {
        ReceptionAccess.Scope scope = requireViewScope(session);
        BookingConsultationRequest r = consultations.findDetailById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu tư vấn."));
        Long consultationBranchId = r.getBranch().getBranchId();
        if (!scope.permits(consultationBranchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập yêu cầu tư vấn của chi nhánh khác.");
        }

        String rawQuery = (queryOverride != null && !queryOverride.isBlank()) ? queryOverride.trim() : r.getGuestPhone();
        String normalizedPhone = PublicConsultationService.normalizeVietnamPhone(rawQuery);
        PatientSearchPolicy.ExactIdentifierType idType = PatientSearchPolicy.classifyExactIdentifier(
                PublicConsultationService.isValidVietnamPhone(normalizedPhone) ? normalizedPhone : rawQuery
        );

        List<PatientProfile> found;
        if (idType == PatientSearchPolicy.ExactIdentifierType.PHONE) {
            found = patients.findExactActiveByPhones(PublicConsultationService.phoneVariants(normalizedPhone));
        } else if (idType == PatientSearchPolicy.ExactIdentifierType.ID_CARD) {
            found = patients.findExactActiveByIdCard(PatientSearchPolicy.normalize(rawQuery));
        } else if (idType == PatientSearchPolicy.ExactIdentifierType.PATIENT_CODE) {
            found = patients.findExactActiveByPatientCode(PatientSearchPolicy.normalize(rawQuery));
        } else {
            return new VerificationResult(
                    "INVALID_IDENTIFIER",
                    rawQuery,
                    "Vui lòng nhập chính xác Số điện thoại (10-11 số), CCCD (9 hoặc 12 số) hoặc Mã bệnh nhân (BN...) để xác minh.",
                    List.of()
            );
        }

        if (found == null || found.isEmpty()) {
            return new VerificationResult(
                    "NOT_FOUND",
                    rawQuery,
                    "Chưa tìm thấy hồ sơ bệnh nhân khớp định danh. Lễ tân xác minh đầy đủ thông tin trước khi tạo hồ sơ mới.",
                    List.of()
            );
        }

        List<CandidateProfile> candidates = found.stream()
                .map(p -> toCandidateProfile(p, scope))
                .toList();

        try {
            Optional<PatientProfile> single = PatientSearchPolicy.resolveSingleConsistentExactMatch(
                    idType,
                    idType == PatientSearchPolicy.ExactIdentifierType.PHONE ? normalizedPhone : rawQuery,
                    found
            );
            if (single.isPresent()) {
                return new VerificationResult(
                        "SINGLE_MATCH",
                        rawQuery,
                        "Tìm thấy 1 hồ sơ khớp định danh. Lễ tân vui lòng đối chiếu lại họ tên/ngày sinh và xác nhận chọn hồ sơ.",
                        candidates
                );
            }
        } catch (ResponseStatusException conflictEx) {
            return new VerificationResult(
                    "AMBIGUOUS_CONFLICT",
                    rawQuery,
                    PatientSearchPolicy.VERIFICATION_CONFLICT_MESSAGE,
                    candidates
            );
        }

        return new VerificationResult(
                "AMBIGUOUS_CONFLICT",
                rawQuery,
                PatientSearchPolicy.VERIFICATION_CONFLICT_MESSAGE,
                candidates
        );
    }

    /**
     * Bước 4: Chuyển đổi yêu cầu tư vấn thành lịch hẹn chính thức trong cùng 1 transaction.
     * Khóa bi quan BookingConsultationRequest -> (PatientService.create nếu tạo mới) -> PatientProfile -> Branch (trong ReceptionAppointmentService).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConvertResult convertToAppointment(
            HttpSession session,
            Long requestId,
            ConvertRequest req,
            HttpServletRequest httpRequest
    ) {
        ReceptionAccess.Scope scope = requireManageScope(session);
        if (req == null || !PublicConsultationService.isValidRequestKey(req.requestKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mã yêu cầu chuyển đổi (requestKey) hợp lệ là bắt buộc để chống trùng.");
        }
        String convertKey = req.requestKey().trim();

        // Chặn ngay nếu client cố truyền branchId nằm ngoài phạm vi chi nhánh được phân quyền
        if (req.branchId() != null && !scope.permits(req.branchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Không có quyền thao tác chuyển đổi sang chi nhánh này.");
        }

        // 1. Khóa bi quan dòng booking_consultation_requests trước khi kiểm tra trạng thái & tạo hồ sơ
        BookingConsultationRequest r = lockAndAuthorizeConsultation(scope, requestId);
        Long consultationBranchId = r.getBranch().getBranchId();

        if (req.branchId() != null && !Objects.equals(req.branchId(), consultationBranchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Không được phép thay đổi chi nhánh của yêu cầu tư vấn.");
        }

        // 2. Idempotency: nếu convertRequestKey đã dùng cho chính yêu cầu này -> trả lại kết quả cũ, không tạo Appointment thứ 2
        Optional<BookingConsultationRequest> existingByKey = consultations.findByConvertRequestKey(convertKey);
        if (existingByKey.isPresent()) {
            BookingConsultationRequest matched = existingByKey.get();
            if (!Objects.equals(matched.getRequestId(), r.getRequestId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Mã chuyển đổi đã được sử dụng cho yêu cầu tư vấn khác.");
            }
            if (matched.getConvertedAppointment() != null) {
                ReceptionAppointmentService.AppointmentView existingAppt =
                        receptionAppointmentService.detail(session, matched.getConvertedAppointment().getAppointmentId()).appointment();
                PatientAccess.Scope pScope = patientAccess.require(session);
                return new ConvertResult(
                        true,
                        "Yêu cầu đã được chuyển đổi thành lịch hẹn #" + existingAppt.code() + " trước đó.",
                        toDetail(matched, scope.canManage(), patientAccess.canAssignDoctor(pScope)),
                        existingAppt
                );
            }
        }

        // 3. Chặn mọi trạng thái kết thúc hoặc đã có người khác phụ trách
        if (r.getStatus() == ConsultationRequestStatus.CONVERTED || r.getConvertedAppointment() != null) {
            String code = r.getConvertedAppointment() != null ? r.getConvertedAppointment().getAppointmentCode() : "";
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Yêu cầu tư vấn này đã được chuyển đổi thành lịch hẹn"
                            + (code.isBlank() ? "." : " #" + code + "."));
        }
        if (r.getStatus().isClosedEquivalent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Yêu cầu tư vấn đã đóng (" + r.getStatus().name() + "), không thể chuyển đổi thành lịch hẹn.");
        }
        if (r.getHandledByUser() != null && !scope.admin()
                && !Objects.equals(r.getHandledByUser().getUserId(), scope.user().getUserId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Yêu cầu đang do " + userDisplayName(r.getHandledByUser()) + " phụ trách. Vui lòng thực hiện bàn giao trước khi chuyển đổi.");
        }

        // 4. Bắt buộc xác minh bệnh nhân: phải chọn profileId hoặc tạo hồ sơ mới đủ thông tin bắt buộc
        boolean creatingNew = Boolean.TRUE.equals(req.createNewPatient());
        if (req.profileId() == null && !creatingNew) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Vui lòng chọn hồ sơ bệnh nhân đã xác minh hoặc thực hiện tạo hồ sơ mới sau khi xác minh.");
        }
        if (req.profileId() != null && creatingNew) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Chỉ được chọn hồ sơ có sẵn hoặc tạo hồ sơ mới, không truyền đồng thời cả hai.");
        }
        if (req.profileId() != null && patients.findBookable(req.profileId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Hồ sơ bệnh nhân đã chọn không tồn tại hoặc đang ngừng hoạt động.");
        }

        // 5. Quy tắc bác sĩ (Mục 3 & F): giữ nguyên quyền chỉ định bác sĩ hiện tại; preferredDoctor chỉ là nguyện vọng
        PatientAccess.Scope pScope = patientAccess.require(session);
        if (req.doctorId() != null && !patientAccess.canAssignDoctor(pScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Lễ tân không được tự ý gán bác sĩ thủ công. Hệ thống sẽ tự động phân bổ bác sĩ.");
        }

        // 6. Xác định dịch vụ, ngày, buổi, giờ & ghi chú
        Long targetServiceId = req.serviceId() != null
                ? req.serviceId()
                : (Boolean.TRUE.equals(req.clearService()) ? null : (r.getService() != null ? r.getService().getServiceId() : null));
        LocalDate targetDate = req.date() != null ? req.date() : r.getPreferredDate();
        PreferredSession targetSession = req.session() != null ? req.session() : r.getPreferredSession();
        LocalTime targetExpectedTime = req.expectedTime() != null
                ? req.expectedTime()
                : (Boolean.TRUE.equals(req.flexibleSessionTime()) ? null : r.getPreferredTime());

        String normalizedNewPhone = creatingNew
                ? PublicConsultationService.normalizeVietnamPhone(req.patientPhone())
                : null;
        String baseSymptoms = cleanNote(req.symptoms(), 500);
        if (baseSymptoms == null) {
            baseSymptoms = cleanNote(r.getNotes(), 500);
        }
        if (baseSymptoms == null) {
            baseSymptoms = "Khám tư vấn theo yêu cầu " + r.getRequestCode();
        }
        String extraNote = cleanNote(req.note(), 250);

        ReceptionAppointmentService.CreateRequest createRequest = new ReceptionAppointmentService.CreateRequest(
                consultationBranchId,
                req.profileId(),
                targetDate,
                targetSession,
                req.doctorId(),
                targetServiceId,
                baseSymptoms,
                targetExpectedTime,
                creatingNew ? cleanNote(req.patientName(), 100) : null,
                normalizedNewPhone,
                creatingNew ? (cleanNote(req.patientAddress(), 255) != null ? cleanNote(req.patientAddress(), 255) : "Chưa cập nhật") : null,
                "GUEST_CONSULTATION",
                Boolean.TRUE.equals(req.isTentative()),
                extraNote,
                creatingNew ? req.patientDob() : null,
                creatingNew ? cleanNote(req.patientGender(), 20) : null,
                creatingNew ? cleanNote(req.guardianName(), 100) : null,
                creatingNew ? PublicConsultationService.normalizeVietnamPhone(req.guardianPhone()) : null,
                creatingNew ? cleanNote(req.guardianRelationship(), 50) : null,
                creatingNew ? cleanNote(req.idCardNumber(), 20) : null,
                creatingNew ? Boolean.TRUE.equals(req.confirmDuplicate()) : Boolean.FALSE
        );

        // 7. Gọi thẳng ReceptionAppointmentService.create trong cùng transaction
        ReceptionAppointmentService.AppointmentView createdAppointment =
                receptionAppointmentService.create(session, createRequest);

        Appointment savedAppointment = appointments.findById(createdAppointment.id())
                .orElseThrow(() -> new IllegalStateException("Không tải được lịch hẹn vừa tạo."));

        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        Map<String, Object> oldAudit = snapshotForAudit(r);
        ConsultationRequestStatus oldStatus = r.getStatus();

        if (r.getHandledByUser() == null) {
            r.setHandledByUser(scope.user());
            r.setClaimedAt(now);
        }
        r.setStatus(ConsultationRequestStatus.CONVERTED);
        r.setConvertedAppointment(savedAppointment);
        r.setConvertedAt(now);
        r.setConvertRequestKey(convertKey);
        consultations.saveAndFlush(r);

        String convertLogNote = "Chuyển thành lịch hẹn #" + createdAppointment.code()
                + (extraNote != null ? " — " + extraNote : "");
        saveLog(r, ConsultationLogAction.CONVERT, oldStatus, ConsultationRequestStatus.CONVERTED, null,
                cleanNote(convertLogNote, 1000), scope.user(), null);
        writeAuditLog(scope.user(), r.getBranch(), "CONSULTATION_CONVERT", r.getRequestId(), httpRequest,
                oldAudit, snapshotForAudit(r));

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ConsultationConvertedEvent(r.getRequestId(), savedAppointment.getAppointmentId()));
        }

        return new ConvertResult(
                false,
                "Đã chuyển yêu cầu " + r.getRequestCode() + " thành lịch hẹn #" + createdAppointment.code() + ".",
                toDetail(r, scope.canManage(), patientAccess.canAssignDoctor(pScope)),
                createdAppointment
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    public ReceptionAccess.Scope requireViewScope(HttpSession session) {
        ReceptionAccess.Scope scope = receptionAccess.require(session);
        boolean pureDoctorWithoutReception = !scope.admin()
                && !scope.canManage()
                && hasRole(scope.user(), "BAC_SI")
                && !hasRole(scope.user(), "LE_TAN");
        if (pureDoctorWithoutReception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Bác sĩ không có quyền tiếp đón không được truy cập quản lý yêu cầu tư vấn.");
        }
        if (scope.admin() || scope.canManage()
                || users.hasActivePermission(scope.user().getUserId(), "APPOINTMENT_VIEW")) {
            return scope;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Tài khoản không có quyền xem danh sách yêu cầu tư vấn.");
    }

    private ReceptionAccess.Scope requireManageScope(HttpSession session) {
        ReceptionAccess.Scope scope = requireViewScope(session);
        if (scope.admin() || scope.canManage()) {
            return scope;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Tài khoản chỉ có quyền xem, không được phép xử lý hoặc chuyển đổi yêu cầu tư vấn.");
    }

    private BookingConsultationRequest lockAndAuthorizeConsultation(ReceptionAccess.Scope scope, Long requestId) {
        BookingConsultationRequest r = consultations.lockById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu tư vấn."));
        if (r.getBranch() == null || !scope.permits(r.getBranch().getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Bạn không có quyền thao tác trên yêu cầu tư vấn của chi nhánh khác.");
        }
        return r;
    }

    private List<Long> resolveScopedBranchIds(ReceptionAccess.Scope scope, Long requestedBranchId) {
        if (requestedBranchId != null) {
            if (!scope.permits(requestedBranchId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Bạn không có quyền truy cập chi nhánh đã chọn.");
            }
            branches.findById(requestedBranchId)
                    .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Chi nhánh không tồn tại hoặc đã ngừng hoạt động."));
            return List.of(requestedBranchId);
        }
        if (scope.admin()) {
            return null;
        }
        if (scope.branchIds() == null || scope.branchIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tài khoản chưa được phân công chi nhánh tiếp đón hợp lệ.");
        }
        return scope.branchIds();
    }

    private ConsultationSummary buildSummary(boolean filterByBranches, List<Long> branchQueryList) {
        List<Object[]> rows = consultations.countStatusBuckets(filterByBranches, branchQueryList);
        long newCount = 0;
        long contactedCount = 0;
        long convertedCount = 0;
        long closedCount = 0;
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || !(row[0] instanceof ConsultationRequestStatus st)) {
                continue;
            }
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            switch (st) {
                case NEW -> newCount += count;
                case CONTACTED -> contactedCount += count;
                case CONVERTED -> convertedCount += count;
                case CLOSED, CANCELLED -> closedCount += count;
            }
        }
        long openCount = newCount + contactedCount;
        long unclaimedNewCount = consultations.countUnclaimedNew(filterByBranches, branchQueryList);
        LocalDateTime latestUpdatedAt = consultations.findLatestUpdatedAt(filterByBranches, branchQueryList);
        return new ConsultationSummary(
                newCount, unclaimedNewCount, contactedCount, openCount, convertedCount, closedCount,
                latestUpdatedAt, LocalDateTime.now(CLINIC_ZONE)
        );
    }

    private boolean canAssignDoctor(ReceptionAccess.Scope scope) {
        if (scope == null || scope.user() == null) {
            return false;
        }
        return scope.admin() || users.hasActivePermission(scope.user().getUserId(), "CLINICAL_DISPATCH");
    }

    private List<ConsultationRequestStatus> parseFilterStatuses(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank() || "ALL".equalsIgnoreCase(rawStatus.trim())) {
            return List.of();
        }
        String norm = rawStatus.trim().toUpperCase(Locale.ROOT);
        if ("OPEN".equals(norm)) {
            return List.of(ConsultationRequestStatus.NEW, ConsultationRequestStatus.CONTACTED);
        }
        if ("CLOSED".equals(norm) || "CANCELLED".equals(norm)) {
            return List.of(ConsultationRequestStatus.CLOSED, ConsultationRequestStatus.CANCELLED);
        }
        try {
            return List.of(ConsultationRequestStatus.valueOf(norm));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái lọc không hợp lệ: " + rawStatus);
        }
    }

    private boolean isEligibleAssignee(User candidate, Long branchId) {
        if (candidate == null || candidate.getUserId() == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(candidate.getIsActive()) || !"ACTIVE".equalsIgnoreCase(candidate.getStatus())) {
            return false;
        }
        if (candidate.getUserType() == UserType.PATIENT) {
            return false;
        }
        Long uid = candidate.getUserId();
        boolean isAdmin = hasRole(candidate, "ADMIN") || users.hasActiveAdminRole(uid);
        if (isAdmin) {
            return true;
        }
        if (!users.hasActivePermission(uid, "APPOINTMENT_MANAGE")) {
            return false;
        }
        if (candidate.getBranch() != null && Objects.equals(candidate.getBranch().getBranchId(), branchId)
                && !Boolean.FALSE.equals(candidate.getBranch().getIsActive())) {
            return true;
        }
        List<Long> activeBranches = receptionAssignments.activeReceptionBranchIds(uid, LocalDate.now(CLINIC_ZONE));
        return activeBranches != null && activeBranches.contains(branchId);
    }

    private boolean hasRole(User user, String name) {
        if (user.getRoles() == null) {
            return false;
        }
        for (Role role : user.getRoles()) {
            if (role != null && Boolean.TRUE.equals(role.getIsActive()) && role.getRoleName() != null) {
                String actual = role.getRoleName().toUpperCase(Locale.ROOT);
                if (actual.equals(name) || actual.equals("ROLE_" + name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void saveLog(
            BookingConsultationRequest request,
            ConsultationLogAction action,
            ConsultationRequestStatus oldStatus,
            ConsultationRequestStatus newStatus,
            ConsultationContactResult contactResult,
            String notes,
            User actor,
            User target
    ) {
        BookingConsultationLog log = new BookingConsultationLog();
        log.setConsultationRequest(request);
        log.setAction(action);
        log.setOldStatus(oldStatus);
        log.setNewStatus(newStatus);
        log.setContactResult(contactResult);
        log.setNotes(cleanNote(notes, 1000));
        log.setActorUser(actor);
        log.setTargetUser(target);
        consultationLogs.saveAndFlush(log);
    }

    private void writeAuditLog(
            User actor,
            Branch branch,
            String action,
            Long entityId,
            HttpServletRequest httpRequest,
            Object oldVal,
            Object newVal
    ) {
        if (systemAuditLogService == null) {
            return;
        }
        String ip = httpRequest != null ? httpRequest.getRemoteAddr() : null;
        systemAuditLogService.logAction(actor, branch, action, "booking_consultation_requests", entityId, ip, oldVal, newVal);
    }

    private Map<String, Object> snapshotForAudit(BookingConsultationRequest r) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("requestCode", r.getRequestCode());
        snap.put("status", r.getStatus() != null ? r.getStatus().name() : null);
        snap.put("branchId", r.getBranch() != null ? r.getBranch().getBranchId() : null);
        snap.put("handledByUserId", r.getHandledByUser() != null ? r.getHandledByUser().getUserId() : null);
        snap.put("convertedAppointmentId", r.getConvertedAppointment() != null ? r.getConvertedAppointment().getAppointmentId() : null);
        snap.put("closeReason", r.getCloseReason());
        return snap;
    }

    private ConsultationDetail toDetail(BookingConsultationRequest r, boolean canManage, boolean canAssignDoctor) {
        List<LogItem> logs = consultationLogs.findHistoryByRequestId(r.getRequestId()).stream()
                .map(l -> new LogItem(
                        l.getLogId(),
                        l.getAction(),
                        l.getOldStatus() != null ? l.getOldStatus().name() : null,
                        l.getNewStatus() != null ? l.getNewStatus().name() : null,
                        l.getContactResult(),
                        l.getNotes(),
                        l.getActorUser().getUserId(),
                        userDisplayName(l.getActorUser()),
                        l.getTargetUser() != null ? l.getTargetUser().getUserId() : null,
                        l.getTargetUser() != null ? userDisplayName(l.getTargetUser()) : null,
                        l.getCreatedAt()
                ))
                .toList();

        Boolean doctorEligible = null;
        if (r.getPreferredDoctor() != null && r.getBranch() != null
                && r.getPreferredDate() != null && r.getPreferredSession() != null) {
            try {
                doctorEligible = appointmentPolicyService.doctorAvailable(
                        r.getBranch(), r.getPreferredDate(), r.getPreferredSession(), r.getPreferredDoctor().getStaffId());
            } catch (Exception ex) {
                doctorEligible = false;
            }
        }
        return new ConsultationDetail(toItem(r), logs, doctorEligible, canManage, canAssignDoctor);
    }

    private ConsultationItem toItem(BookingConsultationRequest r) {
        ConsultationRequestStatus effectiveStatus = r.getStatus() == ConsultationRequestStatus.CANCELLED
                ? ConsultationRequestStatus.CLOSED
                : r.getStatus();
        return new ConsultationItem(
                r.getRequestId(),
                r.getRequestCode(),
                r.getGuestName(),
                r.getGuestPhone(),
                r.getBranch() != null ? r.getBranch().getBranchId() : null,
                r.getBranch() != null ? r.getBranch().getBranchName() : null,
                r.getService() != null ? r.getService().getServiceId() : null,
                r.getService() != null ? r.getService().getServiceName() : null,
                r.getPreferredDoctor() != null ? r.getPreferredDoctor().getStaffId() : null,
                r.getPreferredDoctor() != null ? r.getPreferredDoctor().getFullName() : null,
                r.getPreferredDate(),
                r.getPreferredSession(),
                r.getPreferredTime(),
                r.getNotes(),
                effectiveStatus != null ? effectiveStatus.name() : "NEW",
                r.getHandledByUser() != null ? r.getHandledByUser().getUserId() : null,
                r.getHandledByUser() != null ? userDisplayName(r.getHandledByUser()) : null,
                r.getConvertedAppointment() != null ? r.getConvertedAppointment().getAppointmentId() : null,
                r.getConvertedAppointment() != null ? r.getConvertedAppointment().getAppointmentCode() : null,
                r.getClaimedAt(),
                r.getLastContactedAt(),
                r.getConvertedAt(),
                r.getClosedAt(),
                r.getCloseReason(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }

    private CandidateProfile toCandidateProfile(PatientProfile p, ReceptionAccess.Scope scope) {
        Long bId = p.getPreferredBranch() != null ? p.getPreferredBranch().getBranchId() : null;
        String bName = p.getPreferredBranch() != null ? p.getPreferredBranch().getBranchName() : null;
        boolean inScope = scope.admin() || (bId != null && scope.permits(bId))
                || (p.getProfileId() != null && patients.isProfileAccessibleInBranches(p.getProfileId(), scope.branchIds()));
        return new CandidateProfile(
                p.getProfileId(),
                p.getPatientCode(),
                p.getFullName(),
                p.getPhoneNumber(),
                p.getDob(),
                p.getGender(),
                bId,
                bName,
                !inScope
        );
    }

    private String userDisplayName(User user) {
        if (user == null) {
            return "";
        }
        if (staffProfiles != null && user.getUserId() != null) {
            String staffName = staffProfiles.findByUser_UserId(user.getUserId())
                    .map(StaffProfile::getFullName)
                    .filter(n -> !n.isBlank())
                    .orElse(null);
            if (staffName != null) {
                return staffName.trim();
            }
        }
        return user.getUsername() != null ? user.getUsername() : ("User#" + user.getUserId());
    }

    private static String displayStatus(ConsultationRequestStatus status) {
        if (status == null) return "";
        return status.isClosedEquivalent() ? "CLOSED" : status.name();
    }

    private static String cleanNote(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLen) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung nhập vượt quá " + maxLen + " ký tự.");
        }
        return trimmed;
    }
}
