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

import java.util.List;

@Slf4j
@Controller
@RequestMapping("/doctor/workflows")
@RequiredArgsConstructor
public class ClinicalWorkflowController {

    private final ClinicalWorkflowService workflowService;
    private final PerformedServiceService performedServiceService;

    @GetMapping
    public String getWorkflowsPage(Model model) {
        List<WorkflowTemplateResponseDTO> workflows = workflowService.getAllTemplates();
        List<ServiceDTO> activeServices = performedServiceService.getAllActiveServices();

        model.addAttribute("workflows", workflows);
        model.addAttribute("activeServices", activeServices);
        model.addAttribute("activeMenu", "workflows");
        return "doctor/workflows";
    }
}
