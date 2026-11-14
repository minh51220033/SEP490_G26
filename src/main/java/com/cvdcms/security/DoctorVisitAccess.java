package com.cvdcms.security;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.CustomerCareTask;
import com.cvdcms.entity.LaboOrder;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.SystemAuditLog;
import com.cvdcms.entity.TreatmentPlan;
import com.cvdcms.entity.TreatmentPlanInstallment;
import com.cvdcms.entity.TreatmentPlanItem;
import com.cvdcms.entity.TreatmentPlanStage;
import com.cvdcms.entity.User;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.UserType;
import com.cvdcms.repository.AppointmentRepository;
import com.cvdcms.repository.ClinicalPhotoRepository;
import com.cvdcms.repository.CustomerCareTaskRepository;
import com.cvdcms.repository.DoctorCoverGrantRepository;
import com.cvdcms.repository.ExaminationDiagnosisRepository;
import com.cvdcms.repository.LaboOrderRepository;
import com.cvdcms.repository.ParaclinicalOrderItemRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.PerformedServiceRepository;
import com.cvdcms.repository.PrescriptionRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.SystemAuditLogRepository;
import com.cvdcms.repository.TreatmentPlanInstallmentRepository;
import com.cvdcms.repository.TreatmentPlanItemRepository;
import com.cvdcms.repository.TreatmentPlanRepository;
import com.cvdcms.repository.TreatmentPlanStageRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.repository.WorkflowExecutionRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DoctorVisitAccess {
    private final VisitRepository visits;
    private final StaffProfileRepository staff;
    private final UserRepository users;
    private final ExaminationDiagnosisRepository diagnoses;
    private final PrescriptionRepository prescriptions;
    private final PerformedServiceRepository performedServices;
    private final ParaclinicalOrderItemRepository paraclinicalItems;
    private final DoctorCoverGrantRepository coverGrants;
    private final ClinicalPhotoRepository photos;
    private final WorkflowExecutionRepository workflowExecutions;
    private final SystemAuditLogRepository audits;

    @Autowired(required = false)
    private TreatmentPlanRepository treatmentPlans;

    @Autowired(required = false)
    private TreatmentPlanStageRepository treatmentPlanStages;

    @Autowired(required = false)
    private TreatmentPlanItemRepository treatmentPlanItems;

    @Autowired(required = false)
    private LaboOrderRepository laboOrders;

    @Autowired(required = false)
    private AppointmentRepository appointments;

    @Autowired(required = false)
    private CustomerCareTaskRepository careTasks;

    @Autowired(required = false)
    private PatientProfileRepository patients;

    @Autowired(required = false)
    private StaffBranchAssignmentRepository branchAssignments;

    @Autowired(required = false)
    private TreatmentPlanInstallmentRepository treatmentPlanInstallments;

    public void requireWorkflowExecution(HttpSession session, Long executionId) {
        var execution = workflowExecutions.findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        require(session, execution.getVisit().getVisitId());
    }

    public void requirePatient(HttpSession session, Long profileId) {
        var latest = visits.findFirstByPatientProfile_ProfileIdOrderByCheckInAtDesc(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt khám của bệnh nhân."));
        require(session, latest.getVisitId());
    }

    public void requirePhoto(HttpSession session, Long photoId) {
        var photo = photos.findById(photoId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (photo.getVisit() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ảnh chưa gắn ca khám.");
        require(session, photo.getVisit().getVisitId());
    }

    public void requirePrescription(HttpSession session, Long id) {
        var item = prescriptions.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        require(session, item.getVisit().getVisitId());
    }

    public void requirePerformedService(HttpSession session, Long id) {
        var item = performedServices.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        require(session, item.getVisit().getVisitId());
    }

    public void requireParaclinicalItem(HttpSession session, Long id) {
        var item = paraclinicalItems.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        require(session, item.getOrder().getVisit().getVisitId());
    }

    public void requireDiagnosis(HttpSession session, Long diagnosisId) {
        var diagnosis = diagnoses.findById(diagnosisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chẩn đoán."));
        require(session, diagnosis.getVisit().getVisitId());
    }

    public void require(HttpSession session, Long visitId) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (!(principal instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        var visit = visits.findById(visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca khám."));
        if (users.hasActiveAdminRole(user.getUserId())) return;
        var doctor = staff.findByUserUserId(user.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không có hồ sơ bác sĩ."));
        boolean primary = visit.getPrimaryDoctor() != null &&
                doctor.getStaffId().equals(visit.getPrimaryDoctor().getStaffId());
        if (!primary && !coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                        visitId, doctor.getStaffId(), LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ca khám không được phân công cho bác sĩ hiện tại.");
        }
        if (!primary) {
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes web &&
                    !"GET".equalsIgnoreCase(web.getRequest().getMethod())) {
                SystemAuditLog log = new SystemAuditLog();
                log.setUser(user); log.setBranch(visit.getBranch());
                log.setEntityTable("visits"); log.setEntityId(visitId);
                log.setAction("COVERING_DOCTOR_WRITE");
                try { log.setNewValuesJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        java.util.Map.of("method", web.getRequest().getMethod(), "uri", web.getRequest().getRequestURI()))); }
                catch (Exception ex) { throw new IllegalStateException("Không thể ghi nhật ký bác sĩ khám thay.", ex); }
                audits.save(log);
            }
        }
    }

    public User requireAuthenticatedUser(HttpSession session) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (!(principal instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        return user;
    }

    public Long resolveUserIdOrThrow(HttpSession session) {
        return requireAuthenticatedUser(session).getUserId();
    }

    public boolean isAdmin(User user) {
        return user != null && user.getUserId() != null && users.hasActiveAdminRole(user.getUserId());
    }

    public Set<Long> resolveAccessibleBranchIds(User user) {
        if (user == null || user.getUserId() == null) {
            return Set.of();
        }
        if (isAdmin(user)) {
            return null;
        }
        Set<Long> branchIds = new LinkedHashSet<>();
        if (branchAssignments != null) {
            List<Long> assigned = branchAssignments.findActiveBranchIdsByUserId(user.getUserId(), AssignmentStatus.ACTIVE);
            if (assigned != null) {
                for (Long id : assigned) {
                    if (id != null) {
                        branchIds.add(id);
                    }
                }
            }
        }
        users.findById(user.getUserId()).ifPresentOrElse(fresh -> {
            if (fresh.getBranch() != null && fresh.getBranch().getBranchId() != null) {
                branchIds.add(fresh.getBranch().getBranchId());
            }
        }, () -> {
            try {
                if (user.getBranch() != null && user.getBranch().getBranchId() != null) {
                    branchIds.add(user.getBranch().getBranchId());
                }
            } catch (Exception ignored) {
                // Bo qua proxy detached khi test
            }
        });
        return Set.copyOf(branchIds);
    }

    public boolean isBranchAllowed(User user, Long branchId) {
        Set<Long> allowed = resolveAccessibleBranchIds(user);
        if (allowed == null) {
            return true;
        }
        return branchId != null && allowed.contains(branchId);
    }

    public void requireBranch(HttpSession session, Long branchId) {
        User user = requireAuthenticatedUser(session);
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập dữ liệu của chi nhánh khác.");
        }
    }

    public void requireVisitRead(HttpSession session, Long visitId) {
        User user = requireAuthenticatedUser(session);
        Visit visit = visits.findById(visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca khám."));
        if (isAdmin(user)) {
            return;
        }
        StaffProfile doctor = staff.findByUserUserId(user.getUserId()).orElse(null);
        boolean primary = doctor != null && visit.getPrimaryDoctor() != null
                && doctor.getStaffId().equals(visit.getPrimaryDoctor().getStaffId());
        if (primary || (doctor != null && coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                visitId, doctor.getStaffId(), LocalDateTime.now()))) {
            return;
        }
        Long visitBranchId = visit.getBranch() != null ? visit.getBranch().getBranchId() : null;
        if (!isBranchAllowed(user, visitBranchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền xem thông tin ca khám này.");
        }
    }

    public void requirePatientRead(HttpSession session, Long profileId) {
        User user = requireAuthenticatedUser(session);
        if (patients == null) {
            return;
        }
        PatientProfile patient = patients.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));
        if (isAdmin(user)) {
            return;
        }
        Set<Long> allowed = resolveAccessibleBranchIds(user);
        Long preferredBranchId = patient.getPreferredBranch() != null ? patient.getPreferredBranch().getBranchId() : null;
        if (preferredBranchId != null && allowed != null && allowed.contains(preferredBranchId)) {
            return;
        }
        boolean hasVisitInAllowedBranch = allowed != null && !allowed.isEmpty()
                && visits.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(profileId).stream()
                        .anyMatch(v -> v.getBranch() != null && allowed.contains(v.getBranch().getBranchId()));
        if (hasVisitInAllowedBranch) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập hồ sơ bệnh nhân của chi nhánh khác.");
    }

    public void requirePatientWrite(HttpSession session, Long profileId) {
        requirePatientRead(session, profileId);
    }

    public void requireTreatmentPlanRead(HttpSession session, Long planId) {
        User user = requireAuthenticatedUser(session);
        if (treatmentPlans == null) {
            return;
        }
        TreatmentPlan plan = treatmentPlans.findByIdWithDetails(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch điều trị."));
        if (isAdmin(user)) {
            return;
        }
        Long branchId = resolveTreatmentPlanBranchId(plan);
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền xem kế hoạch điều trị của chi nhánh khác.");
        }
    }

    public void requireTreatmentPlanWrite(HttpSession session, Long planId) {
        User user = requireAuthenticatedUser(session);
        if (treatmentPlans == null) {
            return;
        }
        TreatmentPlan plan = treatmentPlans.findByIdWithDetails(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch điều trị."));
        if (isAdmin(user)) {
            return;
        }
        Long branchId = resolveTreatmentPlanBranchId(plan);
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền chỉnh sửa kế hoạch điều trị của chi nhánh khác.");
        }
        StaffProfile callerDoctor = staff.findByUserUserId(user.getUserId()).orElse(null);
        if (callerDoctor != null && plan.getDoctor() != null
                && !callerDoctor.getStaffId().equals(plan.getDoctor().getStaffId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ bác sĩ phụ trách phác đồ mới có quyền cập nhật.");
        }
    }

    public void requireTreatmentStage(HttpSession session, Long stageId) {
        if (treatmentPlanStages == null) {
            requireAuthenticatedUser(session);
            return;
        }
        TreatmentPlanStage stage = treatmentPlanStages.findById(stageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giai đoạn điều trị."));
        if (stage.getPlan() != null) {
            requireTreatmentPlanWrite(session, stage.getPlan().getPlanId());
        } else {
            requireAuthenticatedUser(session);
        }
    }

    public void requireTreatmentPlanItem(HttpSession session, Long itemId) {
        if (treatmentPlanItems == null) {
            requireAuthenticatedUser(session);
            return;
        }
        TreatmentPlanItem item = treatmentPlanItems.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bước thủ thuật."));
        Long planId = item.getPlan() != null
                ? item.getPlan().getPlanId()
                : (item.getStage() != null && item.getStage().getPlan() != null ? item.getStage().getPlan().getPlanId() : null);
        if (planId != null) {
            requireTreatmentPlanWrite(session, planId);
        } else {
            requireAuthenticatedUser(session);
        }
    }

    public void requireLaboOrder(HttpSession session, Long laboId, boolean writeOperation) {
        User user = requireAuthenticatedUser(session);
        if (laboOrders == null) {
            return;
        }
        LaboOrder order = laboOrders.findById(laboId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu đặt xưởng."));
        if (isAdmin(user)) {
            return;
        }
        Long branchId = order.getBranch() != null
                ? order.getBranch().getBranchId()
                : (order.getVisit() != null && order.getVisit().getBranch() != null ? order.getVisit().getBranch().getBranchId() : null);
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập phiếu labo của chi nhánh khác.");
        }
        if (writeOperation && order.getVisit() != null) {
            StaffProfile callerDoctor = staff.findByUserUserId(user.getUserId()).orElse(null);
            if (callerDoctor != null && order.getVisit().getPrimaryDoctor() != null
                    && !callerDoctor.getStaffId().equals(order.getVisit().getPrimaryDoctor().getStaffId())
                    && !coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                            order.getVisit().getVisitId(), callerDoctor.getStaffId(), LocalDateTime.now())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền sửa phiếu labo thuộc ca khám của bác sĩ khác.");
            }
        }
    }

    public void requireReExamination(HttpSession session, Long appointmentId) {
        User user = requireAuthenticatedUser(session);
        if (appointments == null) {
            return;
        }
        Appointment appointment = appointments.findById(appointmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch tái khám."));
        if (isAdmin(user)) {
            return;
        }
        Long branchId = appointment.getBranch() != null ? appointment.getBranch().getBranchId() : null;
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập lịch hẹn của chi nhánh khác.");
        }
    }

    public void requireCareTask(HttpSession session, Long taskId) {
        User user = requireAuthenticatedUser(session);
        if (careTasks == null) {
            return;
        }
        CustomerCareTask task = careTasks.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tác vụ chăm sóc."));
        if (isAdmin(user)) {
            return;
        }
        Long branchId = task.getBranch() != null
                ? task.getBranch().getBranchId()
                : (task.getPatientProfile() != null && task.getPatientProfile().getPreferredBranch() != null
                        ? task.getPatientProfile().getPreferredBranch().getBranchId() : null);
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập tác vụ CSKH của chi nhánh khác.");
        }
    }

    public void requireWorkflowExecutionRead(HttpSession session, Long executionId) {
        if (workflowExecutions == null) {
            requireAuthenticatedUser(session);
            return;
        }
        var execution = workflowExecutions.findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tiến trình quy trình."));
        if (execution.getVisit() != null) {
            requireVisitRead(session, execution.getVisit().getVisitId());
        } else {
            requireAuthenticatedUser(session);
        }
    }

    public void requireTreatmentInstallment(HttpSession session, Long installmentId) {
        if (treatmentPlanInstallments == null) {
            requireAuthenticatedUser(session);
            return;
        }
        TreatmentPlanInstallment installment = treatmentPlanInstallments.findById(installmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đợt thanh toán."));
        if (installment.getPlan() != null) {
            requireTreatmentPlanWrite(session, installment.getPlan().getPlanId());
        } else {
            requireAuthenticatedUser(session);
        }
    }

    private Long resolveTreatmentPlanBranchId(TreatmentPlan plan) {
        if (plan.getBranch() != null) {
            return plan.getBranch().getBranchId();
        }
        if (plan.getPatientProfile() != null && plan.getPatientProfile().getPreferredBranch() != null) {
            return plan.getPatientProfile().getPreferredBranch().getBranchId();
        }
        return null;
    }
}
