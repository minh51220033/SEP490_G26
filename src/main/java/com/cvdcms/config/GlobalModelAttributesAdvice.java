package com.cvdcms.config;

import com.cvdcms.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collections;
import java.util.List;

/**
 * Cung cấp các thuộc tính dùng chung (roleNames, displayName, currentBranchName, systemNotice)
 * cho mọi Thymeleaf Model, đảm bảo Sidebar, Topbar và Phân quyền không bao giờ
 * bị lỗi NullPointerException / SpEL exception khi Controller chưa truyền thủ công.
 */
@ControllerAdvice
public class GlobalModelAttributesAdvice {

    @ModelAttribute("roleNames")
    public List<String> populateRoleNames(HttpSession session) {
        if (session == null) {
            return Collections.emptyList();
        }
        Object roleNames = session.getAttribute("roleNames");
        if (roleNames instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return Collections.emptyList();
    }

    @ModelAttribute("displayName")
    public String populateDisplayName(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object displayName = session.getAttribute("displayName");
        if (displayName != null && !displayName.toString().isBlank()) {
            return displayName.toString();
        }
        if (session.getAttribute("loggedInUser") instanceof User user) {
            return user.getUsername();
        }
        return null;
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
}
