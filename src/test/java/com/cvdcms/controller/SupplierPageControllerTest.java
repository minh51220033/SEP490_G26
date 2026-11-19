package com.cvdcms.controller;

import com.cvdcms.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SupplierPageControllerTest {

    private final SupplierPageController controller = new SupplierPageController();

    @Test
    void supplierManagementPage_RedirectsToLogin_WhenSessionMissingOrUnauthenticated() {
        assertEquals("redirect:/login", controller.supplierManagementPage(null));
        assertEquals("redirect:/login", controller.supplierManagementPage(new MockHttpSession()));

        User userWithoutId = new User();
        MockHttpSession incompleteSession = new MockHttpSession();
        incompleteSession.setAttribute("loggedInUser", userWithoutId);
        assertEquals("redirect:/login", controller.supplierManagementPage(incompleteSession));
    }

    @Test
    void supplierManagementPage_ReturnsViewName_WhenUserAuthenticated() {
        User validUser = new User();
        validUser.setUserId(1L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", validUser);

        assertEquals("suppliers/list", controller.supplierManagementPage(session));
    }
}
