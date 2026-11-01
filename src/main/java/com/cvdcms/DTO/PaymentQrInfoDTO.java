package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentQrInfoDTO {
    private boolean success;
    private String errorMessage;
    private Long accountId;
    private Long branchId;
    private String branchName;
    private String bankBin;
    private String bankName;
    private String bankShortName;
    private String accountNumber;
    private String accountHolderName;
    private BigDecimal amount;
    private String transferContent;
    private String qrUrl;
    private List<BranchBankAccountOptionDTO> availableAccounts;
}
