package com.cvdcms.controller;

import com.cvdcms.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collections;
import java.util.List;

/**
 * Cung cấp các thuộc tính chung cho các trang Thymeleaf có sử dụng sidebar.
 * Các giá trị mặc định tránh lỗi SpEL khi một controller chưa truyền riêng
 * roleNames, permissionCodes hoặc isAdmin vào Model.
 */
@ControllerAdvice
public class GlobalModelAttributesAdvice {

    @ModelAttribute("roleNames")
    public List<String> populateRoleNames(HttpSession session) {
        return readStringList(session, "roleNames");
    }

    @ModelAttribute("permissionCodes")
    public List<String> populatePermissionCodes(HttpSession session) {
        return readStringList(session, "permissionCodes");
    }

    @ModelAttribute("isAdmin")
    public boolean populateIsAdmin(HttpSession session) {
        return readStringList(session, "roleNames").stream()
                .anyMatch(role -> "ADMIN".equalsIgnoreCase(role)
                        || "ROLE_ADMIN".equalsIgnoreCase(role));
    }

    @ModelAttribute("displayName")
    public String populateDisplayName(HttpSession session) {
        if (session == null) {
            return "";
        }

        Object displayName = session.getAttribute("displayName");
        if (displayName != null && !displayName.toString().isBlank()) {
            return displayName.toString();
        }
        if (session.getAttribute("loggedInUser") instanceof User user) {
            return user.getUsername();
        }

        Object username = session.getAttribute("username");
        return username != null ? username.toString() : "";
    }

    @ModelAttribute("currentBranchName")
    public String populateCurrentBranchName(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object branchName = session.getAttribute("currentBranchName");
        return branchName != null ? branchName.toString() : null;
    }

    @ModelAttribute("systemNotice")
    public String populateSystemNotice(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object flash = session.getAttribute("flashPermissionNotice");
        if (flash != null) {
            session.removeAttribute("flashPermissionNotice");
            return flash.toString();
        }
        return null;
    }

    private List<String> readStringList(HttpSession session, String attributeName) {
        if (session == null) {
            return Collections.emptyList();
        }

        Object values = session.getAttribute(attributeName);
        if (values instanceof List<?> list) {
            return list.stream()
                    .filter(value -> value != null)
                    .map(Object::toString)
                    .toList();
        }
        return Collections.emptyList();
    }
}
