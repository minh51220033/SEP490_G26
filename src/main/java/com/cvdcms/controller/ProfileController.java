
package com.cvdcms.controller;

import com.cvdcms.DTO.ChangePasswordDTO;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.service.PatientProfileService;
import com.cvdcms.DTO.CompleteProfilesDTO;
import com.cvdcms.DTO.PatientProfileFormDTO;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ModelAttribute;


import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.*;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


import java.util.regex.Pattern;


@Controller
public class ProfileController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final BranchRepository branchRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final PatientProfileService patientProfileService;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    // Dùng constructor injection để Spring quản lý dependency.
    public ProfileController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            BranchRepository branchRepository,
            PatientProfileRepository patientProfileRepository,
            PatientProfileService patientProfileService) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.branchRepository = branchRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.patientProfileService = patientProfileService;
    }

    // =========================================================
    // HÀM HỖ TRỢ: LẤY TÀI KHOẢN ĐANG ĐĂNG NHẬP
    // =========================================================

    /**
     * Lấy User từ session.
     *
     * Lưu ý:
     * - Không nhận userId từ form để xác định người đang đăng nhập.
     * - Khi cập nhật dữ liệu, luôn truy vấn lại tài khoản trong database.
     */
    private User getLoggedInUser(HttpSession session) {
        Object value = session.getAttribute("loggedInUser");

        if (!(value instanceof User)) {
            return null;
        }

        User sessionUser = (User) value;

        if (sessionUser.getUserId() == null) {
            return null;
        }

        // Lấy dữ liệu mới nhất từ database thay vì tin hoàn toàn
        // vào đối tượng User có thể đã cũ trong session.
        return userRepository.findById(sessionUser.getUserId())
                .orElse(null);
    }


    /**
     * Chỉ tài khoản có vai trò BENH_NHAN mới có hồ sơ bệnh nhân.
     * Admin và nhân sự không hiển thị khu vực hồ sơ bệnh nhân.
     */
    private boolean isPatientUser(User user) {
        return user != null
                && user.getRoles() != null
                && user.getRoles().stream().anyMatch(role ->
                role != null
                        && role.getRoleName() != null
                        && "BENH_NHAN".equalsIgnoreCase(role.getRoleName().trim()));
    }

    // Kiểm tra định dạng email cơ bản.
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
            );

    // Số điện thoại bắt đầu bằng 0, gồm 10 hoặc 11 chữ số.
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^0\\d{9,10}$");

    // =========================================================
