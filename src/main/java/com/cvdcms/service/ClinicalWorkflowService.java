package com.cvdcms.service;

import com.cvdcms.DTO.*;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ClinicalWorkflowService {

    List<WorkflowTemplateResponseDTO> getAllTemplates();

    WorkflowTemplateResponseDTO getTemplateById(Long templateId);

    WorkflowTemplateResponseDTO getTemplateByServiceId(Long serviceId);

    WorkflowTemplateResponseDTO saveTemplate(WorkflowTemplateRequestDTO dto, Long userId);

    void deleteTemplate(Long templateId);

    WorkflowTemplateResponseDTO parseWorkflowAi(String text, String imageBase64, String mimeType);

    /** FT43: lấy quy trình đã khởi tạo cho lượt khám + dịch vụ, hoặc khởi tạo mới nếu dịch vụ có mẫu đang hoạt động. userId là tài khoản đăng nhập. */
    WorkflowExecutionDTO getOrCreateExecution(Long visitId, Long serviceId, Long userId);

    /** FT43 / SCR-43.1: tổng quan quy trình của lượt khám và các dịch vụ còn có thể khởi tạo quy trình. */
    WorkflowVisitOverviewDTO getVisitOverview(Long visitId);

    /** FT43: khởi tạo quy trình cho lượt khám theo mẫu đang áp dụng; báo lỗi nếu không thể khởi tạo. */
    WorkflowExecutionDTO startExecution(Long visitId, Long serviceId, Long userId);

    /** FT43 / SCR-43.2, 43.3: START, COMPLETE, PAUSE, RESUME, SKIP trên bước hiện tại của quy trình. */
    WorkflowExecutionDTO performStepAction(Long executionId, Long stepId, String action, String note,
                                           boolean criteriaConfirmed, Long userId);

    /** FT43 / SCR-43.4: lịch sử thực hiện quy trình (mới nhất trước). */
    List<WorkflowHistoryEntryDTO> getExecutionHistory(Long executionId);

    WorkflowExecutionDTO getExecutionDetail(Long executionId);

    /** FT43: bảng tiến độ quy trình theo ngày; branchIds = null nghĩa là mọi chi nhánh. */
    List<WorkflowExecutionDTO> getProgressBoard(Collection<Long> branchIds, LocalDate day);

    WorkflowStepDTO updateStepExecution(Long executionId, Long stepId, String status, String note, Long staffId);
}
