package com.cvdcms.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class IssueVoucherDTO {

    @NotNull(message = "Chương trình khuyến mãi không được để trống")
    private Long promotionId;

    @NotEmpty(message = "Cần chọn ít nhất một bệnh nhân để phát hành voucher")
    private List<Long> patientProfileIds;

    @NotNull(message = "Ngày hết hạn voucher không được để trống")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime expiresAt;

    private String customPrefix;
}
