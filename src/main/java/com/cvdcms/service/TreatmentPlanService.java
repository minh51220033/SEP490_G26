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

    TreatmentPlanResponseDTO updatePlanStatus(Long planId, String status);

    TreatmentPlanResponseDTO updateStageStatus(Long stageId, String status);

    TreatmentPlanResponseDTO updateItemStatus(Long planItemId, String status);

    void deletePlan(Long planId);

    AiParsePlanResponseDTO parsePlanWithAi(AiParsePlanRequestDTO request);
}
