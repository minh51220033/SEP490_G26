package com.cvdcms.controller;

import com.cvdcms.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * MVC Controller điều hướng giao diện Quản lý Nhà cung cấp ({@code /suppliers}).
 *
 * <p><b>Chức năng:</b> Kiểm tra phiên đăng nhập hợp lệ trong {@link HttpSession} trước khi render
 * template Thymeleaf {@code templates/suppliers/list.html}. Dữ liệu danh sách và công nợ trên trang
 * được tải bất đồng bộ từ {@code suppliers.js} qua các REST API {@code /api/suppliers} và
 * {@code /api/inventory/supplier-payments/debts}.</p>
 */
@Controller
public class SupplierPageController {

    /**
     * Hiển thị trang Quản lý Nhà cung cấp &amp; Công nợ.
     *
     * <p><b>Endpoint:</b> {@code GET /suppliers}</p>
     * <p><b>Xử lý:</b> Đọc thuộc tính {@code loggedInUser} trong session; nếu chưa đăng nhập hoặc
     * {@code userId == null} thì chuyển hướng về {@code /login}, ngược lại trả về view {@code suppliers/list}.</p>
     * <p><b>Được gọi từ:</b> Menu sidebar mục "Nhà cung cấp" hoặc liên kết điều hướng từ trang Kho.</p>
     *
     * @param session phiên HTTP hiện tại
     * @return tên view {@code "suppliers/list"} hoặc {@code "redirect:/login"}
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
