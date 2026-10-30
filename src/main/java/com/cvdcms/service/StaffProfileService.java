package com.cvdcms.service;

import com.cvdcms.DTO.LinkableUserDTO;
import com.cvdcms.DTO.StaffAssignmentDTO;
import com.cvdcms.DTO.StaffFormDTO;
import com.cvdcms.entity.Branch;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Nghiệp vụ Quản lý hồ sơ nhân viên & Phân công chi nhánh - FT41 (UC-03).
@Service
public class StaffProfileService {

    private static final String ROLE_DOCTOR = "BAC_SI";
    private static final String ROLE_PATIENT = "BENH_NHAN";
    private static final String ROLE_ADMIN = "ADMIN";

    private static final Pattern PHONE_PATTERN = Pattern.compile("^(\\+84|0)[0-9]{9,10}$");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("^([0-9]{9}|[0-9]{12})$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final StaffProfileRepository staffRepository;
    private final StaffBranchAssignmentRepository assignmentRepository;
    private final BranchRepository branchRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public StaffProfileService(
            StaffProfileRepository staffRepository,
            StaffBranchAssignmentRepository assignmentRepository,
            BranchRepository branchRepository,
            RoleRepository roleRepository,
            UserRepository userRepository) {
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.branchRepository = branchRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
    }

    // ===================== TRA CỨU (SCR-41.1, 41.4) =====================

    // Tìm kiếm theo Mã NV / Họ tên / CCCD / SĐT và lọc theo chuyên khoa, trạng thái, chi nhánh.
    @Transactional(readOnly = true)
    public List<StaffProfile> searchStaff(String keyword, String specialty, StaffStatus status, Long branchId) {
        String kw = trimToNull(keyword);
        String kwLower = kw == null ? null : kw.toLowerCase(Locale.ROOT);
        String spec = trimToNull(specialty);

        Set<Long> staffInBranch = null;
        if (branchId != null) {
            staffInBranch = new HashSet<>();
            for (StaffBranchAssignment a : assignmentRepository.findByBranchAndStatus(branchId, AssignmentStatus.ACTIVE)) {
                staffInBranch.add(a.getStaff().getStaffId());
            }
        }

        List<StaffProfile> result = new ArrayList<>();
        for (StaffProfile s : staffRepository.findAllByOrderByStaffCodeAsc()) {
            // Loại bỏ hoàn toàn hồ sơ gắn với tài khoản Admin hoặc mang vai trò Quản trị hệ thống (dữ liệu test cũ)
            if (s.getUser() != null && isPureAdminUser(s.getUser())) {
                continue;
            }
            boolean hasAdminAssignment = assignmentRepository.findByStaffId(s.getStaffId()).stream()
                    .anyMatch(a -> a.getRole() != null && (ROLE_ADMIN.equalsIgnoreCase(a.getRole().getRoleName())
                            || "ROLE_ADMIN".equalsIgnoreCase(a.getRole().getRoleName())));
            if (hasAdminAssignment) {
                continue;
            }

            if (kwLower != null && !matchesKeyword(s, kwLower)) {
                continue;
            }
            if (spec != null && !spec.equalsIgnoreCase(s.getSpecialty())) {
                continue;
            }
            if (status != null && s.getStatus() != status) {
                continue;
            }
            if (staffInBranch != null && !staffInBranch.contains(s.getStaffId())) {
                continue;
            }
            result.add(s);
        }
        return result;
    }

    private boolean matchesKeyword(StaffProfile s, String kwLower) {
        return contains(s.getStaffCode(), kwLower)
                || contains(s.getFullName(), kwLower)
                || contains(s.getIdCardNumber(), kwLower)
                || contains(s.getPhoneNumber(), kwLower)
                || contains(s.getEmail(), kwLower);
    }

    private boolean contains(String value, String kwLower) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(kwLower);
    }

    @Transactional(readOnly = true)
    public StaffProfile getStaffById(Long staffId) {
        return staffRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hồ sơ nhân viên."));
    }

    @Transactional(readOnly = true)
    public List<String> getSpecialties() {
        return staffRepository.findDistinctSpecialties();
    }

