package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Page Controller phục vụ giao diện Web cho chức năng Quản lý kết quả phim X-quang NanoPix (FT12):
 * - URL: /xray hoặc /doctor/xray
 * - Phân quyền: ADMIN, BAC_SI, LE_TAN
 */
@Controller
@RequiredArgsConstructor
public class NanopixXrayPageController {

    private final UserRepository userRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    @GetMapping({"/xray", "/doctor/xray"})
    public String xrayPage(HttpSession session, HttpServletResponse response, Model model) throws IOException {
        User sessionUser = getSessionUser(session);
        if (sessionUser == null || sessionUser.getUserId() == null) {
            return "redirect:/login";
        }

        User dbUser = userRepository.findById(sessionUser.getUserId()).orElse(sessionUser);
        List<String> roleNames = new ArrayList<>();
        if (dbUser.getRoles() != null) {
            for (Role r : dbUser.getRoles()) {
                if (r.getRoleName() != null) {
                    roleNames.add(r.getRoleName().toUpperCase());
                }
            }
        }

        boolean hasAccess = roleNames.stream().anyMatch(r ->
                r.contains("ADMIN") || r.contains("BAC_SI") || r.contains("LE_TAN")
        );

        if (!hasAccess) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Bạn không có quyền truy cập vào chức năng Quản lý kết quả phim X-quang NanoPix (FT12).");
            return null;
        }

        String displayName = dbUser.getUsername();
        Optional<StaffProfile> staffOpt = staffProfileRepository.findByUser_UserId(dbUser.getUserId());
        if (staffOpt.isPresent() && staffOpt.get().getFullName() != null && !staffOpt.get().getFullName().isBlank()) {
            displayName = staffOpt.get().getFullName();
        }

        boolean isAdmin = roleNames.stream().anyMatch(r -> r.contains("ADMIN"));
        model.addAttribute("roleNames", roleNames);
        model.addAttribute("displayName", displayName);
        model.addAttribute("currentUser", dbUser);
        model.addAttribute("activeMenu", "xray");
        model.addAttribute("isAdmin", isAdmin);

        // Nạp danh sách cơ sở phân quyền
        if (isAdmin) {
            model.addAttribute("branches", branchRepository.findByIsActiveTrueOrderByBranchNameAsc());
            model.addAttribute("currentBranchId", null);
        } else {
            List<Long> branchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(dbUser.getUserId(), AssignmentStatus.ACTIVE);
            if (branchIds == null || branchIds.isEmpty()) {
                if (dbUser.getBranch() != null) {
                    branchIds = List.of(dbUser.getBranch().getBranchId());
                }
            }
            if (branchIds != null && !branchIds.isEmpty()) {
                List<Branch> userBranches = branchRepository.findAllById(branchIds);
                model.addAttribute("branches", userBranches);
                model.addAttribute("currentBranchId", branchIds.get(0));
            } else {
                model.addAttribute("branches", Collections.emptyList());
                model.addAttribute("currentBranchId", null);
            }
        }

        return "xray/list";
    }

    private User getSessionUser(HttpSession session) {
        if (session == null) return null;
        Object obj = session.getAttribute("loggedInUser");
        if (obj instanceof User u) return u;
        obj = session.getAttribute("user");
        if (obj instanceof User u) return u;
        return null;
    }
}
