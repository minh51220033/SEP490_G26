package com.cvdcms.controller;

import com.cvdcms.DTO.AccountCreateDTO;
import com.cvdcms.DTO.AccountUpdateDTO;
import com.cvdcms.DTO.RoleUpdateDTO;
import com.cvdcms.entity.Permission;
import com.cvdcms.entity.User;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffBranchAssignment;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.enums.UserType;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.RoleRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.AccountService;

import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/accounts")
public class AccountController {

        private final AccountService accountService;
        private final RoleRepository roleRepository;
        private final BranchRepository branchRepository;
        private final StaffProfileRepository staffProfileRepository;
        private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
        private final UserRepository userRepository;

        // Đưa thông tin người dùng từ session vào model để layout chung (sidebar,
        // topbar) hiển thị đồng bộ.
        private void addLayoutAttributes(HttpSession session, Model model) {
                if (session == null || model == null)
                        return;
                model.addAttribute("roleNames", session.getAttribute("roleNames"));
                model.addAttribute("permissionCodes", session.getAttribute("permissionCodes"));

                Object user = session.getAttribute("loggedInUser");
                if (user instanceof User u) {
                        model.addAttribute("displayName", u.getUsername());
                }
        }

        public AccountController(
                        AccountService accountService,
                        RoleRepository roleRepository,
                        BranchRepository branchRepository,
                        StaffProfileRepository staffProfileRepository,
                        StaffBranchAssignmentRepository staffBranchAssignmentRepository,
                        UserRepository userRepository) {
                this.accountService = accountService;
                this.roleRepository = roleRepository;
                this.branchRepository = branchRepository;
                this.staffProfileRepository = staffProfileRepository;
                this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
                this.userRepository = userRepository;
        }

        // =========================================================
        // 1. DANH SÁCH ACCOUNT
        // =========================================================

        @GetMapping
        public String listAccounts(

                        @RequestParam(defaultValue = "") String keyword,

                        @RequestParam(required = false) Integer roleId,

                        @RequestParam(required = false) Boolean active,

                        @RequestParam(required = false) Long branchId,

                        @RequestParam(required = false) UserType userType,

                        @RequestParam(defaultValue = "0") int page,

                        @RequestParam(defaultValue = "10") int size,

                        @RequestParam(defaultValue = "createdDate") String sort,

                        @RequestParam(defaultValue = "desc") String direction,

                        HttpSession session,

                        Model model) {

                User loggedInUser = (User) session.getAttribute("loggedInUser");

                // Chưa đăng nhập thì chuyển về trang đăng nhập.
                if (loggedInUser == null) {
                        return "redirect:/login";
                }

                // Chỉ Quản trị viên (ADMIN) được quản lý tài khoản hệ thống.
                requireAdmin(session);

                addLayoutAttributes(session, model);

                // Không cho page âm
                if (page < 0) {
                        page = 0;
                }

                // Giới hạn size
                if (size != 10 && size != 25 && size != 50 && size != 100) { size = 10; }

                // =====================================================
                // WHITELIST SORT
                // =====================================================

                String sortField;

                switch (sort) {
                        case "username":
                                sortField = "username";
                                break;

                        case "email":
                                sortField = "email";
                                break;

                        case "createdDate":
                                sortField = "createdDate";
                                break;

                        case "isActive":
                                sortField = "isActive";
                                break;

                        default:
                                sortField = "createdDate";
                }

                Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction)
                                ? Sort.Direction.ASC
                                : Sort.Direction.DESC;

                Pageable pageable = PageRequest.of(
                                page,
                                size,
                                Sort.by(sortDirection, sortField));

                // =====================================================
                // SEARCH
                // =====================================================

                Page<User> accountPage = accountService.searchAccounts(
                                keyword,
                                roleId,
                                active,
                                branchId,
                                userType,
                                pageable);

                // =====================================================
                // DATA CHO THYMELEAF
                // =====================================================

                int from = accountPage.isEmpty() ? 0 : (accountPage.getNumber() * size + 1);
                int to = accountPage.isEmpty() ? 0 : (int) Math.min((long) (accountPage.getNumber() + 1) * size, accountPage.getTotalElements());
                model.addAttribute("fromItem", from);
                model.addAttribute("toItem", to);
                model.addAttribute("pageSize", size);

                model.addAttribute(
                                "accounts",
                                accountPage.getContent());

