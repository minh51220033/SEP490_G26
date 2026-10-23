package com.cvdcms.DTO;

public class BranchOptionDTO {

    private Long branchId;
    private String branchCode;
    private String branchName;

    public BranchOptionDTO(
            Long branchId,
            String branchCode,
            String branchName
    ) {
        this.branchId = branchId;
        this.branchCode = branchCode;
        this.branchName = branchName;
    }

    public Long getBranchId() {
        return branchId;
    }

    public String getBranchCode() {
        return branchCode;
    }

    public String getBranchName() {
        return branchName;
    }
}