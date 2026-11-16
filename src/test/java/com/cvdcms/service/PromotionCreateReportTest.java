package com.cvdcms.service;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreatePromotion")
@TestMethodOrder(MethodOrderer.MethodName.class)
class PromotionCreateReportTest {

    @Mock PromotionRepository promos;@Mock PatientVoucherRepository vouchers;@Mock PatientProfileRepository patients;
    @Mock MembershipTierRepository tiers;@Mock ServiceRepository services;@Mock BranchRepository branches;
    PromotionServiceImpl service;
    @BeforeEach void setup(){service=new PromotionServiceImpl(promos,vouchers,patients,tiers,services,branches);}
    PromotionDTO dto(){PromotionDTO d=new PromotionDTO();d.setPromoCode("SALE");d.setPromoName(" Ưu đãi ");d.setPromoType(PromotionType.PERCENTAGE);d.setDiscountValue(new BigDecimal("10"));d.setMinBillAmount(BigDecimal.ZERO);d.setStartDate(LocalDateTime.of(2000,1,1,0,0));d.setEndDate(LocalDateTime.of(2099,1,1,0,0));d.setIsActive(true);return d;}
    Promotion promo(){Promotion p=new Promotion();p.setPromotionId(1L);p.setPromoCode("SALE");p.setPromoName("Ưu đãi");p.setPromoType(PromotionType.PERCENTAGE);p.setDiscountValue(new BigDecimal("10"));p.setMinBillAmount(BigDecimal.ZERO);p.setStartDate(LocalDateTime.of(2000,1,1,0,0));p.setEndDate(LocalDateTime.of(2099,1,1,0,0));p.setIsActive(true);p.setUsedCount(0);return p;}
    void saveReturnsInput(){when(promos.save(any(Promotion.class))).thenAnswer(i->i.getArgument(0));}
    PatientProfile patient(){PatientProfile p=new PatientProfile();p.setProfileId(10L);p.setPatientCode("BN10");p.setFullName("Nguyễn Văn A");return p;}
    Branch branch(long id){Branch b=new Branch();b.setBranchId(id);b.setBranchName("CN"+id);return b;}
    MembershipTier tier(String spent){MembershipTier t=new MembershipTier();t.setTierId(1L);t.setTierName("GOLD");t.setMinTotalSpent(new BigDecimal(spent));return t;}
    IssueVoucherDTO issue(){IssueVoucherDTO d=new IssueVoucherDTO();d.setPromotionId(1L);d.setPatientProfileIds(List.of(10L));d.setExpiresAt(LocalDateTime.of(2098,1,1,0,0));return d;}
 

    @Test
    @DisplayName("UTCID01 - N - Tạo khuyến mãi, trim tên và đặt usedCount bằng 0")
    void UTCID01() throws Exception {
        saveReturnsInput();Promotion r=service.createPromotion(dto());assertEquals("SALE",r.getPromoCode());assertEquals("Ưu đãi",r.getPromoName());assertEquals(Integer.valueOf(0),r.getUsedCount());assertTrue(r.getIsActive());verify(promos).save(r);
    }

    @Test
    @DisplayName("UTCID02 - N - Chuẩn hóa mã và mặc định các giá trị null")
    void UTCID02() throws Exception {
        saveReturnsInput();PromotionDTO d=dto();d.setPromoCode(" sale ");d.setMinBillAmount(null);d.setIsActive(null);Promotion r=service.createPromotion(d);assertEquals("SALE",r.getPromoCode());assertEquals(0,r.getMinBillAmount().compareTo(BigDecimal.ZERO));assertTrue(r.getIsActive());
    }

    @Test
    @DisplayName("UTCID03 - A - DTO khuyến mãi null")
    void UTCID03() throws Exception {
        assertThrows(IllegalArgumentException.class,()->service.createPromotion(null));verifyNoInteractions(promos);
    }

