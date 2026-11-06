package com.cvdcms.controller;

import com.cvdcms.DTO.ServicePromotionViewDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.ServiceType;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.CustomerPortalService;
import com.cvdcms.service.ServiceCatalogService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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

    @Autowired(required = false)
    private UserRepository userRepository;

    @Autowired(required = false)
    private PatientProfileRepository patientProfileRepository;

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

    private Long resolveUserBranchId(HttpSession session) {
        if (session == null) {
            return null;
        }
        if (session.getAttribute("loggedInUser") instanceof User sessionUser && sessionUser.getUserId() != null) {
            if (patientProfileRepository != null) {
                PatientProfile profile = patientProfileRepository
                        .findFirstByUser_UserIdAndIsDefaultTrue(sessionUser.getUserId())
                        .orElse(null);
                if (profile != null && profile.getPreferredBranch() != null) {
                    return profile.getPreferredBranch().getBranchId();
                }
            }
            User dbUser = userRepository != null
                    ? userRepository.findById(sessionUser.getUserId()).orElse(sessionUser)
                    : sessionUser;
            if (dbUser != null && dbUser.getBranch() != null) {
                return dbUser.getBranch().getBranchId();
            }
        }
        if (session.getAttribute("selectedPortalBranchId") instanceof Long sessBranchId) {
            return sessBranchId;
        }
        return null;
    }

    public String showPublicServiceCatalog(
            Long categoryId,
            ServiceType serviceType,
            String keyword,
            int page,
            int size,
            Model model
    ) {
        return showPublicServiceCatalog(categoryId, serviceType, keyword, page, size, null, model);
    }

    @GetMapping({"", "/"})
    public String showPublicServiceCatalog(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) ServiceType serviceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "9") int size,
            HttpSession session,
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

        Long targetBranchId = resolveUserBranchId(session);
        Map<Long, ServicePromotionViewDTO> promotionsMap = customerPortalService != null
                ? customerPortalService.getActiveServicePromotionsMap(targetBranchId)
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

    public String showPublicServiceDetail(
            Long id,
            HttpServletResponse response,
            Model model
    ) {
        return showPublicServiceDetail(id, null, response, model);
    }

    @GetMapping("/{id}")
    public String showPublicServiceDetail(
            @PathVariable("id") Long id,
            HttpSession session,
            HttpServletResponse response,
            Model model
    ) {
        model.addAttribute("activeNav", "services");
        try {
            Service service = serviceCatalogService.getPublicServiceById(id);
            Long targetBranchId = resolveUserBranchId(session);
            Map<Long, ServicePromotionViewDTO> promotionsMap = customerPortalService != null
                    ? customerPortalService.getActiveServicePromotionsMap(targetBranchId)
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
