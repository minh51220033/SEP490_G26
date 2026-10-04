package com.cvdcms.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CommentBanDTO {

    @NotBlank(
            message = "Lý do cấm bình luận không được để trống."
    )
    @Size(
            min = 5,
            max = 500,
            message = "Lý do phải từ 5 đến 500 ký tự."
    )
    private String reason;
}