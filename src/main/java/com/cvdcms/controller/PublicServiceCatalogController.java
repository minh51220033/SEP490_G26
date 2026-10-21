package com.cvdcms.controller;

import com.cvdcms.entity.enums.ServiceType;
import com.cvdcms.service.ServiceCatalogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/services")
public class PublicServiceCatalogController {

    private final ServiceCatalogService serviceCatalogService;

    public PublicServiceCatalogController(ServiceCatalogService serviceCatalogService) {
        this.serviceCatalogService = serviceCatalogService;
    }

    @GetMapping({"", "/"})
    public String showPublicServiceCatalog(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) ServiceType serviceType,
            @RequestParam(required = false) String keyword,
            Model model
    ) {
        String searchKeyword = keyword == null ? "" : keyword.trim();

        model.addAttribute("categories", serviceCatalogService.getActiveCategories());
        model.addAttribute("services", serviceCatalogService.getPublicServices(
                categoryId, serviceType, searchKeyword
        ));
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("selectedServiceType", serviceType);
        model.addAttribute("selectedKeyword", searchKeyword);

        return "services/list";
    }
}
