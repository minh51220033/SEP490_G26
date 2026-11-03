package com.cvdcms.controller;

import com.cvdcms.reception.QueueWorkflowService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PublicQueueTvController {
    private final QueueWorkflowService queueService;

    public PublicQueueTvController(QueueWorkflowService queueService) {
        this.queueService = queueService;
    }

    @GetMapping("/tv-display")
    public String tvDisplay(@RequestParam(required = false, defaultValue = "1") Long branchId, Model model) {
        model.addAttribute("branchId", branchId);
        model.addAttribute("board", queueService.publicBoard(branchId));
        return "reception/queue-tv";
    }
}
