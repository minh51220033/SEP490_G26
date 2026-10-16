package com.cvdcms.controller;

import com.cvdcms.DTO.UserRegisterDTO;
import com.cvdcms.entity.*;
import com.cvdcms.repository.RoleRepository;
import com.cvdcms.repository.TokenRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.service.EmailService;

import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Controller
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PatientProfileRepository patientProfileRepository;
    // =====================================================
    // REGEX
    // =====================================================

    // =====================================================
// BIỂU THỨC KIỂM TRA DỮ LIỆU ĐẦU VÀO
// =====================================================

    // Username: 4–50 ký tự, bắt đầu bằng chữ hoặc số.
// Cho phép chữ cái, chữ số, dấu chấm, gạch dưới và gạch ngang.
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{3,49}$");

    // Email: kiểm tra cấu trúc cơ bản và phần tên miền.
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
            );

    // Số điện thoại Việt Nam: bắt đầu bằng 0, tổng cộng 10 hoặc 11 chữ số.
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^0\\d{9,10}$");

    // OTP: chính xác 6 chữ số.
    private static final Pattern OTP_PATTERN =
            Pattern.compile("^\\d{6}$");


    // =====================================================
    // CHE EMAIL
    // =====================================================

    private String maskEmail(String email) {

        if (email == null || !email.contains("@")) {
            return "";
        }

        int atIndex = email.indexOf("@");

        String prefix = email.substring(0, atIndex);
        String domain = email.substring(atIndex);

        if (prefix.length() <= 2) {
            return prefix.charAt(0) + "****" + domain;
        }

        return prefix.substring(0, 2)
                + "********"
                + domain;
    }


    @GetMapping("/home")
    public String home(HttpSession session, Model model) {

        // 1. Kiểm tra người dùng đã đăng nhập chưa
        Object loggedInUser = session.getAttribute("loggedInUser");

        if (!(loggedInUser instanceof User)) {
            return "redirect:/login";
        }

        User sessionUser = (User) loggedInUser;

        // 2. Lấy thông tin tài khoản mới nhất từ database
        User user = userRepository.findById(sessionUser.getUserId())
                .orElse(null);

        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            session.invalidate();
            return "redirect:/login";
        }

        // Cập nhật tài khoản mới nhất vào session
        session.setAttribute("loggedInUser", user);
        session.setAttribute("username", user.getUsername());

        // 3. Lấy danh sách vai trò của tài khoản
        List<String> roleNames = user.getRoles()
                .stream()
                .map(Role::getRoleName)
                .toList();

        session.setAttribute("roleNames", roleNames);
        model.addAttribute("roleNames", roleNames);

        // 4. Tên hiển thị mặc định là username
        model.addAttribute("displayName", user.getUsername());

        // 5. Khởi tạo giá trị mặc định cho hồ sơ và cơ sở
        model.addAttribute("defaultPatientProfile", null);
        model.addAttribute("currentBranchName", "Chưa xác định cơ sở");
        model.addAttribute("currentBranchAddress", "");

        // 6. Tìm hồ sơ bệnh nhân mặc định của tài khoản
        PatientProfile defaultProfile =
                patientProfileRepository
                        .findFirstByUser_UserIdAndIsDefaultTrue(user.getUserId())
                        .orElse(null);

        if (defaultProfile != null) {

            // Truyền hồ sơ mặc định sang HTML để lấy avatarUrl
            model.addAttribute("defaultPatientProfile", defaultProfile);

            // Nếu có họ tên, dùng họ tên làm tên hiển thị
            if (defaultProfile.getFullName() != null
                    && !defaultProfile.getFullName().isBlank()) {

                model.addAttribute(
                        "displayName",
                        defaultProfile.getFullName()
                );
            }

            // 7. Lấy cơ sở ưu tiên từ hồ sơ mặc định
            Branch branch = defaultProfile.getPreferredBranch();

            if (branch != null) {

                if (branch.getBranchName() != null
                        && !branch.getBranchName().isBlank()) {

                    model.addAttribute(
                            "currentBranchName",
                            branch.getBranchName()
                    );
                }

                if (branch.getAddress() != null
                        && !branch.getAddress().isBlank()) {

                    model.addAttribute(
                            "currentBranchAddress",
                            branch.getAddress()
                    );
                }
            }
        }

        return "user/home";
    }

    // =====================================================
    // LOGIN - GET
    // =====================================================

    @GetMapping({"/", "/login"})
    public String showLoginForm(
            HttpSession session,
            Model model) {

        Object notification =
                session.getAttribute("notification");

        if (notification != null) {

            model.addAttribute(
                    "notification",
                    notification
            );

            session.removeAttribute("notification");
        }

        return "/user/login";
    }


    // =====================================================
    // LOGIN - POST
    // =====================================================

    @PostMapping("/login")
    public String processLogin(
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            HttpSession session,
            Model model) {

        // ---------------------------------------------
        // USERNAME
        // ---------------------------------------------

        username = username == null
                ? ""
                : username.trim();


        // ---------------------------------------------
        // CHECK INPUT
        // ---------------------------------------------

        if (username.isEmpty() ||
                password == null ||
                password.isEmpty()) {

            model.addAttribute(
                    "error",
                    "Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu."
            );

            return "/user/login";
        }


        // ---------------------------------------------
        // FIND USER
        // ---------------------------------------------

        Optional<User> userOpt =
                userRepository.findByUsername(username);


        // ---------------------------------------------
        // USER NOT FOUND
        // ---------------------------------------------

        if (userOpt.isEmpty()) {

            model.addAttribute(
                    "error",
                    "Tên đăng nhập hoặc mật khẩu không đúng!"
            );

            return "/user/login";
        }


        User user = userOpt.get();


        // ---------------------------------------------
        // CHECK ACTIVE
        // ---------------------------------------------

        if (user.getIsActive() == null ||
                !user.getIsActive()) {

            model.addAttribute(
                    "error",
                    "Tài khoản của bạn đã bị khóa. Vui lòng liên hệ quản trị viên."
            );

            return "/user/login";
        }


        // ---------------------------------------------
        // CHECK PASSWORD - BCrypt
        // ---------------------------------------------

        if (!passwordEncoder.matches(
                password,
                user.getPassword())) {

            model.addAttribute(
                    "error",
                    "Tên đăng nhập hoặc mật khẩu không đúng!"
            );

            return "/user/login";
        }



        // =============================================
        // ĐĂNG NHẬP THÀNH CÔNG
        // =============================================

        session.setAttribute("loggedInUser", user);
        session.setAttribute("username", user.getUsername());

        // Lưu tên các vai trò vào session.
        session.setAttribute(
                "roleNames",
                user.getRoles()
                        .stream()
                        .map(Role::getRoleName)
                        .toList()
        );

        // =============================================
        // KIỂM TRA HỒ SƠ CƠ BẢN CHO MỌI VAI TRÒ
        // =============================================

        // Không phân biệt ADMIN, DOCTOR, LE_TAN hay PATIENT.
        // Nếu tài khoản chưa có hồ sơ mặc định thì yêu cầu hoàn thiện.
        boolean hasDefaultProfile =
                patientProfileRepository
                        .existsByUser_UserIdAndIsDefaultTrue(
                                user.getUserId()
                        );

        if (!hasDefaultProfile) {
            return "redirect:/profile/complete";
        }

        // Đã có hồ sơ mặc định thì vào trang chính.
        return "redirect:/home";

    }

        // =====================================================
    // REGISTER - GET
    // =====================================================

    @GetMapping("/register")
    public String showRegisterForm(Model model) {

        model.addAttribute(
                "registerDTO",
                new UserRegisterDTO()
        );

        return "/user/register";
    }


    // =====================================================
    // REGISTER - POST
    // =====================================================

    @PostMapping("/register")
    public String processRegister(
            @ModelAttribute("registerDTO")
            UserRegisterDTO dto,
            Model model,
            HttpSession session) {

        // -----------------------------
        // USERNAME
        // -----------------------------

        // Chuẩn hóa username, không cho phép giá trị null.
        String username = dto.getUsername() == null
                ? ""
                : dto.getUsername().trim();

// Kiểm tra bắt buộc và định dạng username.
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            model.addAttribute(
                    "error",
                    "Tên đăng nhập phải từ 4 đến 50 ký tự, "
                            + "bắt đầu bằng chữ hoặc số và chỉ chứa "
                            + "chữ cái, chữ số, dấu chấm, gạch dưới "
                            + "hoặc gạch ngang."
            );

            model.addAttribute("registerDTO", dto);
            return "/user/register";
        }

