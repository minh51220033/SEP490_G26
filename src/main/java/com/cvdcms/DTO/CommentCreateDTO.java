package com.cvdcms.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CommentCreateDTO {

    @NotBlank(message = "Nội dung bình luận không được để trống.")
    @Size(
            min = 2,
            max = 2000,
            message = "Bình luận phải từ 2 đến 2000 ký tự."
    )
    private String content;
}