package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * FT43: Tổng quan quy trình khám của một lượt khám — danh sách quy trình đã khởi tạo
 * và các dịch vụ còn có thể khởi tạo quy trình.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowVisitOverviewDTO {
    private Long visitId;
    private Boolean locked;
    private String lockedReason;

    @Builder.Default
    private List<WorkflowExecutionDTO> executions = new ArrayList<>();

    @Builder.Default
    private List<ServiceOption> availableServices = new ArrayList<>();

    @Builder.Default
    private List<ServiceComboDTO> availableCombos = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceOption {
        private Long serviceId;
        private String serviceCode;
        private String serviceName;
        private String templateName;
        private Integer stepCount;
        private Integer estimatedDurationMinutes;
        private Boolean allowSameVisit;
        private Boolean isParaclinical;
        /** true nếu dịch vụ đã nằm trong dịch vụ thực hiện của lượt khám (FT16) hoặc Kế hoạch điều trị. */
        private Boolean suggested;
        private Long planItemId;
        private Long planStageId;
        private String planCode;
        private String planName;
        private String stepName;
        private String toothNumbers;
        private Boolean canPerform;
        private String capabilityReason;
        private Boolean requiresHandover;
    }
}