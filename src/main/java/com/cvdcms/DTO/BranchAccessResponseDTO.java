package com.cvdcms.DTO;

import java.util.List;

public class BranchAccessResponseDTO {

    private boolean branchSelectionAllowed;
    private List<BranchOptionDTO> branches;

    public BranchAccessResponseDTO(
            boolean branchSelectionAllowed,
            List<BranchOptionDTO> branches
    ) {
        this.branchSelectionAllowed = branchSelectionAllowed;
        this.branches = branches;
    }

    public boolean isBranchSelectionAllowed() {
        return branchSelectionAllowed;
    }

    public List<BranchOptionDTO> getBranches() {
        return branches;
    }
}