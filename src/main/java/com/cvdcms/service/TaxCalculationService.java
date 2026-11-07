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

    Optional<BranchTaxConfig> getBranchTaxConfig(Long branchId, LocalDate effectiveDate);
}

