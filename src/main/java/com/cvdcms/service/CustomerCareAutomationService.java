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
 * Dịch vụ tự động phát sinh và đối soát công việc Chăm sóc khách hàng (CSKH) theo 2 cơ chế:
 * <ul>
 *   <li><b>Event-driven ({@code @TransactionalEventListener AFTER_COMMIT}):</b> Tự động sinh task CSKH ngay sau khi
 *       lượt khám hoàn tất, đơn thuốc mới được kê, lịch hẹn được tạo/cập nhật/NO_SHOW, đơn nghỉ phép bác sĩ được duyệt
 *       hoặc thanh toán trả góp được ghi nhận.</li>
 *   <li><b>Scheduler ({@code @Scheduled} mỗi 5 phút):</b> Quét bù các task bị bỏ sót và đối soát đóng/hủy các task
 *       bác sĩ nghỉ phép (FT01) hoặc nhắc thanh toán trả góp (FT02) đã không còn hiệu lực.</li>
 * </ul>
 * <p><b>Được gọi từ:</b> Spring Event Bus (nhận {@link com.cvdcms.event.CustomerCareDomainEvents} và
 * {@link com.cvdcms.event.ClinicQueueDomainEvent}) và Spring Task Scheduler.</p>
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
    private final LeaveRequestRepository leaveRequestRepository;
    private final TreatmentPlanInstallmentRepository installmentRepository;

    /**
     * Lắng nghe sự kiện hoàn thành lượt khám (sau commit) và tạo task hỏi thăm sau điều trị ({@code POST_TREATMENT_FOLLOWUP}) nếu chưa có.
     */
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

    /**
     * Lắng nghe sự kiện tạo mới đơn thuốc (sau commit) và tạo task nhắc dùng thuốc ({@code MEDICATION_REMINDER}) nếu chưa có.
     */
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

    /**
     * Lắng nghe sự kiện tạo/cập nhật lịch hẹn (sau commit) và tạo task nhắc lịch hẹn ({@code APPOINTMENT_REMINDER}) nếu chưa có.
     */
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

    /**
     * Lắng nghe sự kiện bệnh nhân lỡ hẹn (NO_SHOW) sau commit và tạo task gọi lại đặt lịch mới ({@code NO_SHOW_RECALL}) nếu chưa có.
     */
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

    /**
     * Lắng nghe sự kiện duyệt đơn nghỉ phép bác sĩ ({@code LEAVE_APPROVED}) để tự động sinh task liên hệ đổi/hủy lịch cho các bệnh nhân bị ảnh hưởng.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onClinicQueueDomainEvent(com.cvdcms.event.ClinicQueueDomainEvent event) {
        if (event == null || !"LEAVE_APPROVED".equals(event.status())) return;
        try {
            processDoctorLeaveForBranch(event.branchId(), event.doctorId());
        } catch (Exception ex) {
            log.error("[CSKH-AUTO] Lỗi xử lý sự kiện LEAVE_APPROVED (branchId={}, doctorId={}): {}",
                    event.branchId(), event.doctorId(), ex.getMessage(), ex);
        }
    }

    /**
     * Lắng nghe sự kiện ghi nhận thanh toán trả góp kế hoạch điều trị để tự động đồng bộ/đóng task nhắc thanh toán ({@code PAYMENT_REMINDER}).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onInstallmentPaymentRecorded(InstallmentPaymentRecordedCareEvent event) {
        if (event == null || event.planId() == null) return;
        try {
            reconcileInstallmentsForPlan(event.planId());
        } catch (Exception ex) {
            log.error("[CSKH-AUTO] Lỗi đồng bộ CSKH sau thanh toán trả góp cho planId={}: {}",
                    event.planId(), ex.getMessage(), ex);
        }
    }

    /**
     * Tác vụ nền định kỳ (chạy sau 60 giây khởi động và lặp mỗi 5 phút) rà soát bù toàn bộ các chi nhánh đang hoạt động:
     * tạo thiếu task sau khám, nhắc thuốc, nhắc lịch hẹn, NO_SHOW, bác sĩ nghỉ phép và nhắc thanh toán từng đợt.
     */
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
        try {
            totalCreated += reconcileAllDoctorLeaves();
        } catch (Exception ex) {
            log.warn("[CSKH-SCHEDULER] Lỗi rà soát bác sĩ nghỉ phép: {}", ex.getMessage());
        }
        try {
            totalCreated += reconcileDueInstallments();
        } catch (Exception ex) {
            log.warn("[CSKH-SCHEDULER] Lỗi rà soát nhắc thanh toán trả góp: {}", ex.getMessage());
        }
        if (totalCreated > 0) {
            log.info("[CSKH-SCHEDULER] Đã rà soát và tạo bù {} công việc CSKH mới.", totalCreated);
        }
    }

    /**
     * Tạo task hỏi thăm sau điều trị ({@code POST_TREATMENT_FOLLOWUP}) cho lượt khám đã {@code COMPLETED} nếu chưa tồn tại.
     * Hạn xử lý mặc định là ngày khám + 1 (hoặc hôm nay nếu đã qua).
     *
     * @param visitId ID lượt khám
     * @return {@code true} nếu tạo mới thành công, {@code false} nếu bỏ qua/đã tồn tại
     */
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

    /**
     * Tạo task nhắc dùng thuốc ({@code MEDICATION_REMINDER}) cho đơn thuốc hợp lệ nếu chưa tồn tại.
     * Hạn xử lý lấy theo {@code followupDate} của đơn thuốc (hoặc hôm nay nếu không có).
     */
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

    /**
     * Tạo task nhắc lịch hẹn ({@code APPOINTMENT_REMINDER}) cho lịch hẹn {@code PENDING/CONFIRMED} nếu chưa tồn tại.
     * Hạn xử lý mặc định là 1 ngày trước ngày hẹn ({@code appointmentDate - 1}).
     */
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

    /**
     * Tạo task gọi lại đặt lịch mới ({@code NO_SHOW_RECALL}) cho lịch hẹn có trạng thái {@code NO_SHOW} nếu chưa tồn tại.
     */
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

    // =========================================================================
    // FT01 — BÁC SĨ NGHỈ PHÉP (TẠO TASK & ĐỐI SOÁT THU HỒI)
    // =========================================================================

    /**
     * Xử lý toàn bộ đơn nghỉ phép đã duyệt còn hiệu lực của một bác sĩ tại chi nhánh và sinh task dời lịch cho các lịch hẹn bị trùng.
     */
    @Transactional
    public int processDoctorLeaveForBranch(Long branchId, Long doctorId) {
        if (leaveRequestRepository == null || branchId == null || doctorId == null) {
            return 0;
        }
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        List<LeaveRequest> leaves = leaveRequestRepository.findByStaffStaffIdOrderBySubmittedAtDesc(doctorId);
        int created = 0;
        for (LeaveRequest leave : leaves) {
            if (leave.getStatus() != LeaveStatus.APPROVED) continue;
            if (leave.getBranch() == null || !branchId.equals(leave.getBranch().getBranchId())) continue;
            if (leave.getEndDate() == null || leave.getEndDate().isBefore(today)) continue;
            created += createDoctorLeaveTasksIfMissing(leave);
        }
        return created;
    }

    /**
     * Đối soát toàn hệ thống các đơn nghỉ phép bác sĩ đã duyệt: sinh task dời lịch còn thiếu và tự động hủy các task không còn hiệu lực.
     */
    @Transactional
    public int reconcileAllDoctorLeaves() {
        if (leaveRequestRepository == null) return 0;
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        int created = 0;
        List<LeaveRequest> approvedLeaves = leaveRequestRepository.filterAdmin(
                null, LeaveStatus.APPROVED, null, null, null, null);
        if (approvedLeaves != null) {
            for (LeaveRequest leave : approvedLeaves) {
                if (leave.getEndDate() != null && !leave.getEndDate().isBefore(today)) {
                    created += createDoctorLeaveTasksIfMissing(leave);
                }
            }
        }
        reconcileStaleDoctorLeaveTasks();
        return created;
    }

    /**
     * Quét tất cả lịch hẹn của chi nhánh bị ảnh hưởng bởi đơn nghỉ phép {@code leave} và tạo task {@code DOCTOR_LEAVE_RESCHEDULE} nếu chưa có.
     */
    public int createDoctorLeaveTasksIfMissing(LeaveRequest leave) {
        if (leave == null || leave.getStatus() != LeaveStatus.APPROVED
                || leave.getStaff() == null || leave.getBranch() == null
                || leave.getStartDate() == null || leave.getEndDate() == null) {
            return 0;
        }
        Long branchId = leave.getBranch().getBranchId();
        List<Appointment> branchAppts = appointmentRepository.findByBranchWithDetails(branchId);
        if (branchAppts == null || branchAppts.isEmpty()) return 0;

        LocalDate today = LocalDate.now(CLINIC_ZONE);
        int created = 0;
        for (Appointment appt : branchAppts) {
            if (!isAppointmentAffectedByLeave(appt, leave)) continue;
            if (taskRepository.existsByLeaveRequestIdAndAppointment_AppointmentIdAndTaskType(
                    leave.getLeaveId(), appt.getAppointmentId(), CustomerCareTaskType.DOCTOR_LEAVE_RESCHEDULE)) {
                continue;
            }
            CustomerCareTask task = new CustomerCareTask();
            task.setTaskCode(generateUniqueTaskCode());
            task.setBranch(leave.getBranch());
            task.setPatientProfile(appt.getPatientProfile());
            task.setTaskType(CustomerCareTaskType.DOCTOR_LEAVE_RESCHEDULE);
            task.setAppointment(appt);
            task.setLeaveRequestId(leave.getLeaveId());
            task.setAssignedStaff(resolveDefaultBranchCareStaff(branchId));
            LocalDate due = appt.getAppointmentDate().isBefore(today) ? today : today;
            if (appt.getAppointmentDate().isAfter(today)) {
                due = today; // Cần liên hệ ngay khi BS được duyệt nghỉ
            }
            task.setDueDate(due);
            task.setStatus(CustomerCareTaskStatus.PENDING);
            String doctorName = leave.getStaff().getFullName() != null ? leave.getStaff().getFullName() : "Bác sĩ";
            String shiftInfo = leave.getShift() != null && leave.getShift().getShiftName() != null
                    ? " (ca " + leave.getShift().getShiftName() + ")" : " (cả ngày)";
            task.setNotes("Bác sĩ " + doctorName + " nghỉ phép từ " + leave.getStartDate()
                    + " đến " + leave.getEndDate() + shiftInfo
                    + " — Cần liên hệ đổi/hủy lịch khám mã " + appt.getAppointmentCode()
                    + " ngày " + appt.getAppointmentDate()
                    + (appt.getExpectedTime() != null ? " lúc " + appt.getExpectedTime() : "") + ".");

            try {
                CustomerCareTask saved = taskRepository.save(task);
                log.info("[CSKH-FT01] Tạo task DOCTOR_LEAVE_RESCHEDULE {} cho leaveId={}, appt={}",
                        saved.getTaskCode(), leave.getLeaveId(), appt.getAppointmentCode());
                notificationService.notifyTaskCreated(saved, null, "Hệ thống (BS nghỉ)", true);
                created++;
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                log.debug("[CSKH-FT01] Bỏ qua trùng task cho leaveId={}, apptId={}", leave.getLeaveId(), appt.getAppointmentId());
            }
        }
        return created;
    }

    /**
     * Kiểm tra một lịch hẹn có bị ảnh hưởng bởi đơn nghỉ phép của bác sĩ hay không
     * (khớp chi nhánh, bác sĩ, khoảng ngày và giao nhau với ca nghỉ / buổi khám).
     */
    public boolean isAppointmentAffectedByLeave(Appointment appt, LeaveRequest leave) {
        if (appt == null || leave == null || appt.getPatientProfile() == null
                || appt.getBranch() == null || appt.getDoctor() == null) {
            return false;
        }
        if (appt.getStatus() != AppointmentStatus.PENDING && appt.getStatus() != AppointmentStatus.CONFIRMED) {
            return false;
        }
        if (!appt.getBranch().getBranchId().equals(leave.getBranch().getBranchId())) {
            return false;
        }
        if (!appt.getDoctor().getStaffId().equals(leave.getStaff().getStaffId())) {
            return false;
        }
        LocalDate apptDate = appt.getAppointmentDate();
        if (apptDate == null || apptDate.isBefore(leave.getStartDate()) || apptDate.isAfter(leave.getEndDate())) {
            return false;
        }
        WorkShift shift = leave.getShift();
        if (shift == null || shift.getStartTime() == null || shift.getEndTime() == null) {
            return true;
        }
        if (appt.getExpectedTime() != null) {
            java.time.LocalTime apptStart = appt.getExpectedTime();
            java.time.LocalTime apptEnd = appt.getExpectedEndTime() != null
                    ? appt.getExpectedEndTime()
                    : apptStart.plusMinutes(appt.getDurationMinutes() != null ? appt.getDurationMinutes() : 30);
            return apptStart.isBefore(shift.getEndTime()) && apptEnd.isAfter(shift.getStartTime());
        }
        PreferredSession session = appt.getPreferredSession();
        if (session == null || session == PreferredSession.ANY) {
            return true;
        }
        java.time.LocalTime noon = java.time.LocalTime.of(12, 0);
        java.time.LocalTime evening = java.time.LocalTime.of(17, 30);
        return switch (session) {
            case MORNING -> shift.getStartTime().isBefore(noon);
            case AFTERNOON -> shift.getStartTime().isBefore(evening) && shift.getEndTime().isAfter(noon);
            case EVENING -> shift.getEndTime().isAfter(evening);
            case ANY -> true;
        };
    }

    /**
     * Tự động hủy ({@code CANCELLED}) các task {@code DOCTOR_LEAVE_RESCHEDULE} đang mở nhưng đơn nghỉ phép đã hủy
     * hoặc lịch hẹn đã được dời ra khỏi khung giờ nghỉ.
     */
    private void reconcileStaleDoctorLeaveTasks() {
        List<CustomerCareTaskStatus> openStatuses = List.of(
                CustomerCareTaskStatus.PENDING,
                CustomerCareTaskStatus.IN_PROGRESS,
                CustomerCareTaskStatus.ESCALATED_TO_DOCTOR
        );
        List<CustomerCareTask> openLeaveTasks = taskRepository.findByTaskTypeAndStatusIn(
                CustomerCareTaskType.DOCTOR_LEAVE_RESCHEDULE, openStatuses);
        for (CustomerCareTask task : openLeaveTasks) {
            if (task.getLeaveRequestId() == null || task.getAppointment() == null) continue;
            LeaveRequest leave = leaveRequestRepository.findByIdWithDetails(task.getLeaveRequestId()).orElse(null);
            Appointment appt = appointmentRepository.findById(task.getAppointment().getAppointmentId()).orElse(null);
            boolean stillAffected = leave != null
                    && leave.getStatus() == LeaveStatus.APPROVED
                    && appt != null
                    && isAppointmentAffectedByLeave(appt, leave);
            if (!stillAffected) {
                task.setStatus(CustomerCareTaskStatus.CANCELLED);
                String reason = (leave == null || leave.getStatus() != LeaveStatus.APPROVED)
                        ? "[Hệ thống] Đơn nghỉ phép của bác sĩ không còn hiệu lực — giữ nguyên lịch sử tương tác."
                        : "[Hệ thống] Lịch hẹn đã được đổi/hủy hoặc không còn nằm trong phạm vi ca nghỉ.";
                task.setNotes(task.getNotes() != null && !task.getNotes().isBlank()
                        ? task.getNotes() + "\n" + reason : reason);
                taskRepository.save(task);
            }
        }
    }

    // =========================================================================
    // FT02 — NHẮC THANH TOÁN THEO TỪNG ĐỢT (T-3, T-0, OVERDUE)
    // =========================================================================

    /**
     * Quét toàn bộ đợt thanh toán kế hoạch điều trị sắp đến hạn (trong vòng 3 ngày tới) hoặc quá hạn để tạo/nâng mốc task {@code PAYMENT_REMINDER},
     * đồng thời đóng các task đã được thanh toán đủ hoặc kế hoạch bị hủy.
     */
    @Transactional
    public int reconcileDueInstallments() {
        if (installmentRepository == null) return 0;
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        LocalDate cutoff = today.plusDays(3);
        int affected = 0;

        List<TreatmentPlanInstallment> dueInstallments = taskRepository.findDueInstallmentsForCareReminder(cutoff);
        if (dueInstallments != null) {
            for (TreatmentPlanInstallment inst : dueInstallments) {
                if (syncSingleInstallmentTask(inst, today)) {
                    affected++;
                }
            }
        }
        closePaidOrCancelledInstallmentTasks(today);
        return affected;
    }

    /**
     * Đồng bộ trạng thái các task nhắc thanh toán cho toàn bộ đợt thanh toán thuộc một kế hoạch điều trị cụ thể.
     */
    @Transactional
    public void reconcileInstallmentsForPlan(Long planId) {
        if (installmentRepository == null || planId == null) return;
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        List<TreatmentPlanInstallment> list = installmentRepository.findByPlan_PlanIdOrderByInstallmentOrderAsc(planId);
        if (list == null) return;
        for (TreatmentPlanInstallment inst : list) {
            syncSingleInstallmentTask(inst, today);
        }
    }

    /**
     * Đồng bộ một đợt thanh toán đơn lẻ với task {@code PAYMENT_REMINDER}:
     * đóng/hủy nếu đã trả đủ hoặc kế hoạch hủy; tạo mới hoặc nâng mốc nhắc ({@code T_MINUS_3 -> T_ZERO -> OVERDUE}) theo nguyên tắc idempotent.
     */
    private boolean syncSingleInstallmentTask(TreatmentPlanInstallment inst, LocalDate today) {
        if (inst == null || inst.getInstallmentId() == null || inst.getPlan() == null) return false;
        TreatmentPlan plan = inst.getPlan();
        if (plan.getPatientProfile() == null || plan.getBranch() == null) return false;

        java.math.BigDecimal expected = inst.getExpectedAmount() != null ? inst.getExpectedAmount() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal paid = inst.getPaidAmount() != null ? inst.getPaidAmount() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal missing = expected.subtract(paid);
        boolean isPaidOrCancelled = plan.getStatus() == TreatmentPlanStatus.CANCELLED
                || inst.getStatus() == TreatmentPlanInstallmentStatus.PAID
                || missing.compareTo(java.math.BigDecimal.ZERO) <= 0;

        Optional<CustomerCareTask> existingOpt = taskRepository.findFirstByInstallmentIdAndTaskType(
                inst.getInstallmentId(), CustomerCareTaskType.PAYMENT_REMINDER);

        if (isPaidOrCancelled) {
            if (existingOpt.isPresent()) {
                CustomerCareTask existing = existingOpt.get();
                if (existing.getStatus() != CustomerCareTaskStatus.COMPLETED
                        && existing.getStatus() != CustomerCareTaskStatus.CANCELLED) {
                    existing.setStatus(plan.getStatus() == TreatmentPlanStatus.CANCELLED
                            ? CustomerCareTaskStatus.CANCELLED
                            : CustomerCareTaskStatus.COMPLETED);
                    String sysMsg = plan.getStatus() == TreatmentPlanStatus.CANCELLED
                            ? "[Hệ thống] Kế hoạch điều trị đã hủy."
                            : "[Hệ thống] Đợt thanh toán đã được Kế toán thu đủ.";
                    existing.setNotes(existing.getNotes() != null && !existing.getNotes().isBlank()
                            ? existing.getNotes() + "\n" + sysMsg : sysMsg);
                    taskRepository.save(existing);
                    notificationService.notifyTaskUpdated(existing, null, "Hệ thống (Thanh toán)");
                    return true;
                }
            }
            return false;
        }

        if (inst.getExpectedDate() == null || inst.getExpectedDate().isAfter(today.plusDays(3))) {
            return false;
        }

        String stage = resolveInstallmentReminderStage(inst.getExpectedDate(), today);
        String formattedMissing = String.format("%,.0f", missing);
        String summaryMsg = "Nhắc thanh toán đợt " + inst.getInstallmentOrder()
                + " (" + inst.getPhaseName() + ") - KHDT " + plan.getPlanCode()
                + " | Hạn: " + inst.getExpectedDate()
                + " | Còn thiếu đợt này: " + formattedMissing + " đ.";

        if (existingOpt.isPresent()) {
            CustomerCareTask existing = existingOpt.get();
            if (existing.getStatus() == CustomerCareTaskStatus.COMPLETED
                    || existing.getStatus() == CustomerCareTaskStatus.CANCELLED) {
                return false;
            }
            if (stage.equals(existing.getReminderStage())) {
                return false; // Idempotent: không nhắc lặp cùng stage
            }
            existing.setReminderStage(stage);
            existing.setLastRemindedAt(java.time.LocalDateTime.now(CLINIC_ZONE));
            existing.setDueDate(inst.getExpectedDate());
            existing.setNotes(summaryMsg);
            CustomerCareTask updated = taskRepository.save(existing);
            notificationService.notifyInstallmentStageUpdated(updated, stage, summaryMsg);
            return true;
        }

        CustomerCareTask t = new CustomerCareTask();
        t.setTaskCode(generateUniqueTaskCode());
        t.setBranch(plan.getBranch());
        t.setPatientProfile(plan.getPatientProfile());
        t.setTaskType(CustomerCareTaskType.PAYMENT_REMINDER);
        t.setInstallmentId(inst.getInstallmentId());
        t.setAssignedStaff(resolveDefaultBranchCareStaff(plan.getBranch().getBranchId()));
        t.setDueDate(inst.getExpectedDate());
        t.setStatus(CustomerCareTaskStatus.PENDING);
        t.setReminderStage(stage);
        t.setLastRemindedAt(java.time.LocalDateTime.now(CLINIC_ZONE));
        t.setNotes(summaryMsg);

        try {
            CustomerCareTask saved = taskRepository.save(t);
            log.info("[CSKH-FT02] Tạo task PAYMENT_REMINDER {} ({}) cho installmentId={}",
                    saved.getTaskCode(), stage, inst.getInstallmentId());
            notificationService.notifyTaskCreated(saved, null, "Hệ thống (Nhắc thanh toán)", true);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            log.debug("[CSKH-FT02] Bỏ qua trùng task cho installmentId={}", inst.getInstallmentId());
            return false;
        }
    }

    /**
     * Rà soát tất cả task {@code PAYMENT_REMINDER} đang mở để đóng/hủy ngay nếu đợt thanh toán tương ứng đã hoàn tất.
     */
    private void closePaidOrCancelledInstallmentTasks(LocalDate today) {
        List<CustomerCareTaskStatus> openStatuses = List.of(
                CustomerCareTaskStatus.PENDING,
                CustomerCareTaskStatus.IN_PROGRESS,
                CustomerCareTaskStatus.ESCALATED_TO_DOCTOR
        );
        List<CustomerCareTask> openPaymentTasks = taskRepository.findByTaskTypeAndStatusIn(
                CustomerCareTaskType.PAYMENT_REMINDER, openStatuses);
        for (CustomerCareTask task : openPaymentTasks) {
            if (task.getInstallmentId() == null) continue;
            installmentRepository.findById(task.getInstallmentId())
                    .ifPresent(inst -> syncSingleInstallmentTask(inst, today));
        }
    }

    /**
     * Xác định mốc nhắc thanh toán dựa trên ngày dự kiến đóng tiền và ngày hiện tại:
     * {@code OVERDUE} (quá hạn), {@code T_ZERO} (đúng hạn hôm nay) hoặc {@code T_MINUS_3} (trước hạn <= 3 ngày).
     */
    public static String resolveInstallmentReminderStage(LocalDate expectedDate, LocalDate today) {
        if (today.isAfter(expectedDate)) {
            return "OVERDUE";
        }
        if (today.isEqual(expectedDate)) {
            return "T_ZERO";
        }
        return "T_MINUS_3";
    }

    /**
     * Chọn nhân viên CSKH/Lễ tân mặc định của chi nhánh để gán cho task tạo tự động (ưu tiên nhân sự không phải bác sĩ).
     */
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

    /**
     * Kiểm tra một hồ sơ nhân viên có phải là Bác sĩ hay không dựa theo chức danh, phòng ban và mã nhân viên.
     */
    private boolean isDoctorStaff(StaffProfile s) {
        String pos = (s.getPositionTitle() != null ? s.getPositionTitle() : "").toLowerCase(Locale.ROOT);
        String dept = (s.getDepartment() != null ? s.getDepartment() : "").toLowerCase(Locale.ROOT);
        String code = (s.getStaffCode() != null ? s.getStaffCode() : "").toLowerCase(Locale.ROOT);
        return pos.contains("bác sĩ") || pos.contains("doctor") || pos.contains("bs")
                || dept.contains("lâm sàng") || dept.contains("bác sĩ") || dept.contains("điều trị")
                || code.startsWith("bs");
    }

    /**
     * Sinh mã công việc CSKH duy nhất dạng {@code TASK<seq>} (tối đa 20 ký tự), kiểm tra không trùng trong DB.
     */
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
