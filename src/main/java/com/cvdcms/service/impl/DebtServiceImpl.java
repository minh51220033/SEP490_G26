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
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
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
        return getPatientDebts(branchId, branchId != null ? List.of(branchId) : null, null, keyword, pageable);
    }

    @Override
    public Page<PatientDebtSummaryDTO> getPatientDebts(
            Long branchId,
            List<Long> allowedBranchIds,
            InvoicePaymentStatus paymentStatus,
            String keyword,
            Pageable pageable
    ) {
        // Nếu kế toán không có chi nhánh nào được phân công, từ chối và trả về rỗng ngay lập tức
        if (allowedBranchIds != null && allowedBranchIds.isEmpty()) {
            return Page.empty(pageable);
        }

        String kw = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;

        // Nếu người dùng chọn branchId cụ thể
        if (branchId != null) {
            // Kiểm tra phân quyền: nếu không phải admin và branchId không thuộc allowedBranchIds thì từ chối
            if (allowedBranchIds != null && !allowedBranchIds.contains(branchId)) {
                return Page.empty(pageable);
            }
            return invoiceRepository.findPatientDebtsByBranch(branchId, paymentStatus, kw, pageable);
        }

        // Nếu branchId == null:
        // Với non-admin: chỉ truy vấn trong các chi nhánh được phân công
        if (allowedBranchIds != null) {
            return invoiceRepository.findPatientDebtsByBranches(allowedBranchIds, paymentStatus, kw, pageable);
        }

        // Với Admin: truy vấn toàn hệ thống
        return invoiceRepository.findPatientDebtsByBranch(null, paymentStatus, kw, pageable);
    }

    @Override
    public PatientDebtSummaryDTO getPatientDebtDetail(Long profileId) {
        return getPatientDebtDetail(profileId, (List<Long>) null);
    }

    @Override
    public PatientDebtSummaryDTO getPatientDebtDetail(Long profileId, List<Long> branchIds) {
        PatientProfile patient = patientProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân ID: " + profileId));

        PatientDebtSummaryDTO dto = new PatientDebtSummaryDTO();
        dto.setProfileId(patient.getProfileId());
        dto.setPatientCode(patient.getPatientCode());
        dto.setFullName(patient.getFullName());
        dto.setPhoneNumber(patient.getPhoneNumber());
        dto.setDepositBalance(patient.getDepositBalance() != null ? patient.getDepositBalance() : BigDecimal.ZERO);

        // Bảo vệ quyền chi nhánh: nếu danh sách chi nhánh được phép là rỗng -> không trả dữ liệu nợ
        if (branchIds != null && branchIds.isEmpty()) {
            dto.setTotalBilled(BigDecimal.ZERO);
            dto.setTotalPaid(BigDecimal.ZERO);
            dto.setTotalDebt(BigDecimal.ZERO);
            dto.setUnpaidInvoicesCount(0);
            dto.setLastInvoiceDate(null);
            return dto;
        }

        BigDecimal totalBilled;
        BigDecimal totalPaid;
        BigDecimal totalDebt;
        List<Invoice> unpaidInvoices;
        List<Invoice> validInvoices;

        if (branchIds != null) {
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

        dto.setTotalBilled(totalBilled != null ? totalBilled : BigDecimal.ZERO);
        dto.setTotalPaid(totalPaid != null ? totalPaid : BigDecimal.ZERO);
        dto.setTotalDebt(totalDebt != null ? totalDebt : BigDecimal.ZERO);
        dto.setUnpaidInvoicesCount(unpaidInvoices != null ? unpaidInvoices.size() : 0);

        if (validInvoices != null && !validInvoices.isEmpty()) {
            dto.setLastInvoiceDate(validInvoices.get(0).getIssuedAt());
        }

        return dto;
    }

    @Override
    public List<Invoice> getUnpaidInvoicesForPatient(Long profileId) {
        return getUnpaidInvoicesForPatient(profileId, (List<Long>) null);
    }

    @Override
    public List<Invoice> getUnpaidInvoicesForPatient(Long profileId, List<Long> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return Collections.emptyList();
        }
        if (branchIds != null) {
            return invoiceRepository.findUnpaidInvoicesByPatientAndBranches(profileId, branchIds);
        }
        return invoiceRepository.findByPatientProfile_ProfileIdAndPaymentStatusIn(
                profileId,
                List.of(InvoicePaymentStatus.UNPAID, InvoicePaymentStatus.PARTIAL)
        );
    }
}
