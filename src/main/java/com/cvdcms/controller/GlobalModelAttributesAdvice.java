package com.cvdcms.controller;

import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Cung cấp các thuộc tính chung cho các trang Thymeleaf có sử dụng sidebar.
 * Đảm bảo isAdmin, roleNames, permissionCodes, displayName luôn có giá trị an toàn (không bao giờ null)
 * giúp Thymeleaf không bị lỗi EL1001E và sidebar hiển thị đúng phân quyền của người dùng đăng nhập.
 */
@ControllerAdvice
public class GlobalModelAttributesAdvice {

    private final UserRepository userRepository;

    public GlobalModelAttributesAdvice(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @ModelAttribute("roleNames")
    public List<String> populateRoleNames(HttpServletRequest request, HttpSession session) {
        // 1. Lấy từ request attribute (do FeaturePermissionInterceptor đồng bộ)
        Object reqRoles = request != null ? request.getAttribute("roleNames") : null;
        if (reqRoles instanceof Collection<?> col && !col.isEmpty()) {
            return toStringList(col);
        }

        // 2. Lấy từ session attribute
        if (session != null) {
            Object sessRoles = session.getAttribute("roleNames");
            if (sessRoles instanceof Collection<?> col && !col.isEmpty()) {
                return toStringList(col);
            }

            // 3. Fallback: truy vấn DB theo user đang đăng nhập
            Object userObj = session.getAttribute("loggedInUser");
            if (userObj instanceof User user && user.getUserId() != null) {
                List<String> roles = userRepository.findActiveRoleNamesByUserId(user.getUserId());
                session.setAttribute("roleNames", roles);
                return roles;
            }
        }
        return Collections.emptyList();
    }

    @ModelAttribute("permissionCodes")
    public List<String> populatePermissionCodes(HttpServletRequest request, HttpSession session) {
        // 1. Lấy từ request attribute (do FeaturePermissionInterceptor đồng bộ)
        Object reqCodes = request != null ? request.getAttribute("permissionCodes") : null;
        if (reqCodes instanceof Collection<?> col && !col.isEmpty()) {
            return toStringList(col);
        }

        // 2. Lấy từ session attribute
        if (session != null) {
            Object sessCodes = session.getAttribute("permissionCodes");
            if (sessCodes instanceof Collection<?> col && !col.isEmpty()) {
                return toStringList(col);
            }

            // 3. Fallback: truy vấn DB theo user đang đăng nhập
            Object userObj = session.getAttribute("loggedInUser");
            if (userObj instanceof User user && user.getUserId() != null) {
                List<String> codes = userRepository.findActivePermissionCodesByUserId(user.getUserId());
                session.setAttribute("permissionCodes", codes);
                return codes;
            }
        }
        return Collections.emptyList();
    }

    @ModelAttribute("isAdmin")
    public boolean populateIsAdmin(HttpServletRequest request, HttpSession session) {
        // 1. Lấy từ request attribute (do FeaturePermissionInterceptor đồng bộ)
        Object reqIsAdmin = request != null ? request.getAttribute("isAdmin") : null;
        if (reqIsAdmin instanceof Boolean b) {
            return b;
        }

        // 2. Lấy từ session attribute
        if (session != null) {
            Object sessIsAdmin = session.getAttribute("isAdmin");
            if (sessIsAdmin instanceof Boolean b) {
                return b;
            }

            // 3. Fallback: kiểm tra trực tiếp hasActiveAdminRole trên user đang đăng nhập
            Object userObj = session.getAttribute("loggedInUser");
            if (userObj instanceof User user && user.getUserId() != null) {
                boolean admin = userRepository.hasActiveAdminRole(user.getUserId());
                session.setAttribute("isAdmin", admin);
                return admin;
            }

            // 4. Fallback: kiểm tra danh sách roleNames trong session
            Object sessRoles = session.getAttribute("roleNames");
            if (sessRoles instanceof Collection<?> col) {
                return col.stream()
                        .filter(r -> r != null)
                        .map(Object::toString)
                        .anyMatch(role -> "ADMIN".equalsIgnoreCase(role)
                                || "ROLE_ADMIN".equalsIgnoreCase(role));
            }
        }
        return false;
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

    private List<String> toStringList(Collection<?> collection) {
        if (collection == null || collection.isEmpty()) {
            return Collections.emptyList();
        }
        return collection.stream()
                .filter(item -> item != null)
                .map(Object::toString)
                .toList();
    }
}
