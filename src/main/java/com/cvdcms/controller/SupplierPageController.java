package com.cvdcms.controller;

import com.cvdcms.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller điều hướng giao diện Quản lý Nhà cung cấp (/suppliers).
 * Kiểm tra phiên đăng nhập hợp lệ trước khi hiển thị trang.
 */
@Controller
public class SupplierPageController {

    /**
     * Hiển thị trang Quản lý Nhà cung cấp hoặc chuyển hướng về /login nếu chưa đăng nhập.
     */
    @GetMapping("/suppliers")
    public String supplierManagementPage(HttpSession session) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            return "redirect:/login";
        }
        return "suppliers/list";
    }
}
