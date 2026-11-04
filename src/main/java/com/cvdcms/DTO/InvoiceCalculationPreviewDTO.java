package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class InvoiceCalculationPreviewDTO {
    private BigDecimal totalServiceAmount = BigDecimal.ZERO;
    private BigDecimal totalMedicineAmount = BigDecimal.ZERO;
    private BigDecimal memberDiscountPercent = BigDecimal.ZERO;
    private BigDecimal memberDiscountAmount = BigDecimal.ZERO;
    private BigDecimal promotionDiscountAmount = BigDecimal.ZERO;
    private BigDecimal pointDiscountAmount = BigDecimal.ZERO;
    private BigDecimal totalDiscountAmount = BigDecimal.ZERO;
    private BigDecimal totalAmount = BigDecimal.ZERO;
    private Integer pointsEarnable = 0;
    private String promoName;
    private String tierName;

    // Bổ sung kiểm soát giới hạn điểm đổi
    private Integer pointsRedeemed = 0;       // Số điểm thực tế sẽ được dùng (đã khống chế không vượt trần)
    private Integer maxPointsAllowed = 0;     // Số điểm tối đa được phép đổi cho hóa đơn này
    private BigDecimal pointValue = BigDecimal.ZERO; // Giá trị quy đổi: 1 điểm = ... đ
}
