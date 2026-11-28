package com.cvdcms.config;

import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierManagementInterceptorTest {

    @Mock
    private UserRepository userRepository;

    private SupplierManagementInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new SupplierManagementInterceptor(userRepository);
    }

    @Test
    @DisplayName("preHandle - Return 401 JSON when session or loggedInUser is missing")
    void preHandle_noSessionOrUnauthenticated_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/suppliers");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, resp, new Object());

        assertFalse(allowed);
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("Vui lòng đăng nhập"));
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("preHandle - Return 403 JSON when session User has null userId")
    void preHandle_nullUserId_returns403() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/suppliers");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", new User());
        req.setSession(session);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, resp, new Object());

        assertFalse(allowed);
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("không hợp lệ"));
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("preHandle - Reject 403 when user lacks supplier management role or permission")
    void preHandle_missingPermissions_returns403() throws Exception {
        User user = new User();
        user.setUserId(25L);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/suppliers");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
        req.setSession(session);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        when(userRepository.hasActiveSupplierManagementRole(25L)).thenReturn(false);
        when(userRepository.hasActivePermission(25L, "SUPPLIER_MANAGE")).thenReturn(false);
        when(userRepository.hasActivePermission(25L, "LABO_SUPPLIERS")).thenReturn(false);

        boolean allowed = interceptor.preHandle(req, resp, new Object());

        assertFalse(allowed);
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("không có quyền"));
    }

    @Test
    @DisplayName("preHandle - Allow request with supplier management role or SUPPLIER_MANAGE / LABO_SUPPLIERS permission")
    void preHandle_withSupplierRoleOrPermission_returnsTrue() throws Exception {
        User user = new User();
        user.setUserId(5L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);

        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/suppliers");
        req1.setSession(session);
        when(userRepository.hasActiveSupplierManagementRole(5L)).thenReturn(true);
        assertTrue(interceptor.preHandle(req1, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/suppliers");
        req2.setSession(session);
        when(userRepository.hasActiveSupplierManagementRole(5L)).thenReturn(false);
        when(userRepository.hasActivePermission(5L, "SUPPLIER_MANAGE")).thenReturn(false);
        when(userRepository.hasActivePermission(5L, "LABO_SUPPLIERS")).thenReturn(true);
        assertTrue(interceptor.preHandle(req2, new MockHttpServletResponse(), new Object()));
    }
}