    @Test
    @DisplayName("UTCID04 - A - Mã khuyến mãi trùng")
    void UTCID04() throws Exception {
        when(promos.existsByPromoCode("SALE")).thenReturn(true);assertThrows(IllegalArgumentException.class,()->service.createPromotion(dto()));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID05 - A - Thiếu ngày bắt đầu")
    void UTCID05() throws Exception {
        PromotionDTO d=dto();d.setStartDate(null);assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID06 - A - Thiếu ngày kết thúc")
    void UTCID06() throws Exception {
        PromotionDTO d=dto();d.setEndDate(null);assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID07 - A - Ngày kết thúc trước ngày bắt đầu")
    void UTCID07() throws Exception {
        PromotionDTO d=dto();d.setStartDate(LocalDateTime.of(2030,1,2,0,0));d.setEndDate(LocalDateTime.of(2030,1,1,0,0));assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID08 - A - Giảm phần trăm null")
    void UTCID08() throws Exception {
        PromotionDTO d=dto();d.setDiscountValue(null);assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID09 - B - Giảm phần trăm bằng 0")
    void UTCID09() throws Exception {
        PromotionDTO d=dto();d.setDiscountValue(BigDecimal.ZERO);assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID10 - B - Giảm phần trăm vượt 100")
    void UTCID10() throws Exception {
        PromotionDTO d=dto();d.setDiscountValue(new BigDecimal("100.01"));assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID11 - B - Ngưỡng hóa đơn âm")
    void UTCID11() throws Exception {
        PromotionDTO d=dto();d.setMinBillAmount(new BigDecimal("-0.01"));assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID12 - B - Giới hạn lượt bằng 0")
    void UTCID12() throws Exception {
        PromotionDTO d=dto();d.setUsageLimit(0);assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID13 - B - Phần trăm đúng 100")
    void UTCID13() throws Exception {
        saveReturnsInput();PromotionDTO d=dto();d.setDiscountValue(new BigDecimal("100"));Promotion r=service.createPromotion(d);assertEquals(d.getDiscountValue(),r.getDiscountValue());assertEquals(d.getUsageLimit(),r.getUsageLimit());assertEquals(d.getPromoType(),r.getPromoType());
    }

    @Test
    @DisplayName("UTCID14 - B - Giới hạn lượt đúng 1")
    void UTCID14() throws Exception {
        saveReturnsInput();PromotionDTO d=dto();d.setUsageLimit(1);Promotion r=service.createPromotion(d);assertEquals(d.getDiscountValue(),r.getDiscountValue());assertEquals(d.getUsageLimit(),r.getUsageLimit());assertEquals(d.getPromoType(),r.getPromoType());
    }

    @Test
    @DisplayName("UTCID15 - N - Giảm tiền cố định lớn hơn 0")
    void UTCID15() throws Exception {
        saveReturnsInput();PromotionDTO d=dto();d.setPromoType(PromotionType.FIXED_AMOUNT);d.setDiscountValue(new BigDecimal("10000"));Promotion r=service.createPromotion(d);assertEquals(d.getDiscountValue(),r.getDiscountValue());assertEquals(d.getUsageLimit(),r.getUsageLimit());assertEquals(d.getPromoType(),r.getPromoType());
    }

    @Test
    @DisplayName("UTCID16 - A - Không tìm thấy chi nhánh được chọn")
    void UTCID16() throws Exception {
        PromotionDTO d=dto();d.setBranchId(99L);assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID17 - A - Không tìm thấy hạng thành viên được chọn")
    void UTCID17() throws Exception {
        PromotionDTO d=dto();d.setApplicableTierId(99L);assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }

    @Test
    @DisplayName("UTCID18 - A - Không tìm thấy dịch vụ được chọn")
    void UTCID18() throws Exception {
        PromotionDTO d=dto();d.setApplicableServiceId(99L);assertThrows(IllegalArgumentException.class,()->service.createPromotion(d));verify(promos,never()).save(any());
    }
}
