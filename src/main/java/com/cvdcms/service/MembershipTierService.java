package com.cvdcms.service;

import com.cvdcms.DTO.MembershipTierDTO;
import com.cvdcms.entity.MembershipTier;

import java.math.BigDecimal;
import java.util.List;

public interface MembershipTierService {

    List<MembershipTier> getAllTiers();

    List<MembershipTier> getActiveTiers();

    MembershipTier getTierById(Long tierId);

    MembershipTier getTierByCode(String tierCode);

    MembershipTier createTier(MembershipTierDTO dto);

    MembershipTier updateTier(Long tierId, MembershipTierDTO dto);

    MembershipTier toggleTierStatus(Long tierId);

    MembershipTier findHighestQualifyingTier(BigDecimal totalSpent);

    void recalculatePatientTier(Long profileId);
}
