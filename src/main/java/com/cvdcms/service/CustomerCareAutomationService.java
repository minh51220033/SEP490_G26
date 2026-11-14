package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.event.CustomerCareDomainEvents.*;
import com.cvdcms.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dịch vụ tự động phát sinh công việc CSKH theo sự kiện nghiệp vụ (Event-driven)
 * kết hợp tác vụ nền @Scheduled rà soát bù các công việc bị bỏ sót.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerCareAutomationService {

    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicLong AUTO_CODE_SEQ = new AtomicLong(System.currentTimeMillis() % 100000000L);

    private final CustomerCareTaskRepository taskRepository;
    private final VisitRepository visitRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AppointmentRepository appointmentRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffRepository;
    private final CustomerCareNotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onVisitCompleted(VisitCompletedCareEvent event) {
        if (event == null || event.visitId() == null) return;
        try {
            createPostTreatmentTaskIfMissing(event.visitId());
        } catch (Exception ex) {
            log.error("[CSKH-AUTO] Lỗi tạo công việc sau điều trị cho visitId={}: {}", event.visitId(), ex.getMessage(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPrescriptionCreated(PrescriptionCreatedCareEvent event) {
        if (event == null || event.prescriptionId() == null) return;
        try {
            createMedicationReminderIfMissing(event.prescriptionId());
        } catch (Exception ex) {
            log.error("[CSKH-AUTO] Lỗi tạo công việc nhắc dùng thuốc cho prescriptionId={}: {}", event.prescriptionId(), ex.getMessage(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAppointmentUpserted(AppointmentUpsertedCareEvent event) {
        if (event == null || event.appointmentId() == null) return;
        try {
            createAppointmentReminderIfMissing(event.appointmentId());
        } catch (Exception ex) {
            log.error("[CSKH-AUTO] Lỗi tạo công việc nhắc lịch hẹn cho appointmentId={}: {}", event.appointmentId(), ex.getMessage(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAppointmentNoShow(AppointmentNoShowCareEvent event) {
        if (event == null || event.appointmentId() == null) return;
        try {
            createNoShowRecallIfMissing(event.appointmentId());
        } catch (Exception ex) {
            log.error("[CSKH-AUTO] Lỗi tạo công việc gọi lại NO_SHOW cho appointmentId={}: {}", event.appointmentId(), ex.getMessage(), ex);
        }
    }

    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    @Transactional
    public void catchUpMissedTasks() {
        List<Branch> activeBranches = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
        if (activeBranches == null || activeBranches.isEmpty()) {
            return;
        }
        int totalCreated = 0;
        for (Branch branch : activeBranches) {
            Long branchId = branch.getBranchId();
            if (branchId == null) continue;
            try {
                for (Visit v : taskRepository.findCompletedVisitsByBranchForCare(branchId)) {
                    if (createPostTreatmentTaskIfMissing(v.getVisitId())) totalCreated++;
                }
                for (Prescription pr : taskRepository.findValidPrescriptionsByBranchForCare(branchId)) {
                    if (createMedicationReminderIfMissing(pr.getPrescriptionId())) totalCreated++;
                }
                for (Appointment a : appointmentRepository.findByBranchWithDetails(branchId)) {
                    if (a.getStatus() == AppointmentStatus.CONFIRMED || a.getStatus() == AppointmentStatus.PENDING) {
                        if (createAppointmentReminderIfMissing(a.getAppointmentId())) totalCreated++;
                    } else if (a.getStatus() == AppointmentStatus.NO_SHOW) {
                        if (createNoShowRecallIfMissing(a.getAppointmentId())) totalCreated++;
                    }
                }
            } catch (Exception ex) {
                log.warn("[CSKH-SCHEDULER] Lỗi rà soát bù tại cơ sở #{}: {}", branchId, ex.getMessage());
            }
        }
        if (totalCreated > 0) {
            log.info("[CSKH-SCHEDULER] Đã rà soát và tạo bù {} công việc CSKH mới.", totalCreated);
        }
    }

    public boolean createPostTreatmentTaskIfMissing(Long visitId) {
        Visit v = visitRepository.findWithDetailsById(visitId).orElse(null);
        if (v == null || v.getStatus() != VisitStatus.COMPLETED
                || v.getPatientProfile() == null || v.getBranch() == null) {
            return false;
        }
        if (taskRepository.existsByVisit_VisitIdAndTaskType(visitId, CustomerCareTaskType.POST_TREATMENT_FOLLOWUP)) {
            return false;
        }
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        LocalDate visitDate = v.getCheckInAt() != null ? v.getCheckInAt().toLocalDate() : today;
        LocalDate dueDate = visitDate.plusDays(1).isBefore(today) ? today : visitDate.plusDays(1);

        CustomerCareTask t = new CustomerCareTask();
        t.setTaskCode(generateUniqueTaskCode());
        t.setBranch(v.getBranch());
        t.setPatientProfile(v.getPatientProfile());
        t.setTaskType(CustomerCareTaskType.POST_TREATMENT_FOLLOWUP);
        t.setVisit(v);
        t.setAssignedStaff(resolveDefaultBranchCareStaff(v.getBranch().getBranchId()));
        t.setDueDate(dueDate);
        t.setStatus(CustomerCareTaskStatus.PENDING);

        StringBuilder noteBuilder = new StringBuilder("Hỏi thăm sức khỏe bệnh nhân sau ca khám ")
                .append(v.getVisitCode() != null ? v.getVisitCode() : ("#" + v.getVisitId()));
        if (v.getInitialSymptoms() != null && !v.getInitialSymptoms().isBlank()) {
            noteBuilder.append(" (").append(v.getInitialSymptoms().trim()).append(")");
        }
        if (v.getNotes() != null && !v.getNotes().isBlank()) {
            noteBuilder.append(" — Ghi chú điều trị: ").append(v.getNotes().trim());
        }
        t.setNotes(noteBuilder.toString());

        CustomerCareTask saved = taskRepository.save(t);
        log.info("[CSKH-AUTO] Tạo công việc POST_TREATMENT_FOLLOWUP {} từ ca khám {}", saved.getTaskCode(), v.getVisitCode());
        notificationService.notifyTaskCreated(saved, null, "Hệ thống tự động", true);
        return true;
    }

    public boolean createMedicationReminderIfMissing(Long prescriptionId) {
        Prescription pr = prescriptionRepository.findByIdWithDetails(prescriptionId).orElse(null);
        if (pr == null || pr.getPatientProfile() == null || pr.getBranch() == null) {
            return false;
        }
        if (pr.getVisit() != null && pr.getVisit().getStatus() == VisitStatus.CANCELLED) {
            return false;
        }
        if (taskRepository.existsByPrescription_PrescriptionIdAndTaskType(prescriptionId, CustomerCareTaskType.MEDICATION_REMINDER)) {
            return false;
        }
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        CustomerCareTask t = new CustomerCareTask();
        t.setTaskCode(generateUniqueTaskCode());
        t.setBranch(pr.getBranch());
        t.setPatientProfile(pr.getPatientProfile());
        t.setTaskType(CustomerCareTaskType.MEDICATION_REMINDER);
        t.setPrescription(pr);
        t.setVisit(pr.getVisit());
        t.setAssignedStaff(resolveDefaultBranchCareStaff(pr.getBranch().getBranchId()));
        t.setDueDate(pr.getFollowupDate() != null ? pr.getFollowupDate() : today);
        t.setStatus(CustomerCareTaskStatus.PENDING);

        String note = "Nhắc bệnh nhân dùng thuốc theo đơn " + pr.getPrescriptionCode()
                + " — Chẩn đoán: " + (pr.getDiagnosisText() != null ? pr.getDiagnosisText() : "Theo chỉ định");
        if (pr.getAdviceNotes() != null && !pr.getAdviceNotes().isBlank()) {
            note += " — Lời dặn: " + pr.getAdviceNotes().trim();
        }
        t.setNotes(note);

        CustomerCareTask saved = taskRepository.save(t);
        log.info("[CSKH-AUTO] Tạo công việc MEDICATION_REMINDER {} từ đơn thuốc {}", saved.getTaskCode(), pr.getPrescriptionCode());
        notificationService.notifyTaskCreated(saved, null, "Hệ thống tự động", true);
        return true;
    }

    public boolean createAppointmentReminderIfMissing(Long appointmentId) {
        Appointment a = appointmentRepository.findById(appointmentId).orElse(null);
        if (a == null || a.getPatientProfile() == null || a.getBranch() == null || a.getStatus() == null) {
            return false;
        }
        if (a.getStatus() != AppointmentStatus.PENDING && a.getStatus() != AppointmentStatus.CONFIRMED) {
            return false;
        }
        if (taskRepository.existsByAppointment_AppointmentIdAndTaskType(appointmentId, CustomerCareTaskType.APPOINTMENT_REMINDER)) {
            return false;
        }
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        LocalDate due = a.getAppointmentDate() != null ? a.getAppointmentDate().minusDays(1) : today;

        CustomerCareTask t = new CustomerCareTask();
        t.setTaskCode(generateUniqueTaskCode());
        t.setBranch(a.getBranch());
        t.setPatientProfile(a.getPatientProfile());
        t.setTaskType(CustomerCareTaskType.APPOINTMENT_REMINDER);
        t.setAppointment(a);
        t.setAssignedStaff(resolveDefaultBranchCareStaff(a.getBranch().getBranchId()));
        t.setDueDate(due);
        t.setStatus(CustomerCareTaskStatus.PENDING);
        t.setNotes("Nhắc lịch hẹn khám mã " + a.getAppointmentCode()
                + " ngày " + a.getAppointmentDate()
                + (a.getService() != null ? " (" + a.getService().getServiceName() + ")" : ""));

        CustomerCareTask saved = taskRepository.save(t);
        log.info("[CSKH-AUTO] Tạo công việc APPOINTMENT_REMINDER {} từ lịch hẹn {}", saved.getTaskCode(), a.getAppointmentCode());
        notificationService.notifyTaskCreated(saved, null, "Hệ thống tự động", true);
        return true;
    }

    public boolean createNoShowRecallIfMissing(Long appointmentId) {
        Appointment a = appointmentRepository.findById(appointmentId).orElse(null);
        if (a == null || a.getPatientProfile() == null || a.getBranch() == null
                || a.getStatus() != AppointmentStatus.NO_SHOW) {
            return false;
        }
        if (taskRepository.existsByAppointment_AppointmentIdAndTaskType(appointmentId, CustomerCareTaskType.NO_SHOW_RECALL)) {
            return false;
        }
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        CustomerCareTask t = new CustomerCareTask();
        t.setTaskCode(generateUniqueTaskCode());
        t.setBranch(a.getBranch());
        t.setPatientProfile(a.getPatientProfile());
        t.setTaskType(CustomerCareTaskType.NO_SHOW_RECALL);
        t.setAppointment(a);
        t.setAssignedStaff(resolveDefaultBranchCareStaff(a.getBranch().getBranchId()));
        t.setDueDate(today);
        t.setStatus(CustomerCareTaskStatus.PENDING);
        t.setNotes("Liên hệ đặt lại lịch cho bệnh nhân lỡ hẹn mã " + a.getAppointmentCode());

        CustomerCareTask saved = taskRepository.save(t);
        log.info("[CSKH-AUTO] Tạo công việc NO_SHOW_RECALL {} từ lịch hẹn {}", saved.getTaskCode(), a.getAppointmentCode());
        notificationService.notifyTaskCreated(saved, null, "Hệ thống tự động", true);
        return true;
    }

    private StaffProfile resolveDefaultBranchCareStaff(Long branchId) {
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
        return staffRepository.findByStatus(StaffStatus.ACTIVE).stream().findFirst().orElse(null);
    }

    private boolean isDoctorStaff(StaffProfile s) {
        String pos = (s.getPositionTitle() != null ? s.getPositionTitle() : "").toLowerCase(Locale.ROOT);
        String dept = (s.getDepartment() != null ? s.getDepartment() : "").toLowerCase(Locale.ROOT);
        String code = (s.getStaffCode() != null ? s.getStaffCode() : "").toLowerCase(Locale.ROOT);
        return pos.contains("bác sĩ") || pos.contains("doctor") || pos.contains("bs")
                || dept.contains("lâm sàng") || dept.contains("bác sĩ") || dept.contains("điều trị")
                || code.startsWith("bs");
    }

    private String generateUniqueTaskCode() {
        String code;
        do {
            long seq = AUTO_CODE_SEQ.incrementAndGet();
            code = "TASK" + seq;
            if (code.length() > 20) {
                code = code.substring(0, 20);
            }
        } while (taskRepository.existsByTaskCode(code));
        return code;
    }
}
