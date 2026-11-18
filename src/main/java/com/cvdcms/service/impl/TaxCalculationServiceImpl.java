package com.cvdcms.service.impl;

import com.cvdcms.DTO.PitCalculationResult;
import com.cvdcms.entity.BranchTaxConfig;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.TaxPolicy;
import com.cvdcms.entity.enums.InsuranceParticipationStatus;
import com.cvdcms.entity.enums.StaffContractType;
import com.cvdcms.repository.BranchTaxConfigRepository;
import com.cvdcms.repository.TaxPolicyRepository;
import com.cvdcms.service.TaxCalculationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
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

    @org.springframework.beans.factory.annotation.Autowired
    public TaxCalculationServiceImpl(
            TaxPolicyRepository taxPolicyRepository,
            BranchTaxConfigRepository branchTaxConfigRepository
    ) {
        this.taxPolicyRepository = taxPolicyRepository;
        this.branchTaxConfigRepository = branchTaxConfigRepository;
    }

    private static final LocalDate EFFECTIVE_DATE_2026 = LocalDate.of(2026, 1, 1);

    @Override
    public TaxPolicy getActiveTaxPolicy(LocalDate effectiveDate) {
        LocalDate target = effectiveDate != null ? effectiveDate : LocalDate.now();
        if (taxPolicyRepository == null) {
            return createFallbackPolicyForDate(target);
        }
        List<TaxPolicy> policies = taxPolicyRepository.findActivePoliciesAtDate(target, PageRequest.of(0, 2));
        if (policies == null || policies.isEmpty()) {
            log.warn("Không tìm thấy chính sách thuế TNCN hiệu lực trong DB tại ngày {}; dùng chính sách tham khảo tạm thời cho màn xem.", target);
            return createFallbackPolicyForDate(target);
        }
        if (policies.size() > 1) {
            log.warn("Phát hiện chính sách thuế TNCN chồng hiệu lực tại ngày {}: chọn tạm {} (ID={}) thay vì {} (ID={}).",
                    target, policies.get(0).getPolicyCode(), policies.get(0).getPolicyId(),
                    policies.get(1).getPolicyCode(), policies.get(1).getPolicyId());
        }
        return policies.get(0);
    }

    @Override
    public TaxPolicy getActiveTaxPolicyStrict(LocalDate effectiveDate) {
        LocalDate target = effectiveDate != null ? effectiveDate : LocalDate.now();
        if (taxPolicyRepository == null) {
            return createFallbackPolicyForDate(target);
        }
        List<TaxPolicy> policies = taxPolicyRepository.findActivePoliciesAtDate(target, PageRequest.of(0, 2));
        if (policies == null || policies.isEmpty()) {
            throw new IllegalStateException("Không tìm thấy chính sách thuế TNCN đang hiệu lực tại ngày " + target
                    + ". Vui lòng cấu hình bảng tax_policies trước khi tính hoặc chốt lương.");
        }
        if (policies.size() > 1) {
            throw new IllegalStateException("Phát hiện chính sách thuế TNCN đang chồng hiệu lực tại ngày " + target
                    + " (" + policies.get(0).getPolicyCode() + " và " + policies.get(1).getPolicyCode()
                    + "). Vui lòng cấu hình lại khoảng hiệu lực trước khi tính hoặc chốt tài chính.");
        }
        return policies.get(0);
    }

    private TaxPolicy createFallbackPolicyForDate(LocalDate targetDate) {
        LocalDate target = targetDate != null ? targetDate : LocalDate.now();
        TaxPolicy p = new TaxPolicy();
        if (!target.isBefore(EFFECTIVE_DATE_2026)) {
            p.setPolicyCode("PIT_VN_2026_REF");
            p.setPolicyName("Chính sách thuế TNCN 2026 tham khảo (NQ 110/2025/UBTVQH15 & Luật 109/2025/QH15)");
            p.setEffectiveFrom(EFFECTIVE_DATE_2026);
            p.setPersonalRelief(BigDecimal.valueOf(15500000));
            p.setDependentRelief(BigDecimal.valueOf(6200000));
            p.setProgressiveSchedule("PROGRESSIVE_5_2026");
            p.setLegalReference("Nghị quyết 110/2025/UBTVQH15 & Luật Thuế TNCN 109/2025/QH15 (5 bậc)");
        } else {
            p.setPolicyCode("PIT_VN_2020_REF");
            p.setPolicyName("Chính sách thuế TNCN tham khảo (NQ 954/2020/UBTVQH14)");
            p.setEffectiveFrom(LocalDate.of(2020, 7, 1));
            p.setEffectiveTo(LocalDate.of(2025, 12, 31));
            p.setPersonalRelief(BigDecimal.valueOf(11000000));
            p.setDependentRelief(BigDecimal.valueOf(4400000));
            p.setProgressiveSchedule("PROGRESSIVE_7_2007");
            p.setLegalReference("Nghị quyết 954/2020/UBTVQH14, Điều 22 Luật Thuế TNCN 04/2007/QH12, TT 111/2013/TT-BTC");
        }
        p.setCasualTaxRate(BigDecimal.valueOf(10.0));
        p.setNonResidentTaxRate(BigDecimal.valueOf(20.0));
        p.setInsuranceEmployeeRate(BigDecimal.valueOf(10.5));
        p.setInsuranceEmployerRate(BigDecimal.valueOf(21.5));
        p.setIsActive(true);
        return p;
    }

    @Override
    public Optional<BranchTaxConfig> getBranchTaxConfig(Long branchId, LocalDate effectiveDate) {
        if (branchId == null || branchTaxConfigRepository == null) {
            return Optional.empty();
        }
        LocalDate target = effectiveDate != null ? effectiveDate : LocalDate.now();
        List<BranchTaxConfig> configs = branchTaxConfigRepository.findActiveConfigsAtDate(branchId, target, PageRequest.of(0, 2));
        if (configs == null || configs.isEmpty()) {
            return Optional.empty();
        }
        if (configs.size() > 1) {
            log.warn("Phát hiện cấu hình thuế chi nhánh {} chồng hiệu lực tại ngày {}: chọn tạm configId={} thay vì configId={}.",
                    branchId, target, configs.get(0).getConfigId(), configs.get(1).getConfigId());
        }
        return Optional.of(configs.get(0));
    }

    @Override
    public Optional<BranchTaxConfig> getBranchTaxConfigStrict(Long branchId, LocalDate effectiveDate) {
        if (branchId == null || branchTaxConfigRepository == null) {
            return Optional.empty();
        }
        LocalDate target = effectiveDate != null ? effectiveDate : LocalDate.now();
        List<BranchTaxConfig> configs = branchTaxConfigRepository.findActiveConfigsAtDate(branchId, target, PageRequest.of(0, 2));
        if (configs == null || configs.isEmpty()) {
            return Optional.empty();
        }
        if (configs.size() > 1) {
            throw new IllegalStateException("Phát hiện cấu hình thuế chi nhánh (branchId=" + branchId
                    + ") đang chồng hiệu lực tại ngày " + target
                    + " (configId=" + configs.get(0).getConfigId() + " và " + configs.get(1).getConfigId()
                    + "). Vui lòng rà soát lại khoảng hiệu lực.");
        }
        return Optional.of(configs.get(0));
    }

    @Override
    public PitCalculationResult calculatePit(
            BigDecimal grossSalary,
            BigDecimal nonTaxableIncome,
            StaffProfile staff,
            LocalDate effectiveDate
    ) {
        LocalDate targetDate = effectiveDate != null ? effectiveDate : LocalDate.now();
        TaxPolicy policy = getActiveTaxPolicy(targetDate);
        return calculatePitWithPolicy(grossSalary, nonTaxableIncome, staff, policy, targetDate);
    }

    @Override
    public PitCalculationResult calculatePitStrict(
            BigDecimal grossSalary,
            BigDecimal nonTaxableIncome,
            StaffProfile staff,
            LocalDate effectiveDate
    ) {
        LocalDate targetDate = effectiveDate != null ? effectiveDate : LocalDate.now();
        TaxPolicy policy = getActiveTaxPolicyStrict(targetDate);
        return calculatePitWithPolicy(grossSalary, nonTaxableIncome, staff, policy, targetDate);
    }

    @Override
    public PitCalculationResult calculatePitWithPolicy(
            BigDecimal grossSalary,
            BigDecimal nonTaxableIncome,
            StaffProfile staff,
            TaxPolicy policy
    ) {
        return calculatePitWithPolicy(grossSalary, nonTaxableIncome, staff, policy, LocalDate.now());
    }

    private PitCalculationResult calculatePitWithPolicy(
            BigDecimal grossSalary,
            BigDecimal nonTaxableIncome,
            StaffProfile staff,
            TaxPolicy policy,
            LocalDate effectiveDate
    ) {
        LocalDate targetDate = effectiveDate != null ? effectiveDate : LocalDate.now();
        if (policy == null) {
            policy = createFallbackPolicyForDate(targetDate);
        }

        BigDecimal gross = grossSalary != null ? grossSalary : BigDecimal.ZERO;
        BigDecimal nonTaxable = nonTaxableIncome != null ? nonTaxableIncome : BigDecimal.ZERO;
        BigDecimal taxableIncome = gross.subtract(nonTaxable).max(BigDecimal.ZERO);

        InsuranceBasis ins = resolveInsuranceBasis(staff, policy, targetDate);

        // Trường hợp nhân sự thiếu loại hợp đồng: chỉ cho xem tạm nháp, đánh dấu pitVerified=false để chặn khi chốt
        if (staff == null || staff.getContractType() == null) {
            return basePitBuilder(gross, nonTaxable, taxableIncome, ins, policy)
                    .personalDeductionAmount(BigDecimal.ZERO)
                    .dependentDeductionAmount(BigDecimal.ZERO)
                    .assessableIncome(taxableIncome)
                    .pitAmount(BigDecimal.ZERO)
                    .calculationMethod("MISSING_CONTRACT_TYPE")
                    .pitScheduleSnapshot("Thiếu loại hợp đồng lao động (Kỳ " + targetDate + ")")
                    .pitVerified(false)
                    .taxCalculationDetail("CHƯA ĐỦ CĂN CỨ PIT: Chưa thiết lập loại hợp đồng lao động trên hồ sơ nhân sự. Không thể chốt bảng lương với PIT = 0.")
                    .build();
        }

        boolean isResident = staff.getIsTaxResident() == null || Boolean.TRUE.equals(staff.getIsTaxResident());
        StaffContractType contractType = staff.getContractType();
        boolean hasPersonalDeduction = staff.getHasPersonalDeduction() == null || Boolean.TRUE.equals(staff.getHasPersonalDeduction());
        int dependentCount = staff.getDependentCount() != null ? Math.max(0, staff.getDependentCount()) : 0;
        boolean hasCommitment = Boolean.TRUE.equals(staff.getHasTaxCommitment());

        BigDecimal insuranceEmployee = ins.employeeAmount;

        // Phân nhánh tính thuế TNCN theo đối tượng (tách biệt với diện tham gia bảo hiểm)
        if (!isResident || contractType == StaffContractType.NON_RESIDENT) {
            BigDecimal nonResRate = policy.getNonResidentTaxRate() != null ? policy.getNonResidentTaxRate() : BigDecimal.valueOf(20.0);
            BigDecimal pit = taxableIncome.multiply(nonResRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            String detail = String.format("Khấu trừ thuế TNCN không cư trú: %s%% trên TNCT %s đ = %s đ (Điều 18 Thông tư 111/2013/TT-BTC, kỳ %s)",
                    nonResRate.stripTrailingZeros().toPlainString(), formatVnd(taxableIncome), formatVnd(pit), targetDate);

            return basePitBuilder(gross, nonTaxable, taxableIncome, ins, policy)
                    .personalDeductionAmount(BigDecimal.ZERO)
                    .dependentDeductionAmount(BigDecimal.ZERO)
                    .assessableIncome(taxableIncome)
                    .pitAmount(pit)
                    .calculationMethod("NON_RESIDENT_FLAT_20")
                    .pitScheduleSnapshot("Khấu trừ 20% không cư trú (Điều 18 TT 111/2013/TT-BTC)")
                    .pitVerified(true)
                    .taxCalculationDetail(detail)
                    .build();
        }

        // Hợp đồng thử việc, thời vụ, CTV, vãng lai (< 3 tháng)
        if (contractType == StaffContractType.PROBATION_OR_CASUAL
                || contractType == StaffContractType.FULL_TIME_PROBATION
                || contractType == StaffContractType.PART_TIME_HOURLY
                || contractType == StaffContractType.COLLABORATOR) {

            if (hasCommitment) {
                String detail = "Nhân sự có cam kết Mẫu 08/CK-TNCN (ước tính tổng thu nhập trong năm dưới mức chịu thuế). Tạm thời chưa khấu trừ 10% tại nguồn.";
                return basePitBuilder(gross, nonTaxable, taxableIncome, ins, policy)
                        .personalDeductionAmount(BigDecimal.ZERO)
                        .dependentDeductionAmount(BigDecimal.ZERO)
                        .assessableIncome(taxableIncome)
                        .pitAmount(BigDecimal.ZERO)
                        .calculationMethod("CASUAL_COMMITMENT_08")
                        .pitScheduleSnapshot("Cam kết Mẫu 08/CK-TNCN (TT 80/2021/TT-BTC)")
                        .pitVerified(true)
                        .taxCalculationDetail(detail)
                        .build();
            }

            if (taxableIncome.compareTo(THRESHOLD_CASUAL_WITHHOLDING) >= 0) {
                BigDecimal casualRate = policy.getCasualTaxRate() != null ? policy.getCasualTaxRate() : BigDecimal.valueOf(10.0);
                BigDecimal pit = taxableIncome.multiply(casualRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                String detail = String.format("Khấu trừ 10%% tại nguồn cho HĐ dưới 3 tháng / vãng lai (chi trả từ 2,000,000 đ theo Điểm i Khoản 1 Điều 25 TT 111/2013/TT-BTC): %s đ",
                        formatVnd(pit));
                return basePitBuilder(gross, nonTaxable, taxableIncome, ins, policy)
                        .personalDeductionAmount(BigDecimal.ZERO)
                        .dependentDeductionAmount(BigDecimal.ZERO)
                        .assessableIncome(taxableIncome)
                        .pitAmount(pit)
                        .calculationMethod("CASUAL_FLAT_10")
                        .pitScheduleSnapshot("Khấu trừ 10% tại nguồn (Điểm i K1 Đ25 TT 111/2013/TT-BTC)")
                        .pitVerified(true)
                        .taxCalculationDetail(detail)
                        .build();
            } else {
                String detail = "Thu nhập chi trả dưới ngưỡng khấu trừ 2,000,000 đ/lần. Chưa phát sinh thuế khấu trừ.";
                return basePitBuilder(gross, nonTaxable, taxableIncome, ins, policy)
                        .personalDeductionAmount(BigDecimal.ZERO)
                        .dependentDeductionAmount(BigDecimal.ZERO)
                        .assessableIncome(taxableIncome)
                        .pitAmount(BigDecimal.ZERO)
                        .calculationMethod("CASUAL_BELOW_THRESHOLD")
                        .pitScheduleSnapshot("Dưới ngưỡng khấu trừ 2.000.000 đ/lần")
                        .pitVerified(true)
                        .taxCalculationDetail(detail)
                        .build();
            }
        }

        // HỢP ĐỒNG LAO ĐỘNG TỪ 3 THÁNG TRỞ LÊN: BIỂU THUẾ LŨY TIẾN THEO PHIÊN BẢN CHÍNH SÁCH
        if (policy.getPersonalRelief() == null || policy.getDependentRelief() == null) {
            throw new IllegalStateException("Chính sách thuế " + policy.getPolicyCode() + " thiếu mức giảm trừ bản thân hoặc người phụ thuộc.");
        }
        BigDecimal personalRelief = hasPersonalDeduction ? policy.getPersonalRelief() : BigDecimal.ZERO;
        BigDecimal dependentRelief = policy.getDependentRelief().multiply(BigDecimal.valueOf(dependentCount));

        // Thu nhập tính thuế = Thu nhập chịu thuế - Các khoản bảo hiểm - Giảm trừ gia cảnh
        BigDecimal assessableIncome = taxableIncome
                .subtract(insuranceEmployee)
                .subtract(personalRelief)
                .subtract(dependentRelief)
                .max(BigDecimal.ZERO);

        String scheduleCode = resolveProgressiveSchedule(policy);
        ProgressiveTaxDetail prog = calculateProgressivePit(assessableIncome, scheduleCode);
        String scheduleSnapshot = "PROGRESSIVE_5_2026".equals(scheduleCode)
                ? "Biểu 5 bậc (Luật Thuế TNCN 109/2025/QH15) — Kỳ " + targetDate
                : "Biểu 7 bậc (Luật Thuế TNCN 04/2007/QH12) — Kỳ " + targetDate;

        return basePitBuilder(gross, nonTaxable, taxableIncome, ins, policy)
                .personalDeductionAmount(personalRelief)
                .dependentDeductionAmount(dependentRelief)
                .assessableIncome(assessableIncome)
                .pitAmount(prog.totalTax)
                .calculationMethod(scheduleCode)
                .pitScheduleSnapshot(scheduleSnapshot)
                .pitVerified(true)
                .taxCalculationDetail(prog.explanation)
                .build();
    }

    private PitCalculationResult.PitCalculationResultBuilder basePitBuilder(
            BigDecimal gross,
            BigDecimal nonTaxable,
            BigDecimal taxableIncome,
            InsuranceBasis ins,
            TaxPolicy policy
    ) {
        return PitCalculationResult.builder()
                .grossSalary(gross)
                .nonTaxableIncome(nonTaxable)
                .taxableIncome(taxableIncome)
                .insuranceEmployeeDeduction(ins.employeeAmount)
                .insuranceEmployerAmount(ins.employerAmount)
                .insuranceParticipationStatus(ins.status)
                .insuranceVerified(ins.verified)
                .insuranceBaseSalarySnapshot(ins.baseSalarySnapshot)
                .insuranceEmployeeRateSnapshot(ins.employeeRateSnapshot)
                .insuranceEmployerRateSnapshot(ins.employerRateSnapshot)
                .insuranceBasisNote(ins.basisNote)
                .taxPolicyCode(policy.getPolicyCode());
    }

    private String resolveProgressiveSchedule(TaxPolicy policy) {
        String code = policy.getProgressiveSchedule();
        if (code != null && !code.isBlank()) {
            String normalized = code.trim().toUpperCase();
            if ("PROGRESSIVE_5_2026".equals(normalized) || "PROGRESSIVE_7_2007".equals(normalized)) {
                return normalized;
            }
            throw new IllegalStateException("Biểu thuế lũy tiến không được hỗ trợ: " + code);
        }
        if (policy.getEffectiveFrom() != null && !policy.getEffectiveFrom().isBefore(EFFECTIVE_DATE_2026)) {
            return "PROGRESSIVE_5_2026";
        }
        return "PROGRESSIVE_7_2007";
    }

    private InsuranceBasis resolveInsuranceBasis(StaffProfile staff, TaxPolicy policy, LocalDate targetDate) {
        if (staff == null || staff.getInsuranceParticipationStatus() == null
                || staff.getInsuranceParticipationStatus() == InsuranceParticipationStatus.UNVERIFIED) {
            return new InsuranceBasis(
                    InsuranceParticipationStatus.UNVERIFIED,
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    null,
                    "CHƯA ĐỦ CĂN CỨ: Hồ sơ bảo hiểm chưa được xác minh (UNVERIFIED)."
            );
        }

        InsuranceParticipationStatus configuredStatus = staff.getInsuranceParticipationStatus();
        LocalDate effFrom = staff.getInsuranceEffectiveFrom();
        LocalDate effTo = staff.getInsuranceEffectiveTo();
        if ((effFrom != null && targetDate.isBefore(effFrom)) || (effTo != null && targetDate.isAfter(effTo))) {
            return new InsuranceBasis(
                    InsuranceParticipationStatus.UNVERIFIED,
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    null,
                    "CHƯA ĐỦ CĂN CỨ: Hồ sơ bảo hiểm (" + configuredStatus.name()
                            + ") ngoài thời gian hiệu lực tại kỳ " + targetDate + "."
            );
        }

        if (configuredStatus == InsuranceParticipationStatus.EXEMPT) {
            String reason = staff.getInsuranceExemptionReason();
            String note = "Miễn tham gia bảo hiểm"
                    + (reason != null && !reason.isBlank() ? " (" + reason.trim() + ")" : "");
            return new InsuranceBasis(
                    InsuranceParticipationStatus.EXEMPT,
                    true,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    note
            );
        }

        // PARTICIPATING
        BigDecimal insSalary = staff.getInsuranceSalary();
        if (insSalary == null || insSalary.compareTo(BigDecimal.ZERO) <= 0) {
            return new InsuranceBasis(
                    InsuranceParticipationStatus.UNVERIFIED,
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    null,
                    "CHƯA ĐỦ CĂN CỨ: Nhân sự thuộc diện tham gia BH nhưng lương đóng BHXH chưa lớn hơn 0."
            );
        }

        BigDecimal empRate = staff.getInsuranceEmployeeRateOverride() != null
                ? staff.getInsuranceEmployeeRateOverride()
                : (policy != null ? policy.getInsuranceEmployeeRate() : null);
        BigDecimal erRate = staff.getInsuranceEmployerRateOverride() != null
                ? staff.getInsuranceEmployerRateOverride()
                : (policy != null ? policy.getInsuranceEmployerRate() : null);

        if (empRate == null || erRate == null
                || empRate.compareTo(BigDecimal.ZERO) < 0 || erRate.compareTo(BigDecimal.ZERO) < 0) {
            return new InsuranceBasis(
                    InsuranceParticipationStatus.UNVERIFIED,
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    null,
                    "CHƯA ĐỦ CĂN CỨ: Thiếu tỷ lệ đóng bảo hiểm hợp lệ từ chính sách hoặc cấu hình nhân sự."
            );
        }

        BigDecimal empAmt = insSalary.multiply(empRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        BigDecimal erAmt = insSalary.multiply(erRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        String note = String.format("Tham gia BH: Mức đóng %s đ (NLĐ %s%%, NSDLĐ %s%%)",
                formatVnd(insSalary),
                empRate.stripTrailingZeros().toPlainString(),
                erRate.stripTrailingZeros().toPlainString());

        return new InsuranceBasis(
                InsuranceParticipationStatus.PARTICIPATING,
                true,
                empAmt,
                erAmt,
                insSalary,
                empRate,
                erRate,
                note
        );
    }

    private record InsuranceBasis(
            InsuranceParticipationStatus status,
            boolean verified,
            BigDecimal employeeAmount,
            BigDecimal employerAmount,
            BigDecimal baseSalarySnapshot,
            BigDecimal employeeRateSnapshot,
            BigDecimal employerRateSnapshot,
            String basisNote
    ) {}

    private ProgressiveTaxDetail calculateProgressivePit(BigDecimal assessable, String scheduleCode) {
        if (assessable.compareTo(BigDecimal.ZERO) <= 0) {
            return new ProgressiveTaxDetail(BigDecimal.ZERO, "Thu nhập tính thuế <= 0 đ. Thuế TNCN = 0 đ.");
        }

        List<TaxBracket> brackets;
        String legalFootnote;
        if ("PROGRESSIVE_5_2026".equals(scheduleCode)) {
            brackets = List.of(
                    new TaxBracket(BigDecimal.valueOf(10_000_000L), BigDecimal.valueOf(0.05), "Bậc 1 (<= 10M - 5%)"),
                    new TaxBracket(BigDecimal.valueOf(20_000_000L), BigDecimal.valueOf(0.10), "Bậc 2 (10M-30M - 10%)"),
                    new TaxBracket(BigDecimal.valueOf(30_000_000L), BigDecimal.valueOf(0.20), "Bậc 3 (30M-60M - 20%)"),
                    new TaxBracket(BigDecimal.valueOf(40_000_000L), BigDecimal.valueOf(0.30), "Bậc 4 (60M-100M - 30%)"),
                    new TaxBracket(null, BigDecimal.valueOf(0.35), "Bậc 5 (> 100M - 35%)")
            );
            legalFootnote = "Luật Thuế TNCN 109/2025/QH15 (Biểu 5 bậc) & NQ 110/2025/UBTVQH15";
        } else {
            brackets = List.of(
                    new TaxBracket(B1_MAX, BigDecimal.valueOf(0.05), "Bậc 1 (<= 5M - 5%)"),
                    new TaxBracket(B2_MAX.subtract(B1_MAX), BigDecimal.valueOf(0.10), "Bậc 2 (5M-10M - 10%)"),
                    new TaxBracket(B3_MAX.subtract(B2_MAX), BigDecimal.valueOf(0.15), "Bậc 3 (10M-18M - 15%)"),
                    new TaxBracket(B4_MAX.subtract(B3_MAX), BigDecimal.valueOf(0.20), "Bậc 4 (18M-32M - 20%)"),
                    new TaxBracket(B5_MAX.subtract(B4_MAX), BigDecimal.valueOf(0.25), "Bậc 5 (32M-52M - 25%)"),
                    new TaxBracket(B6_MAX.subtract(B5_MAX), BigDecimal.valueOf(0.30), "Bậc 6 (52M-80M - 30%)"),
                    new TaxBracket(null, BigDecimal.valueOf(0.35), "Bậc 7 (> 80M - 35%)")
            );
            legalFootnote = "Điều 22 Luật Thuế TNCN 04/2007/QH12 & NQ 954/2020/UBTVQH14";
        }

        BigDecimal rem = assessable;
        BigDecimal totalTax = BigDecimal.ZERO;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Biểu thuế lũy tiến từng phần %s (TNTT: %s đ):\n",
                "PROGRESSIVE_5_2026".equals(scheduleCode) ? "5 bậc (2026)" : "7 bậc (2007)",
                formatVnd(assessable)));

        for (TaxBracket bracket : brackets) {
            if (rem.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal portion = bracket.width() != null ? rem.min(bracket.width()) : rem;
            if (portion.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal tax = portion.multiply(bracket.rate()).setScale(0, RoundingMode.HALF_UP);
                totalTax = totalTax.add(tax);
                String ratePct = bracket.rate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
                sb.append(String.format("• %s: %s đ x %s%% = %s đ\n", bracket.label(), formatVnd(portion), ratePct, formatVnd(tax)));
                rem = rem.subtract(portion);
            }
        }

        sb.append(String.format("=> Tổng thuế TNCN khấu trừ = %s đ (%s)", formatVnd(totalTax), legalFootnote));
        return new ProgressiveTaxDetail(totalTax, sb.toString());
    }

    private record TaxBracket(BigDecimal width, BigDecimal rate, String label) {}

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
