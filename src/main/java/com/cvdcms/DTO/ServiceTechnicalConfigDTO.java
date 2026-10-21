package com.cvdcms.DTO;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceTechnicalConfigDTO {

    // Mẫu thông số kết quả, được lưu dưới dạng JSON.
    private String resultTemplateJson;

    // Hướng dẫn và yêu cầu thực hiện kỹ thuật.
    private String technicalRequirements;
}