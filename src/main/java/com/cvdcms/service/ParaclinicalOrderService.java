package com.cvdcms.service;

import com.cvdcms.DTO.ParaclinicalBackfillReportDTO;
import com.cvdcms.DTO.ParaclinicalOrderCreateDTO;
import com.cvdcms.DTO.ParaclinicalOrderItemResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;

import java.util.List;

public interface ParaclinicalOrderService {
    List<ParaclinicalOrderItemResponseDTO> getOrdersByVisit(Long visitId);
    ParaclinicalOrderItemResponseDTO createOrder(Long visitId, ParaclinicalOrderCreateDTO dto, Long currentDoctorId);
    ParaclinicalOrderItemResponseDTO updateOrder(Long orderItemId, ParaclinicalOrderCreateDTO dto);
    ParaclinicalOrderItemResponseDTO updateStatus(Long orderItemId, ParaclinicalOrderItemStatus newStatus, String resultSummary);
    void deleteOrder(Long orderItemId);
    List<ServiceDTO> getParaclinicalServices();
    void syncParaclinicalOrdersToPerformedServices(Long visitId);

    default boolean isExternalOrCrossVisitUpload(Long orderItemId, Long activeVisitId) {
        return false;
    }

    default ParaclinicalOrderItemResponseDTO recordExternalLabResult(
            Long orderItemId,
            Long activeVisitId,
            String labPartnerName,
            String resultSummary,
            String fileUrl,
            String fileName,
            boolean isPdf) {
        throw new UnsupportedOperationException("Chưa hỗ trợ ghi nhận kết quả xét nghiệm ngoài viện.");
    }

    default ParaclinicalBackfillReportDTO reconcileCompletedOrdersMissingPerformedService() {
        return reconcileCompletedOrdersMissingPerformedService(true);
    }

    default ParaclinicalBackfillReportDTO reconcileCompletedOrdersMissingPerformedService(boolean dryRun) {
        ParaclinicalBackfillReportDTO report = new ParaclinicalBackfillReportDTO();
        report.setDryRun(dryRun);
        return report;
    }
}
