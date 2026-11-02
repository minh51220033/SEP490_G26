package com.cvdcms.patient;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.entity.PatientProfile;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO tổng hợp thông tin hồ sơ khách hàng theo kiến trúc DentalSoft & CV-DCMS.
 */
@Getter
@Setter
@NoArgsConstructor
public class PatientOverviewDTO {
    private Long id;
    private String code;
    private String fullName;
    private LocalDate dob;
    private String gender;
    private String phoneNumber;
    private String address;
    private Long branchId;
    private String branchName;
    private String status;
    private String customerSource;
    private String idCardNumber;
    private String healthInsuranceNumber;
    private String guardianName;
    private String guardianPhone;
    private String guardianRelationship;
    private String avatarUrl;
    private String allergyDetails;
    private String underlyingDiseaseDetails;
    private String cardiovascularDetails;
    private Boolean hasAllergies;
    private Boolean hasUnderlyingDisease;
    private Boolean hasCardiovascularDisease;
    private Boolean medicalHistoryCompleted;
    private String membershipTierName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Chỉ số tài chính (FT21, FT22, FT24)
    private BigDecimal depositBalance = BigDecimal.ZERO;
    private BigDecimal totalBilled = BigDecimal.ZERO;
    private BigDecimal totalPaid = BigDecimal.ZERO;
    private BigDecimal totalDebt = BigDecimal.ZERO;
    private int unpaidInvoicesCount = 0;

    public static PatientOverviewDTO from(PatientProfile p, PatientDebtSummaryDTO debt) {
        if (p == null) return null;
        PatientOverviewDTO dto = new PatientOverviewDTO();
        dto.setId(p.getProfileId());
        dto.setCode(p.getPatientCode());
        dto.setFullName(p.getFullName());
        dto.setDob(p.getDob());
        dto.setGender(p.getGender());
        dto.setPhoneNumber(p.getPhoneNumber());
        dto.setAddress(p.getAddress());
        if (p.getPreferredBranch() != null) {
            dto.setBranchId(p.getPreferredBranch().getBranchId());
            dto.setBranchName(p.getPreferredBranch().getBranchName());
        }
        dto.setStatus(p.getStatus());
        dto.setCustomerSource(p.getCustomerSource() != null ? p.getCustomerSource() : "Trực tiếp");
        dto.setIdCardNumber(p.getIdCardNumber());
        dto.setHealthInsuranceNumber(p.getHealthInsuranceNumber());
        dto.setGuardianName(p.getGuardianName());
        dto.setGuardianPhone(p.getGuardianPhone());
        dto.setGuardianRelationship(p.getGuardianRelationship());
        dto.setAvatarUrl(p.getAvatarUrl());
        dto.setAllergyDetails(p.getAllergyDetails());
        dto.setUnderlyingDiseaseDetails(p.getUnderlyingDiseaseDetails());
        dto.setCardiovascularDetails(p.getCardiovascularDetails());
        dto.setHasAllergies(p.getHasAllergies());
        dto.setHasUnderlyingDisease(p.getHasUnderlyingDisease());
        dto.setHasCardiovascularDisease(p.getHasCardiovascularDisease());
        dto.setMedicalHistoryCompleted(p.getMedicalHistoryCompleted());
        if (p.getMembershipTier() != null) {
            dto.setMembershipTierName(p.getMembershipTier().getTierName());
        }
        dto.setCreatedAt(p.getCreatedAt());
        dto.setUpdatedAt(p.getUpdatedAt());

        if (debt != null) {
            dto.setDepositBalance(debt.getDepositBalance() != null ? debt.getDepositBalance() : BigDecimal.ZERO);
            dto.setTotalBilled(debt.getTotalBilled() != null ? debt.getTotalBilled() : BigDecimal.ZERO);
            dto.setTotalPaid(debt.getTotalPaid() != null ? debt.getTotalPaid() : BigDecimal.ZERO);
            dto.setTotalDebt(debt.getTotalDebt() != null ? debt.getTotalDebt() : BigDecimal.ZERO);
            dto.setUnpaidInvoicesCount(debt.getUnpaidInvoicesCount());
        } else if (p.getDepositBalance() != null) {
            dto.setDepositBalance(p.getDepositBalance());
        }
        return dto;
    }

    public static PatientOverviewDTO from(ReceptionPatientRecord r, PatientDebtSummaryDTO debt) {
        if (r == null) return null;
        PatientOverviewDTO dto = new PatientOverviewDTO();
        dto.setId(r.getProfileId());
        dto.setCode(r.getPatientCode());
        dto.setFullName(r.getFullName());
        dto.setDob(r.getDob());
        dto.setGender(r.getGender());
        dto.setPhoneNumber(r.getPhoneNumber());
        dto.setAddress(r.getAddress());
        if (r.getPreferredBranch() != null) {
            dto.setBranchId(r.getPreferredBranch().getBranchId());
            dto.setBranchName(r.getPreferredBranch().getBranchName());
        }
        dto.setStatus(r.getStatus());
        dto.setCustomerSource(r.getCustomerSource() != null ? r.getCustomerSource() : "Trực tiếp");
        dto.setIdCardNumber(r.getIdCardNumber());
        dto.setHealthInsuranceNumber(r.getHealthInsuranceNumber());
        dto.setGuardianName(r.getGuardianName());
        dto.setGuardianPhone(r.getGuardianPhone());
        dto.setGuardianRelationship(r.getGuardianRelationship());
        dto.setAvatarUrl(r.getAvatarUrl());
        dto.setAllergyDetails(r.getAllergyDetails());
        dto.setUnderlyingDiseaseDetails(r.getUnderlyingDiseaseDetails());
        dto.setCardiovascularDetails(null);
        dto.setHasAllergies(r.getHasAllergies());
        dto.setHasUnderlyingDisease(r.getHasUnderlyingDisease());
        dto.setHasCardiovascularDisease(false);
        dto.setMedicalHistoryCompleted(r.getMedicalHistoryCompleted());
        dto.setMembershipTierName(null);
        dto.setCreatedAt(r.getCreatedAt());
        dto.setUpdatedAt(r.getUpdatedAt());

        if (debt != null) {
            dto.setDepositBalance(debt.getDepositBalance() != null ? debt.getDepositBalance() : BigDecimal.ZERO);
            dto.setTotalBilled(debt.getTotalBilled() != null ? debt.getTotalBilled() : BigDecimal.ZERO);
            dto.setTotalPaid(debt.getTotalPaid() != null ? debt.getTotalPaid() : BigDecimal.ZERO);
            dto.setTotalDebt(debt.getTotalDebt() != null ? debt.getTotalDebt() : BigDecimal.ZERO);
            dto.setUnpaidInvoicesCount(debt.getUnpaidInvoicesCount());
        } else if (r.getDepositBalance() != null) {
            dto.setDepositBalance(r.getDepositBalance());
        }
        return dto;
    }
}

