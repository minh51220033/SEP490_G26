package com.cvdcms.controller;

import com.cvdcms.entity.Permission;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.repository.PermissionRepository;
import com.cvdcms.repository.RoleRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Quản lý Vai trò & Phân quyền tĩnh mở rộng (Extensible Static RBAC - Phong cách MISA AMIS).
 * - Tập quyền (Permissions) được định nghĩa tĩnh theo các chức năng trên thanh điều hướng (Sidebar).
 * - Giao diện phân cấp theo Phân hệ -> Màn hình chức năng Sidebar -> Dropdown Chọn quyền (Toàn quyền / Xem / Thao tác).
 * - Quản trị viên (ADMIN) có thể mở rộng thêm Vai trò (Roles) mới và cấu hình tập quyền cho từng vai trò.
 */
@Controller
@RequestMapping("/accounts/roles")
public class RoleManagementController {

    private static final Pattern ROLE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,49}$");

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;

    public RoleManagementController(
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            UserRepository userRepository
    ) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
    }

    public record PermissionActionItem(
            Long permissionId,
            String permissionCode,
            String shortBadge,
            String actionDetail,
            String dependsOnCode
    ) {}

    public record PermissionSubTab(
            String tabKey,
            String tabTitle,
            String tabDescription,
            String tabBadge,
            String iconClass,
            List<PermissionActionItem> actions
    ) {}

    public record FeaturePermissionGroup(
            String groupKey,
            String groupTitle,
            String groupDescription,
            String sidebarBadge,
            String iconClass,
            boolean hasSubTabs,
            List<PermissionSubTab> subTabs,
            List<PermissionActionItem> actions
    ) {}

    public record ModulePermissionSection(
            String moduleKey,
            String moduleTitle,
            String moduleIcon,
            List<FeaturePermissionGroup> groups,
            int totalPermissions
    ) {}

    private record SubTabSpec(
            String tabKey,
            String tabTitle,
            String tabDescription,
            String tabBadge,
            String iconClass,
            List<ActionSpec> actionSpecs
    ) {}

    private record GroupSpec(
            String groupKey,
            String groupTitle,
            String groupDescription,
            String sidebarBadge,
            String iconClass,
            List<SubTabSpec> subTabSpecs,
            List<ActionSpec> actionSpecs
    ) {
        public GroupSpec(String groupKey, String groupTitle, String groupDescription, String sidebarBadge, String iconClass, List<ActionSpec> actionSpecs) {
            this(groupKey, groupTitle, groupDescription, sidebarBadge, iconClass, Collections.emptyList(), actionSpecs);
        }

        public GroupSpec(String groupKey, String groupTitle, String groupDescription, String sidebarBadge, String iconClass, List<SubTabSpec> subTabSpecs, boolean hasTabs) {
            this(groupKey, groupTitle, groupDescription, sidebarBadge, iconClass, subTabSpecs, Collections.emptyList());
        }
    }

    private record ActionSpec(
            String code,
            String shortBadge,
            String actionDetail,
            String dependsOnCode
    ) {}

    private void requireAdmin(HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Vui lòng đăng nhập quyền Quản trị viên.");
        }
        if (!userRepository.hasActiveAdminRole(user.getUserId())
                && !userRepository.hasActivePermission(user.getUserId(), "ROLE_MANAGE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền cấu hình vai trò & phân quyền.");
        }
    }

    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("activeMenu", "roles");
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("permissionCodes", session.getAttribute("permissionCodes"));
            if (session.getAttribute("loggedInUser") instanceof User u) {
                model.addAttribute("displayName", u.getUsername());
            }
        }
    }

    @GetMapping
    @Transactional
    public String listRoles(
            @RequestParam(required = false) Long editRoleId,
            HttpSession session,
            Model model
    ) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User)) {
            return "redirect:/login";
        }
        requireAdmin(session);
        addLayoutAttributes(session, model);

        ensureStandardPermissionsExist();

        List<Role> roles = roleRepository.findAllWithPermissions();
        List<Permission> allPermissions = permissionRepository.findAllByOrderByModuleAscPermissionCodeAsc();

        Map<String, String> moduleLabels = getOrderedModuleLabels();
        Map<String, List<Permission>> permissionsByModule = new LinkedHashMap<>();
        for (String modKey : moduleLabels.keySet()) {
            permissionsByModule.put(modKey, new ArrayList<>());
        }
        for (Permission p : allPermissions) {
            String mod = p.getModule() != null ? p.getModule().trim().toUpperCase() : "OTHER";
            permissionsByModule.computeIfAbsent(mod, k -> new ArrayList<>()).add(p);
        }
        permissionsByModule.entrySet().removeIf(e -> e.getValue().isEmpty());

        // Xây dựng cấu trúc phân quyền MISA (Phân hệ -> Chức năng Sidebar -> Dropdown hành động)
        List<FeaturePermissionGroup> sidebarGroups = buildSidebarGroups(allPermissions);
        List<ModulePermissionSection> misaModules = buildMisaModuleSections(allPermissions);

        // Đếm số lượng tài khoản đang gắn với từng vai trò
        Map<Long, Integer> userCountByRoleId = new HashMap<>();
        for (Role r : roles) {
            if (r.getRoleId() != null) {
                List<User> usersWithRole = userRepository.findByRole_RoleId(r.getRoleId().intValue());
                userCountByRoleId.put(r.getRoleId(), usersWithRole.size());
            }
        }

        // Tự động đồng bộ: Tài khoản thuộc vai trò đang tạm ngưng thì chuyển sang Đã khóa (isActive = false)
        for (Role r : roles) {
            if (r.getRoleId() != null && Boolean.FALSE.equals(r.getIsActive()) && !isAdminRole(r)) {
                List<User> usersWithRole = userRepository.findByRole_RoleId(r.getRoleId().intValue());
                for (User u : usersWithRole) {
                    if (Boolean.TRUE.equals(u.getIsActive())) {
                        u.setIsActive(false);
                        userRepository.save(u);
                    }
                }
            }
        }

        // Xác định vai trò đang được chọn để xem/cấu hình quyền
        Role selectedRole = null;
        if (editRoleId != null) {
            selectedRole = roles.stream()
                    .filter(r -> r.getRoleId() != null && r.getRoleId().equals(editRoleId))
                    .findFirst()
                    .orElse(null);
        }
        if (selectedRole == null) {
            selectedRole = roles.stream()
                    .filter(r -> !isAdminRole(r) && !isPatientRole(r))
                    .findFirst()
                    .orElse(roles.isEmpty() ? null : roles.get(0));
        }

        Set<Long> selectedPermissionIds = selectedRole != null && selectedRole.getPermissions() != null
                ? selectedRole.getPermissions().stream()
                        .map(Permission::getPermissionId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet())
                : Set.of();

        boolean isAdminRole = isAdminRole(selectedRole);
        boolean selectedRoleEditable = selectedRole != null && !isAdminRole && !isPatientRole(selectedRole);

        if (isAdminRole && selectedRole != null) {
            selectedPermissionIds = allPermissions.stream()
                    .map(Permission::getPermissionId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        model.addAttribute("roles", roles);
        model.addAttribute("permissionsByModule", permissionsByModule);
        model.addAttribute("misaModules", misaModules);
        model.addAttribute("sidebarGroups", sidebarGroups);
        model.addAttribute("moduleLabels", moduleLabels);
        model.addAttribute("userCountByRoleId", userCountByRoleId);
        model.addAttribute("selectedRole", selectedRole);
        model.addAttribute("selectedPermissionIds", selectedPermissionIds);
        model.addAttribute("selectedRoleEditable", selectedRoleEditable);
        model.addAttribute("isAdminRole", isAdminRole);
        model.addAttribute("totalPermissionsCount", allPermissions.size());

        return "account/roles";
    }

    @PostMapping("/create")
    @Transactional
    public String createRole(
            @RequestParam(required = false) String roleName,
            @RequestParam String displayName,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) List<Long> permissionIds,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        requireAdmin(session);

        try {
            String cleanDisplayName = displayName == null ? "" : displayName.trim();
            if (cleanDisplayName.isEmpty()) {
                throw new IllegalArgumentException("Tên hiển thị vai trò không được để trống.");
            }

            // Tự động sinh mã kỹ thuật từ tên hiển thị nếu người dùng để trống
            String candidateCode = (roleName != null && !roleName.trim().isEmpty()) ? roleName : cleanDisplayName;
            String normalizedCode = normalizeRoleCode(candidateCode);
            if (!ROLE_CODE_PATTERN.matcher(normalizedCode).matches()) {
                throw new IllegalArgumentException(
                        "Mã vai trò không hợp lệ. Vui lòng nhập chữ cái in hoa, chữ số hoặc dấu gạch dưới (ví dụ: DIEU_DUONG, THU_NGAN, CSKH)."
                );
            }

            if (isReservedRoleCode(normalizedCode)) {
                throw new IllegalArgumentException("Mã vai trò này thuộc nhóm đặc quyền hệ thống, không thể tạo trùng.");
            }

            if (roleRepository.existsByRoleNameIgnoreCase(normalizedCode)) {
                throw new IllegalArgumentException("Mã vai trò '" + normalizedCode + "' đã tồn tại trong hệ thống.");
            }

            Role newRole = new Role();
            newRole.setRoleName(normalizedCode);
            newRole.setDisplayName(cleanDisplayName);
            newRole.setDescription(description != null && !description.trim().isEmpty() ? description.trim() : null);
            newRole.setIsSystem(false);
            newRole.setIsActive(true);

            if (permissionIds != null && !permissionIds.isEmpty()) {
                List<Permission> perms = findPermissionsWithImpliedView(permissionIds);
                newRole.getPermissions().addAll(perms);
            }

            Role saved = roleRepository.save(newRole);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã mở rộng thêm vai trò mới: " + saved.getDisplayName() + " (" + saved.getRoleName() + ") với "
                            + saved.getPermissions().size() + " quyền nghiệp vụ."
            );
            return "redirect:/accounts/roles?editRoleId=" + saved.getRoleId();

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/accounts/roles";
        }
    }

    @PostMapping("/{roleId}/update")
    @Transactional
    public String updateRolePermissions(
            @PathVariable Long roleId,
            @RequestParam String displayName,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) List<Long> permissionIds,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        requireAdmin(session);

        try {
            Role role = roleRepository.findByIdWithPermissions(roleId)
                    .orElseThrow(() -> new IllegalArgumentException("Vai trò không tồn tại."));

            if (isAdminRole(role)) {
                throw new IllegalArgumentException("Vai trò Quản trị viên hệ thống có toàn quyền cố định, không thể thu hồi quyền.");
            }

            if (isPatientRole(role)) {
                throw new IllegalArgumentException("Vai trò Bệnh nhân chỉ dành cho Cổng thông tin bệnh nhân (Patient Portal), không gán quyền nội bộ phòng khám.");
            }

            String cleanDisplayName = displayName == null ? "" : displayName.trim();
            if (cleanDisplayName.isEmpty()) {
                throw new IllegalArgumentException("Tên hiển thị vai trò không được để trống.");
            }

            role.setDisplayName(cleanDisplayName);
            role.setDescription(description != null && !description.trim().isEmpty() ? description.trim() : null);

            Set<Permission> oldPerms = new HashSet<>(role.getPermissions());
            Set<Permission> newPerms = new HashSet<>();
            if (permissionIds != null && !permissionIds.isEmpty()) {
                newPerms.addAll(findPermissionsWithImpliedView(permissionIds));
            }

            Set<Permission> addedPerms = new HashSet<>(newPerms);
            addedPerms.removeAll(oldPerms);

            role.getPermissions().clear();
            role.getPermissions().addAll(newPerms);

            roleRepository.save(role);

            // Đồng bộ sang các tài khoản thuộc vai trò này đang bật tùy chỉnh quyền riêng:
            // - Gỡ bỏ các quyền đã bị thu hồi khỏi Vai trò
            // - Tự động bổ sung các quyền mới được Admin cấp thêm cho Vai trò
            List<User> roleUsers = userRepository.findAll();
            for (User u : roleUsers) {
                boolean hasThisRole = u.getRoles() != null && u.getRoles().stream()
                        .anyMatch(r -> r != null && Objects.equals(r.getRoleId(), roleId));
                if (hasThisRole && Boolean.TRUE.equals(u.getCustomPermissionsEnabled())) {
                    u.getCustomPermissions().retainAll(newPerms);
                    u.getCustomPermissions().addAll(addedPerms);
                    if (u.getCustomPermissions().size() == newPerms.size()) {
                        u.setCustomPermissionsEnabled(false);
                        u.getCustomPermissions().clear();
                    }
                    userRepository.save(u);
                }
            }

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã cập nhật cấu hình tập quyền cho vai trò '" + role.getDisplayName() + "' ("
                            + role.getPermissions().size() + " quyền)."
            );
            return "redirect:/accounts/roles?editRoleId=" + roleId;

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/accounts/roles?editRoleId=" + roleId;
        }
    }

    @PostMapping("/{roleId}/toggle-status")
    @Transactional
    public String toggleRoleStatus(
            @PathVariable Long roleId,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        requireAdmin(session);

        try {
            Role role = roleRepository.findById(roleId.intValue())
                    .orElseThrow(() -> new IllegalArgumentException("Vai trò không tồn tại."));

            if (Boolean.TRUE.equals(role.getIsSystem()) || isAdminRole(role) || isPatientRole(role)) {
                throw new IllegalArgumentException(
                        "Vai trò cốt lõi của hệ thống (" + role.getRoleName() + ") được bảo vệ, không thể vô hiệu hóa."
                );
            }

            boolean currentlyActive = Boolean.TRUE.equals(role.getIsActive());
            boolean nextActiveState = !currentlyActive;
            role.setIsActive(nextActiveState);
            roleRepository.save(role);

            // Đồng bộ trạng thái Đã khóa / Hoạt động cho toàn bộ tài khoản thuộc vai trò này:
            List<User> assignedUsers = userRepository.findByRole_RoleId(roleId.intValue());
            for (User u : assignedUsers) {
                u.setIsActive(nextActiveState);
                userRepository.save(u);
            }
            int userCount = assignedUsers.size();

            String roleDisplay = role.getDisplayName() != null ? role.getDisplayName() : role.getRoleName();
            String statusText = nextActiveState ? "Kích hoạt lại" : "Ngừng hoạt động";
            String extraMsg = "";
            if (nextActiveState) {
                if (userCount > 0) {
                    extraMsg = " Toàn bộ " + userCount + " tài khoản thuộc vai trò này đã được Mở khóa (Hoạt động).";
                }
            } else {
                if (userCount > 0) {
                    extraMsg = " Toàn bộ " + userCount + " tài khoản thuộc vai trò này đã được chuyển sang trạng thái Đã khóa.";
                }
            }

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã chuyển trạng thái vai trò '" + roleDisplay + "' sang: " + statusText + "." + extraMsg
            );
            return "redirect:/accounts/roles?editRoleId=" + roleId;

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/accounts/roles?editRoleId=" + roleId;
        }
    }
    @PostMapping("/{roleId}/delete")
    @Transactional
    public String deleteRole(
            @PathVariable Long roleId,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        requireAdmin(session);

        try {
            Role role = roleRepository.findById(roleId.intValue())
                    .orElseThrow(() -> new IllegalArgumentException("Vai trò không tồn tại."));

            if (Boolean.TRUE.equals(role.getIsSystem()) || isAdminRole(role) || isPatientRole(role)) {
                throw new IllegalArgumentException(
                        "Vai trò cốt lõi của hệ thống (" + role.getRoleName() + ") được bảo vệ, không thể xóa."
                );
            }

            List<User> assignedUsers = userRepository.findByRole_RoleId(roleId.intValue());
            if (!assignedUsers.isEmpty()) {
                throw new IllegalArgumentException(
                        "Không thể xóa vì đang có " + assignedUsers.size() + " tài khoản đảm nhiệm vai trò này. Vui lòng chuyển đổi vai trò cho các tài khoản trước."
                );
            }

            String roleDisplay = role.getDisplayName() != null ? role.getDisplayName() : role.getRoleName();

            // Xóa liên kết quyền trong bảng trung gian role_permissions
            role.getPermissions().clear();
            roleRepository.save(role);

            // Xóa vai trò
            roleRepository.delete(role);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã xóa vĩnh viễn vai trò '" + roleDisplay + "' khỏi hệ thống thành công."
            );
            return "redirect:/accounts/roles";

        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không thể xóa vai trò này vì đã phát sinh dữ liệu liên kết trong hệ thống (lịch trực, phân công...). Bạn có thể chuyển sang 'Tạm ngưng' thay vì xóa."
            );
            return "redirect:/accounts/roles?editRoleId=" + roleId;
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/accounts/roles?editRoleId=" + roleId;
        }
    }


    /**
     * Đảm bảo nguyên tắc "Quản lý/Thao tác bao hàm quyền Xem" (Write implies Read) ở tầng Backend:
     * Nếu người dùng tích chọn quyền Tạo/Quản lý thì tự động đảm bảo quyền Xem tương ứng cũng được bật.
     */
    private List<Permission> findPermissionsWithImpliedView(List<Long> permissionIds) {
        return new ArrayList<>(permissionRepository.findAllById(permissionIds));
    }

    private void ensureStandardPermissionsExist() {
        List<com.cvdcms.config.PermissionDataInitializer.PermissionSeed> seeds =
                com.cvdcms.config.PermissionDataInitializer.getStandardPermissionSeeds();
        boolean addedAny = false;
        Map<String, Permission> allMap = new LinkedHashMap<>();
        for (com.cvdcms.config.PermissionDataInitializer.PermissionSeed seed : seeds) {
            Optional<Permission> existing = permissionRepository.findByPermissionCodeIgnoreCase(seed.code());
            Permission perm;
            if (existing.isPresent()) {
                perm = existing.get();
            } else {
                perm = new Permission();
                perm.setPermissionCode(seed.code());
                perm.setModule(seed.module());
                perm.setDescription(seed.description());
                perm = permissionRepository.save(perm);
                addedAny = true;
            }
            allMap.put(seed.code().toUpperCase(), perm);
        }
        if (addedAny) {
            List<Role> allRoles = roleRepository.findAllWithPermissions();
            for (Role r : allRoles) {
                if (isAdminRole(r)) {
                    r.getPermissions().clear();
                    r.getPermissions().addAll(allMap.values());
                    roleRepository.save(r);
                }
            }
        }
    }

    /**
     * Xây dựng danh sách các Menu Sidebar nghiệp vụ (17 mục khớp 100% Sidebar bên trái và đầy đủ toàn bộ Tab ngang con).
     */
    private List<FeaturePermissionGroup> buildSidebarGroups(List<Permission> allPermissions) {
        Map<String, Permission> byCode = new HashMap<>();
        for (Permission p : allPermissions) {
            if (p.getPermissionCode() != null) {
                byCode.put(p.getPermissionCode().trim().toUpperCase(), p);
            }
        }

        List<GroupSpec> groupSpecs = List.of(
                // 1. Quản lý tài khoản (Có 3 Tab ngang)
                new GroupSpec(
                        "GRP_MENU_ACCOUNTS",
                        "Quản lý tài khoản",
                        "Quản lý tài khoản nhân viên/người dùng, vai trò & phân quyền ma trận, quản lý tài khoản bị cấm bình luận.",
                        "Quản lý tài khoản",
                        "bi-people",
                        List.of(
                                new SubTabSpec(
                                        "TAB_ACC_LIST",
                                        "Danh sách tài khoản",
                                        "Xem danh sách tài khoản người dùng, tạo tài khoản mới, cấp mật khẩu và phân quyền cơ sở.",
                                        "Tab ngang",
                                        "bi-people",
                                        List.of(new ActionSpec("ACCOUNT_MANAGE", "Toàn quyền", "Tạo & quản lý tài khoản", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_ACC_ROLES",
                                        "Vai trò & Phân quyền",
                                        "Cấu hình vai trò hệ thống, ma trận phân quyền chi tiết theo từng chức năng.",
                                        "Tab ngang",
                                        "bi-shield-check",
                                        List.of(new ActionSpec("ROLE_MANAGE", "Toàn quyền", "Cấu hình vai trò & phân quyền", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_ACC_BANS",
                                        "Tài khoản bị cấm bình luận",
                                        "Quản lý danh sách tài khoản bị khóa tính năng bình luận tin tức y khoa.",
                                        "Tab ngang",
                                        "bi-slash-circle",
                                        List.of(new ActionSpec("SYSTEM_BANS", "Toàn quyền", "Quản lý tài khoản bị ban", ""))
                                )
                        ),
                        true
                ),

                // 2. Quản lý nhân sự (Có 4 Tab ngang: Hồ sơ nhân sự, Phân ca làm việc, Chấm công chi nhánh, Duyệt nghỉ phép)
                new GroupSpec(
                        "GRP_MENU_HR",
                        "Quản lý nhân sự",
                        "Quản lý toàn bộ nhân sự phòng khám: Hồ sơ nhân sự, Phân ca làm việc, Chấm công chi nhánh và Duyệt nghỉ phép.",
                        "Quản lý nhân sự",
                        "bi-person-badge",
                        List.of(
                                new SubTabSpec(
                                        "TAB_HR_STAFFS",
                                        "Hồ sơ nhân sự",
                                        "Quản lý danh sách nhân viên, thông tin chuyên môn, chứng chỉ hành nghề và hợp đồng.",
                                        "Tab ngang",
                                        "bi-person-lines-fill",
                                        List.of(new ActionSpec("STAFF_MANAGE", "Toàn quyền", "Thêm, sửa hồ sơ & hợp đồng nhân viên", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_HR_SCHEDULES",
                                        "Phân ca làm việc",
                                        "Lên lịch và phân bổ ca làm việc, ca trực lâm sàng cho Bác sĩ, Phụ tá và Lễ tân theo tuần/tháng.",
                                        "Tab ngang",
                                        "bi-calendar-range",
                                        List.of(new ActionSpec("SCHEDULE_MANAGE", "Toàn quyền", "Xếp lịch làm việc & phân ca chi nhánh", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_HR_ATTENDANCE",
                                        "Chấm công chi nhánh",
                                        "Theo dõi, đối soát bảng chấm công theo GPS/WiFi chi nhánh và xuất báo cáo công.",
                                        "Tab ngang",
                                        "bi-fingerprint",
                                        List.of(new ActionSpec("HR_BRANCH_ATTENDANCE", "Toàn quyền", "Theo dõi bảng chấm công toàn chi nhánh", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_HR_LEAVES",
                                        "Duyệt nghỉ phép",
                                        "Tiếp nhận, xét duyệt hoặc từ chối đơn xin nghỉ phép và khóa lịch khám của nhân sự.",
                                        "Tab ngang",
                                        "bi-calendar-check",
                                        List.of(new ActionSpec("HR_LEAVE_APPROVE", "Toàn quyền", "Phê duyệt đơn xin nghỉ phép nhân sự", ""))
                                )
                        ),
                        true
                ),

                // 3. Quản lý khách hàng (Đơn lẻ)
                new GroupSpec(
                        "GRP_MENU_PATIENT_RECORDS",
                        "Quản lý khách hàng",
                        "Tiếp nhận bệnh nhân mới, tra cứu và cập nhật hồ sơ hành chính & lịch sử điều trị.",
                        "Quản lý khách hàng",
                        "bi-person-vcard",
                        List.of(new ActionSpec("PATIENT_PROFILE_MANAGE", "Toàn quyền", "Tiếp nhận bệnh nhân & quản lý hồ sơ hành chính", ""))
                ),

                // 4. Quản lý danh mục (Có 4 Tab ngang: Danh mục dịch vụ, Nhóm dịch vụ, Danh mục thuốc, Danh mục ICD-10)
                new GroupSpec(
                        "GRP_MENU_CATALOGS",
                        "Quản lý danh mục",
                        "Quản lý danh mục kỹ thuật y tế: Danh mục dịch vụ, Nhóm dịch vụ, Danh mục thuốc và Danh mục ICD-10.",
                        "Quản lý danh mục",
                        "bi-collection",
                        List.of(
                                new SubTabSpec(
                                        "TAB_CAT_SERVICES",
                                        "Danh mục dịch vụ",
                                        "Quản lý đơn giá dịch vụ khám nha khoa, thủ thuật điều trị và cấu hình kỹ thuật.",
                                        "Tab ngang",
                                        "bi-tags",
                                        List.of(new ActionSpec("CATALOG_SERVICES", "Toàn quyền", "Quản lý dịch vụ & bảng giá", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_CAT_CATEGORIES",
                                        "Nhóm dịch vụ",
                                        "Quản lý phân nhóm chuyên khoa dịch vụ nha khoa (Nha chu, Phục hình, Chỉnh nha, Implant...).",
                                        "Tab ngang",
                                        "bi-grid-3x3-gap",
                                        List.of(new ActionSpec("CATALOG_SERVICE_CATEGORIES", "Toàn quyền", "Quản lý nhóm dịch vụ nha khoa", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_CAT_MEDS",
                                        "Danh mục thuốc",
                                        "Quản lý danh mục thuốc tân dược, biệt dược, hướng dẫn sử dụng và đơn giá.",
                                        "Tab ngang",
                                        "bi-capsule",
                                        List.of(new ActionSpec("CATALOG_MEDICATIONS", "Toàn quyền", "Quản lý danh mục thuốc", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_CAT_ICD10",
                                        "Danh mục ICD-10",
                                        "Tra cứu và cấu hình danh mục mã chẩn đoán bệnh theo chuẩn quốc tế ICD-10.",
                                        "Tab ngang",
                                        "bi-clipboard2-pulse",
                                        List.of(new ActionSpec("CATALOG_ICD10", "Toàn quyền", "Quản lý mã bệnh ICD-10", ""))
                                )
                        ),
                        true
                ),

                // 5. Quản lý tin tức (Có 2 Tab ngang: Bài viết tin tức, Kiểm duyệt bình luận)
                new GroupSpec(
                        "GRP_MENU_NEWS",
                        "Quản lý tin tức",
                        "Quản lý truyền thông y khoa: Đăng tải bài viết tin tức và kiểm duyệt bình luận độc giả.",
                        "Quản lý tin tức",
                        "bi-file-earmark-richtext",
                        List.of(
                                new SubTabSpec(
                                        "TAB_NEWS_POSTS",
                                        "Bài viết tin tức",
                                        "Soạn thảo, xuất bản và quản lý các bài viết kiến thức nha khoa, thông báo phòng khám.",
                                        "Tab ngang",
                                        "bi-file-earmark-richtext",
                                        List.of(new ActionSpec("CATALOG_NEWS", "Toàn quyền", "Đăng & quản lý bài viết tin tức", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_NEWS_COMMENTS",
                                        "Kiểm duyệt bình luận",
                                        "Theo dõi, ẩn/xóa bình luận vi phạm tiêu chuẩn cộng đồng trên các bài viết tin tức.",
                                        "Tab ngang",
                                        "bi-chat-square-dots",
                                        List.of(new ActionSpec("NEWS_COMMENTS_MODERATE", "Toàn quyền", "Kiểm duyệt bình luận tin tức", ""))
                                )
                        ),
                        true
                ),

                // 6. Quản lý cơ sở (Đơn lẻ)
                new GroupSpec(
                        "GRP_MENU_BRANCH",
                        "Quản lý cơ sở",
                        "Quản lý danh sách chi nhánh cơ sở phòng khám, phòng điều trị, ghế nha và dải IP chấm công.",
                        "Quản lý cơ sở",
                        "bi-buildings",
                        List.of(new ActionSpec("BRANCH_MANAGE", "Toàn quyền", "Quản lý cơ sở & ghế nha khoa", ""))
                ),

                // 7. Quản lý thẻ & ưu đãi (Có 3 Tab ngang: Thẻ bệnh nhân, Hạng thẻ & Tích điểm, Khuyến mãi & Voucher)
                new GroupSpec(
                        "GRP_MENU_MEMBERSHIPS",
                        "Quản lý thẻ & ưu đãi",
                        "Chính sách khách hàng thân thiết: Thẻ bệnh nhân, Hạng thẻ & Tích điểm, Khuyến mãi & Voucher.",
                        "Quản lý thẻ & ưu đãi",
                        "bi-credit-card",
                        List.of(
                                new SubTabSpec(
                                        "TAB_MEMBERSHIP_CARDS",
                                        "Thẻ bệnh nhân",
                                        "Tra cứu thông tin thẻ thành viên, số dư điểm tích lũy và lịch sử đổi quyền lợi.",
                                        "Tab ngang",
                                        "bi-credit-card-2-front",
                                        List.of(new ActionSpec("RECEPTION_MEMBERSHIPS", "Toàn quyền", "Tra cứu thẻ thành viên & điểm tích lũy", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_MEMBERSHIP_TIERS",
                                        "Hạng thẻ & Tích điểm",
                                        "Cấu hình các hạng thẻ (Đồng, Bạc, Vàng, Kim Cương), tỷ lệ chiết khấu và quy tắc tích điểm.",
                                        "Tab ngang",
                                        "bi-award",
                                        List.of(new ActionSpec("CATALOG_MEMBERSHIP_TIERS", "Toàn quyền", "Cấu hình hạng thẻ & quyền lợi", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_MEMBERSHIP_PROMOTIONS",
                                        "Khuyến mãi & Voucher",
                                        "Quản lý các chương trình ưu đãi, mã voucher giảm giá viện phí và thời hạn áp dụng.",
                                        "Tab ngang",
                                        "bi-ticket-perforated",
                                        List.of(new ActionSpec("CATALOG_PROMOTIONS", "Toàn quyền", "Cấu hình voucher & khuyến mãi", ""))
                                )
                        ),
                        true
                ),

                // 8. Nhật ký thao tác (Đơn lẻ)
                new GroupSpec(
                        "GRP_MENU_AUDIT",
                        "Nhật ký thao tác",
                        "Theo dõi nhật ký kiểm toán thao tác người dùng và thay đổi dữ liệu (Audit Log).",
                        "Nhật ký thao tác",
                        "bi-journal-text",
                        List.of(new ActionSpec("SYSTEM_AUDIT", "Toàn quyền", "Xem nhật ký kiểm toán hệ thống", ""))
                ),

                // 9. Quản lý kho vật tư (Có 8 Tab ngang khớp 100% trang Quản lý kho vật tư)
                new GroupSpec(
                        "GRP_MENU_INVENTORY",
                        "Quản lý kho vật tư",
                        "Hệ thống kho vật tư y tế: Tồn kho, Lô & Hạn dùng, Phiếu nhập, Phiếu xuất, Điều chuyển, Danh mục VT, Nhóm VT, NCC & Công nợ.",
                        "Quản lý kho vật tư",
                        "bi-boxes",
                        List.of(
                                new SubTabSpec(
                                        "TAB_INV_STOCK",
                                        "Tồn kho",
                                        "Tra cứu số lượng tồn kho tổng hợp theo thời gian thực và cảnh báo sắp hết.",
                                        "Tab ngang",
                                        "bi-box-seam",
                                        List.of(new ActionSpec("INVENTORY_STOCK", "Toàn quyền", "Tra cứu & quản lý tồn kho", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_INV_BATCHES",
                                        "Lô & Hạn dùng",
                                        "Theo dõi số lượng còn lại theo từng số lô nhập và cảnh báo lô sắp hết hạn sử dụng.",
                                        "Tab ngang",
                                        "bi-calendar2-range",
                                        List.of(new ActionSpec("INVENTORY_BATCHES", "Toàn quyền", "Quản lý lô vật tư & hạn dùng", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_INV_RECEIPT",
                                        "Phiếu nhập kho",
                                        "Lập phiếu nhập kho vật tư mới từ nhà cung cấp và kiểm kê chứng từ nhập.",
                                        "Tab ngang",
                                        "bi-box-arrow-in-down",
                                        List.of(new ActionSpec("INVENTORY_RECEIPT", "Toàn quyền", "Lập & quản lý phiếu nhập kho", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_INV_ISSUE",
                                        "Phiếu xuất kho",
                                        "Lập phiếu xuất kho vật tư tiêu hao phục vụ điều trị lâm sàng tại các phòng khám.",
                                        "Tab ngang",
                                        "bi-box-arrow-up",
                                        List.of(new ActionSpec("INVENTORY_ISSUE", "Toàn quyền", "Lập & quản lý phiếu xuất kho", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_INV_TRANSFER",
                                        "Điều chuyển kho",
                                        "Tạo và xác nhận phiếu điều chuyển vật tư y tế giữa các chi nhánh cơ sở.",
                                        "Tab ngang",
                                        "bi-arrow-left-right",
                                        List.of(new ActionSpec("INVENTORY_TRANSFER", "Toàn quyền", "Điều chuyển vật tư liên chi nhánh", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_INV_MATERIALS",
                                        "Danh mục vật tư",
                                        "Quản lý danh mục chuẩn các mặt hàng vật tư y tế, quy cách đóng gói và định mức tồn.",
                                        "Tab ngang",
                                        "bi-card-list",
                                        List.of(new ActionSpec("INVENTORY_MATERIALS", "Toàn quyền", "Quản lý danh mục vật tư", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_INV_CATEGORIES",
                                        "Nhóm vật tư",
                                        "Phân loại nhóm vật tư tiêu hao, vật liệu phục hình, dụng cụ nha khoa.",
                                        "Tab ngang",
                                        "bi-folder2",
                                        List.of(new ActionSpec("INVENTORY_CATEGORIES", "Toàn quyền", "Quản lý nhóm vật tư", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_INV_SUPPLIERS",
                                        "NCC & Công nợ",
                                        "Quản lý danh mục nhà cung cấp vật tư thiết bị, lịch sử nhập hàng và thanh toán công nợ NCC.",
                                        "Tab ngang",
                                        "bi-truck",
                                        List.of(new ActionSpec("SUPPLIER_MANAGE", "Toàn quyền", "Quản lý đối tác & công nợ NCC", ""))
                                )
                        ),
                        true
                ),

                // 10. Tài chính (Có 11 Tab ngang: 6 Tab Khách hàng + 5 Tab Phòng khám)
                new GroupSpec(
                        "GRP_MENU_FINANCE",
                        "Tài chính",
                        "Quản lý tài chính toàn diện: 6 tab Viện phí Khách hàng (Tổng quan, Hóa đơn, Công nợ, Tạm ứng, Hoàn tiền, Thanh toán) & 5 tab Phòng khám (Chi phí, Lương, Tạm ứng, Hoa hồng, Lợi nhuận).",
                        "Tài chính",
                        "bi-wallet2",
                        List.of(
                                new SubTabSpec(
                                        "TAB_FIN_DASHBOARD",
                                        "[Khách hàng] Tổng quan viện phí",
                                        "Bảng điều khiển tổng quan viện phí, công nợ và tình hình thanh toán của bệnh nhân.",
                                        "Tab ngang",
                                        "bi-speedometer2",
                                        List.of(new ActionSpec("FINANCE_DASHBOARD", "Toàn quyền", "Xem Tổng quan tài chính khách hàng", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_FIN_INVOICES",
                                        "[Khách hàng] Hóa đơn",
                                        "Lập hóa đơn viện phí mới, áp dụng voucher khuyến mãi và in hóa đơn thanh toán.",
                                        "Tab ngang",
                                        "bi-receipt",
                                        List.of(new ActionSpec("INVOICE_CREATE", "Toàn quyền", "Tạo và quản lý hóa đơn viện phí", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_FIN_DEBTS",
                                        "[Khách hàng] Công nợ",
                                        "Theo dõi số dư nợ viện phí, lịch sử trả góp và quản lý công nợ khách hàng.",
                                        "Tab ngang",
                                        "bi-cash-stack",
                                        List.of(new ActionSpec("FINANCE_DEBT", "Toàn quyền", "Theo dõi công nợ bệnh nhân", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_FIN_DEPOSITS",
                                        "[Khách hàng] Tiền tạm ứng",
                                        "Quản lý thu tiền đặt cọc tạm ứng điều trị và đối trừ tạm ứng vào hóa đơn viện phí.",
                                        "Tab ngang",
                                        "bi-wallet2",
                                        List.of(new ActionSpec("FINANCE_DEPOSIT", "Toàn quyền", "Quản lý tiền tạm ứng bệnh nhân", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_FIN_REFUNDS",
                                        "[Khách hàng] Hoàn tiền",
                                        "Tiếp nhận và xử lý yêu cầu hoàn trả tiền dịch vụ viện phí hoặc hủy quy trình điều trị.",
                                        "Tab ngang",
                                        "bi-arrow-counterclockwise",
                                        List.of(new ActionSpec("REFUND_MANAGE", "Toàn quyền", "Xử lý hoàn tiền dịch vụ viện phí", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_FIN_PAYMENTS",
                                        "[Khách hàng] Lịch sử thanh toán",
                                        "Thu tiền viện phí từng đợt (tiền mặt / chuyển khoản), gạch nợ và in biên lai thu tiền.",
                                        "Tab ngang",
                                        "bi-credit-card-2-front",
                                        List.of(new ActionSpec("PAYMENT_COLLECT", "Toàn quyền", "Thu tiền viện phí & lịch sử thanh toán", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_FIN_EXPENSES",
                                        "[Phòng khám] Chi phí vận hành",
                                        "Lập phiếu chi và quản lý các khoản chi phí vận hành, điện nước, маркеting phòng khám.",
                                        "Tab ngang",
                                        "bi-journal-arrow-down",
                                        List.of(new ActionSpec("FINANCE_EXPENSES", "Toàn quyền", "Quản lý chi phí vận hành phòng khám", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_FIN_PAYROLL",
                                        "[Phòng khám] Bảng lương",
                                        "Tính lương cơ bản, phụ cấp, hoa hồng thủ thuật và chốt bảng lương nhân sự hàng tháng.",
                                        "Tab ngang",
                                        "bi-calendar3",
                                        List.of(new ActionSpec("FINANCE_PAYROLL", "Toàn quyền", "Quản lý bảng lương nhân sự", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_FIN_ADVANCES",
                                        "[Phòng khám] Tạm ứng lương",
                                        "Quản lý danh sách đề nghị tạm ứng lương của nhân viên và xét duyệt chi tạm ứng.",
                                        "Tab ngang",
                                        "bi-cash-coin",
                                        List.of(new ActionSpec("FINANCE_PAYROLL_ADVANCES", "Toàn quyền", "Quản lý tạm ứng lương nhân viên", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_FIN_COMMISSION",
                                        "[Phòng khám] Cấu hình hoa hồng",
                                        "Thiết lập định mức và tỷ lệ phần trăm hoa hồng điều trị cho Bác sĩ và Phụ tá.",
                                        "Tab ngang",
                                        "bi-sliders",
                                        List.of(new ActionSpec("FINANCE_COMMISSION_CONFIG", "Toàn quyền", "Cấu hình tỷ lệ hoa hồng", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_FIN_DISTRIBUTIONS",
                                        "[Phòng khám] Phân chia lợi nhuận",
                                        "Theo dõi kết quả kinh doanh ròng và phân chia cổ tức lợi nhuận cho các cổ đông.",
                                        "Tab ngang",
                                        "bi-diagram-2",
                                        List.of(new ActionSpec("FINANCE_OWNER_DISTRIBUTIONS", "Toàn quyền", "Quản lý phân chia lợi nhuận", ""))
                                )
                        ),
                        true
                ),

                // 11. Tiếp đón bệnh nhân (Có 4 Tab ngang)
                new GroupSpec(
                        "GRP_MENU_RECEPTION",
                        "Tiếp đón bệnh nhân",
                        "Quy trình tiếp đón bệnh nhân tại quầy: Lịch hẹn, Tiếp đón & Check-in, Điều phối ghế nha và Hàng chờ gọi số.",
                        "Tiếp đón bệnh nhân",
                        "bi-person-check",
                        List.of(
                                new SubTabSpec(
                                        "TAB_REC_APPOINTMENT",
                                        "Lịch hẹn",
                                        "Xem danh sách lịch hẹn khám, đặt lịch hẹn mới và điều phối lịch hẹn theo ngày/tuần/tháng.",
                                        "Tab ngang",
                                        "bi-calendar3",
                                        List.of(new ActionSpec("APPOINTMENT_MANAGE", "Toàn quyền", "Tạo và quản lý lịch hẹn khám", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_REC_CHECKIN",
                                        "Tiếp đón & Check-in",
                                        "Thực hiện thủ tục check-in đón bệnh nhân và phân luồng tiếp đón tại quầy.",
                                        "Tab ngang",
                                        "bi-person-check",
                                        List.of(new ActionSpec("RECEPTION_CHECKIN", "Toàn quyền", "Thực hiện check-in tiếp đón bệnh nhân", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_REC_DISPATCH",
                                        "Điều phối ghế nha",
                                        "Bảng Kanban theo dõi và điều phối bệnh nhân vào từng ghế khám răng.",
                                        "Tab ngang",
                                        "bi-kanban",
                                        List.of(new ActionSpec("RECEPTION_DISPATCH", "Toàn quyền", "Điều phối ghế nha khoa", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_REC_QUEUE",
                                        "Hàng chờ gọi số",
                                        "Theo dõi danh sách và thứ tự bệnh nhân đang trong hàng chờ khám.",
                                        "Tab ngang",
                                        "bi-list-ol",
                                        List.of(new ActionSpec("APPOINTMENT_VIEW", "Toàn quyền", "Theo dõi hàng chờ khám tại phòng khám", ""))
                                )
                        ),
                        true
                ),

                // 12. Khám trong ngày (Có 7 Tab/Thao tác lâm sàng)
                new GroupSpec(
                        "GRP_MENU_DAILY_VISITS",
                        "Khám trong ngày",
                        "Danh sách ca khám theo ngày, thực hiện chẩn đoán lâm sàng, kê đơn, cận lâm sàng và phác đồ điều trị.",
                        "Khám trong ngày",
                        "bi-heart-pulse",
                        List.of(
                                new SubTabSpec(
                                        "TAB_CLIN_EXAM",
                                        "Phiên khám & Chẩn đoán",
                                        "Bắt đầu ca khám, ghi nhận chẩn đoán và diễn tiến lâm sàng của người bệnh.",
                                        "Thao tác ca khám",
                                        "bi-clipboard2-pulse",
                                        List.of(new ActionSpec("CLINICAL_DAILY_VISITS", "Toàn quyền", "Thực hiện phiên khám & chẩn đoán lâm sàng", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_CLIN_PLAN",
                                        "Kế hoạch điều trị",
                                        "Lập phác đồ điều trị chi tiết nhiều giai đoạn, dự toán chi phí và theo dõi tiến độ.",
                                        "Thao tác ca khám",
                                        "bi-calendar-check",
                                        List.of(new ActionSpec("TREATMENT_PLAN_MANAGE", "Toàn quyền", "Lập kế hoạch & phác đồ điều trị", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_CLIN_PRESCRIPTION",
                                        "Đơn thuốc & Dặn dò",
                                        "Kê đơn thuốc điện tử, hướng dẫn dặn dò người bệnh sau điều trị và in đơn thuốc.",
                                        "Thao tác ca khám",
                                        "bi-capsule-pill",
                                        List.of(new ActionSpec("PRESCRIPTION_WRITE", "Toàn quyền", "Kê đơn thuốc & dặn dò bệnh nhân", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_CLIN_PHOTOS",
                                        "Ảnh chụp lâm sàng",
                                        "Quản lý kho hình ảnh trước/sau điều trị và ảnh chụp khoang miệng của bệnh nhân.",
                                        "Thao tác ca khám",
                                        "bi-camera",
                                        List.of(new ActionSpec("CLINICAL_PHOTOS", "Toàn quyền", "Tải lên & quản lý ảnh chụp lâm sàng", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_CLIN_XRAY",
                                        "Phim X-quang NanoPix",
                                        "Trình xem ảnh X-quang nha khoa tích hợp máy chụp Eighteeth NanoPix.",
                                        "Thao tác ca khám",
                                        "bi-radioactive",
                                        List.of(new ActionSpec("CLINICAL_XRAY", "Toàn quyền", "Xem & đối chiếu ảnh X-quang NanoPix", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_CLIN_EMR",
                                        "Hồ sơ bệnh án điện tử (EMR)",
                                        "Xem hồ sơ bệnh án điện tử (EMR), tiền sử bệnh, phim chụp và toàn bộ lịch sử điều trị.",
                                        "Thao tác ca khám",
                                        "bi-folder2-open",
                                        List.of(new ActionSpec("MEDICAL_RECORD_VIEW", "Toàn quyền", "Xem hồ sơ bệnh án điện tử EMR", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_CLIN_CHAIRS",
                                        "Điều phối ghế điều trị",
                                        "Theo dõi trạng thái ghế nha và điều phối vào ghế điều trị lâm sàng.",
                                        "Thao tác ca khám",
                                        "bi-kanban",
                                        List.of(new ActionSpec("CLINICAL_DISPATCH", "Toàn quyền", "Điều phối ghế nha khoa", ""))
                                )
                        ),
                        true
                ),

                // 13. Quy trình kỹ thuật (Đơn lẻ)
                new GroupSpec(
                        "GRP_MENU_WORKFLOWS",
                        "Quy trình kỹ thuật",
                        "Thiết lập các bước quy trình kỹ thuật chuẩn trong điều trị nha khoa (FT43).",
                        "Quy trình kỹ thuật",
                        "bi-diagram-3",
                        List.of(new ActionSpec("CLINICAL_WORKFLOWS", "Toàn quyền", "Thiết lập quy trình kỹ thuật điều trị", ""))
                ),

                // 14. Quản lý labo (Có 3 Tab ngang: Phiếu đặt xưởng, Nhà cung cấp, Bảng giá)
                new GroupSpec(
                        "GRP_MENU_LABO",
                        "Quản lý labo",
                        "Quản lý toàn diện Labo gia công: Phiếu đặt xưởng chế tác răng sứ, Nhà cung cấp xưởng và Bảng giá Labo.",
                        "Quản lý labo",
                        "bi-gear-wide-connected",
                        List.of(
                                new SubTabSpec(
                                        "TAB_LABO_ORDERS",
                                        "Phiếu đặt xưởng",
                                        "Lập phiếu đặt gia công răng sứ/khí cụ, theo dõi tiến độ giao nhận và bảo hành Labo.",
                                        "Tab ngang",
                                        "bi-file-earmark-medical",
                                        List.of(new ActionSpec("CLINICAL_LABO", "Toàn quyền", "Quản lý phiếu đặt xưởng Labo", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_LABO_SUPPLIERS",
                                        "Nhà cung cấp",
                                        "Quản lý thông tin các đối tác xưởng chế tác răng sứ ngoài và lịch sử hợp tác.",
                                        "Tab ngang",
                                        "bi-building",
                                        List.of(new ActionSpec("LABO_SUPPLIERS", "Toàn quyền", "Quản lý nhà cung cấp xưởng Labo", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_LABO_PRICING",
                                        "Bảng giá",
                                        "Cấu hình đơn giá gia công từng dòng răng sứ, trụ Implant và khí cụ chỉnh nha.",
                                        "Tab ngang",
                                        "bi-tags",
                                        List.of(new ActionSpec("LABO_PRICING", "Toàn quyền", "Quản lý bảng giá gia công Labo", ""))
                                )
                        ),
                        true
                ),

                // 15. Chăm sóc khách hàng (Đơn lẻ)
                new GroupSpec(
                        "GRP_MENU_CUSTOMER_CARE",
                        "Chăm sóc khách hàng",
                        "Quản lý danh sách nhắc hẹn sau điều trị, gửi tin nhắn chăm sóc và gọi điện hỏi thăm bệnh nhân.",
                        "Chăm sóc khách hàng",
                        "bi-headset",
                        List.of(new ActionSpec("CUSTOMER_CARE_MANAGE", "Toàn quyền", "Chăm sóc khách hàng sau điều trị", ""))
                ),

                // 16. Chấm công & Ca trực cá nhân (Có 3 Tab ngang: Chấm công cá nhân, Lịch trực tuần của tôi, Yêu cầu nghỉ / Khóa lịch)
                new GroupSpec(
                        "GRP_MENU_MY_SCHEDULE",
                        "Chấm công & Ca trực cá nhân",
                        "Theo dõi lịch trực tuần của tôi, thực hiện điểm danh chấm công cá nhân và gửi yêu cầu nghỉ / khóa lịch.",
                        "Chấm công & Ca trực cá nhân",
                        "bi-calendar2-check",
                        List.of(
                                new SubTabSpec(
                                        "TAB_MY_ATTENDANCE",
                                        "Chấm công cá nhân",
                                        "Thực hiện điểm danh vào/ra ca qua WiFi/GPS chi nhánh và tra cứu lịch sử chấm công.",
                                        "Tab ngang",
                                        "bi-fingerprint",
                                        List.of(new ActionSpec("HR_MY_ATTENDANCE", "Toàn quyền", "Chấm công cá nhân hàng ngày", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_MY_SCHEDULE",
                                        "Lịch trực tuần của tôi",
                                        "Xem lịch phân ca cá nhân theo tuần/tháng, thông tin ca trực và vị trí phòng khám.",
                                        "Tab ngang",
                                        "bi-calendar-week",
                                        List.of(new ActionSpec("SCHEDULE_MY", "Toàn quyền", "Xem lịch làm việc cá nhân", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_MY_LEAVE",
                                        "Yêu cầu nghỉ / Khóa lịch",
                                        "Tạo đơn xin nghỉ phép/khóa lịch khám, theo dõi tiến độ phê duyệt từ ban quản lý.",
                                        "Tab ngang",
                                        "bi-calendar-x",
                                        List.of(new ActionSpec("HR_LEAVE_REQUESTS", "Toàn quyền", "Tạo và theo dõi đơn xin nghỉ phép", ""))
                                )
                        ),
                        true
                ),

                // 17. Báo cáo & Thống kê (Có 4 Tab ngang: Tổng quan điều hành, Báo cáo Tài chính & Dòng tiền, Công nợ Bệnh nhân, Vận hành & Nguồn khách)
                new GroupSpec(
                        "GRP_MENU_REPORTS",
                        "Báo cáo & Thống kê",
                        "Hệ thống báo cáo quản trị: Tổng quan điều hành, Báo cáo Tài chính & Dòng tiền, Công nợ Bệnh nhân và Vận hành & Nguồn khách.",
                        "Báo cáo & Thống kê",
                        "bi-graph-up",
                        List.of(
                                new SubTabSpec(
                                        "TAB_REP_OVERVIEW",
                                        "Tổng quan điều hành (29.1)",
                                        "Phân tích các chỉ số KPI điều hành cốt lõi, thực thu dòng tiền và cơ cấu doanh thu.",
                                        "Tab ngang",
                                        "bi-speedometer2",
                                        List.of(new ActionSpec("FINANCE_REPORTS", "Toàn quyền", "Xem báo cáo Tổng quan điều hành", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_REP_FINANCIAL",
                                        "Báo cáo Tài chính & Dòng tiền (29.2)",
                                        "Báo cáo chi tiết doanh thu, chi phí vận hành, lợi nhuận trước/sau thuế và dòng tiền.",
                                        "Tab ngang",
                                        "bi-cash-stack",
                                        List.of(new ActionSpec("REPORT_FINANCIAL", "Toàn quyền", "Xem báo cáo Tài chính & Dòng tiền", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_REP_DEBTS",
                                        "Công nợ Bệnh nhân",
                                        "Thống kê danh sách bệnh nhân còn nợ viện phí, tỷ lệ thu hồi nợ và phân tích tuổi nợ.",
                                        "Tab ngang",
                                        "bi-receipt-cutoff",
                                        List.of(new ActionSpec("REPORT_DEBTS", "Toàn quyền", "Xem báo cáo Công nợ Bệnh nhân", ""))
                                ),
                                new SubTabSpec(
                                        "TAB_REP_OPERATIONAL",
                                        "Vận hành & Nguồn khách",
                                        "Thống kê số lượt khám, tỷ lệ khách mới/tái khám và hiệu suất điều trị theo bác sĩ.",
                                        "Tab ngang",
                                        "bi-people",
                                        List.of(new ActionSpec("REPORT_OPERATIONAL", "Toàn quyền", "Xem báo cáo Vận hành & Nguồn khách", ""))
                                )
                        ),
                        true
                )
        );

        List<FeaturePermissionGroup> builtGroups = new ArrayList<>();
        for (GroupSpec gs : groupSpecs) {
            boolean hasSubTabs = gs.subTabSpecs() != null && !gs.subTabSpecs().isEmpty();
            List<PermissionSubTab> builtSubTabs = new ArrayList<>();
            List<PermissionActionItem> directActions = new ArrayList<>();

            if (hasSubTabs) {
                for (SubTabSpec sts : gs.subTabSpecs()) {
                    List<PermissionActionItem> tabActions = new ArrayList<>();
                    for (ActionSpec as : sts.actionSpecs()) {
                        Permission p = byCode.get(as.code());
                        if (p != null) {
                            tabActions.add(new PermissionActionItem(
                                    p.getPermissionId(),
                                    p.getPermissionCode(),
                                    as.shortBadge(),
                                    as.actionDetail(),
                                    as.dependsOnCode()
                            ));
                        }
                    }
                    builtSubTabs.add(new PermissionSubTab(
                            sts.tabKey(),
                            sts.tabTitle(),
                            sts.tabDescription(),
                            sts.tabBadge(),
                            sts.iconClass(),
                            tabActions
                    ));
                }
            } else {
                for (ActionSpec as : gs.actionSpecs()) {
                    Permission p = byCode.get(as.code());
                    if (p != null) {
                        directActions.add(new PermissionActionItem(
                                p.getPermissionId(),
                                p.getPermissionCode(),
                                as.shortBadge(),
                                as.actionDetail(),
                                as.dependsOnCode()
                        ));
                    }
                }
            }

            builtGroups.add(new FeaturePermissionGroup(
                    gs.groupKey(),
                    gs.groupTitle(),
                    gs.groupDescription(),
                    gs.sidebarBadge(),
                    gs.iconClass(),
                    hasSubTabs,
                    builtSubTabs,
                    directActions
            ));
        }

        return builtGroups;
    }

    private List<ModulePermissionSection> buildMisaModuleSections(List<Permission> allPermissions) {
        List<FeaturePermissionGroup> groups = buildSidebarGroups(allPermissions);
        return List.of(new ModulePermissionSection(
                "SIDEBAR",
                "Chức năng trên thanh Sidebar",
                "bi-layout-sidebar-inset",
                groups,
                allPermissions.size()
        ));
    }

    private Map<String, String> getOrderedModuleLabels() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("RECEPTION", "Lễ tân, Tiếp đón & CSKH");
        map.put("CLINICAL", "Bác sĩ & Điều trị lâm sàng");
        map.put("FINANCE", "Tài chính & Kế toán");
        map.put("INVENTORY", "Kho & Vật tư");
        map.put("HR", "Nhân sự, Phân ca & Chấm công");
        map.put("SYSTEM", "Quản trị hệ thống & Danh mục");
        return map;
    }

    private String normalizeRoleCode(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().replace('đ', 'd').replace('Đ', 'D');
        String deAccented = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String underscored = deAccented.replaceAll("[\\s\\-]+", "_").toUpperCase(Locale.ROOT);
        if (underscored.startsWith("ROLE_")) {
            underscored = underscored.substring(5);
        }
        return underscored;
    }

    private boolean isReservedRoleCode(String code) {
        return "ADMIN".equalsIgnoreCase(code)
                || "ROLE_ADMIN".equalsIgnoreCase(code)
                || "BENH_NHAN".equalsIgnoreCase(code)
                || "ROLE_BENH_NHAN".equalsIgnoreCase(code)
                || "PATIENT".equalsIgnoreCase(code)
                || "ROLE_PATIENT".equalsIgnoreCase(code);
    }

    private boolean isAdminRole(Role r) {
        if (r == null || r.getRoleName() == null) {
            return false;
        }
        String name = r.getRoleName().trim();
        return "ADMIN".equalsIgnoreCase(name) || "ROLE_ADMIN".equalsIgnoreCase(name);
    }

    private boolean isPatientRole(Role r) {
        if (r == null || r.getRoleName() == null) {
            return false;
        }
        String name = r.getRoleName().trim();
        return "BENH_NHAN".equalsIgnoreCase(name)
                || "ROLE_BENH_NHAN".equalsIgnoreCase(name)
                || "PATIENT".equalsIgnoreCase(name)
                || "ROLE_PATIENT".equalsIgnoreCase(name);
    }
}
