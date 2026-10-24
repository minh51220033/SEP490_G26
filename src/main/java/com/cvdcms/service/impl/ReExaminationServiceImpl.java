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

    @Override
    @Transactional
    public ReExaminationResponseDTO createReExamination(ReExaminationRequestDTO dto) {
        log.info("Creating re-examination appointment: {}", dto);

        PatientProfile patient = patientProfileRepository.findById(dto.getPatientProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân ID: " + dto.getPatientProfileId()));

        Branch branch = null;
        if (dto.getBranchId() != null) {
            branch = branchRepository.findById(dto.getBranchId()).orElse(null);
        }
        if (branch == null && patient.getPreferredBranch() != null) {
            branch = patient.getPreferredBranch();
        }
        if (branch == null) {
            branch = branchRepository.findAll().stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Hệ thống chưa có chi nhánh nào!"));
        }

        StaffProfile doctor = null;
        if (dto.getDoctorId() != null) {
            doctor = staffProfileRepository.findById(dto.getDoctorId()).orElse(null);
        }

        com.cvdcms.entity.Service service = null;
        if (dto.getServiceId() != null) {
            service = serviceRepository.findById(dto.getServiceId()).orElse(null);
        }

        Visit currentVisit = null;
        if (dto.getVisitId() != null) {
            currentVisit = visitRepository.findById(dto.getVisitId()).orElse(null);
        }

        Appointment appt = new Appointment();
        String dateStr = dto.getAppointmentDate() != null
                ? dto.getAppointmentDate().format(DateTimeFormatter.ofPattern("yyMMdd"))
                : LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        long randomSuffix = 1000 + (long)(Math.random() * 9000);
        appt.setAppointmentCode("TK" + dateStr + "-" + randomSuffix);

        appt.setPatientProfile(patient);
        appt.setBranch(branch);
        appt.setDoctor(doctor);
        appt.setService(service);
        appt.setAppointmentDate(dto.getAppointmentDate() != null ? dto.getAppointmentDate() : LocalDate.now().plusDays(7));
        appt.setPreferredSession(dto.getPreferredSession() != null ? dto.getPreferredSession() : PreferredSession.MORNING);

        if (dto.getExpectedTime() != null && !dto.getExpectedTime().isBlank()) {
            try {
                appt.setExpectedTime(LocalTime.parse(dto.getExpectedTime()));
            } catch (Exception e) {
                appt.setExpectedTime(LocalTime.of(9, 0));
            }
        } else {
            appt.setExpectedTime(LocalTime.of(9, 0));
        }

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
    @Transactional
    public List<ReExaminationResponseDTO> getRecallsList(String dateFilter, Long doctorId, String statusStr, String keyword) {
        seedSampleRecallsIfEmpty();

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
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch hẹn ID: " + appointmentId));

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
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch hẹn ID: " + appointmentId));

        if (newDate != null) appt.setAppointmentDate(newDate);
        if (session != null) appt.setPreferredSession(session);
        if (newTime != null && !newTime.isBlank()) {
            try {
                appt.setExpectedTime(LocalTime.parse(newTime));
            } catch (Exception ignored) {}
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
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhiệm vụ CSKH ID: " + dto.getTaskId()));

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

    private synchronized void seedSampleRecallsIfEmpty() {
        if (appointmentRepository.count() > 0) return;

        log.info("Seeding realistic sample re-examination recalls...");
        List<PatientProfile> patients = patientProfileRepository.findAll();
        if (patients.isEmpty()) return;

        Branch branch = branchRepository.findAll().stream().findFirst().orElse(null);
        StaffProfile doctor = staffProfileRepository.findAll().stream()
                .filter(s -> s.getStaffCode() != null && s.getStaffCode().startsWith("BS"))
                .findFirst().orElse(null);

        LocalDate today = LocalDate.now();

        // 1. Tái khám hôm nay: Cắt chỉ sau nhổ răng khôn
        createSampleAppt(patients.get(0), branch, doctor, today, LocalTime.of(9, 30),
                "Tái khám cắt chỉ & kiểm tra huyệt ổ răng 38 sau nhổ 7 ngày", AppointmentStatus.CONFIRMED, "TK" + today.format(DateTimeFormatter.ofPattern("yyMMdd")) + "-1001");

        // 2. Tái khám ngày mai: Siết dây cung & thay chun niềng răng
        if (patients.size() > 1) {
            createSampleAppt(patients.get(1), branch, doctor, today.plusDays(1), LocalTime.of(14, 0),
                    "Tái khám chỉnh nha: Thay thun siết răng & kiểm tra mắc cài cung dưới", AppointmentStatus.CONFIRMED, "TK" + today.format(DateTimeFormatter.ofPattern("yyMMdd")) + "-1002");
        }

        // 3. Tái khám sau 3 ngày: Thử sườn mão răng sứ Cercon
        if (patients.size() > 2) {
            createSampleAppt(patients.get(2), branch, doctor, today.plusDays(3), LocalTime.of(10, 0),
                    "Hẹn thử sườn & so màu mão sứ thẩm mỹ R21", AppointmentStatus.CONFIRMED, "TK" + today.format(DateTimeFormatter.ofPattern("yyMMdd")) + "-1003");
        }

        // 4. Tái khám quá hạn 2 ngày: Kiểm tra tủy răng số 46
        if (patients.size() > 3) {
            createSampleAppt(patients.get(3), branch, doctor, today.minusDays(2), LocalTime.of(15, 30),
                    "Tái khám kiểm tra tủy & trám bít ống tủy lần 2 (Quá hạn chưa đến)", AppointmentStatus.PENDING, "TK" + today.format(DateTimeFormatter.ofPattern("yyMMdd")) + "-1004");
        }
    }

    private void createSampleAppt(PatientProfile patient, Branch branch, StaffProfile doctor, LocalDate date, LocalTime time, String reason, AppointmentStatus status, String code) {
        Appointment a = new Appointment();
        a.setAppointmentCode(code);
        a.setPatientProfile(patient);
        a.setBranch(branch);
        a.setDoctor(doctor);
        a.setAppointmentDate(date);
        a.setPreferredSession(time.getHour() < 12 ? PreferredSession.MORNING : PreferredSession.AFTERNOON);
        a.setExpectedTime(time);
        a.setInitialSymptoms(reason);
        a.setStatus(status);
        a.setSource(AppointmentSource.RECEPTIONIST);
        Appointment saved = appointmentRepository.save(a);

        CustomerCareTask task = new CustomerCareTask();
        task.setTaskCode("CSKH" + System.currentTimeMillis() % 10000000);
        task.setPatientProfile(patient);
        task.setBranch(branch);
        task.setTaskType(CustomerCareTaskType.APPOINTMENT_REMINDER);
        task.setAppointment(saved);
        task.setDueDate(date.minusDays(1).isBefore(LocalDate.now()) ? LocalDate.now() : date.minusDays(1));
        task.setStatus(status == AppointmentStatus.CONFIRMED ? CustomerCareTaskStatus.COMPLETED : CustomerCareTaskStatus.PENDING);
        task.setNotes("Nhắc lịch tái khám: " + reason);
        customerCareTaskRepository.save(task);
    }
}
