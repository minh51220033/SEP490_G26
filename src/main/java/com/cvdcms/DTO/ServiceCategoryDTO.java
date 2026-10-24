package com.cvdcms.DTO;

import com.cvdcms.entity.enums.ServiceType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceCategoryDTO {

    @NotBlank(message = "Vui lòng nhập mã nhóm dịch vụ.")
    @Pattern(
            regexp = "^[A-Za-z0-9_-]{2,30}$",
            message = "Mã nhóm gồm 2–30 ký tự: chữ không dấu, số, dấu - hoặc _."
    )
    private String categoryCode;

    @NotBlank(message = "Vui lòng nhập tên nhóm dịch vụ.")
    @Size(
            max = 150,
            message = "Tên nhóm dịch vụ tối đa 150 ký tự."
    )
    private String categoryName;

    @NotNull(message = "Vui lòng chọn loại nhóm dịch vụ.")
    private ServiceType categoryType;

    @NotNull(message = "Vui lòng nhập thứ tự hiển thị.")
    @Min(value = 0, message = "Thứ tự hiển thị không được âm.")
    private Integer displayOrder = 0;

    @NotNull(message = "Vui lòng chọn trạng thái nhóm dịch vụ.")
    private Boolean isActive = true;
}