// Kiểm tra username đã tồn tại.
        if (userRepository.findByUsername(username).isPresent()) {
            model.addAttribute(
                    "error",
                    "Tên đăng nhập đã tồn tại. Vui lòng chọn tên khác."
            );

            model.addAttribute("registerDTO", dto);
            return "/user/register";
        }

        if (userRepository
                .findByUsername(username)
                .isPresent()) {

            model.addAttribute(
                    "error",
                    "Tên đăng nhập đã tồn tại. Vui lòng chọn tên khác."
            );

            return "/user/register";
        }


        // -----------------------------
        // PASSWORD
        // -----------------------------

        String password = dto.getPassword();

        if (password == null ||
                password.length() < 8 ||
                password.length() > 64) {

            model.addAttribute(
                    "error",
                    "Mật khẩu phải từ 8 đến 64 ký tự."
            );

            return "/user/register";
        }


        // -----------------------------
        // CONFIRM PASSWORD
        // -----------------------------

        String confirmPassword =
                dto.getConfirmPassword();

        if (!password.equals(confirmPassword)) {

            model.addAttribute(
                    "error",
                    "Mật khẩu xác minh không khớp."
            );

            return "/user/register";
        }


        // -----------------------------
// EMAIL
// -----------------------------
        String email = dto.getEmail() == null
                ? ""
                : dto.getEmail().trim().toLowerCase(Locale.ROOT);

        if (email.isEmpty()
                || email.length() > 254
                || !EMAIL_PATTERN.matcher(email).matches()) {

            model.addAttribute("error", "Email không hợp lệ.");
            model.addAttribute("registerDTO", dto);
            return "/user/register";
        }

