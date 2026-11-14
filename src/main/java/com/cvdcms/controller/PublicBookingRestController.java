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
import jakarta.servlet.http.HttpSession;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
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
        private Long doctorId;
        private String appointmentDate;
        private String preferredSession;
        private String slotTime;
        private String fullName;
        private String phoneNumber;
        private String note;
    }

    /**
     * Endpoint kiểm tra khả năng tiếp nhận và danh sách bác sĩ ĐANG TRỰC của cơ sở
     * theo đúng chính sách và lịch làm việc (StaffSchedule, StaffBranchAssignment, LeaveRequest).
     */
    @GetMapping("/availability")
    public ResponseEntity<Map<String, Object>> checkAvailability(
            @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam PreferredSession sessionPeriod
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
            @RequestParam PreferredSession sessionPeriod,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long serviceId
    ) {
        Branch branch = branchRepository.findById(branchId).filter(b -> Boolean.TRUE.equals(b.getIsActive())).orElse(null);
        if (branch == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        Service service = serviceId != null ? serviceRepository.findById(serviceId).orElse(null) : null;
        int duration = AppointmentSlotService.duration(service);

        List<AppointmentSlotService.Slot> slots = appointmentSlotService.available(
                branch, date, sessionPeriod, doctorId, duration, null, AppointmentActorType.PATIENT
        );

        List<Map<String, Object>> resp = new ArrayList<>();
        for (AppointmentSlotService.Slot s : slots) {
            Map<String, Object> item = new HashMap<>();
            item.put("time", s.time().toString().substring(0, 5));
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
        Branch branch = branchRepository.findById(branchId).orElse(null);
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
     * Tiếp nhận đăng ký lịch khám trực tuyến từ Drawer trên trang chủ.
     * Áp dụng đầy đủ quy tắc kiểm tra bác sĩ trực, công suất ca khám và ràng buộc chính sách.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> submitBooking(
            HttpSession session,
            @RequestBody QuickBookingForm form
    ) {
        Map<String, Object> resp = new HashMap<>();

        if (form == null) {
            resp.put("success", false);
            resp.put("message", "Dữ liệu đặt lịch không hợp lệ.");
            return ResponseEntity.badRequest().body(resp);
        }

        // 1. Kiểm tra họ tên và số điện thoại
        String fullName = form.getFullName() != null ? form.getFullName().trim() : "";
        String rawPhone = form.getPhoneNumber() != null ? form.getPhoneNumber().trim() : "";
        String cleanPhone = rawPhone.replaceAll("[^0-9]", "");

        if (fullName.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "Vui lòng nhập họ và tên của Quý khách.");
            return ResponseEntity.badRequest().body(resp);
        }

        if (cleanPhone.length() < 9 || cleanPhone.length() > 11) {
            resp.put("success", false);
            resp.put("message", "Số điện thoại không hợp lệ (yêu cầu từ 9 đến 11 chữ số).");
            return ResponseEntity.badRequest().body(resp);
        }

        // 2. Kiểm tra chi nhánh
        if (form.getBranchId() == null) {
            resp.put("success", false);
            resp.put("message", "Vui lòng chọn cơ sở phòng khám tiếp nhận.");
            return ResponseEntity.badRequest().body(resp);
        }

        Branch branch = branchRepository.findById(form.getBranchId()).orElse(null);
        if (branch == null || !Boolean.TRUE.equals(branch.getIsActive())) {
            resp.put("success", false);
            resp.put("message", "Cơ sở phòng khám đã chọn hiện không hoạt động.");
            return ResponseEntity.badRequest().body(resp);
        }

        // 3. Kiểm tra ngày hẹn
        LocalDate bookDate;
        try {
            bookDate = LocalDate.parse(form.getAppointmentDate());
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "Ngày khám không đúng định dạng (YYYY-MM-DD).");
            return ResponseEntity.badRequest().body(resp);
        }

        if (bookDate.isBefore(LocalDate.now(AppointmentPolicyService.CLINIC_ZONE))) {
            resp.put("success", false);
            resp.put("message", "Ngày khám không được ở quá khứ.");
            return ResponseEntity.badRequest().body(resp);
        }

        // 4. Kiểm tra buổi khám
        PreferredSession sessionPeriod;
        try {
            sessionPeriod = form.getPreferredSession() != null
                    ? PreferredSession.valueOf(form.getPreferredSession().trim().toUpperCase())
                    : PreferredSession.AFTERNOON;
        } catch (Exception e) {
            sessionPeriod = PreferredSession.AFTERNOON;
        }

        // 5. Kiểm tra dịch vụ (nếu có chọn)
        Service service = null;
        if (form.getServiceId() != null) {
            service = serviceRepository.findById(form.getServiceId()).orElse(null);
            if (service != null && (!Boolean.TRUE.equals(service.getIsBookableOnline())
                    || service.getStatus() != com.cvdcms.entity.enums.ServiceStatus.ACTIVE)) {
                resp.put("success", false);
                resp.put("message", "Dịch vụ đã chọn hiện không nhận đặt trực tuyến.");
                return ResponseEntity.badRequest().body(resp);
            }
        }

        // 6. Kiểm tra bác sĩ (nếu có yêu cầu bác sĩ đích danh)
        StaffProfile doctor = null;
        if (form.getDoctorId() != null) {
            doctor = staffProfileRepository.findById(form.getDoctorId()).orElse(null);
            if (doctor == null) {
                resp.put("success", false);
                resp.put("message", "Bác sĩ đã chọn không tồn tại trong hệ thống.");
                return ResponseEntity.badRequest().body(resp);
            }
        }

        // 7. Xác định hoặc khởi tạo User & PatientProfile
        PatientProfile profile;
        User user = null;
        Object sessionUserObj = session != null ? session.getAttribute("loggedInUser") : null;
        if (sessionUserObj instanceof User loggedIn && loggedIn.getUserId() != null) {
            user = userRepository.findById(loggedIn.getUserId()).orElse(null);
        }

        if (user != null && user.getUserType() == UserType.PATIENT) {
            List<PatientProfile> userProfiles = patientProfileRepository.findAllByUser_UserId(user.getUserId());
            if (!userProfiles.isEmpty()) {
                profile = userProfiles.get(0);
            } else {
                profile = createPatientProfileForUser(user, fullName, cleanPhone);
            }
        } else {
            Optional<User> existingUserOpt = userRepository.findByPhoneNumber(cleanPhone);
            if (existingUserOpt.isPresent()) {
                user = existingUserOpt.get();
                List<PatientProfile> existingProfiles = patientProfileRepository.findAllByUser_UserId(user.getUserId());
                if (!existingProfiles.isEmpty()) {
                    profile = existingProfiles.get(0);
                } else {
                    profile = createPatientProfileForUser(user, fullName, cleanPhone);
                }
            } else {
                user = createGuestPatientUser(fullName, cleanPhone);
                profile = createPatientProfileForUser(user, fullName, cleanPhone);
            }
        }

        // 8. KIỂM TRA CHÍNH SÁCH ĐẶT LỊCH CHUẨN XÁC QUA AppointmentPolicyService
        // Đảm bảo: bác sĩ có lịch làm việc (StaffSchedule), được phân công tại chi nhánh (StaffBranchAssignment),
        // không nghỉ phép (LeaveRequest), buổi khám chưa đầy định mức và bệnh nhân chưa có lịch trùng.
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
            resp.put("success", false);
            resp.put("message", ex.getReason() != null ? ex.getReason() : "Lịch khám hoặc bác sĩ không khả dụng.");
            return ResponseEntity.status(ex.getStatusCode()).body(resp);
        } catch (Exception ex) {
            resp.put("success", false);
            resp.put("message", "Lỗi kiểm tra tính khả dụng: " + ex.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }

        // 9. Xử lý khung giờ hẹn dự kiến qua AppointmentSlotService
        int durationMinutes = AppointmentSlotService.duration(service);
        LocalTime requestedSlot = null;
        if (form.getSlotTime() != null && !form.getSlotTime().isBlank()) {
            try {
                String rawTime = form.getSlotTime().trim();
                if (rawTime.length() == 5) rawTime += ":00";
                requestedSlot = LocalTime.parse(rawTime);
            } catch (Exception ignored) {}
        }

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
            resp.put("success", false);
            resp.put("message", ex.getReason() != null ? ex.getReason() : "Khung giờ hẹn không hợp lệ.");
            return ResponseEntity.status(ex.getStatusCode()).body(resp);
        }

        // 10. Tạo và lưu Appointment
        String appointmentCode = "LH" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();

        Appointment appointment = new Appointment();
        appointment.setAppointmentCode(appointmentCode);
        appointment.setBranch(branch);
        appointment.setPatientProfile(profile);
        appointment.setDoctor(doctor);
        appointment.setService(service);
        appointment.setAppointmentDate(bookDate);
        appointment.setPreferredSession(sessionPeriod);
        appointment.setExpectedTime(resolvedExpectedTime);
        appointment.setDurationMinutes(durationMinutes);
        appointment.setInitialSymptoms(form.getNote() != null && !form.getNote().isBlank() ? form.getNote().trim() : null);
        appointment.setSource(AppointmentSource.ONLINE);
        appointment.setStatus(AppointmentStatus.PENDING);

        appointment = appointmentRepository.saveAndFlush(appointment);

        // 11. Ghi nhật ký lịch sử cuộc hẹn
        try {
            AppointmentHistory history = new AppointmentHistory();
            history.setAppointment(appointment);
            history.setActorUserId(user != null ? user.getUserId() : null);
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

        resp.put("success", true);
        resp.put("appointmentId", appointment.getAppointmentId());
        resp.put("appointmentCode", appointmentCode);
        resp.put("branchId", branch.getBranchId());
        resp.put("branchName", branch.getBranchName());
        resp.put("message", "Đặt lịch thành công (" + sessionName + timeInfo + docInfo + ") tại " + branch.getBranchName() + ". Lịch hẹn đã sẵn sàng trên màn hình Quản lý lịch hẹn của Lễ tân.");

        return ResponseEntity.ok(resp);
    }

    private User createGuestPatientUser(String fullName, String cleanPhone) {
        User guest = new User();
        guest.setPhoneNumber(cleanPhone);
        guest.setEmail("guest_" + cleanPhone + "@cvdcms.local");
        guest.setUsername("kh_" + cleanPhone);
        guest.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        guest.setUserType(UserType.PATIENT);
        guest.setStatus("ACTIVE");
        guest.setIsActive(true);

        Role patientRole = roleRepository.findByRoleName("BENH_NHAN")
                .or(() -> roleRepository.findByRoleName("ROLE_BENH_NHAN"))
                .orElse(null);
        if (patientRole != null) {
            guest.setRoles(Set.of(patientRole));
        }

        return userRepository.save(guest);
    }

    private PatientProfile createPatientProfileForUser(User user, String fullName, String cleanPhone) {
        List<PatientProfile> existing = patientProfileRepository.findByPhoneNumber(cleanPhone);
        for (PatientProfile p : existing) {
            if (p.getUser() == null) {
                p.setUser(user);
                if (p.getFullName() == null || p.getFullName().isBlank()) {
                    p.setFullName(fullName != null && !fullName.isBlank() ? fullName : "Khách hàng");
                }
                return patientProfileRepository.save(p);
            }
        }
        PatientProfile p = new PatientProfile();
        p.setUser(user);
        p.setFullName(fullName != null && !fullName.isBlank() ? fullName : "Khách hàng");
        p.setPhoneNumber(cleanPhone);
        p.setGender("Khác");
        p.setAddress("Chưa cập nhật");
        p.setPatientCode("BN" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        p.setStatus("ACTIVE");
        return patientProfileRepository.save(p);
    }
}
