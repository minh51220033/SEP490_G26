package com.cvdcms.reception;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
        when(assignments.activeReceptionBranchIds(eq(9L), any(LocalDate.class))).thenReturn(List.of(1L, 2L));
        var scope = new ReceptionAccess(users, assignments).require(session);
        assertTrue(scope.permits(1L)); assertTrue(scope.permits(2L)); assertFalse(scope.permits(3L));
    }

    @Test
    void receptionistWithoutAssignmentIsDenied() {
        User user = receptionist();
        when(session.getAttribute("loggedInUser")).thenReturn(user);
        when(users.findById(9L)).thenReturn(Optional.of(user));
        when(assignments.activeReceptionBranchIds(eq(9L), any(LocalDate.class))).thenReturn(List.of());
        assertThrows(ResponseStatusException.class, () -> new ReceptionAccess(users, assignments).require(session));
    }

    private User receptionist() {
        User user = new User(); user.setUserId(9L); user.setStatus("ACTIVE"); user.setIsActive(true);
        Role role = new Role(); role.setRoleName("LE_TAN"); role.setIsActive(true);
        user.getRoles().add(role); return user;
    }
}
