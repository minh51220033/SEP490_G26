package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class StockIssueResponseDTO {
    private Long issueId;
    private String issueCode;
    private Long branchId;
    private String branchName;
    private String issueType;
    private LocalDate issueDate;
    private Long destinationBranchId;
    private String destinationBranchName;
    private String issuerName;
    private String notes;
    private int itemCount;
    private BigDecimal totalQuantity = BigDecimal.ZERO;
    private BigDecimal totalEstimatedValue = BigDecimal.ZERO;
    private List<ItemDetail> items = new ArrayList<>();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ItemDetail {
        private Long issueItemId;
        private Long materialId;
        private String materialCode;
        private String materialName;
        private String unit;
        private Long batchId;
        private String batchNumber;
        private LocalDate expiryDate;
        private BigDecimal quantity;
        private BigDecimal importPrice;
        private BigDecimal lineValue;
        private String notes;
    }
}
