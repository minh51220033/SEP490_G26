package com.cvdcms.service;

import com.cvdcms.DTO.MembershipTierDTO;
import com.cvdcms.entity.MembershipTier;
import com.cvdcms.repository.MembershipTierRepository;
import com.cvdcms.repository.PatientLoyaltyWalletRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.service.impl.MembershipTierServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Service-level tests against MembershipTierServiceImpl in DB(8).zip.
 * These tests invoke the implementation directly, without a Spring context.
 * Bean Validation annotations on MembershipTierDTO are not executed by this
 * service method; DTO validation requires separate validator/controller tests.
 * The service checks spendPerPoint > 0, while the DTO requires >= 1000.
 * This suite does not establish that every DTO constraint is enforced here.
 * Expected classification: N = 5, A = 6, B = 9; total = 20.
 * Run locally before recording execution results in the report.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateTier")
class MembershipTierCreateReportTest {

    @Mock private MembershipTierRepository membershipTierRepository;
    @Mock private PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    @Mock private PatientProfileRepository patientProfileRepository;

    private MembershipTierServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MembershipTierServiceImpl(membershipTierRepository,
                patientLoyaltyWalletRepository, patientProfileRepository);
    }

    private MembershipTierDTO validInput() {
        MembershipTierDTO dto = new MembershipTierDTO();
        dto.setTierCode("GOLD");
        dto.setTierName("Vàng");
        dto.setMinTotalSpent(new BigDecimal("20000000.00"));
        dto.setDiscountPercent(new BigDecimal("10.00"));
        dto.setSpendPerPoint(new BigDecimal("10000.00"));
        dto.setPointValue(new BigDecimal("10.00"));
        dto.setDisplayOrder(3);
        dto.setIsActive(true);
        return dto;
    }

    private void assertCreated(MembershipTierDTO dto) {
        String expectedCode = dto.getTierCode().trim().toUpperCase();
        when(membershipTierRepository.existsByTierCode(expectedCode)).thenReturn(false);
        when(membershipTierRepository.save(any(MembershipTier.class))).thenAnswer(invocation -> {
            MembershipTier entity = invocation.getArgument(0);
            entity.setTierId(1L);
            return entity;
        });

        MembershipTier result = service.createTier(dto);

        ArgumentCaptor<MembershipTier> captor = ArgumentCaptor.forClass(MembershipTier.class);
        verify(membershipTierRepository).save(captor.capture());
        verify(membershipTierRepository).existsByTierCode(expectedCode);
        assertNotNull(result);
        assertSame(captor.getValue(), result);
        assertEquals(Long.valueOf(1L), result.getTierId());
        assertEquals(expectedCode, result.getTierCode());
        assertEquals(dto.getTierName().trim(), result.getTierName());
        assertEquals(0, dto.getMinTotalSpent().compareTo(result.getMinTotalSpent()));
        assertEquals(0, dto.getDiscountPercent().compareTo(result.getDiscountPercent()));
        assertEquals(0, dto.getSpendPerPoint().compareTo(result.getSpendPerPoint()));
        assertEquals(0, dto.getPointValue().compareTo(result.getPointValue()));
        assertEquals(dto.getDisplayOrder(), result.getDisplayOrder());
        assertEquals(dto.getIsActive() == null ? Boolean.TRUE : dto.getIsActive(), result.getIsActive());
        verifyNoInteractions(patientLoyaltyWalletRepository, patientProfileRepository);
    }

    private void assertRejected(MembershipTierDTO dto, String expectedMessage) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTier(dto));
        assertEquals(expectedMessage, error.getMessage());
        verify(membershipTierRepository, never()).save(any(MembershipTier.class));
        verifyNoInteractions(patientLoyaltyWalletRepository, patientProfileRepository);
    }

    @Test
    @DisplayName("UTCID01 (N) - Tạo hạng hợp lệ, trả về và lưu đúng dữ liệu")
    void UTCID01_validInput_returnsSavedTier() {
        assertCreated(validInput());
    }

    @Test
    @DisplayName("UTCID02 (N) - Chuẩn hóa mã chữ thường và khoảng trắng mã, tên")
    void UTCID02_normalizesCodeAndTrimsName() {
        MembershipTierDTO dto = validInput();
        dto.setTierCode(" gold ");
        dto.setTierName(" Vàng ");
        assertCreated(dto);
    }

    @Test
    @DisplayName("UTCID03 (N) - Tạo hạng có isActive = false")
    void UTCID03_inactiveTier_persistsFalse() {
        MembershipTierDTO dto = validInput();
        dto.setIsActive(false);
        assertCreated(dto);
    }

    @Test
    @DisplayName("UTCID04 (N) - isActive null được mặc định thành true")
    void UTCID04_nullActive_defaultsToTrue() {
        MembershipTierDTO dto = validInput();
        dto.setIsActive(null);
        assertCreated(dto);
    }

    @Test
    @DisplayName("UTCID05 (A) - Từ chối mã hạng đã tồn tại")
    void UTCID05_duplicateCode_rejected() {
        when(membershipTierRepository.existsByTierCode("GOLD")).thenReturn(true);
        assertRejected(validInput(), "Mã hạng thành viên 'GOLD' đã tồn tại trong hệ thống.");
    }

    @Test
    @DisplayName("UTCID06 (A) - Từ chối DTO null")
    void UTCID06_nullDto_rejected() {
        assertRejected(null, "Dữ liệu hạng thành viên không được rỗng.");
        verify(membershipTierRepository, never()).existsByTierCode(any());
    }

    @Test
    @DisplayName("UTCID07 (A) - Ngưỡng chi tiêu null")
    void UTCID07_nullMinimumSpending_rejected() {
        MembershipTierDTO dto = validInput();
        dto.setMinTotalSpent(null);
        assertRejected(dto, "Ngưỡng chi tiêu tối thiểu phải >= 0 đ.");
    }

    @Test
    @DisplayName("UTCID08 (B) - Ngưỡng chi tiêu -0.01")
    void UTCID08_negativeMinimumSpending_rejected() {
        MembershipTierDTO dto = validInput();
        dto.setMinTotalSpent(new BigDecimal("-0.01"));
        assertRejected(dto, "Ngưỡng chi tiêu tối thiểu phải >= 0 đ.");
    }

    @Test
    @DisplayName("UTCID09 (B) - Ngưỡng chi tiêu bằng 0")
    void UTCID09_zeroMinimumSpending_accepted() {
        MembershipTierDTO dto = validInput();
        dto.setMinTotalSpent(BigDecimal.ZERO);
        assertCreated(dto);
    }

    @Test
    @DisplayName("UTCID10 (A) - Tỷ lệ chiết khấu null")
    void UTCID10_nullDiscount_rejected() {
        MembershipTierDTO dto = validInput();
        dto.setDiscountPercent(null);
        assertRejected(dto, "Tỷ lệ chiết khấu phải từ 0% đến 100%.");
    }

    @Test
    @DisplayName("UTCID11 (B) - Tỷ lệ chiết khấu -0.01%")
    void UTCID11_negativeDiscount_rejected() {
        MembershipTierDTO dto = validInput();
        dto.setDiscountPercent(new BigDecimal("-0.01"));
        assertRejected(dto, "Tỷ lệ chiết khấu phải từ 0% đến 100%.");
    }

    @Test
    @DisplayName("UTCID12 (B) - Tỷ lệ chiết khấu bằng 0%")
    void UTCID12_zeroDiscount_accepted() {
        MembershipTierDTO dto = validInput();
        dto.setDiscountPercent(BigDecimal.ZERO);
        assertCreated(dto);
    }

    @Test
    @DisplayName("UTCID13 (B) - Tỷ lệ chiết khấu đúng 100%")
    void UTCID13_hundredPercentDiscount_accepted() {
        MembershipTierDTO dto = validInput();
        dto.setDiscountPercent(new BigDecimal("100.00"));
        assertCreated(dto);
    }

    @Test
    @DisplayName("UTCID14 (B) - Tỷ lệ chiết khấu vượt biên 100.01%")
    void UTCID14_discountAboveMaximum_rejected() {
        MembershipTierDTO dto = validInput();
        dto.setDiscountPercent(new BigDecimal("100.01"));
        assertRejected(dto, "Tỷ lệ chiết khấu phải từ 0% đến 100%.");
    }

    @Test
    @DisplayName("UTCID15 (A) - Mức chi tiêu đổi 1 điểm null")
    void UTCID15_nullSpendPerPoint_rejected() {
        MembershipTierDTO dto = validInput();
        dto.setSpendPerPoint(null);
        assertRejected(dto, "Mức chi tiêu đổi 1 điểm phải lớn hơn 0 đ.");
    }

    @Test
    @DisplayName("UTCID16 (B) - Mức chi tiêu đổi 1 điểm bằng 0")
    void UTCID16_zeroSpendPerPoint_rejected() {
        MembershipTierDTO dto = validInput();
        dto.setSpendPerPoint(BigDecimal.ZERO);
        assertRejected(dto, "Mức chi tiêu đổi 1 điểm phải lớn hơn 0 đ.");
    }

    @Test
    @DisplayName("UTCID17 (N) - Mức chi tiêu đổi 1 điểm bằng 1000")
    void UTCID17_spendPerPointAt1000_accepted() {
        MembershipTierDTO dto = validInput();
        dto.setSpendPerPoint(new BigDecimal("1000.00"));
        assertCreated(dto);
    }

    @Test
    @DisplayName("UTCID18 (A) - Giá trị quy đổi 1 điểm null")
    void UTCID18_nullPointValue_rejected() {
        MembershipTierDTO dto = validInput();
        dto.setPointValue(null);
        assertRejected(dto, "Giá trị quy đổi 1 điểm phải >= 0 đ.");
    }

    @Test
    @DisplayName("UTCID19 (B) - Giá trị quy đổi 1 điểm -0.01")
    void UTCID19_negativePointValue_rejected() {
        MembershipTierDTO dto = validInput();
        dto.setPointValue(new BigDecimal("-0.01"));
        assertRejected(dto, "Giá trị quy đổi 1 điểm phải >= 0 đ.");
    }

    @Test
    @DisplayName("UTCID20 (B) - Giá trị quy đổi 1 điểm bằng 0")
    void UTCID20_zeroPointValue_accepted() {
        MembershipTierDTO dto = validInput();
        dto.setPointValue(BigDecimal.ZERO);
        assertCreated(dto);
    }
}
