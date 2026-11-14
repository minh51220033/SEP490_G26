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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReExaminationServiceImpl implements ReExaminationService {

    private final AppointmentRepository appointmentRepository;
    private final CustomerCareTaskRepository customerCareTaskRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final ServiceRepository serviceRepository;
    private final VisitRepository visitRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LeaveRequestRepository leaveRequestRepository;

    @Override
    @Transactional
    public ReExaminationResponseDTO createReExamination(ReExaminationRequestDTO dto) {
        log.info("Creating re-examination appointment: {}", dto);

        if (dto.getPatientProfileId() == null) {
            throw new IllegalArgumentException("Vui lòng chỉ định hồ sơ bệnh nhân!");
        }
        if (dto.getAppointmentDate() != null && dto.getAppointmentDate().isBefore(LocalDate.now())) {
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
        }

        com.cvdcms.entity.Service service = null;
        if (dto.getServiceId() != null) {
            service = serviceRepository.findById(dto.getServiceId()).orElse(null);
        }

        Visit currentVisit = null;
        if (dto.getVisitId() != null) {
            currentVisit = visitRepository.findById(dto.getVisitId()).orElse(null);
        }

        LocalDate targetDate = dto.getAppointmentDate() != null ? dto.getAppointmentDate() : LocalDate.now().plusDays(7);
        LocalTime resolvedTime = parseExpectedTimeSafe(dto.getExpectedTime(), LocalTime.of(9, 0));
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
        appt.setPreferredSession(dto.getPreferredSession() != null ? dto.getPreferredSession() : PreferredSession.MORNING);
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
            if (careDueDate.isBefore(LocalDate.now())) {
                careDueDate = LocalDate.now();
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
        LocalDate today = LocalDate.now();
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
        LocalDate today = LocalDate.now();
        LocalDate startOfWeek = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate endOfWeek = startOfWeek.plusDays(6);

        long total = appointmentRepository.count();
        long todayCount = appointmentRepository.countByAppointmentDate(today);
        long weekCount = appointmentRepository.countByAppointmentDateBetween(today, today.plusDays(7));
        long overdueCount = appointmentRepository.countByAppointmentDateBeforeAndStatusIn(
                today,
                List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED)
        );

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("todayCount", todayCount);
        stats.put("upcomingCount", weekCount);
        stats.put("overdueCount", overdueCount);
        return stats;
    }

    @Override
    @Transactional
    public ReExaminationResponseDTO updateAppointmentStatus(Long appointmentId, String statusStr, String cancelReason) {
        Appointment appt = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch hẹn tái khám."));

        AppointmentStatus newStatus = AppointmentStatus.valueOf(statusStr.toUpperCase());
        appt.setStatus(newStatus);
        if (newStatus == AppointmentStatus.CANCELLED) {
            appt.setCancelReason(cancelReason != null ? cancelReason : "Bệnh nhân báo bận");
            appt.setCancelledAt(LocalDateTime.now());
        }
        Appointment saved = appointmentRepository.save(appt);
        return mapToDTO(saved);
    }

    @Override
    @Transactional
    public ReExaminationResponseDTO updateAppointmentDate(Long appointmentId, LocalDate newDate, String newTime, PreferredSession session) {
        Appointment appt = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch hẹn tái khám."));

        if (newDate != null && newDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Ngày hẹn tái khám không được nằm trong quá khứ!");
        }

        LocalDate targetDate = newDate != null ? newDate : appt.getAppointmentDate();
        LocalTime targetTime = (newTime != null && !newTime.isBlank())
                ? parseExpectedTimeSafe(newTime, appt.getExpectedTime())
                : appt.getExpectedTime();
        if (appt.getDoctor() != null && targetDate != null) {
            validateDoctorAvailabilityForReExam(appt.getDoctor(), appt.getBranch(), targetDate, targetTime, appt.getAppointmentId());
        }

        if (newDate != null) appt.setAppointmentDate(newDate);
        if (session != null) appt.setPreferredSession(session);
        if (targetTime != null) {
            appt.setExpectedTime(targetTime);
        }
        appt.setRescheduleCount(appt.getRescheduleCount() + 1);
        Appointment saved = appointmentRepository.save(appt);

        // Cập nhật ngày cho CustomerCareTask nếu có
        Optional<CustomerCareTask> taskOpt = customerCareTaskRepository.findFirstByAppointment_AppointmentId(appointmentId);
        if (taskOpt.isPresent()) {
            CustomerCareTask task = taskOpt.get();
            LocalDate newDueDate = newDate != null ? newDate.minusDays(1) : LocalDate.now();
            if (newDueDate.isBefore(LocalDate.now())) newDueDate = LocalDate.now();
            task.setDueDate(newDueDate);
            task.setStatus(CustomerCareTaskStatus.PENDING);
            task.setNotes(task.getNotes() + " | [Đổi ngày sang: " + (newDate != null ? newDate : "") + "]");
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

    private LocalTime parseExpectedTimeSafe(String expectedTimeStr, LocalTime fallback) {
        if (expectedTimeStr == null || expectedTimeStr.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(expectedTimeStr.trim());
        } catch (Exception e) {
            return fallback;
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
        LocalDate today = LocalDate.now();
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
