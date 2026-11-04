package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchBankAccountDTO {
    private Long accountId;
    private Long branchId;
    private String branchName;
    private String branchCode;
    private String bankBin;
    private String bankName;
    private String bankShortName;
    private String accountNumber;
    private String accountHolderName;
    private Boolean isDefault;
    private Boolean isActive;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean hasTransactions; // True nếu tài khoản đã có thanh toán tham chiếu
}
