package com.cvdcms.controller;

import com.cvdcms.DTO.AuditLogDetailDTO;
import com.cvdcms.DTO.AuditLogFilterDTO;
import com.cvdcms.DTO.AuditLogResponseDTO;
import com.cvdcms.DTO.AuditSummaryStatsDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.User;
import jakarta.servlet.http.HttpSession;
import com.cvdcms.service.SystemAuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Controller
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final SystemAuditLogService auditService;

    @GetMapping
    public String getAuditLogPage(@ModelAttribute("filter") AuditLogFilterDTO filter, HttpSession session, Model model) {
        User user = (User) session.getAttribute("loggedInUser");
        if (user == null) {
            return "redirect:/login";
        }

        Page<AuditLogResponseDTO> logsPage = auditService.getAuditLogsPage(filter);
        AuditSummaryStatsDTO stats = auditService.getSummaryStats();
        List<Branch> branches = auditService.getAllBranches();
        List<String> actions = auditService.getDistinctActions();
        List<String> entityTables = auditService.getDistinctEntityTables();

        int pageSize = filter.getSize();
        if (pageSize != 10 && pageSize != 25 && pageSize != 50 && pageSize != 100) {
            pageSize = 10;
            filter.setSize(10);
        }
        int from = logsPage.isEmpty() ? 0 : (logsPage.getNumber() * pageSize + 1);
        int to = logsPage.isEmpty() ? 0 : (int) Math.min((long) (logsPage.getNumber() + 1) * pageSize, logsPage.getTotalElements());

        model.addAttribute("fromItem", from);
        model.addAttribute("toItem", to);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("currentPage", logsPage.getNumber());
        model.addAttribute("totalPages", logsPage.getTotalPages());
        model.addAttribute("totalItems", logsPage.getTotalElements());
        model.addAttribute("logsPage", logsPage);
        model.addAttribute("logs", logsPage.getContent());
        model.addAttribute("filter", filter);
        model.addAttribute("stats", stats);
        model.addAttribute("branches", branches);
        model.addAttribute("actions", actions);
        model.addAttribute("entityTables", entityTables);
        model.addAttribute("entityTableMap", auditService.getEntityTableLabels());
        model.addAttribute("activeMenu", "audit");

        return "admin/audit";
    }

    @GetMapping("/detail/{logId}")
    @ResponseBody
    public ResponseEntity<AuditLogDetailDTO> getAuditDetail(@PathVariable Long logId, HttpSession session) {
        User user = (User) session.getAttribute("loggedInUser");
        if (user == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }
        try {
            AuditLogDetailDTO detail = auditService.getAuditLogDetail(logId);
            return ResponseEntity.ok(detail);
        } catch (IllegalArgumentException e) {
            log.warn("Không tìm thấy log: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Lỗi lấy chi tiết audit log: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
