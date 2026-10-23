package com.cvdcms.controller;

import com.cvdcms.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller điều hướng giao diện chính của chức năng Quản lý kho (/inventory).
 * Kiểm tra phiên đăng nhập trước khi hiển thị trang.
 */
@Controller
public class InventoryPageController {

    /**
     * Hiển thị trang Quản lý kho hoặc chuyển hướng về /login nếu chưa đăng nhập.
     */
    @GetMapping("/inventory")
    public String inventoryPage(HttpSession session) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            return "redirect:/login";
        }
        return "inventory/list";
    }
}
