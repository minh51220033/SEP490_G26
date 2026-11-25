package com.cvdcms.controller;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.AppointmentHistory;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AppointmentActorType;
import com.cvdcms.entity.enums.AppointmentSource;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.entity.enums.ServiceStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.UserType;
import com.cvdcms.repository.AppointmentHistoryRepository;
import com.cvdcms.repository.AppointmentRepository;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.RoleRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.AppointmentSlotService;
import com.cvdcms.service.PublicBookingRateLimiter;
import com.cvdcms.service.PublicConsultationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.Getter;
import lombok.Setter;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/booking")
public class PublicBookingRestController {

    private final BranchRepository branchRepository;
    private final ServiceRepository serviceRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final UserRepository userRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentHistoryRepository appointmentHistoryRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppointmentPolicyService appointmentPolicyService;
    private final AppointmentSlotService appointmentSlotService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.PortalAccess portalAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.PortalPatientService portalPatientService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.AppointmentBookingLockRepository appointmentBookingLockRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PublicBookingRateLimiter publicBookingRateLimiter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PublicConsultationService publicConsultationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ResourceBookingTransactionService resourceBookingTransactionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ResourceAvailabilityService resourceAvailabilityService;

    public PublicBookingRestController(
            BranchRepository branchRepository,
            ServiceRepository serviceRepository,
            StaffProfileRepository staffProfileRepository,
            UserRepository userRepository,
            PatientProfileRepository patientProfileRepository,
            AppointmentRepository appointmentRepository,
            AppointmentHistoryRepository appointmentHistoryRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            AppointmentPolicyService appointmentPolicyService,
            AppointmentSlotService appointmentSlotService
    ) {
        this.branchRepository = branchRepository;
        this.serviceRepository = serviceRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.userRepository = userRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.appointmentRepository = appointmentRepository;
        this.appointmentHistoryRepository = appointmentHistoryRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.appointmentPolicyService = appointmentPolicyService;
        this.appointmentSlotService = appointmentSlotService;
    }

    @Getter
    @Setter
    public static class QuickBookingForm {
        private Long branchId;
        private Long serviceId;
        private Long profileId;
        private Long doctorId;
        private String appointmentDate;
        private String preferredSession;
        private String slotTime;
        private String fullName;
        private String phoneNumber;
        private String note;
        private String requestKey;
    }

    /**
     * Trả ngữ cảnh đặt lịch cho tài khoản đang đăng nhập (danh sách hồ sơ bệnh nhân gia đình thuộc tài khoản).
     * Nếu chưa đăng nhập hoặc là tài khoản nhân sự -> không trả danh sách hồ sơ bệnh nhân.
     */
    @GetMapping("/context")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> bookingContext(HttpSession session) {
        Map<String, Object> resp = new HashMap<>();
        Object sessionUserObj = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(sessionUserObj instanceof User loggedIn) || loggedIn.getUserId() == null) {
            resp.put("authenticated", false);
            resp.put("isPatientUser", false);
            resp.put("profiles", Collections.emptyList());
            return ResponseEntity.ok(resp);
        }
        User dbUser = userRepository.findById(loggedIn.getUserId()).orElse(loggedIn);
        boolean patientAccount = isPatientAccount(dbUser);
        resp.put("authenticated", true);
        resp.put("isPatientUser", patientAccount);
        resp.put("preferredBranchId", dbUser.getBranch() != null ? dbUser.getBranch().getBranchId() : null);

        if (!patientAccount) {
            resp.put("profiles", Collections.emptyList());
            return ResponseEntity.ok(resp);
        }

