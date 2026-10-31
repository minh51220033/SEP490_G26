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

    public record FeaturePermissionGroup(
            String groupKey,
            String groupTitle,
            String groupDescription,
            String sidebarBadge,
            String iconClass,
            List<PermissionActionItem> actions
    ) {}

    public record ModulePermissionSection(
            String moduleKey,
            String moduleTitle,
            String moduleIcon,
            List<FeaturePermissionGroup> groups,
            int totalPermissions
    ) {}

    private record GroupSpec(
            String groupKey,
            String groupTitle,
            String groupDescription,
            String sidebarBadge,
            String iconClass,
            List<ActionSpec> actionSpecs
    ) {}

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
    @Transactional(readOnly = true)
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
        List<ModulePermissionSection> misaModules = buildMisaModuleSections(allPermissions);

        // Đếm số lượng tài khoản đang gắn với từng vai trò
        Map<Long, Integer> userCountByRoleId = new HashMap<>();
        for (Role r : roles) {
            if (r.getRoleId() != null) {
                List<User> usersWithRole = userRepository.findByRole_RoleId(r.getRoleId().intValue());
                userCountByRoleId.put(r.getRoleId(), usersWithRole.size());
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

        boolean selectedRoleEditable = selectedRole != null && !isAdminRole(selectedRole) && !isPatientRole(selectedRole);

        model.addAttribute("roles", roles);
        model.addAttribute("permissionsByModule", permissionsByModule);
        model.addAttribute("misaModules", misaModules);
        model.addAttribute("moduleLabels", moduleLabels);
        model.addAttribute("userCountByRoleId", userCountByRoleId);
        model.addAttribute("selectedRole", selectedRole);
        model.addAttribute("selectedPermissionIds", selectedPermissionIds);
        model.addAttribute("selectedRoleEditable", selectedRoleEditable);
        model.addAttribute("totalPermissionsCount", allPermissions.size());

        return "account/roles";
    }

    @PostMapping("/create")
    @Transactional
    public String createRole(
            @RequestParam String roleName,
            @RequestParam String displayName,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) List<Long> permissionIds,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        requireAdmin(session);

        try {
            String normalizedCode = normalizeRoleCode(roleName);
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

            String cleanDisplayName = displayName == null ? "" : displayName.trim();
            if (cleanDisplayName.isEmpty()) {
                throw new IllegalArgumentException("Tên hiển thị vai trò không được để trống.");
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
            if (currentlyActive) {
                List<User> assignedUsers = userRepository.findByRole_RoleId(roleId.intValue());
                if (!assignedUsers.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Đang có " + assignedUsers.size() + " tài khoản đảm nhiệm vai trò '"
                                    + (role.getDisplayName() != null ? role.getDisplayName() : role.getRoleName())
                                    + "'. Vui lòng chuyển đổi vai trò cho các tài khoản đó trước khi ngừng hoạt động vai trò này."
                    );
                }
            }

            role.setIsActive(!currentlyActive);
            roleRepository.save(role);

            String statusText = Boolean.TRUE.equals(role.getIsActive()) ? "Kích hoạt lại" : "Ngừng hoạt động";
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã chuyển trạng thái vai trò '" + (role.getDisplayName() != null ? role.getDisplayName() : role.getRoleName())
                            + "' sang: " + statusText + "."
            );
            return "redirect:/accounts/roles?editRoleId=" + roleId;

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/accounts/roles?editRoleId=" + roleId;
        }
    }

    /**
     * Đảm bảo nguyên tắc "Quản lý/Thao tác bao hàm quyền Xem" (Write implies Read) ở tầng Backend:
     * Nếu người dùng tích chọn quyền Tạo/Quản lý thì tự động đảm bảo quyền Xem tương ứng cũng được bật.
     */
    private List<Permission> findPermissionsWithImpliedView(List<Long> permissionIds) {
        List<Permission> selected = new ArrayList<>(permissionRepository.findAllById(permissionIds));
        Set<String> codes = selected.stream()
                .map(Permission::getPermissionCode)
                .filter(Objects::nonNull)
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        Map<String, String> impliedMap = Map.of(
                "APPOINTMENT_MANAGE", "APPOINTMENT_VIEW",
                "CLINICAL_EXAM_WRITE", "MEDICAL_RECORD_VIEW",
                "INVOICE_CREATE", "INVOICE_VIEW",
                "PAYMENT_COLLECT", "INVOICE_VIEW",
                "INVENTORY_MANAGE", "INVENTORY_VIEW"
        );

        for (Map.Entry<String, String> entry : impliedMap.entrySet()) {
            if (codes.contains(entry.getKey()) && !codes.contains(entry.getValue())) {
                permissionRepository.findByPermissionCodeIgnoreCase(entry.getValue())
                        .ifPresent(selected::add);
                codes.add(entry.getValue());
            }
        }
        return selected;
    }

    /**
     * Xây dựng danh sách Phân hệ -> Chức năng Sidebar -> Các hành động con theo chuẩn giao diện MISA AMIS.
     * Tên từng dòng khớp 100% với tên các mục hiển thị trên thanh điều hướng (Sidebar).
     */
    private List<ModulePermissionSection> buildMisaModuleSections(List<Permission> allPermissions) {
        Map<String, Permission> byCode = new HashMap<>();
        for (Permission p : allPermissions) {
            if (p.getPermissionCode() != null) {
                byCode.put(p.getPermissionCode().trim().toUpperCase(), p);
            }
        }

        Map<String, List<GroupSpec>> moduleSpecs = new LinkedHashMap<>();

        // 1. LỄ TÂN, TIẾP ĐÓN & CSKH (Khớp 1:1 từng menu Sidebar)
        moduleSpecs.put("RECEPTION", List.of(
                new GroupSpec(
                        "GRP_RECEPTION_PATIENT",
                        "Hồ sơ bệnh nhân",
                        "Tiếp nhận bệnh nhân mới, tra cứu và cập nhật hồ sơ hành chính.",
                        "Hồ sơ bệnh nhân",
                        "bi-person-vcard",
                        List.of(new ActionSpec("PATIENT_PROFILE_MANAGE", "Toàn quyền", "Tiếp nhận bệnh nhân & quản lý hồ sơ hành chính", ""))
                ),
                new GroupSpec(
                        "GRP_RECEPTION_MEMBERSHIP",
                        "Thẻ thành viên & Điểm",
                        "Tra cứu thông tin hạng thẻ thành viên, điểm tích lũy và đổi quyền lợi bệnh nhân (FT19).",
                        "Thẻ thành viên & Điểm",
                        "bi-credit-card",
                        List.of(new ActionSpec("CUSTOMER_CARE_MANAGE", "Toàn quyền", "Tra cứu thẻ thành viên & điểm tích lũy", ""))
                ),
                new GroupSpec(
                        "GRP_RECEPTION_APPOINTMENT",
                        "Quản lý lịch hẹn",
                        "Xem danh sách lịch hẹn khám, đặt lịch hẹn mới và điều phối lịch hẹn theo tuần/tháng.",
                        "Quản lý lịch hẹn",
                        "bi-calendar3",
                        List.of(
                                new ActionSpec("APPOINTMENT_VIEW", "Xem", "Xem danh sách lịch hẹn bệnh nhân", ""),
                                new ActionSpec("APPOINTMENT_MANAGE", "Đặt lịch", "Tạo và quản lý lịch hẹn khám", "APPOINTMENT_VIEW")
                        )
                ),
                new GroupSpec(
                        "GRP_RECEPTION_CHECKIN",
                        "Tiếp đón & Check-in",
                        "Thực hiện thủ tục check-in đón bệnh nhân và phân luồng tiếp đón tại quầy.",
                        "Tiếp đón & Check-in",
                        "bi-person-check",
                        List.of(new ActionSpec("APPOINTMENT_MANAGE", "Toàn quyền", "Thực hiện check-in tiếp đón bệnh nhân", ""))
                ),
                new GroupSpec(
                        "GRP_RECEPTION_QUEUE",
                        "Hàng chờ khám",
                        "Theo dõi danh sách và thứ tự bệnh nhân đang trong hàng chờ khám.",
                        "Hàng chờ khám",
                        "bi-list-ol",
                        List.of(new ActionSpec("APPOINTMENT_VIEW", "Toàn quyền", "Theo dõi hàng chờ khám tại phòng khám", ""))
                ),
                new GroupSpec(
                        "GRP_RECEPTION_CUSTOMER_CARE",
                        "Chăm sóc khách hàng",
                        "Quản lý danh sách nhắc hẹn sau điều trị, gửi tin nhắn chăm sóc và gọi điện hỏi thăm bệnh nhân (FT44).",
                        "Chăm sóc khách hàng",
                        "bi-headset",
                        List.of(new ActionSpec("CUSTOMER_CARE_MANAGE", "Toàn quyền", "Quản lý chăm sóc khách hàng & nhắc tái khám", ""))
                )
        ));

        // 2. BÁC SĨ & ĐIỀU TRỊ LÂM SÀNG (Khớp 1:1 từng menu Sidebar)
        moduleSpecs.put("CLINICAL", List.of(
                new GroupSpec(
                        "GRP_CLINICAL_DAILY_VISITS",
                        "Danh sách khám trong ngày",
                        "Xem danh sách bệnh nhân khám theo ngày, tuần và chọn ca để bắt đầu phiên khám.",
                        "Danh sách khám trong ngày",
                        "bi-calendar2-week",
                        List.of(
                                new ActionSpec("MEDICAL_RECORD_VIEW", "Xem", "Xem danh sách ca khám trong ngày", ""),
                                new ActionSpec("CLINICAL_EXAM_WRITE", "Khám lâm sàng", "Bắt đầu ca khám và chẩn đoán bệnh", "MEDICAL_RECORD_VIEW")
                        )
                ),
                new GroupSpec(
                        "GRP_CLINICAL_WORKFLOWS",
                        "Quy trình kỹ thuật",
                        "Quản lý và thực hiện quy trình kỹ thuật lâm sàng chuẩn Bộ Y Tế (QĐ 3207/QĐ-BYT).",
                        "Quy trình kỹ thuật",
                        "bi-journal-medical",
                        List.of(new ActionSpec("CLINICAL_EXAM_WRITE", "Toàn quyền", "Thực hiện quy trình kỹ thuật lâm sàng", ""))
                ),
                new GroupSpec(
                        "GRP_CLINICAL_CHAIRS",
                        "Điều phối ghế nha",
                        "Bảng Kanban theo dõi và điều phối bệnh nhân vào từng ghế khám răng.",
                        "Điều phối ghế nha",
                        "bi-kanban",
                        List.of(new ActionSpec("CLINICAL_EXAM_WRITE", "Toàn quyền", "Điều phối ghế nha khoa", ""))
                ),
                new GroupSpec(
                        "GRP_CLINICAL_PRESCRIPTION",
                        "Đơn thuốc & Dặn dò",
                        "Kê đơn thuốc điện tử, hướng dẫn dặn dò người bệnh sau điều trị và in đơn thuốc (FT18).",
                        "Đơn thuốc & Dặn dò",
                        "bi-capsule",
                        List.of(new ActionSpec("PRESCRIPTION_WRITE", "Toàn quyền", "Kê đơn thuốc & dặn dò sau điều trị", ""))
                ),
                new GroupSpec(
                        "GRP_CLINICAL_TREATMENT_PLAN",
                        "Kế hoạch điều trị",
                        "Lập kế hoạch điều trị nha khoa nhiều giai đoạn và lộ trình chi phí cho bệnh nhân (FT15).",
                        "Kế hoạch điều trị",
                        "bi-diagram-3-fill",
                        List.of(new ActionSpec("TREATMENT_PLAN_MANAGE", "Toàn quyền", "Lập kế hoạch điều trị nhiều giai đoạn", ""))
                ),
                new GroupSpec(
                        "GRP_CLINICAL_PHOTOS",
                        "Kho ảnh lâm sàng",
                        "Quản lý kho hình ảnh trước/sau điều trị và ảnh chụp khoang miệng của bệnh nhân (FT17).",
                        "Kho ảnh lâm sàng",
                        "bi-images",
                        List.of(new ActionSpec("CLINICAL_EXAM_WRITE", "Toàn quyền", "Xem & tải ảnh lâm sàng trước/sau", ""))
                ),
                new GroupSpec(
                        "GRP_CLINICAL_LABO",
                        "Xét nghiệm & Labo",
                        "Quản lý phiếu đặt xưởng răng sứ, theo dõi tiến độ gia công phục hình và mẫu xét nghiệm (FT13, FT15).",
                        "Xét nghiệm & Labo",
                        "bi-droplet-half",
                        List.of(new ActionSpec("TREATMENT_PLAN_MANAGE", "Toàn quyền", "Quản lý phiếu xưởng Labo & xét nghiệm", ""))
                ),
                new GroupSpec(
                        "GRP_CLINICAL_PATIENT_RECORDS",
                        "Hồ sơ bệnh nhân (Bác sĩ)",
                        "Xem hồ sơ bệnh án điện tử (EMR), tiền sử bệnh, phim chụp và toàn bộ lịch sử điều trị.",
                        "Hồ sơ bệnh nhân",
                        "bi-folder2-open",
                        List.of(new ActionSpec("MEDICAL_RECORD_VIEW", "Toàn quyền", "Xem hồ sơ bệnh án điện tử EMR", ""))
                )
        ));

        // 3. TÀI CHÍNH & KẾ TOÁN (Khớp 1:1 từng menu Sidebar)
        moduleSpecs.put("FINANCE", List.of(
                new GroupSpec(
                        "GRP_FINANCE_DASHBOARD",
                        "Dashboard Bệnh nhân",
                        "Bảng điều khiển tổng quan viện phí, công nợ và tình hình thanh toán của bệnh nhân (SCR-22.1).",
                        "Dashboard Bệnh nhân",
                        "bi-speedometer2",
                        List.of(new ActionSpec("INVOICE_VIEW", "Toàn quyền", "Xem Dashboard tài chính bệnh nhân", ""))
                ),
                new GroupSpec(
                        "GRP_FINANCE_INVOICE",
                        "Quản lý Hóa đơn",
                        "Lập hóa đơn viện phí mới, áp dụng voucher khuyến mãi và in hóa đơn thanh toán (SCR-21.2).",
                        "Quản lý Hóa đơn",
                        "bi-receipt",
                        List.of(
                                new ActionSpec("INVOICE_VIEW", "Xem", "Xem danh sách hóa đơn", ""),
                                new ActionSpec("INVOICE_CREATE", "Lập hóa đơn", "Tạo hóa đơn viện phí mới", "INVOICE_VIEW")
                        )
                ),
                new GroupSpec(
                        "GRP_FINANCE_PAYMENT",
                        "Lịch sử Thanh toán",
                        "Thu tiền viện phí từng đợt (tiền mặt / chuyển khoản), gạch nợ và in biên lai thu tiền (SCR-22.3).",
                        "Lịch sử Thanh toán",
                        "bi-credit-card-2-front",
                        List.of(new ActionSpec("PAYMENT_COLLECT", "Toàn quyền", "Thu tiền viện phí & ghi nhận thanh toán", ""))
                ),
                new GroupSpec(
                        "GRP_FINANCE_DEBT",
                        "Công nợ bệnh nhân",
                        "Theo dõi số dư nợ viện phí, lịch sử trả góp và quản lý công nợ khách hàng.",
                        "Công nợ bệnh nhân",
                        "bi-cash-stack",
                        List.of(new ActionSpec("INVOICE_VIEW", "Toàn quyền", "Theo dõi công nợ bệnh nhân", ""))
                ),
                new GroupSpec(
                        "GRP_FINANCE_REFUND",
                        "Quản lý Hoàn tiền",
                        "Tiếp nhận và xử lý yêu cầu hoàn trả tiền dịch vụ viện phí hoặc hủy quy trình điều trị.",
                        "Quản lý Hoàn tiền",
                        "bi-arrow-counterclockwise",
                        List.of(new ActionSpec("REFUND_MANAGE", "Toàn quyền", "Xử lý yêu cầu hoàn tiền dịch vụ", ""))
                )
        ));

        // 4. KHO & VẬT TƯ (Khớp 1:1 từng menu Sidebar)
        moduleSpecs.put("INVENTORY", List.of(
                new GroupSpec(
                        "GRP_INVENTORY_STOCK",
                        "Quản lý kho vật tư",
                        "Tra cứu tồn kho vật tư tiêu hao, lập phiếu nhập kho, xuất kho và cảnh báo tồn tối thiểu.",
                        "Quản lý kho vật tư",
                        "bi-box-seam",
                        List.of(
                                new ActionSpec("INVENTORY_VIEW", "Xem tồn kho", "Tra cứu tồn kho vật tư", ""),
                                new ActionSpec("INVENTORY_MANAGE", "Nhập/Xuất kho", "Lập phiếu nhập & xuất kho vật tư", "INVENTORY_VIEW")
                        )
                ),
                new GroupSpec(
                        "GRP_INVENTORY_SUPPLIER",
                        "Nhà cung cấp",
                        "Quản lý danh bạ nhà cung cấp vật tư y tế và theo dõi công nợ phải trả nhà cung cấp.",
                        "Nhà cung cấp",
                        "bi-truck",
                        List.of(new ActionSpec("SUPPLIER_MANAGE", "Toàn quyền", "Quản lý nhà cung cấp & công nợ", ""))
                )
        ));

        // 5. NHÂN SỰ, CA TRỰC & CHẤM CÔNG (Khớp 1:1 từng menu Sidebar)
        moduleSpecs.put("HR", List.of(
                new GroupSpec(
                        "GRP_HR_MY_SCHEDULE",
                        "Lịch trực & Chấm công",
                        "Xem lịch làm việc cá nhân hàng tuần và thực hiện check-in/check-out chấm công.",
                        "Lịch trực & Chấm công",
                        "bi-calendar-check",
                        List.of(new ActionSpec("SCHEDULE_MY", "Toàn quyền", "Xem lịch làm việc & chấm công cá nhân", ""))
                ),
                new GroupSpec(
                        "GRP_HR_BRANCH_SCHEDULE",
                        "Phân ca làm việc",
                        "Xếp lịch phân ca làm việc tuần cho toàn bộ nhân viên tại chi nhánh cơ sở.",
                        "Phân ca làm việc",
                        "bi-calendar-range",
                        List.of(new ActionSpec("SCHEDULE_MANAGE", "Toàn quyền", "Phân ca làm việc cho nhân viên", ""))
                ),
                new GroupSpec(
                        "GRP_HR_ATTENDANCE",
                        "Chấm công chi nhánh",
                        "Theo dõi và quản lý bảng tổng hợp chấm công thực tế của nhân viên toàn chi nhánh.",
                        "Chấm công chi nhánh",
                        "bi-fingerprint",
                        List.of(new ActionSpec("SCHEDULE_MANAGE", "Toàn quyền", "Theo dõi bảng chấm công chi nhánh", ""))
                ),
                new GroupSpec(
                        "GRP_HR_STAFF",
                        "Quản lý nhân sự",
                        "Quản lý hồ sơ nhân viên, chứng chỉ hành nghề y tế và điều động phân công làm việc.",
                        "Quản lý nhân sự",
                        "bi-person-badge",
                        List.of(new ActionSpec("STAFF_MANAGE", "Toàn quyền", "Quản lý hồ sơ nhân sự cơ sở", ""))
                )
        ));

        // 6. QUẢN TRỊ HỆ THỐNG & DANH MỤC (Khớp 1:1 từng menu Sidebar)
        moduleSpecs.put("SYSTEM", List.of(
                new GroupSpec(
                        "GRP_SYSTEM_ACCOUNT",
                        "Quản lý tài khoản",
                        "Tạo tài khoản mới, khóa hoặc mở khóa tài khoản nhân viên và phân quyền cá nhân.",
                        "Quản lý tài khoản",
                        "bi-people",
                        List.of(new ActionSpec("ACCOUNT_MANAGE", "Toàn quyền", "Quản lý tài khoản người dùng", ""))
                ),
                new GroupSpec(
                        "GRP_SYSTEM_ROLE",
                        "Vai trò & Phân quyền",
                        "Cấu hình ma trận phân quyền các vai trò hệ thống và mở rộng thêm các vai trò mới.",
                        "Vai trò & Phân quyền",
                        "bi-shield-lock",
                        List.of(new ActionSpec("ROLE_MANAGE", "Toàn quyền", "Cấu hình vai trò & phân quyền", ""))
                ),
                new GroupSpec(
                        "GRP_SYSTEM_BRANCH",
                        "Quản lý cơ sở",
                        "Quản lý danh sách chi nhánh cơ sở phòng khám, phòng điều trị, ghế nha và dải IP chấm công.",
                        "Quản lý cơ sở",
                        "bi-buildings",
                        List.of(new ActionSpec("BRANCH_MANAGE", "Toàn quyền", "Quản lý cơ sở & ghế nha khoa", ""))
                ),
                new GroupSpec(
                        "GRP_SYSTEM_SERVICES",
                        "Dịch vụ & Bảng giá",
                        "Quản lý danh mục dịch vụ nha khoa, đơn giá và bảng giá niêm yết (FT02).",
                        "Dịch vụ & Bảng giá",
                        "bi-tags",
                        List.of(new ActionSpec("CATALOG_MANAGE", "Toàn quyền", "Quản lý danh mục dịch vụ & bảng giá", ""))
                ),
                new GroupSpec(
                        "GRP_SYSTEM_MEDICATIONS",
                        "Danh mục thuốc",
                        "Quản lý danh mục thuốc điều trị, biệt dược và hướng dẫn liều dùng.",
                        "Danh mục thuốc",
                        "bi-capsule",
                        List.of(new ActionSpec("CATALOG_MANAGE", "Toàn quyền", "Quản lý danh mục thuốc", ""))
                ),
                new GroupSpec(
                        "GRP_SYSTEM_ICD10",
                        "Danh mục ICD-10",
                        "Quản lý bảng mã bệnh quốc tế ICD-10 phục vụ chẩn đoán lâm sàng.",
                        "Danh mục ICD-10",
                        "bi-clipboard2-pulse",
                        List.of(new ActionSpec("CATALOG_MANAGE", "Toàn quyền", "Quản lý danh mục mã bệnh ICD-10", ""))
                ),
                new GroupSpec(
                        "GRP_SYSTEM_MEMBERSHIP_TIERS",
                        "Hạng thẻ & Tích điểm",
                        "Quản lý các hạng thành viên (Bạc, Vàng, Kim Cương) và quy tắc tích lũy điểm thưởng (FT19).",
                        "Hạng thẻ & Tích điểm",
                        "bi-award",
                        List.of(new ActionSpec("CATALOG_MANAGE", "Toàn quyền", "Quản lý hạng thành viên & tích điểm", ""))
                ),
                new GroupSpec(
                        "GRP_SYSTEM_PROMOTIONS",
                        "Khuyến mãi & Voucher",
                        "Quản lý các chương trình ưu đãi, giảm giá và mã voucher viện phí (FT20).",
                        "Khuyến mãi & Voucher",
                        "bi-ticket-perforated",
                        List.of(new ActionSpec("CATALOG_MANAGE", "Toàn quyền", "Quản lý khuyến mãi & voucher", ""))
                )
        ));

        Map<String, String> moduleTitles = getOrderedModuleLabels();
        Map<String, String> moduleIcons = Map.of(
                "RECEPTION", "bi-person-workspace",
                "CLINICAL", "bi-heart-pulse",
                "FINANCE", "bi-cash-coin",
                "INVENTORY", "bi-boxes",
                "HR", "bi-people",
                "SYSTEM", "bi-sliders"
        );

        List<ModulePermissionSection> sections = new ArrayList<>();
        for (Map.Entry<String, List<GroupSpec>> entry : moduleSpecs.entrySet()) {
            String modKey = entry.getKey();
            List<FeaturePermissionGroup> builtGroups = new ArrayList<>();
            int modPermCount = 0;

            for (GroupSpec gs : entry.getValue()) {
                List<PermissionActionItem> builtActions = new ArrayList<>();
                for (ActionSpec as : gs.actionSpecs()) {
                    Permission p = byCode.get(as.code());
                    if (p != null) {
                        builtActions.add(new PermissionActionItem(
                                p.getPermissionId(),
                                p.getPermissionCode(),
                                as.shortBadge(),
                                as.actionDetail(),
                                as.dependsOnCode()
                        ));
                        modPermCount++;
                    }
                }
                if (!builtActions.isEmpty()) {
                    builtGroups.add(new FeaturePermissionGroup(
                            gs.groupKey(),
                            gs.groupTitle(),
                            gs.groupDescription(),
                            gs.sidebarBadge(),
                            gs.iconClass(),
                            builtActions
                    ));
                }
            }

            if (!builtGroups.isEmpty()) {
                sections.add(new ModulePermissionSection(
                        modKey,
                        moduleTitles.getOrDefault(modKey, modKey),
                        moduleIcons.getOrDefault(modKey, "bi-folder2-open"),
                        builtGroups,
                        modPermCount
                ));
            }
        }

        return sections;
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
