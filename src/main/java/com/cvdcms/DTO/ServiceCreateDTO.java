package com.cvdcms.DTO;

import com.cvdcms.entity.enums.ServiceStatus;
import com.cvdcms.entity.enums.ServiceType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ServiceCreateDTO {

    @NotNull(message = "Vui lòng chọn nhóm dịch vụ.")
    @Positive(message = "Nhóm dịch vụ không hợp lệ.")
    private Long categoryId;

    @NotBlank(message = "Vui lòng nhập mã dịch vụ.")
    @Pattern(
            regexp = "^[A-Za-z0-9_-]{2,30}$",
            message = "Mã dịch vụ gồm 2–30 ký tự: chữ không dấu, số, dấu - hoặc _."
    )
    private String serviceCode;

    @NotBlank(message = "Vui lòng nhập tên dịch vụ.")
    @Size(max = 200, message = "Tên dịch vụ tối đa 200 ký tự.")
    private String serviceName;

    @NotNull(message = "Vui lòng chọn loại dịch vụ.")
    private ServiceType serviceType;

    @NotBlank(message = "Vui lòng nhập đơn vị tính.")
    @Size(max = 30, message = "Đơn vị tính tối đa 30 ký tự.")
    private String unit = "Lần";

    @NotNull(message = "Vui lòng nhập đơn giá.")
    @DecimalMin(value = "0", message = "Đơn giá không được âm.")
    @Digits(
            integer = 13,
            fraction = 2,
            message = "Đơn giá tối đa 13 chữ số phần nguyên và 2 chữ số thập phân."
    )
    private BigDecimal basePrice = BigDecimal.ZERO;

    @NotNull(message = "Vui lòng nhập phụ phí.")
    @DecimalMin(value = "0", message = "Phụ phí không được âm.")
    @Digits(
            integer = 13,
            fraction = 2,
            message = "Phụ phí tối đa 13 chữ số phần nguyên và 2 chữ số thập phân."
    )
    private BigDecimal surchargeAmount = BigDecimal.ZERO;

    @NotNull(message = "Vui lòng xác định yêu cầu chọn răng.")
    private Boolean requiresTooth = false;

    @NotNull(message = "Vui lòng xác định khả năng đặt lịch trực tuyến.")
    private Boolean isBookableOnline = false;

    @Min(value = 0, message = "Số tháng bảo hành không được âm.")
    private Integer warrantyMonths;

    private String description;

    @NotNull(message = "Vui lòng chọn trạng thái dịch vụ.")
    private ServiceStatus status = ServiceStatus.ACTIVE;
}