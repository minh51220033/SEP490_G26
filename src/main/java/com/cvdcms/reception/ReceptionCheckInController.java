package com.cvdcms.reception;

import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/reception/check-in")
public class ReceptionCheckInController {
    private final ReceptionService service;

    public ReceptionCheckInController(ReceptionService service) {
        this.service = service;
    }

    @GetMapping
    public String page(@RequestParam(required = false) Long branchId,
                       @RequestParam(required = false) String query,
                       @RequestParam(required = false) Long appointmentId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                       HttpSession session, Model model) {
        String effectiveQuery = query;
        if ((effectiveQuery == null || effectiveQuery.isBlank()) && model.containsAttribute("savedQuery")) {
            Object sq = model.getAttribute("savedQuery");
            if (sq != null) effectiveQuery = sq.toString();
        }
        model.addAttribute("dashboard", service.dashboard(session, branchId, effectiveQuery, day));
        model.addAttribute("selectedAppointmentId", appointmentId);
        model.addAttribute("selectedDay", day);
        model.addAttribute("roleNames", List.of("LE_TAN"));
        model.addAttribute("sidebarMenu", "checkin");
        return "reception/check-in";
    }

    @GetMapping("/patients/search")
    @ResponseBody
    public List<ReceptionService.PatientRow> searchPatients(@RequestParam(required = false) String query,
                                                            HttpSession session) {
        return service.searchPatients(session, query);
    }

    @PostMapping("/appointments/{id}")
    public String appointment(@PathVariable Long id, @RequestParam Long branchId,
                              @RequestParam(required = false) String symptoms,
                              @RequestParam(defaultValue = "false") boolean priority,
                              @RequestParam(defaultValue = "false") boolean emergency,
                              @RequestParam String requestKey,
                              HttpSession session, RedirectAttributes redirect) {
        try {
            var r = service.checkInAppointment(session, id, symptoms, requestKey, priority, emergency);
            redirect.addFlashAttribute("notice", "Đã check-in thành công cho " + r.patientName() + " – Lượt khám " + r.code() + ".");
            redirect.addFlashAttribute("checkedInVisitId", r.id());
            redirect.addFlashAttribute("checkedInPatientName", r.patientName());
            redirect.addFlashAttribute("checkedInCode", r.code());
        } catch (ResponseStatusException e) {
            redirect.addFlashAttribute("error", e.getReason());
        }
        return "redirect:/reception/check-in?branchId=" + branchId;
    }

    @PostMapping("/appointments/{id}/confirm-and-check-in")
    public String confirmAndCheckIn(@PathVariable Long id, @RequestParam Long branchId,
                                    @RequestParam(required = false) String symptoms,
                                    @RequestParam(defaultValue = "false") boolean priority,
                                    @RequestParam(defaultValue = "false") boolean emergency,
                                    @RequestParam String requestKey,
                                    HttpSession session, RedirectAttributes redirect) {
        try {
            var r = service.confirmAndCheckIn(session, id, symptoms, requestKey, priority, emergency);
            redirect.addFlashAttribute("notice", "Đã xác nhận & check-in thành công cho " + r.patientName() + " – Lượt khám " + r.code() + ".");
            redirect.addFlashAttribute("checkedInVisitId", r.id());
            redirect.addFlashAttribute("checkedInPatientName", r.patientName());
            redirect.addFlashAttribute("checkedInCode", r.code());
        } catch (ResponseStatusException e) {
            redirect.addFlashAttribute("error", e.getReason());
        }
        return "redirect:/reception/check-in?branchId=" + branchId;
    }

    @PostMapping("/appointments/{id}/revert")
    public String revertCheckIn(@PathVariable Long id, @RequestParam Long branchId,
                                @RequestParam(required = false) String returnUrl,
                                HttpSession session, RedirectAttributes redirect) {
        try {
            var r = service.revertCheckIn(session, id);
            redirect.addFlashAttribute("notice", "Đã hoàn tác check-in thành công cho " + r.patientName() + " – Lịch hẹn trở về trạng thái Đã xác nhận.");
        } catch (ResponseStatusException e) {
            redirect.addFlashAttribute("error", e.getReason());
        }
        if (returnUrl != null && !returnUrl.isBlank()) {
            String trimmed = returnUrl.trim();
            if (trimmed.startsWith("/") && !trimmed.startsWith("//") && !trimmed.contains("\\")) {
                return "redirect:" + trimmed;
            }
        }
        return "redirect:/reception/check-in?branchId=" + branchId;
    }

    @PostMapping("/appointments/{id}/note")
    public String updateNote(@PathVariable Long id, @RequestParam Long branchId,
                             @RequestParam(required = false) String note,
                             HttpSession session, RedirectAttributes redirect) {
        try {
            service.updateAppointmentNote(session, id, note);
            redirect.addFlashAttribute("notice", "Đã cập nhật chú thích thành công.");
        } catch (ResponseStatusException e) {
            redirect.addFlashAttribute("error", e.getReason());
        }
        return "redirect:/reception/check-in?branchId=" + branchId;
    }

    @PostMapping("/walk-ins")
    public String walkIn(@RequestParam Long branchId, @RequestParam Long profileId,
                         @RequestParam String symptoms,
                         @RequestParam(defaultValue = "false") boolean priority,
                         @RequestParam(defaultValue = "false") boolean emergency,
                         @RequestParam String requestKey,
                         @RequestParam(required = false) String query,
                         HttpSession session, RedirectAttributes redirect) {
        try {
            var r = service.checkInWalkIn(session, branchId, profileId, symptoms, emergency, priority, requestKey);
            redirect.addFlashAttribute("notice", "Đã check-in thành công cho " + r.patientName() + " – Lượt khám " + r.code() + ".");
            redirect.addFlashAttribute("checkedInVisitId", r.id());
            redirect.addFlashAttribute("checkedInPatientName", r.patientName());
            redirect.addFlashAttribute("checkedInCode", r.code());
            return "redirect:/reception/check-in?branchId=" + branchId;
        } catch (ResponseStatusException e) {
            redirect.addFlashAttribute("error", e.getReason());
            redirect.addFlashAttribute("savedSymptoms", symptoms);
            redirect.addFlashAttribute("savedEmergency", emergency);
            redirect.addFlashAttribute("savedPriority", priority);
            redirect.addFlashAttribute("savedProfileId", profileId);
            redirect.addFlashAttribute("savedQuery", query);
            String redirectUrl = "/reception/check-in?branchId=" + branchId;
            if (query != null && !query.isBlank()) {
                redirectUrl += "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            }
            return "redirect:" + redirectUrl;
        }
    }
}