// -----------------------------
// PHONE
// -----------------------------
        String phoneNumber = dto.getPhoneNumber() == null
                ? ""
                : dto.getPhoneNumber().trim();

        if (!PHONE_PATTERN.matcher(phoneNumber).matches()) {
            model.addAttribute(
                    "error",
                    "Số điện thoại phải bắt đầu bằng số 0 "
                            + "và gồm 10 hoặc 11 chữ số."
            );

            model.addAttribute("registerDTO", dto);
            return "/user/register";
        }

// -----------------------------
// KIỂM TRA TRÙNG DỮ LIỆU
// -----------------------------
        if (userRepository.existsByEmail(email)) {
            model.addAttribute(
                    "error",
                    "Email đã được sử dụng. Vui lòng nhập email khác."
            );

            model.addAttribute("registerDTO", dto);
            return "/user/register";
        }

        if (userRepository.existsByPhoneNumber(phoneNumber)) {
            model.addAttribute(
                    "error",
                    "Số điện thoại đã được sử dụng. Vui lòng nhập số khác."
            );

            model.addAttribute("registerDTO", dto);
            return "/user/register";
        }



        // =============================================
        // KIỂM TRA EMAIL VÀ SỐ ĐIỆN THOẠI ĐÃ TỒN TẠI
        // =============================================

        // Chuẩn hóa email để hạn chế trường hợp cùng email
        // nhưng khác chữ hoa/chữ thường.
        email = email.toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            model.addAttribute(
                    "error",
                    "Email đã được sử dụng. Vui lòng nhập email khác."
            );
            model.addAttribute("registerDTO", dto);
            return "/user/register";
        }

        if (userRepository.existsByPhoneNumber(phoneNumber)) {
            model.addAttribute(
                    "error",
                    "Số điện thoại đã được sử dụng. Vui lòng nhập số khác."
            );
            model.addAttribute("registerDTO", dto);
            return "/user/register";
        }

        // =============================================
        // LẤY VAI TRÒ BỆNH NHÂN
        // =============================================

        // Theo dữ liệu roles bạn gửi, ID 6 là BENH_NHAN.
        // Không cho người đăng ký tự chọn vai trò ADMIN.
        Role patientRole = roleRepository.findById(6)
                .orElse(null);

        if (patientRole == null
                || !Boolean.TRUE.equals(patientRole.getIsActive())) {

            model.addAttribute(
                    "error",
                    "Vai trò bệnh nhân chưa được cấu hình hoặc đang bị khóa."
            );
            model.addAttribute("registerDTO", dto);
            return "/user/register";
        }

        // =============================================
        // TẠO TÀI KHOẢN
        // =============================================

        User user = new User();

        user.setUsername(username);

        // Mật khẩu luôn được mã hóa bằng BCrypt.
        user.setPassword(passwordEncoder.encode(password));

        user.setEmail(email);
        user.setPhoneNumber(phoneNumber);
        user.setIsActive(true);
        user.setStatus("ACTIVE");

        // Mô hình mới: thêm vai trò vào tập hợp roles.
        // Không dùng user.setRole(patientRole).
        user.getRoles().add(patientRole);

        // Không gọi user.setFullName().
        // Hồ sơ bệnh nhân được tạo ở bước hoàn thiện hồ sơ.
        userRepository.save(user);

        // =============================================
        // ĐĂNG NHẬP NGAY SAU KHI ĐĂNG KÝ
        // =============================================

        session.setAttribute("loggedInUser", user);
        session.setAttribute("username", user.getUsername());

        List<String> roleNames = user.getRoles()
                .stream()
                .map(Role::getRoleName)
                .toList();

        session.setAttribute("roleNames", roleNames);

        session.setAttribute(
                "notification",
                "Đăng ký tài khoản thành công. Vui lòng hoàn thiện hồ sơ."
        );

        // URL này cần khớp với @GetMapping trong ProfileController.
        return "redirect:/profile/complete";


    }


    // =====================================================
    // FORGOT PASSWORD - GET
    // =====================================================

    @GetMapping("/forgot-password")
    public String showForgotPasswordPage() {

        return "/user/forgot-password";
    }


    // =====================================================
    // FORGOT PASSWORD - POST
    // =====================================================

    @PostMapping("/forgot-password")
    public String processForgotPassword(
            @RequestParam("username") String username,
            HttpSession session,
            Model model) {

        username = username == null
                ? ""
                : username.trim();

        if (username.isEmpty()) {

            model.addAttribute(
                    "error",
                    "Vui lòng nhập tên đăng nhập."
            );

            return "/user/forgot-password";
        }


        Optional<User> userOpt =
                userRepository.findByUsername(username);


        /*
         * Với project hiện tại:
         * nếu tìm thấy tài khoản thì hiển thị email đã che.
         *
         * Về bảo mật cao hơn, OWASP khuyến nghị thông báo
         * giống nhau cho tài khoản tồn tại và không tồn tại
         * để tránh user enumeration.
         */

        if (userOpt.isEmpty()) {

            model.addAttribute(
                    "error",
                    "Nếu tài khoản tồn tại, hệ thống sẽ gửi mã OTP đến email đã đăng ký."
            );

            return "/user/forgot-password";
        }


        User user = userOpt.get();

        String email = user.getEmail();


        if (email == null || email.isBlank()) {

            model.addAttribute(
                    "error",
                    "Nếu tài khoản tồn tại, hệ thống sẽ gửi mã OTP đến email đã đăng ký."
            );

            return "/user/forgot-password";
        }


        // =================================================
        // TẠO OTP BẰNG SECURE RANDOM
        // =================================================

        SecureRandom secureRandom =
                new SecureRandom();

        int otpNumber =
                secureRandom.nextInt(1_000_000);

        String otp =
                String.format(
                        "%06d",
                        otpNumber
                );


        // =================================================
        // TẠO TOKEN
        // =================================================

        Token resetToken = new Token();

        resetToken.setToken(otp);

        resetToken.setExpiryDate(
                LocalDateTime.now()
                        .plusMinutes(5)
        );

        resetToken.setUser(user);

        tokenRepository.save(resetToken);


        // =================================================
        // GỬI EMAIL
        // =================================================

        String emailContent =
                "Xin chào,\n\n"
                        + "Bạn vừa yêu cầu đặt lại mật khẩu cho tài khoản: "
                        + username
                        + "\n\n"
                        + "Mã OTP của bạn là: "
                        + otp
                        + "\n\n"
                        + "Mã OTP có hiệu lực trong 5 phút "
                        + "và chỉ được sử dụng một lần.\n\n"
                        + "Nếu bạn không thực hiện yêu cầu này, "
                        + "vui lòng bỏ qua email này.\n\n"
                        + "Trân trọng,\n"
                        + "CV-DCMS";


        emailService.sendSimpleMail(
                email,
                "CV-DCMS - Mã OTP đặt lại mật khẩu",
                emailContent
        );


        // =================================================
        // LƯU THÔNG TIN RESET VÀO SESSION
        // =================================================

        session.setAttribute(
                "resetUsername",
                username
        );

        session.setAttribute(
                "maskedEmail",
                maskEmail(email)
        );


        return "redirect:/verify-otp";
    }


    // =====================================================
    // VERIFY OTP - GET
    // =====================================================

    @GetMapping("/verify-otp")
    public String showVerifyOtpPage(
            HttpSession session,
            Model model) {

        String username =
                (String) session
                        .getAttribute("resetUsername");

        if (username == null) {

            return "redirect:/forgot-password";
        }

        model.addAttribute(
                "maskedEmail",
                session.getAttribute("maskedEmail")
        );

        return "/user/verify-otp";
    }


    // =====================================================
    // VERIFY OTP - POST
    // =====================================================

    @PostMapping("/verify-otp")
    public String processVerifyOtp(
            @RequestParam("otp") String otp,
            HttpSession session,
            Model model) {

        String username =
                (String) session
                        .getAttribute("resetUsername");


        if (username == null) {

            return "redirect:/user/forgot-password";
        }


        otp = otp == null ? "" : otp.trim();

        if (!OTP_PATTERN.matcher(otp).matches()) {
            model.addAttribute("error", "Mã OTP phải gồm 6 chữ số.");
            model.addAttribute(
                    "maskedEmail",
                    session.getAttribute("maskedEmail")
            );

            return "/user/verify-otp";
        }


        User user =
                userRepository
                        .findByUsername(username)
                        .orElse(null);


        if (user == null) {

            return "redirect:/user/forgot-password";
        }


        Optional<Token> tokenOpt =
                tokenRepository
                        .findByTokenAndUser(
                                otp,
                                user
                        );


        if (tokenOpt.isEmpty()) {

            model.addAttribute(
                    "error",
                    "Mã OTP không hợp lệ."
            );

            model.addAttribute(
                    "maskedEmail",
                    session.getAttribute("maskedEmail")
            );

            return "/user/verify-otp";
        }


        Token token = tokenOpt.get();


        if (token.getExpiryDate() == null ||
                token.getExpiryDate()
                        .isBefore(LocalDateTime.now())) {

            tokenRepository.delete(token);

            model.addAttribute(
                    "error",
                    "Mã OTP đã hết hạn. Vui lòng yêu cầu mã mới."
            );

            model.addAttribute(
                    "maskedEmail",
                    session.getAttribute("maskedEmail")
            );

            return "/user/verify-otp";
        }


        /*
         * OTP đúng.
         *
         * Chưa đổi mật khẩu ở đây.
         *
         * Chuyển sang bước đặt mật khẩu mới.
         */

        session.setAttribute(
                "resetVerified",
                true
        );


        return "redirect:/reset-password";
    }


    // =====================================================
    // RESET PASSWORD - GET
    // =====================================================


    @GetMapping("/reset-password")
    public String showResetPasswordPage(
            HttpSession session) {

        String username =
                (String) session
                        .getAttribute("resetUsername");

        Boolean verified =
                (Boolean) session
                        .getAttribute("resetVerified");


        if (username == null ||
                !Boolean.TRUE.equals(verified)) {

            return "redirect:/forgot-password";
        }


        return "/user/reset-password";
    }


    // =====================================================
    // RESET PASSWORD - POST
    // =====================================================

    @PostMapping("/reset-password")
    public String processResetPassword(
            @RequestParam("newPassword")
            String newPassword,

            @RequestParam("confirmPassword")
            String confirmPassword,

            HttpSession session,
            Model model) {


        String username =
                (String) session
                        .getAttribute("resetUsername");

        Boolean verified =
                (Boolean) session
                        .getAttribute("resetVerified");


        if (username == null ||
                !Boolean.TRUE.equals(verified)) {

            return "redirect:/forgot-password";
        }


        // =================================================
        // PASSWORD
        // =================================================

        if (newPassword == null ||
                newPassword.length() < 8 ||
                newPassword.length() > 64) {

            model.addAttribute(
                    "error",
                    "Mật khẩu mới phải từ 8 đến 64 ký tự."
            );

            return "/user/reset-password";
        }


        if (confirmPassword == null
                || !newPassword.equals(confirmPassword)) {

            model.addAttribute(
                    "error",
                    "Mật khẩu xác minh không khớp."
            );

            return "/user/reset-password";
        }


        // =================================================
        // TÌM USER
        // =================================================

        User user =
                userRepository
                        .findByUsername(username)
                        .orElse(null);


        if (user == null) {

            return "redirect:/login";
        }


        // =================================================
        // ĐỔI PASSWORD
        // =================================================

        user.setPassword(
                passwordEncoder.encode(
                        newPassword
                )
        );

        userRepository.save(user);


        // =================================================
        // XÓA SESSION RESET
        // =================================================

        session.removeAttribute(
                "resetUsername"
        );

        session.removeAttribute(
                "maskedEmail"
        );

        session.removeAttribute(
                "resetVerified"
        );


        session.setAttribute(
                "notification",
                "Đổi mật khẩu thành công! Vui lòng đăng nhập bằng mật khẩu mới."
        );


        return "redirect:/login";
    }


    // ================= ĐĂNG XUẤT =================

    @PostMapping("/logout")
    public String logout(HttpSession session) {

        session.invalidate();

        return "redirect:/login";
    }
}