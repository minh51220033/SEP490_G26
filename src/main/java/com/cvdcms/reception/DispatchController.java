package com.cvdcms.reception;

import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.entity.enums.VisitType;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.List;

@Controller
@RequestMapping("/reception/dispatch")
public class DispatchController {
    private final DispatchService service;
    public DispatchController(DispatchService service) { this.service = service; }

    @ModelAttribute("roleNames")
    public List<String> roles() { return List.of("LE_TAN"); }

    @GetMapping
    public String board(@RequestParam(required = false) Long branchId,
                        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate day,
                        @RequestParam(required = false) String keyword,
                        @RequestParam(required = false) Long doctorId,
                        @RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "10") int size,
                        HttpSession session, Model model) {
        Object loggedIn = session == null ? null : session.getAttribute("loggedInUser");
        if (!(loggedIn instanceof com.cvdcms.entity.User)) {
            return "redirect:/login";
        }

        if (size != 10 && size != 25 && size != 50 && size != 100) {
            size = 10;
        }

        if (day != null && session != null) {
            session.setAttribute("currentWorkflowDay", day);
        }

        Long effectiveBranchId = branchId;
        if (effectiveBranchId == null && session != null) {
            Object sId = session.getAttribute("currentBranchId");
            if (sId instanceof Long sid) effectiveBranchId = sid;
        }

        var board = service.board(session, effectiveBranchId);
        if (session != null) {
            session.setAttribute("currentBranchId", board.branchId());
        }
        List<Visit> allVisits = board.visits();

        // Lọc theo keyword, doctorId, status
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<Visit> filtered = allVisits.stream().filter(v -> {
            if (!kw.isEmpty()) {
                String searchTarget = (
                        (v.getVisitCode() != null ? v.getVisitCode() : "") + " " +
                        (v.getPatientProfile() != null ? v.getPatientProfile().getFullName() + " " + v.getPatientProfile().getPatientCode() + " " + v.getPatientProfile().getPhoneNumber() : "") + " " +
                        (v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getFullName() : "") + " " +
                        (v.getInitialSymptoms() != null ? v.getInitialSymptoms() : "") + " " +
                        (board.issued().get(v.getVisitId()) != null ? board.issued().get(v.getVisitId()) : "")
                ).toLowerCase();
                if (!searchTarget.contains(kw)) {
                    return false;
                }
            }
            if (doctorId != null && doctorId > 0) {
                if (v.getPrimaryDoctor() == null || !doctorId.equals(v.getPrimaryDoctor().getStaffId())) {
                    return false;
                }
            }
            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
                String st = status.trim().toUpperCase();
                switch (st) {
                    case "UNASSIGNED" -> {
                        if (v.getPrimaryDoctor() != null || !isActiveVisit(v)) return false;
                    }
                    case "WAITING" -> {
                        if (v.getStatus() != VisitStatus.WAITING || v.getPrimaryDoctor() == null) return false;
                    }
                    case "IN_EXAM" -> {
                        if (v.getStatus() != VisitStatus.IN_EXAM && v.getStatus() != VisitStatus.IN_TREATMENT) return false;
                    }
                    case "WAITING_RESULT" -> {
                        if (v.getStatus() != VisitStatus.WAITING_RESULT) return false;
                    }
                    case "PRIORITY" -> {
                        String num = board.issued().get(v.getVisitId());
                        boolean isPri = v.getVisitType() == VisitType.EMERGENCY
                                || (num != null && num.startsWith("P-"))
                                || (v.getNotes() != null && v.getNotes().contains("ƯU TIÊN"));
                        if (!isPri) return false;
                    }
                    case "COMPLETED" -> {
                        if (v.getStatus() != VisitStatus.COMPLETED && v.getStatus() != VisitStatus.WAITING_PAYMENT) return false;
                    }
                    default -> {}
                }
            }
            return true;
        }).toList();

        long totalItems = filtered.size();
        int totalPages = (int) Math.ceil((double) totalItems / size);
        int currentPage = page;
        if (currentPage >= totalPages && totalPages > 0) {
            currentPage = totalPages - 1;
        }
        if (currentPage < 0) {
            currentPage = 0;
        }

        int fromIndex = currentPage * size;
        int toIndex = Math.min(fromIndex + size, (int) totalItems);
        List<Visit> pagedItems = totalItems == 0 ? List.of() : filtered.subList(fromIndex, toIndex);

        long toItem = Math.min(((long) (currentPage + 1) * size), totalItems);
        long fromItem = totalItems == 0 ? 0 : Math.min(((long) currentPage * size) + 1, toItem);

        java.util.Map<Long, List<Visit>> filteredDoctorVisits = new java.util.LinkedHashMap<>();
        for (var doc : board.doctors()) {
            List<Visit> docList = pagedItems.stream()
                    .filter(v -> v.getPrimaryDoctor() != null && doc.id().equals(v.getPrimaryDoctor().getStaffId()))
                    .toList();
            filteredDoctorVisits.put(doc.id(), docList);
        }
        List<Visit> filteredUnassigned = pagedItems.stream()
                .filter(v -> v.getPrimaryDoctor() == null)
                .toList();

        model.addAttribute("board", board);
        model.addAttribute("currentBranchId", board.branchId());
        model.addAttribute("items", pagedItems);
        model.addAttribute("doctorVisitMap", filteredDoctorVisits);
        model.addAttribute("unassignedItems", filteredUnassigned);
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("keyword", keyword != null ? keyword : "");
        model.addAttribute("selectedDoctorId", doctorId);
        model.addAttribute("selectedStatus", status != null ? status : "ALL");
        model.addAttribute("sidebarMenu", "dispatch");
        return "reception/dispatch";
    }

    private static boolean isActiveVisit(Visit v) {
        return DispatchService.isWaitingDispatchVisit(v);
    }

    @PostMapping("/{visitId}/assign")
    public String assign(@PathVariable Long visitId, @RequestParam Long branchId,
                         @RequestParam(required = false) Long doctorId,
                         @RequestParam(required = false) Long roomId,
                         @RequestParam(required = false) Long chairId,
                         @RequestParam(required = false) String reason,
                         @RequestParam(required = false) String note,
                         @RequestParam(defaultValue = "false") boolean issueTicket,
                         @RequestParam(defaultValue = "false") boolean priority,
                         HttpSession session, RedirectAttributes flash) {
        try {
            if (issueTicket) {
                var ticket = service.completeDispatchAndIssueTicket(session, branchId, visitId, doctorId, roomId, chairId, priority, reason, note);
                populateTicketFlash(flash, session, ticket, "Đã phân công bác sĩ và cấp số ");
            } else {
                if (note != null && !note.isBlank()) {
                    service.assign(session, branchId, visitId, doctorId, roomId, chairId, reason, note);
                } else {
                    service.assign(session, branchId, visitId, doctorId, roomId, chairId, reason);
                }
                flash.addFlashAttribute("notice", "Đã lưu phân công. Bệnh nhân tiếp tục chờ theo bác sĩ được phân.");
            }
        } catch (ResponseStatusException ex) {
            flash.addFlashAttribute("error", ex.getReason());
        }
        return "redirect:/reception/dispatch?branchId=" + branchId;
    }

    @PostMapping("/{visitId}/ticket")
    public String ticket(@PathVariable Long visitId, @RequestParam Long branchId,
            @RequestParam(defaultValue = "false") boolean priority, @RequestParam(required = false) String reason,
            HttpSession session, RedirectAttributes flash) {
        try {
            var ticket = service.issue(session, branchId, visitId, priority, reason);
            populateTicketFlash(flash, session, ticket, "Đã cấp số ");
        } catch (ResponseStatusException ex) {
            flash.addFlashAttribute("error", ex.getReason());
        }
        return "redirect:/reception/dispatch?branchId=" + branchId;
    }

    private void populateTicketFlash(RedirectAttributes flash, HttpSession session, com.cvdcms.entity.QueueTicket ticket, String prefix) {
        if (ticket == null) return;
        Visit v = ticket.getVisit();
        String pName = v != null && v.getPatientProfile() != null ? v.getPatientProfile().getFullName() : "Bệnh nhân";
        String pCode = v != null && v.getPatientProfile() != null ? v.getPatientProfile().getPatientCode() : "—";
        String docName = v != null && v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getFullName() : "Chưa gán";
        String roomName = "Phòng khám Nha khoa";
        String chairName = "Ghế tiêu chuẩn";
        try {
            var pv = service.printView(session, ticket.getTicketId());
            if (pv.roomName() != null && !pv.roomName().isBlank()) roomName = pv.roomName();
            if (pv.chairName() != null && !pv.chairName().isBlank()) chairName = pv.chairName();
        } catch (Exception ignored) {
        }
        flash.addFlashAttribute("notice", prefix + ticket.getDisplayNumber()
                + " cho bệnh nhân " + pName + " (Mã BN: " + pCode + ") — Bác sĩ: " + docName
                + " — Phòng khám: " + roomName + ".");
        flash.addFlashAttribute("issuedTicketId", ticket.getTicketId());
        flash.addFlashAttribute("issuedTicketNumber", ticket.getDisplayNumber());
        flash.addFlashAttribute("issuedPatientName", pName);
        flash.addFlashAttribute("issuedPatientCode", pCode);
        flash.addFlashAttribute("issuedDoctorName", docName);
        flash.addFlashAttribute("issuedRoomName", roomName);
        flash.addFlashAttribute("issuedChairName", chairName);
        flash.addFlashAttribute("issuedVisitCode", v != null ? v.getVisitCode() : "");
        flash.addFlashAttribute("issuedIsEmergency", Boolean.TRUE.equals(ticket.getIsEmergency()));
        flash.addFlashAttribute("issuedIsPriority", Boolean.TRUE.equals(ticket.getIsPriority()));
    }

    @GetMapping("/tickets/{id}")
    public String print(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean autoPrint, HttpSession session, Model model) {
        Object loggedIn = session == null ? null : session.getAttribute("loggedInUser");
        if (!(loggedIn instanceof com.cvdcms.entity.User)) {
            return "redirect:/login";
        }
        model.addAttribute("ticket", service.ticket(session, id));
        model.addAttribute("printView", service.printView(session, id));
        model.addAttribute("autoPrint", autoPrint);
        return "reception/exam-ticket";
    }
}
