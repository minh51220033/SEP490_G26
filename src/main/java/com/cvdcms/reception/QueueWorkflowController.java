package com.cvdcms.reception;

import com.cvdcms.entity.enums.QueueStatus;
import com.cvdcms.entity.enums.VisitStatus;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
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
        Long effectiveBranchId = branchId;
        if (effectiveBranchId == null && session != null) {
            Object sId = session.getAttribute("currentBranchId");
            if (sId instanceof Long sid) effectiveBranchId = sid;
        }
        var board = service.board(session, effectiveBranchId);
        if (session != null) {
            session.setAttribute("currentBranchId", board.branchId());
        }
        model.addAttribute("board", board);
        model.addAttribute("currentBranchId", board.branchId());
        Set<Long> callableDoctorIds = board.doctors().stream()
                .filter(doctor -> doctor.called() == 0)
                .map(QueueWorkflowService.DoctorQueue::id)
                .collect(Collectors.toSet());
        var nextByDoctor = new LinkedHashMap<Long, Long>();
        board.tickets().stream()
                .filter(ticket -> ticket.getStatus() == QueueStatus.WAITING
                        && ticket.getVisit().getStatus() == VisitStatus.WAITING
                        && ticket.getVisit().getPrimaryDoctor() != null)
                .forEach(ticket -> nextByDoctor.putIfAbsent(
                        ticket.getVisit().getPrimaryDoctor().getStaffId(), ticket.getTicketId()));
        model.addAttribute("callableDoctorIds", callableDoctorIds);
        model.addAttribute("nextTicketIds", Set.copyOf(nextByDoctor.values()));
        model.addAttribute("lanes", List.of(QueueStatus.WAITING, QueueStatus.CALLED,
                QueueStatus.IN_SERVICE, QueueStatus.DONE));
        model.addAttribute("laneCounts", board.tickets().stream()
                .collect(Collectors.groupingBy(t -> t.getStatus(), Collectors.counting())));
    }
    @PostMapping("/{id}/{action}")
    public Object change(@PathVariable Long id, @PathVariable QueueWorkflowService.Action action,
            @RequestParam Long branchId, @RequestParam(required = false) Long chairId, @RequestParam(required = false) String reason,
            @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
            HttpSession session, RedirectAttributes flash) {
        try {
            service.transition(session, id, action, chairId, reason);
            String notice = switch (action) {
                case CALL -> "Đã gọi số khám.";
                case PAUSE -> "Đã tạm dừng số khám.";
                case RESUME -> "Số khám đã trở lại hàng chờ.";
                case PRIORITIZE -> "Đã cập nhật mức ưu tiên.";
                case EMERGENCY -> "Đã chuyển số khám sang cấp cứu.";
                case START -> "Đã bắt đầu khám.";
                case DONE -> "Đã hoàn tất số khám.";
            };
            if ("XMLHttpRequest".equalsIgnoreCase(requestedWith)) {
                return org.springframework.http.ResponseEntity.ok(java.util.Map.of("success", true, "notice", notice));
            }
            flash.addFlashAttribute("notice", notice);
        } catch (ResponseStatusException ex) {
            if ("XMLHttpRequest".equalsIgnoreCase(requestedWith)) {
                return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(java.util.Map.of("success", false, "error", ex.getReason()));
            }
            flash.addFlashAttribute("error", ex.getReason());
        }
        return "redirect:/reception/queue?branchId=" + branchId;
    }
    @PostMapping("/doctors/{doctorId}/call-next")
    public String callNext(@PathVariable Long doctorId, @RequestParam Long branchId,
            HttpSession session, RedirectAttributes flash) {
        try {
            var ticket = service.callNext(session, branchId, doctorId);
            flash.addFlashAttribute("notice", "Đã gọi số " + ticket.getDisplayNumber() + ".");
        } catch (ResponseStatusException ex) {
            flash.addFlashAttribute("error", ex.getReason());
        }
        return "redirect:/reception/queue?branchId=" + branchId;
    }
    @GetMapping({"/tv", "/tv/{branchId}"})
    public String tv(@PathVariable(name = "branchId", required = false) Long branchId,
                     @RequestParam(name = "branchId", required = false) Long branchIdParam,
                     HttpSession session, Model model) {
        Long targetBranchId = branchId != null ? branchId : branchIdParam;
        if (targetBranchId == null && session != null) {
            Object sId = session.getAttribute("currentBranchId");
            if (sId instanceof Long sid) targetBranchId = sid;
        }
        if (targetBranchId == null) targetBranchId = 1L;
        model.addAttribute("branchId", targetBranchId);
        model.addAttribute("board", service.publicBoard(targetBranchId));
        model.addAttribute("queueList", service.publicQueueList(targetBranchId));
        return "reception/queue-tv";
    }
    @GetMapping({"/tv/data", "/tv/{branchId}/data"})
    @ResponseBody
    public QueueWorkflowService.PublicBoard tvData(@PathVariable(name = "branchId", required = false) Long branchId,
                                                   @RequestParam(name = "branchId", required = false) Long branchIdParam,
                                                   HttpSession session) {
        Long targetBranchId = branchId != null ? branchId : branchIdParam;
        if (targetBranchId == null && session != null) {
            Object sId = session.getAttribute("currentBranchId");
            if (sId instanceof Long sid) targetBranchId = sid;
        }
        if (targetBranchId == null) targetBranchId = 1L;
        return service.publicBoard(targetBranchId);
    }
}
