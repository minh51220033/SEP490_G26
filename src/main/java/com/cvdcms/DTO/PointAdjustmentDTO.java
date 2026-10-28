package com.cvdcms.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PointAdjustmentDTO {

    @NotNull(message = "ID hồ sơ bệnh nhân không được để trống")
    private Long profileId;

    @NotNull(message = "Số điểm điều chỉnh không được để trống")
    private Integer points;

    @NotBlank(message = "Lý do điều chỉnh không được để trống")
    @Size(max = 255, message = "Lý do điều chỉnh không được vượt quá 255 ký tự")
    private String reason;
}
