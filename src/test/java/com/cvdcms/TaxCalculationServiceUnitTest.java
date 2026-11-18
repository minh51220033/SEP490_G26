package com.cvdcms;

import com.cvdcms.DTO.PitCalculationResult;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.InsuranceParticipationStatus;
import com.cvdcms.entity.enums.StaffContractType;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.BranchTaxConfigRepository;
import com.cvdcms.repository.TaxPolicyRepository;
import com.cvdcms.service.impl.TaxCalculationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public class TaxCalculationServiceUnitTest {

    @Mock
    private TaxPolicyRepository taxPolicyRepository;
    @Mock
    private BranchTaxConfigRepository branchTaxConfigRepository;

    private TaxCalculationServiceImpl taxCalculationService;
    private TaxPolicy standardPolicy;

    @BeforeEach
    void setUp() {
        taxCalculationService = new TaxCalculationServiceImpl(taxPolicyRepository, branchTaxConfigRepository);

        standardPolicy = new TaxPolicy();
        standardPolicy.setPolicyId(1L);
        standardPolicy.setPolicyCode("PIT_VN_2020");
        standardPolicy.setPolicyName("Nghị quyết 954/2020/UBTVQH14");
        standardPolicy.setEffectiveFrom(LocalDate.of(2020, 7, 1));
        standardPolicy.setPersonalRelief(new BigDecimal("11000000"));
        standardPolicy.setDependentRelief(new BigDecimal("4400000"));
        standardPolicy.setCasualTaxRate(new BigDecimal("10.0"));
        standardPolicy.setNonResidentTaxRate(new BigDecimal("20.0"));
        standardPolicy.setInsuranceEmployeeRate(new BigDecimal("10.5"));
        standardPolicy.setInsuranceEmployerRate(new BigDecimal("21.5"));
        standardPolicy.setIsActive(true);

        lenient().when(taxPolicyRepository.findActivePoliciesAtDate(any(), any(Pageable.class)))
                .thenReturn(List.of(standardPolicy));
    }

    private StaffProfile createStaff(StaffContractType contractType, boolean isResident, boolean hasPersonalDeduction, int dependents, boolean hasCommitment, BigDecimal insuranceSalary) {
        StaffProfile staff = new StaffProfile();
        staff.setStaffId(100L);
        staff.setFullName("Nguyễn Văn Kiểm Thử");
        staff.setStaffCode("NV999");
        staff.setStatus(StaffStatus.ACTIVE);
        staff.setContractType(contractType);
        staff.setIsTaxResident(isResident);
        staff.setHasPersonalDeduction(hasPersonalDeduction);
        staff.setDependentCount(dependents);
        staff.setHasTaxCommitment(hasCommitment);
        staff.setInsuranceSalary(insuranceSalary);
        staff.setInsuranceParticipationStatus(insuranceSalary != null && insuranceSalary.compareTo(BigDecimal.ZERO) > 0
                ? InsuranceParticipationStatus.PARTICIPATING
                : InsuranceParticipationStatus.EXEMPT);
        return staff;
    }

    // =========================================================================
    // 1. KIỂM THỬ THUẾ SUẤT BIỂU LŨY TIẾN TỪNG PHẦN 7 BẬC (ĐIỀU 22 LUẬT THUẾ TNCN)
    // =========================================================================

    @Test
    @DisplayName("Kịch bản 1: Thu nhập tính thuế <= 0 -> Thuế TNCN = 0 đ")
    void testZeroAssessableIncome_NoTax() {
        // Gross 11 triệu = đúng mức giảm trừ bản thân 11 triệu -> TNTT = 0
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, BigDecimal.ZERO);
        PitCalculationResult result = taxCalculationService.calculatePit(new BigDecimal("11000000"), BigDecimal.ZERO, staff, LocalDate.now());

        assertEquals(new BigDecimal("11000000"), result.getGrossSalary());
        assertEquals(new BigDecimal("11000000"), result.getPersonalDeductionAmount());
        assertEquals(BigDecimal.ZERO, result.getAssessableIncome());
        assertEquals(BigDecimal.ZERO, result.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 2: Bậc 1 (TNTT đến 5 triệu VNĐ - Thuế suất 5%)")
    void testProgressiveBracket1_BoundaryAndStep() {
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, BigDecimal.ZERO);

        // TNTT = 3,000,000 (Gross = 14M, GTBT = 11M) -> Thuế = 3M x 5% = 150,000 đ
        PitCalculationResult r1 = taxCalculationService.calculatePit(new BigDecimal("14000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("3000000"), r1.getAssessableIncome());
        assertEquals(new BigDecimal("150000"), r1.getPitAmount());

        // Đúng biên Bậc 1: TNTT = 5,000,000 (Gross = 16M) -> Thuế = 5M x 5% = 250,000 đ
        PitCalculationResult r2 = taxCalculationService.calculatePit(new BigDecimal("16000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("5000000"), r2.getAssessableIncome());
        assertEquals(new BigDecimal("250000"), r2.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 3: Bậc 2 (TNTT trên 5M đến 10M - Thuế suất 10%)")
    void testProgressiveBracket2_BoundaryAndStep() {
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, BigDecimal.ZERO);

        // TNTT = 8,000,000 (Gross = 19M) -> Thuế = 250k + 3M x 10% = 550,000 đ
        PitCalculationResult r1 = taxCalculationService.calculatePit(new BigDecimal("19000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("8000000"), r1.getAssessableIncome());
        assertEquals(new BigDecimal("550000"), r1.getPitAmount());

        // Đúng biên Bậc 2: TNTT = 10,000,000 (Gross = 21M) -> Thuế = 250k + 5M x 10% = 750,000 đ
        PitCalculationResult r2 = taxCalculationService.calculatePit(new BigDecimal("21000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("10000000"), r2.getAssessableIncome());
        assertEquals(new BigDecimal("750000"), r2.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 4: Bậc 3 (TNTT trên 10M đến 18M - Thuế suất 15%)")
    void testProgressiveBracket3_BoundaryAndStep() {
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, BigDecimal.ZERO);

        // TNTT = 15,000,000 (Gross = 26M) -> Thuế = 750k + 5M x 15% = 1,500,000 đ
        PitCalculationResult r1 = taxCalculationService.calculatePit(new BigDecimal("26000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("15000000"), r1.getAssessableIncome());
        assertEquals(new BigDecimal("1500000"), r1.getPitAmount());

        // Đúng biên Bậc 3: TNTT = 18,000,000 (Gross = 29M) -> Thuế = 750k + 8M x 15% = 1,950,000 đ
        PitCalculationResult r2 = taxCalculationService.calculatePit(new BigDecimal("29000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("18000000"), r2.getAssessableIncome());
        assertEquals(new BigDecimal("1950000"), r2.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 5: Bậc 4 (TNTT trên 18M đến 32M - Thuế suất 20%)")
    void testProgressiveBracket4_BoundaryAndStep() {
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, BigDecimal.ZERO);

        // TNTT = 25,000,000 (Gross = 36M) -> Thuế = 1,950k + 7M x 20% = 3,350,000 đ
        PitCalculationResult r1 = taxCalculationService.calculatePit(new BigDecimal("36000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("25000000"), r1.getAssessableIncome());
        assertEquals(new BigDecimal("3350000"), r1.getPitAmount());

        // Đúng biên Bậc 4: TNTT = 32,000,000 (Gross = 43M) -> Thuế = 1,950k + 14M x 20% = 4,750,000 đ
        PitCalculationResult r2 = taxCalculationService.calculatePit(new BigDecimal("43000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("32000000"), r2.getAssessableIncome());
        assertEquals(new BigDecimal("4750000"), r2.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 6: Bậc 5 (TNTT trên 32M đến 52M - Thuế suất 25%)")
    void testProgressiveBracket5_BoundaryAndStep() {
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, BigDecimal.ZERO);

        // TNTT = 40,000,000 (Gross = 51M) -> Thuế = 4,750k + 8M x 25% = 6,750,000 đ
        PitCalculationResult r1 = taxCalculationService.calculatePit(new BigDecimal("51000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("40000000"), r1.getAssessableIncome());
        assertEquals(new BigDecimal("6750000"), r1.getPitAmount());

        // Đúng biên Bậc 5: TNTT = 52,000,000 (Gross = 63M) -> Thuế = 4,750k + 20M x 25% = 9,750,000 đ
        PitCalculationResult r2 = taxCalculationService.calculatePit(new BigDecimal("63000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("52000000"), r2.getAssessableIncome());
        assertEquals(new BigDecimal("9750000"), r2.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 7: Bậc 6 (TNTT trên 52M đến 80M - Thuế suất 30%)")
    void testProgressiveBracket6_BoundaryAndStep() {
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, BigDecimal.ZERO);

        // TNTT = 60,000,000 (Gross = 71M) -> Thuế = 9,750k + 8M x 30% = 12,150,000 đ
        PitCalculationResult r1 = taxCalculationService.calculatePit(new BigDecimal("71000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("60000000"), r1.getAssessableIncome());
        assertEquals(new BigDecimal("12150000"), r1.getPitAmount());

        // Đúng biên Bậc 6: TNTT = 80,000,000 (Gross = 91M) -> Thuế = 9,750k + 28M x 30% = 18,150,000 đ
        PitCalculationResult r2 = taxCalculationService.calculatePit(new BigDecimal("91000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("80000000"), r2.getAssessableIncome());
        assertEquals(new BigDecimal("18150000"), r2.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 8: Bậc 7 (TNTT trên 80M - Thuế suất 35%)")
    void testProgressiveBracket7_Excess() {
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, BigDecimal.ZERO);

        // TNTT = 100,000,000 (Gross = 111M) -> Thuế = 18,150k + 20M x 35% = 25,150,000 đ
        PitCalculationResult r = taxCalculationService.calculatePit(new BigDecimal("111000000"), BigDecimal.ZERO, staff, LocalDate.now());
        assertEquals(new BigDecimal("100000000"), r.getAssessableIncome());
        assertEquals(new BigDecimal("25150000"), r.getPitAmount());
    }

    // =========================================================================
    // 2. KIỂM THỬ KHẤU TRỪ 10% HỢP ĐỒNG THỬ VIỆC / THỜI VỤ / VÃNG LAI
    // =========================================================================

    @Test
    @DisplayName("Kịch bản 9: Hợp đồng vãng lai / thử việc không có cam kết 08 -> Khấu trừ 10% tại nguồn")
    void testCasualContract_AboveThresholdWithoutCommitment_10Percent() {
        StaffProfile staff = createStaff(StaffContractType.FULL_TIME_PROBATION, true, true, 0, false, BigDecimal.ZERO);
        PitCalculationResult r = taxCalculationService.calculatePit(new BigDecimal("10000000"), BigDecimal.ZERO, staff, LocalDate.now());

        // Khấu trừ 10% trên 10 triệu = 1,000,000 đ
        assertEquals(new BigDecimal("10000000"), r.getGrossSalary());
        assertEquals(new BigDecimal("1000000"), r.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 10: Hợp đồng vãng lai dưới ngưỡng 2,000,000 đ/lần -> Chưa khấu trừ 10%")
    void testCasualContract_Below2MThreshold_ZeroTax() {
        StaffProfile staff = createStaff(StaffContractType.COLLABORATOR, true, true, 0, false, BigDecimal.ZERO);
        PitCalculationResult r = taxCalculationService.calculatePit(new BigDecimal("1800000"), BigDecimal.ZERO, staff, LocalDate.now());

        assertEquals(new BigDecimal("1800000"), r.getGrossSalary());
        assertEquals(BigDecimal.ZERO, r.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 11: Nhân sự có cam kết Mẫu 08/CK-TNCN -> Tạm thời chưa khấu trừ 10%")
    void testCasualContract_WithForm08Commitment_ZeroTax() {
        StaffProfile staff = createStaff(StaffContractType.COLLABORATOR, true, true, 0, true, BigDecimal.ZERO);
        PitCalculationResult r = taxCalculationService.calculatePit(new BigDecimal("15000000"), BigDecimal.ZERO, staff, LocalDate.now());

        assertEquals(new BigDecimal("15000000"), r.getGrossSalary());
        assertEquals(BigDecimal.ZERO, r.getPitAmount());
        assertTrue(r.getTaxCalculationDetail().contains("Mẫu 08/CK-TNCN"));
    }

    // =========================================================================
    // 3. KIỂM THỬ CÁ NHÂN KHÔNG CƯ TRÚ (ĐIỀU 18 THÔNG TƯ 111/2013/TT-BTC)
    // =========================================================================

    @Test
    @DisplayName("Kịch bản 12: Cá nhân không cư trú -> Khấu trừ 20% phẳng trên toàn bộ TNCT")
    void testNonResident_Flat20Percent() {
        StaffProfile staff = createStaff(StaffContractType.NON_RESIDENT, false, true, 2, false, BigDecimal.ZERO);
        PitCalculationResult r = taxCalculationService.calculatePit(new BigDecimal("30000000"), BigDecimal.ZERO, staff, LocalDate.now());

        // Không cư trú: Không giảm trừ gia cảnh, không giảm trừ người phụ thuộc
        assertEquals(new BigDecimal("30000000"), r.getGrossSalary());
        assertEquals(BigDecimal.ZERO, r.getPersonalDeductionAmount());
        assertEquals(BigDecimal.ZERO, r.getDependentDeductionAmount());
        // Thuế = 30M x 20% = 6,000,000 đ
        assertEquals(new BigDecimal("6000000"), r.getPitAmount());
    }

    // =========================================================================
    // 4. KIỂM THỬ GIẢM TRỪ GIA CẢNH BẢN THÂN, NGƯỜI PHỤ THUỘC & BHXH (10.5%)
    // =========================================================================

    @Test
    @DisplayName("Kịch bản 13: Lương Gross 30M, BHXH trên 10M (10.5%), Giảm trừ bản thân + 2 người phụ thuộc")
    void testPersonalRelief_WithDependentsAndInsurance() {
        // Lương bảo hiểm = 10,000,000 đ -> BHXH NLĐ đóng 10.5% = 1,050,000 đ
        // Giảm trừ bản thân = 11,000,000 đ
        // Giảm trừ 2 người phụ thuộc = 2 x 4,400,000 = 8,800,000 đ
        // Tổng các khoản giảm trừ = 1,050,000 + 11,000,000 + 8,800,000 = 20,850,000 đ
        // TNTT = 30,000,000 - 20,850,000 = 9,150,000 đ (nằm trong Bậc 2)
        // Thuế Bậc 1 (5M x 5%) = 250,000 đ
        // Thuế Bậc 2 ((9.15M - 5M) x 10%) = 4,150,000 x 10% = 415,000 đ
        // Tổng thuế TNCN = 250,000 + 415,000 = 665,000 đ
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 2, false, new BigDecimal("10000000"));
        PitCalculationResult r = taxCalculationService.calculatePit(new BigDecimal("30000000"), BigDecimal.ZERO, staff, LocalDate.now());

        assertEquals(new BigDecimal("1050000"), r.getInsuranceEmployeeDeduction());
        assertEquals(new BigDecimal("2150000"), r.getInsuranceEmployerAmount());
        assertEquals(new BigDecimal("11000000"), r.getPersonalDeductionAmount());
        assertEquals(new BigDecimal("8800000"), r.getDependentDeductionAmount());
        assertEquals(new BigDecimal("9150000"), r.getAssessableIncome());
        assertEquals(new BigDecimal("665000"), r.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 14: Nhân sự không đăng ký giảm trừ bản thân tại phòng khám (hasPersonalDeduction = false)")
    void testPersonalRelief_Disabled() {
        // Không đăng ký giảm trừ bản thân: personalRelief = 0
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, false, 0, false, BigDecimal.ZERO);
        PitCalculationResult r = taxCalculationService.calculatePit(new BigDecimal("20000000"), BigDecimal.ZERO, staff, LocalDate.now());

        assertEquals(BigDecimal.ZERO, r.getPersonalDeductionAmount());
        assertEquals(new BigDecimal("20000000"), r.getAssessableIncome());
        // TNTT 20M (Bậc 3: đến 5M 5%, 5-10M 10%, 10-18M 15%, 18-20M 20%):
        // B1: 250k, B2: 500k, B3: 8M x 15% = 1.2M, B4: 2M x 20% = 400k -> Tổng = 2,350,000 đ
        assertEquals(new BigDecimal("2350000"), r.getPitAmount());
    }

    // =========================================================================
    // 5. KIỂM THỬ CẤU HÌNH THUẾ DOANH NGHIỆP & HỘ KINH DOANH (FT29)
    // =========================================================================

    @Test
    @DisplayName("Kịch bản 15: Cấu hình thuế chi nhánh Doanh nghiệp (TNDN 20%)")
    void testBranchTaxConfig_CitStandard_20Percent() {
        Branch branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở Cầu Giấy");

        BranchTaxConfig config = new BranchTaxConfig();
        config.setConfigId(1L);
        config.setBranch(branch);
        config.setEntityType("ENTERPRISE");
        config.setTaxRegime("CIT_STANDARD");
        config.setCitRate(new BigDecimal("20.0"));
        config.setEffectiveFrom(LocalDate.of(2020, 1, 1));
        config.setLegalReference("Luật Thuế TNDN 14/2008/QH12");

        when(branchTaxConfigRepository.findActiveConfigsAtDate(eq(1L), any(), any(Pageable.class)))
                .thenReturn(List.of(config));

        Optional<BranchTaxConfig> activeConfig = taxCalculationService.getBranchTaxConfig(1L, LocalDate.now());
        assertTrue(activeConfig.isPresent());
        assertEquals("ENTERPRISE", activeConfig.get().getEntityType());
        assertEquals(new BigDecimal("20.0"), activeConfig.get().getCitRate());
    }

    @Test
    @DisplayName("Kịch bản 16: Cấu hình thuế chi nhánh Hộ kinh doanh cá thể (Thuế khoán 2% doanh thu)")
    void testBranchTaxConfig_HouseholdRevenue_2Percent() {
        Branch branch = new Branch();
        branch.setBranchId(2L);
        branch.setBranchName("Cơ sở Đống Đa");

        BranchTaxConfig config = new BranchTaxConfig();
        config.setConfigId(2L);
        config.setBranch(branch);
        config.setEntityType("HOUSEHOLD");
        config.setTaxRegime("REVENUE_PERCENT");
        config.setServiceTaxRate(new BigDecimal("2.0"));
        config.setMedicineTaxRate(new BigDecimal("0.5"));
        config.setEffectiveFrom(LocalDate.of(2021, 8, 1));
        config.setLegalReference("Thông tư 40/2021/TT-BTC");

        when(branchTaxConfigRepository.findActiveConfigsAtDate(eq(2L), any(), any(Pageable.class)))
                .thenReturn(List.of(config));

        Optional<BranchTaxConfig> activeConfig = taxCalculationService.getBranchTaxConfig(2L, LocalDate.now());
        assertTrue(activeConfig.isPresent());
        assertEquals("HOUSEHOLD", activeConfig.get().getEntityType());
        assertEquals("REVENUE_PERCENT", activeConfig.get().getTaxRegime());
        assertEquals(new BigDecimal("2.0"), activeConfig.get().getServiceTaxRate());
    }

    @Test
    @DisplayName("Kịch bản 17: Tra cứu policy thuế khi có 0/1/2 bản ghi (màn xem fallback theo kỳ, chốt báo lỗi)")
    void testTaxPolicyOverlap_ViewVsStrictFinalization() {
        LocalDate target2026 = LocalDate.of(2026, 7, 1);
        LocalDate target2025 = LocalDate.of(2025, 11, 1);

        // 0 kết quả -> màn xem fallback theo kỳ áp dụng; chốt chặt phải báo lỗi không fallback im lặng
        when(taxPolicyRepository.findActivePoliciesAtDate(eq(target2026), eq(PageRequest.of(0, 2))))
                .thenReturn(Collections.emptyList());
        when(taxPolicyRepository.findActivePoliciesAtDate(eq(target2025), eq(PageRequest.of(0, 2))))
                .thenReturn(Collections.emptyList());

        assertEquals("PIT_VN_2026_REF", taxCalculationService.getActiveTaxPolicy(target2026).getPolicyCode());
        assertEquals("PIT_VN_2020_REF", taxCalculationService.getActiveTaxPolicy(target2025).getPolicyCode());
        IllegalStateException missingEx = assertThrows(IllegalStateException.class,
                () -> taxCalculationService.getActiveTaxPolicyStrict(target2026));
        assertTrue(missingEx.getMessage().contains("Không tìm thấy chính sách thuế"));

        // 2 kết quả chồng hiệu lực -> màn xem lấy bản ghi đầu theo thứ tự, chốt chặt báo IllegalStateException
        TaxPolicy newer = new TaxPolicy();
        newer.setPolicyId(12L);
        newer.setPolicyCode("PIT_2026_V2");
        TaxPolicy older = new TaxPolicy();
        older.setPolicyId(11L);
        older.setPolicyCode("PIT_2026_V1");

        when(taxPolicyRepository.findActivePoliciesAtDate(eq(target2026), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(newer, older));

        assertEquals("PIT_2026_V2", taxCalculationService.getActiveTaxPolicy(target2026).getPolicyCode());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> taxCalculationService.getActiveTaxPolicyStrict(target2026));
        assertTrue(ex.getMessage().contains("chồng hiệu lực"));
    }

    @Test
    @DisplayName("Kịch bản 18: Tra cứu cấu hình thuế chi nhánh khi có 0/2 bản ghi chồng hiệu lực")
    void testBranchTaxConfigOverlap_ViewVsStrictFinalization() {
        LocalDate target = LocalDate.of(2026, 7, 1);

        when(branchTaxConfigRepository.findActiveConfigsAtDate(eq(1L), eq(target), eq(PageRequest.of(0, 2))))
                .thenReturn(Collections.emptyList());
        assertTrue(taxCalculationService.getBranchTaxConfig(1L, target).isEmpty());
        assertTrue(taxCalculationService.getBranchTaxConfigStrict(1L, target).isEmpty());

        BranchTaxConfig cfgNew = new BranchTaxConfig();
        cfgNew.setConfigId(22L);
        cfgNew.setEntityType("ENTERPRISE");
        BranchTaxConfig cfgOld = new BranchTaxConfig();
        cfgOld.setConfigId(21L);
        cfgOld.setEntityType("HOUSEHOLD");

        when(branchTaxConfigRepository.findActiveConfigsAtDate(eq(1L), eq(target), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(cfgNew, cfgOld));

        Optional<BranchTaxConfig> viewCfg = taxCalculationService.getBranchTaxConfig(1L, target);
        assertTrue(viewCfg.isPresent());
        assertEquals(22L, viewCfg.get().getConfigId());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> taxCalculationService.getBranchTaxConfigStrict(1L, target));
        assertTrue(ex.getMessage().contains("chồng hiệu lực"));
    }

    @Test
    @DisplayName("Kịch bản 19: PART_TIME_HOURLY / COLLABORATOR tham gia BHXH không bị tự miễn")
    void testInsurance_PartTimeAndCollaboratorParticipating() {
        StaffProfile partTime = createStaff(StaffContractType.PART_TIME_HOURLY, true, false, 0, false, new BigDecimal("10000000"));
        partTime.setInsuranceParticipationStatus(InsuranceParticipationStatus.PARTICIPATING);

        PitCalculationResult r1 = taxCalculationService.calculatePit(new BigDecimal("15000000"), BigDecimal.ZERO, partTime, LocalDate.of(2026, 7, 1));
        assertEquals(InsuranceParticipationStatus.PARTICIPATING, r1.getInsuranceParticipationStatus());
        assertTrue(Boolean.TRUE.equals(r1.getInsuranceVerified()));
        assertEquals(new BigDecimal("1050000"), r1.getInsuranceEmployeeDeduction());
        assertEquals(new BigDecimal("2150000"), r1.getInsuranceEmployerAmount());

        StaffProfile collab = createStaff(StaffContractType.COLLABORATOR, true, false, 0, false, new BigDecimal("8000000"));
        collab.setInsuranceParticipationStatus(InsuranceParticipationStatus.PARTICIPATING);
        PitCalculationResult r2 = taxCalculationService.calculatePit(new BigDecimal("12000000"), BigDecimal.ZERO, collab, LocalDate.of(2026, 7, 1));
        assertEquals(new BigDecimal("840000"), r2.getInsuranceEmployeeDeduction());
        assertEquals(new BigDecimal("1720000"), r2.getInsuranceEmployerAmount());
    }

    @Test
    @DisplayName("Kịch bản 20: NON_RESIDENT không tự quyết định miễn bảo hiểm")
    void testInsurance_NonResidentParticipating() {
        StaffProfile nonRes = createStaff(StaffContractType.NON_RESIDENT, false, false, 0, false, new BigDecimal("10000000"));
        nonRes.setInsuranceParticipationStatus(InsuranceParticipationStatus.PARTICIPATING);

        PitCalculationResult r = taxCalculationService.calculatePit(new BigDecimal("30000000"), BigDecimal.ZERO, nonRes, LocalDate.of(2026, 7, 1));
        assertEquals(InsuranceParticipationStatus.PARTICIPATING, r.getInsuranceParticipationStatus());
        assertTrue(Boolean.TRUE.equals(r.getInsuranceVerified()));
        assertEquals(new BigDecimal("1050000"), r.getInsuranceEmployeeDeduction());
        assertEquals(new BigDecimal("2150000"), r.getInsuranceEmployerAmount());
    }

    @Test
    @DisplayName("Kịch bản 21: Tỷ lệ bảo hiểm override riêng theo hồ sơ thay vì cứng 10.5%/21.5%")
    void testInsurance_CustomRateOverrideSnapshot() {
        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, new BigDecimal("10000000"));
        staff.setInsuranceParticipationStatus(InsuranceParticipationStatus.PARTICIPATING);
        staff.setInsuranceEmployeeRateOverride(new BigDecimal("9.50"));
        staff.setInsuranceEmployerRateOverride(new BigDecimal("20.50"));

        PitCalculationResult r = taxCalculationService.calculatePit(new BigDecimal("20000000"), BigDecimal.ZERO, staff, LocalDate.of(2026, 7, 1));
        assertEquals(new BigDecimal("950000"), r.getInsuranceEmployeeDeduction());
        assertEquals(new BigDecimal("2050000"), r.getInsuranceEmployerAmount());
        assertEquals(new BigDecimal("9.50"), r.getInsuranceEmployeeRateSnapshot());
        assertEquals(new BigDecimal("20.50"), r.getInsuranceEmployerRateSnapshot());
    }

    @Test
    @DisplayName("Kịch bản 22: Hồ sơ UNVERIFIED hoặc ngoài ngày hiệu lực không được coi là 0 hợp lệ")
    void testInsurance_UnverifiedOrOutOfEffectiveDate() {
        StaffProfile unverified = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, BigDecimal.ZERO);
        unverified.setInsuranceParticipationStatus(InsuranceParticipationStatus.UNVERIFIED);

        PitCalculationResult r1 = taxCalculationService.calculatePit(new BigDecimal("20000000"), BigDecimal.ZERO, unverified, LocalDate.of(2026, 7, 1));
        assertEquals(InsuranceParticipationStatus.UNVERIFIED, r1.getInsuranceParticipationStatus());
        assertFalse(Boolean.TRUE.equals(r1.getInsuranceVerified()));
        assertTrue(r1.getInsuranceBasisNote().contains("CHƯA ĐỦ CĂN CỨ"));

        StaffProfile expired = createStaff(StaffContractType.FULL_CONTRACT, true, true, 0, false, new BigDecimal("10000000"));
        expired.setInsuranceParticipationStatus(InsuranceParticipationStatus.PARTICIPATING);
        expired.setInsuranceEffectiveFrom(LocalDate.of(2026, 8, 1));
        PitCalculationResult r2 = taxCalculationService.calculatePit(new BigDecimal("20000000"), BigDecimal.ZERO, expired, LocalDate.of(2026, 7, 1));
        assertEquals(InsuranceParticipationStatus.UNVERIFIED, r2.getInsuranceParticipationStatus());
        assertFalse(Boolean.TRUE.equals(r2.getInsuranceVerified()));
        assertTrue(r2.getInsuranceBasisNote().contains("CHƯA ĐỦ CĂN CỨ"));
    }

    @Test
    @DisplayName("Kịch bản 23: Policy 2026 (15.5M/6.2M - biểu 5 bậc) vs Policy 2020 (11M/4.4M - biểu 7 bậc) theo kỳ")
    void testPitPolicyVersioning_2020Vs2026() {
        TaxPolicy policy2026 = new TaxPolicy();
        policy2026.setPolicyId(2L);
        policy2026.setPolicyCode("PIT_VN_2026");
        policy2026.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        policy2026.setPersonalRelief(new BigDecimal("15500000"));
        policy2026.setDependentRelief(new BigDecimal("6200000"));
        policy2026.setCasualTaxRate(new BigDecimal("10.0"));
        policy2026.setNonResidentTaxRate(new BigDecimal("20.0"));
        policy2026.setProgressiveSchedule("PROGRESSIVE_5_2026");
        policy2026.setInsuranceEmployeeRate(new BigDecimal("10.5"));
        policy2026.setInsuranceEmployerRate(new BigDecimal("21.5"));
        policy2026.setIsActive(true);

        LocalDate date2025 = LocalDate.of(2025, 12, 1);
        LocalDate date2026 = LocalDate.of(2026, 7, 1);
        when(taxPolicyRepository.findActivePoliciesAtDate(eq(date2025), any(Pageable.class)))
                .thenReturn(List.of(standardPolicy));
        when(taxPolicyRepository.findActivePoliciesAtDate(eq(date2026), any(Pageable.class)))
                .thenReturn(List.of(policy2026));

        StaffProfile staff = createStaff(StaffContractType.FULL_CONTRACT, true, true, 1, false, BigDecimal.ZERO);

        // Kỳ 2025: GTBT 11M + 1 NPT 4.4M = 15.4M. Gross 35.4M -> TNTT = 20M (7 bậc: 250k + 500k + 1.2M + 400k = 2,350,000)
        PitCalculationResult r2025 = taxCalculationService.calculatePitStrict(new BigDecimal("35400000"), BigDecimal.ZERO, staff, date2025);
        assertEquals("PIT_VN_2020", r2025.getTaxPolicyCode());
        assertEquals("PROGRESSIVE_7_2007", r2025.getCalculationMethod());
        assertEquals(new BigDecimal("20000000"), r2025.getAssessableIncome());
        assertEquals(new BigDecimal("2350000"), r2025.getPitAmount());

        // Kỳ 2026: GTBT 15.5M + 1 NPT 6.2M = 21.7M.
        // Test ranh giới Bậc 2 (TNTT = 30M -> Gross = 51.7M): 10M*5% + 20M*10% = 500k + 2M = 2,500,000
        PitCalculationResult r2026B2 = taxCalculationService.calculatePitStrict(new BigDecimal("51700000"), BigDecimal.ZERO, staff, date2026);
        assertEquals("PIT_VN_2026", r2026B2.getTaxPolicyCode());
        assertEquals("PROGRESSIVE_5_2026", r2026B2.getCalculationMethod());
        assertEquals(new BigDecimal("30000000"), r2026B2.getAssessableIncome());
        assertEquals(new BigDecimal("2500000"), r2026B2.getPitAmount());

        // Test ranh giới Bậc 5 (TNTT = 120M -> Gross = 141.7M): 500k + 2M + 30M*20%(6M) + 40M*30%(12M) + 20M*35%(7M) = 27,500,000
        PitCalculationResult r2026B5 = taxCalculationService.calculatePitStrict(new BigDecimal("141700000"), BigDecimal.ZERO, staff, date2026);
        assertEquals(new BigDecimal("120000000"), r2026B5.getAssessableIncome());
        assertEquals(new BigDecimal("27500000"), r2026B5.getPitAmount());
    }

    @Test
    @DisplayName("Kịch bản 24: Thiếu loại hợp đồng lao động bị đánh dấu chưa đủ căn cứ PIT (MISSING_CONTRACT_TYPE)")
    void testPit_MissingContractTypeNotVerified() {
        StaffProfile staff = createStaff(null, true, true, 0, false, BigDecimal.ZERO);
        PitCalculationResult r = taxCalculationService.calculatePit(new BigDecimal("30000000"), BigDecimal.ZERO, staff, LocalDate.of(2026, 7, 1));
        assertEquals("MISSING_CONTRACT_TYPE", r.getCalculationMethod());
        assertFalse(Boolean.TRUE.equals(r.getPitVerified()));
        assertTrue(r.getTaxCalculationDetail().contains("CHƯA ĐỦ CĂN CỨ PIT"));
    }
}
