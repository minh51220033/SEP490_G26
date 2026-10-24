package com.cvdcms.service;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface DebtService {

    Page<PatientDebtSummaryDTO> getPatientDebts(Long branchId, String keyword, Pageable pageable);

    PatientDebtSummaryDTO getPatientDebtDetail(Long profileId);

    PatientDebtSummaryDTO getPatientDebtDetail(Long profileId, List<Long> branchIds);

    List<Invoice> getUnpaidInvoicesForPatient(Long profileId);

    List<Invoice> getUnpaidInvoicesForPatient(Long profileId, List<Long> branchIds);
}

