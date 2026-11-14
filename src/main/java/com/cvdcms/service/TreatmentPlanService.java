package com.cvdcms.service;

import com.cvdcms.DTO.AiParsePlanRequestDTO;
import com.cvdcms.DTO.AiParsePlanResponseDTO;
import com.cvdcms.DTO.TreatmentPlanRequestDTO;
import com.cvdcms.DTO.TreatmentPlanResponseDTO;

import java.util.List;

public interface TreatmentPlanService {

    List<TreatmentPlanResponseDTO> getPlansByPatientId(Long patientId);

    TreatmentPlanResponseDTO getPlanById(Long planId);

    TreatmentPlanResponseDTO createPlan(TreatmentPlanRequestDTO request);

    TreatmentPlanResponseDTO updatePlan(Long planId, TreatmentPlanRequestDTO request);

    TreatmentPlanResponseDTO updatePlanStatus(Long planId, String status);

    TreatmentPlanResponseDTO updateStageStatus(Long stageId, String status);

    TreatmentPlanResponseDTO updateItemStatus(Long planItemId, String status);

    TreatmentPlanResponseDTO syncPlanProgressAndStatus(Long planId);

    void deletePlan(Long planId);

    TreatmentPlanResponseDTO handoverToAccounting(Long planId);

    AiParsePlanResponseDTO parsePlanWithAi(AiParsePlanRequestDTO request);

    List<com.cvdcms.DTO.TreatmentPlanInstallmentDTO> getPlanInstallments(Long planId);

    TreatmentPlanResponseDTO saveInstallments(Long planId, List<com.cvdcms.DTO.TreatmentPlanInstallmentDTO> installmentDTOs);

    TreatmentPlanResponseDTO recordInstallmentPayment(Long planId, java.math.BigDecimal amount, Long invoiceId);

    com.cvdcms.DTO.TreatmentPlanInstallmentDTO markReminderSent(Long installmentId, String notes);

    org.springframework.data.domain.Page<com.cvdcms.DTO.TreatmentPlanInstallmentDTO> searchUpcomingInstallments(
            Long branchId, java.time.LocalDate startDate, java.time.LocalDate endDate, String status, String keyword, org.springframework.data.domain.Pageable pageable);
}
