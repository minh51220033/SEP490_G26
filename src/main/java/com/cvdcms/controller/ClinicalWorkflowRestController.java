package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.User;
import com.cvdcms.service.ClinicalWorkflowService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/doctor/workflows")
@RequiredArgsConstructor
public class ClinicalWorkflowRestController {

    private final ClinicalWorkflowService workflowService;

    @GetMapping
    public ResponseEntity<List<WorkflowTemplateResponseDTO>> getAllWorkflows() {
        return ResponseEntity.ok(workflowService.getAllTemplates());
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowTemplateResponseDTO> getWorkflowById(@PathVariable Long id) {
        return ResponseEntity.ok(workflowService.getTemplateById(id));
    }

    @GetMapping("/by-service/{serviceId}")
    public ResponseEntity<WorkflowTemplateResponseDTO> getWorkflowByService(@PathVariable Long serviceId) {
        WorkflowTemplateResponseDTO dto = workflowService.getTemplateByServiceId(serviceId);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    @PostMapping
    public ResponseEntity<WorkflowTemplateResponseDTO> saveWorkflow(
            @RequestBody WorkflowTemplateRequestDTO requestDTO,
            HttpSession session) {
        User user = (User) session.getAttribute("loggedInUser");
        Long userId = user != null ? user.getUserId() : 1L;
        return ResponseEntity.ok(workflowService.saveTemplate(requestDTO, userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable Long id) {
        workflowService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ai-extract")
    public ResponseEntity<WorkflowTemplateResponseDTO> extractFromAi(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        String imageBase64 = body.get("imageBase64");
        String mimeType = body.get("mimeType");

        WorkflowTemplateResponseDTO result = workflowService.parseWorkflowAi(text, imageBase64, mimeType);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/execution/{visitId}/{serviceId}")
    public ResponseEntity<WorkflowExecutionDTO> getExecution(
            @PathVariable Long visitId,
            @PathVariable Long serviceId,
            HttpSession session) {
        User user = (User) session.getAttribute("loggedInUser");
        Long staffId = user != null ? user.getUserId() : 1L;

        WorkflowExecutionDTO dto = workflowService.getOrCreateExecution(visitId, serviceId, staffId);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/execution/step")
    public ResponseEntity<WorkflowStepDTO> updateStepExecution(
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Long executionId = Long.valueOf(body.get("executionId").toString());
        Long stepId = Long.valueOf(body.get("stepId").toString());
        String status = body.get("status").toString();
        String note = body.containsKey("note") && body.get("note") != null ? body.get("note").toString() : null;

        User user = (User) session.getAttribute("loggedInUser");
        Long staffId = user != null ? user.getUserId() : 1L;

        WorkflowStepDTO updated = workflowService.updateStepExecution(executionId, stepId, status, note, staffId);
        return ResponseEntity.ok(updated);
    }
}
