package com.cvdcms.controller;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.CustomerCareService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Controller điều hướng giao diện chính của chức năng Quản lý Chăm sóc khách hàng (FT44 - UC-26).
 * Kiểm tra chặt chẽ quyền truy cập: chỉ cho phép ADMIN, LE_TAN và BAC_SI vào trang,
 * các role khác trả về lỗi 403 FORBIDDEN.
 */
@Controller
public class CustomerCarePageController {

    private final CustomerCareService customerCareService;
    private final UserRepository userRepository;
    private final StaffProfileRepository staffProfileRepository;

    public CustomerCarePageController(
            CustomerCareService customerCareService,
            UserRepository userRepository,
            StaffProfileRepository staffProfileRepository) {
        this.customerCareService = customerCareService;
        this.userRepository = userRepository;
        this.staffProfileRepository = staffProfileRepository;
    }

    /**
     * Mở trang Quản lý Chăm sóc khách hàng (/customer-care hoặc /cskh).
     */
    @GetMapping({"/customer-care", "/cskh"})
    public String customerCarePage(
            HttpSession session,
            HttpServletResponse response,
            Model model) throws IOException {

        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User sessionUser) || sessionUser.getUserId() == null) {
            return "redirect:/login";
        }

        // Kiểm tra vai trò truy cập (chỉ ADMIN, LE_TAN, BAC_SI được phép truy cập)
        String careRole;
        try {
            careRole = customerCareService.requireCareRole(sessionUser.getUserId());
        } catch (ResponseStatusException ex) {
            response.sendError(
                    ex.getStatusCode().value(),
                    ex.getReason() != null ? ex.getReason() : "Bạn không có quyền truy cập trang Quản lý Chăm sóc khách hàng."
            );
            return null;
        }

        // Đưa thông tin roleNames và displayName vào Model để Sidebar hiển thị đúng phân quyền
        User dbUser = userRepository.findById(sessionUser.getUserId()).orElse(sessionUser);
        List<String> roleNames = new ArrayList<>();
        if (dbUser.getRoles() != null) {
            for (Role r : dbUser.getRoles()) {
                if (r.getRoleName() != null) {
                    roleNames.add(r.getRoleName().toUpperCase());
                }
            }
        }
        if (!roleNames.contains(careRole)) {
            roleNames.add(careRole);
        }

        String displayName = dbUser.getUsername();
        Optional<StaffProfile> staffOpt = staffProfileRepository.findByUser_UserId(dbUser.getUserId());
        if (staffOpt.isPresent() && staffOpt.get().getFullName() != null && !staffOpt.get().getFullName().isBlank()) {
            displayName = staffOpt.get().getFullName();
        }

        model.addAttribute("roleNames", roleNames);
        model.addAttribute("displayName", displayName);
        model.addAttribute("careRole", careRole);
        return "customer-care/list";
    }
}
