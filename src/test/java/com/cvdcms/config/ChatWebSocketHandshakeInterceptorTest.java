package com.cvdcms.config;

import com.cvdcms.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChatWebSocketHandshakeInterceptorTest {

    private final ChatWebSocketHandshakeInterceptor interceptor = new ChatWebSocketHandshakeInterceptor();

    @Test
    void beforeHandshake_ReturnsFalse_WhenNotServletRequest() {
        ServerHttpRequest nonServletRequest = mock(ServerHttpRequest.class);
        Map<String, Object> attrs = new HashMap<>();

        boolean allowed = interceptor.beforeHandshake(
                nonServletRequest, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attrs);

        assertFalse(allowed);
        assertTrue(attrs.isEmpty());
    }

    @Test
    void beforeHandshake_ReturnsFalse_WhenNoHttpSessionOrMissingLoggedInUser() {
        MockHttpServletRequest noSessionReq = new MockHttpServletRequest();
        ServletServerHttpRequest req1 = new ServletServerHttpRequest(noSessionReq);
        assertFalse(interceptor.beforeHandshake(
                req1, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));

        MockHttpServletRequest emptySessionReq = new MockHttpServletRequest();
        emptySessionReq.setSession(new MockHttpSession());
        ServletServerHttpRequest req2 = new ServletServerHttpRequest(emptySessionReq);
        assertFalse(interceptor.beforeHandshake(
                req2, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));
    }

    @Test
    void beforeHandshake_ReturnsFalse_WhenUserInactiveOrStatusNotActive() {
        User inactive = new User();
        inactive.setUserId(10L);
        inactive.setUsername("user10");
        inactive.setIsActive(false);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true).setAttribute("loggedInUser", inactive);
        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(req), mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));

        User suspended = new User();
        suspended.setUserId(11L);
        suspended.setUsername("user11");
        suspended.setIsActive(true);
        suspended.setStatus("LOCKED");
        req.getSession().setAttribute("loggedInUser", suspended);
        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(req), mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));
    }

    @Test
    void beforeHandshake_StoresUserAttributesAndReturnsTrue_WhenUserActive() {
        User active = new User();
        active.setUserId(25L);
        active.setUsername("letan25");
        active.setIsActive(true);
        active.setStatus("ACTIVE");

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true).setAttribute("loggedInUser", active);
        Map<String, Object> attrs = new HashMap<>();

        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(req), mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attrs);

        assertTrue(allowed);
        assertEquals(25L, attrs.get(ChatWebSocketHandshakeInterceptor.ATTR_USER_ID));
        assertEquals("letan25", attrs.get(ChatWebSocketHandshakeInterceptor.ATTR_USERNAME));
    }

    @Test
    void afterHandshake_ExecutesCleanlyWithoutErrors() {
        assertDoesNotThrow(() -> interceptor.afterHandshake(
                mock(ServerHttpRequest.class), mock(ServerHttpResponse.class), mock(WebSocketHandler.class), null));
    }
}
