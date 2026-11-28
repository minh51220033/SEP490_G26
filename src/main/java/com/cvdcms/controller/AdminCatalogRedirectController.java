package com.cvdcms.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * [Phân hệ]: Danh mục dịch vụ (Quản trị)
 * [Vai trò]: Controller điều hướng trung tâm quản lý danh mục sang từng màn hình danh mục con tương ứng.
 */
@Controller
public class AdminCatalogRedirectController {

    /**
     * [Chức năng]: Xử lý điều hướng endpoint GET /admin/catalogs và /admin/catalog theo tham số tab.
     * [Mục đích]: Chuyển tiếp người dùng đến màn hình nhóm dịch vụ, thuốc, mã bệnh ICD-10 hoặc mặc định về danh sách dịch vụ (/admin/services).
     */
    @GetMapping({"/admin/catalogs", "/admin/catalog"})
    public String catalogHub(@RequestParam(value = "tab", required = false) String tab) {
        if ("categories".equalsIgnoreCase(tab)) {
            return "redirect:/admin/services/categories";
        }
        if ("medications".equalsIgnoreCase(tab)) {
            return "redirect:/admin/medications";
        }
        if ("icd10".equalsIgnoreCase(tab)) {
            return "redirect:/admin/icd10";
        }
        return "redirect:/admin/services";
    }
}

