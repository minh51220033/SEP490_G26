package com.cvdcms.patient;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientAccessTest {

    @Mock private UserRepository users;
    @Mock private StaffBranchAssignmentRepository assignments;
    @Mock private BranchRepository branchRepository;

    private PatientAccess patientAccess;

    @BeforeEach
    void setUp() {
        patientAccess = new PatientAccess(users, assignments);
        patientAccess.setBranchRepository(branchRepository);
    }

    @Test
    @DisplayName("Security: Nhân viên không có chi nhánh và không có phân công phải bị chặn 403 ngay cả khi BranchRepository được inject")
    void unassignedStaffIsDenied403EvenWhenBranchRepositoryPresent() {
        User staff = activeStaff(10L, "LE_TAN", null);
        when(users.findById(10L)).thenReturn(Optional.of(staff));
        when(users.hasActiveAdminRole(10L)).thenReturn(false);
        when(users.hasActivePermission(10L, "APPOINTMENT_MANAGE")).thenReturn(true);
        when(assignments.findActiveBranchIdsByUserId(10L, AssignmentStatus.ACTIVE)).thenReturn(List.of());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", staff);
        session.setAttribute("currentBranchId", 1L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> patientAccess.require(session));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(branchRepository);
    }

    @Test
    @DisplayName("Security: Nhân viên có chi nhánh không hoạt động và không còn phân công khác bị chặn 403")
    void staffWithInactiveHomeBranchAndNoAssignmentsIsDenied403() {
        Branch inactive = branch(5L, false);
        User staff = activeStaff(11L, "LE_TAN", inactive);
        when(users.findById(11L)).thenReturn(Optional.of(staff));
        when(users.hasActiveAdminRole(11L)).thenReturn(false);
        when(users.hasActivePermission(11L, "APPOINTMENT_MANAGE")).thenReturn(false);
        when(users.hasActivePermission(11L, "APPOINTMENT_VIEW")).thenReturn(false);
        when(users.hasActivePermission(11L, "RECEPTION_CHECKIN")).thenReturn(true);
        when(assignments.findActiveBranchIdsByUserId(11L, AssignmentStatus.ACTIVE)).thenReturn(List.of());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", staff);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> patientAccess.require(session));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("Security: Nhân viên cơ sở 1 bị chặn truy cập cơ sở 2 nhưng dùng được nhiều cơ sở được phân công")
    void staffPermitsOnlyAssignedBranchesAndRejectsSessionBranchSpoofing() {
        Branch b1 = branch(1L, true);
        User staff = activeStaff(12L, "LE_TAN", b1);
        when(users.findById(12L)).thenReturn(Optional.of(staff));
        when(users.hasActiveAdminRole(12L)).thenReturn(false);
        when(users.hasActivePermission(12L, "APPOINTMENT_MANAGE")).thenReturn(true);
        when(assignments.findActiveBranchIdsByUserId(12L, AssignmentStatus.ACTIVE)).thenReturn(List.of(2L));

        // Giả mạo currentBranchId=99 trong session không được chấp nhận
        MockHttpSession spoofSession = new MockHttpSession();
        spoofSession.setAttribute("loggedInUser", staff);
        spoofSession.setAttribute("currentBranchId", 99L);

        PatientAccess.Scope scope = patientAccess.require(spoofSession);
        assertFalse(scope.admin());
        assertEquals(1L, scope.branchId());
        assertTrue(scope.permits(1L));
        assertTrue(scope.permits(2L));
        assertFalse(scope.permits(99L));
        assertFalse(scope.permits(null));

        // Chuyển session sang branch 2 (có trong assignment) thì hợp lệ
        MockHttpSession validSession = new MockHttpSession();
        validSession.setAttribute("loggedInUser", staff);
        validSession.setAttribute("currentBranchId", 2L);
        PatientAccess.Scope branch2Scope = patientAccess.require(validSession);
        assertEquals(2L, branch2Scope.branchId());
    }

    @Test
    @DisplayName("Security: Admin hợp lệ truy cập mọi chi nhánh; tài khoản khóa/thiếu quyền/chưa đăng nhập bị chặn")
    void adminInactiveAndUnauthorizedChecks() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () -> patientAccess.require(null)).getStatusCode());

        User admin = activeStaff(1L, "ADMIN", null);
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("loggedInUser", admin);
        PatientAccess.Scope adminScope = patientAccess.require(adminSession);
        assertTrue(adminScope.admin());
        assertTrue(adminScope.permits(999L));
        assertTrue(patientAccess.canAssignDoctor(adminScope));

        User locked = activeStaff(13L, "ADMIN", null);
        locked.setIsActive(false);
        when(users.findById(13L)).thenReturn(Optional.of(locked));
        MockHttpSession lockedSession = new MockHttpSession();
        lockedSession.setAttribute("loggedInUser", locked);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> patientAccess.require(lockedSession)).getStatusCode());

        User regularNoPerm = activeStaff(14L, "KE_TOAN", branch(1L, true));
        when(users.findById(14L)).thenReturn(Optional.of(regularNoPerm));
        when(users.hasActiveAdminRole(14L)).thenReturn(false);
        MockHttpSession noPermSession = new MockHttpSession();
        noPermSession.setAttribute("loggedInUser", regularNoPerm);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> patientAccess.require(noPermSession)).getStatusCode());

        PatientAccess.Scope nonDispatchScope = new PatientAccess.Scope(regularNoPerm, 1L, false);
        when(users.hasActivePermission(14L, "CLINICAL_DISPATCH")).thenReturn(false);
        assertFalse(patientAccess.canAssignDoctor(nonDispatchScope));
        when(users.hasActivePermission(14L, "CLINICAL_DISPATCH")).thenReturn(true);
        assertTrue(patientAccess.canAssignDoctor(nonDispatchScope));
    }

    private User activeStaff(Long id, String roleName, Branch branch) {
        User u = new User();
        u.setUserId(id);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setBranch(branch);
        Role r = new Role();
        r.setRoleName(roleName);
        r.setIsActive(true);
        u.setRoles(Set.of(r));
        return u;
    }

    private Branch branch(Long id, boolean active) {
        Branch b = new Branch();
        b.setBranchId(id);
        b.setIsActive(active);
        return b;
    }
}
