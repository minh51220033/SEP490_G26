package com.cvdcms.controller;

import com.cvdcms.DTO.ServicePromotionViewDTO;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.enums.ServiceType;
import com.cvdcms.service.CustomerPortalService;
import com.cvdcms.service.ServiceCatalogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/services")
public class PublicServiceCatalogController {

    private final ServiceCatalogService serviceCatalogService;
    private final CustomerPortalService customerPortalService;

    public PublicServiceCatalogController(ServiceCatalogService serviceCatalogService) {
        this(serviceCatalogService, null);
    }

    @Autowired
    public PublicServiceCatalogController(
            ServiceCatalogService serviceCatalogService,
            CustomerPortalService customerPortalService
    ) {
        this.serviceCatalogService = serviceCatalogService;
        this.customerPortalService = customerPortalService;
    }

    @GetMapping({"", "/"})
    public String showPublicServiceCatalog(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) ServiceType serviceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "9") int size,
            Model model
    ) {
        String searchKeyword = keyword == null ? "" : keyword.trim();

        List<Service> allMatching = serviceCatalogService.getPublicServices(
                categoryId, serviceType, searchKeyword
        );

        int safeSize = Math.max(1, Math.min(size, 48));
        int totalItems = allMatching.size();
        int totalPages = totalItems == 0 ? 1 : (int) Math.ceil((double) totalItems / safeSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int fromIndex = safePage * safeSize;
        int toIndex = Math.min(fromIndex + safeSize, totalItems);
        List<Service> pagedServices = totalItems == 0 ? List.of() : allMatching.subList(fromIndex, toIndex);

        Map<Long, ServicePromotionViewDTO> promotionsMap = customerPortalService != null
                ? customerPortalService.getActiveServicePromotionsMap()
                : Map.of();

        model.addAttribute("activeNav", "services");
        model.addAttribute("categories", serviceCatalogService.getActiveCategories());
        model.addAttribute("serviceTypes", ServiceType.values());
        model.addAttribute("services", pagedServices);
        model.addAttribute("allServices", allMatching);
        model.addAttribute("totalServices", totalItems);
        model.addAttribute("currentPage", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("servicePromotions", promotionsMap);
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("selectedServiceType", serviceType);
        model.addAttribute("selectedKeyword", searchKeyword);

        return "services/list";
    }

    @GetMapping("/{id}")
    public String showPublicServiceDetail(
            @PathVariable("id") Long id,
            HttpServletResponse response,
            Model model
    ) {
        model.addAttribute("activeNav", "services");
        try {
            Service service = serviceCatalogService.getPublicServiceById(id);
            Map<Long, ServicePromotionViewDTO> promotionsMap = customerPortalService != null
                    ? customerPortalService.getActiveServicePromotionsMap()
                    : Map.of();
            ServicePromotionViewDTO promo = promotionsMap.get(service.getServiceId());
            List<Service> relatedServices = customerPortalService != null
                    ? customerPortalService.getRelatedPublicServices(
                    service.getServiceId(),
                    service.getCategory() != null ? service.getCategory().getCategoryId() : null,
                    4
            )
                    : List.of();

            model.addAttribute("service", service);
            model.addAttribute("servicePromotion", promo);
            model.addAttribute("servicePromotions", promotionsMap);
            model.addAttribute("relatedServices", relatedServices);
            return "services/detail";
        } catch (IllegalArgumentException ex) {
            if (response != null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
            model.addAttribute("notFoundTitle", "Không tìm thấy dịch vụ");
            model.addAttribute("notFoundMessage", ex.getMessage());
            model.addAttribute("backUrl", "/services");
            model.addAttribute("backLabel", "Quay lại danh sách dịch vụ");
            return "portal/not-found";
        }
    }
}
