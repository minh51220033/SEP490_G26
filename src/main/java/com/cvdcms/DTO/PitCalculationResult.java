package com.cvdcms.DTO;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
public class PitCalculationResult {
    private BigDecimal grossSalary;
    private BigDecimal nonTaxableIncome;
    private BigDecimal insuranceEmployeeDeduction;
    private BigDecimal insuranceEmployerAmount;
    private BigDecimal personalDeductionAmount;
    private BigDecimal dependentDeductionAmount;
    private BigDecimal taxableIncome;
    private BigDecimal assessableIncome;
    private BigDecimal pitAmount;
    private String taxPolicyCode;
    private String taxCalculationDetail;
    private String calculationMethod;
}

