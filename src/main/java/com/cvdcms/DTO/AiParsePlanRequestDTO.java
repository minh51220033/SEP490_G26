package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiParsePlanRequestDTO {
    private String text;
    private String medicalText;
    private String imageBase64;
    private String mimeType; // e.g. "image/jpeg", "image/png"

    public String getText() {
        if (text != null && !text.trim().isEmpty()) {
            return text;
        }
        return medicalText;
    }
}
