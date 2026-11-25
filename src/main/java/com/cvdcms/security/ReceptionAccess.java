package com.cvdcms.security;

import com.cvdcms.repository.ReceptionAssignmentRepository;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class ReceptionAccess {
    private final UserRepository users;
    private final ReceptionAssignmentRepository assignments;

    public ReceptionAccess(UserRepository users, ReceptionAssignmentRepository assignments) {
        this.users = users;
        this.assignments = assignments;
    }

    public Scope require(HttpSession session) {
        Object value = session == null ? null : session.getAttribute("loggedInUser");
        if (!(value instanceof User loggedIn) || loggedIn.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        User user = users.findById(loggedIn.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tài khoản không tồn tại."));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không hoạt động.");
        }
        boolean admin = hasRole(user, "ADMIN") || users.hasActiveAdminRole(user.getUserId());
        boolean hasPermission = users.hasActivePermission(user.getUserId(), "APPOINTMENT_MANAGE")
                || users.hasActivePermission(user.getUserId(), "APPOINTMENT_VIEW")
                || users.hasActivePermission(user.getUserId(), "RECEPTION_CHECKIN")
                || users.hasActivePermission(user.getUserId(), "RECEPTION_DISPATCH")
                || users.hasActivePermission(user.getUserId(), "RECEPTION_MEMBERSHIPS");
        if (!admin && !hasPermission) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không có quyền tiếp đón & check-in.");
        }
        List<Long> assignedIds = admin ? List.of() : assignments.activeReceptionBranchIds(user.getUserId(), LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
        java.util.Set<Long> merged = new java.util.LinkedHashSet<>(assignedIds);
        if (!admin && user.getBranch() != null && user.getBranch().getBranchId() != null
                && !Boolean.FALSE.equals(user.getBranch().getIsActive())) {
            merged.add(user.getBranch().getBranchId());
        }
        List<Long> branchIds = List.copyOf(merged);
        if (!admin && branchIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản chưa được phân công chi nhánh tiếp đón.");
        }
        boolean canManage = admin || users.hasActivePermission(user.getUserId(), "APPOINTMENT_MANAGE");
        return new Scope(user, admin, canManage, branchIds);
    }

    public boolean canAssignDoctor(Scope scope) {
        if (scope == null) {
            return false;
        }
        if (scope.admin()) {
            return true;
        }
        User user = scope.user();
        if (user == null || hasRole(user, "LE_TAN")) {
            return false;
        }
        Long uid = user.getUserId();
        return uid != null && users != null && users.hasActivePermission(uid, "CLINICAL_DISPATCH");
    }

    private boolean hasRole(User user, String name) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        for (Role role : user.getRoles()) {
            if (role != null && Boolean.TRUE.equals(role.getIsActive()) && role.getRoleName() != null) {
                String actual = role.getRoleName().toUpperCase();
                if (actual.equals(name) || actual.equals("ROLE_" + name)) return true;
            }
        }
        return false;
    }

    public record Scope(User user, boolean admin, boolean canManage, List<Long> branchIds) {
        public Scope(User user, boolean admin, List<Long> branchIds) {
            this(user, admin, admin, branchIds);
        }
        public boolean permits(Long branchId) {
            return admin || (branchId != null && branchIds != null && branchIds.contains(branchId));
        }
    }
}
