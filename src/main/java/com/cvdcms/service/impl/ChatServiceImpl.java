package com.cvdcms.service.impl;

import com.cvdcms.DTO.ChatConversationDTO;
import com.cvdcms.DTO.ChatMessageDTO;
import com.cvdcms.DTO.ChatSendRequestDTO;
import com.cvdcms.DTO.ChatUserSearchItemDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.ChatChannel;
import com.cvdcms.entity.enums.ChatStatus;
import com.cvdcms.entity.enums.SenderType;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.UserType;
import com.cvdcms.repository.*;
import com.cvdcms.service.ChatService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Triển khai nghiệp vụ Realtime Chat theo Cơ sở cho CV-DCMS.
 * Quy tắc phân quyền cốt lõi:
 * 1. Chỉ duy nhất ADMIN được quyền xem và trả lời tin nhắn của tất cả các cơ sở.
 * 2. Các vai trò nhân viên khác (LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN) được phân ở cơ sở nào thì CHỈ được xem và nhắn tin trong phạm vi cơ sở đó.
 * 3. Người nhận được tin nhắn của Bệnh nhân (BENH_NHAN) để tiếp nhận và phản hồi CHỈ bao gồm: Lễ tân (LE_TAN), Bác sĩ (BAC_SI) cùng cơ sở và Admin (ADMIN).
 *    Quản lý kho (QUAN_LY_KHO) và Kế toán (KE_TOAN) không được xem hoặc phản hồi tin nhắn của Bệnh nhân (chỉ nhắn tin nội bộ với các tài khoản nhân viên cùng cơ sở).
 * 4. Các vai trò ngoài Bệnh nhân có thể tìm kiếm tài khoản trong cùng cơ sở để gửi tin nhắn trực tiếp.
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final CustomerCareTaskRepository customerCareTaskRepository;

    // Bộ nhớ đệm ngắn hạn chống gửi trùng tin nhắn khi người dùng bấm nút Gửi liên tiếp (clientMessageId)
    private final Map<String, ChatMessageDTO> recentClientMessages = new ConcurrentHashMap<>();

    public ChatServiceImpl(
            ChatConversationRepository conversationRepository,
            ChatMessageRepository messageRepository,
            UserRepository userRepository,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            PatientProfileRepository patientProfileRepository,
            CustomerCareTaskRepository customerCareTaskRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.branchRepository = branchRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.customerCareTaskRepository = customerCareTaskRepository;
    }

    // =========================================================================
    // 1. XÁC ĐỊNH VAI TRÒ, TÊN HIỂN THỊ VÀ PHẠM VI CƠ SỞ (ROLE & BRANCH SCOPE)
    // =========================================================================

    private User requireActiveUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ. Vui lòng đăng nhập lại.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Không tìm thấy tài khoản đăng nhập."));
        if (Boolean.FALSE.equals(user.getIsActive())
                || (user.getStatus() != null && !"ACTIVE".equalsIgnoreCase(user.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản của bạn đã bị khóa hoặc ngưng hoạt động.");
        }
        return user;
    }

    /**
     * Chỉ Lễ tân (LE_TAN), Bác sĩ (BAC_SI) và Admin (ADMIN) mới được phép tiếp nhận & phản hồi tin nhắn của Bệnh nhân.
     */
    private boolean canHandlePatientMessages(String roleCode) {
        return "ADMIN".equalsIgnoreCase(roleCode)
                || "LE_TAN".equalsIgnoreCase(roleCode)
                || "BAC_SI".equalsIgnoreCase(roleCode);
    }

    @Override
    @Transactional(readOnly = true)
    public String resolvePrimaryRoleCode(Long userId) {
        if (userId == null || userId <= 0) {
            return "BENH_NHAN";
        }
        if (userRepository.hasActiveAdminRole(userId)) {
            return "ADMIN";
        }
        List<String> rawRoles = conversationRepository.findActiveRoleNamesByUserId(userId);
        if (rawRoles != null && !rawRoles.isEmpty()) {
            String normalized = resolveRoleFromRoleNames(rawRoles);
            if (normalized != null) {
                return normalized;
            }
        }
        Optional<StaffProfile> staffOpt = staffProfileRepository.findByUser_UserId(userId);
        if (staffOpt.isPresent() && staffOpt.get().getStatus() == StaffStatus.ACTIVE) {
            return inferStaffRoleFromProfile(staffOpt.get());
        }
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User u = userOpt.get();
            if (u.getRoles() != null && !u.getRoles().isEmpty()) {
                List<String> entityRoleNames = u.getRoles().stream()
                        .filter(r -> r != null && r.getRoleName() != null && !Boolean.FALSE.equals(r.getIsActive()))
                        .map(r -> r.getRoleName().trim().toUpperCase())
                        .toList();
                String resolved = resolveRoleFromRoleNames(entityRoleNames);
                if (resolved != null) {
                    return resolved;
                }
            }
            if (u.getUserType() == UserType.STAFF) {
                return "LE_TAN";
            }
        }
        return "BENH_NHAN";
    }

    private String resolveRoleFromRoleNames(Collection<String> rawRoles) {
        if (rawRoles == null || rawRoles.isEmpty()) {
            return null;
        }
        Set<String> normalized = new HashSet<>();
        for (String r : rawRoles) {
            if (r == null) continue;
            String clean = r.trim().toUpperCase();
            if (clean.startsWith("ROLE_")) {
                clean = clean.substring(5);
            }
            normalized.add(clean);
        }
        // Thứ tự ưu tiên nhất quán: ADMIN -> BAC_SI -> LE_TAN -> QUAN_LY_KHO -> KE_TOAN -> BENH_NHAN
        if (normalized.contains("ADMIN")) {
            return "ADMIN";
        }
        if (normalized.contains("BAC_SI") || normalized.contains("DOCTOR")) {
            return "BAC_SI";
        }
        if (normalized.contains("LE_TAN") || normalized.contains("RECEPTIONIST") || normalized.contains("CSKH")) {
            return "LE_TAN";
        }
        if (normalized.contains("QUAN_LY_KHO") || normalized.contains("WAREHOUSE")) {
            return "QUAN_LY_KHO";
        }
        if (normalized.contains("KE_TOAN") || normalized.contains("ACCOUNTANT") || normalized.contains("THU_NGAN")) {
            return "KE_TOAN";
        }
        if (normalized.contains("BENH_NHAN") || normalized.contains("PATIENT") || normalized.contains("CUSTOMER")) {
            return "BENH_NHAN";
        }
        return null;
    }

    private String inferStaffRoleFromProfile(StaffProfile sp) {
        String combined = ((sp.getDepartment() != null ? sp.getDepartment() : "") + " "
                + (sp.getPositionTitle() != null ? sp.getPositionTitle() : "") + " "
                + (sp.getStaffCode() != null ? sp.getStaffCode() : "")).toLowerCase(Locale.ROOT);
        if (combined.contains("bác sĩ") || combined.contains("bac si") || combined.contains("dr.")
                || combined.contains("bs") || combined.contains("lâm sàng")) {
            return "BAC_SI";
        }
        if (combined.contains("kho") || combined.contains("vật tư") || combined.contains("vat tu")) {
            return "QUAN_LY_KHO";
        }
        if (combined.contains("kế toán") || combined.contains("ke toan") || combined.contains("thu ngân") || combined.contains("tài chính")) {
            return "KE_TOAN";
        }
        return "LE_TAN";
    }

    @Override
    public String toRoleLabel(String roleCode) {
        if (roleCode == null) {
            return "Bệnh nhân";
        }
        return switch (roleCode.trim().toUpperCase()) {
            case "ADMIN" -> "Admin";
            case "BAC_SI" -> "Bác sĩ";
            case "LE_TAN" -> "Lễ tân";
            case "QUAN_LY_KHO" -> "Quản lý kho";
            case "KE_TOAN" -> "Kế toán";
            default -> "Bệnh nhân";
        };
    }

    @Override
    @Transactional(readOnly = true)
    public String requireInboxAccess(Long userId) {
        requireActiveUser(userId);
        String roleCode = resolvePrimaryRoleCode(userId);
        if ("BENH_NHAN".equals(roleCode)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bệnh nhân không có quyền truy cập Hộp thư nhân viên hoặc tìm kiếm tài khoản trong cơ sở."
            );
        }
        return roleCode;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAdminUser(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }
        return "ADMIN".equals(resolvePrimaryRoleCode(userId));
    }

    /**
     * Kiểm tra quyền SUBSCRIBE vào kênh hộp thư bệnh nhân của cơ sở (/topic/chat/branch/{branchId}):
     * - Chỉ ADMIN, LE_TAN và BAC_SI thuộc đúng cơ sở đó mới được phép nhận tin nhắn của Bệnh nhân gửi tới cơ sở.
     * - QUAN_LY_KHO, KE_TOAN và BENH_NHAN bị từ chối.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean canAccessBranchTopic(Long userId, Long branchId) {
        if (userId == null || branchId == null) {
            return false;
        }
        try {
            String roleCode = requireInboxAccess(userId);
            if (!canHandlePatientMessages(roleCode)) {
                return false;
            }
            if ("ADMIN".equals(roleCode)) {
                return true;
            }
            List<Branch> allowed = resolveStaffAllowedBranches(userId, roleCode);
            return allowed.stream().anyMatch(b -> Objects.equals(b.getBranchId(), branchId));
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Branch> getAccessibleBranchesForInbox(Long userId) {
        String roleCode = requireInboxAccess(userId);
        return resolveStaffAllowedBranches(userId, roleCode);
    }

    private List<Branch> getAllActiveBranches() {
        List<Branch> active = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
        if (active == null || active.isEmpty()) {
            active = branchRepository.findAllByOrderByBranchNameAsc();
        }
        return active != null ? active : List.of();
    }

    /**
     * Xác định cơ sở mà một nhân viên hoặc Admin được phép truy cập:
     * - ADMIN: Tất cả các cơ sở đang hoạt động.
     * - Các vai trò khác (LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN): CHỈ cơ sở được phân công của tài khoản đó.
     */
    private List<Branch> resolveStaffAllowedBranches(Long userId, String roleCode) {
        List<Branch> allActive = getAllActiveBranches();
        if ("ADMIN".equals(roleCode)) {
            return allActive;
        }

        // 1. Kiểm tra trực tiếp thuộc tính user.getBranch() (cột users.branch_id) nếu có
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent() && userOpt.get().getBranch() != null && userOpt.get().getBranch().getBranchId() != null) {
            Long userBranchId = userOpt.get().getBranch().getBranchId();
            List<Branch> matched = allActive.stream()
                    .filter(b -> Objects.equals(b.getBranchId(), userBranchId))
                    .toList();
            if (!matched.isEmpty()) {
                return List.of(matched.get(0));
            }
        }

        // 2. Kiểm tra phân công chính thức trong staff_branch_assignments
        List<Long> explicitIds = conversationRepository.findExplicitBranchIdsByUserId(userId);
        if (explicitIds != null && !explicitIds.isEmpty()) {
            Set<Long> allowedSet = new LinkedHashSet<>(explicitIds);
            List<Branch> matched = allActive.stream()
                    .filter(b -> allowedSet.contains(b.getBranchId()))
                    .toList();
            if (!matched.isEmpty()) {
                return matched;
            }
        }

        // 3. Dự phòng cho dữ liệu seed nhân sự chưa chèn bảng staff_branch_assignments:
        // Suy luận cơ sở từ lịch sử công tác của hồ sơ nhân viên (ca khám, lịch hẹn, đơn thuốc, công việc CSKH)
        Optional<StaffProfile> staffOpt = staffProfileRepository.findByUser_UserId(userId);
        if (staffOpt.isPresent()) {
            List<Long> inferredIds = customerCareTaskRepository.findInferredBranchIdsByStaffId(staffOpt.get().getStaffId());
            if (inferredIds != null && !inferredIds.isEmpty()) {
                Set<Long> allowedSet = new LinkedHashSet<>(inferredIds);
                List<Branch> matched = allActive.stream()
                        .filter(b -> allowedSet.contains(b.getBranchId()))
                        .toList();
                if (!matched.isEmpty()) {
                    return List.of(matched.get(0));
                }
            }
        }

        // 4. Nếu nhân viên mới hoàn toàn chưa có phân công, khóa phạm vi vào 1 cơ sở đầu tiên (không bao giờ mở toàn bộ như Admin)
        return allActive.isEmpty() ? List.of() : List.of(allActive.get(0));
    }

    /**
     * Xác định cơ sở gắn với tài khoản người dùng khi gửi tin nhắn từ khung chat nổi (Floating Widget).
     */
    private Branch resolveUserHomeBranch(User user, String roleCode) {
        if (user.getBranch() != null && user.getBranch().getBranchId() != null) {
            Optional<Branch> branchOpt = branchRepository.findById(user.getBranch().getBranchId());
            if (branchOpt.isPresent()) {
                return branchOpt.get();
            }
        }

        Optional<PatientProfile> defaultProfile = patientProfileRepository.findFirstByUser_UserIdAndIsDefaultTrue(user.getUserId());
        if (defaultProfile.isPresent() && defaultProfile.get().getPreferredBranch() != null) {
            return defaultProfile.get().getPreferredBranch();
        }
        List<PatientProfile> profiles = patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(user.getUserId());
        if (profiles != null) {
            for (PatientProfile p : profiles) {
                if (p.getPreferredBranch() != null) {
                    return p.getPreferredBranch();
                }
            }
        }

        if (!"BENH_NHAN".equals(roleCode)) {
            List<Branch> staffBranches = resolveStaffAllowedBranches(user.getUserId(), roleCode);
            if (!staffBranches.isEmpty()) {
                return staffBranches.get(0);
            }
        }

        List<Branch> activeBranches = getAllActiveBranches();
        if (!activeBranches.isEmpty()) {
            return activeBranches.get(0);
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Tài khoản của bạn chưa gắn với cơ sở nha khoa nào. Vui lòng cập nhật cơ sở trong trang Thông tin cá nhân."
        );
    }

    /**
     * Lấy danh sách cơ sở của một tài khoản ứng viên khi tìm kiếm người nhận trong cùng cơ sở.
     */
    private List<Branch> resolveAccountBranches(User account, String accountRoleCode) {
        if ("ADMIN".equals(accountRoleCode)) {
            return getAllActiveBranches();
        }
        if ("BENH_NHAN".equals(accountRoleCode)) {
            try {
                Branch home = resolveUserHomeBranch(account, accountRoleCode);
                return home != null ? List.of(home) : List.of();
            } catch (Exception ex) {
                return List.of();
            }
        }
        return resolveStaffAllowedBranches(account.getUserId(), accountRoleCode);
    }

    /**
     * Lấy họ tên hiển thị chuẩn xác của một tài khoản (StaffProfile.fullName -> PatientProfile.fullName -> User.username).
     */
    private String resolveDisplayName(User user) {
        if (user == null) {
            return "Người dùng";
        }
        Long uid = user.getUserId();
        if (uid != null) {
            Optional<StaffProfile> staffOpt = staffProfileRepository.findByUser_UserId(uid);
            if (staffOpt.isPresent() && staffOpt.get().getFullName() != null && !staffOpt.get().getFullName().isBlank()) {
                return staffOpt.get().getFullName().trim();
            }
            Optional<PatientProfile> defaultPatientOpt = patientProfileRepository.findFirstByUser_UserIdAndIsDefaultTrue(uid);
            if (defaultPatientOpt.isPresent() && defaultPatientOpt.get().getFullName() != null && !defaultPatientOpt.get().getFullName().isBlank()) {
                return defaultPatientOpt.get().getFullName().trim();
            }
            List<PatientProfile> patientList = patientProfileRepository.findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(uid);
            if (patientList != null) {
                for (PatientProfile p : patientList) {
                    if (p.getFullName() != null && !p.getFullName().isBlank()) {
                        return p.getFullName().trim();
                    }
                }
            }
        }
        return user.getUsername() != null ? user.getUsername() : "Người dùng #" + uid;
    }

    // =========================================================================
    // 2. TÌM KIẾM TÀI KHOẢN TRONG CÙNG CƠ SỞ & KHỞI TẠO HỘI THOẠI VỚI NGƯỜI NHẬN
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<ChatUserSearchItemDTO> searchRecipientUsers(
            Long currentUserId,
            String keyword,
            String roleFilter,
            Long branchId) {

        // Chặn tuyệt đối Bệnh nhân (403 FORBIDDEN)
        String currentRole = requireInboxAccess(currentUserId);
        boolean canChatWithPatient = canHandlePatientMessages(currentRole);

        List<Branch> callerAllowedBranches = resolveStaffAllowedBranches(currentUserId, currentRole);
        Set<Long> callerAllowedBranchIds = callerAllowedBranches.stream()
                .map(Branch::getBranchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (!"ADMIN".equals(currentRole)) {
            if (callerAllowedBranchIds.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Tài khoản của bạn chưa được phân công cơ sở làm việc."
                );
            }
            if (branchId != null && !callerAllowedBranchIds.contains(branchId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Bạn chỉ được phép tìm kiếm các tài khoản thuộc cùng cơ sở của mình."
                );
            }
        }

        Set<Long> targetBranchScope = (branchId != null)
                ? Set.of(branchId)
                : callerAllowedBranchIds;

        String cleanKeyword = keyword != null ? keyword.trim().toLowerCase(Locale.ROOT) : "";
        String cleanRoleFilter = roleFilter != null ? roleFilter.trim().toUpperCase(Locale.ROOT) : "";
        if ("ALL".equals(cleanRoleFilter) || "TAT_CA".equals(cleanRoleFilter)) {
            cleanRoleFilter = "";
        }

        // Nếu vai trò hiện tại là Quản lý kho hoặc Kế toán -> không được tìm Bệnh nhân để phản hồi/nhắn tin
        if (!canChatWithPatient && "BENH_NHAN".equalsIgnoreCase(cleanRoleFilter)) {
            return List.of();
        }

        List<User> allUsers = userRepository.findAll();
        if (allUsers == null || allUsers.isEmpty()) {
            return List.of();
        }

        List<ChatConversation> allConversations = conversationRepository.findAllConversationsWithUserAndBranch();

        List<ChatUserSearchItemDTO> results = new ArrayList<>();
        for (User candidate : allUsers) {
            if (candidate == null || candidate.getUserId() == null) {
                continue;
            }
            if (Objects.equals(candidate.getUserId(), currentUserId)) {
                continue;
            }
            if (Boolean.FALSE.equals(candidate.getIsActive())
                    || (candidate.getStatus() != null && !"ACTIVE".equalsIgnoreCase(candidate.getStatus()))) {
                continue;
            }

            String candidateRole = resolvePrimaryRoleCode(candidate.getUserId());

            // Quản lý kho và Kế toán không nhận/phản hồi tin nhắn của Bệnh nhân
            if (!canChatWithPatient && "BENH_NHAN".equalsIgnoreCase(candidateRole)) {
                continue;
            }

            if (!cleanRoleFilter.isEmpty() && !cleanRoleFilter.equalsIgnoreCase(candidateRole)) {
                continue;
            }

            List<Branch> candidateBranches = resolveAccountBranches(candidate, candidateRole);
            Branch matchedBranch = null;
            for (Branch cb : candidateBranches) {
                if (cb != null && cb.getBranchId() != null && targetBranchScope.contains(cb.getBranchId())) {
                    matchedBranch = cb;
                    break;
                }
            }
            if (matchedBranch == null) {
                continue;
            }

            String fullName = resolveDisplayName(candidate);
            String username = candidate.getUsername() != null ? candidate.getUsername() : "";
            String phone = candidate.getPhoneNumber() != null ? candidate.getPhoneNumber() : "";
            String email = candidate.getEmail() != null ? candidate.getEmail() : "";
            String roleLabel = toRoleLabel(candidateRole);

            if (!cleanKeyword.isEmpty()) {
                boolean matched = fullName.toLowerCase(Locale.ROOT).contains(cleanKeyword)
                        || username.toLowerCase(Locale.ROOT).contains(cleanKeyword)
                        || phone.toLowerCase(Locale.ROOT).contains(cleanKeyword)
                        || email.toLowerCase(Locale.ROOT).contains(cleanKeyword)
                        || roleLabel.toLowerCase(Locale.ROOT).contains(cleanKeyword);
                if (!matched) {
                    continue;
                }
            }

            Long existingConvId = findExistingConversationIdInMemory(
                    allConversations, currentUserId, candidate.getUserId(), candidateRole, matchedBranch.getBranchId()
            );

            results.add(ChatUserSearchItemDTO.builder()
                    .userId(candidate.getUserId())
                    .username(username)
                    .fullName(fullName)
                    .phoneNumber(phone)
                    .email(email)
                    .roleCode(candidateRole)
                    .roleLabel(roleLabel)
                    .branchId(matchedBranch.getBranchId())
                    .branchCode(matchedBranch.getBranchCode())
                    .branchName(matchedBranch.getBranchName())
                    .existingConversationId(existingConvId)
                    .build());
        }

        results.sort(Comparator
                .comparing((ChatUserSearchItemDTO u) -> "BENH_NHAN".equals(u.getRoleCode()) ? 1 : 0)
                .thenComparing(u -> u.getFullName() != null ? u.getFullName().toLowerCase(Locale.ROOT) : ""));

        return results;
    }

    private Long findExistingConversationIdInMemory(
            List<ChatConversation> allConversations,
            Long currentUserId,
            Long targetUserId,
            String targetRoleCode,
            Long branchId) {
        if (allConversations == null || allConversations.isEmpty()) {
            return null;
        }
        for (ChatConversation c : allConversations) {
            if (c.getStatus() == ChatStatus.CLOSED || c.getUser() == null) {
                continue;
            }
            if ("BENH_NHAN".equals(targetRoleCode)) {
                if (c.getRecipientUser() == null
                        && Objects.equals(c.getUser().getUserId(), targetUserId)
                        && (c.getBranch() == null || Objects.equals(c.getBranch().getBranchId(), branchId))) {
                    return c.getConversationId();
                }
            } else {
                if (c.getRecipientUser() != null) {
                    Long uId = c.getUser().getUserId();
                    Long rId = c.getRecipientUser().getUserId();
                    if ((Objects.equals(uId, currentUserId) && Objects.equals(rId, targetUserId))
                            || (Objects.equals(uId, targetUserId) && Objects.equals(rId, currentUserId))) {
                        return c.getConversationId();
                    }
                }
            }
        }
        return null;
    }

    @Override
    @Transactional
    public Map<String, Object> startOrGetConversationWithRecipient(
            Long currentUserId,
            Long targetUserId,
            Long requestedBranchId) {

        User currentUser = requireActiveUser(currentUserId);
        String currentRole = requireInboxAccess(currentUserId);

        if (targetUserId == null || targetUserId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn tài khoản người nhận hợp lệ.");
        }
        if (Objects.equals(currentUserId, targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể tự tạo cuộc hội thoại với chính mình.");
        }

        ChatConversation conversation = resolveOrCreateConversationForRecipient(
                currentUser, currentRole, targetUserId, requestedBranchId
        );
        return getConversationMessages(currentUserId, conversation.getConversationId());
    }

    /**
     * Tìm hoặc khởi tạo cuộc hội thoại với một người nhận cụ thể (targetUserId) trong cùng cơ sở:
     * - Kiểm tra nghiêm ngặt: người gửi và người nhận phải thuộc cùng một cơ sở được phép truy cập (trừ khi người gửi là ADMIN).
     * - Chỉ Lễ tân (LE_TAN), Bác sĩ (BAC_SI) và Admin (ADMIN) mới được phép mở hội thoại và phản hồi Bệnh nhân (BENH_NHAN).
     */
    private ChatConversation resolveOrCreateConversationForRecipient(
            User sender,
            String senderRole,
            Long targetUserId,
            Long requestedBranchId) {

        User targetUser = requireActiveUser(targetUserId);
        String targetRole = resolvePrimaryRoleCode(targetUserId);

        // Nếu người nhận là Bệnh nhân -> chỉ Lễ tân, Bác sĩ và Admin được phép nhắn tin/phản hồi
        if ("BENH_NHAN".equals(targetRole) && !canHandlePatientMessages(senderRole)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Chỉ Lễ tân, Bác sĩ thuộc cơ sở và Admin mới có quyền tiếp nhận và phản hồi tin nhắn của Bệnh nhân."
            );
        }

        List<Branch> senderBranches = resolveStaffAllowedBranches(sender.getUserId(), senderRole);
        Set<Long> senderBranchIds = senderBranches.stream()
                .map(Branch::getBranchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (!"ADMIN".equals(senderRole) && requestedBranchId != null && !senderBranchIds.contains(requestedBranchId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn không được phép tạo cuộc hội thoại ở cơ sở khác."
            );
        }

        List<Branch> targetBranches = resolveAccountBranches(targetUser, targetRole);
        Branch sharedBranch = null;

        if (requestedBranchId != null) {
            for (Branch tb : targetBranches) {
                if (tb != null && Objects.equals(tb.getBranchId(), requestedBranchId)) {
                    sharedBranch = tb;
                    break;
                }
            }
        } else {
            for (Branch tb : targetBranches) {
                if (tb != null && tb.getBranchId() != null && senderBranchIds.contains(tb.getBranchId())) {
                    sharedBranch = tb;
                    break;
                }
            }
        }

        if (sharedBranch == null) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Tài khoản người nhận không thuộc cùng cơ sở làm việc với bạn."
            );
        }

        if ("BENH_NHAN".equals(targetRole)) {
            return getOrCreateConversationForInitiator(targetUser, sharedBranch);
        }

        return getOrCreateDirectConversation(sender, targetUser, sharedBranch);
    }

    private ChatConversation getOrCreateDirectConversation(User userA, User userB, Branch sharedBranch) {
        List<ChatConversation> existingList = conversationRepository.findActiveDirectConversationsBetweenUsers(
                userA.getUserId(), userB.getUserId()
        );
        if (existingList != null && !existingList.isEmpty()) {
            ChatConversation existing = existingList.get(0);
            if (existing.getBranch() == null) {
                existing.setBranch(sharedBranch);
            }
            if (existing.getStatus() == ChatStatus.BOT_HANDLING) {
                existing.setStatus(ChatStatus.STAFF_HANDLING);
            }
            return conversationRepository.save(existing);
        }

        ChatConversation created = new ChatConversation();
        created.setUser(userA);
        created.setRecipientUser(userB);
        created.setBranch(sharedBranch);
        created.setChannel(ChatChannel.WEB_WIDGET);
        created.setStatus(ChatStatus.STAFF_HANDLING);

        LocalDateTime now = LocalDateTime.now();
        created.setCreatedAt(now);
        created.setLastMessageAt(now);
        return conversationRepository.save(created);
    }

    // =========================================================================
    // 3. NGỮ CẢNH CHAT & KHUNG CHAT NỔI (FLOATING WIDGET)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getUserChatContext(Long userId) {
        User user = requireActiveUser(userId);
        String roleCode = resolvePrimaryRoleCode(userId);
        String roleLabel = toRoleLabel(roleCode);
        String displayName = resolveDisplayName(user);
        boolean isAdmin = "ADMIN".equals(roleCode);
        boolean canAccessInbox = !"BENH_NHAN".equals(roleCode);
        boolean canHandlePatient = canHandlePatientMessages(roleCode);

        Branch homeBranch = resolveUserHomeBranch(user, roleCode);
        List<Map<String, Object>> accessibleBranches = new ArrayList<>();
        if (canAccessInbox) {
            for (Branch b : resolveStaffAllowedBranches(userId, roleCode)) {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("branchId", b.getBranchId());
                bm.put("branchCode", b.getBranchCode());
                bm.put("branchName", b.getBranchName());
                accessibleBranches.add(bm);
            }
        }

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("authenticated", true);
        ctx.put("userId", user.getUserId());
        ctx.put("username", user.getUsername());
        ctx.put("fullName", displayName);
        ctx.put("roleCode", roleCode);
        ctx.put("roleLabel", roleLabel);
        ctx.put("isAdmin", isAdmin);
        ctx.put("canAccessInbox", canAccessInbox);
        ctx.put("canSearchRecipients", canAccessInbox);
        ctx.put("canHandlePatients", canHandlePatient);
        ctx.put("branchId", homeBranch != null ? homeBranch.getBranchId() : null);
        ctx.put("branchCode", homeBranch != null ? homeBranch.getBranchCode() : null);
        ctx.put("branchName", homeBranch != null ? homeBranch.getBranchName() : "Chưa gắn cơ sở");
        ctx.put("accessibleBranches", accessibleBranches);
        ctx.put("greetingMessage", GREETING_MESSAGE);
        ctx.put("firstMessageSystemNotice", FIRST_MESSAGE_SYSTEM_NOTICE);
        return ctx;
    }

    @Override
    @Transactional
    public Map<String, Object> getOrInitMyWidgetConversation(Long userId) {
        User user = requireActiveUser(userId);
        String roleCode = resolvePrimaryRoleCode(userId);
        Branch homeBranch = resolveUserHomeBranch(user, roleCode);

        ChatConversation conversation = null;
        List<ChatMessageDTO> messages = List.of();
        long unreadCount = 0L;

        // Bệnh nhân (hoặc Lễ tân/Bác sĩ/Admin) mới có hộp thư chung cơ sở;
        // Quản lý kho & Kế toán dùng widget để chọn đồng nghiệp cùng cơ sở nhắn tin trực tiếp
        if ("BENH_NHAN".equals(roleCode) || canHandlePatientMessages(roleCode)) {
            conversation = findActiveConversationForUserAndBranch(user, homeBranch);
            if (conversation != null) {
                if (conversation.getStatus() == ChatStatus.BOT_HANDLING) {
                    conversation.setStatus(ChatStatus.STAFF_HANDLING);
                    conversationRepository.save(conversation);
                }
                List<ChatMessage> rawMessages = messageRepository.findByConversationIdOrderBySentAtAsc(conversation.getConversationId());
                messages = mapMessagesToDTOs(conversation, rawMessages);
                unreadCount = messageRepository.countUnreadForInitiator(conversation.getConversationId(), user.getUserId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getUserId());
        result.put("username", user.getUsername());
        result.put("fullName", resolveDisplayName(user));
        result.put("roleCode", roleCode);
        result.put("roleLabel", toRoleLabel(roleCode));
        result.put("canSearchRecipients", !"BENH_NHAN".equals(roleCode));
        result.put("canHandlePatients", canHandlePatientMessages(roleCode));
        result.put("branchId", homeBranch.getBranchId());
        result.put("branchName", homeBranch.getBranchName());
        result.put("conversationId", conversation != null ? conversation.getConversationId() : null);
        result.put("unreadCount", unreadCount);
        result.put("greetingMessage", GREETING_MESSAGE);
        result.put("firstMessageSystemNotice", FIRST_MESSAGE_SYSTEM_NOTICE);
        result.put("messages", messages);
        return result;
    }

    private ChatConversation findActiveConversationForUserAndBranch(User user, Branch branch) {
        List<ChatConversation> activeList = conversationRepository.findActiveByUserIdAndBranchId(
                user.getUserId(), branch.getBranchId()
        );
        if (activeList != null && !activeList.isEmpty()) {
            return activeList.get(0);
        }
        List<ChatConversation> legacyNullBranch = conversationRepository.findActiveByUserIdWithNullBranch(user.getUserId());
        if (legacyNullBranch != null && !legacyNullBranch.isEmpty()) {
            ChatConversation legacy = legacyNullBranch.get(0);
            legacy.setBranch(branch);
            legacy.setStatus(ChatStatus.STAFF_HANDLING);
            return conversationRepository.save(legacy);
        }
        return null;
    }

    private ChatConversation getOrCreateConversationForInitiator(User user, Branch branch) {
        ChatConversation existing = findActiveConversationForUserAndBranch(user, branch);
        if (existing != null) {
            if (existing.getStatus() == ChatStatus.BOT_HANDLING) {
                existing.setStatus(ChatStatus.STAFF_HANDLING);
                existing = conversationRepository.save(existing);
            }
            return existing;
        }

        ChatConversation created = new ChatConversation();
        created.setUser(user);
        created.setRecipientUser(null);
        created.setBranch(branch);
        created.setChannel(ChatChannel.WEB_WIDGET);
        created.setStatus(ChatStatus.STAFF_HANDLING);

        Optional<PatientProfile> defaultPatient = patientProfileRepository.findFirstByUser_UserIdAndIsDefaultTrue(user.getUserId());
        defaultPatient.ifPresent(created::setPatientProfile);

        LocalDateTime now = LocalDateTime.now();
        created.setCreatedAt(now);
        created.setLastMessageAt(now);
        return conversationRepository.save(created);
    }

    // =========================================================================
    // 4. TÌM KIẾM, LỌC THEO VAI TRÒ & CƠ SỞ TRONG HỘP THƯ (/chat)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public Page<ChatConversationDTO> searchInboxConversations(
            Long currentUserId,
            String keyword,
            String initiatorRole,
            Long branchId,
            Boolean unreadOnly,
            Pageable pageable) {

        String currentRole = requireInboxAccess(currentUserId);
        boolean canHandlePatient = canHandlePatientMessages(currentRole);

        List<Branch> allowedBranches = resolveStaffAllowedBranches(currentUserId, currentRole);
        Set<Long> allowedBranchIds = allowedBranches.stream()
                .map(Branch::getBranchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Chỉ ADMIN mới được xem tất cả cơ sở; các role khác bắt buộc khóa chặt vào cơ sở được phân công
        if (!"ADMIN".equals(currentRole)) {
            if (allowedBranchIds.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Tài khoản của bạn chưa được phân công cơ sở làm việc nên không thể xem hộp thư."
                );
            }
            if (branchId != null && !allowedBranchIds.contains(branchId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Bạn chỉ được quyền xem tin nhắn thuộc cơ sở mình được phân công."
                );
            }
        }

        List<ChatConversation> allConversations = conversationRepository.findAllConversationsWithUserAndBranch();
        if (allConversations == null || allConversations.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        Map<Long, String> userRoleCache = new HashMap<>();

        // Lọc theo phạm vi cơ sở (Branch Scope) và quyền tiếp nhận tin nhắn Bệnh nhân
        List<ChatConversation> branchFiltered = new ArrayList<>();
        for (ChatConversation c : allConversations) {
            if (c.getUser() == null) {
                continue;
            }
            Branch convBranch = c.getBranch();
            if (convBranch == null) {
                convBranch = c.getUser().getBranch();
            }
            Long convBranchId = convBranch != null ? convBranch.getBranchId() : null;

            // 1. Kiểm tra phạm vi Cơ sở:
            // - ADMIN: Xem được mọi cơ sở (hoặc lọc theo branchId nếu truyền vào).
            // - Các role khác: CHỈ được xem tin nhắn thuộc đúng cơ sở mình được phân công (allowedBranchIds).
            if ("ADMIN".equals(currentRole)) {
                if (branchId != null && !Objects.equals(convBranchId, branchId)) {
                    continue;
                }
            } else {
                if (convBranchId == null || !allowedBranchIds.contains(convBranchId)) {
                    continue;
                }
                if (branchId != null && !Objects.equals(convBranchId, branchId)) {
                    continue;
                }
            }

            User initiator = c.getUser();
            User recipient = c.getRecipientUser();
            boolean isDirect = recipient != null;
            String initRoleCode = userRoleCache.computeIfAbsent(initiator.getUserId(), this::resolvePrimaryRoleCode);

            if (isDirect) {
                // Hội thoại trực tiếp 1-1: đối với nhân viên không phải Admin, chỉ hiển thị nếu là 1 trong 2 người tham gia
                if (!"ADMIN".equals(currentRole)) {
                    boolean isParticipant = Objects.equals(initiator.getUserId(), currentUserId)
                            || Objects.equals(recipient.getUserId(), currentUserId);
                    if (!isParticipant) {
                        continue;
                    }
                }
                // Nếu người xem không có quyền tiếp nhận Bệnh nhân (QUAN_LY_KHO, KE_TOAN) -> loại bỏ mọi hội thoại có Bệnh nhân
                if (!canHandlePatient) {
                    String recRoleCode = userRoleCache.computeIfAbsent(recipient.getUserId(), this::resolvePrimaryRoleCode);
                    if ("BENH_NHAN".equals(initRoleCode) || "BENH_NHAN".equals(recRoleCode)) {
                        continue;
                    }
                }
            } else {
                // Hội thoại chung của cơ sở (do Bệnh nhân hoặc nhân viên gửi vào hộp thư chung):
                // CHỈ Lễ tân (LE_TAN), Bác sĩ (BAC_SI) của cơ sở đó và Admin (ADMIN) mới được nhận và phản hồi!
                if (!canHandlePatient) {
                    continue;
                }
            }

            branchFiltered.add(c);
        }

        if (branchFiltered.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<Long> convIds = branchFiltered.stream().map(ChatConversation::getConversationId).toList();
        Map<Long, ChatMessage> latestMessageMap = new HashMap<>();
        for (ChatMessage m : messageRepository.findLatestMessagesByConversationIds(convIds)) {
            if (m.getConversation() != null) {
                latestMessageMap.put(m.getConversation().getConversationId(), m);
            }
        }

        Map<Long, Long> staffUnreadMap = new HashMap<>();
        for (Object[] row : messageRepository.countUnreadForStaffByConversationIds(convIds)) {
            if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                staffUnreadMap.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
        }

        Map<Long, Long> directUnreadMap = new HashMap<>();
        List<Object[]> directRows = messageRepository.countUnreadForDirectViewerByConversationIds(convIds, currentUserId);
        if (directRows != null) {
            for (Object[] row : directRows) {
                if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                    directUnreadMap.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
                }
            }
        }

        String cleanKeyword = keyword != null ? keyword.trim().toLowerCase(Locale.ROOT) : "";
        Set<Long> messageMatchedConvIds = new HashSet<>();
        if (!cleanKeyword.isEmpty()) {
            List<Long> matchedIds = messageRepository.findConversationIdsByMessageKeyword(cleanKeyword);
            if (matchedIds != null) {
                messageMatchedConvIds.addAll(matchedIds);
            }
        }

        String cleanRoleFilter = initiatorRole != null ? initiatorRole.trim().toUpperCase(Locale.ROOT) : "";
        if ("ALL".equals(cleanRoleFilter) || "TAT_CA".equals(cleanRoleFilter)) {
            cleanRoleFilter = "";
        }

        Map<Long, String> userNameCache = new HashMap<>();

        List<ChatConversationDTO> matchedDTOs = new ArrayList<>();
        for (ChatConversation c : branchFiltered) {
            User initiator = c.getUser();
            Long initiatorId = initiator.getUserId();
            String initRoleCode = userRoleCache.computeIfAbsent(initiatorId, this::resolvePrimaryRoleCode);
            String initFullName = userNameCache.computeIfAbsent(initiatorId, id -> resolveDisplayName(initiator));
            String initUsername = initiator.getUsername() != null ? initiator.getUsername() : "";
            String initPhone = initiator.getPhoneNumber() != null ? initiator.getPhoneNumber() : "";

            User recipient = c.getRecipientUser();
            boolean isDirect = recipient != null;
            Long recipientId = isDirect ? recipient.getUserId() : null;
            String recRoleCode = isDirect ? userRoleCache.computeIfAbsent(recipientId, this::resolvePrimaryRoleCode) : null;
            String recFullName = isDirect ? userNameCache.computeIfAbsent(recipientId, id -> resolveDisplayName(recipient)) : null;
            String recUsername = isDirect && recipient.getUsername() != null ? recipient.getUsername() : "";
            String recPhone = isDirect && recipient.getPhoneNumber() != null ? recipient.getPhoneNumber() : "";

            boolean currentIsInitiator = isDirect && Objects.equals(initiatorId, currentUserId);
            Long counterpartId = currentIsInitiator ? recipientId : initiatorId;
            String counterpartFullName = currentIsInitiator ? recFullName : initFullName;
            String counterpartUsername = currentIsInitiator ? recUsername : initUsername;
            String counterpartPhone = currentIsInitiator ? recPhone : initPhone;
            String counterpartRoleCode = currentIsInitiator ? recRoleCode : initRoleCode;

            // 1. Lọc theo vai trò người khởi tạo (hoặc đối phương trong hội thoại trực tiếp 1-1)
            if (!cleanRoleFilter.isEmpty()) {
                boolean roleMatch = cleanRoleFilter.equalsIgnoreCase(initRoleCode)
                        || (isDirect && cleanRoleFilter.equalsIgnoreCase(counterpartRoleCode));
                if (!roleMatch) {
                    continue;
                }
            }

            long unreadCount = isDirect
                    ? directUnreadMap.getOrDefault(c.getConversationId(), 0L)
                    : staffUnreadMap.getOrDefault(c.getConversationId(), 0L);

            // 2. Lọc chỉ hội thoại chưa đọc (nếu bật)
            if (Boolean.TRUE.equals(unreadOnly) && unreadCount <= 0) {
                continue;
            }

            ChatMessage lastMsg = latestMessageMap.get(c.getConversationId());
            String lastMsgText = lastMsg != null && lastMsg.getMessageText() != null ? lastMsg.getMessageText() : "";

            // 3. Lọc theo từ khóa
            if (!cleanKeyword.isEmpty()) {
                boolean nameMatch = initFullName.toLowerCase(Locale.ROOT).contains(cleanKeyword)
                        || initUsername.toLowerCase(Locale.ROOT).contains(cleanKeyword)
                        || initPhone.toLowerCase(Locale.ROOT).contains(cleanKeyword)
                        || (isDirect && recFullName != null && recFullName.toLowerCase(Locale.ROOT).contains(cleanKeyword))
                        || (isDirect && recUsername.toLowerCase(Locale.ROOT).contains(cleanKeyword))
                        || (isDirect && recPhone.toLowerCase(Locale.ROOT).contains(cleanKeyword))
                        || String.valueOf(c.getConversationId()).contains(cleanKeyword);
                boolean msgMatch = lastMsgText.toLowerCase(Locale.ROOT).contains(cleanKeyword)
                        || messageMatchedConvIds.contains(c.getConversationId());
                if (!nameMatch && !msgMatch) {
                    continue;
                }
            }

            Branch convBranch = c.getBranch() != null ? c.getBranch() : initiator.getBranch();
            String lastSenderName = null;
            if (lastMsg != null && lastMsg.getSenderId() != null) {
                Long sid = lastMsg.getSenderId();
                lastSenderName = userNameCache.computeIfAbsent(sid, id ->
                        userRepository.findById(id).map(this::resolveDisplayName).orElse("Người dùng #" + id)
                );
            }

            LocalDateTime effectiveLastAt = lastMsg != null && lastMsg.getSentAt() != null
                    ? lastMsg.getSentAt()
                    : (c.getLastMessageAt() != null ? c.getLastMessageAt() : c.getCreatedAt());

            ChatConversationDTO dto = ChatConversationDTO.builder()
                    .conversationId(c.getConversationId())
                    .initiatorUserId(initiatorId)
                    .initiatorUsername(initUsername)
                    .initiatorFullName(initFullName)
                    .initiatorPhone(initPhone)
                    .initiatorRoleCode(initRoleCode)
                    .initiatorRoleLabel(toRoleLabel(initRoleCode))
                    .directConversation(isDirect)
                    .recipientUserId(recipientId)
                    .recipientUsername(recUsername)
                    .recipientFullName(recFullName)
                    .recipientPhone(recPhone)
                    .recipientRoleCode(recRoleCode)
                    .recipientRoleLabel(recRoleCode != null ? toRoleLabel(recRoleCode) : null)
                    .counterpartUserId(counterpartId)
                    .counterpartUsername(counterpartUsername)
                    .counterpartFullName(counterpartFullName)
                    .counterpartPhone(counterpartPhone)
                    .counterpartRoleCode(counterpartRoleCode)
                    .counterpartRoleLabel(toRoleLabel(counterpartRoleCode))
                    .branchId(convBranch != null ? convBranch.getBranchId() : null)
                    .branchCode(convBranch != null ? convBranch.getBranchCode() : null)
                    .branchName(convBranch != null ? convBranch.getBranchName() : "Chưa gắn cơ sở")
                    .status(c.getStatus() != null ? c.getStatus().name() : ChatStatus.STAFF_HANDLING.name())
                    .lastMessageText(lastMsgText)
                    .lastMessageSenderId(lastMsg != null ? lastMsg.getSenderId() : null)
                    .lastMessageSenderName(lastSenderName)
                    .lastMessageAt(effectiveLastAt)
                    .createdAt(c.getCreatedAt())
                    .unreadCount(unreadCount)
                    .unread(unreadCount > 0)
                    .build();

            matchedDTOs.add(dto);
        }

        matchedDTOs.sort((a, b) -> {
            LocalDateTime t1 = a.getLastMessageAt() != null ? a.getLastMessageAt() : LocalDateTime.MIN;
            LocalDateTime t2 = b.getLastMessageAt() != null ? b.getLastMessageAt() : LocalDateTime.MIN;
            int cmp = t2.compareTo(t1);
            if (cmp != 0) {
                return cmp;
            }
            Long id1 = a.getConversationId() != null ? a.getConversationId() : 0L;
            Long id2 = b.getConversationId() != null ? b.getConversationId() : 0L;
            return id2.compareTo(id1);
        });

        int total = matchedDTOs.size();
        int start = (int) pageable.getOffset();
        if (start >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        int end = Math.min(start + pageable.getPageSize(), total);
        return new PageImpl<>(matchedDTOs.subList(start, end), pageable, total);
    }

    // =========================================================================
    // 5. XEM CHI TIẾT TIN NHẮN, GỬI TIN NHẮN & ĐÁNH DẤU ĐÃ ĐỌC
    // =========================================================================

    /**
     * Kiểm tra quyền xem và trả lời một cuộc hội thoại:
     * 1. ADMIN: Được phép xem và trả lời hội thoại của tất cả cơ sở.
     * 2. BENH_NHAN: Chỉ được xem và gửi tin nhắn trong hội thoại của chính mình tại cơ sở gắn với tài khoản.
     * 3. Nhân viên (LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN):
     *    - Bắt buộc hội thoại phải thuộc đúng cơ sở được phân công (không bao giờ được xem tin nhắn của cơ sở khác).
     *    - Nếu là hội thoại của Bệnh nhân (recipientUser == null hoặc có bên là BENH_NHAN): CHỈ Lễ tân (LE_TAN) và Bác sĩ (BAC_SI) cùng cơ sở mới được xem và phản hồi; Quản lý kho (QUAN_LY_KHO) và Kế toán (KE_TOAN) bị từ chối (403 FORBIDDEN).
     *    - Nếu là hội thoại trực tiếp 1-1 nội bộ (recipientUser != null): Chỉ 2 người tham gia trong cùng cơ sở (và Admin) được phép xem/trả lời.
     */
    private ChatConversation requireConversationAccess(Long currentUserId, Long conversationId) {
        User currentUser = requireActiveUser(currentUserId);
        ChatConversation conversation = conversationRepository.findByIdWithDetails(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy cuộc hội thoại #" + conversationId));

        String currentRole = resolvePrimaryRoleCode(currentUserId);
        User initiator = conversation.getUser();
        User recipient = conversation.getRecipientUser();

        // 1. Admin được phép xem và trả lời hội thoại của mọi cơ sở
        if ("ADMIN".equals(currentRole)) {
            return conversation;
        }

        // 2. Bệnh nhân chỉ được truy cập hội thoại của chính mình
        if ("BENH_NHAN".equals(currentRole)) {
            if (recipient == null && initiator != null && Objects.equals(initiator.getUserId(), currentUser.getUserId())) {
                return conversation;
            }
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn không có quyền xem hoặc gửi tin nhắn vào cuộc hội thoại của người dùng khác."
            );
        }

        // 3. Các role nhân viên (LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN):
        // Bắt buộc kiểm tra hội thoại phải thuộc cơ sở được phân công của nhân viên đó!
        List<Branch> allowedBranches = resolveStaffAllowedBranches(currentUserId, currentRole);
        Set<Long> allowedBranchIds = allowedBranches.stream()
                .map(Branch::getBranchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Branch convBranch = conversation.getBranch() != null
                ? conversation.getBranch()
                : (initiator != null ? initiator.getBranch() : null);
        Long convBranchId = convBranch != null ? convBranch.getBranchId() : null;

        if (convBranchId == null || !allowedBranchIds.contains(convBranchId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn chỉ được phép xem và trả lời tin nhắn thuộc cơ sở mình được phân công."
            );
        }

        // 4. Nếu là hội thoại trực tiếp 1-1 giữa các nhân viên trong cơ sở:
        if (recipient != null) {
            boolean isParticipant = (initiator != null && Objects.equals(initiator.getUserId(), currentUserId))
                    || Objects.equals(recipient.getUserId(), currentUserId);
            if (!isParticipant) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Đây là cuộc hội thoại trực tiếp riêng giữa hai tài khoản khác."
                );
            }
            return conversation;
        }

        // 5. Nếu là hội thoại hộp thư cơ sở (tin nhắn của Bệnh nhân):
        // CHỈ Lễ tân (LE_TAN) và Bác sĩ (BAC_SI) tại cơ sở đó (cùng Admin) mới được phép xem và phản hồi!
        if (!canHandlePatientMessages(currentRole)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Chỉ Lễ tân, Bác sĩ thuộc cơ sở và Admin mới có quyền tiếp nhận và phản hồi tin nhắn của Bệnh nhân."
            );
        }

        return conversation;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getConversationMessages(Long currentUserId, Long conversationId) {
        ChatConversation conversation = requireConversationAccess(currentUserId, conversationId);
        List<ChatMessage> rawMessages = messageRepository.findByConversationIdOrderBySentAtAsc(conversationId);
        List<ChatMessageDTO> messageDTOs = mapMessagesToDTOs(conversation, rawMessages);

        User initiator = conversation.getUser();
        String initiatorRoleCode = initiator != null ? resolvePrimaryRoleCode(initiator.getUserId()) : "BENH_NHAN";
        String initiatorFullName = initiator != null ? resolveDisplayName(initiator) : "Người dùng";

        User recipient = conversation.getRecipientUser();
        boolean isDirect = recipient != null;
        String recipientRoleCode = isDirect ? resolvePrimaryRoleCode(recipient.getUserId()) : null;
        String recipientFullName = isDirect ? resolveDisplayName(recipient) : null;

        boolean currentIsInitiator = isDirect && initiator != null && Objects.equals(initiator.getUserId(), currentUserId);
        User counterpart = currentIsInitiator ? recipient : initiator;
        String counterpartRoleCode = currentIsInitiator ? recipientRoleCode : initiatorRoleCode;
        String counterpartFullName = currentIsInitiator ? recipientFullName : initiatorFullName;

        Branch branch = conversation.getBranch() != null
                ? conversation.getBranch()
                : (initiator != null ? initiator.getBranch() : null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversation.getConversationId());
        response.put("directConversation", isDirect);
        response.put("status", conversation.getStatus() != null ? conversation.getStatus().name() : ChatStatus.STAFF_HANDLING.name());
        response.put("branchId", branch != null ? branch.getBranchId() : null);
        response.put("branchName", branch != null ? branch.getBranchName() : "Chưa gắn cơ sở");
        response.put("initiatorUserId", initiator != null ? initiator.getUserId() : null);
        response.put("initiatorUsername", initiator != null ? initiator.getUsername() : null);
        response.put("initiatorFullName", initiatorFullName);
        response.put("initiatorPhone", initiator != null ? initiator.getPhoneNumber() : null);
        response.put("initiatorRoleCode", initiatorRoleCode);
        response.put("initiatorRoleLabel", toRoleLabel(initiatorRoleCode));
        response.put("recipientUserId", isDirect ? recipient.getUserId() : null);
        response.put("recipientUsername", isDirect ? recipient.getUsername() : null);
        response.put("recipientFullName", recipientFullName);
        response.put("recipientPhone", isDirect ? recipient.getPhoneNumber() : null);
        response.put("recipientRoleCode", recipientRoleCode);
        response.put("recipientRoleLabel", recipientRoleCode != null ? toRoleLabel(recipientRoleCode) : null);
        response.put("counterpartUserId", counterpart != null ? counterpart.getUserId() : null);
        response.put("counterpartUsername", counterpart != null ? counterpart.getUsername() : null);
        response.put("counterpartFullName", counterpartFullName);
        response.put("counterpartPhone", counterpart != null ? counterpart.getPhoneNumber() : null);
        response.put("counterpartRoleCode", counterpartRoleCode);
        response.put("counterpartRoleLabel", counterpartRoleCode != null ? toRoleLabel(counterpartRoleCode) : "Bệnh nhân");
        response.put("messages", messageDTOs);
        return response;
    }

    @Override
    @Transactional
    public ChatMessageDTO sendMessage(Long currentUserId, ChatSendRequestDTO request) {
        User sender = requireActiveUser(currentUserId);
        if (request == null || request.getMessageText() == null || request.getMessageText().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung tin nhắn không được để trống.");
        }

        String trimmedText = request.getMessageText().trim();
        if (trimmedText.length() > MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Nội dung tin nhắn vượt quá giới hạn cho phép (" + MAX_MESSAGE_LENGTH + " ký tự)."
            );
        }

        String clientMessageId = request.getClientMessageId() != null ? request.getClientMessageId().trim() : null;
        if (clientMessageId != null && !clientMessageId.isEmpty()) {
            String dedupKey = currentUserId + ":" + clientMessageId;
            ChatMessageDTO cached = recentClientMessages.get(dedupKey);
            if (cached != null) {
                return cached;
            }
        }

        String senderRoleCode = resolvePrimaryRoleCode(currentUserId);
        String senderRoleLabel = toRoleLabel(senderRoleCode);
        String senderFullName = resolveDisplayName(sender);

        ChatConversation conversation;
        if (request.getConversationId() != null && request.getConversationId() > 0) {
            conversation = requireConversationAccess(currentUserId, request.getConversationId());
            if (conversation.getStatus() == ChatStatus.CLOSED || conversation.getStatus() == ChatStatus.BOT_HANDLING) {
                conversation.setStatus(ChatStatus.STAFF_HANDLING);
            }
            if (conversation.getBranch() == null) {
                User initiator = conversation.getUser() != null ? conversation.getUser() : sender;
                String initRole = resolvePrimaryRoleCode(initiator.getUserId());
                conversation.setBranch(resolveUserHomeBranch(initiator, initRole));
            }
        } else if (request.getRecipientUserId() != null && request.getRecipientUserId() > 0) {
            if ("BENH_NHAN".equals(senderRoleCode)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Bệnh nhân chỉ được phép gửi tin nhắn tới hộp thư chung của cơ sở, không chọn người nhận cụ thể."
                );
            }
            conversation = resolveOrCreateConversationForRecipient(sender, senderRoleCode, request.getRecipientUserId(), null);
        } else {
            // Gửi từ Floating Widget khi chưa có conversationId và không chọn người nhận cụ thể:
            // Quản lý kho và Kế toán bắt buộc phải chọn đồng nghiệp trong cùng cơ sở để nhắn tin
            if (!"BENH_NHAN".equals(senderRoleCode) && !canHandlePatientMessages(senderRoleCode)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Vui lòng bấm 'Tìm người nhận' để chọn tài khoản trong cùng cơ sở mà bạn muốn gửi tin nhắn."
                );
            }
            Branch homeBranch = resolveUserHomeBranch(sender, senderRoleCode);
            conversation = getOrCreateConversationForInitiator(sender, homeBranch);
        }

        long existingMessageCount = messageRepository.countByConversation_ConversationId(conversation.getConversationId());
        User initiator = conversation.getUser() != null ? conversation.getUser() : sender;
        User recipient = conversation.getRecipientUser();
        boolean isDirect = recipient != null;
        boolean isInitiatorSending = Objects.equals(initiator.getUserId(), sender.getUserId());
        boolean isFirstMessage = !isDirect && existingMessageCount == 0 && isInitiatorSending;

        SenderType senderType = "BENH_NHAN".equals(senderRoleCode) ? SenderType.PATIENT : SenderType.STAFF;

        if (!isInitiatorSending && !isDirect) {
            staffProfileRepository.findByUser_UserId(sender.getUserId())
                    .ifPresent(conversation::setAssignedStaff);
        }

        LocalDateTime now = LocalDateTime.now();
        conversation.setLastMessageAt(now);
        conversation.setStatus(ChatStatus.STAFF_HANDLING);
        conversationRepository.save(conversation);

        ChatMessage message = new ChatMessage();
        message.setConversation(conversation);
        message.setSenderType(senderType);
        message.setSenderId(sender.getUserId());
        message.setMessageText(trimmedText);
        message.setIsRead(false);
        message.setSentAt(now);
        ChatMessage savedMessage = messageRepository.save(message);

        String initiatorRoleCode = resolvePrimaryRoleCode(initiator.getUserId());
        String recipientRoleCode = isDirect ? resolvePrimaryRoleCode(recipient.getUserId()) : null;
        Branch convBranch = conversation.getBranch();

        ChatMessageDTO dto = ChatMessageDTO.builder()
                .messageId(savedMessage.getMessageId())
                .conversationId(conversation.getConversationId())
                .branchId(convBranch != null ? convBranch.getBranchId() : null)
                .branchName(convBranch != null ? convBranch.getBranchName() : "Chưa gắn cơ sở")
                .senderId(sender.getUserId())
                .senderUsername(sender.getUsername())
                .senderFullName(senderFullName)
                .senderRoleCode(senderRoleCode)
                .senderRoleLabel(senderRoleLabel)
                .senderType(senderType.name())
                .initiatorUserId(initiator.getUserId())
                .initiatorUsername(initiator.getUsername())
                .initiatorFullName(resolveDisplayName(initiator))
                .initiatorRoleCode(initiatorRoleCode)
                .initiatorRoleLabel(toRoleLabel(initiatorRoleCode))
                .directConversation(isDirect)
                .recipientUserId(isDirect ? recipient.getUserId() : null)
                .recipientUsername(isDirect ? recipient.getUsername() : null)
                .recipientFullName(isDirect ? resolveDisplayName(recipient) : null)
                .recipientRoleCode(recipientRoleCode)
                .recipientRoleLabel(recipientRoleCode != null ? toRoleLabel(recipientRoleCode) : null)
                .messageText(savedMessage.getMessageText())
                .isRead(savedMessage.getIsRead())
                .sentAt(savedMessage.getSentAt() != null ? savedMessage.getSentAt() : now)
                .clientMessageId(clientMessageId)
                .firstMessageInConversation(isFirstMessage)
                .systemNotice(isFirstMessage ? FIRST_MESSAGE_SYSTEM_NOTICE : null)
                .build();

        if (clientMessageId != null && !clientMessageId.isEmpty()) {
            if (recentClientMessages.size() > 1000) {
                recentClientMessages.clear();
            }
            recentClientMessages.put(currentUserId + ":" + clientMessageId, dto);
        }

        return dto;
    }

    @Override
    @Transactional
    public Map<String, Object> markConversationAsRead(Long currentUserId, Long conversationId) {
        ChatConversation conversation = requireConversationAccess(currentUserId, conversationId);
        User initiator = conversation.getUser();
        Long initiatorUserId = initiator != null ? initiator.getUserId() : -1L;

        int updatedCount;
        if (conversation.getRecipientUser() != null || Objects.equals(currentUserId, initiatorUserId)) {
            updatedCount = messageRepository.markMessagesAsReadForInitiator(conversationId, currentUserId);
        } else {
            updatedCount = messageRepository.markMessagesAsReadForStaff(conversationId, initiatorUserId);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("conversationId", conversationId);
        res.put("updatedCount", updatedCount);
        res.put("unreadCount", 0);
        return res;
    }

    // =========================================================================
    // 6. HÀM CHUYỂN ĐỔI DANH SÁCH TIN NHẮN SANG DTO (BATCH OPTIMAL)
    // =========================================================================

    private List<ChatMessageDTO> mapMessagesToDTOs(ChatConversation conversation, List<ChatMessage> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return List.of();
        }

        User initiator = conversation.getUser();
        Long initiatorId = initiator != null ? initiator.getUserId() : null;
        String initiatorUsername = initiator != null ? initiator.getUsername() : null;
        String initiatorFullName = initiator != null ? resolveDisplayName(initiator) : "Người dùng";
        String initiatorRoleCode = initiatorId != null ? resolvePrimaryRoleCode(initiatorId) : "BENH_NHAN";
        String initiatorRoleLabel = toRoleLabel(initiatorRoleCode);

        User recipient = conversation.getRecipientUser();
        boolean isDirect = recipient != null;
        Long recipientId = isDirect ? recipient.getUserId() : null;
        String recipientUsername = isDirect ? recipient.getUsername() : null;
        String recipientFullName = isDirect ? resolveDisplayName(recipient) : null;
        String recipientRoleCode = isDirect ? resolvePrimaryRoleCode(recipientId) : null;
        String recipientRoleLabel = recipientRoleCode != null ? toRoleLabel(recipientRoleCode) : null;

        Branch branch = conversation.getBranch() != null
                ? conversation.getBranch()
                : (initiator != null ? initiator.getBranch() : null);
        Long branchId = branch != null ? branch.getBranchId() : null;
        String branchName = branch != null ? branch.getBranchName() : "Chưa gắn cơ sở";

        Map<Long, User> userCache = new HashMap<>();
        if (initiator != null && initiatorId != null) {
            userCache.put(initiatorId, initiator);
        }
        if (recipient != null && recipientId != null) {
            userCache.put(recipientId, recipient);
        }
        Map<Long, String> nameCache = new HashMap<>();
        Map<Long, String> roleCache = new HashMap<>();
        if (initiatorId != null) {
            nameCache.put(initiatorId, initiatorFullName);
            roleCache.put(initiatorId, initiatorRoleCode);
        }
        if (recipientId != null) {
            nameCache.put(recipientId, recipientFullName);
            roleCache.put(recipientId, recipientRoleCode);
        }

        List<ChatMessageDTO> result = new ArrayList<>(rawMessages.size());
        boolean firstInitiatorMsgMarked = false;

        for (ChatMessage m : rawMessages) {
            if (m.getSenderType() == SenderType.BOT) {
                continue;
            }
            Long sid = m.getSenderId() != null ? m.getSenderId() : initiatorId;
            User senderUser = sid != null
                    ? userCache.computeIfAbsent(sid, id -> userRepository.findById(id).orElse(null))
                    : null;

            String sFullName = sid != null
                    ? nameCache.computeIfAbsent(sid, id -> senderUser != null ? resolveDisplayName(senderUser) : "Người dùng #" + id)
                    : "Người dùng";
            String sRoleCode = sid != null
                    ? roleCache.computeIfAbsent(sid, this::resolvePrimaryRoleCode)
                    : "BENH_NHAN";
            String sUsername = senderUser != null ? senderUser.getUsername() : "";

            boolean isFirstInitiatorMsg = false;
            if (!isDirect && !firstInitiatorMsgMarked && Objects.equals(sid, initiatorId)) {
                firstInitiatorMsgMarked = true;
                isFirstInitiatorMsg = true;
            }

            result.add(ChatMessageDTO.builder()
                    .messageId(m.getMessageId())
                    .conversationId(conversation.getConversationId())
                    .branchId(branchId)
                    .branchName(branchName)
                    .senderId(sid)
                    .senderUsername(sUsername)
                    .senderFullName(sFullName)
                    .senderRoleCode(sRoleCode)
                    .senderRoleLabel(toRoleLabel(sRoleCode))
                    .senderType(m.getSenderType() != null ? m.getSenderType().name() : SenderType.PATIENT.name())
                    .initiatorUserId(initiatorId)
                    .initiatorUsername(initiatorUsername)
                    .initiatorFullName(initiatorFullName)
                    .initiatorRoleCode(initiatorRoleCode)
                    .initiatorRoleLabel(initiatorRoleLabel)
                    .directConversation(isDirect)
                    .recipientUserId(recipientId)
                    .recipientUsername(recipientUsername)
                    .recipientFullName(recipientFullName)
                    .recipientRoleCode(recipientRoleCode)
                    .recipientRoleLabel(recipientRoleLabel)
                    .messageText(m.getMessageText())
                    .isRead(m.getIsRead())
                    .sentAt(m.getSentAt())
                    .firstMessageInConversation(isFirstInitiatorMsg)
                    .systemNotice(isFirstInitiatorMsg ? FIRST_MESSAGE_SYSTEM_NOTICE : null)
                    .build());
        }

        return result;
    }
}
