package com.cvdcms.controller;

import com.cvdcms.entity.User;
import com.cvdcms.service.DoctorNotificationService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class DoctorNotificationController {

    private final DoctorNotificationService doctorNotificationService;

    @GetMapping("/doctor/notifications")
    public String notificationsPage(HttpSession session, Model model) {
        User user = requireSessionUser(session);
        model.addAttribute("activeMenu", "doctor-notifications");
        model.addAttribute("currentUserId", user.getUserId());
        return "doctor/notifications";
    }

    @GetMapping("/api/doctor/notifications")
    @ResponseBody
    public Map<String, Object> listNotifications(
            @RequestParam(defaultValue = "ALL") String category,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "30") int limit,
            HttpSession session) {
        User user = requireSessionUser(session);
        return doctorNotificationService.getNotifications(user.getUserId(), category, unreadOnly, limit);
    }

    @PostMapping("/api/doctor/notifications/{id}/read")
    @ResponseBody
    public Map<String, Object> markRead(@PathVariable Long id, HttpSession session) {
        User user = requireSessionUser(session);
        return doctorNotificationService.markAsRead(id, user.getUserId());
    }

    @PostMapping("/api/doctor/notifications/read-all")
    @ResponseBody
    public Map<String, Object> markAllRead(HttpSession session) {
        User user = requireSessionUser(session);
        return doctorNotificationService.markAllAsRead(user.getUserId());
    }

    private User requireSessionUser(HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        return user;
    }
}
