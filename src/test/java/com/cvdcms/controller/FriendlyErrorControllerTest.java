package com.cvdcms.controller;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FriendlyErrorControllerTest {
    private final FriendlyErrorController controller = new FriendlyErrorController();

    @Test
    void apiConflictReturnsJsonMessageWithoutStackTrace() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 409);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/portal/appointments/api/17/reschedule");
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Khung giờ đã hết chỗ.");
        ResponseEntity<?> result = assertInstanceOf(ResponseEntity.class, controller.error(request));
        assertEquals(409, result.getStatusCode().value());
        assertEquals("Khung giờ đã hết chỗ.", ((Map<?, ?>) result.getBody()).get("message"));
    }

    @Test
    void htmlNotFoundRendersFriendlyPage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/unknown");
        ModelAndView result = assertInstanceOf(ModelAndView.class, controller.error(request));
        assertEquals("error/friendly", result.getViewName());
        assertEquals("Không tìm thấy trang", result.getModel().get("title"));
    }

    @Test
    void serverErrorDoesNotExposeInternalMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/emr/records");
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Database password leaked");
        ResponseEntity<?> result = assertInstanceOf(ResponseEntity.class, controller.error(request));
        assertFalse(((Map<?, ?>) result.getBody()).get("message").toString().contains("password"));
    }

    @Test
    void ajaxRequestReturnsJsonErrorEvenWithoutApiPath() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/patients");
        request.addHeader("Accept", "application/json");
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Thông tin chưa hợp lệ");
        ResponseEntity<?> result = assertInstanceOf(ResponseEntity.class, controller.error(request));
        assertEquals(400, result.getStatusCode().value());
        assertEquals("Thông tin chưa hợp lệ", ((Map<?, ?>) result.getBody()).get("message"));
    }
}
