package com.cvdcms.reception;

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
    public String board(@RequestParam(required = false) Long branchId, HttpSession session, Model model) {
        Object loggedIn = session == null ? null : session.getAttribute("loggedInUser");
        if (!(loggedIn instanceof com.cvdcms.entity.User)) {
            return "redirect:/login";
        }
        model.addAttribute("board", service.board(session, branchId));
        return "reception/dispatch";
    }

    @PostMapping("/{visitId}/assign")
    public String assign(@PathVariable Long visitId, @RequestParam Long branchId, @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long roomId, @RequestParam(required = false) Long chairId,
            @RequestParam(required = false) String reason,
            HttpSession session, RedirectAttributes flash) {
        try {
            service.assign(session, branchId, visitId, doctorId, roomId, chairId, reason);
            flash.addFlashAttribute("notice", "Đã lưu phân công. Bệnh nhân tiếp tục chờ theo bác sĩ được phân.");
        } catch (ResponseStatusException ex) { flash.addFlashAttribute("error", ex.getReason()); }
        return "redirect:/reception/dispatch?branchId=" + branchId;
    }

    @PostMapping("/{visitId}/ticket")
    public String ticket(@PathVariable Long visitId, @RequestParam Long branchId,
            @RequestParam(defaultValue = "false") boolean priority, @RequestParam(required = false) String reason,
            HttpSession session, RedirectAttributes flash) {
        try {
            var ticket = service.issue(session, branchId, visitId, priority, reason);
            return "redirect:/reception/dispatch/tickets/" + ticket.getTicketId();
        } catch (ResponseStatusException ex) {
            flash.addFlashAttribute("error", ex.getReason());
            return "redirect:/reception/dispatch?branchId=" + branchId;
        }
    }

    @GetMapping("/tickets/{id}")
    public String print(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean autoPrint, HttpSession session, Model model) {
        Object loggedIn = session == null ? null : session.getAttribute("loggedInUser");
        if (!(loggedIn instanceof com.cvdcms.entity.User)) {
            return "redirect:/login";
        }
        model.addAttribute("ticket", service.ticket(session, id));
        model.addAttribute("autoPrint", autoPrint);
        return "reception/exam-ticket";
    }
}
