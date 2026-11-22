package com.cvdcms.reception;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReceptionAccessTest {
    @Mock UserRepository users;
    @Mock ReceptionAssignmentRepository assignments;
    @Mock HttpSession session;

    @Test
    void receptionistCanOnlyUseCurrentlyAssignedBranches() {
        User user = receptionist();
        when(session.getAttribute("loggedInUser")).thenReturn(user);
        when(users.findById(9L)).thenReturn(Optional.of(user));
        when(users.hasActivePermission(9L, "APPOINTMENT_MANAGE")).thenReturn(true);
        when(assignments.activeReceptionBranchIds(eq(9L), any(LocalDate.class))).thenReturn(List.of(1L, 2L));
        var scope = new ReceptionAccess(users, assignments).require(session);
        assertFalse(scope.admin());
        assertTrue(scope.permits(1L));
        assertTrue(scope.permits(2L));
        assertFalse(scope.permits(3L));
        assertFalse(scope.permits(null));
    }

    @Test
    void receptionistWithoutAssignmentIsDenied() {
        User user = receptionist();
        when(session.getAttribute("loggedInUser")).thenReturn(user);
        when(users.findById(9L)).thenReturn(Optional.of(user));
        when(users.hasActivePermission(9L, "APPOINTMENT_MANAGE")).thenReturn(true);
        when(assignments.activeReceptionBranchIds(eq(9L), any(LocalDate.class))).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> new ReceptionAccess(users, assignments).require(session));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void receptionistWithInactiveHomeBranchAndNoAssignmentIsDenied403() {
        User user = receptionist();
        Branch inactiveBranch = new Branch();
        inactiveBranch.setBranchId(5L);
        inactiveBranch.setIsActive(false);
        user.setBranch(inactiveBranch);

        when(session.getAttribute("loggedInUser")).thenReturn(user);
        when(users.findById(9L)).thenReturn(Optional.of(user));
        when(users.hasActivePermission(9L, "APPOINTMENT_MANAGE")).thenReturn(false);
        when(users.hasActivePermission(9L, "APPOINTMENT_VIEW")).thenReturn(false);
        when(users.hasActivePermission(9L, "RECEPTION_CHECKIN")).thenReturn(true);
        when(assignments.activeReceptionBranchIds(eq(9L), any(LocalDate.class))).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> new ReceptionAccess(users, assignments).require(session));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void activeAdminPermitsAllBranchesEvenWithoutBranchAssignments() {
        User admin = new User();
        admin.setUserId(1L);
        admin.setStatus("ACTIVE");
        admin.setIsActive(true);
        Role adminRole = new Role();
        adminRole.setRoleName("ADMIN");
        adminRole.setIsActive(true);
        admin.getRoles().add(adminRole);

        when(session.getAttribute("loggedInUser")).thenReturn(admin);
        when(users.findById(1L)).thenReturn(Optional.of(admin));

        var scope = new ReceptionAccess(users, assignments).require(session);
        assertTrue(scope.admin());
        assertTrue(scope.canManage());
        assertTrue(scope.permits(1L));
        assertTrue(scope.permits(99L));
        verifyNoInteractions(assignments);
    }

    @Test
    void inactiveAdminOrMissingPermissionOrMissingSessionIsRejected() {
        ResponseStatusException unauth = assertThrows(ResponseStatusException.class,
                () -> new ReceptionAccess(users, assignments).require(session));
        assertEquals(HttpStatus.UNAUTHORIZED, unauth.getStatusCode());

        User disabledAdmin = new User();
        disabledAdmin.setUserId(2L);
        disabledAdmin.setStatus("INACTIVE");
        disabledAdmin.setIsActive(false);
        Role adminRole = new Role();
        adminRole.setRoleName("ADMIN");
        adminRole.setIsActive(true);
        disabledAdmin.getRoles().add(adminRole);

        when(session.getAttribute("loggedInUser")).thenReturn(disabledAdmin);
        when(users.findById(2L)).thenReturn(Optional.of(disabledAdmin));
        ResponseStatusException locked = assertThrows(ResponseStatusException.class,
                () -> new ReceptionAccess(users, assignments).require(session));
        assertEquals(HttpStatus.FORBIDDEN, locked.getStatusCode());

        User staffNoPerm = receptionist();
        when(session.getAttribute("loggedInUser")).thenReturn(staffNoPerm);
        when(users.findById(9L)).thenReturn(Optional.of(staffNoPerm));
        when(users.hasActiveAdminRole(9L)).thenReturn(false);
        ResponseStatusException noPerm = assertThrows(ResponseStatusException.class,
                () -> new ReceptionAccess(users, assignments).require(session));
        assertEquals(HttpStatus.FORBIDDEN, noPerm.getStatusCode());
    }

    private User receptionist() {
        User user = new User(); user.setUserId(9L); user.setStatus("ACTIVE"); user.setIsActive(true);
        Role role = new Role(); role.setRoleName("LE_TAN"); role.setIsActive(true);
        user.getRoles().add(role); return user;
    }
}