                model.addAttribute(
                                "currentPage",
                                accountPage.getNumber());

                model.addAttribute(
                                "totalPages",
                                accountPage.getTotalPages());

                model.addAttribute(
                                "totalItems",
                                accountPage.getTotalElements());

                model.addAttribute(
                                "keyword",
                                keyword);

                model.addAttribute(
                                "selectedRole",
                                roleId);

                model.addAttribute(
                                "selectedActive",
                                active);

                model.addAttribute(
                                "sort",
                                sort);

                model.addAttribute(
                                "direction",
                                direction);

                model.addAttribute(
                                "size",
                                size);

                // Danh sách role
                model.addAttribute(
                                "roles",
                                roleRepository.findAll());

                // Bộ lọc chi nhánh / loại tài khoản
                model.addAttribute("branches", branchRepository.findAllByOrderByBranchNameAsc());
                model.addAttribute("selectedBranch", branchId);
                model.addAttribute("selectedUserType", userType == null ? null : userType.name());

                // Họ tên hiển thị theo từng tài khoản (nhân sự / bệnh nhân / admin)
                model.addAttribute("displayNames", buildDisplayNames(accountPage.getContent()));

                return "account/list";
        }

        // =========================================================
        // 2. FORM CREATE
        // =========================================================

        @GetMapping("/create")
        public String showCreateForm(HttpSession session, Model model) {

                requireAdmin(session);

                // Lấy user đang đăng nhập để kiểm tra quyền admin
                User currentUser = (User) session.getAttribute("loggedInUser");
                addLayoutAttributes(session, model);
                model.addAttribute("isAdmin", isAdmin(currentUser));

                model.addAttribute(
                                "accountCreateDTO",
                                new AccountCreateDTO());

                model.addAttribute(
                                "roles",
                                roleRepository.findAllWithPermissions().stream()
                                        .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                                        .toList());

                model.addAttribute(
                                "branches",
                                branchRepository.findByIsActiveTrueOrderByBranchNameAsc());

                return "account/create";
        }

        // =========================================================
        // 3. CREATE
        // =========================================================
        @PostMapping("/create")
        public String createAccount(
                        @ModelAttribute AccountCreateDTO dto,
                        HttpSession session,
                        Model model) {

                User currentUser = (User) session.getAttribute("loggedInUser");

                if (currentUser == null
                                || !Boolean.TRUE.equals(currentUser.getIsActive())) {
                        return "redirect:/login";
                }

                requireAdmin(session);

                try {
                        // Tìm role được chọn trong form.
                        Role selectedRole = roleRepository.findAll().stream()
                                        .filter(role -> dto.getRoleId() != null
                                                        && role.getRoleId() != null
                                                        && role.getRoleId().longValue() == dto.getRoleId().longValue())
                                        .findFirst()
                                        .orElseThrow(() -> new ResponseStatusException(
                                                        HttpStatus.BAD_REQUEST,
                                                        "Role không tồn tại."));

                        if (Boolean.FALSE.equals(selectedRole.getIsActive())) {
                                throw new IllegalArgumentException("Vai trò '" + (selectedRole.getDisplayName() != null ? selectedRole.getDisplayName() : selectedRole.getRoleName()) + "' đang tạm ngưng hoạt động, không thể gán cho tài khoản mới.");
                        }

                        // Chỉ Quản trị viên được tạo tài khoản.
                        requireAdmin(session);

                        accountService.createAccount(dto);

                        return "redirect:/accounts?success=created";

                } catch (ResponseStatusException e) {
                        throw e;
                } catch (RuntimeException e) {
                        addLayoutAttributes(session, model);
                        model.addAttribute("error", e.getMessage());
                        model.addAttribute("accountCreateDTO", dto);
                        model.addAttribute("roles", roleRepository.findAllWithPermissions());
                        model.addAttribute("isAdmin", isAdmin(currentUser));
                        model.addAttribute("branches", branchRepository.findByIsActiveTrueOrderByBranchNameAsc());

                        return "account/create";
                }
        }

        // =========================================================
        // 4. DETAIL
        // =========================================================

        @GetMapping("/{id}")
        public String detailAccount(

                        @PathVariable("id") Long id,

                        HttpSession session,

                        Model model) {

                requireAdmin(session);

                User user = accountService.getAccountById(id);

                addLayoutAttributes(session, model);

                model.addAttribute(
                                "account",
                                user);

                // Tài khoản đang xem có phải chính người đang đăng nhập hoặc tài khoản Quản trị
                // viên không.
                User currentUser = (User) session.getAttribute("loggedInUser");
                boolean isSelf = currentUser != null && id.equals(currentUser.getUserId());
                boolean isTargetAdmin = isAdmin(user);
                model.addAttribute("isSelf", isSelf);
                model.addAttribute("isTargetAdmin", isTargetAdmin);

                // Tài khoản Quản trị viên hoặc Bệnh nhân thì khóa không cho đổi vai trò; Nhân
                // sự nội bộ cho phép đổi vai trò.
                StaffProfile linkedStaff = staffProfileRepository.findByUser_UserId(id).orElse(null);
                boolean hasStaffProfile = linkedStaff != null;
                boolean hasPatientProfile = user.getPatientProfiles() != null && !user.getPatientProfiles().isEmpty();
                boolean isTargetPatient = hasPatientProfile
                                || user.getUserType() == UserType.PATIENT
                                || (user.getRoles() != null && user.getRoles().stream()
                                                .anyMatch(r -> r.getRoleName() != null && (r.getRoleName()
                                                                .equalsIgnoreCase("BENH_NHAN")
                                                                || r.getRoleName().equalsIgnoreCase("ROLE_BENH_NHAN")
                                                                || r.getRoleName().equalsIgnoreCase("PATIENT")
                                                                || r.getRoleName().equalsIgnoreCase("ROLE_PATIENT"))));
                boolean roleLocked = isSelf || isTargetAdmin || isTargetPatient;

                String roleLockedReason = null;
                if (isSelf || isTargetAdmin) {
                        roleLockedReason = "Tài khoản Quản trị viên hệ thống được cố định và không thể thay đổi vai trò.";
                } else if (isTargetPatient) {
                        roleLockedReason = "Tài khoản bệnh nhân được cố định, không thể chuyển đổi sang vai trò nhân sự.";
                }

                List<StaffBranchAssignment> staffAssignments = linkedStaff != null
                                ? staffBranchAssignmentRepository.findByStaffId(linkedStaff.getStaffId())
                                : List.of();

                // Tính danh sách quyền hạn thực tế đang áp dụng cho tài khoản (từ Role hoặc đã
                // tùy chỉnh riêng)
                List<Permission> rolePermissions = user.getRoles() == null
                                ? List.of()
                                : user.getRoles().stream()
                                                .filter(Objects::nonNull)
                                                .flatMap(r -> r.getPermissions() == null
                                                                ? java.util.stream.Stream.empty()
                                                                : r.getPermissions().stream())
                                                .filter(Objects::nonNull)
                                                .distinct()
                                                .sorted(Comparator
                                                                .comparing((Permission p) -> p.getModule() == null ? ""
                                                                                : p.getModule())
                                                                .thenComparing(p -> p.getPermissionCode() == null ? ""
                                                                                : p.getPermissionCode()))
                                                .collect(Collectors.toList());

                Set<Long> customPermIds = user.getCustomPermissions() == null
                                ? Set.of()
                                : user.getCustomPermissions().stream()
                                                .filter(Objects::nonNull)
                                                .map(Permission::getPermissionId)
                                                .filter(Objects::nonNull)
                                                .collect(Collectors.toSet());

                List<Permission> effectivePermissions = Boolean.TRUE.equals(user.getCustomPermissionsEnabled())
                                ? rolePermissions.stream()
                                                .filter(p -> p.getPermissionId() != null
                                                                && customPermIds.contains(p.getPermissionId()))
                                                .collect(Collectors.toList())
                                : rolePermissions;

                Set<Long> effectivePermissionIds = effectivePermissions.stream()
                                .map(Permission::getPermissionId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet());

                model.addAttribute("linkedStaff", linkedStaff);
                model.addAttribute("staffAssignments", staffAssignments);
                model.addAttribute("hasStaffProfile", hasStaffProfile);
                model.addAttribute("hasPatientProfile", hasPatientProfile);
                model.addAttribute("isTargetPatient", isTargetPatient);
                model.addAttribute("roleLocked", roleLocked);
                model.addAttribute("roleLockedReason", roleLockedReason);
                model.addAttribute("statusLocked", isSelf || isTargetAdmin);
                model.addAttribute("rolePermissions", rolePermissions);
                model.addAttribute("effectivePermissions", effectivePermissions);
                model.addAttribute("effectivePermissionIds", effectivePermissionIds);
                model.addAttribute("customPermissionsEnabled", Boolean.TRUE.equals(user.getCustomPermissionsEnabled()));

                model.addAttribute(
                                "roles",
                                roleRepository.findAllWithPermissions().stream()
                                        .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                                        .toList());

                // Chỉ ADMIN mới được thấy lựa chọn vai trò ADMIN
                model.addAttribute(
                                "isAdmin",
                                isAdmin((User) session.getAttribute("loggedInUser")));

                model.addAttribute(
                                "displayName",
                                buildDisplayNames(List.of(user)).get(user.getUserId()));

                return "account/detail";
        }

        // =========================================================
        // 5. FORM EDIT
        // =========================================================

        @GetMapping("/{id}/edit")
        public String showEditForm(
                        @PathVariable("id") Long id,
                        HttpSession session,
                        Model model) {
                requireAdmin(session);
                addLayoutAttributes(session, model);

                User user = accountService.getAccountById(id);

                AccountUpdateDTO dto = new AccountUpdateDTO();
                dto.setEmail(user.getEmail());
                dto.setPhoneNumber(user.getPhoneNumber());
                dto.setBranchId(user.getBranch() != null ? user.getBranch().getBranchId() : null);

                Role currentRole = (user.getRoles() != null && !user.getRoles().isEmpty())
                                ? user.getRoles().iterator().next()
                                : null;
                if (currentRole != null && currentRole.getRoleId() != null) {
                        dto.setRoleId(currentRole.getRoleId().intValue());
                }

                populateEditViewModel(user, dto, session, model);
                return "account/edit";
        }

        // =========================================================
        // 6. UPDATE
        // =========================================================

        @PostMapping("/{id}/edit")
        public String updateAccount(
                        @PathVariable("id") Long id,
                        @ModelAttribute AccountUpdateDTO dto,
                        HttpSession session,
                        Model model) {

                requireAdmin(session);
                User currentUser = (User) session.getAttribute("loggedInUser");

                // Quản trị viên không được tự hạ vai trò của chính mình.
                if (currentUser != null && id.equals(currentUser.getUserId()) && dto.getRoleId() != null) {
                        Role selectedRole = roleRepository.findById(dto.getRoleId()).orElse(null);
                        if (selectedRole != null) {
                                String rName = selectedRole.getRoleName();
                                boolean isTargetAdmin = rName != null && (rName.equalsIgnoreCase("ADMIN") || rName.equalsIgnoreCase("ROLE_ADMIN"));
                                if (!isTargetAdmin) {
                                        User user = accountService.getAccountById(id);
                                        addLayoutAttributes(session, model);
                                        model.addAttribute("error", "Bạn không thể tự hạ vai trò Quản trị viên của chính mình.");
                                        populateEditViewModel(user, dto, session, model);
                                        return "account/edit";
                                }
                        }
                }

                try {
                        accountService.updateAccount(id, dto);
                        return "redirect:/accounts/" + id + "?success=updated";
                } catch (RuntimeException e) {
                        User user = accountService.getAccountById(id);
                        addLayoutAttributes(session, model);
                        model.addAttribute("error", e.getMessage());
                        populateEditViewModel(user, dto, session, model);
                        return "account/edit";
                }
        }

        private void populateEditViewModel(User user, AccountUpdateDTO dto, HttpSession session, Model model) {
                User currentUser = (User) session.getAttribute("loggedInUser");
                boolean isSelf = currentUser != null && user.getUserId().equals(currentUser.getUserId());
                boolean isTargetAdmin = isAdmin(user);

                StaffProfile linkedStaff = staffProfileRepository.findByUser_UserId(user.getUserId()).orElse(null);
                boolean hasPatientProfile = user.getPatientProfiles() != null && !user.getPatientProfiles().isEmpty();
                boolean isTargetPatient = hasPatientProfile
                                || user.getUserType() == UserType.PATIENT
                                || (user.getRoles() != null && user.getRoles().stream().anyMatch(r ->
                                                r.getRoleName() != null && (r.getRoleName().equalsIgnoreCase("BENH_NHAN")
                                                                || r.getRoleName().equalsIgnoreCase("ROLE_BENH_NHAN")
                                                                || r.getRoleName().equalsIgnoreCase("PATIENT")
                                                                || r.getRoleName().equalsIgnoreCase("ROLE_PATIENT"))));
                boolean roleLocked = isSelf || isTargetAdmin || isTargetPatient;

                String roleLockedReason = null;
                if (isSelf || isTargetAdmin) {
                        roleLockedReason = "Tài khoản Quản trị viên hệ thống được cố định và không thể thay đổi vai trò.";
                } else if (isTargetPatient) {
                        roleLockedReason = "Tài khoản bệnh nhân được cố định, không thể chuyển đổi sang vai trò nhân sự.";
                }

                List<Permission> rolePermissions = user.getRoles() == null
                                ? List.of()
                                : user.getRoles().stream()
                                                .filter(Objects::nonNull)
                                                .flatMap(r -> r.getPermissions() == null ? java.util.stream.Stream.empty() : r.getPermissions().stream())
                                                .filter(Objects::nonNull)
                                                .distinct()
                                                .sorted(Comparator
                                                                .comparing((Permission p) -> p.getModule() == null ? "" : p.getModule())
                                                                .thenComparing(p -> p.getPermissionCode() == null ? "" : p.getPermissionCode()))
                                                .collect(Collectors.toList());

                Set<Long> customPermIds = user.getCustomPermissions() == null
                                ? Set.of()
                                : user.getCustomPermissions().stream()
                                                .filter(Objects::nonNull)
                                                .map(Permission::getPermissionId)
                                                .filter(Objects::nonNull)
                                                .collect(Collectors.toSet());

                List<Permission> effectivePermissions = Boolean.TRUE.equals(user.getCustomPermissionsEnabled())
                                ? rolePermissions.stream()
                                                .filter(p -> p.getPermissionId() != null && customPermIds.contains(p.getPermissionId()))
                                                .collect(Collectors.toList())
                                : rolePermissions;

                Set<Long> effectivePermissionIds = effectivePermissions.stream()
                                .map(Permission::getPermissionId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet());

                addBranchLockFlags(user, model);
                model.addAttribute("account", user);
                model.addAttribute("accountUpdateDTO", dto);
                model.addAttribute("branches", branchRepository.findByIsActiveTrueOrderByBranchNameAsc());
                model.addAttribute("roles", roleRepository.findAllWithPermissions());
                model.addAttribute("rolePermissions", rolePermissions);
                model.addAttribute("effectivePermissions", effectivePermissions);
                model.addAttribute("effectivePermissionIds", effectivePermissionIds);
                model.addAttribute("customPermissionsEnabled", Boolean.TRUE.equals(user.getCustomPermissionsEnabled()));
                model.addAttribute("roleLocked", roleLocked);
                model.addAttribute("roleLockedReason", roleLockedReason);
                model.addAttribute("isSelf", isSelf);
                model.addAttribute("isTargetAdmin", isTargetAdmin);
                model.addAttribute("isTargetPatient", isTargetPatient);
        }

        // =========================================================
        // 7. CHANGE ROLE
        // =========================================================

        @PostMapping("/{id}/role")
        public String changeRole(
                        @PathVariable Long id,
                        @ModelAttribute RoleUpdateDTO dto,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {

                User currentUser = (User) session.getAttribute("loggedInUser");

                if (currentUser == null
                                || !Boolean.TRUE.equals(currentUser.getIsActive())) {
                        return "redirect:/login";
                }

                requireAdmin(session);

                Role selectedRole = roleRepository.findAll().stream()
                                .filter(role -> dto.getRoleId() != null
                                                && role.getRoleId() != null
                                                && role.getRoleId().longValue() == dto.getRoleId().longValue())
                                .findFirst()
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "Role không tồn tại."));

                String roleName = selectedRole.getRoleName();

                boolean isAdminRole = roleName != null
                                && (roleName.equalsIgnoreCase("ADMIN")
                                                || roleName.equalsIgnoreCase("ROLE_ADMIN"));

                // Quản trị viên không được tự hạ vai trò của chính mình.
                if (id.equals(currentUser.getUserId()) && !isAdminRole) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        "Bạn không thể tự hạ vai trò Quản trị viên của chính mình.");
                        return "redirect:/accounts/" + id;
                }

                try {
                        accountService.changeRole(
                                        id,
                                        dto.getRoleId(),
                                        dto.getPermissionIds(),
                                        Boolean.TRUE.equals(dto.getCustomPermissionsSubmitted()));
                } catch (IllegalArgumentException e) {
                        redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
                        return "redirect:/accounts/" + id;
                }

                return "redirect:/accounts/" + id + "?success=role";
        }

        // =========================================================
        // 8. LOCK / UNLOCK
        // =========================================================

        @PostMapping("/{id}/toggle-status")
        public String toggleStatus(

                        @PathVariable("id") Long id,

                        HttpSession session,

                        RedirectAttributes redirectAttributes) {

                requireAdmin(session);

                // Quản trị viên không được tự khóa tài khoản của chính mình.
                User currentUser = (User) session.getAttribute("loggedInUser");
                if (currentUser != null && id.equals(currentUser.getUserId())) {
                        redirectAttributes.addFlashAttribute(
                                        "errorMessage",
                                        "Bạn không thể tự khóa tài khoản của chính mình.");
                        return "redirect:/accounts/" + id;
                }

                try {
                        accountService.toggleStatus(id);
                } catch (IllegalArgumentException e) {
                        redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
                        return "redirect:/accounts/" + id;
                }

                return "redirect:/accounts/"
                                + id
                                + "?success=status";
        }

        /**
         * Kiểm tra tài khoản hiện tại có quyền ADMIN hay không (truy vấn DB để tránh
         * LazyInitializationException).
         */
        private boolean isAdmin(User user) {
                if (user == null || user.getUserId() == null) {
                        return false;
                }
                return userRepository.hasActiveAdminRole(user.getUserId());
        }

        /**
         * Chỉ cho phép ADMIN thực hiện thao tác quản trị.
         */
        private void requireAdmin(HttpSession session) {
                User currentUser = (User) session.getAttribute("loggedInUser");

                if (!isAdmin(currentUser)) {
                        throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN,
                                        "Chỉ Quản trị viên mới được quản lý tài khoản hệ thống.");
                }
        }

        /**
         * Xác định họ tên hiển thị cho từng tài khoản:
         * - Có hồ sơ nhân sự -> họ tên nhân sự
         * - Có hồ sơ bệnh nhân -> họ tên bệnh nhân
         * - Vai trò ADMIN -> "Quản trị viên"
         * - Còn lại -> "—"
         */
        private Map<Long, String> buildDisplayNames(List<User> users) {
                Map<Long, String> result = new HashMap<>();

                for (User u : users) {
                        String name = staffProfileRepository
                                        .findByUser_UserId(u.getUserId())
                                        .map(StaffProfile::getFullName)
                                        .filter(n -> n != null && !n.isBlank())
                                        .orElse(null);

                        if (name == null && u.getPatientProfiles() != null
                                        && !u.getPatientProfiles().isEmpty()) {
                                name = u.getPatientProfiles().stream()
                                                .map(PatientProfile::getFullName)
                                                .filter(n -> n != null && !n.isBlank())
                                                .collect(Collectors.joining(", "));
                        }

                        if (name == null || name.isBlank()) {
                                name = isAdmin(u) ? "Quản trị viên" : "—";
                        }

                        result.put(u.getUserId(), name);
                }

                return result;
        }

        /**
         * Cờ khóa ô chọn chi nhánh ở form sửa:
         * - Tài khoản Quản trị viên: luôn toàn hệ thống, không gán chi nhánh.
         * - Tài khoản đã có hồ sơ nhân sự: chi nhánh do phân công chi nhánh (FT41) quản
         * lý.
         */
        private void addBranchLockFlags(User account, Model model) {
                boolean adminAccount = account.getRoles() != null
                                && account.getRoles().stream()
                                                .filter(Objects::nonNull)
                                                .map(Role::getRoleName)
                                                .filter(Objects::nonNull)
                                                .anyMatch(n -> n.equalsIgnoreCase("ADMIN")
                                                                || n.equalsIgnoreCase("ROLE_ADMIN"));

                boolean linkedStaff = staffProfileRepository.existsByUserUserId(account.getUserId());

                model.addAttribute("branchLocked", adminAccount || linkedStaff);
                model.addAttribute(
                                "branchLockedReason",
                                adminAccount
                                                ? "Quản trị viên là cấp toàn hệ thống nên không gán chi nhánh."
                                                : "Tài khoản đã có hồ sơ nhân sự - chi nhánh được quản lý ở phần Phân công chi nhánh (Quản lý nhân sự).");
        }

}