// 1. XEM THÔNG TIN TÀI KHOẢN VÀ DANH SÁCH HỒ SƠ
// GET /profile
// =========================================================
    @GetMapping("/profile")
    public String profile(HttpSession session, Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        model.addAttribute("user", user);

        model.addAttribute("isPatient", isPatientUser(user));
        model.addAttribute(
                "currentBranchName",
                user.getBranch() != null
                        ? user.getBranch().getBranchName()
                        : null
        );

        // Lấy tất cả hồ sơ thuộc đúng tài khoản đang đăng nhập.
        List<PatientProfile> patientProfiles =
                patientProfileRepository
                        .findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(
                                user.getUserId()
                        );

        model.addAttribute("patientProfiles", patientProfiles);

        // Danh sách vai trò.
        List<String> roleNames = user.getRoles()
                .stream()
                .map(role -> role.getRoleName())
                .toList();

        model.addAttribute("roleNames", roleNames);

        // Thông báo chỉ hiển thị một lần.
        Object success = session.getAttribute("profileSuccess");

        if (success != null) {
            model.addAttribute("success", success);
            session.removeAttribute("profileSuccess");
        }

        return "profile/profile";
    }

    // =========================================================
    // 2. HIỂN THỊ FORM SỬA EMAIL / SỐ ĐIỆN THOẠI ... Sua het nhe
    // GET /profile/edit
    // =========================================================

    @GetMapping("/profile/edit")
    public String editProfile(
            @RequestParam(value = "profileId", required = false)
            Long profileId,
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        List<PatientProfile> patientProfiles =
                patientProfileRepository
                        .findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(
                                user.getUserId()
                        );

        PatientProfile selectedProfile = null;

        if (profileId != null) {
            selectedProfile = patientProfileRepository
                    .findByProfileIdAndUser_UserId(
                            profileId,
                            user.getUserId()
                    )
                    .orElse(null);

            if (selectedProfile == null) {
                model.addAttribute(
                        "error",
                        "Hồ sơ không tồn tại hoặc không thuộc tài khoản của bạn."
                );
            }
        }

        model.addAttribute("user", user);

        model.addAttribute("isPatient", isPatientUser(user));
        model.addAttribute("patientProfiles", patientProfiles);
        model.addAttribute("patientProfile", selectedProfile);
        model.addAttribute(
                "currentBranchName",
                user.getBranch() != null
                        ? user.getBranch().getBranchName()
                        : null
        );
        model.addAttribute("selectedProfileId",
                selectedProfile != null
                        ? selectedProfile.getProfileId()
                        : null);

        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
        );


        return "profile/edit";
    }

    // =========================================================
    // 3. CẬP NHẬT EMAIL / SỐ ĐIỆN THOẠI
    // POST /profile/edit
    // =========================================================

    @PostMapping("/profile/edit")
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

            model.addAttribute("error", "Email không hợp lệ.");
            model.addAttribute("user", user);
            model.addAttribute("isPatient", isPatientUser(user));
            return "profile/edit";
        }

