package com.cvdcms.DTO;

import com.cvdcms.entity.enums.PromotionType;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class PromotionDTO {

    private Long promotionId;

    @NotBlank(message = "Mã khuyến mãi không được để trống")
    @Size(max = 30, message = "Mã khuyến mãi tối đa 30 ký tự")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Mã khuyến mãi chỉ được chứa ký tự chữ, số, gạch ngang và gạch dưới")
    private String promoCode;

    @NotBlank(message = "Tên chương trình khuyến mãi không được để trống")
    @Size(max = 200, message = "Tên chương trình tối đa 200 ký tự")
    private String promoName;

    @Size(max = 500, message = "Mô tả chi tiết tối đa 500 ký tự")
    private String description;

    private Long branchId;

    @NotNull(message = "Loại chiết khấu không được để trống")
    private PromotionType promoType = PromotionType.PERCENTAGE;

    @NotNull(message = "Giá trị giảm không được để trống")
    @DecimalMin(value = "0.01", message = "Giá trị giảm phải lớn hơn 0")
    private BigDecimal discountValue;

    private BigDecimal maxDiscountAmount;

    @DecimalMin(value = "0.00", message = "Giá trị hóa đơn tối thiểu phải >= 0")
    private BigDecimal minBillAmount = BigDecimal.ZERO;

    private Long applicableTierId;

    private Long applicableServiceId;

    @NotNull(message = "Thời gian bắt đầu không được để trống")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startDate;

    @NotNull(message = "Thời gian kết thúc không được để trống")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endDate;

    @Min(value = 1, message = "Giới hạn lượt dùng tối thiểu là 1")
    private Integer usageLimit;

    private Integer usedCount = 0;

    private Boolean isActive = true;
}
