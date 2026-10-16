
package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.service.BranchService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/branches")
public class BranchController {

    private final BranchService branchService;

    // Tiêm Service qua constructor để Controller không phụ thuộc
    // trực tiếp vào cách truy vấn database.
    public BranchController(BranchService branchService) {
        this.branchService = branchService;
    }

    // GET /admin/branches
    // Hiển thị danh sách chi nhánh và biểu mẫu thêm mới.
    @GetMapping
    public String listBranches(Model model) {
        model.addAttribute("branches", branchService.getAllBranches());

        // Tạo đối tượng rỗng để Thymeleaf liên kết dữ liệu biểu mẫu.
        model.addAttribute("branchForm", new Branch());

        return "branch/list";
    }

    // POST /admin/branches
    // Nhận dữ liệu từ biểu mẫu và yêu cầu Service tạo chi nhánh.
    @PostMapping
    public String createBranch(
            @ModelAttribute("branchForm") Branch branch,
            RedirectAttributes redirectAttributes) {

        try {
            branchService.createBranch(branch);

            // Flash attribute chỉ tồn tại cho lần chuyển hướng tiếp theo,
            // phù hợp để hiển thị thông báo sau khi thêm thành công.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Thêm chi nhánh thành công."
            );
        } catch (IllegalArgumentException ex) {
            // Hiển thị thông báo nghiệp vụ nếu dữ liệu không hợp lệ.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        // Redirect để tránh gửi lại biểu mẫu khi người dùng tải lại trang.
        return "redirect:/admin/branches";
    }

    // POST /admin/branches/{id}/deactivate
    // Ngừng hoạt động, không xóa bản ghi khỏi database.
    @PostMapping("/{id}/deactivate")
    public String deactivateBranch(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        try {
            branchService.deactivateBranch(id);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã ngừng hoạt động chi nhánh."
            );
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/admin/branches";
    }

    // POST /admin/branches/{id}/activate
    // Cho phép kích hoạt lại chi nhánh đã ngừng hoạt động.
    @PostMapping("/{id}/activate")
    public String activateBranch(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        try {
            branchService.activateBranch(id);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã kích hoạt chi nhánh."
            );
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/admin/branches";
    }
}
