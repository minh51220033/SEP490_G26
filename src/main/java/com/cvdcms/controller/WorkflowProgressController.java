package com.cvdcms.controller;

import com.cvdcms.DTO.WorkflowExecutionDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.reception.ReceptionAccess;
import com.cvdcms.service.ClinicalWorkflowService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FT43 (Lễ tân): bảng theo dõi tiến độ thực hiện quy trình khám của các lượt khám trong ngày.
 * Lễ tân chỉ xem tiến độ để điều phối bệnh nhân sang phòng/hàng chờ tiếp theo; việc cập nhật
 * bước do bác sĩ thực hiện trong màn khám.
 */
@Controller
@RequiredArgsConstructor
public class WorkflowProgressController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ClinicalWorkflowService workflowService;
    private final ReceptionAccess receptionAccess;
    private final BranchRepository branchRepository;

    @GetMapping("/reception/workflow-progress")
    public String page(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long branchId,
            HttpSession session,
            Model model) {
        ReceptionAccess.Scope scope = receptionAccess.require(session);
        LocalDate day = date != null ? date : LocalDate.now(ZONE);

        List<Branch> branches = branchRepository.findAll().stream()
                .filter(b -> scope.permits(b.getBranchId()))
                .collect(Collectors.toList());

        Collection<Long> branchIds;
        if (branchId != null) {
            if (!scope.permits(branchId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem chi nhánh này.");
            }
            branchIds = List.of(branchId);
        } else {
            branchIds = scope.admin() ? null : scope.branchIds();
        }

        List<WorkflowExecutionDTO> executions = workflowService.getProgressBoard(branchIds, day);

        model.addAttribute("executions", executions);
        model.addAttribute("branches", branches);
        model.addAttribute("selectedBranchId", branchId);
        model.addAttribute("selectedDate", day);
        model.addAttribute("totalCount", executions.size());
        model.addAttribute("inProgressCount", count(executions, "IN_PROGRESS"));
        model.addAttribute("pausedCount", count(executions, "PAUSED"));
        model.addAttribute("completedCount", count(executions, "COMPLETED"));
        model.addAttribute("activeMenu", "workflow-progress");
        return "reception/workflow-progress";
    }

    /** SCR-43.1 / 43.4 (chỉ xem): chi tiết các bước và lịch sử của một quy trình. */
    @GetMapping("/api/reception/workflow-progress/{executionId}")
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long executionId, HttpSession session) {
        ReceptionAccess.Scope scope = receptionAccess.require(session);
        WorkflowExecutionDTO execution = workflowService.getExecutionDetail(executionId);
        if (!scope.permits(execution.getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem quy trình của chi nhánh này.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("execution", execution);
        body.put("history", workflowService.getExecutionHistory(executionId));
        return ResponseEntity.ok(body);
    }

    private long count(List<WorkflowExecutionDTO> list, String status) {
        return list.stream().filter(e -> status.equals(e.getStatus())).count();
    }
}

