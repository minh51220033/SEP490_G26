package com.cvdcms.service.impl;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
import com.cvdcms.repository.InvoiceRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.service.DebtService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DebtServiceImpl implements DebtService {

    private final InvoiceRepository invoiceRepository;
    private final PatientProfileRepository patientProfileRepository;

    public DebtServiceImpl(InvoiceRepository invoiceRepository, PatientProfileRepository patientProfileRepository) {
        this.invoiceRepository = invoiceRepository;
        this.patientProfileRepository = patientProfileRepository;
    }

    @Override
    public Page<PatientDebtSummaryDTO> getPatientDebts(Long branchId, String keyword, Pageable pageable) {
        List<Long> profileIds = invoiceRepository.findPatientProfileIdsWithDebt(branchId);
        if (profileIds.isEmpty()) {
            return Page.empty(pageable);
        }

        List<PatientProfile> allPatients = patientProfileRepository.findAllById(profileIds);
        List<PatientDebtSummaryDTO> summaries = new ArrayList<>();

        for (PatientProfile patient : allPatients) {
            if (keyword != null && !keyword.trim().isEmpty()) {
                String kw = keyword.trim().toLowerCase();
                boolean match = (patient.getFullName() != null && patient.getFullName().toLowerCase().contains(kw))
                        || (patient.getPatientCode() != null && patient.getPatientCode().toLowerCase().contains(kw))
                        || (patient.getPhoneNumber() != null && patient.getPhoneNumber().contains(kw));
                if (!match) {
                    continue;
                }
            }

            PatientDebtSummaryDTO summary = getPatientDebtDetail(patient.getProfileId());
            if (summary.getTotalDebt().compareTo(BigDecimal.ZERO) > 0) {
                summaries.add(summary);
            }
        }

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), summaries.size());
        List<PatientDebtSummaryDTO> subList = (start <= end && start < summaries.size())
                ? summaries.subList(start, end)
                : new ArrayList<>();

        return new PageImpl<>(subList, pageable, summaries.size());
    }

    @Override
    public PatientDebtSummaryDTO getPatientDebtDetail(Long profileId) {
        return getPatientDebtDetail(profileId, null);
    }

    @Override
    public PatientDebtSummaryDTO getPatientDebtDetail(Long profileId, List<Long> branchIds) {
        PatientProfile patient = patientProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân ID: " + profileId));

        BigDecimal totalBilled;
        BigDecimal totalPaid;
        BigDecimal totalDebt;
        List<Invoice> unpaidInvoices;
        List<Invoice> validInvoices;

        if (branchIds != null && !branchIds.isEmpty()) {
            totalBilled = invoiceRepository.sumTotalInvoicedByPatientAndBranches(profileId, branchIds);
            totalPaid = invoiceRepository.sumTotalPaidByPatientAndBranches(profileId, branchIds);
            totalDebt = invoiceRepository.sumTotalDebtByPatientAndBranches(profileId, branchIds);
            unpaidInvoices = invoiceRepository.findUnpaidInvoicesByPatientAndBranches(profileId, branchIds);
            validInvoices = invoiceRepository.findInvoicesByPatientAndBranches(profileId, branchIds);
        } else {
            totalBilled = invoiceRepository.sumTotalInvoicedByPatient(profileId);
            totalPaid = invoiceRepository.sumTotalPaidByPatient(profileId);
            totalDebt = invoiceRepository.sumTotalDebtByPatient(profileId);
            unpaidInvoices = invoiceRepository.findByPatientProfile_ProfileIdAndPaymentStatusIn(
                    profileId,
                    List.of(InvoicePaymentStatus.UNPAID, InvoicePaymentStatus.PARTIAL)
            );
            validInvoices = invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(profileId, InvoiceStatus.CANCELLED);
        }

        PatientDebtSummaryDTO dto = new PatientDebtSummaryDTO();
        dto.setProfileId(patient.getProfileId());
        dto.setPatientCode(patient.getPatientCode());
        dto.setFullName(patient.getFullName());
        dto.setPhoneNumber(patient.getPhoneNumber());
        dto.setDepositBalance(patient.getDepositBalance() != null ? patient.getDepositBalance() : BigDecimal.ZERO);
        dto.setTotalBilled(totalBilled != null ? totalBilled : BigDecimal.ZERO);
        dto.setTotalPaid(totalPaid != null ? totalPaid : BigDecimal.ZERO);
        dto.setTotalDebt(totalDebt != null ? totalDebt : BigDecimal.ZERO);
        dto.setUnpaidInvoicesCount(unpaidInvoices.size());

        if (!validInvoices.isEmpty()) {
            dto.setLastInvoiceDate(validInvoices.get(0).getIssuedAt());
        }

        return dto;
    }

    @Override
    public List<Invoice> getUnpaidInvoicesForPatient(Long profileId) {
        return getUnpaidInvoicesForPatient(profileId, null);
    }

    @Override
    public List<Invoice> getUnpaidInvoicesForPatient(Long profileId, List<Long> branchIds) {
        if (branchIds != null && !branchIds.isEmpty()) {
            return invoiceRepository.findUnpaidInvoicesByPatientAndBranches(profileId, branchIds);
        }
        return invoiceRepository.findByPatientProfile_ProfileIdAndPaymentStatusIn(
                profileId,
                List.of(InvoicePaymentStatus.UNPAID, InvoicePaymentStatus.PARTIAL)
        );
    }
}
