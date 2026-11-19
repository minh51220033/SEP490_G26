package com.cvdcms.controller;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.CustomerCareService;
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
class CustomerCarePageControllerTest {

    @Mock
    private CustomerCareService customerCareService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StaffProfileRepository staffProfileRepository;

    @InjectMocks
    private CustomerCarePageController controller;

    @Test
    void customerCarePage_RedirectsToLogin_WhenSessionMissingOrUnauthenticated() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ConcurrentModel();

        assertEquals("redirect:/login", controller.customerCarePage(null, response, model));
        assertEquals("redirect:/login", controller.customerCarePage(new MockHttpSession(), response, model));
    }

    @Test
    void customerCarePage_Sends403ErrorAndReturnsNull_WhenRequireCareRoleThrowsForbidden() throws Exception {
        User user = new User();
        user.setUserId(5L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(customerCareService.requireCareRole(5L))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền CSKH"));

        String view = controller.customerCarePage(session, response, new ConcurrentModel());

        assertNull(view);
        assertEquals(403, response.getStatus());
        assertEquals("Không có quyền CSKH", response.getErrorMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void customerCarePage_PopulatesRoleNamesAndStaffDisplayNameAndReturnsView() throws Exception {
        User sessionUser = new User();
        sessionUser.setUserId(10L);
        sessionUser.setUsername("letan01");

        Role role = new Role();
        role.setRoleName("le_tan");
        User dbUser = new User();
        dbUser.setUserId(10L);
        dbUser.setUsername("letan01");
        dbUser.setRoles(Set.of(role));

        StaffProfile staff = new StaffProfile();
        staff.setFullName("Nguyễn Thị Lễ Tân");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", sessionUser);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ConcurrentModel();

        when(customerCareService.requireCareRole(10L)).thenReturn("LE_TAN");
        when(userRepository.findById(10L)).thenReturn(Optional.of(dbUser));
        when(staffProfileRepository.findByUser_UserId(10L)).thenReturn(Optional.of(staff));

        String view = controller.customerCarePage(session, response, model);

        assertEquals("customer-care/list", view);
        assertEquals("Nguyễn Thị Lễ Tân", model.getAttribute("displayName"));
        assertEquals("LE_TAN", model.getAttribute("careRole"));
        assertEquals(List.of("LE_TAN"), model.getAttribute("roleNames"));
    }

    @Test
    void customerCarePage_FallsBackToUsernameAndAddsCareRole_WhenNoStaffProfileOrRoleNotInSet() throws Exception {
        User sessionUser = new User();
        sessionUser.setUserId(11L);
        sessionUser.setUsername("admin01");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", sessionUser);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ConcurrentModel();

        when(customerCareService.requireCareRole(11L)).thenReturn("ADMIN");
        when(userRepository.findById(11L)).thenReturn(Optional.empty());
        when(staffProfileRepository.findByUser_UserId(11L)).thenReturn(Optional.empty());

        String view = controller.customerCarePage(session, response, model);

        assertEquals("customer-care/list", view);
        assertEquals("admin01", model.getAttribute("displayName"));
        assertEquals(List.of("ADMIN"), model.getAttribute("roleNames"));
    }
}
