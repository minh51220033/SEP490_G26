package com.cvdcms.controller;

import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Controller
public class FinanceRedirectController {

    private final UserRepository userRepository;

    public FinanceRedirectController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/finance")
    public String redirectFinance(HttpServletRequest request, HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User user)) {
            return "redirect:/login";
        }

        boolean isAdmin = false;
        Object sessIsAdmin = session.getAttribute("isAdmin");
        if (sessIsAdmin instanceof Boolean b) {
            isAdmin = b;
        } else if (user.getUserId() != null) {
            isAdmin = userRepository.hasActiveAdminRole(user.getUserId());
        }

        List<String> roleNames = extractList(session.getAttribute("roleNames"));
        if (roleNames.isEmpty() && user.getUserId() != null) {
            roleNames = userRepository.findActiveRoleNamesByUserId(user.getUserId());
        }

        List<String> perms = extractList(session.getAttribute("permissionCodes"));
        if (perms.isEmpty() && user.getUserId() != null) {
            perms = userRepository.findActivePermissionCodesByUserId(user.getUserId());
        }

        boolean isKeToan = roleNames.stream().anyMatch(r -> "KE_TOAN".equalsIgnoreCase(r) || "ROLE_KE_TOAN".equalsIgnoreCase(r));
        if (!isAdmin) {
            isAdmin = roleNames.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r) || "ROLE_ADMIN".equalsIgnoreCase(r));
        }

        // 1. Nhóm Khách hàng (Thu ngân):
        if (isAdmin || isKeToan || perms.contains("FINANCE_DASHBOARD")) {
            return "redirect:/payments/dashboard";
        }
        if (perms.contains("INVOICE_CREATE") || perms.contains("INVOICE_VIEW")) {
            return "redirect:/invoices";
        }
        if (perms.contains("PAYMENT_COLLECT")) {
            return "redirect:/payments";
        }
        if (perms.contains("FINANCE_DEBT")) {
            return "redirect:/payments/debts";
        }
        if (perms.contains("REFUND_MANAGE")) {
            return "redirect:/payments/refunds";
        }

        // 2. Nhóm Phòng khám (fallback):
        return "redirect:/expenses";
    }

    private List<String> extractList(Object obj) {
        if (obj instanceof Collection<?> col) {
            return col.stream().filter(o -> o != null).map(Object::toString).toList();
        }
        return Collections.emptyList();
    }
}
