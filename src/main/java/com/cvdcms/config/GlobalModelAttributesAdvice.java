package com.cvdcms.config;

import com.cvdcms.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collections;
import java.util.List;

/**
 * ControllerAdvice toàn cục đảm bảo roleNames, permissionCodes, isAdmin,
 * displayName, currentBranchName và systemNotice luôn hiện diện trong Model
 * cho mọi request render giao diện Thymeleaf.
 */
@ControllerAdvice
public class GlobalModelAttributesAdvice {

    @ModelAttribute("roleNames")
    public Object populateRoleNames(HttpSession session) {
        if (session != null) {
            Object roles = session.getAttribute("roleNames");
            if (roles instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            } else if (roles != null) {
                return roles;
            }
        }
        return Collections.emptyList();
    }

    @ModelAttribute("permissionCodes")
    public List<String> populatePermissionCodes(HttpSession session) {
        if (session != null) {
            Object perms = session.getAttribute("permissionCodes");
            if (perms instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
        }
        return Collections.emptyList();
    }

    @ModelAttribute("isAdmin")
    public boolean populateIsAdmin(HttpSession session) {
        if (session != null) {
            Object roles = session.getAttribute("roleNames");
            if (roles instanceof List<?> list) {
                return list.stream().anyMatch(r -> {
                    String s = r != null ? r.toString().trim().toUpperCase() : "";
                    return "ADMIN".equals(s) || "ROLE_ADMIN".equals(s);
                });
            }
        }
        return false;
    }

    @ModelAttribute("displayName")
    public String populateDisplayName(HttpSession session) {
        if (session != null) {
            Object displayName = session.getAttribute("displayName");
            if (displayName != null && !displayName.toString().isBlank()) {
                return displayName.toString();
            }
            Object userObj = session.getAttribute("loggedInUser");
            if (userObj instanceof User user) {
                return user.getUsername();
            }
            Object username = session.getAttribute("username");
            if (username != null) {
                return username.toString();
            }
        }
        return "";
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

