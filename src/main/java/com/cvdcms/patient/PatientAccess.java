package com.cvdcms.patient;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class PatientAccess {
    private final UserRepository users;
    private final StaffBranchAssignmentRepository staffBranchAssignments;

    public PatientAccess(UserRepository users) {
        this(users, null);
    }

    @Autowired
    public PatientAccess(UserRepository users, StaffBranchAssignmentRepository staffBranchAssignments) {
        this.users = users;
        this.staffBranchAssignments = staffBranchAssignments;
    }

    public Scope require(HttpSession session) {
        Object value = session == null ? null : session.getAttribute("loggedInUser");
        if (!(value instanceof User sessionUser) || sessionUser.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        User user = users.findById(sessionUser.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không hoạt động.");
        }
        boolean admin = hasRole(user, "ADMIN") || users.hasActiveAdminRole(user.getUserId());
        boolean hasReceptionPermission = users.hasActivePermission(user.getUserId(), "APPOINTMENT_MANAGE")
                || users.hasActivePermission(user.getUserId(), "PATIENT_PROFILE_MANAGE");
        if (!admin && !hasReceptionPermission) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản của bạn không được cấp quyền truy cập chức năng này.");
        }
        Long branchId = admin ? null : user.getBranch() == null ? null : user.getBranch().getBranchId();
        if (!admin && branchId == null && staffBranchAssignments != null) {
            List<Long> activeBranches = staffBranchAssignments.findActiveBranchIdsByUserId(user.getUserId(), AssignmentStatus.ACTIVE);
            if (activeBranches != null && !activeBranches.isEmpty()) {
                branchId = activeBranches.get(0);
            }
        }
        if (!admin && branchId == null) {
            return new Scope(user, null, true);
        }
        return new Scope(user, branchId, admin);
    }

    private boolean hasRole(User user, String name) {
        return user.getRoles().stream().anyMatch(role -> activeRole(role, name));
    }

    private boolean activeRole(Role role, String name) {
        if (role == null || !Boolean.TRUE.equals(role.getIsActive()) || role.getRoleName() == null) return false;
        String actual = role.getRoleName().toUpperCase();
        return actual.equals(name) || actual.equals("ROLE_" + name);
    }

    public record Scope(User user, Long branchId, boolean admin) {
        public boolean permits(Long targetBranchId) {
            return admin || (branchId != null && branchId.equals(targetBranchId));
        }
    }
}
