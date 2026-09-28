package com.cvdcms.controller;

import com.cvdcms.DTO.ChangePasswordDTO;
import com.cvdcms.DTO.ProfileUpdateDTO;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
public class ProfileController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ProfileController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }


    // =========================================================
    // XEM THÔNG TIN CÁ NHÂN
    // =========================================================

    @GetMapping("/profile")
    public String profile(
            HttpSession session,
            Model model) {

        User loggedInUser =
                (User) session.getAttribute("loggedInUser");

        if (loggedInUser == null) {
            return "redirect:/login";
        }


        // Đưa user ra giao diện
        model.addAttribute(
                "user",
                loggedInUser
        );


        // -----------------------------
        // SUCCESS MESSAGE
        // -----------------------------

        Object success =
                session.getAttribute("profileSuccess");

        if (success != null) {

            model.addAttribute(
                    "success",
                    success
            );

            // Chỉ hiển thị 1 lần
            session.removeAttribute(
                    "profileSuccess"
            );
        }


        return "profile/profile";
    }


    // =========================================================
    // FORM CHỈNH SỬA
    // =========================================================

    @GetMapping("/profile/edit")
    public String editProfile(
            HttpSession session,
            Model model) {

        User loggedInUser =
                (User) session.getAttribute("loggedInUser");

        if (loggedInUser == null) {
            return "redirect:/login";
        }

        ProfileUpdateDTO dto = new ProfileUpdateDTO();

        dto.setFullName(loggedInUser.getFullName());
        dto.setEmail(loggedInUser.getEmail());
        dto.setPhoneNumber(loggedInUser.getPhoneNumber());
        dto.setDob(loggedInUser.getDob());
        dto.setGender(loggedInUser.getGender());
        dto.setAddress(loggedInUser.getAddress());

        model.addAttribute("profileUpdateDTO", dto);
        model.addAttribute("user", loggedInUser);

        return "profile/edit";
    }


    // =========================================================
    // LƯU THÔNG TIN CÁ NHÂN
    // =========================================================

    @PostMapping("/profile/edit")
    public String updateProfile(
            @Valid @ModelAttribute("profileUpdateDTO")
            ProfileUpdateDTO dto,

            BindingResult bindingResult,

            HttpSession session,
            Model model) {

        User loggedInUser =
                (User) session.getAttribute("loggedInUser");

        if (loggedInUser == null) {
            return "redirect:/login";
        }

        if (bindingResult.hasErrors()) {

            model.addAttribute("user", loggedInUser);

            return "profile/edit";
        }


        User user = userRepository
                .findById(loggedInUser.getUserId())
                .orElse(null);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }


        // Chỉ cập nhật những trường được phép

        user.setFullName(
                dto.getFullName() != null
                        ? dto.getFullName().trim()
                        : null
        );

        user.setEmail(
                dto.getEmail() != null
                        ? dto.getEmail().trim()
                        : null
        );

        user.setPhoneNumber(
                dto.getPhoneNumber() != null
                        ? dto.getPhoneNumber().trim()
                        : null
        );

        user.setDob(dto.getDob());

        user.setGender(dto.getGender());

        user.setAddress(
                dto.getAddress() != null
                        ? dto.getAddress().trim()
                        : null
        );


        User updatedUser =
                userRepository.save(user);


        // Cập nhật lại Session
        session.setAttribute(
                "loggedInUser",
                updatedUser
        );


        return "redirect:/profile?success";
    }


    // =========================================================
    // ĐỔI MẬT KHẨU - FORM
    // =========================================================



    @GetMapping("/profile/change-password")
    public String changePassword(
            HttpSession session,
            Model model) {

        User loggedInUser =
                (User) session.getAttribute("loggedInUser");

        if (loggedInUser == null) {
            return "redirect:/login";
        }

        model.addAttribute(
                "changePasswordDTO",
                new ChangePasswordDTO()
        );

        return "profile/change-password";
    }


    // =====================================================
// CHANGE PASSWORD - POST
// =====================================================

    @PostMapping("/profile/change-password")
    public String processChangePassword(
            @Valid @ModelAttribute("changePasswordDTO")
            ChangePasswordDTO dto,

            BindingResult bindingResult,

            HttpSession session,
            Model model) {

        // -----------------------------
        // CHECK LOGIN
        // -----------------------------

        User loggedInUser =
                (User) session.getAttribute("loggedInUser");

        if (loggedInUser == null) {
            return "redirect:/login";
        }


        // -----------------------------
        // VALIDATE DTO
        // -----------------------------

        if (bindingResult.hasErrors()) {
            return "profile/change-password";
        }


        // -----------------------------
        // GET USER FROM DATABASE
        // -----------------------------

        User user = userRepository
                .findById(loggedInUser.getUserId())
                .orElse(null);

        if (user == null) {
            session.invalidate();
            return "redirect:/login";
        }


        // -----------------------------
        // CHECK CURRENT PASSWORD
        // -----------------------------

        if (!passwordEncoder.matches(
                dto.getCurrentPassword(),
                user.getPassword())) {

            model.addAttribute(
                    "error",
                    "Mật khẩu hiện tại không đúng."
            );

            return "profile/change-password";
        }


        // -----------------------------
        // CHECK NEW PASSWORD
        // -----------------------------

        if (dto.getCurrentPassword()
                .equals(dto.getNewPassword())) {

            model.addAttribute(
                    "error",
                    "Mật khẩu mới phải khác mật khẩu hiện tại."
            );

            return "profile/change-password";
        }


        // -----------------------------
        // CONFIRM PASSWORD
        // -----------------------------

        if (!dto.getNewPassword()
                .equals(dto.getConfirmPassword())) {

            model.addAttribute(
                    "error",
                    "Mật khẩu xác nhận không khớp."
            );

            return "profile/change-password";
        }


        // -----------------------------
        // ENCODE NEW PASSWORD
        // -----------------------------

        user.setPassword(
                passwordEncoder.encode(
                        dto.getNewPassword()
                )
        );


        // -----------------------------
        // SAVE DATABASE
        // -----------------------------

        User updatedUser =
                userRepository.save(user);


        // -----------------------------
        // UPDATE SESSION
        // -----------------------------

        session.setAttribute(
                "loggedInUser",
                updatedUser
        );


        // -----------------------------
        // SUCCESS MESSAGE
        // -----------------------------

        session.setAttribute(
                "profileSuccess",
                "Đổi mật khẩu thành công!"
        );


        // -----------------------------
        // REDIRECT TO PROFILE
        // -----------------------------

        return "redirect:/profile";
    }



}