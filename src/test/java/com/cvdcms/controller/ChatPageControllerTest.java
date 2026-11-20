package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.ChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatPageControllerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StaffProfileRepository staffProfileRepository;

    @InjectMocks
    private ChatPageController controller;

    @Test
    void chatInboxPage_RedirectsToLogin_WhenUnauthenticated() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        Model model = new ConcurrentModel();

        assertEquals("redirect:/login", controller.chatInboxPage(null, res, model));
        assertEquals("redirect:/login", controller.chatInboxPage(new MockHttpSession(), res, model));
    }

    @Test
    void chatInboxPage_SendsForbiddenError_WhenPatientBlockedByRequireInboxAccess() throws Exception {
        User patient = new User();
        patient.setUserId(99L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", patient);
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(chatService.requireInboxAccess(99L))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Bệnh nhân không có quyền truy cập"));

        String view = controller.chatInboxPage(session, res, new ConcurrentModel());

        assertNull(view);
        assertEquals(403, res.getStatus());
        assertEquals("Bệnh nhân không có quyền truy cập", res.getErrorMessage());
    }

    @Test
    void chatInboxPage_PopulatesAdminAttributes() throws Exception {
        User admin = new User();
        admin.setUserId(1L);
        admin.setUsername("admin");
        Role r = new Role();
        r.setRoleName("ADMIN");
        admin.setRoles(Set.of(r));

        Branch b1 = new Branch();
        b1.setBranchId(1L);
        b1.setBranchName("Cơ sở 1");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", admin);
        MockHttpServletResponse res = new MockHttpServletResponse();
        Model model = new ConcurrentModel();

        when(chatService.requireInboxAccess(1L)).thenReturn("ADMIN");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.empty());
        when(chatService.toRoleLabel("ADMIN")).thenReturn("Admin");
        when(chatService.getAccessibleBranchesForInbox(1L)).thenReturn(List.of(b1));

        String view = controller.chatInboxPage(session, res, model);

        assertEquals("chat/inbox", view);
        assertEquals(true, model.getAttribute("isAdmin"));
        assertEquals(true, model.getAttribute("canHandlePatients"));
        assertEquals("Quản trị tất cả cơ sở", model.getAttribute("currentBranchName"));
        assertEquals("admin", model.getAttribute("displayName"));
    }

    @Test
    void chatInboxPage_SetsCanHandlePatientsFalseForWarehouseRoleAndFallsBackToUserBranch() throws Exception {
        Branch branch = new Branch();
        branch.setBranchId(2L);
        branch.setBranchName("Cơ sở 2");

        User wh = new User();
        wh.setUserId(8L);
        wh.setUsername("kho02");
        wh.setBranch(branch);

        StaffProfile sp = new StaffProfile();
        sp.setFullName("Trần Thủ Kho");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", wh);
        Model model = new ConcurrentModel();

        when(chatService.requireInboxAccess(8L)).thenReturn("QUAN_LY_KHO");
        when(userRepository.findById(8L)).thenReturn(Optional.of(wh));
        when(staffProfileRepository.findByUser_UserId(8L)).thenReturn(Optional.of(sp));
        when(chatService.toRoleLabel("QUAN_LY_KHO")).thenReturn("Quản lý kho");
        when(chatService.getAccessibleBranchesForInbox(8L)).thenReturn(List.of());

        String view = controller.chatInboxPage(session, new MockHttpServletResponse(), model);

        assertEquals("chat/inbox", view);
        assertEquals(false, model.getAttribute("isAdmin"));
        assertEquals(false, model.getAttribute("canHandlePatients"));
        assertEquals("Trần Thủ Kho", model.getAttribute("displayName"));
        assertEquals("Cơ sở 2", model.getAttribute("currentBranchName"));
        assertEquals(List.of("QUAN_LY_KHO"), model.getAttribute("roleNames"));
    }
}
