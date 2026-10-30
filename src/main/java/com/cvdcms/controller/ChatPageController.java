package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.ChatService;
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
 * Controller điều hướng trang Hộp thư Tin nhắn Realtime theo Cơ sở (/chat, /chat/inbox).
 * - Cho phép: ADMIN (xem mọi cơ sở), LE_TAN & BAC_SI (tiếp nhận tin nhắn bệnh nhân + nội bộ thuộc cơ sở mình),
 *   QUAN_LY_KHO & KE_TOAN (chỉ nhắn tin nội bộ thuộc cơ sở mình, không nhận tin nhắn của bệnh nhân).
 * - Từ chối (403 FORBIDDEN): BENH_NHAN.
 */
@Controller
public class ChatPageController {

    private final ChatService chatService;
    private final UserRepository userRepository;
    private final StaffProfileRepository staffProfileRepository;

    public ChatPageController(
            ChatService chatService,
            UserRepository userRepository,
            StaffProfileRepository staffProfileRepository) {
        this.chatService = chatService;
        this.userRepository = userRepository;
        this.staffProfileRepository = staffProfileRepository;
    }

    @GetMapping({"/chat", "/chat/inbox"})
    public String chatInboxPage(
            HttpSession session,
            HttpServletResponse response,
            Model model) throws IOException {

        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User sessionUser) || sessionUser.getUserId() == null) {
            return "redirect:/login";
        }

        String chatRole;
        try {
            chatRole = chatService.requireInboxAccess(sessionUser.getUserId());
        } catch (ResponseStatusException ex) {
            response.sendError(
                    ex.getStatusCode().value(),
                    ex.getReason() != null ? ex.getReason() : "Bạn không có quyền truy cập trang Hộp thư tin nhắn."
            );
            return null;
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
        if (!roleNames.contains(chatRole)) {
            roleNames.add(chatRole);
        }

        String displayName = dbUser.getUsername();
        Optional<StaffProfile> staffOpt = staffProfileRepository.findByUser_UserId(dbUser.getUserId());
        if (staffOpt.isPresent() && staffOpt.get().getFullName() != null && !staffOpt.get().getFullName().isBlank()) {
            displayName = staffOpt.get().getFullName();
        }

        boolean isAdmin = "ADMIN".equals(chatRole);
        boolean canHandlePatients = isAdmin || "LE_TAN".equals(chatRole) || "BAC_SI".equals(chatRole);

        List<Branch> accessibleBranches = chatService.getAccessibleBranchesForInbox(dbUser.getUserId());
        Branch currentBranch = !accessibleBranches.isEmpty() ? accessibleBranches.get(0) : dbUser.getBranch();

        model.addAttribute("roleNames", roleNames);
        model.addAttribute("displayName", displayName);
        model.addAttribute("chatRole", chatRole);
        model.addAttribute("chatRoleLabel", chatService.toRoleLabel(chatRole));
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("canHandlePatients", canHandlePatients);
        model.addAttribute("accessibleBranches", accessibleBranches);
        model.addAttribute("currentBranch", currentBranch);
        model.addAttribute("currentBranchName", isAdmin
                ? "Quản trị tất cả cơ sở"
                : (currentBranch != null ? currentBranch.getBranchName() : "Cơ sở phụ trách"));
        model.addAttribute("currentUserId", dbUser.getUserId());
        model.addAttribute("currentUsername", dbUser.getUsername());
        return "chat/inbox";
    }
}
