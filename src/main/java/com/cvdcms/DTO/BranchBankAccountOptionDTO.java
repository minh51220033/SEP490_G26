package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchBankAccountOptionDTO {
    private Long accountId;
    private Long branchId;
    private String bankBin;
    private String bankName;
    private String bankShortName;
    private String accountNumber;
    private String accountHolderName;
    private Boolean isDefault;
}
