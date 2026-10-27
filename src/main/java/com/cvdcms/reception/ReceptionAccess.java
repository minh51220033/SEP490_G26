package com.cvdcms.reception;
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
    private final UserRepository users; private final ReceptionAssignmentRepository assignments;
    public ReceptionAccess(UserRepository users, ReceptionAssignmentRepository assignments) { this.users = users; this.assignments = assignments; }
    public Scope require(HttpSession session) {
        Object value = session == null ? null : session.getAttribute("loggedInUser");
        if (!(value instanceof User loggedIn) || loggedIn.getUserId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        User user = users.findById(loggedIn.getUserId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tài khoản không tồn tại."));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không hoạt động.");
        boolean admin = hasRole(user, "ADMIN");
        if (!admin && !hasRole(user, "LE_TAN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ lễ tân hoặc quản trị viên được tiếp đón.");
        List<Long> branchIds = admin ? List.of() : assignments.activeReceptionBranchIds(user.getUserId(), LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
        if (!admin && branchIds.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Lễ tân chưa có phân công chi nhánh còn hiệu lực.");
        return new Scope(user, admin, branchIds);
    }
    private boolean hasRole(User user, String name) {
        for (Role role : user.getRoles()) if (role != null && Boolean.TRUE.equals(role.getIsActive()) && role.getRoleName() != null) {
            String actual = role.getRoleName().toUpperCase(); if (actual.equals(name) || actual.equals("ROLE_" + name)) return true;
        } return false;
    }
    public record Scope(User user, boolean admin, List<Long> branchIds) { public boolean permits(Long branchId) { return admin || branchIds.contains(branchId); } }
}