// Kiểm tra số điện thoại bắt buộc và định dạng.
        if (!PHONE_PATTERN.matcher(phoneNumber).matches()) {
            model.addAttribute(
                    "error",
                    "Số điện thoại phải bắt đầu bằng 0 "
                            + "và gồm 10 hoặc 11 chữ số."
            );

            model.addAttribute("user", user);

            model.addAttribute("isPatient", isPatientUser(user));
            return "profile/edit";
        }

        // Kiểm tra số điện thoại có thuộc tài khoản khác hay không.
        boolean phoneUsedByAnotherAccount = userRepository
                .findByPhoneNumber(phoneNumber)
                .filter(existing ->
                        !existing.getUserId().equals(user.getUserId()))
                .isPresent();

        if (phoneUsedByAnotherAccount) {
            model.addAttribute(
                    "error",
                    "Số điện thoại này đã được tài khoản khác sử dụng."
            );
            model.addAttribute("user", user);
            model.addAttribute("isPatient", isPatientUser(user));
            return "profile/edit";
        }

        // Chỉ cập nhật thông tin thuộc User.
        // Không cập nhật PatientProfile tại đây.
        user.setEmail(email);
        user.setPhoneNumber(phoneNumber);

        User updatedUser = userRepository.save(user);

        // Cập nhật session để dữ liệu không bị cũ.
        session.setAttribute("loggedInUser", updatedUser);

        session.setAttribute(
                "profileSuccess",
                "Cập nhật thông tin tài khoản thành công."
        );

        return "redirect:/profile";
    }

    // =========================================================
    // 4. HIỂN THỊ FORM ĐỔI MẬT KHẨU
    // GET /profile/change-password
    // =========================================================

    @GetMapping("/profile/change-password")
    public String changePassword(
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        model.addAttribute(
                "changePasswordDTO",
                new ChangePasswordDTO()
        );

        return "profile/change-password";
    }

    // =========================================================
    // 5. XỬ LÝ ĐỔI MẬT KHẨU
    // POST /profile/change-password
    // =========================================================

    @PostMapping("/profile/change-password")
    public String processChangePassword(
            @Valid
            @org.springframework.web.bind.annotation.ModelAttribute(
                    "changePasswordDTO")
            ChangePasswordDTO dto,
            BindingResult bindingResult,
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        // Hiển thị lỗi validation từ ChangePasswordDTO.
        if (bindingResult.hasErrors()) {
            return "profile/change-password";
        }

        // Đọc mật khẩu hiện tại từ database và kiểm tra bằng BCrypt.
        if (dto.getCurrentPassword() == null
                || !passwordEncoder.matches(
                dto.getCurrentPassword(),
                user.getPassword())) {

            model.addAttribute(
                    "error",
                    "Mật khẩu hiện tại không đúng."
            );
            return "profile/change-password";
        }

        String newPassword = dto.getNewPassword();
        String confirmPassword = dto.getConfirmPassword();

        // Kiểm tra mật khẩu mới.
        if (newPassword == null
                || newPassword.length() < 8
                || newPassword.length() > 64) {

            model.addAttribute(
                    "error",
                    "Mật khẩu mới phải từ 8 đến 64 ký tự."
            );
            return "profile/change-password";
        }

        // Không cho đặt lại đúng mật khẩu đang dùng.
        if (passwordEncoder.matches(
                newPassword,
                user.getPassword())) {

            model.addAttribute(
                    "error",
                    "Mật khẩu mới phải khác mật khẩu hiện tại."
            );
            return "profile/change-password";
        }

        // Xác nhận mật khẩu mới.
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute(
                    "error",
                    "Mật khẩu xác nhận không khớp."
            );
            return "profile/change-password";
        }

        // Mã hóa BCrypt trước khi lưu database.
        user.setPassword(passwordEncoder.encode(newPassword));
        User updatedUser = userRepository.save(user);

        session.setAttribute("loggedInUser", updatedUser);

        session.setAttribute(
                "profileSuccess",
                "Đổi mật khẩu thành công."
        );



        return "redirect:/profile";
    }


    /**
     * Hiển thị form hoàn thiện hồ sơ bệnh nhân.
     * GET /profile/complete
     */

    @GetMapping("/profile/complete")
    public String completeProfile(HttpSession session, Model model) {
        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        // Nếu đã có hồ sơ mặc định, bước hoàn thiện ban đầu đã xong.
        // Muốn thêm hồ sơ sau này, dùng trang quản lý hồ sơ riêng.
        if (patientProfileRepository
                .existsByUser_UserIdAndIsDefaultTrue(user.getUserId())) {
            return "redirect:/home";
        }

        model.addAttribute("user", user);

        model.addAttribute("isPatient", isPatientUser(user));
        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
        );
        model.addAttribute("completeProfilesDTO", new CompleteProfilesDTO());


        return "profile/complete";
    }



    @PostMapping(
            value = "/profile/complete",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Transactional
    public String saveCompleteProfiles(
            @ModelAttribute("completeProfilesDTO")
            CompleteProfilesDTO dto,
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

                // Lưu ảnh đại diện nếu người dùng đã tải ảnh lên.
                String avatarUrl = saveAvatar(p.getAvatar());

                patientProfileService.createProfile(
                        user,
                        p.getFullName(),
                        p.getDob(),
                        p.getGender(),
                        user.getPhoneNumber(), // Số điện thoại của tài khoản
                        p.getAddress(),
                        p.getBranchId(),
                        p.getHasUnderlyingDisease(),
                        p.getUnderlyingDiseaseDetails(),
                        avatarUrl
                );
            }

            session.setAttribute(
                    "profileSuccess",
                    "Đã tạo thành công " + profiles.size()
                            + " hồ sơ bệnh nhân."
            );

            return "redirect:/home";

        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
        } catch (IOException ex) {
            model.addAttribute(
                    "error",
                    "Không thể tải ảnh đại diện. Vui lòng thử lại."
            );
        }

        model.addAttribute("user", user);

        model.addAttribute("isPatient", isPatientUser(user));
        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
        );

        return "profile/complete";
    }


    /**
     * Lưu hồ sơ bệnh nhân mặc định.
     * POST /profile/complete
     */
    @PostMapping("/profile/complete")
    public String saveCompleteProfile(
            @RequestParam("fullName") String fullName,
            @RequestParam(value = "dob", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dob,
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

        // Chuẩn hóa dữ liệu nhận từ biểu mẫu.
        fullName = fullName == null ? "" : fullName.trim();
        gender = gender == null ? "" : gender.trim();
        phoneNumber = phoneNumber == null ? "" : phoneNumber.trim();
        address = address == null ? "" : address.trim();

        // Kiểm tra các trường bắt buộc.
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
                    "Vui lòng nhập đầy đủ và kiểm tra lại thông tin."
            );
            model.addAttribute("user", user);
            model.addAttribute("isPatient", isPatientUser(user));
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
            );
            return "profile/complete";
        }

        // Không chấp nhận ngày sinh ở tương lai.
        if (dob != null && dob.isAfter(LocalDate.now())) {
            model.addAttribute(
                    "error",
                    "Ngày sinh không được lớn hơn ngày hiện tại."
            );
            model.addAttribute("user", user);
            model.addAttribute("isPatient", isPatientUser(user));
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
            );
            return "profile/complete";
        }

        try {
            PatientProfile profile =
                    patientProfileService.createDefaultProfile(
                            user,
                            fullName,
                            dob,
                            gender,
                            phoneNumber,
                            address,
                            branchId
                    );

            session.setAttribute(
                    "profileSuccess",
                    "Hoàn thiện hồ sơ bệnh nhân thành công."
            );

            return "redirect:/profile";

        } catch (IllegalArgumentException | IllegalStateException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("user", user);
            model.addAttribute("isPatient", isPatientUser(user));
            model.addAttribute(
                    "branches",
                    branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
            );
            return "profile/complete";
        }
    }


    private String saveAvatar(MultipartFile avatar) throws IOException {
        if (avatar == null || avatar.isEmpty()) {
            return null;
        }

        // Giới hạn dung lượng ảnh 5 MB.
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

        // Đảm bảo file được lưu đúng trong thư mục avatars.
        if (!destination.startsWith(directory)) {
            throw new IllegalArgumentException("Đường dẫn ảnh không hợp lệ.");
        }

        avatar.transferTo(destination);

        return "/uploads/avatars/" + fileName;
    }


    /**
     * Nạp lại dữ liệu cần thiết khi hiển thị trang chỉnh sửa.
     * Dùng chung khi xảy ra lỗi validation.
     */
    private void loadEditProfileData(
            User user,
            Long profileId,
            Model model) {

        List<PatientProfile> patientProfiles =
                patientProfileRepository
                        .findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(
                                user.getUserId()
                        );

        PatientProfile selectedProfile = null;

        if (profileId != null) {
            selectedProfile = patientProfileRepository
                    .findByProfileIdAndUser_UserId(
                            profileId,
                            user.getUserId()
                    )
                    .orElse(null);
        }

        model.addAttribute("user", user);

        model.addAttribute("isPatient", isPatientUser(user));
        model.addAttribute("patientProfiles", patientProfiles);
        model.addAttribute("patientProfile", selectedProfile);
        model.addAttribute(
                "selectedProfileId",
                selectedProfile == null
                        ? null
                        : selectedProfile.getProfileId()
        );
        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
        );
    }

    /**
     * Cập nhật thông tin của một hồ sơ bệnh nhân.
     *
     * Chỉ cập nhật hồ sơ thuộc tài khoản đang đăng nhập.
     * Nếu không tải ảnh mới thì giữ nguyên ảnh hiện tại.
     */
    @PostMapping(
            value = "/profile/edit-patient",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Transactional
    public String updatePatientProfile(
            @RequestParam("profileId") Long profileId,
            @RequestParam("fullName") String fullName,
            @RequestParam(value = "dob", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dob,
            @RequestParam("gender") String gender,
            @RequestParam("phoneNumber") String phoneNumber,
            @RequestParam("address") String address,
            @RequestParam("hasUnderlyingDisease") Boolean hasUnderlyingDisease,
            @RequestParam(
                    value = "underlyingDiseaseDetails",
                    required = false
            ) String underlyingDiseaseDetails,
            @RequestParam(value = "avatar", required = false)
            MultipartFile avatar,
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

        // Chỉ cho phép cập nhật hồ sơ thuộc tài khoản đang đăng nhập.
        PatientProfile profile = patientProfileRepository
                .findByProfileIdAndUser_UserId(
                        profileId,
                        user.getUserId()
                )
                .orElse(null);

        if (profile == null) {
            session.setAttribute(
                    "profileSuccess",
                    "Không tìm thấy hồ sơ hoặc bạn không có quyền chỉnh sửa."
            );
            return "redirect:/profile";
        }

        // Kiểm tra dữ liệu bắt buộc.
        if (fullName.isBlank()
                || fullName.length() > 100
                || gender.isBlank()
                || gender.length() > 20
                || phoneNumber.isBlank()
                || phoneNumber.length() > 20
                || address.isBlank()
                || address.length() > 255
                || hasUnderlyingDisease == null) {

            model.addAttribute(
                    "error",
                    "Vui lòng nhập đầy đủ và kiểm tra lại thông tin."
            );
            loadEditProfileData(user, profileId, model);
            return "profile/edit";
        }

        // Không chấp nhận ngày sinh ở tương lai.
        if (dob != null && dob.isAfter(LocalDate.now())) {
            model.addAttribute(
                    "error",
                    "Ngày sinh không được lớn hơn ngày hiện tại."
            );
            loadEditProfileData(user, profileId, model);
            return "profile/edit";
        }

        // Nếu có bệnh nền thì phải nhập mô tả.
        if (Boolean.TRUE.equals(hasUnderlyingDisease)
                && underlyingDiseaseDetails.isBlank()) {

            model.addAttribute("error", "Vui lòng nhập mô tả bệnh nền.");
            loadEditProfileData(user, profileId, model);
            return "profile/edit";
        }

        // Không có bệnh nền thì xóa mô tả cũ.
        if (Boolean.FALSE.equals(hasUnderlyingDisease)) {
            underlyingDiseaseDetails = "";
        }

        try {
            // Chỉ cập nhật ảnh khi người dùng chọn ảnh mới.
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
                            : underlyingDiseaseDetails
            );

            /*
             * KHÔNG cập nhật preferredBranch tại đây.
             * Cơ sở được quản lý chung ở User.branch.
             */
            patientProfileRepository.save(profile);

            session.setAttribute(
                    "profileSuccess",
                    "Cập nhật hồ sơ bệnh nhân thành công."
            );

            return "redirect:/profile";

        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
            loadEditProfileData(user, profileId, model);
            return "profile/edit";

        } catch (IOException ex) {
            model.addAttribute(
                    "error",
                    "Không thể tải ảnh đại diện. Vui lòng thử lại."
            );
            loadEditProfileData(user, profileId, model);
            return "profile/edit";
        }
    }

    @GetMapping("/edit")
    public String showEditProfile(
            @RequestParam(required = false) Long profileId,
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes) {

        // Lấy tài khoản đang đăng nhập.
        User user = getLoggedInUser(session);

        if (user == null) {
            return "redirect:/login";
        }

        // Lấy danh sách hồ sơ thuộc đúng tài khoản đang đăng nhập.
        List<PatientProfile> patientProfiles =
                patientProfileRepository
                        .findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(
                                user.getUserId());

        PatientProfile selectedProfile = null;

        // Nếu URL có profileId, phải xác minh quyền sở hữu hồ sơ.
        if (profileId != null) {
            Optional<PatientProfile> result =
                    patientProfileRepository
                            .findByProfileIdAndUser_UserId(
                                    profileId,
                                    user.getUserId());

            if (result.isEmpty()) {
                redirectAttributes.addFlashAttribute(
                        "error",
                        "Hồ sơ không tồn tại hoặc không thuộc tài khoản của bạn.");

                return "redirect:/profile/edit";
            }

            PatientProfile patientProfile = result.get();

        } else if (!patientProfiles.isEmpty()) {
            // Mặc định chọn hồ sơ đầu tiên nếu người dùng chưa chọn.
            selectedProfile = patientProfiles.get(0);
        }

        // Dữ liệu tài khoản.
        model.addAttribute("user", user);
        model.addAttribute("isPatient", isPatientUser(user));

        // Dữ liệu danh sách và hồ sơ đang được chọn.
        model.addAttribute("patientProfiles", patientProfiles);
        model.addAttribute("patientProfile", selectedProfile);
        model.addAttribute(
                "selectedProfileId",
                selectedProfile != null
                        ? selectedProfile.getProfileId()
                        : null);

        // Danh sách chi nhánh đang hoạt động cho thẻ select.
        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc());

        return "profile/edit";
    }

    // =========================================================
