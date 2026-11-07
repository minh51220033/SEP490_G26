package com.cvdcms.service.impl;

import com.cvdcms.DTO.PitCalculationResult;
import com.cvdcms.entity.BranchTaxConfig;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.TaxPolicy;
import com.cvdcms.entity.enums.StaffContractType;
import com.cvdcms.repository.BranchTaxConfigRepository;
import com.cvdcms.repository.TaxPolicyRepository;
import com.cvdcms.service.TaxCalculationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class TaxCalculationServiceImpl implements TaxCalculationService {

    private final TaxPolicyRepository taxPolicyRepository;
    private final BranchTaxConfigRepository branchTaxConfigRepository;

    private static final BigDecimal THRESHOLD_CASUAL_WITHHOLDING = BigDecimal.valueOf(2000000); // 2 triệu VNĐ/lần

    // 7 Bậc thuế TNCN theo Điều 22 Luật Thuế TNCN
    private static final BigDecimal B1_MAX = BigDecimal.valueOf(5000000);
    private static final BigDecimal B2_MAX = BigDecimal.valueOf(10000000);
    private static final BigDecimal B3_MAX = BigDecimal.valueOf(18000000);
    private static final BigDecimal B4_MAX = BigDecimal.valueOf(32000000);
    private static final BigDecimal B5_MAX = BigDecimal.valueOf(52000000);
    private static final BigDecimal B6_MAX = BigDecimal.valueOf(80000000);

    public TaxCalculationServiceImpl() {
        this.taxPolicyRepository = null;
        this.branchTaxConfigRepository = null;
    }

    public TaxCalculationServiceImpl(
            TaxPolicyRepository taxPolicyRepository,
            BranchTaxConfigRepository branchTaxConfigRepository
    ) {
        this.taxPolicyRepository = taxPolicyRepository;
        this.branchTaxConfigRepository = branchTaxConfigRepository;
    }

    @Override
    public TaxPolicy getActiveTaxPolicy(LocalDate effectiveDate) {
        if (taxPolicyRepository == null) {
            return createFallbackPolicy();
        }
        LocalDate target = effectiveDate != null ? effectiveDate : LocalDate.now();
        return taxPolicyRepository.findActivePolicyAtDate(target)
                .orElseGet(this::createFallbackPolicy);
    }

    private TaxPolicy createFallbackPolicy() {
        TaxPolicy p = new TaxPolicy();
        p.setPolicyCode("PIT_VN_2020");
        p.setPolicyName("Chính sách thuế TNCN & Bảo hiểm theo NQ 954/2020/UBTVQH14");
        p.setEffectiveFrom(LocalDate.of(2020, 7, 1));
        p.setPersonalRelief(BigDecimal.valueOf(11000000));
        p.setDependentRelief(BigDecimal.valueOf(4400000));
        p.setCasualTaxRate(BigDecimal.valueOf(10.0));
        p.setNonResidentTaxRate(BigDecimal.valueOf(20.0));
        p.setInsuranceEmployeeRate(BigDecimal.valueOf(10.5));
        p.setInsuranceEmployerRate(BigDecimal.valueOf(21.5));
        p.setLegalReference("Nghị quyết 954/2020/UBTVQH14, Điều 22 Luật Thuế TNCN 04/2007/QH12, TT 111/2013/TT-BTC");
        p.setIsActive(true);
        return p;
    }

    @Override
    public Optional<BranchTaxConfig> getBranchTaxConfig(Long branchId, LocalDate effectiveDate) {
        if (branchId == null || branchTaxConfigRepository == null) {
            return Optional.empty();
        }
        LocalDate target = effectiveDate != null ? effectiveDate : LocalDate.now();
        return branchTaxConfigRepository.findActiveConfigAtDate(branchId, target);
    }

    @Override
    public PitCalculationResult calculatePit(
            BigDecimal grossSalary,
            BigDecimal nonTaxableIncome,
            StaffProfile staff,
            LocalDate effectiveDate
    ) {
        TaxPolicy policy = getActiveTaxPolicy(effectiveDate);
        return calculatePitWithPolicy(grossSalary, nonTaxableIncome, staff, policy);
    }

    @Override
    public PitCalculationResult calculatePitWithPolicy(
            BigDecimal grossSalary,
            BigDecimal nonTaxableIncome,
            StaffProfile staff,
            TaxPolicy policy
    ) {
        if (policy == null) {
            policy = createFallbackPolicy();
        }

        BigDecimal gross = grossSalary != null ? grossSalary : BigDecimal.ZERO;
        BigDecimal nonTaxable = nonTaxableIncome != null ? nonTaxableIncome : BigDecimal.ZERO;
        BigDecimal taxableIncome = gross.subtract(nonTaxable).max(BigDecimal.ZERO);

        // Trường hợp nhân sự chưa có thông tin hợp đồng (legacy mock hoặc chưa khai báo thuế)
        if (staff == null || staff.getContractType() == null) {
            return PitCalculationResult.builder()
                    .grossSalary(gross)
                    .nonTaxableIncome(nonTaxable)
                    .insuranceEmployeeDeduction(BigDecimal.ZERO)
                    .insuranceEmployerAmount(BigDecimal.ZERO)
                    .personalDeductionAmount(BigDecimal.ZERO)
                    .dependentDeductionAmount(BigDecimal.ZERO)
                    .taxableIncome(taxableIncome)
                    .assessableIncome(taxableIncome)
                    .pitAmount(BigDecimal.ZERO)
                    .taxPolicyCode(policy.getPolicyCode())
                    .taxCalculationDetail("Chưa thiết lập loại hợp đồng lao động trên hồ sơ nhân sự. Tạm thời không khấu trừ thuế.")
                    .build();
        }

        boolean isResident = staff.getIsTaxResident() == null || Boolean.TRUE.equals(staff.getIsTaxResident());
        StaffContractType contractType = staff.getContractType();
        boolean hasPersonalDeduction = staff.getHasPersonalDeduction() == null || Boolean.TRUE.equals(staff.getHasPersonalDeduction());
        int dependentCount = staff.getDependentCount() != null ? Math.max(0, staff.getDependentCount()) : 0;
        boolean hasCommitment = Boolean.TRUE.equals(staff.getHasTaxCommitment());

        // 1. Tính bảo hiểm bắt buộc người lao động và đơn vị đóng (tính trên insuranceSalary nếu có, hoặc baseSalary nếu là HĐ chính thức)
        BigDecimal insSalary = staff.getInsuranceSalary() != null ? staff.getInsuranceSalary() : BigDecimal.ZERO;
        BigDecimal insRateEmployee = policy.getInsuranceEmployeeRate() != null ? policy.getInsuranceEmployeeRate() : BigDecimal.valueOf(10.5);
        BigDecimal insRateEmployer = policy.getInsuranceEmployerRate() != null ? policy.getInsuranceEmployerRate() : BigDecimal.valueOf(21.5);

        BigDecimal insuranceEmployee = insSalary.multiply(insRateEmployee).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        BigDecimal insuranceEmployer = insSalary.multiply(insRateEmployer).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);

        // Phân nhánh tính thuế TNCN theo đối tượng
        if (!isResident || contractType == StaffContractType.NON_RESIDENT) {
            // Cá nhân không cư trú: Khấu trừ 20% trên toàn bộ thu nhập chịu thuế (Điều 18 TT 111/2013/TT-BTC)
            BigDecimal nonResRate = policy.getNonResidentTaxRate() != null ? policy.getNonResidentTaxRate() : BigDecimal.valueOf(20.0);
            BigDecimal pit = taxableIncome.multiply(nonResRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            String detail = String.format("Khấu trừ thuế TNCN không cư trú: %s%% trên TNCT %s đ = %s đ (Điều 18 Thông tư 111/2013/TT-BTC)",
                    nonResRate.stripTrailingZeros().toPlainString(), formatVnd(taxableIncome), formatVnd(pit));

            return PitCalculationResult.builder()
                    .grossSalary(gross)
                    .nonTaxableIncome(nonTaxable)
                    .insuranceEmployeeDeduction(insuranceEmployee)
                    .insuranceEmployerAmount(insuranceEmployer)
                    .personalDeductionAmount(BigDecimal.ZERO)
                    .dependentDeductionAmount(BigDecimal.ZERO)
                    .taxableIncome(taxableIncome)
                    .assessableIncome(taxableIncome)
                    .pitAmount(pit)
                    .taxPolicyCode(policy.getPolicyCode())
                    .taxCalculationDetail(detail)
                    .build();
        }

        // Hợp đồng thử việc, thời vụ, CTV, vãng lai (< 3 tháng)
        if (contractType == StaffContractType.FULL_TIME_PROBATION
                || contractType == StaffContractType.PART_TIME_HOURLY
                || contractType == StaffContractType.COLLABORATOR) {

            if (hasCommitment) {
                // Có cam kết Mẫu 08/CK-TNCN theo Thông tư 80/2021/TT-BTC -> Tạm thời không khấu trừ 10%
                String detail = "Nhân sự có cam kết Mẫu 08/CK-TNCN (ước tính tổng thu nhập trong năm dưới mức chịu thuế). Tạm thời chưa khấu trừ 10% tại nguồn.";
                return PitCalculationResult.builder()
                        .grossSalary(gross)
                        .nonTaxableIncome(nonTaxable)
                        .insuranceEmployeeDeduction(insuranceEmployee)
                        .insuranceEmployerAmount(insuranceEmployer)
                        .personalDeductionAmount(BigDecimal.ZERO)
                        .dependentDeductionAmount(BigDecimal.ZERO)
                        .taxableIncome(taxableIncome)
                        .assessableIncome(taxableIncome)
                        .pitAmount(BigDecimal.ZERO)
                        .taxPolicyCode(policy.getPolicyCode())
                        .taxCalculationDetail(detail)
                        .build();
            }

            if (taxableIncome.compareTo(THRESHOLD_CASUAL_WITHHOLDING) >= 0) {
                BigDecimal casualRate = policy.getCasualTaxRate() != null ? policy.getCasualTaxRate() : BigDecimal.valueOf(10.0);
                BigDecimal pit = taxableIncome.multiply(casualRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                String detail = String.format("Khấu trừ 10%% tại nguồn cho HĐ dưới 3 tháng / vãng lai (chi trả từ 2,000,000 đ theo Điểm i Khoản 1 Điều 25 TT 111/2013/TT-BTC): %s đ",
                        formatVnd(pit));
                return PitCalculationResult.builder()
                        .grossSalary(gross)
                        .nonTaxableIncome(nonTaxable)
                        .insuranceEmployeeDeduction(insuranceEmployee)
                        .insuranceEmployerAmount(insuranceEmployer)
                        .personalDeductionAmount(BigDecimal.ZERO)
                        .dependentDeductionAmount(BigDecimal.ZERO)
                        .taxableIncome(taxableIncome)
                        .assessableIncome(taxableIncome)
                        .pitAmount(pit)
                        .taxPolicyCode(policy.getPolicyCode())
                        .taxCalculationDetail(detail)
                        .build();
            } else {
                String detail = "Thu nhập chi trả dưới ngưỡng khấu trừ 2,000,000 đ/lần. Chưa phát sinh thuế khấu trừ.";
                return PitCalculationResult.builder()
                        .grossSalary(gross)
                        .nonTaxableIncome(nonTaxable)
                        .insuranceEmployeeDeduction(insuranceEmployee)
                        .insuranceEmployerAmount(insuranceEmployer)
                        .personalDeductionAmount(BigDecimal.ZERO)
                        .dependentDeductionAmount(BigDecimal.ZERO)
                        .taxableIncome(taxableIncome)
                        .assessableIncome(taxableIncome)
                        .pitAmount(BigDecimal.ZERO)
                        .taxPolicyCode(policy.getPolicyCode())
                        .taxCalculationDetail(detail)
                        .build();
            }
        }

        // HỢP ĐỒNG LAO ĐỘNG TỪ 3 THÁNG TRỞ LÊN (FULL_TIME_OFFICIAL): BIỂU THUẾ LŨY TIẾN TỪNG PHẦN 7 BẬC
        BigDecimal personalRelief = hasPersonalDeduction ? policy.getPersonalRelief() : BigDecimal.ZERO;
        BigDecimal dependentReliefUnit = policy.getDependentRelief();
        BigDecimal dependentRelief = dependentReliefUnit.multiply(BigDecimal.valueOf(dependentCount));

        // Thu nhập tính thuế = Thu nhập chịu thuế - Các khoản bảo hiểm - Giảm trừ gia cảnh
        BigDecimal assessableIncome = taxableIncome
                .subtract(insuranceEmployee)
                .subtract(personalRelief)
                .subtract(dependentRelief)
                .max(BigDecimal.ZERO);

        ProgressiveTaxDetail prog = calculateProgressivePit(assessableIncome);

        return PitCalculationResult.builder()
                .grossSalary(gross)
                .nonTaxableIncome(nonTaxable)
                .insuranceEmployeeDeduction(insuranceEmployee)
                .insuranceEmployerAmount(insuranceEmployer)
                .personalDeductionAmount(personalRelief)
                .dependentDeductionAmount(dependentRelief)
                .taxableIncome(taxableIncome)
                .assessableIncome(assessableIncome)
                .pitAmount(prog.totalTax)
                .taxPolicyCode(policy.getPolicyCode())
                .taxCalculationDetail(prog.explanation)
                .build();
    }

    private ProgressiveTaxDetail calculateProgressivePit(BigDecimal assessable) {
        if (assessable.compareTo(BigDecimal.ZERO) <= 0) {
            return new ProgressiveTaxDetail(BigDecimal.ZERO, "Thu nhập tính thuế <= 0 đ. Thuế TNCN = 0 đ.");
        }

        BigDecimal rem = assessable;
        BigDecimal totalTax = BigDecimal.ZERO;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Biểu thuế lũy tiến từng phần (TNTT: %s đ):\n", formatVnd(assessable)));

        // Bậc 1: Đến 5 triệu đ (5%)
        BigDecimal b1Portion = rem.min(B1_MAX);
        if (b1Portion.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal t1 = b1Portion.multiply(BigDecimal.valueOf(0.05)).setScale(0, RoundingMode.HALF_UP);
            totalTax = totalTax.add(t1);
            sb.append(String.format("• Bậc 1 (<= 5M - 5%%): %s đ x 5%% = %s đ\n", formatVnd(b1Portion), formatVnd(t1)));
            rem = rem.subtract(b1Portion);
        }

        // Bậc 2: Trên 5 đến 10 triệu đ (10%)
        if (rem.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal b2Cap = B2_MAX.subtract(B1_MAX); // 5 triệu
            BigDecimal b2Portion = rem.min(b2Cap);
            BigDecimal t2 = b2Portion.multiply(BigDecimal.valueOf(0.10)).setScale(0, RoundingMode.HALF_UP);
            totalTax = totalTax.add(t2);
            sb.append(String.format("• Bậc 2 (5M-10M - 10%%): %s đ x 10%% = %s đ\n", formatVnd(b2Portion), formatVnd(t2)));
            rem = rem.subtract(b2Portion);
        }

        // Bậc 3: Trên 10 đến 18 triệu đ (15%)
        if (rem.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal b3Cap = B3_MAX.subtract(B2_MAX); // 8 triệu
            BigDecimal b3Portion = rem.min(b3Cap);
            BigDecimal t3 = b3Portion.multiply(BigDecimal.valueOf(0.15)).setScale(0, RoundingMode.HALF_UP);
            totalTax = totalTax.add(t3);
            sb.append(String.format("• Bậc 3 (10M-18M - 15%%): %s đ x 15%% = %s đ\n", formatVnd(b3Portion), formatVnd(t3)));
            rem = rem.subtract(b3Portion);
        }

        // Bậc 4: Trên 18 đến 32 triệu đ (20%)
        if (rem.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal b4Cap = B4_MAX.subtract(B3_MAX); // 14 triệu
            BigDecimal b4Portion = rem.min(b4Cap);
            BigDecimal t4 = b4Portion.multiply(BigDecimal.valueOf(0.20)).setScale(0, RoundingMode.HALF_UP);
            totalTax = totalTax.add(t4);
            sb.append(String.format("• Bậc 4 (18M-32M - 20%%): %s đ x 20%% = %s đ\n", formatVnd(b4Portion), formatVnd(t4)));
            rem = rem.subtract(b4Portion);
        }

        // Bậc 5: Trên 32 đến 52 triệu đ (25%)
        if (rem.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal b5Cap = B5_MAX.subtract(B4_MAX); // 20 triệu
            BigDecimal b5Portion = rem.min(b5Cap);
            BigDecimal t5 = b5Portion.multiply(BigDecimal.valueOf(0.25)).setScale(0, RoundingMode.HALF_UP);
            totalTax = totalTax.add(t5);
            sb.append(String.format("• Bậc 5 (32M-52M - 25%%): %s đ x 25%% = %s đ\n", formatVnd(b5Portion), formatVnd(t5)));
            rem = rem.subtract(b5Portion);
        }

        // Bậc 6: Trên 52 đến 80 triệu đ (30%)
        if (rem.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal b6Cap = B6_MAX.subtract(B5_MAX); // 28 triệu
            BigDecimal b6Portion = rem.min(b6Cap);
            BigDecimal t6 = b6Portion.multiply(BigDecimal.valueOf(0.30)).setScale(0, RoundingMode.HALF_UP);
            totalTax = totalTax.add(t6);
            sb.append(String.format("• Bậc 6 (52M-80M - 30%%): %s đ x 30%% = %s đ\n", formatVnd(b6Portion), formatVnd(t6)));
            rem = rem.subtract(b6Portion);
        }

        // Bậc 7: Trên 80 triệu đ (35%)
        if (rem.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal t7 = rem.multiply(BigDecimal.valueOf(0.35)).setScale(0, RoundingMode.HALF_UP);
            totalTax = totalTax.add(t7);
            sb.append(String.format("• Bậc 7 (> 80M - 35%%): %s đ x 35%% = %s đ\n", formatVnd(rem), formatVnd(t7)));
        }

        sb.append(String.format("=> Tổng thuế TNCN khấu trừ = %s đ (Điều 22 Luật Thuế TNCN & TT 111/2013/TT-BTC)", formatVnd(totalTax)));
        return new ProgressiveTaxDetail(totalTax, sb.toString());
    }

    private String formatVnd(BigDecimal amount) {
        if (amount == null) return "0";
        return String.format("%,d", amount.longValue());
    }

    private static class ProgressiveTaxDetail {
        final BigDecimal totalTax;
        final String explanation;

        ProgressiveTaxDetail(BigDecimal totalTax, String explanation) {
            this.totalTax = totalTax;
            this.explanation = explanation;
        }
    }
}
