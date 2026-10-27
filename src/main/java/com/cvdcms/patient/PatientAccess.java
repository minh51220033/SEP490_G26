package com.cvdcms.patient;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PatientAccess {
    private final UserRepository users;

    public PatientAccess(UserRepository users) {
        this.users = users;
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
        boolean admin = hasRole(user, "ADMIN");
        if (!admin && !hasRole(user, "LE_TAN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ lễ tân hoặc quản trị viên được truy cập.");
        }
        Long branchId = admin ? null : user.getBranch() == null ? null : user.getBranch().getBranchId();
        if (!admin && branchId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản chưa được phân chi nhánh.");
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
            return admin || branchId.equals(targetBranchId);
        }
    }
}
