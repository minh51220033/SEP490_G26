package com.cvdcms.service.impl;

import com.cvdcms.DTO.CareTaskUpdateDTO;
import com.cvdcms.DTO.ReExaminationRequestDTO;
import com.cvdcms.DTO.ReExaminationResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.ReExaminationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReExaminationServiceImpl implements ReExaminationService {

    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AppointmentRepository appointmentRepository;
    private final CustomerCareTaskRepository customerCareTaskRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final ServiceRepository serviceRepository;
    private final VisitRepository visitRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LeaveRequestRepository leaveRequestRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    @Override
    @Transactional
    public ReExaminationResponseDTO createReExamination(ReExaminationRequestDTO dto) {
        log.info("Creating re-examination appointment: {}", dto);

        if (dto == null || dto.getPatientProfileId() == null) {
            throw new IllegalArgumentException("Vui lòng chỉ định hồ sơ bệnh nhân!");
        }
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        LocalTime nowTime = LocalTime.now(CLINIC_ZONE);
        LocalDate targetDate = dto.getAppointmentDate() != null ? dto.getAppointmentDate() : today.plusDays(7);
        if (targetDate.isBefore(today)) {
            throw new IllegalArgumentException("Ngày hẹn tái khám không được nằm trong quá khứ!");
        }

        PatientProfile patient = patientProfileRepository.findById(dto.getPatientProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hồ sơ bệnh nhân."));

        Branch branch;
        if (dto.getBranchId() != null) {
            branch = branchRepository.findById(dto.getBranchId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh thực hiện tái khám!"));
        } else if (patient.getPreferredBranch() != null) {
            branch = patient.getPreferredBranch();
        } else {
            throw new IllegalArgumentException("Vui lòng chọn chi nhánh thực hiện tái khám!");
        }

        StaffProfile doctor = null;
        if (dto.getDoctorId() != null) {
            doctor = staffProfileRepository.findById(dto.getDoctorId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bác sĩ phụ trách tái khám!"));
            if (doctor.getStatus() != StaffStatus.ACTIVE) {
                throw new IllegalArgumentException("Bác sĩ phụ trách tái khám hiện không ở trạng thái hoạt động!");
            }
        }

        com.cvdcms.entity.Service service = null;
        if (dto.getServiceId() != null) {
            service = serviceRepository.findById(dto.getServiceId()).orElse(null);
        }

        Visit currentVisit = null;
        if (dto.getVisitId() != null) {
            currentVisit = visitRepository.findById(dto.getVisitId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám để đặt lịch hẹn tái khám!"));
            if (currentVisit.getPatientProfile() != null
                    && !currentVisit.getPatientProfile().getProfileId().equals(patient.getProfileId())) {
                throw new IllegalArgumentException("Ca khám không thuộc về hồ sơ bệnh nhân được chọn!");
            }
        } else {
            currentVisit = visitRepository.findFirstByPatientProfile_ProfileIdOrderByCheckInAtDesc(patient.getProfileId())
                    .orElse(null);
        }
        if (currentVisit != null && currentVisit.getStatus() == VisitStatus.CANCELLED) {
            throw new IllegalStateException("Không thể đặt lịch hẹn tái khám cho ca khám đã bị hủy.");
        }

        PreferredSession resolvedSession = dto.getPreferredSession() != null ? dto.getPreferredSession() : PreferredSession.MORNING;
        LocalTime resolvedTime = parseExpectedTimeStrictOrDefault(dto.getExpectedTime(), defaultTimeForSession(resolvedSession));
        if (targetDate.isEqual(today) && resolvedTime != null && resolvedTime.isBefore(nowTime)) {
            throw new IllegalArgumentException("Giờ hẹn tái khám hôm nay không được nằm trong quá khứ!");
        }
        if (doctor != null) {
            validateDoctorAvailabilityForReExam(doctor, branch, targetDate, resolvedTime, null);
        }

        Appointment appt = new Appointment();
        String dateStr = targetDate.format(DateTimeFormatter.ofPattern("yyMMdd"));
        long randomSuffix = 1000 + (long)(Math.random() * 9000);
        appt.setAppointmentCode("TK" + dateStr + "-" + randomSuffix);

        appt.setPatientProfile(patient);
        appt.setBranch(branch);
        appt.setDoctor(doctor);
        appt.setService(service);
        appt.setAppointmentDate(targetDate);
        appt.setPreferredSession(resolvedSession);
        appt.setExpectedTime(resolvedTime);

        appt.setInitialSymptoms(dto.getFollowupReason() != null ? dto.getFollowupReason() : "Tái khám kiểm tra sau điều trị");
        appt.setSource(AppointmentSource.RECEPTIONIST);
        appt.setStatus(AppointmentStatus.CONFIRMED);

        Appointment savedAppt = appointmentRepository.save(appt);

        // Tạo nhiệm vụ chăm sóc khách hàng nếu có yêu cầu
        CustomerCareTask careTask = null;
        if (dto.isCreateCareTask()) {
            careTask = new CustomerCareTask();
            careTask.setTaskCode("CSKH" + System.currentTimeMillis() % 10000000);
            careTask.setPatientProfile(patient);
            careTask.setBranch(branch);
            careTask.setTaskType(CustomerCareTaskType.APPOINTMENT_REMINDER);
            careTask.setAppointment(savedAppt);
            careTask.setVisit(currentVisit);

            LocalDate careDueDate = savedAppt.getAppointmentDate().minusDays(1);
            if (careDueDate.isBefore(today)) {
                careDueDate = today;
            }
            careTask.setDueDate(careDueDate);
            careTask.setStatus(CustomerCareTaskStatus.PENDING);
            careTask.setNotes("Nhắc lịch hẹn tái khám ngày " + savedAppt.getAppointmentDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    + ": " + savedAppt.getInitialSymptoms()
                    + (dto.getCareTaskNotes() != null && !dto.getCareTaskNotes().isBlank() ? " | Ghi chú thêm: " + dto.getCareTaskNotes() : ""));

            careTask = customerCareTaskRepository.save(careTask);
        }

        return mapToDTO(savedAppt, careTask);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReExaminationResponseDTO> getReExaminationsByPatient(Long patientProfileId) {
        if (patientProfileId == null) return Collections.emptyList();

        List<Appointment> list = appointmentRepository.findByPatientProfile_ProfileIdOrderByAppointmentDateDesc(patientProfileId);
        return list.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReExaminationResponseDTO> getRecallsList(String dateFilter, Long doctorId, String statusStr, String keyword) {
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        LocalDate startDate = null;
        LocalDate endDate = null;

        if ("TODAY".equalsIgnoreCase(dateFilter)) {
            startDate = today;
            endDate = today;
        } else if ("THIS_WEEK".equalsIgnoreCase(dateFilter)) {
            startDate = today.minusDays(today.getDayOfWeek().getValue() - 1);
            endDate = startDate.plusDays(6);
        } else if ("THIS_MONTH".equalsIgnoreCase(dateFilter)) {
            startDate = today.withDayOfMonth(1);
            endDate = today.withDayOfMonth(today.lengthOfMonth());
        } else if ("NEXT_7_DAYS".equalsIgnoreCase(dateFilter)) {
            startDate = today;
            endDate = today.plusDays(7);
        } else if ("OVERDUE".equalsIgnoreCase(dateFilter)) {
            endDate = today.minusDays(1);
        }

        AppointmentStatus status = null;
        if (statusStr != null && !statusStr.isBlank() && !"ALL".equalsIgnoreCase(statusStr)) {
            try {
                status = AppointmentStatus.valueOf(statusStr.toUpperCase());
            } catch (Exception ignored) {}
        }

        String search = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;

        List<Appointment> list = appointmentRepository.filterAppointments(startDate, endDate, doctorId, status, search);

        // Xử lý riêng cho bộ lọc OVERDUE: chỉ lấy những ca chưa hoàn thành / chưa hủy
        if ("OVERDUE".equalsIgnoreCase(dateFilter)) {
            list = list.stream()
                    .filter(a -> a.getStatus() == AppointmentStatus.PENDING || a.getStatus() == AppointmentStatus.CONFIRMED)
                    .collect(Collectors.toList());
        }

        return list.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getRecallsStatistics() {
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        Set<Long> allowedBranchIds = resolveCurrentUserBranchScope();

        long total;
        long todayCount;
        long weekCount;
        long overdueCount;

        if (allowedBranchIds != null) {
            Map<Long, Appointment> branchAppointments = new LinkedHashMap<>();
            for (Long branchId : allowedBranchIds) {
                if (branchId == null) continue;
                List<Appointment> items = appointmentRepository.findByBranchWithDetails(branchId);
                if (items != null) {
                    for (Appointment a : items) {
                        if (a != null && a.getAppointmentId() != null) {
                            branchAppointments.put(a.getAppointmentId(), a);
                        }
                    }
                }
            }
            LocalDate weekEnd = today.plusDays(7);
            total = branchAppointments.size();
            todayCount = branchAppointments.values().stream()
                    .filter(a -> today.equals(a.getAppointmentDate()))
                    .count();
            weekCount = branchAppointments.values().stream()
                    .filter(a -> a.getAppointmentDate() != null
                            && !a.getAppointmentDate().isBefore(today)
                            && !a.getAppointmentDate().isAfter(weekEnd))
                    .count();
            overdueCount = branchAppointments.values().stream()
                    .filter(a -> a.getAppointmentDate() != null
                            && a.getAppointmentDate().isBefore(today)
                            && (a.getStatus() == AppointmentStatus.PENDING || a.getStatus() == AppointmentStatus.CONFIRMED))
                    .count();
        } else {
            total = appointmentRepository.count();
            todayCount = appointmentRepository.countByAppointmentDate(today);
            weekCount = appointmentRepository.countByAppointmentDateBetween(today, today.plusDays(7));
            overdueCount = appointmentRepository.countByAppointmentDateBeforeAndStatusIn(
                    today,
                    List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED)
            );
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("todayCount", todayCount);
        stats.put("upcomingCount", weekCount);
        stats.put("overdueCount", overdueCount);
        return stats;
    }

    private Set<Long> resolveCurrentUserBranchScope() {
        if (doctorVisitAccess == null) {
            return null;
        }
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra)) {
            return null;
        }
        jakarta.servlet.http.HttpSession session = sra.getRequest().getSession(false);
        if (session == null) {
            return null;
        }
        Object principal = session.getAttribute("loggedInUser");
        if (!(principal instanceof User user) || user.getUserId() == null) {
            return null;
        }
        return doctorVisitAccess.resolveAccessibleBranchIds(user);
    }

    @Override
    @Transactional
    public ReExaminationResponseDTO updateAppointmentStatus(Long appointmentId, String statusStr, String cancelReason) {
        Appointment appt = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch hẹn tái khám."));

        assertAppointmentMutable(appt, "Không thể cập nhật trạng thái lịch hẹn đã hoàn thành, đã check-in hoặc đã hủy.");

        if (statusStr == null || statusStr.isBlank()) {
            throw new IllegalArgumentException("Trạng thái lịch hẹn không hợp lệ.");
        }
        AppointmentStatus newStatus;
        try {
            newStatus = AppointmentStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Trạng thái lịch hẹn không hợp lệ: " + statusStr);
        }

        appt.setStatus(newStatus);
        if (newStatus == AppointmentStatus.CANCELLED) {
            appt.setCancelReason(cancelReason != null && !cancelReason.isBlank() ? cancelReason : "Bệnh nhân báo bận");
            appt.setCancelledAt(LocalDateTime.now(CLINIC_ZONE));
        }
        Appointment saved = appointmentRepository.save(appt);
        if (newStatus == AppointmentStatus.CANCELLED) {
            cancelLinkedCareTasks(appointmentId, saved.getCancelReason());
        }
        return mapToDTO(saved);
    }

    @Override
    @Transactional
    public ReExaminationResponseDTO updateAppointmentDate(Long appointmentId, LocalDate newDate, String newTime, PreferredSession session) {
        Appointment appt = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch hẹn tái khám."));

        assertAppointmentMutable(appt, "Không thể dời lịch hẹn đã hoàn thành, đã check-in hoặc đã hủy.");

        LocalDate today = LocalDate.now(CLINIC_ZONE);
        LocalTime nowTime = LocalTime.now(CLINIC_ZONE);
        LocalDate targetDate = newDate != null ? newDate : appt.getAppointmentDate();
        if (targetDate == null) {
            throw new IllegalArgumentException("Vui lòng chọn ngày hẹn tái khám!");
        }
        if (targetDate.isBefore(today)) {
            throw new IllegalArgumentException("Ngày hẹn tái khám không được nằm trong quá khứ!");
        }

        PreferredSession targetSession = session != null ? session : (appt.getPreferredSession() != null ? appt.getPreferredSession() : PreferredSession.MORNING);
        LocalTime fallbackTime = appt.getExpectedTime() != null ? appt.getExpectedTime() : defaultTimeForSession(targetSession);
        LocalTime targetTime = (newTime != null && !newTime.isBlank())
                ? parseExpectedTimeStrictOrDefault(newTime, fallbackTime)
                : fallbackTime;

        if (targetDate.isEqual(today) && targetTime != null && targetTime.isBefore(nowTime)) {
            throw new IllegalArgumentException("Giờ hẹn tái khám hôm nay không được nằm trong quá khứ!");
        }
        if (appt.getDoctor() != null) {
            validateDoctorAvailabilityForReExam(appt.getDoctor(), appt.getBranch(), targetDate, targetTime, appt.getAppointmentId());
        }

        appt.setAppointmentDate(targetDate);
        appt.setPreferredSession(targetSession);
        if (targetTime != null) {
            appt.setExpectedTime(targetTime);
        }
        appt.setRescheduleCount(appt.getRescheduleCount() + 1);
        Appointment saved = appointmentRepository.save(appt);

        // Cập nhật ngày cho CustomerCareTask nếu có
        List<CustomerCareTask> linkedTasks = findLinkedCareTasks(appointmentId);
        for (CustomerCareTask task : linkedTasks) {
            LocalDate newDueDate = targetDate.minusDays(1);
            if (newDueDate.isBefore(today)) newDueDate = today;
            task.setDueDate(newDueDate);
            if (task.getStatus() != CustomerCareTaskStatus.COMPLETED && task.getStatus() != CustomerCareTaskStatus.CANCELLED) {
                task.setStatus(CustomerCareTaskStatus.PENDING);
            }
            String changeNote = "[Đổi ngày sang: " + targetDate
                    + (targetTime != null ? " " + targetTime.format(DateTimeFormatter.ofPattern("HH:mm")) : "") + "]";
            String existingNotes = task.getNotes();
            task.setNotes((existingNotes != null && !existingNotes.isBlank())
                    ? existingNotes + " | " + changeNote
                    : changeNote);
            customerCareTaskRepository.save(task);
        }

        return mapToDTO(saved);
    }

    @Override
    @Transactional
    public void updateCareTaskStatus(CareTaskUpdateDTO dto) {
        if (dto.getTaskId() == null) return;
        CustomerCareTask task = customerCareTaskRepository.findById(dto.getTaskId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhiệm vụ CSKH."));

        if (dto.getStatus() != null) {
            task.setStatus(dto.getStatus());
        }
        if (dto.getNotes() != null && !dto.getNotes().isBlank()) {
            String prefix = dto.getContactResult() != null ? "[" + dto.getContactResult() + "] " : "";
            task.setNotes((task.getNotes() != null ? task.getNotes() + "\n" : "") + prefix + dto.getNotes());
        }
        customerCareTaskRepository.save(task);
    }

    @Override
    @Transactional
    public void cancelAppointment(Long appointmentId, String reason) {
        updateAppointmentStatus(appointmentId, "CANCELLED", reason);
    }

    private void assertAppointmentMutable(Appointment appt, String errorMessage) {
        if (appt.getStatus() == AppointmentStatus.COMPLETED
                || appt.getStatus() == AppointmentStatus.CHECKED_IN
                || appt.getStatus() == AppointmentStatus.CANCELLED) {
            throw new IllegalStateException(errorMessage);
        }
    }

    private List<CustomerCareTask> findLinkedCareTasks(Long appointmentId) {
        List<CustomerCareTask> list = customerCareTaskRepository.findByAppointment_AppointmentId(appointmentId);
        if (list != null && !list.isEmpty()) {
            return list;
        }
        return customerCareTaskRepository.findFirstByAppointment_AppointmentId(appointmentId)
                .map(List::of)
                .orElse(Collections.emptyList());
    }

    private void cancelLinkedCareTasks(Long appointmentId, String cancelReason) {
        List<CustomerCareTask> linkedTasks = findLinkedCareTasks(appointmentId);
        String cancelNote = "[Hủy lịch hẹn" + (cancelReason != null && !cancelReason.isBlank() ? ": " + cancelReason : "") + "]";
        for (CustomerCareTask task : linkedTasks) {
            if (task.getStatus() != CustomerCareTaskStatus.COMPLETED && task.getStatus() != CustomerCareTaskStatus.CANCELLED) {
                task.setStatus(CustomerCareTaskStatus.CANCELLED);
            }
            String existingNotes = task.getNotes();
            task.setNotes((existingNotes != null && !existingNotes.isBlank())
                    ? existingNotes + " | " + cancelNote
                    : cancelNote);
            customerCareTaskRepository.save(task);
        }
    }

    private LocalTime defaultTimeForSession(PreferredSession session) {
        if (session == PreferredSession.AFTERNOON) {
            return LocalTime.of(14, 0);
        }
        if (session == PreferredSession.EVENING) {
            return LocalTime.of(18, 0);
        }
        return LocalTime.of(9, 0);
    }

    private LocalTime parseExpectedTimeStrictOrDefault(String expectedTimeStr, LocalTime fallback) {
        if (expectedTimeStr == null || expectedTimeStr.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(expectedTimeStr.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Giờ hẹn tái khám không hợp lệ (định dạng HH:mm).");
        }
    }

    private void validateDoctorAvailabilityForReExam(
            StaffProfile doctor,
            Branch branch,
            LocalDate date,
            LocalTime expectedTime,
            Long excludeAppointmentId) {
        if (doctor == null || date == null) {
            return;
        }
        String doctorName = doctor.getFullName() != null ? doctor.getFullName() : "Bác sĩ";
        String dateDisplay = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        if (leaveRequestRepository != null) {
            List<LeaveRequest> leaves = leaveRequestRepository.findByStaffStaffIdOrderBySubmittedAtDesc(doctor.getStaffId());
            if (leaves != null) {
                for (LeaveRequest leave : leaves) {
                    if (leave.getStatus() != LeaveStatus.APPROVED) continue;
                    if (leave.getStartDate() == null || leave.getEndDate() == null) continue;
                    if (date.isBefore(leave.getStartDate()) || date.isAfter(leave.getEndDate())) continue;
                    WorkShift shift = leave.getShift();
                    if (shift == null) {
                        throw new IllegalArgumentException(doctorName + " đã đăng ký nghỉ phép ngày " + dateDisplay + ". Vui lòng chọn ngày hoặc bác sĩ khác!");
                    }
                    if (expectedTime != null && shift.getStartTime() != null && shift.getEndTime() != null
                            && !expectedTime.isBefore(shift.getStartTime()) && !expectedTime.isAfter(shift.getEndTime())) {
                        throw new IllegalArgumentException(doctorName + " nghỉ phép ca " + shift.getShiftName() + " ngày " + dateDisplay + ". Vui lòng chọn khung giờ khác!");
                    }
                }
            }
        }

        List<Appointment> sameDayAppointments = appointmentRepository.findByDoctor_StaffIdAndAppointmentDate(doctor.getStaffId(), date);
        if (sameDayAppointments != null && expectedTime != null) {
            for (Appointment existing : sameDayAppointments) {
                if (excludeAppointmentId != null && excludeAppointmentId.equals(existing.getAppointmentId())) continue;
                if (existing.getStatus() == AppointmentStatus.CANCELLED || existing.getStatus() == AppointmentStatus.NO_SHOW) continue;
                if (existing.getExpectedTime() != null) {
                    long diffMinutes = Math.abs(ChronoUnit.MINUTES.between(expectedTime, existing.getExpectedTime()));
                    if (diffMinutes < 30) {
                        throw new IllegalArgumentException(
                                doctorName + " đã có lịch hẹn lúc " + existing.getExpectedTime() + " ngày " + dateDisplay + ". Vui lòng chọn khung giờ cách ít nhất 30 phút!");
                    }
                }
            }
        }
    }

    private ReExaminationResponseDTO mapToDTO(Appointment appt) {
        Optional<CustomerCareTask> careTaskOpt = customerCareTaskRepository.findFirstByAppointment_AppointmentId(appt.getAppointmentId());
        return mapToDTO(appt, careTaskOpt.orElse(null));
    }

    private ReExaminationResponseDTO mapToDTO(Appointment appt, CustomerCareTask careTask) {
        PatientProfile p = appt.getPatientProfile();
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        long daysUntil = ChronoUnit.DAYS.between(today, appt.getAppointmentDate());

        String badgeText;
        String badgeClass;
        if (appt.getStatus() == AppointmentStatus.COMPLETED) {
            badgeText = "Đã hoàn thành";
            badgeClass = "bg-success text-white";
        } else if (appt.getStatus() == AppointmentStatus.CANCELLED) {
            badgeText = "Đã hủy";
            badgeClass = "bg-secondary text-white";
        } else if (daysUntil < 0) {
            badgeText = "Quá hạn " + Math.abs(daysUntil) + " ngày";
            badgeClass = "bg-danger text-white";
        } else if (daysUntil == 0) {
            badgeText = "Hôm nay";
            badgeClass = "bg-warning text-dark fw-bold";
        } else if (daysUntil == 1) {
            badgeText = "Ngày mai";
            badgeClass = "bg-primary text-white";
        } else {
            badgeText = "Còn " + daysUntil + " ngày";
            badgeClass = "bg-info text-white";
        }

        Integer age = null;
        if (p != null && p.getDob() != null) {
            age = Period.between(p.getDob(), today).getYears();
        }

        return ReExaminationResponseDTO.builder()
                .appointmentId(appt.getAppointmentId())
                .appointmentCode(appt.getAppointmentCode())
                .patientId(p != null ? p.getProfileId() : null)
                .patientToken(p != null ? new com.cvdcms.security.UrlIdCodec().encodePatient(p.getProfileId()) : null)
                .patientCode(p != null ? p.getPatientCode() : "---")
                .patientName(p != null ? p.getFullName() : "Chưa xác định")
                .phoneNumber(p != null ? p.getPhoneNumber() : "---")
                .gender(p != null ? p.getGender() : "---")
                .dob(p != null ? p.getDob() : null)
                .age(age)
                .branchId(appt.getBranch() != null ? appt.getBranch().getBranchId() : null)
                .branchName(appt.getBranch() != null ? appt.getBranch().getBranchName() : "---")
                .doctorId(appt.getDoctor() != null ? appt.getDoctor().getStaffId() : null)
                .doctorName(appt.getDoctor() != null ? appt.getDoctor().getFullName() : "BS. Chưa chỉ định")
                .serviceId(appt.getService() != null ? appt.getService().getServiceId() : null)
                .serviceName(appt.getService() != null ? appt.getService().getServiceName() : "Tái khám theo dõi chuyên khoa")
                .appointmentDate(appt.getAppointmentDate())
                .preferredSession(appt.getPreferredSession())
                .expectedTime(appt.getExpectedTime())
                .initialSymptoms(appt.getInitialSymptoms())
                .status(appt.getStatus())
                .createdAt(appt.getCreatedAt())
                .hasCareTask(careTask != null)
                .careTaskId(careTask != null ? careTask.getTaskId() : null)
                .careTaskStatus(careTask != null ? careTask.getStatus() : null)
                .careTaskNotes(careTask != null ? careTask.getNotes() : null)
                .daysUntil(daysUntil)
                .timelineBadgeText(badgeText)
                .timelineBadgeClass(badgeClass)
                .build();
    }
}
