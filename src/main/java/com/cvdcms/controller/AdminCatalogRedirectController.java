package com.cvdcms.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Điều hướng trang trung tâm Quản lý danh mục.
 * Mặc định chuyển tiếp đến tab 1 (Danh mục dịch vụ) hoặc tab tương ứng từ tham số.
 */
@Controller
public class AdminCatalogRedirectController {

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

