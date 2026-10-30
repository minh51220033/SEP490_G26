package com.cvdcms.service;

import com.cvdcms.DTO.IssueVoucherDTO;
import com.cvdcms.DTO.PromotionDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.PatientVoucher;
import com.cvdcms.entity.Promotion;

import java.math.BigDecimal;
import java.util.List;

public interface PromotionService {

    List<Promotion> getAllPromotions(String keyword, String status, Long branchId);

    Promotion getPromotionById(Long promotionId);

    Promotion getPromotionByCode(String promoCode);

    Promotion createPromotion(PromotionDTO dto);

    Promotion updatePromotion(Long promotionId, PromotionDTO dto);

    Promotion togglePromotionStatus(Long promotionId);

    List<PatientVoucher> issueVouchers(IssueVoucherDTO dto);

    List<PatientVoucher> getVouchersByPromotion(Long promotionId);

    List<PatientVoucher> getAvailableVouchersForPatient(Long profileId);

    BigDecimal calculateDiscount(Promotion promo, BigDecimal billAmount);

    void validatePromotionForInvoice(Promotion promo, PatientProfile patient, BigDecimal billAmount, List<Long> serviceIds, Branch branch);
}