// TẠO THÊM HỒ SƠ BỆNH NHÂN
// GET  /profile/add-patient  -> Hiển thị form
// POST /profile/add-patient -> Lưu hồ sơ mới
// =========================================================

    @GetMapping("/profile/add-patient")
    public String showAddPatientProfile(
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        // Chi nhánh được quản lý ở cấp tài khoản.
        if (user.getBranch() == null) {
            model.addAttribute(
                    "error",
                    "Tài khoản chưa được gán chi nhánh. "
                            + "Vui lòng thiết lập chi nhánh trước."
            );
            model.addAttribute("user", user);
            model.addAttribute("isPatient", isPatientUser(user));
            return "profile/add-patient";
        }

        model.addAttribute("user", user);

        model.addAttribute("isPatient", isPatientUser(user));
        model.addAttribute("branch", user.getBranch());

        return "profile/add-patient";
    }

    @PostMapping(
            value = "/profile/add-patient",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Transactional
    public String addPatientProfile(
            @RequestParam("fullName") String fullName,
            @RequestParam(value = "dob", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dob,
            @RequestParam("gender") String gender,
            @RequestParam("phoneNumber") String phoneNumber,
            @RequestParam("address") String address,
            @RequestParam("hasUnderlyingDisease")
            Boolean hasUnderlyingDisease,
            @RequestParam(
                    value = "underlyingDiseaseDetails",
                    required = false
            ) String underlyingDiseaseDetails,
            @RequestParam(value = "avatar", required = false)
            MultipartFile avatar,
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        // Không cho tạo hồ sơ khi tài khoản chưa có chi nhánh.
        if (user.getBranch() == null
                || user.getBranch().getBranchId() == null) {
            model.addAttribute(
                    "error",
                    "Tài khoản chưa được gán chi nhánh."
            );
            model.addAttribute("user", user);
            model.addAttribute("isPatient", isPatientUser(user));
            return "profile/add-patient";
        }

        fullName = fullName == null ? "" : fullName.trim();
        gender = gender == null ? "" : gender.trim();
        phoneNumber = phoneNumber == null ? "" : phoneNumber.trim();
        address = address == null ? "" : address.trim();

        underlyingDiseaseDetails = underlyingDiseaseDetails == null
                ? ""
                : underlyingDiseaseDetails.trim();

        // Kiểm tra dữ liệu bắt buộc.
        if (fullName.isBlank()
                || fullName.length() > 100
                || gender.isBlank()
                || phoneNumber.isBlank()
                || phoneNumber.length() > 20
                || address.isBlank()
                || address.length() > 255
                || hasUnderlyingDisease == null) {

            model.addAttribute(
                    "error",
                    "Vui lòng nhập đầy đủ và kiểm tra lại thông tin."
            );
            model.addAttribute("user", user);
            model.addAttribute("isPatient", isPatientUser(user));
            model.addAttribute("branch", user.getBranch());
            return "profile/add-patient";
        }

        if (dob != null && dob.isAfter(LocalDate.now())) {
            model.addAttribute(
                    "error",
                    "Ngày sinh không được lớn hơn ngày hiện tại."
            );
            model.addAttribute("user", user);
            model.addAttribute("isPatient", isPatientUser(user));
            model.addAttribute("branch", user.getBranch());
            return "profile/add-patient";
        }

        if (Boolean.TRUE.equals(hasUnderlyingDisease)
                && underlyingDiseaseDetails.isBlank()) {
            model.addAttribute(
                    "error",
                    "Vui lòng nhập thông tin bệnh nền."
            );
            model.addAttribute("user", user);
            model.addAttribute("isPatient", isPatientUser(user));
            model.addAttribute("branch", user.getBranch());
            return "profile/add-patient";
        }

        if (Boolean.FALSE.equals(hasUnderlyingDisease)) {
            underlyingDiseaseDetails = "";
        }

        try {
            String avatarUrl = saveAvatar(avatar);

            // Luôn dùng chi nhánh của tài khoản.
            // Không nhận branchId từ form.
            Long branchId = user.getBranch().getBranchId();

            /*
             * Cần bổ sung phoneNumber vào chữ ký của
             * PatientProfileService.createProfile(...).
             *
             * Phương thức này cần tạo hồ sơ gắn với user,
             * sử dụng branchId được truyền vào và lưu đầy đủ
             * thông tin bệnh nhân.
             */
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
                    avatarUrl
            );

            session.setAttribute(
                    "profileSuccess",
                    "Tạo hồ sơ bệnh nhân mới thành công."
            );

            return "redirect:/profile";

        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
        } catch (IOException ex) {
            model.addAttribute(
                    "error",
                    "Không thể tải ảnh đại diện. Vui lòng thử lại."
            );
        }

        model.addAttribute("user", user);

        model.addAttribute("isPatient", isPatientUser(user));
        model.addAttribute("branch", user.getBranch());

        return "profile/add-patient";
    }


    @GetMapping("/profile/change-branch")
    public String showChangeBranch(
            HttpSession session,
            Model model) {

        User user = getLoggedInUser(session);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }

        model.addAttribute("user", user);

        model.addAttribute("isPatient", isPatientUser(user));
        model.addAttribute(
                "branches",
                branchRepository.findByIsActiveTrueOrderByBranchNameAsc()
        );
        model.addAttribute(
                "currentBranch",
                user.getBranch()
        );

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

        // Chỉ cho phép chuyển sang cơ sở đang hoạt động.
        var branch = branchRepository
                .findByIsActiveTrueOrderByBranchNameAsc()
                .stream()
                .filter(item -> item.getBranchId().equals(branchId))
                .findFirst()
                .orElse(null);

        if (branch == null) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Cơ sở không tồn tại hoặc hiện không hoạt động."
            );
            return "redirect:/profile/change-branch";
        }

        // 1. Cập nhật cơ sở ở cấp tài khoản.
        user.setBranch(branch);
        userRepository.save(user);

        // 2. Đồng bộ cơ sở cho toàn bộ hồ sơ của tài khoản.
        List<PatientProfile> profiles =
                patientProfileRepository.findAllByUser_UserId(
                        user.getUserId()
                );

        for (PatientProfile profile : profiles) {
            profile.setPreferredBranch(branch);
        }

        patientProfileRepository.saveAll(profiles);

        // 3. Cập nhật User trong session nếu session đang giữ đối tượng cũ.
        session.setAttribute("loggedInUser", user);

        redirectAttributes.addFlashAttribute(
                "profileSuccess",
                "Đã thay đổi cơ sở cho tài khoản và tất cả hồ sơ bệnh nhân."
        );

        return "redirect:/profile";
    }

}
