package com.cvdcms.service;

import com.cvdcms.DTO.*;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface ClinicalWorkflowService {

    List<WorkflowTemplateResponseDTO> getAllTemplates();

    WorkflowTemplateResponseDTO getTemplateById(Long templateId);

    WorkflowTemplateResponseDTO getTemplateByServiceId(Long serviceId);

    WorkflowTemplateResponseDTO saveTemplate(WorkflowTemplateRequestDTO dto, Long userId);

    void deleteTemplate(Long templateId);


    /** FT43: lấy quy trình đã khởi tạo cho lượt khám + dịch vụ, hoặc khởi tạo mới nếu dịch vụ có mẫu đang hoạt động. userId là tài khoản đăng nhập. */
    WorkflowExecutionDTO getOrCreateExecution(Long visitId, Long serviceId, Long userId);

    /** FT43 / SCR-43.1: tổng quan quy trình của lượt khám và các dịch vụ còn có thể khởi tạo quy trình. */
    WorkflowVisitOverviewDTO getVisitOverview(Long visitId);

    /** FT43: khởi tạo quy trình cho lượt khám theo mẫu đang áp dụng; báo lỗi nếu không thể khởi tạo. */
    WorkflowExecutionDTO startExecution(Long visitId, Long serviceId, Long userId);

    /** Khởi tạo (hoặc lấy lại) quy trình gắn với mục Kế hoạch điều trị. */
    WorkflowExecutionDTO startExecution(Long visitId, Long serviceId, Long planItemId, Long userId);

    /** Khởi tạo (hoặc lấy lại) quy trình gắn với giai đoạn/mục Kế hoạch điều trị. */
    WorkflowExecutionDTO startExecution(Long visitId, Long serviceId, Long planItemId, Long planStageId, Long userId);

    /** Tầng 1 Combo VTTech: Quản lý & áp dụng Combo dịch vụ mẫu vào lượt khám. */
    List<ServiceComboDTO> getAllCombos(boolean activeOnly);

    ServiceComboDTO saveCombo(Map<String, Object> payload, Long userId);

    void deleteCombo(Long comboId);

    Map<String, Object> startComboExecution(Long visitId, Long comboId, Long userId);

    /** Lấy danh sách trường/cột mẫu mặc định theo 5 khung thao tác. */
    Map<String, List<WorkflowStepDTO.CustomFieldDTO>> getAllFrameCustomFields();

    /** Cập nhật danh sách trường/cột mẫu mặc định cho 1 khung thao tác. */
    List<WorkflowStepDTO.CustomFieldDTO> saveFrameCustomFields(String frameKey, List<WorkflowStepDTO.CustomFieldDTO> fields);

    /** FT43 / SCR-43.2, 43.3: START, COMPLETE, PAUSE, RESUME, SKIP trên bước hiện tại của quy trình. */
    WorkflowExecutionDTO performStepAction(Long executionId, Long stepId, String action, String note,
                                           boolean criteriaConfirmed, Long userId);

    /** Phiên bản mở rộng: lưu kết quả chi tiết từng khung (resultData), SAVE_DRAFT và hỗ trợ khám nhiều buổi (activeVisitId). */
    WorkflowExecutionDTO performStepAction(Long executionId, Long stepId, String action, String note,
                                           boolean criteriaConfirmed, Map<String, Object> resultData,
                                           Long activeVisitId, Long userId);

    /** Thêm bước phát sinh riêng cho 1 ca điều trị (không sửa mẫu chuẩn của Admin). */
    WorkflowExecutionDTO addAdhocStep(Long executionId, String stepName, String frameKey, String stepType,
                                      String insertPosition, Long afterStepId, String completionCriteria,
                                      String stepDescription, Long activeVisitId, Long userId);

    /** Xóa bước phát sinh khi chưa bắt đầu / chưa lưu dữ liệu. */
    WorkflowExecutionDTO removeAdhocStep(Long executionId, Long stepId, Long activeVisitId, Long userId);

    /** Xóa quy trình mới khởi tạo khi chưa thực hiện bất kỳ bước nào (tránh chọn nhầm dịch vụ). */
    void deleteExecution(Long executionId, Long activeVisitId, Long userId);

    /** FT43 / SCR-43.4: lịch sử thực hiện quy trình (mới nhất trước). */
    List<WorkflowHistoryEntryDTO> getExecutionHistory(Long executionId);

    WorkflowExecutionDTO getExecutionDetail(Long executionId);

    /** FT43: bảng tiến độ quy trình theo ngày; branchIds = null nghĩa là mọi chi nhánh. */
    List<WorkflowExecutionDTO> getProgressBoard(Collection<Long> branchIds, LocalDate day);

    WorkflowStepDTO updateStepExecution(Long executionId, Long stepId, String status, String note, Long staffId);
}
