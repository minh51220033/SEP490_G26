package com.cvdcms.patient;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class PatientAccess {
    private final UserRepository users;
    private final StaffBranchAssignmentRepository staffBranchAssignments;
    private BranchRepository branchRepository;

    @Autowired(required = false)
    public void setBranchRepository(BranchRepository branchRepository) {
        this.branchRepository = branchRepository;
    }

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
        Long userId = null;
        if (value instanceof User sessionUser) {
            userId = sessionUser.getUserId();
        } else if (value != null && "com.cvdcms.entity.User".equals(value.getClass().getName())) {
            try {
                Object idVal = value.getClass().getMethod("getUserId").invoke(value);
                if (idVal instanceof Long uid) userId = uid;
            } catch (Exception ignored) {}
        }
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        session.setAttribute("loggedInUser", user);
        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không hoạt động.");
        }
        boolean admin = hasRole(user, "ADMIN") || users.hasActiveAdminRole(user.getUserId());
        boolean doctor = hasRole(user, "BAC_SI");
        boolean hasReceptionPermission = doctor
                || users.hasActivePermission(user.getUserId(), "APPOINTMENT_MANAGE")
                || users.hasActivePermission(user.getUserId(), "APPOINTMENT_VIEW")
                || users.hasActivePermission(user.getUserId(), "RECEPTION_CHECKIN")
                || users.hasActivePermission(user.getUserId(), "RECEPTION_DISPATCH")
                || users.hasActivePermission(user.getUserId(), "RECEPTION_MEMBERSHIPS")
                || users.hasActivePermission(user.getUserId(), "PATIENT_PROFILE_MANAGE")
                || users.hasActivePermission(user.getUserId(), "CUSTOMER_CARE_MANAGE")
                || users.hasActivePermission(user.getUserId(), "MEDICAL_RECORD_VIEW");
        if (!admin && !hasReceptionPermission) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản của bạn không được cấp quyền truy cập chức năng này.");
        }
        Set<Long> accessibleBranchIds = new HashSet<>();
        if (user.getBranch() != null && user.getBranch().getBranchId() != null) {
            accessibleBranchIds.add(user.getBranch().getBranchId());
        }
        if (staffBranchAssignments != null) {
            List<Long> activeBranches = staffBranchAssignments.findActiveBranchIdsByUserId(user.getUserId(), AssignmentStatus.ACTIVE);
            if (activeBranches != null && !activeBranches.isEmpty()) {
                accessibleBranchIds.addAll(activeBranches);
            }
        }

        if (!admin && accessibleBranchIds.isEmpty() && branchRepository != null) {
            for (Branch b : branchRepository.findByIsActiveTrueOrderByBranchNameAsc()) {
                if (b.getBranchId() != null) accessibleBranchIds.add(b.getBranchId());
            }
        }

        Long branchId = admin ? null : (user.getBranch() == null ? null : user.getBranch().getBranchId());
        if (session != null) {
            Object sBranchId = session.getAttribute("currentBranchId");
            if (sBranchId instanceof Long sId && (admin || accessibleBranchIds.contains(sId))) {
                branchId = sId;
            }
        }
        if (!admin && branchId == null && !accessibleBranchIds.isEmpty()) {
            branchId = accessibleBranchIds.iterator().next();
        }
        if (!admin && branchId == null && accessibleBranchIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản của bạn chưa được phân công chi nhánh hoạt động.");
        }
        return new Scope(user, branchId, admin, Collections.unmodifiableSet(accessibleBranchIds));
    }

    private boolean hasRole(User user, String name) {
        return user.getRoles().stream().anyMatch(role -> activeRole(role, name));
    }

    private boolean activeRole(Role role, String name) {
        if (role == null || !Boolean.TRUE.equals(role.getIsActive()) || role.getRoleName() == null) return false;
        String actual = role.getRoleName().toUpperCase();
        return actual.equals(name) || actual.equals("ROLE_" + name);
    }

    public record Scope(User user, Long branchId, boolean admin, Set<Long> branchIds) {
        public Scope(User user, Long branchId, boolean admin) {
            this(user, branchId, admin, branchId != null ? Set.of(branchId) : Collections.emptySet());
        }

        public boolean permits(Long targetBranchId) {
            if (admin) return true;
            if (targetBranchId == null) return false;
            if (branchId != null && branchId.equals(targetBranchId)) return true;
            return branchIds != null && branchIds.contains(targetBranchId);
        }
    }
}
