package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TreatmentPlanInstallmentDTO {
    private Long installmentId;
    private Long planId;
    private String planCode;
    private String planName;
    private Long patientId;
    private String patientCode;
    private String patientName;
    private String patientPhone;
    private Long branchId;
    private String branchName;
    private Integer installmentOrder;
    private String phaseName;
    private String expectedDate;
    private String expectedDateRaw;
    private BigDecimal expectedAmount;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private String status;
    private String statusDisplayName;
    private String statusBadgeClass;
    private String notes;
    private Boolean reminded;
    private String remindedAt;
}

