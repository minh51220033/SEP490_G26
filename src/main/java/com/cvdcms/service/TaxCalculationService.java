package com.cvdcms.service;

import com.cvdcms.DTO.PitCalculationResult;
import com.cvdcms.entity.BranchTaxConfig;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.TaxPolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface TaxCalculationService {

    PitCalculationResult calculatePit(
            BigDecimal grossSalary,
            BigDecimal nonTaxableIncome,
            StaffProfile staff,
            LocalDate effectiveDate
    );

    PitCalculationResult calculatePitWithPolicy(
            BigDecimal grossSalary,
            BigDecimal nonTaxableIncome,
            StaffProfile staff,
            TaxPolicy policy
    );

    TaxPolicy getActiveTaxPolicy(LocalDate effectiveDate);

    default TaxPolicy getActiveTaxPolicyStrict(LocalDate effectiveDate) {
        return getActiveTaxPolicy(effectiveDate);
    }

    Optional<BranchTaxConfig> getBranchTaxConfig(Long branchId, LocalDate effectiveDate);

    default Optional<BranchTaxConfig> getBranchTaxConfigStrict(Long branchId, LocalDate effectiveDate) {
        return getBranchTaxConfig(branchId, effectiveDate);
    }

    default PitCalculationResult calculatePitStrict(
            BigDecimal grossSalary,
            BigDecimal nonTaxableIncome,
            StaffProfile staff,
            LocalDate effectiveDate
    ) {
        return calculatePit(grossSalary, nonTaxableIncome, staff, effectiveDate);
    }
}

