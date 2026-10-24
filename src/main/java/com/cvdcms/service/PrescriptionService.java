package com.cvdcms.service;

import com.cvdcms.DTO.MedicationDTO;
import com.cvdcms.DTO.PrescriptionRequestDTO;
import com.cvdcms.DTO.PrescriptionResponseDTO;

import java.util.List;

public interface PrescriptionService {

    List<MedicationDTO> getActiveMedications();

    List<PrescriptionResponseDTO> getPrescriptionsByVisit(Long visitId);

    PrescriptionResponseDTO getPrescriptionById(Long prescriptionId);

    PrescriptionResponseDTO createPrescription(PrescriptionRequestDTO request);

    void deletePrescription(Long prescriptionId);
}
