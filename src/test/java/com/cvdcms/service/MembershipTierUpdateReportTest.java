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
@DisplayName("UpdateTier")
@TestMethodOrder(MethodOrderer.MethodName.class)
class MembershipTierUpdateReportTest {

    @Mock MembershipTierRepository tiers;@Mock PatientLoyaltyWalletRepository wallets;@Mock PatientProfileRepository patients;
    MembershipTierServiceImpl service;
    @BeforeEach void setup(){service=new MembershipTierServiceImpl(tiers,wallets,patients);}
    MembershipTier tier(long id,String code,String spent){MembershipTier t=new MembershipTier();t.setTierId(id);t.setTierCode(code);t.setTierName(code);t.setMinTotalSpent(new BigDecimal(spent));t.setDiscountPercent(BigDecimal.ZERO);t.setSpendPerPoint(new BigDecimal("10000"));t.setPointValue(new BigDecimal("10"));t.setIsActive(true);t.setDisplayOrder((int)id);return t;}
    MembershipTierDTO dto(){MembershipTierDTO d=new MembershipTierDTO();d.setTierCode("SILVER");d.setTierName(" Bạc ");d.setMinTotalSpent(new BigDecimal("5000000"));d.setDiscountPercent(new BigDecimal("5"));d.setSpendPerPoint(new BigDecimal("10000"));d.setPointValue(new BigDecimal("10"));d.setDisplayOrder(2);d.setIsActive(true);return d;}
    void sameSave(){when(tiers.save(any(MembershipTier.class))).thenAnswer(i->i.getArgument(0));}
 

    @Test
    @DisplayName("UTCID01 - N - Sửa hạng giữ ID và cập nhật trường")
    void UTCID01() throws Exception {
        MembershipTier t=tier(2L,"SILVER","5000000");when(tiers.findById(2L)).thenReturn(Optional.of(t));sameSave();MembershipTier r=service.updateTier(2L,dto());assertSame(t,r);assertEquals(Long.valueOf(2L),r.getTierId());assertEquals("Bạc",r.getTierName());assertEquals(0,new BigDecimal("5").compareTo(r.getDiscountPercent()));verify(tiers).save(t);
    }

    @Test
    @DisplayName("UTCID02 - A - DTO cập nhật null")
    void UTCID02() throws Exception {
        assertThrows(IllegalArgumentException.class,()->service.updateTier(2L,null));verifyNoInteractions(tiers);
    }

    @Test
    @DisplayName("UTCID03 - A - Hạng cập nhật không tồn tại")
    void UTCID03() throws Exception {
        assertThrows(IllegalArgumentException.class,()->service.updateTier(2L,dto()));verify(tiers,never()).save(any());
    }

    @Test
    @DisplayName("UTCID04 - A - Mã cập nhật trùng hạng khác")
    void UTCID04() throws Exception {
        when(tiers.findById(2L)).thenReturn(Optional.of(tier(2L,"SILVER","5000000")));when(tiers.existsByTierCodeAndTierIdNot("SILVER",2L)).thenReturn(true);assertThrows(IllegalArgumentException.class,()->service.updateTier(2L,dto()));verify(tiers,never()).save(any());
    }

    @Test
    @DisplayName("UTCID05 - N - isActive null giữ nguyên trạng thái")
    void UTCID05() throws Exception {
        MembershipTier t=tier(2L,"SILVER","5000000");t.setIsActive(false);when(tiers.findById(2L)).thenReturn(Optional.of(t));sameSave();MembershipTierDTO d=dto();d.setIsActive(null);assertFalse(service.updateTier(2L,d).getIsActive());
    }

    @Test
    @DisplayName("UTCID06 - A - Không tắt hạng cơ sở duy nhất")
    void UTCID06() throws Exception {
        MembershipTier t=tier(1L,"BASE","0");when(tiers.findById(1L)).thenReturn(Optional.of(t));when(tiers.findByIsActiveTrueOrderByMinTotalSpentAsc()).thenReturn(List.of(t));MembershipTierDTO d=dto();d.setMinTotalSpent(BigDecimal.ZERO);d.setIsActive(false);assertThrows(IllegalStateException.class,()->service.updateTier(1L,d));verify(tiers,never()).save(any());
    }

    @Test
    @DisplayName("UTCID07 - N - Có hạng cơ sở thay thế thì cho ngừng")
    void UTCID07() throws Exception {
        MembershipTier t=tier(1L,"BASE","0");MembershipTier replacement=tier(3L,"OTHER","0");when(tiers.findById(1L)).thenReturn(Optional.of(t));when(tiers.findByIsActiveTrueOrderByMinTotalSpentAsc()).thenReturn(List.of(t,replacement));sameSave();MembershipTierDTO d=dto();d.setMinTotalSpent(BigDecimal.ZERO);d.setIsActive(false);assertFalse(service.updateTier(1L,d).getIsActive());
    }

    @Test
    @DisplayName("UTCID08 - B - Ngưỡng chi tiêu âm")
    void UTCID08() throws Exception {
        when(tiers.findById(2L)).thenReturn(Optional.of(tier(2L,"SILVER","5000000")));MembershipTierDTO d=dto();d.setMinTotalSpent(new BigDecimal("-0.01"));assertThrows(IllegalArgumentException.class,()->service.updateTier(2L,d));verify(tiers,never()).save(any());
    }

    @Test
    @DisplayName("UTCID09 - B - Chiết khấu vượt 100%")
    void UTCID09() throws Exception {
        when(tiers.findById(2L)).thenReturn(Optional.of(tier(2L,"SILVER","5000000")));MembershipTierDTO d=dto();d.setDiscountPercent(new BigDecimal("100.01"));assertThrows(IllegalArgumentException.class,()->service.updateTier(2L,d));verify(tiers,never()).save(any());
    }

    @Test
    @DisplayName("UTCID10 - B - Chi tiêu đổi điểm bằng 0")
    void UTCID10() throws Exception {
        when(tiers.findById(2L)).thenReturn(Optional.of(tier(2L,"SILVER","5000000")));MembershipTierDTO d=dto();d.setSpendPerPoint(BigDecimal.ZERO);assertThrows(IllegalArgumentException.class,()->service.updateTier(2L,d));verify(tiers,never()).save(any());
    }

    @Test
    @DisplayName("UTCID11 - B - Giá trị điểm âm")
    void UTCID11() throws Exception {
        when(tiers.findById(2L)).thenReturn(Optional.of(tier(2L,"SILVER","5000000")));MembershipTierDTO d=dto();d.setPointValue(new BigDecimal("-0.01"));assertThrows(IllegalArgumentException.class,()->service.updateTier(2L,d));verify(tiers,never()).save(any());
    }
}
