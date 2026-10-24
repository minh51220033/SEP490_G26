package com.cvdcms.DTO;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class StockIssueCreateDTO {
    private Long branchId;
    private String issueType; // INTERNAL_USE, DISCARD_EXPIRED, TRANSFER
    private Long destinationBranchId;
    private LocalDate issueDate;
    private String notes;
    private List<Item> items;

    @Getter
    @Setter
    public static class Item {
        private Long materialId;
        private Long batchId; // Optional: nếu null thì tự động phân bổ theo FEFO
        private BigDecimal quantity;
        private String notes;
    }
}