    // Các phân công đang hiệu lực gom theo nhân viên - để hiển thị chi nhánh/vai trò trong danh sách.
    @Transactional(readOnly = true)
    public Map<Long, List<StaffBranchAssignment>> getActiveAssignmentsByStaff() {
        Map<Long, List<StaffBranchAssignment>> map = new LinkedHashMap<>();
        for (StaffBranchAssignment a : assignmentRepository.findAllByStatus(AssignmentStatus.ACTIVE)) {
            map.computeIfAbsent(a.getStaff().getStaffId(), k -> new ArrayList<>()).add(a);
        }
        // Chi nhánh chính luôn đứng đầu.
        map.values().forEach(list -> list.sort((x, y) ->
                Boolean.compare(Boolean.TRUE.equals(y.getIsPrimary()), Boolean.TRUE.equals(x.getIsPrimary()))));
        return map;
    }

    @Transactional(readOnly = true)
    public List<StaffBranchAssignment> getAssignments(Long staffId) {
        return assignmentRepository.findByStaffId(staffId);
    }

    // Bác sĩ/nhân viên đang công tác tại chi nhánh - dành cho các phân hệ khác (đặt lịch, phân công khám).
    @Transactional(readOnly = true)
    public List<StaffBranchAssignment> getActiveStaffByBranch(Long branchId) {
        return assignmentRepository.findByBranchAndStatus(branchId, AssignmentStatus.ACTIVE);
    }

    // ===================== DỮ LIỆU CHO FORM =====================

    // Vai trò có thể phân công cho nhân sự (loại bỏ vai trò Bệnh nhân, Quản trị hệ thống và vai trò ngừng hoạt động).
    @Transactional(readOnly = true)
    public List<Role> getAssignableRoles() {
        List<Role> roles = new ArrayList<>();
        for (Role r : roleRepository.findAll()) {
            if (Boolean.TRUE.equals(r.getIsActive())
                    && !isPatientOrAdminRoleName(r.getRoleName())) {
                roles.add(r);
            }
        }
        return roles;
    }

    private boolean isPatientOrAdminRoleName(String roleName) {
        if (roleName == null) {
            return true;
        }
        String name = roleName.trim();
        return ROLE_PATIENT.equalsIgnoreCase(name)
                || "ROLE_BENH_NHAN".equalsIgnoreCase(name)
                || "PATIENT".equalsIgnoreCase(name)
                || "ROLE_PATIENT".equalsIgnoreCase(name)
                || ROLE_ADMIN.equalsIgnoreCase(name)
                || "ROLE_ADMIN".equalsIgnoreCase(name);
    }

