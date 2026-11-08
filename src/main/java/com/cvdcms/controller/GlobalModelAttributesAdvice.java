package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.ServiceCategory;
import com.cvdcms.entity.User;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.BranchService;
import com.cvdcms.service.CustomerPortalService;
import com.cvdcms.service.ServiceCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cung cấp các thuộc tính chung cho các trang Thymeleaf có sử dụng sidebar hoặc
 * Customer Portal Header/Footer.
 * Đảm bảo isAdmin, roleNames, permissionCodes, displayName, publicBranches,
 * footerServices luôn có giá trị an toàn.
 */
@ControllerAdvice
public class GlobalModelAttributesAdvice {

    private static final Set<String> STAFF_ROLE_CODES = Set.of(
            "ADMIN", "ROLE_ADMIN",
            "BAC_SI", "ROLE_BAC_SI",
            "LE_TAN", "ROLE_LE_TAN",
            "QUAN_LY_KHO", "ROLE_QUAN_LY_KHO",
            "KE_TOAN", "ROLE_KE_TOAN");

    private final UserRepository userRepository;
    private final ObjectProvider<CustomerPortalService> customerPortalServiceProvider;
    private final ObjectProvider<ServiceCatalogService> serviceCatalogServiceProvider;
    private final ObjectProvider<PatientProfileRepository> patientProfileRepositoryProvider;

    public GlobalModelAttributesAdvice(UserRepository userRepository) {
        this(userRepository, null, null, null);
    }

    @Autowired
    public GlobalModelAttributesAdvice(
            UserRepository userRepository,
            ObjectProvider<CustomerPortalService> customerPortalServiceProvider,
            ObjectProvider<ServiceCatalogService> serviceCatalogServiceProvider,
            ObjectProvider<PatientProfileRepository> patientProfileRepositoryProvider) {
        this.userRepository = userRepository;
        this.customerPortalServiceProvider = customerPortalServiceProvider;
        this.serviceCatalogServiceProvider = serviceCatalogServiceProvider;
        this.patientProfileRepositoryProvider = patientProfileRepositoryProvider;

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

    @ModelAttribute("isStaffUser")
    public boolean populateIsStaffUser(HttpServletRequest request, HttpSession session) {
        List<String> roles = populateRoleNames(request, session);
        for (String r : roles) {
            if (r != null && STAFF_ROLE_CODES.contains(r.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    @ModelAttribute("isPatientUser")
    public boolean populateIsPatientUser(HttpServletRequest request, HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User)) {
            return false;
        }
        if (populateIsStaffUser(request, session)) {
            return false;
        }
        List<String> roles = populateRoleNames(request, session);
        for (String r : roles) {
            if (r != null) {
                String norm = r.trim().toUpperCase(Locale.ROOT);
                if ("BENH_NHAN".equals(norm) || "ROLE_BENH_NHAN".equals(norm)) {
                    return true;
                }
            }
        }
        return false;
    }

    @ModelAttribute("defaultPatientProfile")
    public PatientProfile populateDefaultPatientProfile(HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User user)
                || user.getUserId() == null) {
            return null;
        }
        PatientProfileRepository repo = patientProfileRepositoryProvider != null
                ? patientProfileRepositoryProvider.getIfAvailable()
                : null;
        if (repo == null) {
            return null;
        }
        return repo.findFirstByUser_UserIdAndIsDefaultTrueAndStatus(user.getUserId(), "ACTIVE")
                .or(() -> repo.findFirstByUser_UserIdAndIsDefaultTrue(user.getUserId()))
                .orElse(null);
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
        PatientProfile pp = populateDefaultPatientProfile(session);
        if (pp != null && pp.getFullName() != null && !pp.getFullName().isBlank()) {
            return pp.getFullName();
        }
        if (session.getAttribute("loggedInUser") instanceof User user) {
            return user.getUsername();
        }

        Object username = session.getAttribute("username");
        return username != null ? username.toString() : "";
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

    @ModelAttribute("publicBranches")
    public List<Branch> populatePublicBranches() {
        if (customerPortalServiceProvider == null) {
            return Collections.emptyList();
        }
        try {
            CustomerPortalService portalService = customerPortalServiceProvider.getIfAvailable();
            return portalService != null ? portalService.getActiveBranches() : Collections.emptyList();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    @ModelAttribute("currentPortalBranch")
    public Branch populateCurrentPortalBranch(HttpSession session) {
        if (session == null) {
            return null;
        }
        List<Branch> activeBranches = populatePublicBranches();
        // 1. Nếu người dùng đã đăng nhập: ưu tiên cơ sở ưu tiên của hồ sơ bệnh nhân
        // hoặc cơ sở của tài khoản
        if (session.getAttribute("loggedInUser") instanceof User sessionUser && sessionUser.getUserId() != null) {
            PatientProfile pp = populateDefaultPatientProfile(session);
            if (pp != null && pp.getPreferredBranch() != null
                    && Boolean.TRUE.equals(pp.getPreferredBranch().getIsActive())) {
                return pp.getPreferredBranch();
            }
            User user = userRepository != null ? userRepository.findById(sessionUser.getUserId()).orElse(sessionUser)
                    : sessionUser;
            if (user != null && user.getBranch() != null && Boolean.TRUE.equals(user.getBranch().getIsActive())) {
                return user.getBranch();
            }
        }
        // 2. Nếu có chọn cơ sở trong session (khi bấm "Chuyển sang cơ sở này")
        Object selectedBranchIdObj = session.getAttribute("selectedPortalBranchId");
        if (selectedBranchIdObj instanceof Long selectedId) {
            for (Branch b : activeBranches) {
                if (selectedId.equals(b.getBranchId())) {
                    return b;
                }
            }
        }
        return null;
    }

    @ModelAttribute("primaryBranch")
    public Branch populatePrimaryBranch(HttpSession session) {
        Branch current = populateCurrentPortalBranch(session);
        if (current != null) {
            return current;
        }
        if (customerPortalServiceProvider == null) {
            return null;
        }
        try {
            CustomerPortalService portalService = customerPortalServiceProvider.getIfAvailable();
            return portalService != null ? portalService.getPrimaryBranch() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @ModelAttribute("effectiveContactPhone")
    public String populateEffectiveContactPhone(HttpSession session) {
        if (session != null) {
            Branch current = populateCurrentPortalBranch(session);
            if (current == null && session.getAttribute("loggedInUser") != null) {
                current = populatePrimaryBranch(session);
            }
            if (current != null && current.getPhone() != null && !current.getPhone().isBlank()) {
                return current.getPhone().trim();
            }
        }
        return "0987654321";
    }

    @ModelAttribute("footerServices")
    public List<Service> populateFooterServices() {
        if (customerPortalServiceProvider == null) {
            return Collections.emptyList();
        }
        try {
            CustomerPortalService portalService = customerPortalServiceProvider.getIfAvailable();
            return portalService != null ? portalService.getFeaturedServices(5) : Collections.emptyList();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    @ModelAttribute("headerServiceCategories")
    public List<ServiceCategory> populateHeaderServiceCategories() {
        ServiceCatalogService catalogService = serviceCatalogServiceProvider != null
                ? serviceCatalogServiceProvider.getIfAvailable()
                : null;
        if (catalogService == null) {
            return Collections.emptyList();
        }
        try {
            return catalogService.getActiveCategories();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<String> toStringList(Collection<?> col) {
        return col.stream()
                .filter(item -> item != null && !item.toString().isBlank())
                .map(Object::toString)
                .toList();
    }
}
