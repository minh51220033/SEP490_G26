package com.cvdcms.controller;

import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.DTO.WorkflowTemplateResponseDTO;
import com.cvdcms.service.ClinicalWorkflowService;
import com.cvdcms.service.PerformedServiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Slf4j
@Controller
@RequestMapping("/doctor/workflows")
@RequiredArgsConstructor
public class ClinicalWorkflowController {

    private final ClinicalWorkflowService workflowService;
    private final PerformedServiceService performedServiceService;

    @GetMapping
    public String getWorkflowsPage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) String status,
            Model model) {
        if (size != 10 && size != 25 && size != 50 && size != 100) {
            size = 10;
        }
        List<WorkflowTemplateResponseDTO> allTemplates = workflowService.getAllTemplates();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            allTemplates = allTemplates.stream()
                    .filter(w -> (w.getTemplateName() != null && w.getTemplateName().toLowerCase().contains(kw)) ||
                                 (w.getTemplateCode() != null && w.getTemplateCode().toLowerCase().contains(kw)) ||
                                 (w.getDescription() != null && w.getDescription().toLowerCase().contains(kw)))
                    .toList();
        }
        if (serviceId != null) {
            allTemplates = allTemplates.stream()
                    .filter(w -> serviceId.equals(w.getServiceId()))
                    .toList();
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            allTemplates = allTemplates.stream()
                    .filter(w -> w.getStatus() != null && status.equalsIgnoreCase(w.getStatus()))
                    .toList();
        }

        long totalItems = allTemplates.size();
        int totalPages = totalItems == 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        int currentPage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        int fromIndex = totalItems == 0 ? 0 : currentPage * size;
        int toIndex = (int) Math.min(fromIndex + size, totalItems);
        List<WorkflowTemplateResponseDTO> pagedWorkflows = totalItems == 0 ? List.of() : allTemplates.subList(fromIndex, toIndex);

        long fromItem = totalItems == 0 ? 0 : ((long) currentPage * size) + 1;
        long toItem = toIndex;

        List<ServiceDTO> activeServices = performedServiceService.getAllActiveServices();

        model.addAttribute("workflows", pagedWorkflows);
        model.addAttribute("allWorkflows", workflowService.getAllTemplates());
        model.addAttribute("items", pagedWorkflows);
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedServiceId", serviceId);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("activeServices", activeServices);
        model.addAttribute("activeMenu", "workflows");
        return "doctor/workflows";
    }
}