    // Xác định vai trò hiện tại của nhân sự: ưu tiên phân công ACTIVE -> vai trò trên tài khoản liên kết -> lịch sử phân công gần nhất.
    @Transactional(readOnly = true)
    public Role resolveCurrentStaffRole(StaffProfile staff, List<StaffBranchAssignment> activeAssignments) {
        if (activeAssignments != null && !activeAssignments.isEmpty()) {
            Role primaryActive = activeAssignments.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getIsPrimary()) && a.getRole() != null)
                    .map(StaffBranchAssignment::getRole)
                    .findFirst()
                    .orElse(null);
            if (primaryActive != null) {
                return primaryActive;
            }
            Role anyActive = activeAssignments.stream()
                    .map(StaffBranchAssignment::getRole)
                    .filter(r -> r != null)
                    .findFirst()
                    .orElse(null);
            if (anyActive != null) {
                return anyActive;
            }
        }
        if (staff != null && staff.getUser() != null && staff.getUser().getRoles() != null) {
            Role userRole = staff.getUser().getRoles().stream()
                    .filter(r -> r != null && !isPatientOrAdminRoleName(r.getRoleName()))
                    .findFirst()
                    .orElse(null);
            if (userRole != null) {
                return userRole;
            }
        }
        if (staff != null && staff.getStaffId() != null) {
            return assignmentRepository.findByStaffId(staff.getStaffId()).stream()
                    .map(StaffBranchAssignment::getRole)
                    .filter(r -> r != null && !isPatientOrAdminRoleName(r.getRoleName()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    // Tài khoản chưa gắn hồ sơ nhân sự nào (loại bỏ tài khoản Bệnh nhân thuần và Admin thuần).
    // currentUserId: tài khoản đang gắn với hồ sơ đang sửa - vẫn phải được chọn lại.
    @Transactional(readOnly = true)
    public List<LinkableUserDTO> getLinkableUsers(Long currentUserId) {
        Set<Long> linked = new HashSet<>();
        for (StaffProfile s : staffRepository.findAll()) {
            if (s.getUser() != null) {
                linked.add(s.getUser().getUserId());
            }
        }

        List<LinkableUserDTO> users = new ArrayList<>();
        for (User u : userRepository.findAll()) {
            boolean isCurrent = currentUserId != null && currentUserId.equals(u.getUserId());
            if (linked.contains(u.getUserId()) && !isCurrent) {
                continue;
            }
            if (!isCurrent && u.getPatientProfiles() != null && !u.getPatientProfiles().isEmpty()) {
                continue;
            }
            boolean hasStaffRole = u.getRoles().stream()
                    .anyMatch(r -> !ROLE_PATIENT.equalsIgnoreCase(r.getRoleName())
                            && !ROLE_ADMIN.equalsIgnoreCase(r.getRoleName())
                            && !"ROLE_ADMIN".equalsIgnoreCase(r.getRoleName()));
            if (hasStaffRole || isCurrent) {
                String roleIds = u.getRoles().stream()
                        .map(r -> String.valueOf(r.getRoleId()))
                        .collect(Collectors.joining(","));
                String roleNames = u.getRoles().stream()
                        .map(Role::getRoleName)
                        .collect(Collectors.joining(","));
                String roleDisplayNames = u.getRoles().stream()
                        .map(r -> r.getDisplayName() != null ? r.getDisplayName() : r.getRoleName())
                        .collect(Collectors.joining(", "));

                String display = u.getUsername() + " (" + u.getEmail() + ")" + (roleDisplayNames.isBlank() ? "" : " - " + roleDisplayNames);
                users.add(new LinkableUserDTO(u.getUserId(), u.getUsername(), u.getEmail(), roleIds, roleNames, display));
            }
        }
        return users;
    }

    private boolean isPureAdminUser(User user) {
        if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
            return false;
        }
        return user.getRoles().stream()
                .allMatch(r -> ROLE_ADMIN.equalsIgnoreCase(r.getRoleName()) || "ROLE_ADMIN".equalsIgnoreCase(r.getRoleName()));
    }

    // ===================== THÊM / SỬA HỒ SƠ (SCR-41.2) =====================

    @Transactional
    public StaffProfile createStaff(StaffFormDTO dto) {
        validateProfile(dto, null);

        Branch branch = branchRepository.findById(requireId(dto.getBranchId(), "Chi nhánh chính"))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        Role role = findRole(requireId(dto.getRoleId(), "Vai trò"));

        if (ROLE_ADMIN.equalsIgnoreCase(role.getRoleName()) || "ROLE_ADMIN".equalsIgnoreCase(role.getRoleName())) {
            throw new IllegalArgumentException("Không thể tạo hồ sơ nhân viên cho vai trò Quản trị hệ thống (Admin).");
        }

        boolean isDoctor = ROLE_DOCTOR.equalsIgnoreCase(role.getRoleName());
        if (isDoctor && trimToNull(dto.getPracticeLicenseNo()) == null) {
            throw new IllegalArgumentException("Bác sĩ bắt buộc phải có số chứng chỉ hành nghề (CCHN).");
        }

        StaffProfile staff = new StaffProfile();

        String code = trimToNull(dto.getStaffCode());
        if (code == null) {
            code = generateStaffCode(role);
        } else {
            code = code.toUpperCase(Locale.ROOT);
            if (staffRepository.existsByStaffCode(code)) {
                throw new IllegalArgumentException("Mã nhân viên '" + code + "' đã tồn tại.");
            }
        }
        staff.setStaffCode(code);

        applyProfile(staff, dto, isDoctor);

        User user = resolveLinkedUser(dto.getUserId(), null, role);
        syncContactBetweenStaffAndUser(staff, user);
        staff.setUser(user);

        StaffProfile saved = staffRepository.save(staff);

        // Phân công chi nhánh chính ban đầu.
        StaffBranchAssignment assignment = new StaffBranchAssignment();
        assignment.setStaff(saved);
        assignment.setBranch(branch);
        assignment.setRole(role);
        assignment.setIsPrimary(true);
        assignment.setStartDate(dto.getHireDate() != null ? dto.getHireDate() : LocalDate.now());
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignmentRepository.save(assignment);

        // Đồng bộ tài khoản đăng nhập: là tài khoản nhân viên, thuộc chi nhánh chính, vai trò chuyên môn và trạng thái hoạt động.
        if (user != null) {
            syncUserStatusWithStaff(user, saved.getStatus());
            syncUserWithStaff(user, branch, role);
        }
        return saved;
    }

    @Transactional
    public StaffProfile updateStaff(Long staffId, StaffFormDTO dto) {
        StaffProfile staff = getStaffById(staffId);
        validateProfile(dto, staff);

        List<StaffBranchAssignment> activeAssignments =
                assignmentRepository.findByStaffStaffIdAndStatus(staffId, AssignmentStatus.ACTIVE);

        Role fixedRole = resolveCurrentStaffRole(staff, activeAssignments);
        boolean isDoctor = activeAssignments.stream()
                .anyMatch(a -> a.getRole() != null && ROLE_DOCTOR.equalsIgnoreCase(a.getRole().getRoleName()))
                || (fixedRole != null && ROLE_DOCTOR.equalsIgnoreCase(fixedRole.getRoleName()));

        // Nếu nhân viên đang là bác sĩ thì bắt buộc có CCHN.
        if (isDoctor && trimToNull(dto.getPracticeLicenseNo()) == null) {
            throw new IllegalArgumentException("Bác sĩ bắt buộc phải có số chứng chỉ hành nghề (CCHN).");
        }

        boolean isFixedDoctor = isDoctor;
        applyProfile(staff, dto, isFixedDoctor);

        User previousUser = staff.getUser();
        Long currentUserId = previousUser != null ? previousUser.getUserId() : null;
        User user = resolveLinkedUser(dto.getUserId(), staff, fixedRole);

        // Nếu gỡ tài khoản cũ hoặc đổi sang tài khoản khác -> giải phóng chi nhánh trên tài khoản cũ.
        if (previousUser != null && (user == null || !previousUser.getUserId().equals(user.getUserId()))) {
            previousUser.setBranch(null);
            userRepository.save(previousUser);
        }

        syncContactBetweenStaffAndUser(staff, user);
        staff.setUser(user);
        StaffProfile saved = staffRepository.save(staff);

        // Đồng bộ tài khoản liên kết theo chi nhánh chính hiện tại, vai trò chuyên môn và trạng thái làm việc.
        if (user != null) {
            syncUserStatusWithStaff(user, saved.getStatus());
            if (!user.getUserId().equals(currentUserId)) {
                StaffBranchAssignment primary = activeAssignments.stream()
                        .filter(a -> Boolean.TRUE.equals(a.getIsPrimary()))
                        .findFirst()
                        .orElse(null);
                Branch primaryBranch = primary != null ? primary.getBranch() : null;
                Role primaryRole = primary != null ? primary.getRole() : fixedRole;
                syncUserWithStaff(user, primaryBranch, primaryRole);
            } else {
                userRepository.save(user);
            }
        }
        return saved;
    }

    // Đổi trạng thái làm việc: ACTIVE / INACTIVE.
    @Transactional
    public void changeStatus(Long staffId, StaffStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("Trạng thái không hợp lệ.");
        }
        StaffProfile staff = getStaffById(staffId);
        staff.setStatus(status);
        staffRepository.save(staff);

        if (staff.getUser() != null) {
            syncUserStatusWithStaff(staff.getUser(), status);
            userRepository.save(staff.getUser());
        }
    }

    // ===================== PHÂN CÔNG CHI NHÁNH (SCR-41.3) =====================

    @Transactional
    public StaffBranchAssignment addAssignment(Long staffId, StaffAssignmentDTO dto) {
        StaffProfile staff = getStaffById(staffId);

        Branch branch = branchRepository.findById(requireId(dto.getBranchId(), "Chi nhánh"))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));

        // Tự động lấy vai trò chuyên môn hiện tại của nhân sự này.
        List<StaffBranchAssignment> active =
                assignmentRepository.findByStaffStaffIdAndStatus(staffId, AssignmentStatus.ACTIVE);
        Role role = resolveCurrentStaffRole(staff, active);
        if (role == null) {
            role = findRole(requireId(dto.getRoleId(), "Vai trò"));
        }

        if (role != null && isPatientOrAdminRoleName(role.getRoleName())) {
            throw new IllegalArgumentException("Không thể phân công vai trò Quản trị hệ thống hoặc Bệnh nhân cho nhân sự chi nhánh.");
        }

        LocalDate start = dto.getStartDate() != null ? dto.getStartDate() : LocalDate.now();
        LocalDate end = dto.getEndDate();
        if (end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("Ngày kết thúc phải sau hoặc bằng ngày bắt đầu.");
        }

        if (assignmentRepository.existsByStaffStaffIdAndBranchBranchIdAndRoleRoleIdAndStatus(
                staffId, branch.getBranchId(), role.getRoleId(), AssignmentStatus.ACTIVE)) {
            throw new IllegalArgumentException("Nhân viên đã được phân công vai trò này tại chi nhánh này.");
        }

        if (ROLE_DOCTOR.equalsIgnoreCase(role.getRoleName()) && trimToNull(staff.getPracticeLicenseNo()) == null) {
            throw new IllegalArgumentException(
                    "Cần cập nhật số chứng chỉ hành nghề (CCHN) trong hồ sơ trước khi phân công vai trò Bác sĩ.");
        }

        boolean hasPrimary = active.stream().anyMatch(a -> Boolean.TRUE.equals(a.getIsPrimary()));
        boolean makePrimary = Boolean.TRUE.equals(dto.getIsPrimary()) || !hasPrimary;

        // Mỗi nhân viên chỉ có một chi nhánh chính đang hiệu lực.
        if (makePrimary) {
            for (StaffBranchAssignment a : active) {
                if (Boolean.TRUE.equals(a.getIsPrimary())) {
                    a.setIsPrimary(false);
                    assignmentRepository.save(a);
                }
            }
        }

        StaffBranchAssignment assignment = assignmentRepository
                .findByStaffStaffIdAndBranchBranchIdAndRoleRoleIdAndStartDate(
                        staffId, branch.getBranchId(), role.getRoleId(), start)
                .orElseGet(StaffBranchAssignment::new);
        assignment.setStaff(staff);
        assignment.setBranch(branch);
        assignment.setRole(role);
        assignment.setIsPrimary(makePrimary);
        assignment.setStartDate(start);
        assignment.setEndDate(end);
        assignment.setStatus(end != null && end.isBefore(LocalDate.now())
                ? AssignmentStatus.ENDED : AssignmentStatus.ACTIVE);
        StaffBranchAssignment saved = assignmentRepository.save(assignment);

        if (staff.getUser() != null) {
            syncUserWithStaff(staff.getUser(), makePrimary ? branch : null, role);
        }
        return saved;
    }

    // Kết thúc phân công (không xóa - giữ lịch sử điều động).
    @Transactional
    public void endAssignment(Long staffId, Long assignmentId) {
        StaffBranchAssignment assignment = getAssignmentOfStaff(staffId, assignmentId);
        if (assignment.getStatus() == AssignmentStatus.ENDED) {
            throw new IllegalArgumentException("Phân công này đã kết thúc.");
        }

        boolean wasPrimary = Boolean.TRUE.equals(assignment.getIsPrimary());
        assignment.setStatus(AssignmentStatus.ENDED);
        assignment.setIsPrimary(false);
        LocalDate today = LocalDate.now();
        if (assignment.getEndDate() == null || assignment.getEndDate().isAfter(today)) {
            assignment.setEndDate(today);
        }
        assignmentRepository.save(assignment);

        // Nếu vừa kết thúc chi nhánh chính -> tự chuyển chi nhánh chính sang phân công còn hiệu lực sớm nhất (hoặc xóa chi nhánh trên tài khoản nếu hết phân công).
        if (wasPrimary) {
            reassignNextPrimaryOrClearBranch(staffId, assignment.getStaff());
        }
    }

    // Cập nhật phân công chi nhánh (ngày bắt đầu, kết thúc, đặt làm chính).
    @Transactional
    public StaffBranchAssignment updateAssignment(Long staffId, Long assignmentId, StaffAssignmentDTO dto) {
        StaffBranchAssignment assignment = getAssignmentOfStaff(staffId, assignmentId);

        LocalDate start = dto.getStartDate() != null ? dto.getStartDate() : assignment.getStartDate();
        LocalDate end = dto.getEndDate();
        if (start != null && end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("Ngày kết thúc phải sau hoặc bằng ngày bắt đầu.");
        }

        assignment.setStartDate(start);
        assignment.setEndDate(end);

        LocalDate today = LocalDate.now();
        boolean wasPrimary = Boolean.TRUE.equals(assignment.getIsPrimary());
        if (end != null && end.isBefore(today)) {
            assignment.setStatus(AssignmentStatus.ENDED);
            assignment.setIsPrimary(false);
            StaffBranchAssignment saved = assignmentRepository.save(assignment);
            if (wasPrimary) {
                reassignNextPrimaryOrClearBranch(staffId, assignment.getStaff());
            }
            return saved;
        } else {
            assignment.setStatus(AssignmentStatus.ACTIVE);
            if (Boolean.TRUE.equals(dto.getIsPrimary())) {
                assignment.setIsPrimary(true);
                for (StaffBranchAssignment a : assignmentRepository.findByStaffStaffIdAndStatus(staffId, AssignmentStatus.ACTIVE)) {
                    if (!a.getAssignmentId().equals(assignmentId) && Boolean.TRUE.equals(a.getIsPrimary())) {
                        a.setIsPrimary(false);
                        assignmentRepository.save(a);
                    }
                }
                if (assignment.getStaff().getUser() != null) {
                    syncUserWithStaff(assignment.getStaff().getUser(), assignment.getBranch());
                }
            }
        }

        return assignmentRepository.save(assignment);
    }

    // Đặt một phân công đang hiệu lực làm chi nhánh chính.
    @Transactional
    public void setPrimaryAssignment(Long staffId, Long assignmentId) {
        StaffBranchAssignment target = getAssignmentOfStaff(staffId, assignmentId);
        if (target.getStatus() != AssignmentStatus.ACTIVE) {
            throw new IllegalArgumentException("Chỉ có thể đặt chi nhánh chính cho phân công đang hiệu lực.");
        }

        for (StaffBranchAssignment a : assignmentRepository.findByStaffStaffIdAndStatus(staffId, AssignmentStatus.ACTIVE)) {
            boolean isTarget = a.getAssignmentId().equals(assignmentId);
            if (Boolean.TRUE.equals(a.getIsPrimary()) != isTarget) {
                a.setIsPrimary(isTarget);
                assignmentRepository.save(a);
            }
        }

        StaffProfile staff = target.getStaff();
        if (staff.getUser() != null) {
            syncUserWithStaff(staff.getUser(), target.getBranch());
        }
    }

    // ===================== HÀM HỖ TRỢ =====================

    private StaffBranchAssignment getAssignmentOfStaff(Long staffId, Long assignmentId) {
        StaffBranchAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân công."));
        if (!assignment.getStaff().getStaffId().equals(staffId)) {
            throw new IllegalArgumentException("Phân công không thuộc nhân viên này.");
        }
        return assignment;
    }

    private Role findRole(Long roleId) {
        return roleRepository.findAll().stream()
                .filter(r -> r.getRoleId() != null && r.getRoleId().equals(roleId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Vai trò không tồn tại."));
    }

    // Tài khoản liên kết: phải tồn tại, chưa gắn với hồ sơ khác, không phải Admin/Bệnh nhân và khớp vai trò chuyên môn.
    private User resolveLinkedUser(Long userId, StaffProfile current, Role expectedRole) {
        if (userId == null) {
            return null;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Tài khoản liên kết không tồn tại."));

        staffRepository.findByUserUserId(userId).ifPresent(other -> {
            if (current == null || !other.getStaffId().equals(current.getStaffId())) {
                throw new IllegalArgumentException(
                        "Tài khoản '" + user.getUsername() + "' đã được gắn với hồ sơ nhân viên " + other.getStaffCode() + ".");
            }
        });

        if (isPureAdminUser(user)) {
            throw new IllegalArgumentException("Tài khoản Quản trị hệ thống ('" + user.getUsername() + "') không thuộc đối tượng nhân sự phòng khám.");
        }

        boolean isCurrentLinked = current != null && current.getUser() != null
                && userId.equals(current.getUser().getUserId());

        if (!isCurrentLinked && user.getPatientProfiles() != null && !user.getPatientProfiles().isEmpty()) {
            throw new IllegalArgumentException("Tài khoản '" + user.getUsername() + "' đã có hồ sơ bệnh nhân nên không thể gắn cho nhân sự.");
        }

        if (!isCurrentLinked && expectedRole != null && user.getRoles() != null && !user.getRoles().isEmpty()) {
            boolean roleMatches = user.getRoles().stream()
                    .anyMatch(r -> r.getRoleId() != null && r.getRoleId().equals(expectedRole.getRoleId()));
            if (!roleMatches) {
                throw new IllegalArgumentException(
                        "Vai trò của tài khoản '" + user.getUsername() + "' không khớp với vai trò chuyên môn của hồ sơ nhân sự.");
            }
        }

        return user;
    }

    // Đồng bộ 2 chiều Email và Số điện thoại giữa Hồ sơ nhân sự và Tài khoản liên kết.
    private void syncContactBetweenStaffAndUser(StaffProfile staff, User user) {
        if (user == null) {
            return;
        }

        // Email: nếu hồ sơ nhân sự để trống -> lấy từ tài khoản; nếu có nhập -> cập nhật sang tài khoản (kiểm tra trùng).
        if (staff.getEmail() == null) {
            staff.setEmail(user.getEmail());
        } else {
            String normalizedEmail = staff.getEmail().trim().toLowerCase(Locale.ROOT);
            staff.setEmail(normalizedEmail);
            userRepository.findByEmail(normalizedEmail)
                    .filter(existing -> !existing.getUserId().equals(user.getUserId()))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("Email '" + normalizedEmail + "' đã được sử dụng bởi tài khoản khác.");
                    });
            user.setEmail(normalizedEmail);
        }

        // Số điện thoại: nếu hồ sơ nhân sự để trống -> lấy từ tài khoản; nếu có nhập -> cập nhật sang tài khoản (kiểm tra trùng).
        if (staff.getPhoneNumber() == null) {
            staff.setPhoneNumber(user.getPhoneNumber());
        } else {
            String normalizedPhone = staff.getPhoneNumber().trim();
            staff.setPhoneNumber(normalizedPhone);
            userRepository.findByPhoneNumber(normalizedPhone)
                    .filter(existing -> !existing.getUserId().equals(user.getUserId()))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("Số điện thoại '" + normalizedPhone + "' đã được sử dụng bởi tài khoản khác.");
                    });
            user.setPhoneNumber(normalizedPhone);
        }
    }

    // Đồng bộ trạng thái làm việc của nhân sự sang trạng thái khóa/mở khóa tài khoản đăng nhập.
    private void syncUserStatusWithStaff(User user, StaffStatus status) {
        if (user == null || status == null) {
            return;
        }
        if (status == StaffStatus.INACTIVE) {
            user.setIsActive(false);
            user.setStatus("LOCKED");
        } else if (status == StaffStatus.ACTIVE) {
            user.setIsActive(true);
            user.setStatus("ACTIVE");
        }
    }

    // Chuyển chi nhánh chính sang phân công đang hiệu lực sớm nhất, hoặc xóa chi nhánh trên tài khoản nếu hết phân công.
    private void reassignNextPrimaryOrClearBranch(Long staffId, StaffProfile staff) {
        StaffBranchAssignment next = assignmentRepository.findByStaffStaffIdAndStatus(staffId, AssignmentStatus.ACTIVE).stream()
                .min((x, y) -> x.getStartDate().compareTo(y.getStartDate()))
                .orElse(null);

        if (next != null) {
            next.setIsPrimary(true);
            assignmentRepository.save(next);
            if (staff != null && staff.getUser() != null) {
                syncUserWithStaff(staff.getUser(), next.getBranch());
            }
        } else if (staff != null && staff.getUser() != null) {
            User user = staff.getUser();
            user.setBranch(null);
            userRepository.save(user);
        }
    }

    // Tài khoản gắn với nhân sự -> loại STAFF và thuộc chi nhánh chính.
    private void syncUserWithStaff(User user, Branch primaryBranch) {
        syncUserWithStaff(user, primaryBranch, null);
    }

    private void syncUserWithStaff(User user, Branch primaryBranch, Role role) {
        user.setUserType(UserType.STAFF);
        if (primaryBranch != null) {
            user.setBranch(primaryBranch);
        }
        if (role != null) {
            user.getRoles().clear();
            user.getRoles().add(role);
        }
        userRepository.save(user);
    }

    private String generateStaffCode(Role role) {
        String prefix = role != null && ROLE_DOCTOR.equalsIgnoreCase(role.getRoleName()) ? "BS" : "NV";
        long n = staffRepository.count() + 1;
        String code;
        do {
            code = prefix + String.format("%04d", n++);
        } while (staffRepository.existsByStaffCode(code));
        return code;
    }

    // Kiểm tra dữ liệu hành chính / chuyên môn / lương và chống trùng CCCD, CCHN.
    private void validateProfile(StaffFormDTO dto, StaffProfile current) {
        if (trimToNull(dto.getFullName()) == null) {
            throw new IllegalArgumentException("Họ tên không được để trống.");
        }

        String phone = trimToNull(dto.getPhoneNumber());
        if (phone != null && !PHONE_PATTERN.matcher(phone).matches()) {
            throw new IllegalArgumentException("Số điện thoại không hợp lệ (10-11 số, ví dụ 0901234567).");
        }

        String email = trimToNull(dto.getEmail());
        if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Email không hợp lệ.");
        }

        String idCard = trimToNull(dto.getIdCardNumber());
        if (idCard != null) {
            if (!ID_CARD_PATTERN.matcher(idCard).matches()) {
                throw new IllegalArgumentException("CCCD/CMND phải gồm 9 hoặc 12 chữ số.");
            }
            staffRepository.findByIdCardNumber(idCard).ifPresent(other -> {
                if (current == null || !other.getStaffId().equals(current.getStaffId())) {
                    throw new IllegalArgumentException("CCCD '" + idCard + "' đã thuộc hồ sơ " + other.getStaffCode() + ".");
                }
            });
        }

        String license = trimToNull(dto.getPracticeLicenseNo());
        if (license != null) {
            staffRepository.findByPracticeLicenseNo(license).ifPresent(other -> {
                if (current == null || !other.getStaffId().equals(current.getStaffId())) {
                    throw new IllegalArgumentException("Số CCHN '" + license + "' đã thuộc hồ sơ " + other.getStaffCode() + ".");
                }
            });
        }

        if (dto.getDob() != null && !dto.getDob().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Ngày sinh phải nằm trong quá khứ.");
        }

        checkNotNegative(dto.getBaseSalary(), "Lương cơ bản");
        checkNotNegative(dto.getAllowanceAmount(), "Phụ cấp");
        checkNotNegative(dto.getInsuranceSalary(), "Lương đóng BHXH");
    }

    private void checkNotNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(label + " không được âm.");
        }
    }

    // Chép dữ liệu form vào entity (không đụng tới mã nhân viên & tài khoản liên kết).
    private void applyProfile(StaffProfile staff, StaffFormDTO dto, boolean isDoctor) {
        staff.setFullName(dto.getFullName().trim());
        staff.setGender(dto.getGender());
        staff.setDob(dto.getDob());
        staff.setPhoneNumber(trimToNull(dto.getPhoneNumber()));
        staff.setEmail(trimToNull(dto.getEmail()));
        staff.setIdCardNumber(trimToNull(dto.getIdCardNumber()));
        staff.setDepartment(trimToNull(dto.getDepartment()));
        staff.setPositionTitle(trimToNull(dto.getPositionTitle()));
        staff.setSpecialty(isDoctor ? trimToNull(dto.getSpecialty()) : null);
        staff.setPracticeLicenseNo(isDoctor ? trimToNull(dto.getPracticeLicenseNo()) : null);
        staff.setMohDoctorCode(isDoctor ? trimToNull(dto.getMohDoctorCode()) : null);
        staff.setBaseSalary(dto.getBaseSalary() != null ? dto.getBaseSalary() : BigDecimal.ZERO);
        staff.setAllowanceAmount(dto.getAllowanceAmount() != null ? dto.getAllowanceAmount() : BigDecimal.ZERO);
        staff.setInsuranceSalary(dto.getInsuranceSalary() != null ? dto.getInsuranceSalary() : BigDecimal.ZERO);
        staff.setBankAccountNumber(trimToNull(dto.getBankAccountNumber()));
        staff.setBankName(trimToNull(dto.getBankName()));
        staff.setHireDate(dto.getHireDate());
        staff.setStatus(dto.getStatus() != null ? dto.getStatus() : StaffStatus.ACTIVE);
    }

    private Long requireId(Long id, String label) {
        if (id == null) {
            throw new IllegalArgumentException(label + " không được để trống.");
        }
        return id;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
