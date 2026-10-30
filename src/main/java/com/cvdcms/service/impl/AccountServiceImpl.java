package com.cvdcms.service.impl;

import com.cvdcms.DTO.AccountCreateDTO;
import com.cvdcms.DTO.AccountUpdateDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Permission;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffBranchAssignment;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.UserType;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.RoleRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.AccountService;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@Transactional
public class AccountServiceImpl implements AccountService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    public AccountServiceImpl(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.branchRepository = branchRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
    }

    // =========================================================
    // 1. TÌM KIẾM + LỌC + PHÂN TRANG
    // =========================================================

    @Override
    @Transactional(readOnly = true)
    public Page<User> searchAccounts(
            String keyword,
            Integer roleId,
            Boolean active,
            Long branchId,
            UserType userType,
            Pageable pageable) {

        if (keyword == null) {
            keyword = "";
        }

        keyword = keyword.trim();

        return userRepository.searchUsers(
                keyword,
                roleId,
                active,
                branchId,
                userType,
                pageable);
    }

    // =========================================================
    // 2. XEM CHI TIẾT ACCOUNT
    // =========================================================

    @Override
    @Transactional(readOnly = true)
    public User getAccountById(Long userId) {

        return userRepository.findByIdWithRolesAndPermissions(userId)
                .or(() -> userRepository.findById(userId))
                .orElseThrow(() -> new RuntimeException(
                        "Không tìm thấy tài khoản có ID: " + userId));
    }

    // =========================================================
    // 3. TẠO ACCOUNT
    // =========================================================

    @Override
    public User createAccount(AccountCreateDTO dto) {

        // 1. Kiểm tra DTO đầu vào.
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu tài khoản không hợp lệ.");
        }

        // 2. Kiểm tra và chuẩn hóa username.
        if (dto.getUsername() == null || dto.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username không được để trống.");
        }

        String username = dto.getUsername().trim();

        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username đã tồn tại.");
        }

        // 3. Kiểm tra mật khẩu.
        if (dto.getPassword() == null || dto.getPassword().isBlank()) {
            throw new IllegalArgumentException("Mật khẩu không được để trống.");
        }

        if (!dto.getPassword().equals(dto.getConfirmPassword())) {
            throw new IllegalArgumentException("Mật khẩu xác nhận không khớp.");
        }

        // 4. Kiểm tra email.
        if (dto.getEmail() == null || dto.getEmail().isBlank()) {
            throw new IllegalArgumentException("Email không được để trống.");
        }

        String email = dto.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email đã được sử dụng.");
        }

        // 5. Kiểm tra số điện thoại.
        if (dto.getPhoneNumber() == null || dto.getPhoneNumber().isBlank()) {
            throw new IllegalArgumentException(
                    "Số điện thoại không được để trống.");
        }

        String phoneNumber = dto.getPhoneNumber().trim();

        if (userRepository.existsByPhoneNumber(phoneNumber)) {
            throw new IllegalArgumentException(
                    "Số điện thoại đã được sử dụng.");
        }

        // 6. Lấy vai trò được Admin chọn.
        // Role ID phải tồn tại trong bảng roles.
        if (dto.getRoleId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn vai trò.");
        }

        Role role = roleRepository.findByIdWithPermissions(dto.getRoleId().longValue())
                .or(() -> roleRepository.findById(dto.getRoleId()))
                .orElseThrow(() -> new IllegalArgumentException("Vai trò không tồn tại."));

        if (!Boolean.TRUE.equals(role.getIsActive())) {
            throw new IllegalArgumentException(
                    "Vai trò này đang bị vô hiệu hóa.");
        }

        if (isAdminRole(role)) {
            throw new IllegalArgumentException(
                    "Không được phép tạo thêm tài khoản Quản trị viên hệ thống.");
        }

        // 7. Tạo tài khoản mới.
        User user = new User();
        user.setUsername(username);

        // Chỉ lưu mật khẩu đã mã hóa BCrypt.
        user.setPassword(passwordEncoder.encode(dto.getPassword()));

        user.setEmail(email);
        user.setPhoneNumber(phoneNumber);
        user.setIsActive(true);
        user.setStatus("ACTIVE");

        // Loại tài khoản suy ra từ vai trò: BENH_NHAN -> PATIENT, còn lại -> STAFF.
        user.setUserType(resolveUserType(role));

        // Quản trị viên là cấp toàn hệ thống nên không gán chi nhánh;
        // các vai trò khác có thể gán chi nhánh (không bắt buộc).
        user.setBranch(isAdminRole(role) ? null : resolveBranch(dto.getBranchId()));

        // Mô hình mới: một User có thể có nhiều Role.
        user.getRoles().add(role);

        // Áp dụng tùy chỉnh quyền hạn chi tiết trong phạm vi tập quyền của Role đã
        // chọn.
        applyUserPermissionCustomization(
                user,
                role,
                dto.getPermissionIds(),
                Boolean.TRUE.equals(dto.getCustomPermissionsSubmitted()));

        // Không gọi user.setFullName() vì họ tên thuộc PatientProfile.
        // Không tự tạo PatientProfile ở đây.
        // Tài khoản do Admin tạo chưa bắt buộc có hồ sơ bệnh nhân.

        return userRepository.save(user);
    }

    // =========================================================
    // 4. UPDATE ACCOUNT
    // =========================================================

    @Override
    public User updateAccount(Long userId, AccountUpdateDTO dto) {

        // Lấy tài khoản cần cập nhật.
        User user = getAccountById(userId);

        // Cập nhật email nếu người quản trị gửi giá trị mới.
        if (dto.getEmail() != null) {
            String email = dto.getEmail().trim().toLowerCase();

            if (email.isBlank()) {
                throw new IllegalArgumentException(
                        "Email không được để trống.");
            }

            // Chỉ báo trùng nếu email đang thuộc tài khoản khác.
            userRepository.findByEmail(email)
                    .filter(existing -> !existing.getUserId().equals(userId))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException(
                                "Email đã được tài khoản khác sử dụng.");
                    });

            user.setEmail(email);
        }

        // Cập nhật số điện thoại nếu có gửi giá trị mới.
        if (dto.getPhoneNumber() != null) {
            String phone = dto.getPhoneNumber().trim();

            if (phone.isBlank()) {
                throw new IllegalArgumentException(
                        "Số điện thoại không được để trống.");
            }

            // Không cho phép hai tài khoản sử dụng cùng số điện thoại.
            userRepository.findByPhoneNumber(phone)
                    .filter(existing -> !existing.getUserId().equals(userId))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException(
                                "Số điện thoại đã được tài khoản khác sử dụng.");
                    });

            user.setPhoneNumber(phone);
        }

        // Chi nhánh:
        // - Quản trị viên: luôn để trống (toàn hệ thống).
        // - Nhân sự đã có hồ sơ nhân sự: chi nhánh do phân công chi nhánh (FT41) quyết
        // định, giữ nguyên.
        // - Tài khoản khác: cập nhật theo lựa chọn (để trống = bỏ gán chi nhánh).
        if (hasAdminRole(user)) {
            user.setBranch(null);
        } else if (!staffProfileRepository.existsByUserUserId(userId)) {
            user.setBranch(resolveBranch(dto.getBranchId()));
        }

        // Đồng bộ Email và Số điện thoại sang Hồ sơ nhân sự (FT41) nếu tài khoản đã gắn
        // với nhân sự.
        final String syncEmail = user.getEmail();
        final String syncPhone = user.getPhoneNumber();
        staffProfileRepository.findByUser_UserId(userId).ifPresent(staff -> {
            staff.setEmail(syncEmail);
            staff.setPhoneNumber(syncPhone);
            staffProfileRepository.save(staff);
        });

        User savedUser = userRepository.save(user);

        // Cập nhật Vai trò & Phân quyền nghiệp vụ chi tiết nếu form chỉnh sửa gửi lên
        if (dto.getRoleId() != null) {
            Role currentRole = (savedUser.getRoles() != null && !savedUser.getRoles().isEmpty())
                    ? savedUser.getRoles().iterator().next()
                    : null;
            Long dtoRoleIdLong = dto.getRoleId().longValue();
            boolean roleChanged = currentRole == null || !dtoRoleIdLong.equals(currentRole.getRoleId());
            boolean permissionsSubmitted = Boolean.TRUE.equals(dto.getCustomPermissionsSubmitted());

            if (roleChanged || permissionsSubmitted) {
                changeRole(userId, dto.getRoleId(), dto.getPermissionIds(), permissionsSubmitted);
                savedUser = getAccountById(userId);
            }
        }

        // Không cập nhật fullName tại đây.
        // Việc cập nhật họ tên phải thực hiện qua PatientProfileService.

        return savedUser;
    }

    // =========================================================
    // 5. ĐỔI ROLE
    // =========================================================

    @Override
    @Transactional
    public void changeRole(Long userId, Integer roleId) {
        changeRole(userId, roleId, null, false);
    }

    @Override
    @Transactional
    public void changeRole(
            Long userId,
            Integer roleId,
            List<Long> permissionIds,
            boolean customPermissionsSubmitted) {

        // 1. Tìm tài khoản cần thay đổi quyền
        User user = userRepository.findByIdWithRolesAndPermissions(userId)
                .or(() -> userRepository.findById(userId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Tài khoản không tồn tại."));

        // 2. Tìm role mới (kèm tập quyền hạn của role)
        if (roleId == null) {
            throw new IllegalArgumentException("Vui lòng chọn vai trò.");
        }

        Role selectedRole = roleRepository.findByIdWithPermissions(roleId.longValue())
                .or(() -> roleRepository.findById(roleId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Role không tồn tại."));

        // 3. Không cho phép gán role đã bị vô hiệu hóa
        if (!Boolean.TRUE.equals(selectedRole.getIsActive())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Role này hiện không hoạt động.");
        }

        // 3b. Bảo vệ tài khoản Quản trị viên hệ thống và tài khoản Bệnh nhân:
        if (hasAdminRole(user)) {
            throw new IllegalArgumentException(
                    "Tài khoản Quản trị viên hệ thống được cố định, không thể thay đổi vai trò.");
        }

        if (isAdminRole(selectedRole)) {
            throw new IllegalArgumentException(
                    "Không được phép cấp quyền Quản trị viên hệ thống cho tài khoản khác.");
        }

        if (isPatientAccount(user)) {
            throw new IllegalArgumentException(
                    "Tài khoản bệnh nhân được cố định, không thể chuyển đổi sang vai trò nhân sự.");
        }

        if (isPatientRole(selectedRole)) {
            throw new IllegalArgumentException(
                    "Không thể chuyển đổi tài khoản nhân sự sang vai trò Bệnh nhân.");
        }

        // 4. Xóa toàn bộ role cũ và chỉ gán một role mới
        user.getRoles().clear();
        user.getRoles().add(selectedRole);

        // 5. Đồng bộ loại tài khoản theo vai trò mới
        user.setUserType(resolveUserType(selectedRole));

        // 5b. Áp dụng tùy chỉnh quyền hạn chi tiết trong đúng tập quyền của vai trò mới
        applyUserPermissionCustomization(user, selectedRole, permissionIds, customPermissionsSubmitted);

        // 6. Lưu thay đổi trên tài khoản
        userRepository.save(user);

        // 7. Đồng bộ sang hồ sơ nhân sự & lưu lịch sử chuyển đổi vai trò công tác
        // (FT41)
        StaffProfile staff = staffProfileRepository.findByUser_UserId(userId).orElse(null);
        if (staff != null) {
            syncStaffRoleAssignmentHistory(staff, user, selectedRole);
        }
    }

    // =========================================================
    // 6. KHÓA / MỞ KHÓA ACCOUNT
    // =========================================================

    @Override
    public void toggleStatus(Long userId) {

        // Tìm tài khoản; nếu không tồn tại thì báo lỗi.
        User user = getAccountById(userId);

        if (hasAdminRole(user)) {
            throw new IllegalArgumentException(
                    "Không thể khóa tài khoản Quản trị viên hệ thống.");
        }

        // Đảo trạng thái hoạt động.
        // Tài khoản đang hoạt động -> bị khóa.
        // Tài khoản bị khóa -> được mở lại.
        boolean nowActive = !Boolean.TRUE.equals(user.getIsActive());

        if (nowActive) {
            staffProfileRepository.findByUser_UserId(userId).ifPresent(staff -> {
                if (staff.getStatus() == StaffStatus.INACTIVE) {
                    throw new IllegalArgumentException(
                            "Nhân sự " + staff.getStaffCode() + " đang ở trạng thái Đã nghỉ việc. "
                                    + "Vui lòng chuyển trạng thái làm việc sang Đang làm việc tại Quản lý nhân sự trước khi mở khóa tài khoản.");
                }
            });
        }

        user.setIsActive(nowActive);

        // Đồng bộ cột status với isActive để các truy vấn kiểm tra status hoạt động
        // đúng.
        user.setStatus(nowActive ? "ACTIVE" : "LOCKED");

        userRepository.save(user);
    }

    // =========================================================
    // HÀM HỖ TRỢ
    // =========================================================

    private boolean isAdminRole(Role role) {
        if (role == null || role.getRoleName() == null) {
            return false;
        }
        String name = role.getRoleName().trim();
        return name.equalsIgnoreCase("ADMIN") || name.equalsIgnoreCase("ROLE_ADMIN");
    }

    private boolean hasAdminRole(User user) {
        return user.getRoles() != null
                && user.getRoles().stream().anyMatch(this::isAdminRole);
    }

    private boolean isPatientRole(Role role) {
        if (role == null || role.getRoleName() == null) {
            return false;
        }
        String name = role.getRoleName().trim();
        return name.equalsIgnoreCase("BENH_NHAN")
                || name.equalsIgnoreCase("ROLE_BENH_NHAN")
                || name.equalsIgnoreCase("PATIENT")
                || name.equalsIgnoreCase("ROLE_PATIENT");
    }

    private boolean isPatientAccount(User user) {
        if (user == null) {
            return false;
        }
        boolean hasPatientProfiles = user.getPatientProfiles() != null && !user.getPatientProfiles().isEmpty();
        boolean hasPatientRole = user.getRoles() != null && user.getRoles().stream().anyMatch(this::isPatientRole);
        return hasPatientProfiles || hasPatientRole || user.getUserType() == UserType.PATIENT;
    }

    /**
     * Tùy chỉnh quyền hạn chi tiết cho từng tài khoản trong phạm vi tập quyền chuẩn
     * của Vai trò (Role):
     * - Chỉ cho phép chọn các quyền thuộc tập quyền của Role đã gán (giao tập hợp).
     * - Nếu chọn đủ 100% quyền của Role hoặc không bật tùy chỉnh riêng: đặt
     * customPermissionsEnabled = false (kế thừa mặc định từ Role).
     * - Nếu có bớt quyền trong tập quyền của Role: đặt customPermissionsEnabled =
     * true và lưu danh sách quyền được cấp vào user_permissions.
     */
    private void applyUserPermissionCustomization(
            User user,
            Role role,
            List<Long> permissionIds,
            boolean customPermissionsSubmitted) {
        if (user.getCustomPermissions() == null) {
            user.setCustomPermissions(new HashSet<>());
        }

        Set<Permission> rolePermissions = role.getPermissions() == null
                ? Set.of()
                : role.getPermissions().stream()
                        .filter(p -> p != null)
                        .collect(Collectors.toSet());

        if (!customPermissionsSubmitted
                || isAdminRole(role)
                || isPatientRole(role)
                || rolePermissions.isEmpty()) {
            user.setCustomPermissionsEnabled(false);
            user.getCustomPermissions().clear();
            return;
        }

        Set<Long> requestedIds = permissionIds == null
                ? Set.of()
                : permissionIds.stream()
                        .filter(id -> id != null)
                        .collect(Collectors.toSet());

        // Chỉ giữ lại các quyền thực sự nằm trong tập quyền của Role đã chọn
        Set<Permission> allowedSelected = rolePermissions.stream()
                .filter(p -> p.getPermissionId() != null && requestedIds.contains(p.getPermissionId()))
                .collect(Collectors.toSet());

        // Đảm bảo nguyên tắc "Quản lý/Thao tác bao hàm quyền Xem" (Write implies Read)
        Set<String> selectedCodes = allowedSelected.stream()
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
            if (selectedCodes.contains(entry.getKey()) && !selectedCodes.contains(entry.getValue())) {
                rolePermissions.stream()
                        .filter(p -> p.getPermissionCode() != null && p.getPermissionCode().equalsIgnoreCase(entry.getValue()))
                        .findFirst()
                        .ifPresent(allowedSelected::add);
            }
        }

        if (allowedSelected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Vui lòng tích chọn ít nhất 1 quyền hạn nghiệp vụ trong tập quyền của vai trò.");
        }

        if (allowedSelected.size() == rolePermissions.size()) {
            // Giống hệt tập quyền chuẩn của Role -> kế thừa mặc định theo Role
            user.setCustomPermissionsEnabled(false);
            user.getCustomPermissions().clear();
        } else {
            user.setCustomPermissionsEnabled(true);
            user.getCustomPermissions().clear();
            user.getCustomPermissions().addAll(allowedSelected);
        }
    }

    /**
     * Đồng bộ lịch sử chuyển đổi vai trò trong bảng phân công công tác
     * (staff_branch_assignments):
     * - Đóng phân công với vai trò cũ (status = ENDED, endDate = hôm nay) để giữ
     * nguyên lịch sử công tác.
     * - Tạo mới (hoặc kích hoạt lại nếu đổi qua lại trong cùng ngày) phân công
     * ACTIVE với vai trò mới.
     */
    private void syncStaffRoleAssignmentHistory(StaffProfile staff, User user, Role newRole) {
        List<StaffBranchAssignment> activeAssignments = staffBranchAssignmentRepository
                .findByStaffStaffIdAndStatus(staff.getStaffId(), AssignmentStatus.ACTIVE);
        LocalDate today = LocalDate.now();

        if (!activeAssignments.isEmpty()) {
            for (StaffBranchAssignment currentAssign : activeAssignments) {
                if (currentAssign.getRole() != null
                        && currentAssign.getRole().getRoleId() != null
                        && currentAssign.getRole().getRoleId().equals(newRole.getRoleId())) {
                    continue;
                }

                boolean wasPrimary = Boolean.TRUE.equals(currentAssign.getIsPrimary());
                Branch branch = currentAssign.getBranch();

                // Đóng phân công vai trò cũ để bảo toàn lịch sử
                currentAssign.setStatus(AssignmentStatus.ENDED);
                currentAssign.setIsPrimary(false);
                if (currentAssign.getStartDate() != null && currentAssign.getStartDate().isAfter(today)) {
                    currentAssign.setEndDate(currentAssign.getStartDate());
                } else if (currentAssign.getEndDate() == null || currentAssign.getEndDate().isAfter(today)) {
                    currentAssign.setEndDate(today);
                }
                staffBranchAssignmentRepository.saveAndFlush(currentAssign);

                // Tạo hoặc kích hoạt lại phân công với vai trò mới tại cùng chi nhánh (đảm bảo
                // không vi phạm uk_sba)
                upsertActiveAssignmentForRole(staff, branch, newRole, wasPrimary, today);
            }
        } else if (user.getBranch() != null && Boolean.TRUE.equals(user.getBranch().getIsActive())) {
            upsertActiveAssignmentForRole(staff, user.getBranch(), newRole, true, today);
        }
    }

    private void upsertActiveAssignmentForRole(
            StaffProfile staff,
            Branch branch,
            Role newRole,
            boolean isPrimary,
            LocalDate today) {
        StaffBranchAssignment sameDayExisting = staffBranchAssignmentRepository
                .findByStaffStaffIdAndBranchBranchIdAndRoleRoleIdAndStartDate(
                        staff.getStaffId(),
                        branch.getBranchId(),
                        newRole.getRoleId(),
                        today)
                .orElse(null);

        if (sameDayExisting != null) {
            sameDayExisting.setStatus(AssignmentStatus.ACTIVE);
            sameDayExisting.setEndDate(null);
            sameDayExisting.setIsPrimary(isPrimary);
            staffBranchAssignmentRepository.save(sameDayExisting);
        } else {
            StaffBranchAssignment newAssign = new StaffBranchAssignment();
            newAssign.setStaff(staff);
            newAssign.setBranch(branch);
            newAssign.setRole(newRole);
            newAssign.setIsPrimary(isPrimary);
            newAssign.setStartDate(today);
            newAssign.setEndDate(null);
            newAssign.setStatus(AssignmentStatus.ACTIVE);
            staffBranchAssignmentRepository.save(newAssign);
        }
    }

    // BENH_NHAN -> PATIENT; các vai trò còn lại (nhân sự, quản trị) -> STAFF.
    private UserType resolveUserType(Role role) {
        String name = role.getRoleName() == null
                ? ""
                : role.getRoleName().trim().toUpperCase();

        return name.equals("BENH_NHAN") || name.equals("ROLE_BENH_NHAN")
                ? UserType.PATIENT
                : UserType.STAFF;
    }

    // Trả về chi nhánh theo ID; null nếu không chọn. Chỉ cho phép chi nhánh đang
    // hoạt động.
    private Branch resolveBranch(Long branchId) {
        if (branchId == null) {
            return null;
        }

        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Chi nhánh không tồn tại."));

        if (!Boolean.TRUE.equals(branch.getIsActive())) {
            throw new IllegalArgumentException(
                    "Chi nhánh này đang ngừng hoạt động.");
        }

        return branch;
    }

}