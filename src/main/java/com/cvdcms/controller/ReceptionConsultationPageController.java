package com.cvdcms.controller;

import com.cvdcms.security.ReceptionAccess;
import com.cvdcms.service.ReceptionConsultationService;

import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/reception/consultations")
public class ReceptionConsultationPageController {

    private final ReceptionConsultationService consultationService;
    private final UserRepository users;

    public ReceptionConsultationPageController(
            ReceptionConsultationService consultationService,
            UserRepository users
    ) {
        this.consultationService = consultationService;
        this.users = users;
    }

    @GetMapping
    public String page(
            HttpSession session,
            @RequestParam(required = false) Long branchId,
            Model model
    ) {
        ReceptionAccess.Scope scope = consultationService.requireViewScope(session);
        if (branchId != null && !scope.permits(branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Bạn không có quyền truy cập chi nhánh đã chọn.");
        }

        boolean canAssignDoctor = scope.admin()
                || users.hasActivePermission(scope.user().getUserId(), "CLINICAL_DISPATCH");
        model.addAttribute("activeMenu", "appointments");
        model.addAttribute("canManage", scope.canManage());
        model.addAttribute("canAssignDoctor", canAssignDoctor);
        model.addAttribute("isAdmin", scope.admin());
        model.addAttribute("currentUserId", scope.user().getUserId());
        if (branchId != null) {
            if (session != null) {
                session.setAttribute("currentBranchId", branchId);
            }
            model.addAttribute("currentBranchId", branchId);
        }
        return "reception/consultations/index";
    }
}
