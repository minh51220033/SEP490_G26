package com.cvdcms.security;

import com.cvdcms.entity.DoctorCoverGrant;
import com.cvdcms.entity.SystemAuditLog;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.DoctorCoverGrantRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.SystemAuditLogRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.repository.VisitRepository;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/doctor/visits/{visitId}/cover-grants")
@RequiredArgsConstructor
public class DoctorCoverController {
    private final DoctorCoverGrantRepository grants;
    private final VisitRepository visits;
    private final StaffProfileRepository staff;
    private final StaffBranchAssignmentRepository assignments;
    private final UserRepository users;
    private final SystemAuditLogRepository audits;

    public record GrantRequest(Long coveringDoctorId, String reason, Integer hours) {}

    @PostMapping
    @Transactional
    public Map<String, Long> grant(@PathVariable Long visitId, @RequestBody GrantRequest request, HttpSession session) {
        User actor = admin(session);
        if (request == null || request.coveringDoctorId() == null || request.reason() == null
                || request.reason().trim().length() < 10 || request.reason().trim().length() > 255
                || request.hours() == null || request.hours() < 1 || request.hours() > 8)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần bác sĩ khám thay, lý do 10–255 ký tự và thời hạn 1–8 giờ.");
        var visit = visits.findById(visitId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (visit.getStatus() != VisitStatus.IN_EXAM && visit.getStatus() != VisitStatus.IN_TREATMENT)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ cấp quyền khám thay cho ca đang khám.");
        var doctor = staff.findById(request.coveringDoctorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bác sĩ khám thay."));
        if (visit.getPrimaryDoctor() == null || doctor.getStaffId().equals(visit.getPrimaryDoctor().getStaffId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bác sĩ khám thay phải khác bác sĩ chính.");
        if (doctor.getUser() == null || !assignments.hasActiveDoctorAssignment(
                doctor.getStaffId(), visit.getBranch().getBranchId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bác sĩ khám thay chưa được phân công tại chi nhánh.");
        DoctorCoverGrant grant = new DoctorCoverGrant();
        grant.setVisit(visit); grant.setCoveringDoctor(doctor); grant.setGrantedBy(actor);
        grant.setReason(request.reason().trim()); grant.setGrantedAt(LocalDateTime.now());
        grant.setExpiresAt(grant.getGrantedAt().plusHours(request.hours()));
        grants.save(grant);
        audit(visitId, actor, "GRANT_DOCTOR_COVER", grant.getReason(), visit.getBranch());
        return Map.of("grantId", grant.getId());
    }

    @DeleteMapping("/{grantId}")
    @Transactional
    public void revoke(@PathVariable Long visitId, @PathVariable Long grantId, HttpSession session) {
        User actor = admin(session);
        var grant = grants.findById(grantId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!grant.getVisit().getVisitId().equals(visitId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (grant.getRevokedAt() != null) return;
        grant.setRevokedAt(LocalDateTime.now());
        grants.save(grant);
        audit(visitId, actor, "REVOKE_DOCTOR_COVER", "grantId=" + grantId, grant.getVisit().getBranch());
    }

    private User admin(HttpSession session) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (!(principal instanceof User user) || user.getUserId() == null || !users.hasActiveAdminRole(user.getUserId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ quản trị viên được cấp quyền khám thay.");
        return users.findById(user.getUserId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private void audit(Long visitId, User actor, String action, String reason, com.cvdcms.entity.Branch branch) {
        SystemAuditLog log = new SystemAuditLog();
        log.setUser(actor); log.setBranch(branch); log.setEntityTable("visits"); log.setEntityId(visitId);
        log.setAction(action);
        try { log.setNewValuesJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("reason", reason))); }
        catch (Exception ex) { throw new IllegalStateException("Không thể ghi audit khám thay.", ex); }
        audits.save(log);
    }
}
