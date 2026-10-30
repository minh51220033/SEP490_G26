package com.cvdcms.reception;

import com.cvdcms.entity.enums.QueueStatus;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/reception/queue")
public class QueueWorkflowController {
    private final QueueWorkflowService service;
    public QueueWorkflowController(QueueWorkflowService service) { this.service = service; }
    @ModelAttribute("roleNames")
    public List<String> roles() { return List.of("LE_TAN"); }

    @GetMapping
    public String page(@RequestParam(required = false) Long branchId, HttpSession session, Model model) {
        populate(session, branchId, model);
        return "reception/queue";
    }
    @GetMapping("/rows")
    public String rows(@RequestParam Long branchId, HttpSession session, Model model) {
        populate(session, branchId, model);
        return "reception/queue-rows :: rows";
    }
    private void populate(HttpSession session, Long branchId, Model model) {
        var board = service.board(session, branchId);
        model.addAttribute("board", board);
        model.addAttribute("lanes", List.of(QueueStatus.WAITING, QueueStatus.CALLED,
                QueueStatus.IN_SERVICE, QueueStatus.PAUSED, QueueStatus.DONE));
        model.addAttribute("laneCounts", board.tickets().stream()
                .collect(Collectors.groupingBy(t -> t.getStatus(), Collectors.counting())));
    }
    @PostMapping("/{id}/{action}")
    public String change(@PathVariable Long id, @PathVariable QueueWorkflowService.Action action,
            @RequestParam Long branchId, @RequestParam(required = false) String reason,
            HttpSession session, RedirectAttributes flash) {
        try {
            service.transition(session, id, action, reason);
            flash.addFlashAttribute("notice", "Đã cập nhật hàng chờ: " + action.name());
        } catch (ResponseStatusException ex) {
            flash.addFlashAttribute("error", ex.getReason());
        }
        return "redirect:/reception/queue?branchId=" + branchId;
    }
    @GetMapping("/tv/{branchId}")
    public String tv(@PathVariable Long branchId, Model model) {
        model.addAttribute("branchId", branchId);
        model.addAttribute("board", service.publicBoard(branchId));
        return "reception/queue-tv";
    }
    @GetMapping("/tv/{branchId}/data")
    @ResponseBody
    public QueueWorkflowService.PublicBoard tvData(@PathVariable Long branchId) {
        return service.publicBoard(branchId);
    }
}
