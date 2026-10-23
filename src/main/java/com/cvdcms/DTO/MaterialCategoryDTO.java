package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MaterialCategoryDTO {
    private String categoryName;
    private long totalMaterials;
    private long activeMaterials;
}
