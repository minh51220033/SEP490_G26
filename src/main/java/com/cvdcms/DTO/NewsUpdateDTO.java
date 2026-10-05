package com.cvdcms.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NewsUpdateDTO {

    // =====================================================
    // ID BÀI VIẾT
    // =====================================================

    private Long newsId;


    // =====================================================
    // TIÊU ĐỀ
    // =====================================================

    @NotBlank(
            message = "Tiêu đề không được để trống."
    )
    @Size(
            min = 5,
            max = 200,
            message = "Tiêu đề phải từ 5 đến 200 ký tự."
    )
    private String title;


    // =====================================================
    // MÔ TẢ NGẮN
    // =====================================================

    @Size(
            max = 500,
            message = "Mô tả ngắn không được vượt quá 500 ký tự."
    )
    private String summary;


    // =====================================================
    // NỘI DUNG
    // =====================================================

    @NotBlank(
            message = "Nội dung bài viết không được để trống."
    )
    private String content;


    // =====================================================
    // ẢNH ĐẠI DIỆN
    // =====================================================

    @Size(
            max = 500,
            message = "Đường dẫn ảnh không được vượt quá 500 ký tự."
    )
    private String thumbnail;


    // =====================================================
    // TRẠNG THÁI
    // =====================================================

    private String status;
}