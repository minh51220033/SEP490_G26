package com.cvdcms.emr;

import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.Set;

@Component
public class EmrAccess {
    private final UserRepository users;
    private final StaffBranchAssignmentRepository assignments;

    public EmrAccess(UserRepository users, StaffBranchAssignmentRepository assignments) {
        this.users = users;
        this.assignments = assignments;
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa.");
        }
        boolean admin = hasRole(user, "ADMIN", "ROLE_ADMIN") || users.hasActiveAdminRole(user.getUserId());
        boolean doctor = hasRole(user, "BAC_SI", "ROLE_BAC_SI")
                || users.hasActivePermission(user.getUserId(), "MEDICAL_RECORD_VIEW");
        if (!admin && !doctor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tài khoản không có quyền truy cập module bệnh án điện tử.");
        }
        Set<Long> branchIds = new HashSet<>(assignments.findActiveBranchIdsByUserId(
                user.getUserId(), AssignmentStatus.ACTIVE));
        if (user.getBranch() != null) branchIds.add(user.getBranch().getBranchId());
        if (!admin && branchIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tài khoản bác sĩ chưa được phân chi nhánh.");
        }
        return new Scope(user, admin, doctor, Set.copyOf(branchIds));
    }

    public void requireBranch(Scope scope, Long branchId) {
        if (!scope.admin() && (branchId == null || !scope.branchIds().contains(branchId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ trong phạm vi được phân công.");
        }
    }

    private boolean hasRole(User user, String... names) {
        return user.getRoles().stream().anyMatch(role -> Boolean.TRUE.equals(role.getIsActive())
                && java.util.Arrays.stream(names).anyMatch(name -> name.equalsIgnoreCase(role.getRoleName())));
    }

    public record Scope(User user, boolean admin, boolean doctor, Set<Long> branchIds) {
        public boolean permits(Long branchId) {
            return admin || (branchId != null && branchIds.contains(branchId));
        }
    }
}
