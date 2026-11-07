package com.cvdcms.patient;

import com.cvdcms.entity.Visit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record VisitView(Long id, String code, LocalDateTime checkInAt,
                        String branchName, String doctorName, String status,
                        String initialSymptoms, String notes) {
    public static VisitView from(Visit visit) {
        return new VisitView(visit.getVisitId(), visit.getVisitCode(), visit.getCheckInAt(),
                visit.getBranch().getBranchName(),
                visit.getPrimaryDoctor() == null ? null : visit.getPrimaryDoctor().getFullName(),
                visit.getStatus().name(), visit.getInitialSymptoms(), visit.getNotes());
    }

    public boolean isCompleted() {
        return "COMPLETED".equalsIgnoreCase(status);
    }

    public boolean isInProgress() {
        return "IN_EXAM".equalsIgnoreCase(status) || "IN_TREATMENT".equalsIgnoreCase(status);
    }

    public record VitalSignsView(Integer pulse, String bloodPressure, Double temperature,
                                 Integer respiratoryRate, Double weight) {}

    public record DiagnosisItemView(String type, String icd10Code, String diseaseNameVi,
                                    String diagnosisText, String toothNumbers, String notes) {}

    public record ServiceItemView(String serviceCode, String serviceName, String toothNumbers,
                                  BigDecimal quantity, BigDecimal unitPrice, BigDecimal finalPrice,
                                  String doctorName, String status, LocalDateTime completedAt,
                                  String clinicalNotes) {}

    public record PrescriptionItemView(String drugName, String activeIngredient, String dosage,
                                       BigDecimal quantity, String unit, String usageInstruction,
                                       Integer durationDays) {}

    public record PrescriptionView(String prescriptionCode, String diagnosisText, String adviceNotes,
                                   LocalDate followupDate, List<PrescriptionItemView> items) {}

    public record ClinicalPhotoItemView(Long photoId, String stage, String category,
                                        String imageUrl, String caption, LocalDateTime capturedAt) {}

    public record ToothInterventionView(String toothNumber, List<String> procedures,
                                        List<String> diagnoses, String summary) {}
}
