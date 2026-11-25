package com.cvdcms.controller;

import com.cvdcms.security.PortalAccess;
import com.cvdcms.service.PortalAppointmentService;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.ui.Model;

import java.util.List;

@Controller
@RequestMapping("/portal/appointments")
public class PortalAppointmentPageController {
    private final PortalAccess access;
    private final PortalAppointmentService appointments;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.PatientProfileRepository patientProfileRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.UserRepository userRepository;

    public PortalAppointmentPageController(PortalAccess access, PortalAppointmentService appointments) {
        this.access = access; this.appointments = appointments;
    }

    @ModelAttribute("roleNames")
    public List<String> roles(HttpSession session) {
        access.requireUser(session);
        return List.of("BENH_NHAN");
    }

    @GetMapping
    public String page() { return "portal/appointments/index"; }

    @GetMapping("/new")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public String newAppointment(HttpSession session, Model model, @RequestParam(required = false) Long profileId) {
        access.requireUser(session);
        model.addAttribute("selectedProfileId", profileId);
        model.addAttribute("defaultBranchId", resolveDefaultBranchId(session, profileId));
        return "portal/appointments/form";
    }

    private Long resolveDefaultBranchId(HttpSession session, Long requestedProfileId) {
        if (session == null) {
            return null;
        }
        if (session.getAttribute("loggedInUser") instanceof com.cvdcms.entity.User sessionUser
                && sessionUser.getUserId() != null) {
            if (patientProfileRepository != null) {
                var profiles = patientProfileRepository
                        .findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(sessionUser.getUserId());
                if (profiles != null && !profiles.isEmpty()) {
                    com.cvdcms.entity.PatientProfile preferred = null;
                    if (requestedProfileId != null) {
                        preferred = profiles.stream()
                                .filter(p -> requestedProfileId.equals(p.getProfileId()))
                                .findFirst()
                                .orElse(null);
                    }
                    if (preferred == null) {
                        preferred = profiles.stream()
                                .filter(p -> Boolean.TRUE.equals(p.getIsDefault()))
                                .findFirst()
                                .orElse(profiles.get(0));
                    }
                    if (preferred != null && preferred.getPreferredBranch() != null
                            && preferred.getPreferredBranch().getBranchId() != null) {
                        return preferred.getPreferredBranch().getBranchId();
                    }
                }
            }
            if (userRepository != null) {
                var dbUser = userRepository.findById(sessionUser.getUserId()).orElse(null);
                if (dbUser != null && dbUser.getBranch() != null && dbUser.getBranch().getBranchId() != null) {
                    return dbUser.getBranch().getBranchId();
                }
            }
            if (sessionUser.getBranch() != null && sessionUser.getBranch().getBranchId() != null) {
                return sessionUser.getBranch().getBranchId();
            }
        }
        Object branchAttr = session.getAttribute("selectedPortalBranchId");
        return branchAttr instanceof Long id ? id : null;
    }

    @GetMapping("/{id}")
    public String detail(HttpSession session, @PathVariable Long id, Model model) {
        appointments.detail(session, id);
        model.addAttribute("appointmentId", id);
        return "portal/appointments/detail";
    }

    @GetMapping("/{id}/change")
    public String change(HttpSession session, @PathVariable Long id, Model model) {
        appointments.detail(session, id);
        model.addAttribute("appointmentId", id);
        return "portal/appointments/form";
    }
}
