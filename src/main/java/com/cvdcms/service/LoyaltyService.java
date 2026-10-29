package com.cvdcms.service;

import com.cvdcms.DTO.MemberCardDTO;
import com.cvdcms.DTO.PointAdjustmentDTO;
import com.cvdcms.entity.PatientLoyaltyWallet;
import com.cvdcms.entity.Payment;
import com.cvdcms.entity.PointTransaction;
import com.cvdcms.entity.Refund;

import java.util.List;

public interface LoyaltyService {

    PatientLoyaltyWallet getOrCreateWallet(Long profileId);

    MemberCardDTO getMemberCard(Long profileId);

    List<PointTransaction> getPointHistory(Long profileId);

    PointTransaction recordPaymentPoints(Payment payment);

    PointTransaction recordRefundAdjustment(Refund refund);

    PointTransaction adjustPoints(PointAdjustmentDTO dto, Long staffUserId);

    List<MemberCardDTO> searchMemberCards(String keyword, Long branchId);
}