        List<Map<String, Object>> profileList = new ArrayList<>();
        if (portalPatientService != null) {
            for (var p : portalPatientService.list(session)) {
                if (!"ACTIVE".equalsIgnoreCase(p.status())) continue;
                Map<String, Object> item = new HashMap<>();
                item.put("id", p.id());
                item.put("code", p.code());
                item.put("fullName", p.fullName());
                item.put("phoneNumber", p.phoneNumber());
                item.put("self", p.self());
                item.put("defaultProfile", p.defaultProfile());
                item.put("relation", p.self() ? "Bản thân"
                        : (p.guardianRelationship() != null && !p.guardianRelationship().isBlank()
                            ? p.guardianRelationship() : "Người phụ thuộc"));
                item.put("branchId", p.branchId());
                item.put("branchName", p.branchName());
                profileList.add(item);
            }
        } else {
            for (PatientProfile p : patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(dbUser.getUserId())) {
                if (!"ACTIVE".equalsIgnoreCase(p.getStatus())) continue;
                Map<String, Object> item = new HashMap<>();
                item.put("id", p.getProfileId());
                item.put("code", p.getPatientCode());
                item.put("fullName", p.getFullName());
                item.put("phoneNumber", p.getPhoneNumber());
                item.put("self", Boolean.TRUE.equals(p.getIsDefault()));
                item.put("defaultProfile", Boolean.TRUE.equals(p.getIsDefault()));
                item.put("relation", Boolean.TRUE.equals(p.getIsDefault()) ? "Bản thân" : "Người phụ thuộc");
                item.put("branchId", p.getPreferredBranch() != null ? p.getPreferredBranch().getBranchId() : null);
                item.put("branchName", p.getPreferredBranch() != null ? p.getPreferredBranch().getBranchName() : null);
                profileList.add(item);
            }
        }
        resp.put("profiles", profileList);
        return ResponseEntity.ok(resp);
    }

    private boolean isPatientAccount(User user) {
        if (user == null) return false;
        if (user.getUserType() == UserType.PATIENT) return true;
        if (user.getUserType() == UserType.STAFF) return false;
        return user.getRoles() != null && user.getRoles().stream()
                .anyMatch(r -> r != null && !Boolean.FALSE.equals(r.getIsActive()) && r.getRoleName() != null
                        && ("BENH_NHAN".equalsIgnoreCase(r.getRoleName())
                            || "ROLE_BENH_NHAN".equalsIgnoreCase(r.getRoleName())
                            || "PATIENT".equalsIgnoreCase(r.getRoleName())
                            || "ROLE_PATIENT".equalsIgnoreCase(r.getRoleName())));
    }

    /**
     * Endpoint kiểm tra khả năng tiếp nhận và danh sách bác sĩ ĐANG TRỰC của cơ sở
     * theo đúng chính sách và lịch làm việc (StaffSchedule, StaffBranchAssignment, LeaveRequest).
     */
    @GetMapping("/availability")
    public ResponseEntity<Map<String, Object>> checkAvailability(
            @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam PreferredSession sessionPeriod,
            @RequestParam(required = false) Long profileId,
            HttpSession session
    ) {
        Map<String, Object> resp = new HashMap<>();

        Branch branch = branchRepository.findById(branchId).orElse(null);
        if (branch == null || !Boolean.TRUE.equals(branch.getIsActive())) {
            resp.put("available", false);
            resp.put("reason", "Cơ sở phòng khám không tồn tại hoặc đã ngừng hoạt động.");
            resp.put("remainingBranch", 0);
            resp.put("doctors", Collections.emptyList());
            return ResponseEntity.ok(resp);
        }

        if (profileId != null && session != null && session.getAttribute("loggedInUser") instanceof User loggedIn && loggedIn.getUserId() != null) {
            try {
                PatientProfile verified = resolveVerifiedProfileForUser(loggedIn, profileId);
                if (verified != null) {
                    AppointmentPolicyService.PatientSessionConflict conflict = appointmentPolicyService.findSameSessionConflict(
                            verified.getProfileId(), date, sessionPeriod, null);
                    resp.put("sameSessionConflict", conflict.hasConflict());
                    if (conflict.hasConflict()) {
                        resp.put("conflictMessage", conflict.message());
                        resp.put("conflictAppointmentId", conflict.appointmentId());
                        resp.put("conflictAppointmentCode", conflict.appointmentCode());
                        resp.put("conflictBranchName", conflict.branchName());
                    }
                }
            } catch (ResponseStatusException ignored) {
                // Không tiết lộ thông tin hồ sơ không thuộc quyền tài khoản
            }
        }

        AppointmentPolicyService.Availability result;
        try {
            result = appointmentPolicyService.availability(
                    branch, date, sessionPeriod, null, AppointmentActorType.PATIENT
            );
        } catch (Exception ex) {
            resp.put("available", false);
            resp.put("reason", "Tạm thời chưa kiểm tra được lịch buổi này: " + ex.getMessage());
            resp.put("remainingBranch", 0);
            resp.put("doctors", Collections.emptyList());
            return ResponseEntity.ok(resp);
        }

        resp.put("available", result.available());
        resp.put("reason", result.reason());
        resp.put("remainingBranch", result.remainingBranch());

        List<Map<String, Object>> doctorOptions = new ArrayList<>();
        if (result.doctors() != null) {
            for (AppointmentPolicyService.DoctorOption d : result.doctors()) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", d.id());
                doc.put("name", d.name());
                doc.put("specialty", d.specialty());
                doc.put("remaining", d.remaining());
                doctorOptions.add(doc);
            }
        }
        resp.put("doctors", doctorOptions);

        return ResponseEntity.ok(resp);
    }

    /**
     * Endpoint lấy danh sách khung giờ 15 phút còn trống cho ca khám dựa theo thời lượng dịch vụ và bác sĩ.
     */
    @GetMapping("/slots")
    public ResponseEntity<List<Map<String, Object>>> getAvailableSlots(
            @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) PreferredSession sessionPeriod,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long serviceId
    ) {
        Branch branch = branchRepository.findById(branchId).filter(b -> Boolean.TRUE.equals(b.getIsActive())).orElse(null);
        if (branch == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        Service service = null;
        if (serviceId != null) {
            service = serviceRepository.findById(serviceId).orElse(null);
            if (service == null || !Boolean.TRUE.equals(service.getIsBookableOnline())
                    || service.getServiceType() == com.cvdcms.entity.enums.ServiceType.PRODUCT
                    || service.getStatus() != ServiceStatus.ACTIVE
                    || (service.getCategory() != null && Boolean.FALSE.equals(service.getCategory().getIsActive()))) {
                return ResponseEntity.ok(Collections.emptyList());
            }
        }
        int duration = AppointmentSlotService.duration(service);

        List<Map<String, Object>> resp = new ArrayList<>();
        if (sessionPeriod == null && doctorId != null) {
            var availability = appointmentSlotService.getDoctorAvailability(branchId, doctorId, date, duration, null);
            for (var s : availability.suggestedSlots()) {
                Map<String, Object> item = new HashMap<>();
                item.put("time", s.startTime().toString().substring(0, 5));
                item.put("endTime", s.endTime().toString().substring(0, 5));
                item.put("remaining", 1);
                resp.add(item);
            }
            return ResponseEntity.ok(resp);
        }

        List<AppointmentSlotService.Slot> slots = appointmentSlotService.available(
                branch, date, sessionPeriod, doctorId, duration, null, AppointmentActorType.PATIENT
        );

        boolean useResourceEngine = Boolean.TRUE.equals(branch.getResourceEngineEnabled())
                && service != null && resourceAvailabilityService != null;
        for (AppointmentSlotService.Slot s : slots) {
            if (useResourceEngine) {
                var avail = resourceAvailabilityService.checkAvailability(
                        new com.cvdcms.DTO.availability.AvailabilityCheckRequest(
                                branchId, service.getServiceId(), date, s.time(), doctorId, null, false));
                if (!avail.available()) {
                    continue;
                }
            }
            Map<String, Object> item = new HashMap<>();
            item.put("time", s.time().toString().substring(0, 5));
            item.put("endTime", s.time().plusMinutes(duration).toString().substring(0, 5));
            item.put("remaining", s.remaining());
            resp.add(item);
        }

        return ResponseEntity.ok(resp);
    }

    /**
     * Endpoint lấy danh sách ca khám và giờ làm việc cấu hình thực tế của chi nhánh.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> getSessions(@RequestParam Long branchId) {
        Branch branch = branchRepository.findById(branchId)
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .orElse(null);
        if (branch == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<AppointmentPolicyService.SessionWindowInfo> list = appointmentPolicyService.sessionWindows(branch);
        List<Map<String, Object>> result = new ArrayList<>();
        LocalTime now = LocalTime.now(AppointmentPolicyService.CLINIC_ZONE);
        for (var s : list) {
            Map<String, Object> item = new HashMap<>();
            item.put("session", s.session().name());
            item.put("name", s.sessionName());
            item.put("startTime", s.startTime() != null ? s.startTime().toString().substring(0, 5) : "");
            item.put("endTime", s.endTime() != null ? s.endTime().toString().substring(0, 5) : "");
            item.put("label", s.label());
            item.put("active", s.active());
            boolean endedToday = s.endTime() != null && !now.isBefore(s.endTime());
            if (branch.getClosingTime() != null && !now.isBefore(branch.getClosingTime())) {
                endedToday = true;
            }
            item.put("endedToday", endedToday);
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Tiếp nhận đăng ký đặt lịch từ Drawer trang chủ.
     * - Bệnh nhân đã đăng nhập: đặt lịch Appointment chính thức qua AppointmentPolicyService &amp; AppointmentSlotService.
     * - Khách chưa đăng nhập (Guest): kiểm tra SĐT đã tồn tại (không tạo Appointment/không gắn hồ sơ/không lộ thông tin)
     *   hoặc lưu yêu cầu tư vấn riêng vào booking_consultation_requests cho khách mới.
     */
    public ResponseEntity<Map<String, Object>> submitBooking(HttpSession session, QuickBookingForm form) {
        return submitBooking(session, null, form);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submitBooking(
            HttpSession session,
            HttpServletRequest httpRequest,
            @RequestBody QuickBookingForm form
    ) {
        try {
            if (publicBookingRateLimiter != null) {
                publicBookingRateLimiter.checkIpAndSession(httpRequest, session);
            }

            if (form == null) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Dữ liệu đặt lịch không hợp lệ.");
            }

            // 1. Kiểm tra chi nhánh
            if (form.getBranchId() == null || form.getBranchId() <= 0) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Vui lòng chọn cơ sở phòng khám tiếp nhận.");
            }
            Branch branch = branchRepository.findById(form.getBranchId()).orElse(null);
            if (branch == null || !Boolean.TRUE.equals(branch.getIsActive())) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Cơ sở phòng khám đã chọn hiện không hoạt động.");
            }

            // 2. Kiểm tra ngày hẹn
            LocalDate bookDate;
            try {
                if (form.getAppointmentDate() == null || form.getAppointmentDate().isBlank()) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "Ngày khám không đúng định dạng (YYYY-MM-DD).");
                }
                bookDate = LocalDate.parse(form.getAppointmentDate().trim());
            } catch (Exception e) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Ngày khám không đúng định dạng (YYYY-MM-DD).");
            }
            LocalDate clinicToday = LocalDate.now(AppointmentPolicyService.CLINIC_ZONE);
            if (bookDate.isBefore(clinicToday)) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Ngày khám không được ở quá khứ.");
            }
            if (bookDate.isAfter(clinicToday.plusDays(90))) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Chỉ hỗ trợ đặt lịch trong vòng 90 ngày tới.");
            }

            // 3. Kiểm tra buổi khám hợp lệ
            PreferredSession sessionPeriod;
            try {
                if (form.getPreferredSession() == null || form.getPreferredSession().isBlank()) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "Vui lòng chọn buổi khám.");
                }
                sessionPeriod = PreferredSession.valueOf(form.getPreferredSession().trim().toUpperCase());
                if (sessionPeriod == PreferredSession.ANY) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "Vui lòng chọn buổi khám cụ thể (Sáng, Chiều hoặc Tối).");
                }
            } catch (Exception e) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Buổi khám không hợp lệ.");
            }

            // 4. Kiểm tra dịch vụ (nếu có chọn) - không bỏ qua khi serviceId không tồn tại hoặc ngừng hoạt động
            Service service = null;
            if (form.getServiceId() != null) {
                service = serviceRepository.findWithCategoryByServiceId(form.getServiceId()).orElse(null);
                if (service == null || !Boolean.TRUE.equals(service.getIsBookableOnline())
                        || service.getServiceType() == com.cvdcms.entity.enums.ServiceType.PRODUCT
                        || service.getStatus() != ServiceStatus.ACTIVE
                        || (service.getCategory() != null && Boolean.FALSE.equals(service.getCategory().getIsActive()))) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "Dịch vụ đã chọn hiện không nhận đặt trực tuyến.");
                }
            }

            // 5. Kiểm tra bác sĩ (nếu có chọn)
            StaffProfile doctor = null;
            if (form.getDoctorId() != null) {
                doctor = staffProfileRepository.findById(form.getDoctorId()).orElse(null);
                if (doctor == null || doctor.getStatus() != StaffStatus.ACTIVE) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "Bác sĩ đã chọn không tồn tại hoặc đang ngừng hoạt động.");
                }
            }

            // 6. Kiểm tra định dạng giờ hẹn (nếu có) và độ dài ghi chú
            LocalTime requestedSlot = null;
            if (form.getSlotTime() != null && !form.getSlotTime().isBlank()) {
                try {
                    String rawTime = form.getSlotTime().trim();
                    if (rawTime.length() == 5) {
                        rawTime += ":00";
                    }
                    requestedSlot = LocalTime.parse(rawTime);
                } catch (Exception e) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "Khung giờ hẹn không đúng định dạng (HH:mm).");
                }
            }
            String cleanNote = form.getNote() != null && !form.getNote().isBlank() ? form.getNote().trim() : null;
            if (cleanNote != null && cleanNote.length() > 500) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Ghi chú không được vượt quá 500 ký tự.");
            }

            // 7. Phân tách Bệnh nhân đã đăng nhập vs Guest chưa đăng nhập
            User sessionAccount = resolveActiveSessionAccount(session);
            boolean loggedInPatient = sessionAccount != null && isPatientAccount(sessionAccount);

            if (!loggedInPatient) {
                return handleGuestConsultationSubmission(
                        form, branch, doctor, service, bookDate, sessionPeriod, requestedSlot, cleanNote);
            }

            return executeLoggedInBookingInTransaction(
                    sessionAccount, form, branch, doctor, service, bookDate, sessionPeriod, requestedSlot, cleanNote);
        } catch (ResponseStatusException ex) {
            return errorResponse(HttpStatus.valueOf(ex.getStatusCode().value()),
                    ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu đặt lịch.");
        }
    }

    private User resolveActiveSessionAccount(HttpSession session) {
        Object sessionUserObj = session != null ? session.getAttribute("loggedInUser") : null;
        if (sessionUserObj instanceof User loggedIn && loggedIn.getUserId() != null) {
            User dbUser = userRepository.findById(loggedIn.getUserId()).orElse(null);
            if (dbUser != null && Boolean.TRUE.equals(dbUser.getIsActive())
                    && "ACTIVE".equalsIgnoreCase(dbUser.getStatus())) {
                return dbUser;
            }
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> handleGuestConsultationSubmission(
            QuickBookingForm form,
            Branch branch,
            StaffProfile doctor,
            Service service,
            LocalDate bookDate,
            PreferredSession sessionPeriod,
            LocalTime requestedSlot,
            String cleanNote
    ) {
        // Không bao giờ cho phép Guest tự truyền profileId để gán vào hồ sơ người khác
        if (form.getProfileId() != null) {
            return errorResponse(HttpStatus.FORBIDDEN, "Vui lòng đăng nhập tài khoản bệnh nhân để đặt lịch cho hồ sơ đã có.");
        }

        String fullName = form.getFullName() != null ? form.getFullName().trim() : "";
        if (fullName.isEmpty() || fullName.length() > 150) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Vui lòng nhập họ và tên hợp lệ (tối đa 150 ký tự).");
        }

        String normalizedPhone = PublicConsultationService.normalizeVietnamPhone(form.getPhoneNumber());
        if (!PublicConsultationService.isValidVietnamPhone(normalizedPhone)) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Số điện thoại không hợp lệ (gồm 10 hoặc 11 chữ số, bắt đầu bằng 0).");
        }

        if (!PublicConsultationService.isValidRequestKey(form.getRequestKey())) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Mã yêu cầu đặt lịch (requestKey) không hợp lệ.");
        }

        if (publicBookingRateLimiter != null) {
            publicBookingRateLimiter.checkPhone(normalizedPhone);
        }

        PublicConsultationService consultationHandler = publicConsultationService;
        if (consultationHandler == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ tiếp nhận tư vấn hiện chưa sẵn sàng.");
        }

        PublicConsultationService.GuestSubmissionOutcome outcome = consultationHandler.submitGuestConsultation(
                new PublicConsultationService.ConsultationCommand(
                        form.getRequestKey().trim(),
                        branch,
                        doctor,
                        service,
                        fullName,
                        normalizedPhone,
                        bookDate,
                        sessionPeriod,
                        requestedSlot,
                        cleanNote
                )
        );

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("consultationMode", outcome.consultationMode());
        resp.put("loginUrl", outcome.loginUrl());
        resp.put("message", outcome.message());
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<Map<String, Object>> executeLoggedInBookingInTransaction(
            User sessionAccount,
            QuickBookingForm form,
            Branch branch,
            StaffProfile doctor,
            Service service,
            LocalDate bookDate,
            PreferredSession sessionPeriod,
            LocalTime requestedSlot,
            String cleanNote
    ) {
        if (Boolean.TRUE.equals(branch.getResourceEngineEnabled())) {
            return executeLoggedInBookingWithResourceEngine(
                    sessionAccount, form, branch, doctor, service, bookDate, sessionPeriod, requestedSlot, cleanNote);
        }
        if (transactionManager != null && !TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            int maxRetries = 2;
            for (int attempt = 0; ; attempt++) {
                try {
                    return tx.execute(status -> persistLoggedInBooking(
                            sessionAccount, form, branch, doctor, service, bookDate, sessionPeriod, requestedSlot, cleanNote));
                } catch (PessimisticLockingFailureException ex) {
                    if (attempt >= maxRetries) {
                        return errorResponse(HttpStatus.CONFLICT, "Khung giờ đang có người khác thao tác đồng thời. Vui lòng thử lại.");
                    }
                }
            }
        }
        return persistLoggedInBooking(
                sessionAccount, form, branch, doctor, service, bookDate, sessionPeriod, requestedSlot, cleanNote);
    }

    private ResponseEntity<Map<String, Object>> executeLoggedInBookingWithResourceEngine(
            User sessionAccount,
            QuickBookingForm form,
            Branch branch,
            StaffProfile doctor,
            Service service,
            LocalDate bookDate,
            PreferredSession sessionPeriod,
            LocalTime requestedSlot,
            String cleanNote
    ) {
        if (resourceBookingTransactionService == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Resource Booking Engine chưa sẵn sàng.");
        }
        if (form.getProfileId() == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Vui lòng chọn hồ sơ người đi khám.");
        }
        if (service == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Vui lòng chọn dịch vụ khám trước khi đặt lịch.");
        }
        if (requestedSlot == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Vui lòng chọn khung giờ khám cụ thể.");
        }
        PatientProfile profile;
        try {
            profile = resolveVerifiedProfileForUser(sessionAccount, form.getProfileId());
        } catch (ResponseStatusException ex) {
            return errorResponse(HttpStatus.FORBIDDEN, ex.getStatusCode() == HttpStatus.NOT_FOUND
                    ? "Không tìm thấy hồ sơ người đi khám thuộc tài khoản của bạn."
                    : (ex.getReason() != null ? ex.getReason() : "Không có quyền đặt lịch cho hồ sơ này."));
        }
        if (profile == null) {
            return errorResponse(HttpStatus.FORBIDDEN, "Không tìm thấy hồ sơ người đi khám thuộc tài khoản của bạn.");
        }
        if (!"ACTIVE".equalsIgnoreCase(profile.getStatus())) {
            return errorResponse(HttpStatus.CONFLICT, "Hồ sơ bệnh nhân đã ngừng hoạt động.");
        }
        try {
            if (transactionManager != null) {
                TransactionTemplate readTx = new TransactionTemplate(transactionManager);
                readTx.setReadOnly(true);
                readTx.executeWithoutResult(status -> appointmentPolicyService.ensureBookable(
                        branch, profile, bookDate, sessionPeriod,
                        doctor != null ? doctor.getStaffId() : null,
                        null, AppointmentActorType.PATIENT));
            } else {
                appointmentPolicyService.ensureBookable(
                        branch, profile, bookDate, sessionPeriod,
                        doctor != null ? doctor.getStaffId() : null,
                        null, AppointmentActorType.PATIENT);
            }
        } catch (ResponseStatusException ex) {
            return errorResponse(HttpStatus.valueOf(ex.getStatusCode().value()),
                    ex.getReason() != null ? ex.getReason() : "Lịch khám hoặc bác sĩ không khả dụng.");
        }

        String idempotencyKey = (form.getRequestKey() != null && !form.getRequestKey().isBlank())
                ? "PUB:" + form.getRequestKey().trim() : null;
        var req = new com.cvdcms.DTO.availability.ResourceBookingRequest(
                branch.getBranchId(),
                profile.getProfileId(),
                service.getServiceId(),
                bookDate,
                requestedSlot,
                doctor != null ? doctor.getStaffId() : null,
                doctor == null,
                null,
                sessionPeriod,
                AppointmentSource.ONLINE,
                cleanNote,
                idempotencyKey,
                false);
        var result = resourceBookingTransactionService.bookWithRetry(req, AppointmentStatus.PENDING);

        if (!result.idempotentReplay()) {
            appointmentRepository.findById(result.appointmentId()).ifPresent(appt -> {
                AppointmentHistory history = new AppointmentHistory();
                history.setAppointment(appt);
                history.setActorUserId(sessionAccount.getUserId());
                history.setAction("CREATE");
                history.setNewValue(bookDate + "|" + sessionPeriod + "|doctor=" + result.doctorId()
                        + "|room=" + result.roomId() + "|time=" + requestedSlot);
                appointmentHistoryRepository.save(history);
            });
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("appointmentId", result.appointmentId());
        resp.put("appointmentCode", result.appointmentCode());
        resp.put("consultationMode", false);
        resp.put("idempotentReplay", result.idempotentReplay());
        resp.put("message", "Đặt lịch hẹn thành công! Mã lịch hẹn của bạn là #" + result.appointmentCode()
                + ". Lễ tân sẽ xác nhận lại qua số điện thoại.");
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<Map<String, Object>> persistLoggedInBooking(
            User sessionAccount,
            QuickBookingForm form,
            Branch initialBranch,
            StaffProfile initialDoctor,
            Service service,
            LocalDate bookDate,
            PreferredSession sessionPeriod,
            LocalTime requestedSlot,
            String cleanNote
    ) {
        if (form.getProfileId() == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Vui lòng chọn hồ sơ người đi khám.");
        }

        PatientProfile profile;
        try {
            profile = resolveVerifiedProfileForUser(sessionAccount, form.getProfileId());
        } catch (ResponseStatusException ex) {
            return errorResponse(HttpStatus.FORBIDDEN, ex.getStatusCode() == HttpStatus.NOT_FOUND
                    ? "Không tìm thấy hồ sơ người đi khám thuộc tài khoản của bạn."
                    : (ex.getReason() != null ? ex.getReason() : "Không có quyền đặt lịch cho hồ sơ này."));
        }
        if (profile == null) {
            return errorResponse(HttpStatus.FORBIDDEN, "Không tìm thấy hồ sơ người đi khám thuộc tài khoản của bạn.");
        }
        if (!"ACTIVE".equalsIgnoreCase(profile.getStatus())) {
            return errorResponse(HttpStatus.CONFLICT, "Hồ sơ bệnh nhân đã ngừng hoạt động.");
        }

        // Global lock hierarchy: 1. PatientProfile -> 2. Branch -> 3. StaffProfile -> 4. Appointment
        if (profile.getProfileId() != null) {
            profile = patientProfileRepository.findByIdForUpdate(profile.getProfileId()).orElse(profile);
        }
        Branch branch = initialBranch;
        if (appointmentBookingLockRepository != null && branch.getBranchId() != null) {
            branch = appointmentBookingLockRepository.findByIdForBooking(branch.getBranchId()).orElse(branch);
        }

        String rawRequestKey = form.getRequestKey() != null && !form.getRequestKey().isBlank()
                ? form.getRequestKey().trim() : null;
        String idempotentCode = rawRequestKey != null
                ? com.cvdcms.service.ResourceBookingTransactionService.deriveIdempotentAppointmentCode("PUB:" + rawRequestKey)
                : null;
        if (idempotentCode != null) {
            Optional<Appointment> existingOpt = appointmentRepository.findByAppointmentCode(idempotentCode);
            if (existingOpt.isPresent()) {
                Appointment existing = existingOpt.get();
                Long exProfileId = existing.getPatientProfile() != null ? existing.getPatientProfile().getProfileId() : null;
                Long exBranchId = existing.getBranch() != null ? existing.getBranch().getBranchId() : null;
                Long exServiceId = existing.getService() != null ? existing.getService().getServiceId() : null;
                Long exDoctorId = existing.getDoctor() != null ? existing.getDoctor().getStaffId() : null;
                Long reqServiceId = service != null ? service.getServiceId() : null;
                Long reqDoctorId = initialDoctor != null ? initialDoctor.getStaffId() : null;
                boolean samePayload = java.util.Objects.equals(exProfileId, profile.getProfileId())
                        && java.util.Objects.equals(exBranchId, branch.getBranchId())
                        && java.util.Objects.equals(exServiceId, reqServiceId)
                        && java.util.Objects.equals(existing.getAppointmentDate(), bookDate)
                        && existing.getPreferredSession() == sessionPeriod
                        && (requestedSlot == null || java.util.Objects.equals(existing.getExpectedTime(), requestedSlot))
                        && (reqDoctorId == null || java.util.Objects.equals(exDoctorId, reqDoctorId));
                if (!samePayload) {
                    return errorResponse(HttpStatus.CONFLICT, "Mã yêu cầu (requestKey) đã được sử dụng cho thông tin lịch hẹn khác.");
                }
                Map<String, Object> replayResp = new HashMap<>();
                replayResp.put("success", true);
                replayResp.put("appointmentId", existing.getAppointmentId());
                replayResp.put("appointmentCode", existing.getAppointmentCode());
                replayResp.put("branchId", branch.getBranchId());
                replayResp.put("branchName", branch.getBranchName());
                replayResp.put("idempotentReplay", true);
                replayResp.put("message", "Lịch hẹn đã được ghi nhận trước đó (#" + existing.getAppointmentCode() + ").");
                return ResponseEntity.ok(replayResp);
            }
        }

        StaffProfile doctor = initialDoctor;
        try {
            appointmentPolicyService.ensureBookable(
                    branch,
                    profile,
                    bookDate,
                    sessionPeriod,
                    doctor != null ? doctor.getStaffId() : null,
                    null,
                    AppointmentActorType.PATIENT
            );
        } catch (ResponseStatusException ex) {
            return errorResponse(HttpStatus.valueOf(ex.getStatusCode().value()),
                    ex.getReason() != null ? ex.getReason() : "Lịch khám hoặc bác sĩ không khả dụng.");
        } catch (Exception ex) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Lỗi kiểm tra tính khả dụng.");
        }

        int durationMinutes = AppointmentSlotService.duration(service);
        LocalTime resolvedExpectedTime;
        try {
            resolvedExpectedTime = appointmentSlotService.resolveFlexibly(
                    branch,
                    bookDate,
                    sessionPeriod,
                    doctor != null ? doctor.getStaffId() : null,
                    durationMinutes,
                    null,
                    requestedSlot,
                    AppointmentActorType.PATIENT
            );
        } catch (ResponseStatusException ex) {
            return errorResponse(HttpStatus.valueOf(ex.getStatusCode().value()),
                    ex.getReason() != null ? ex.getReason() : "Khung giờ hẹn không hợp lệ.");
        }

        if (doctor == null && resolvedExpectedTime != null) {
            try {
                doctor = appointmentSlotService.autoAssignDoctorForSlot(
                        branch, bookDate, sessionPeriod, resolvedExpectedTime, durationMinutes, null);
            } catch (ResponseStatusException ex) {
                return errorResponse(HttpStatus.valueOf(ex.getStatusCode().value()),
                        ex.getReason() != null ? ex.getReason() : "Không còn bác sĩ khả dụng cho khung giờ đã chọn.");
            }
        } else if (doctor != null && resolvedExpectedTime == null) {
            try {
                appointmentSlotService.lockAndValidateDoctorSession(
                        branch.getBranchId(), doctor.getStaffId(), bookDate, sessionPeriod, null);
            } catch (ResponseStatusException ex) {
                return errorResponse(HttpStatus.valueOf(ex.getStatusCode().value()),
                        ex.getReason() != null ? ex.getReason() : "Bác sĩ hoặc ca khám không còn sức chứa khả dụng.");
            }
        }
        if (resolvedExpectedTime != null) {
            try {
                appointmentSlotService.validateBranchResourceCapacity(
                        branch, service, bookDate,
                        resolvedExpectedTime, resolvedExpectedTime.plusMinutes(durationMinutes), null);
            } catch (ResponseStatusException ex) {
                return errorResponse(HttpStatus.valueOf(ex.getStatusCode().value()),
                        ex.getReason() != null ? ex.getReason() : "Tài nguyên phòng khám không còn đủ cho khung giờ đã chọn.");
            }
        }

        String appointmentCode = idempotentCode != null
                ? idempotentCode
                : "LH" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();

        Appointment appointment = new Appointment();
        appointment.setAppointmentCode(appointmentCode);
        appointment.setBranch(branch);
        appointment.setPatientProfile(profile);
        appointment.setDoctor(doctor);
        appointment.setIsDoctorRequested(form.getDoctorId() != null);
        appointment.setService(service);
        appointment.setAppointmentDate(bookDate);
        appointment.setPreferredSession(sessionPeriod);
        appointment.setExpectedTime(resolvedExpectedTime);
        appointment.setDurationMinutes(durationMinutes);
        appointment.syncExpectedEndTime();
        appointment.setInitialSymptoms(cleanNote);
        appointment.setSource(AppointmentSource.ONLINE);
        appointment.setStatus(AppointmentStatus.PENDING);

        appointment = appointmentRepository.saveAndFlush(appointment);

        try {
            AppointmentHistory history = new AppointmentHistory();
            history.setAppointment(appointment);
            history.setActorUserId(sessionAccount.getUserId());
            history.setAction("CREATE");
            history.setNewValue(bookDate + "|" + sessionPeriod + "|doctor=" + (doctor != null ? doctor.getStaffId() : "null")
                    + "|time=" + resolvedExpectedTime + "|status=PENDING");
            history.setReason("Bệnh nhân đặt lịch trực tuyến nhanh qua Drawer trang chủ");
            appointmentHistoryRepository.save(history);
        } catch (Exception ignored) {}

        String sessionName = switch (sessionPeriod) {
            case MORNING -> "Buổi sáng";
            case AFTERNOON -> "Buổi chiều";
            case EVENING -> "Buổi tối";
            default -> "Buổi khám";
        };

        String timeInfo = resolvedExpectedTime != null ? " lúc " + resolvedExpectedTime.toString().substring(0, 5) : "";
        String docInfo = doctor != null ? " với " + doctor.getFullName() : "";

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("appointmentId", appointment.getAppointmentId());
        resp.put("appointmentCode", appointmentCode);
        resp.put("branchId", branch.getBranchId());
        resp.put("branchName", branch.getBranchName());
        resp.put("message", "Đặt lịch thành công (" + sessionName + timeInfo + docInfo + ") tại " + branch.getBranchName() + ". Lịch hẹn đã sẵn sàng trên màn hình Quản lý lịch hẹn của Lễ tân.");
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", false);
        resp.put("message", message);
        return ResponseEntity.status(status).body(resp);
    }

    private PatientProfile resolveVerifiedProfileForUser(User user, Long profileId) {
        if (user == null || user.getUserId() == null || profileId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền sử dụng hồ sơ bệnh nhân này.");
        }
        if (portalAccess != null) {
            return portalAccess.requireProfile(user, profileId);
        }
        return patientProfileRepository.findByProfileIdAndUser_UserId(profileId, user.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy hồ sơ bệnh nhân thuộc tài khoản này."));
    }
}
