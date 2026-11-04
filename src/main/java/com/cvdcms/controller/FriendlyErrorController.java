package com.cvdcms.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

@Controller
public class FriendlyErrorController implements ErrorController {
    @RequestMapping("/error")
    public Object error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = value instanceof Number number ? number.intValue() : 500;
        String title = switch (status) {
            case 400 -> "Thông tin chưa hợp lệ";
            case 401 -> "Cần đăng nhập";
            case 403 -> "Không có quyền truy cập";
            case 404 -> "Không tìm thấy trang";
            case 409 -> "Dữ liệu đã thay đổi";
            default -> "Không thể hoàn tất yêu cầu";
        };
        String description = switch (status) {
            case 400 -> "Vui lòng kiểm tra thông tin đã nhập và thử lại.";
            case 401 -> "Vui lòng đăng nhập để tiếp tục công việc.";
            case 403 -> "Tài khoản của bạn chưa được cấp quyền xem nội dung này.";
            case 404 -> "Đường dẫn không tồn tại hoặc nội dung đã được di chuyển.";
            case 409 -> "Vui lòng tải lại trang và kiểm tra dữ liệu trước khi thao tác tiếp.";
            default -> "Hệ thống gặp sự cố. Vui lòng thử lại hoặc liên hệ quản trị viên.";
        };
        String path = String.valueOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        String accept = request.getHeader("Accept");
        String requestedWith = request.getHeader("X-Requested-With");
        boolean isApi = path.startsWith("/api/") || path.contains("/api/")
                || (accept != null && accept.contains("application/json"))
                || "XMLHttpRequest".equalsIgnoreCase(requestedWith);
        if (isApi) {
            Object raw = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
            String message = status >= 500 || raw == null || raw.toString().isBlank()
                    ? description : raw.toString();
            return ResponseEntity.status(status).body(Map.of("status", status, "message", message));
        }
        ModelAndView page = new ModelAndView("error/friendly");
        page.addObject("status", status);
        page.addObject("title", title);
        page.addObject("description", description);
        return page;
    }
}
