package com.cvdcms.controller;

import com.cvdcms.DTO.UserRegisterDTO;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.Token;
import com.cvdcms.entity.User;
import com.cvdcms.repository.RoleRepository;
import com.cvdcms.repository.TokenRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.EmailService;

import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
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


    // =====================================================
    // REGEX
    // =====================================================

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^0\\d{9,10}$");


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
    public String home(HttpSession session) {

        // Kiểm tra đã đăng nhập chưa
        if (session.getAttribute("loggedInUser") == null) {
            return "redirect:/login";
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


        // ---------------------------------------------
        // LOGIN SUCCESS
        // ---------------------------------------------

        session.setAttribute(
                "loggedInUser",
                user
        );

        session.setAttribute(
                "username",
                user.getUsername()
        );


        // ---------------------------------------------
        // REDIRECT HOME
        // ---------------------------------------------

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

        String username = dto.getUsername() == null
                ? ""
                : dto.getUsername().trim();

        if (username.isEmpty()) {

            model.addAttribute(
                    "error",
                    "Vui lòng nhập tên đăng nhập."
            );

            return "/user/register";
        }

        if (username.length() < 4 ||
                username.length() > 50) {

            model.addAttribute(
                    "error",
                    "Tên đăng nhập phải từ 4 đến 50 ký tự."
            );

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
                : dto.getEmail().trim();

        if (email.isEmpty() ||
                !EMAIL_PATTERN
                        .matcher(email)
                        .matches()) {

            model.addAttribute(
                    "error",
                    "Email không hợp lệ."
            );

            return "/user/register";
        }


        // -----------------------------
        // PHONE
        // -----------------------------

        String phoneNumber =
                dto.getPhoneNumber() == null
                        ? ""
                        : dto.getPhoneNumber().trim();

        if (!PHONE_PATTERN
                .matcher(phoneNumber)
                .matches()) {

            model.addAttribute(
                    "error",
                    "Số điện thoại không hợp lệ."
            );

            return "/user/register";
        }


        // -----------------------------
        // ROLE PATIENT
        // -----------------------------

        Role patientRole =
                roleRepository.findById(6)
                        .orElse(null);

        if (patientRole == null) {

            model.addAttribute(
                    "error",
                    "Không tìm thấy quyền khách hàng."
            );

            return "/user/register";
        }


        // -----------------------------
        // CREATE USER
        // -----------------------------

        User user = new User();

        user.setUsername(username);

        /*
         * KHÔNG lưu password dạng plaintext.
         */
        user.setPassword(
                passwordEncoder.encode(password)
        );

        user.setEmail(email);

        user.setPhoneNumber(phoneNumber);

        /*
         * Theo yêu cầu hiện tại:
         * không bắt buộc họ tên.
         */
        user.setFullName(null);

        user.setIsActive(true);

        user.setRole(patientRole);


        userRepository.save(user);


        // -----------------------------
        // SUCCESS
        // -----------------------------

        session.setAttribute(
                "notification",
                "Đăng ký tài khoản thành công! Vui lòng đăng nhập."
        );

        return "redirect:/login";
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


        if (otp == null ||
                !otp.matches("\\d{6}")) {

            model.addAttribute(
                    "error",
                    "Mã OTP phải gồm 6 chữ số."
            );

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


        if (!newPassword.equals(confirmPassword)) {

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