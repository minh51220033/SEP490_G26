package com.cvdcms.controller;

import com.cvdcms.DTO.ChangePasswordDTO;
import com.cvdcms.DTO.CompleteProfilesDTO;
import com.cvdcms.DTO.PatientProfileFormDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.TokenType;
import com.cvdcms.patientportal.AccountPatientProfile;
import com.cvdcms.patientportal.AccountPatientProfileRepository;
import com.cvdcms.repository.*;
import com.cvdcms.service.PatientProfileService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Controller
public class ProfileController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final BranchRepository branchRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final PatientProfileService patientProfileService;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final AccountPatientProfileRepository accountPatientProfileRepository;
    private final TokenRepository tokenRepository;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    public ProfileController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            BranchRepository branchRepository,
            PatientProfileRepository patientProfileRepository,
            PatientProfileService patientProfileService,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            AccountPatientProfileRepository accountPatientProfileRepository,
            TokenRepository tokenRepository) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.branchRepository = branchRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.patientProfileService = patientProfileService;
        this.staffProfileRepository = staffProfileRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.accountPatientProfileRepository = accountPatientProfileRepository;
        this.tokenRepository = tokenRepository;
    }

    // =========================================================
    // HÀM HỖ TRỢ: LẤY TÀI KHOẢN ĐANG ĐĂNG NHẬP & ĐỒNG BỘ DỮ LIỆU
    // =========================================================

    private User getLoggedInUser(HttpSession session) {
        Object value = session.getAttribute("loggedInUser");

        if (!(value instanceof User)) {
            return null;
        }

        User sessionUser = (User) value;

        if (sessionUser.getUserId() == null) {
            return null;
        }

        return userRepository.findById(sessionUser.getUserId())
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .orElse(null);
    }

    private boolean isPatientUser(User user) {
        return user != null
                && user.getRoles() != null
                && user.getRoles().stream().anyMatch(role -> role != null
                        && role.getRoleName() != null
                        && ("BENH_NHAN".equalsIgnoreCase(role.getRoleName().trim())
                                || "ROLE_BENH_NHAN".equalsIgnoreCase(role.getRoleName().trim())));
    }

    private boolean isAdminUser(User user) {
        return user != null
                && user.getRoles() != null
                && user.getRoles().stream().anyMatch(role -> role != null
                        && role.getRoleName() != null
                        && ("ADMIN".equalsIgnoreCase(role.getRoleName().trim())
                                || "ROLE_ADMIN".equalsIgnoreCase(role.getRoleName().trim())));
    }

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final Pattern PHONE_PATTERN = Pattern.compile("^0\\d{9,10}$");

    /**
     * Đồng bộ hai chiều giữa patient_profiles và account_patient_profiles
     * để dữ liệu giữa /profile và /portal/records luôn nhất quán.
     */
    private List<PatientProfile> getAndSyncPatientProfiles(User user) {
        if (user == null || user.getUserId() == null) {
            return Collections.emptyList();
        }
        Map<Long, PatientProfile> merged = new LinkedHashMap<>();

        List<PatientProfile> directProfiles = patientProfileRepository
                .findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(user.getUserId());

        for (PatientProfile profile : directProfiles) {
            merged.put(profile.getProfileId(), profile);
            if (accountPatientProfileRepository.findByProfile_ProfileId(profile.getProfileId()).isEmpty()) {
                AccountPatientProfile link = new AccountPatientProfile();
                link.setAccount(user);
                link.setProfile(profile);
                link.setAccessType(Boolean.TRUE.equals(profile.getIsDefault())
                        ? AccountPatientProfile.AccessType.SELF
                        : AccountPatientProfile.AccessType.DEPENDENT);
                accountPatientProfileRepository.save(link);
            }
        }

        List<AccountPatientProfile> linkedProfiles = accountPatientProfileRepository
                .findByAccount_UserIdOrderByProfile_ProfileIdDesc(user.getUserId());
        for (AccountPatientProfile link : linkedProfiles) {
            if (link.getProfile() != null) {
                merged.putIfAbsent(link.getProfile().getProfileId(), link.getProfile());
            }
        }

        List<PatientProfile> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> {
            boolean aDef = Boolean.TRUE.equals(a.getIsDefault());
            boolean bDef = Boolean.TRUE.equals(b.getIsDefault());
            if (aDef != bDef) {
                return aDef ? -1 : 1;
            }
            return Long.compare(
                    b.getProfileId() != null ? b.getProfileId() : 0L,
                    a.getProfileId() != null ? a.getProfileId() : 0L);
        });
        return result;
    }

    /**
     * Cấp mới Refresh Token (TokenType.REFRESH) và thu hồi các Refresh Token cũ.
     */
    private Token rotateRefreshToken(User user, HttpSession session) {
        if (user == null || user.getUserId() == null) {
            return null;
        }
        List<Token> oldTokens = tokenRepository
                .findByUser_UserIdAndTokenTypeAndIsUsedFalseOrderByCreatedAtDesc(
                        user.getUserId(), TokenType.REFRESH);
        for (Token old : oldTokens) {
            old.setIsUsed(true);
        }
        if (!oldTokens.isEmpty()) {
            tokenRepository.saveAll(oldTokens);
        }

        Token newToken = new Token();
        newToken.setToken(UUID.randomUUID().toString());
        newToken.setTokenType(TokenType.REFRESH);
        newToken.setIsUsed(false);
        newToken.setExpiryDate(LocalDateTime.now().plusHours(24));
        newToken.setUser(user);
        Token saved = tokenRepository.save(newToken);

        if (session != null) {
            session.setAttribute("refreshToken", saved.getToken());
            session.setAttribute("refreshTokenExpiry", saved.getExpiryDate());
        }
        return saved;
    }

    /**
     * Lấy Refresh Token đang hoạt động của tài khoản (nếu chưa có thì tự động cấp
     * mới cho phiên hiện tại).
     */
    private Token getOrIssueActiveRefreshToken(User user, HttpSession session) {
        if (user == null || user.getUserId() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        List<Token> activeTokens = tokenRepository
                .findByUser_UserIdAndTokenTypeAndIsUsedFalseOrderByCreatedAtDesc(
                        user.getUserId(), TokenType.REFRESH);

        for (Token t : activeTokens) {
            if (t.getExpiryDate() != null && t.getExpiryDate().isAfter(now)) {
                if (session != null) {
                    session.setAttribute("refreshToken", t.getToken());
                    session.setAttribute("refreshTokenExpiry", t.getExpiryDate());
                }
                return t;
            } else {
                t.setIsUsed(true);
                tokenRepository.save(t);
            }
        }
        return rotateRefreshToken(user, session);
    }

    /**
     * Nạp đầy đủ các thuộc tính chung cho mọi màn hình /profile/* để tương thích
     * hoàn toàn
     * với layout app-shell (sidebar, header topbar) và liên kết dữ liệu Nhân sự /
     * Bệnh nhân.
     */
    private List<PatientProfile> populateCommonProfileModel(User user, HttpSession session, Model model) {
        model.addAttribute("user", user);

        boolean isPatient = isPatientUser(user);
        boolean isAdmin = isAdminUser(user);
        model.addAttribute("isPatient", isPatient);
        model.addAttribute("isAdmin", isAdmin);

        List<String> roleNames = user.getRoles() == null ? Collections.emptyList()
                : user.getRoles().stream()
                        .filter(Objects::nonNull)
                        .map(Role::getRoleName)
                        .filter(Objects::nonNull)
                        .toList();
        List<String> roleDisplayNames = user.getRoles() == null ? Collections.emptyList()
                : user.getRoles().stream()
                        .filter(Objects::nonNull)
                        .map(r -> r.getDisplayName() != null && !r.getDisplayName().isBlank()
                                ? r.getDisplayName()
                                : r.getRoleName())
                        .filter(Objects::nonNull)
                        .toList();

        model.addAttribute("roleNames", roleNames);
        model.addAttribute("roleDisplayNames", roleDisplayNames);
        session.setAttribute("roleNames", roleNames);

        // Hồ sơ nhân sự (nếu tài khoản là Bác sĩ / Lễ tân / Kế toán / Quản lý kho /
        // Admin...)
        StaffProfile staffProfile = staffProfileRepository
                .findByUser_UserId(user.getUserId())
                .orElse(null);
        List<StaffBranchAssignment> staffBranches = Collections.emptyList();
        if (staffProfile != null) {
            staffBranches = staffBranchAssignmentRepository.findByStaffId(staffProfile.getStaffId())
                    .stream()
                    .filter(a -> a.getStatus() == null || a.getStatus() == AssignmentStatus.ACTIVE)
                    .toList();
        }
        model.addAttribute("staffProfile", staffProfile);
        model.addAttribute("staffBranches", staffBranches);

        // Danh sách hồ sơ bệnh nhân (chỉ truy xuất cho tài khoản bệnh nhân thực tế)
        List<PatientProfile> patientProfiles = Collections.emptyList();
        PatientProfile defaultPatientProfile = null;
        if (isPatient) {
            patientProfiles = getAndSyncPatientProfiles(user);
            defaultPatientProfile = patientProfiles.stream()
                    .filter(p -> Boolean.TRUE.equals(p.getIsDefault()))
                    .findFirst()
                    .orElse(patientProfiles.isEmpty() ? null : patientProfiles.get(0));
            if (defaultPatientProfile != null) {
                session.setAttribute("defaultPatientProfile", defaultPatientProfile);
            }
        }
        model.addAttribute("patientProfiles", patientProfiles);
        model.addAttribute("defaultPatientProfile", defaultPatientProfile);

        // Xác định tên hiển thị trên Topbar & Hero Banner
        String displayName = user.getUsername();
        if (!isPatient) {
            if (staffProfile != null
                    && staffProfile.getFullName() != null
                    && !staffProfile.getFullName().isBlank()) {
                displayName = staffProfile.getFullName();
            } else if (isAdmin) {
                displayName = "Quản trị viên Hệ thống";
            }
        } else {
            if (defaultPatientProfile != null
                    && defaultPatientProfile.getFullName() != null
                    && !defaultPatientProfile.getFullName().isBlank()) {
                displayName = defaultPatientProfile.getFullName();
            }
        }
        model.addAttribute("displayName", displayName);
        session.setAttribute("displayName", displayName);

        // Xác định cơ sở hiện tại
        String currentBranchName = null;
        if (user.getBranch() != null && user.getBranch().getBranchName() != null) {
            currentBranchName = user.getBranch().getBranchName();
        } else if (defaultPatientProfile != null
                && defaultPatientProfile.getPreferredBranch() != null
                && defaultPatientProfile.getPreferredBranch().getBranchName() != null) {
            currentBranchName = defaultPatientProfile.getPreferredBranch().getBranchName();
        } else if (!staffBranches.isEmpty() && staffBranches.get(0).getBranch() != null) {
            currentBranchName = staffBranches.get(0).getBranch().getBranchName();
        }
        model.addAttribute("currentBranchName", currentBranchName);
        if (currentBranchName != null) {
            session.setAttribute("currentBranchName", currentBranchName);
        }

        // Thông tin nhân khẩu học bổ sung (ngày sinh, giới tính, địa chỉ, người khẩn
        // cấp, CCCD)
        LocalDate profileDob = null;
        String profileGender = null;
        String profileAddress = null;
        String emergencyContact = null;
        String profileAvatarUrl = null;
        String profileCitizenId = null;

        if (!isPatient) {
            if (staffProfile != null) {
                profileDob = staffProfile.getDob();
                profileCitizenId = staffProfile.getIdCardNumber();
                if (staffProfile.getGender() != null) {
                    profileGender = switch (staffProfile.getGender().name()) {
                        case "MALE" -> "Nam";
                        case "FEMALE" -> "Nữ";
                        default -> "Khác";
                    };
                }
            }
        } else {
            if (defaultPatientProfile != null) {
                profileDob = defaultPatientProfile.getDob();
                profileGender = defaultPatientProfile.getGender();
                profileAddress = defaultPatientProfile.getAddress();
                profileAvatarUrl = defaultPatientProfile.getAvatarUrl();
                profileCitizenId = defaultPatientProfile.getIdCardNumber();
                if (defaultPatientProfile.getGuardianName() != null
                        && !defaultPatientProfile.getGuardianName().isBlank()) {
                    emergencyContact = defaultPatientProfile.getGuardianName();
                    if (defaultPatientProfile.getGuardianPhone() != null
                            && !defaultPatientProfile.getGuardianPhone().isBlank()) {
                        emergencyContact += " (" + defaultPatientProfile.getGuardianPhone() + ")";
                    }
                }
            }
        }

        model.addAttribute("profileDob", profileDob);
        model.addAttribute("profileGender", profileGender);
        model.addAttribute("profileAddress", profileAddress);
        model.addAttribute("emergencyContact", emergencyContact);
        model.addAttribute("profileAvatarUrl", profileAvatarUrl);
        model.addAttribute("profileCitizenId", profileCitizenId);

        // Khởi tạo changePasswordDTO nếu chưa có trong Model (dùng cho modal đổi mật
        // khẩu tại trang)
        if (!model.containsAttribute("changePasswordDTO")) {
            model.addAttribute("changePasswordDTO", new ChangePasswordDTO());
        }

        // Thông tin Refresh Token bảo mật
        Token activeRefreshToken = getOrIssueActiveRefreshToken(user, session);
        model.addAttribute("activeRefreshToken", activeRefreshToken);

        return patientProfiles;
    }

    // =========================================================
    // 1. XEM THÔNG TIN TÀI KHOẢN VÀ DANH SÁCH HỒ SƠ
    // GET /profile
    // =========================================================
    @GetMapping("/profile")
    @Transactional
    public String profile(HttpSession session, Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        populateCommonProfileModel(user, session, model);

        // Thông báo chỉ hiển thị một lần (từ session hoặc flash attribute).
        Object success = session.getAttribute("profileSuccess");
        if (success != null) {
            model.addAttribute("success", success);
            session.removeAttribute("profileSuccess");
        } else if (model.containsAttribute("profileSuccess")) {
            model.addAttribute("success", model.asMap().get("profileSuccess"));
        }

        return "profile/profile";
    }

    // =========================================================
    // 2. HIỂN THỊ FORM SỬA THÔNG TIN TÀI KHOẢN & HỒ SƠ
    // GET /profile/edit & GET /edit
    // =========================================================

    @GetMapping("/profile/edit")
    @Transactional
    public String editProfile(
            @RequestParam(value = "profileId", required = false) Long profileId,
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        loadEditProfileData(user, profileId, session, model);
        return "profile/edit";
    }

    @GetMapping("/edit")
    @Transactional
    public String showEditProfile(
            @RequestParam(required = false) Long profileId,
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes) {

        User user = getLoggedInUser(session);

        if (user == null) {
            return "redirect:/login";
        }

        if (profileId != null) {
            Optional<PatientProfile> result = patientProfileRepository.findByProfileIdAndUser_UserId(profileId,
                    user.getUserId());
            if (result.isEmpty()) {
                redirectAttributes.addFlashAttribute(
                        "error",
                        "Hồ sơ không tồn tại hoặc không thuộc tài khoản của bạn.");
                return "redirect:/profile/edit";
            }
        }

        loadEditProfileData(user, profileId, session, model);
        return "profile/edit";
    }

    // =========================================================
    // 3. CẬP NHẬT EMAIL / SỐ ĐIỆN THOẠI TÀI KHOẢN
    // POST /profile/edit
    // =========================================================

    @PostMapping("/profile/edit")
    @Transactional
    public String updateProfile(
            @RequestParam("email") String emailInput,
            @RequestParam("phoneNumber") String phoneInput,
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        String oldPhone = user.getPhoneNumber();

        // Chuẩn hóa dữ liệu đầu vào.
        String email = emailInput == null
                ? ""
                : emailInput.trim().toLowerCase(Locale.ROOT);

        String phoneNumber = phoneInput == null
                ? ""
                : phoneInput.trim();

        // Kiểm tra email bắt buộc và định dạng.
        if (email.isBlank()
                || email.length() > 254
                || !EMAIL_PATTERN.matcher(email).matches()) {

            model.addAttribute("error", "Email không hợp lệ. Vui lòng nhập đúng định dạng (ví dụ: ten@domain.com).");
            loadEditProfileData(user, null, session, model);
            return "profile/edit";
        }

        // Kiểm tra số điện thoại bắt buộc và định dạng.
        if (!PHONE_PATTERN.matcher(phoneNumber).matches()) {
            model.addAttribute(
                    "error",
                    "Số điện thoại phải bắt đầu bằng số 0 và gồm 10 hoặc 11 chữ số.");
            loadEditProfileData(user, null, session, model);
            return "profile/edit";
        }

        // Kiểm tra email có thuộc tài khoản khác hay không.
        boolean emailUsedByAnotherAccount = userRepository
                .findByEmail(email)
                .filter(existing -> !existing.getUserId().equals(user.getUserId()))
                .isPresent();

        if (emailUsedByAnotherAccount) {
            model.addAttribute(
                    "error",
                    "Địa chỉ email này đã được tài khoản khác sử dụng.");
            loadEditProfileData(user, null, session, model);
            return "profile/edit";
        }

        // Kiểm tra số điện thoại có thuộc tài khoản khác hay không.
        boolean phoneUsedByAnotherAccount = userRepository
                .findByPhoneNumber(phoneNumber)
                .filter(existing -> !existing.getUserId().equals(user.getUserId()))
                .isPresent();

        if (phoneUsedByAnotherAccount) {
            model.addAttribute(
                    "error",
                    "Số điện thoại này đã được tài khoản khác sử dụng.");
            loadEditProfileData(user, null, session, model);
            return "profile/edit";
        }

        // 1. Cập nhật thông tin thuộc User.
        user.setEmail(email);
        user.setPhoneNumber(phoneNumber);
        User updatedUser = userRepository.save(user);

        // 2. Liên kết móc nối: Đồng bộ Email & SĐT sang Hồ sơ Nhân sự (StaffProfile)
        // nếu có
        staffProfileRepository.findByUser_UserId(user.getUserId()).ifPresent(staff -> {
            staff.setEmail(email);
            staff.setPhoneNumber(phoneNumber);
            staffProfileRepository.save(staff);
        });

        // 3. Liên kết móc nối: Đồng bộ SĐT sang Hồ sơ Bệnh nhân mặc định nếu SĐT hồ sơ
        // đang trùng SĐT cũ của tài khoản
        patientProfileRepository.findFirstByUser_UserIdAndIsDefaultTrue(user.getUserId()).ifPresent(defProfile -> {
            if (defProfile.getPhoneNumber() == null
                    || defProfile.getPhoneNumber().isBlank()
                    || (oldPhone != null && oldPhone.equals(defProfile.getPhoneNumber()))) {
                defProfile.setPhoneNumber(phoneNumber);
                patientProfileRepository.save(defProfile);
            }
        });

        // Cập nhật session để dữ liệu không bị cũ.
        session.setAttribute("loggedInUser", updatedUser);

        session.setAttribute(
                "profileSuccess",
                "Cập nhật thông tin liên hệ tài khoản thành công và đã đồng bộ hồ sơ liên quan.");

        return "redirect:/profile";
    }

    // =========================================================
    // 4. HIỂN THỊ MODAL ĐỔI MẬT KHẨU
    // GET /profile/change-password
    // =========================================================

    @GetMapping("/profile/change-password")
    public String changePassword(HttpSession session) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        // Tự động mở Modal Đổi mật khẩu ngay tại trang /profile
        return "redirect:/profile?openPasswordModal=true";
    }

    // =========================================================
    // 5. XỬ LÝ ĐỔI MẬT KHẨU (BCRYPT & REFRESH TOKEN ROTATION)
    // POST /profile/change-password
    // =========================================================

    @PostMapping("/profile/change-password")
    @Transactional
    public String processChangePassword(
            @Valid @ModelAttribute("changePasswordDTO") ChangePasswordDTO dto,
            BindingResult bindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        // Hiển thị lỗi validation từ ChangePasswordDTO
        if (bindingResult.hasErrors()) {
            String errorMsg = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("passwordError", errorMsg);
            return "redirect:/profile?openPasswordModal=true";
        }

        // Đọc mật khẩu hiện tại từ database và kiểm tra bằng BCrypt
        if (dto.getCurrentPassword() == null
                || !passwordEncoder.matches(
                        dto.getCurrentPassword(),
                        user.getPassword())) {

            redirectAttributes.addFlashAttribute(
                    "passwordError",
                    "Mật khẩu hiện tại không đúng. Vui lòng kiểm tra lại.");
            return "redirect:/profile?openPasswordModal=true";
        }

        String newPassword = dto.getNewPassword();
        String confirmPassword = dto.getConfirmPassword();

        // Kiểm tra mật khẩu mới
        if (newPassword == null
                || newPassword.length() < 8
                || newPassword.length() > 64) {

            redirectAttributes.addFlashAttribute(
                    "passwordError",
                    "Mật khẩu mới phải từ 8 đến 64 ký tự.");
            return "redirect:/profile?openPasswordModal=true";
        }

        // Không cho đặt lại đúng mật khẩu đang dùng
        if (passwordEncoder.matches(
                newPassword,
                user.getPassword())) {

            redirectAttributes.addFlashAttribute(
                    "passwordError",
                    "Mật khẩu mới phải khác mật khẩu hiện tại.");
            return "redirect:/profile?openPasswordModal=true";
        }

        // Xác nhận mật khẩu mới
        if (!newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute(
                    "passwordError",
                    "Mật khẩu xác nhận không khớp với mật khẩu mới.");
            return "redirect:/profile?openPasswordModal=true";
        }

        // 1. Mã hóa BCrypt trước khi lưu database
        user.setPassword(passwordEncoder.encode(newPassword));
        User updatedUser = userRepository.save(user);

        // 2. Thu hồi toàn bộ Refresh Token cũ và cấp mới Refresh Token cho phiên hiện
        // tại
        rotateRefreshToken(updatedUser, session);

        session.setAttribute("loggedInUser", updatedUser);

        redirectAttributes.addFlashAttribute(
                "success",
                "Đổi mật khẩu thành công! Mật khẩu mới đã được mã hóa an toàn và làm mới phiên xác thực.");

        return "redirect:/profile";
    }

    // =========================================================
    // 5B. LÀM MỚI PHIÊN BẢO MẬT (ROTATE REFRESH TOKEN)
    // POST /profile/refresh-token
    // =========================================================

    @PostMapping("/profile/refresh-token")
    @Transactional
    public String manualRotateRefreshToken(HttpSession session) {
        User user = getLoggedInUser(session);
        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        rotateRefreshToken(user, session);
        session.setAttribute(
                "profileSuccess",
                "Đã thu hồi các Refresh Token cũ và cấp mã Refresh Token mới cho phiên đăng nhập hiện tại.");
        return "redirect:/profile";
    }

    // =========================================================
    // 6. HOÀN THIỆN HỒ SƠ BỆNH NHÂN BAN ĐẦU
    // GET /profile/complete & POST /profile/complete
    // =========================================================

    @GetMapping("/profile/complete")
    @Transactional
    public String completeProfile(HttpSession session, Model model) {
        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        if (patientProfileRepository
                .existsByUser_UserIdAndIsDefaultTrue(user.getUserId())) {
            return "redirect:/home";
        }

        populateCommonProfileModel(user, session, model);
        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc());
        model.addAttribute("completeProfilesDTO", new CompleteProfilesDTO());

        return "profile/complete";
    }

    @PostMapping(value = "/profile/complete", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public String saveCompleteProfiles(
            @ModelAttribute("completeProfilesDTO") CompleteProfilesDTO dto,
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        List<PatientProfileFormDTO> profiles = dto.getProfiles();

        try {
            if (profiles == null || profiles.isEmpty()) {
                throw new IllegalArgumentException(
                        "Vui lòng tạo ít nhất một hồ sơ.");
            }

            // Bước 1: kiểm tra tất cả hồ sơ trước khi lưu.
            for (int i = 0; i < profiles.size(); i++) {
                PatientProfileFormDTO p = profiles.get(i);

                if (p.getFullName() == null
                        || p.getFullName().isBlank()
                        || p.getFullName().trim().length() > 100
                        || p.getGender() == null
                        || p.getGender().isBlank()
                        || p.getAddress() == null
                        || p.getAddress().isBlank()
                        || p.getAddress().trim().length() > 255
                        || p.getBranchId() == null
                        || p.getHasUnderlyingDisease() == null) {
                    throw new IllegalArgumentException(
                            "Vui lòng kiểm tra thông tin bắt buộc ở hồ sơ "
                                    + (i + 1) + ".");
                }

                if (p.getDob() != null
                        && p.getDob().isAfter(LocalDate.now())) {
                    throw new IllegalArgumentException(
                            "Ngày sinh ở hồ sơ " + (i + 1)
                                    + " không được ở tương lai.");
                }

                if (Boolean.TRUE.equals(p.getHasUnderlyingDisease())
                        && (p.getUnderlyingDiseaseDetails() == null
                                || p.getUnderlyingDiseaseDetails().isBlank())) {
                    throw new IllegalArgumentException(
                            "Vui lòng nhập bệnh nền ở hồ sơ "
                                    + (i + 1) + ".");
                }
            }

            // Bước 2: Tạo từng hồ sơ bệnh nhân.
            for (int i = 0; i < profiles.size(); i++) {
                PatientProfileFormDTO p = profiles.get(i);

                String avatarUrl = saveAvatar(p.getAvatar());

                patientProfileService.createProfile(
                        user,
                        p.getFullName(),
                        p.getDob(),
                        p.getGender(),
                        user.getPhoneNumber(),
                        p.getAddress(),
                        p.getBranchId(),
                        p.getHasUnderlyingDisease(),
                        p.getUnderlyingDiseaseDetails(),
                        avatarUrl);
            }

            session.setAttribute(
                    "profileSuccess",
                    "Đã tạo thành công " + profiles.size()
                            + " hồ sơ bệnh nhân.");

            return "redirect:/home";

        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
        } catch (IOException ex) {
            model.addAttribute(
                    "error",
                    "Không thể tải ảnh đại diện. Vui lòng thử lại.");
        }

        populateCommonProfileModel(user, session, model);
        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc());

        return "profile/complete";
    }

    @PostMapping("/profile/complete")
    @Transactional
    public String saveCompleteProfile(
            @RequestParam("fullName") String fullName,
            @RequestParam(value = "dob", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
            @RequestParam("gender") String gender,
            @RequestParam("phoneNumber") String phoneNumber,
            @RequestParam("address") String address,
            @RequestParam("branchId") Long branchId,
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        fullName = fullName == null ? "" : fullName.trim();
        gender = gender == null ? "" : gender.trim();
        phoneNumber = phoneNumber == null ? "" : phoneNumber.trim();
        address = address == null ? "" : address.trim();

        if (fullName.isBlank()
                || fullName.length() > 100
                || gender.isBlank()
                || gender.length() > 20
                || phoneNumber.isBlank()
                || phoneNumber.length() > 20
                || address.isBlank()
                || address.length() > 255
                || branchId == null) {

            model.addAttribute(
                    "error",
                    "Vui lòng nhập đầy đủ và kiểm tra lại thông tin.");
            populateCommonProfileModel(user, session, model);
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc());
            return "profile/complete";
        }

        if (dob != null && dob.isAfter(LocalDate.now())) {
            model.addAttribute(
                    "error",
                    "Ngày sinh không được lớn hơn ngày hiện tại.");
            populateCommonProfileModel(user, session, model);
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc());
            return "profile/complete";
        }

        try {
            patientProfileService.createDefaultProfile(
                    user,
                    fullName,
                    dob,
                    gender,
                    phoneNumber,
                    address,
                    branchId);

            session.setAttribute(
                    "profileSuccess",
                    "Hoàn thiện hồ sơ bệnh nhân thành công.");

            return "redirect:/profile";

        } catch (IllegalArgumentException | IllegalStateException ex) {
            model.addAttribute("error", ex.getMessage());
            populateCommonProfileModel(user, session, model);
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc());
            return "profile/complete";
        }
    }

    private String saveAvatar(MultipartFile avatar) throws IOException {
        if (avatar == null || avatar.isEmpty()) {
            return null;
        }

        if (avatar.getSize() > 5 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "Ảnh đại diện không được vượt quá 5 MB.");
        }

        String contentType = avatar.getContentType();

        String extension;
        if (MediaType.IMAGE_JPEG_VALUE.equals(contentType)) {
            extension = ".jpg";
        } else if (MediaType.IMAGE_PNG_VALUE.equals(contentType)) {
            extension = ".png";
        } else if ("image/webp".equals(contentType)) {
            extension = ".webp";
        } else {
            throw new IllegalArgumentException(
                    "Chỉ chấp nhận ảnh JPG, PNG hoặc WebP.");
        }

        Path directory = Paths.get(uploadDir, "avatars")
                .toAbsolutePath().normalize();

        Files.createDirectories(directory);

        String fileName = UUID.randomUUID() + extension;
        Path destination = directory.resolve(fileName).normalize();

        if (!destination.startsWith(directory)) {
            throw new IllegalArgumentException("Đường dẫn ảnh không hợp lệ.");
        }

        avatar.transferTo(destination);

        return "/uploads/avatars/" + fileName;
    }

    /**
     * Nạp lại dữ liệu cần thiết khi hiển thị trang chỉnh sửa.
     */
    private void loadEditProfileData(
            User user,
            Long profileId,
            HttpSession session,
            Model model) {

        List<PatientProfile> patientProfiles = populateCommonProfileModel(user, session, model);

        PatientProfile selectedProfile = null;

        if (profileId != null) {
            selectedProfile = patientProfiles.stream()
                    .filter(p -> profileId.equals(p.getProfileId()))
                    .findFirst()
                    .orElse(null);

            if (selectedProfile == null) {
                model.addAttribute(
                        "error",
                        "Hồ sơ không tồn tại hoặc không thuộc tài khoản của bạn.");
            }
        } else if (!patientProfiles.isEmpty()) {
            selectedProfile = patientProfiles.get(0);
        }

        model.addAttribute("patientProfile", selectedProfile);
        model.addAttribute(
                "selectedProfileId",
                selectedProfile == null
                        ? null
                        : selectedProfile.getProfileId());
        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc());
    }

    // =========================================================
    // 7. CẬP NHẬT HỒ SƠ BỆNH NHÂN
    // POST /profile/edit-patient
    // =========================================================

    @PostMapping(value = "/profile/edit-patient", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public String updatePatientProfile(
            @RequestParam("profileId") Long profileId,
            @RequestParam("fullName") String fullName,
            @RequestParam(value = "dob", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
            @RequestParam("gender") String gender,
            @RequestParam("phoneNumber") String phoneNumber,
            @RequestParam("address") String address,
            @RequestParam("hasUnderlyingDisease") Boolean hasUnderlyingDisease,
            @RequestParam(value = "underlyingDiseaseDetails", required = false) String underlyingDiseaseDetails,
            @RequestParam(value = "avatar", required = false) MultipartFile avatar,
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        fullName = fullName == null ? "" : fullName.trim();
        gender = gender == null ? "" : gender.trim();
        phoneNumber = phoneNumber == null ? "" : phoneNumber.trim();
        address = address == null ? "" : address.trim();

        underlyingDiseaseDetails = underlyingDiseaseDetails == null
                ? ""
                : underlyingDiseaseDetails.trim();

        List<PatientProfile> ownedProfiles = getAndSyncPatientProfiles(user);
        PatientProfile profile = ownedProfiles.stream()
                .filter(p -> profileId != null && profileId.equals(p.getProfileId()))
                .findFirst()
                .orElse(null);

        if (profile == null) {
            session.setAttribute(
                    "profileSuccess",
                    "Không tìm thấy hồ sơ hoặc bạn không có quyền chỉnh sửa.");
            return "redirect:/profile";
        }

        if (fullName.isBlank()
                || fullName.length() > 100
                || gender.isBlank()
                || gender.length() > 20
                || phoneNumber.isBlank()
                || !PHONE_PATTERN.matcher(phoneNumber).matches()
                || address.isBlank()
                || address.length() > 255
                || hasUnderlyingDisease == null) {

            model.addAttribute(
                    "error",
                    "Vui lòng nhập đầy đủ thông tin hợp lệ (SĐT gồm 10–11 chữ số bắt đầu bằng 0).");
            loadEditProfileData(user, profileId, session, model);
            return "profile/edit";
        }

        if (dob != null && dob.isAfter(LocalDate.now())) {
            model.addAttribute(
                    "error",
                    "Ngày sinh không được lớn hơn ngày hiện tại.");
            loadEditProfileData(user, profileId, session, model);
            return "profile/edit";
        }

        if (Boolean.TRUE.equals(hasUnderlyingDisease)
                && underlyingDiseaseDetails.isBlank()) {

            model.addAttribute("error", "Vui lòng nhập mô tả bệnh nền.");
            loadEditProfileData(user, profileId, session, model);
            return "profile/edit";
        }

        if (Boolean.FALSE.equals(hasUnderlyingDisease)) {
            underlyingDiseaseDetails = "";
        }

        try {
            if (avatar != null && !avatar.isEmpty()) {
                profile.setAvatarUrl(saveAvatar(avatar));
            }

            profile.setFullName(fullName);
            profile.setDob(dob);
            profile.setGender(gender);
            profile.setPhoneNumber(phoneNumber);
            profile.setAddress(address);
            profile.setHasUnderlyingDisease(hasUnderlyingDisease);
            profile.setUnderlyingDiseaseDetails(
                    underlyingDiseaseDetails.isBlank()
                            ? null
                            : underlyingDiseaseDetails);

            patientProfileRepository.save(profile);

            if (Boolean.TRUE.equals(profile.getIsDefault())) {
                session.setAttribute("defaultPatientProfile", profile);
                session.setAttribute("displayName", profile.getFullName());
            }

            session.setAttribute(
                    "profileSuccess",
                    "Cập nhật hồ sơ bệnh nhân \"" + fullName + "\" thành công.");

            return "redirect:/profile";

        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
            loadEditProfileData(user, profileId, session, model);
            return "profile/edit";

        } catch (IOException ex) {
            model.addAttribute(
                    "error",
                    "Không thể tải ảnh đại diện. Vui lòng thử lại.");
            loadEditProfileData(user, profileId, session, model);
            return "profile/edit";
        }
    }

    // =========================================================
    // 8. TẠO THÊM HỒ SƠ BỆNH NHÂN (NGƯỜI THÂN / PHỤ THUỘC)
    // GET /profile/add-patient & POST /profile/add-patient
    // =========================================================

    @GetMapping("/profile/add-patient")
    @Transactional
    public String showAddPatientProfile(
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        List<PatientProfile> profiles = populateCommonProfileModel(user, session, model);

        Branch effectiveBranch = user.getBranch();
        if (effectiveBranch == null && !profiles.isEmpty()) {
            effectiveBranch = profiles.get(0).getPreferredBranch();
            if (effectiveBranch != null) {
                user.setBranch(effectiveBranch);
                userRepository.save(user);
            }
        }

        if (effectiveBranch == null) {
            model.addAttribute(
                    "error",
                    "Tài khoản chưa được gán cơ sở khám. Vui lòng thiết lập cơ sở khám trước.");
            return "profile/add-patient";
        }

        model.addAttribute("branch", effectiveBranch);
        return "profile/add-patient";
    }

    @PostMapping(value = "/profile/add-patient", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public String addPatientProfile(
            @RequestParam("fullName") String fullName,
            @RequestParam(value = "dob", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
            @RequestParam("gender") String gender,
            @RequestParam("phoneNumber") String phoneNumber,
            @RequestParam("address") String address,
            @RequestParam("hasUnderlyingDisease") Boolean hasUnderlyingDisease,
            @RequestParam(value = "underlyingDiseaseDetails", required = false) String underlyingDiseaseDetails,
            @RequestParam(value = "avatar", required = false) MultipartFile avatar,
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        List<PatientProfile> profiles = populateCommonProfileModel(user, session, model);
        Branch effectiveBranch = user.getBranch();
        if (effectiveBranch == null && !profiles.isEmpty()) {
            effectiveBranch = profiles.get(0).getPreferredBranch();
            if (effectiveBranch != null) {
                user.setBranch(effectiveBranch);
                userRepository.save(user);
            }
        }

        if (effectiveBranch == null || effectiveBranch.getBranchId() == null) {
            model.addAttribute(
                    "error",
                    "Tài khoản chưa được gán cơ sở khám.");
            return "profile/add-patient";
        }

        model.addAttribute("branch", effectiveBranch);

        fullName = fullName == null ? "" : fullName.trim();
        gender = gender == null ? "" : gender.trim();
        phoneNumber = phoneNumber == null ? "" : phoneNumber.trim();
        address = address == null ? "" : address.trim();

        underlyingDiseaseDetails = underlyingDiseaseDetails == null
                ? ""
                : underlyingDiseaseDetails.trim();

        if (fullName.isBlank()
                || fullName.length() > 100
                || gender.isBlank()
                || phoneNumber.isBlank()
                || !PHONE_PATTERN.matcher(phoneNumber).matches()
                || address.isBlank()
                || address.length() > 255
                || hasUnderlyingDisease == null) {

            model.addAttribute(
                    "error",
                    "Vui lòng nhập đầy đủ và kiểm tra lại thông tin (SĐT bắt đầu bằng 0, gồm 10–11 chữ số).");
            return "profile/add-patient";
        }

        if (dob != null && dob.isAfter(LocalDate.now())) {
            model.addAttribute(
                    "error",
                    "Ngày sinh không được lớn hơn ngày hiện tại.");
            return "profile/add-patient";
        }

        if (Boolean.TRUE.equals(hasUnderlyingDisease)
                && underlyingDiseaseDetails.isBlank()) {
            model.addAttribute(
                    "error",
                    "Vui lòng nhập thông tin bệnh nền.");
            return "profile/add-patient";
        }

        if (Boolean.FALSE.equals(hasUnderlyingDisease)) {
            underlyingDiseaseDetails = "";
        }

        try {
            String avatarUrl = saveAvatar(avatar);
            Long branchId = effectiveBranch.getBranchId();

            patientProfileService.createProfile(
                    user,
                    fullName,
                    dob,
                    gender,
                    phoneNumber,
                    address,
                    branchId,
                    hasUnderlyingDisease,
                    underlyingDiseaseDetails,
                    avatarUrl);

            session.setAttribute(
                    "profileSuccess",
                    "Tạo hồ sơ bệnh nhân mới \"" + fullName + "\" thành công.");

            return "redirect:/profile";

        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
        } catch (IOException ex) {
            model.addAttribute(
                    "error",
                    "Không thể tải ảnh đại diện. Vui lòng thử lại.");
        }

        return "profile/add-patient";
    }

    // =========================================================
    // 9. ĐỔI CƠ SỞ KHÁM CHỮA BỆNH
    // GET /profile/change-branch & POST /profile/change-branch
    // =========================================================

    @GetMapping("/profile/change-branch")
    @Transactional
    public String showChangeBranch(
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        List<PatientProfile> profiles = populateCommonProfileModel(user, session, model);

        Branch currentBranch = user.getBranch();
        if (currentBranch == null && !profiles.isEmpty()) {
            currentBranch = profiles.get(0).getPreferredBranch();
        }

        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc());
        model.addAttribute("currentBranch", currentBranch);

        return "profile/change-branch";
    }

    @PostMapping("/profile/change-branch")
    @Transactional
    public String changeBranch(
            @RequestParam("branchId") Long branchId,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        var branch = branchRepository
                .findByIsActiveTrueOrderByBranchNameAsc()
                .stream()
                .filter(item -> item.getBranchId().equals(branchId))
                .findFirst()
                .orElse(null);

        if (branch == null) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Cơ sở không tồn tại hoặc hiện không hoạt động.");
            return "redirect:/profile/change-branch";
        }

        // 1. Cập nhật cơ sở ở cấp tài khoản.
        user.setBranch(branch);
        userRepository.save(user);

        // 2. Đồng bộ cơ sở cho toàn bộ hồ sơ của tài khoản.
        List<PatientProfile> profiles = getAndSyncPatientProfiles(user);
        for (PatientProfile profile : profiles) {
            profile.setPreferredBranch(branch);
        }
        patientProfileRepository.saveAll(profiles);

        // 3. Cập nhật User & tên cơ sở trong session.
        session.setAttribute("loggedInUser", user);
        session.setAttribute("currentBranchName", branch.getBranchName());

        session.setAttribute(
                "profileSuccess",
                "Đã chuyển cơ sở khám sang \"" + branch.getBranchName()
                        + "\" cho tài khoản và toàn bộ hồ sơ bệnh nhân.");

        return "redirect:/profile";
    }

}
