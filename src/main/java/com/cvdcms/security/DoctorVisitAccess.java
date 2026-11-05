package com.cvdcms.security;

import com.cvdcms.entity.User;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.repository.ExaminationDiagnosisRepository;
import com.cvdcms.repository.PrescriptionRepository;
import com.cvdcms.repository.PerformedServiceRepository;
import com.cvdcms.repository.ParaclinicalOrderItemRepository;
import com.cvdcms.repository.DoctorCoverGrantRepository;
import com.cvdcms.repository.ClinicalPhotoRepository;
import com.cvdcms.repository.WorkflowExecutionRepository;
import com.cvdcms.repository.SystemAuditLogRepository;
import com.cvdcms.entity.SystemAuditLog;
import java.time.LocalDateTime;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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
}
