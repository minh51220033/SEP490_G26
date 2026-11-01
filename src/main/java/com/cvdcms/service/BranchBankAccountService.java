package com.cvdcms.service;

import com.cvdcms.DTO.BranchBankAccountDTO;
import com.cvdcms.DTO.BranchBankAccountOptionDTO;
import com.cvdcms.DTO.PaymentQrInfoDTO;

import java.math.BigDecimal;
import java.util.List;

public interface BranchBankAccountService {

    List<BranchBankAccountDTO> getAccountsByBranch(Long branchId);

    List<BranchBankAccountOptionDTO> getActiveAccountOptions(Long branchId);

    BranchBankAccountDTO getAccountById(Long accountId);

    BranchBankAccountDTO createAccount(BranchBankAccountDTO dto, Long currentUserId, String ipAddress);

    BranchBankAccountDTO updateAccount(Long accountId, BranchBankAccountDTO dto, Long currentUserId, String ipAddress);

    void toggleAccountStatus(Long accountId, boolean active, Long currentUserId, String ipAddress);

    void setDefaultAccount(Long accountId, Long currentUserId, String ipAddress);

    void deleteAccount(Long accountId, Long currentUserId, String ipAddress);

    PaymentQrInfoDTO getQrInfo(Long branchId, Long invoiceId, Long profileId, Long accountId, BigDecimal amount, String sessionRef);
}
