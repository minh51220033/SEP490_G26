package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class InvoiceCreateRequestDTO {
    private Long visitId;
    private Long profileId;
    private Long branchId;
    private List<InvoiceItemRequestDTO> items = new ArrayList<>();
    private BigDecimal discountAmount = BigDecimal.ZERO;
    private Long promotionId;
    private Long voucherId;
    private Integer pointsToRedeem = 0;
    private String notes;
}
