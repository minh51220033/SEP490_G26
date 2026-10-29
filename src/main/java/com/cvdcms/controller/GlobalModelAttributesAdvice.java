package com.cvdcms.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collections;

/**
 * ControllerAdvice toàn cục đảm bảo roleNames và displayName luôn hiện diện trong Model
 * cho mọi request render giao diện, giúp Sidebar và Topbar luôn hiển thị đúng phân quyền.
 */
@ControllerAdvice
public class GlobalModelAttributesAdvice {

    @ModelAttribute("roleNames")
    public Object populateRoleNames(HttpSession session) {
        if (session != null) {
            Object roles = session.getAttribute("roleNames");
            if (roles != null) {
                return roles;
            }
        }
        return Collections.emptyList();
    }

    @ModelAttribute("displayName")
    public String populateDisplayName(HttpSession session) {
        if (session != null) {
            Object userObj = session.getAttribute("loggedInUser");
            if (userObj instanceof com.cvdcms.entity.User user) {
                return user.getUsername();
            }
            Object username = session.getAttribute("username");
            if (username != null) {
                return username.toString();
            }
        }
        return "";
    }
}

