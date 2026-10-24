package com.cvdcms.service;

import com.cvdcms.DTO.MaterialCategoryDTO;
import com.cvdcms.DTO.MaterialCreateDTO;
import com.cvdcms.DTO.MaterialUpdateDTO;
import com.cvdcms.entity.Material;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface MaterialService {

    Page<Material> search(
            String keyword,
            String category,
            Boolean active,
            Pageable pageable
    );

    List<Material> listActive();

    List<MaterialCategoryDTO> getCategories();

    int renameCategory(String oldCategory, String newCategory);

    Material getById(Long id);

    Material create(MaterialCreateDTO dto);

    Material update(Long id, MaterialUpdateDTO dto);

    Material deactivate(Long id);

    Material activate(Long id);
}
