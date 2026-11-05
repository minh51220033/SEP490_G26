package com.cvdcms.controller;

import com.cvdcms.DTO.PublicDoctorCardDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.service.CustomerPortalService;
import com.cvdcms.service.ServiceCatalogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Controller
public class CustomerPortalController {

    private final CustomerPortalService customerPortalService;
    private final ServiceCatalogService serviceCatalogService;

    public CustomerPortalController(
            CustomerPortalService customerPortalService,
            ServiceCatalogService serviceCatalogService
    ) {
        this.customerPortalService = customerPortalService;
        this.serviceCatalogService = serviceCatalogService;
    }

    @GetMapping({"/doctors", "/doctors/"})
    public String showPublicDoctors(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String specialty,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size,
            Model model
    ) {
        String searchKeyword = keyword == null ? "" : keyword.trim();
        String selectedSpecialty = specialty == null ? "" : specialty.trim();

        List<PublicDoctorCardDTO> allMatching = customerPortalService.getPublicDoctors(
                searchKeyword,
                selectedSpecialty,
                branchId
        );

        int safeSize = Math.max(1, Math.min(size, 40));
        int totalItems = allMatching.size();
        int totalPages = totalItems == 0 ? 1 : (int) Math.ceil((double) totalItems / safeSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int fromIndex = safePage * safeSize;
        int toIndex = Math.min(fromIndex + safeSize, totalItems);
        List<PublicDoctorCardDTO> pagedDoctors = totalItems == 0
                ? List.of()
                : allMatching.subList(fromIndex, toIndex);

        model.addAttribute("activeNav", "doctors");
        model.addAttribute("doctors", pagedDoctors);
        model.addAttribute("totalDoctors", totalItems);
        model.addAttribute("currentPage", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("specialties", customerPortalService.getPublicDoctorSpecialties());
        model.addAttribute("branches", customerPortalService.getActiveBranches());
        model.addAttribute("selectedKeyword", searchKeyword);
        model.addAttribute("selectedSpecialty", selectedSpecialty);
        model.addAttribute("selectedBranchId", branchId);

        return "doctors/list";
    }

    @GetMapping("/doctors/{id}")
    public String showPublicDoctorDetail(
            @PathVariable("id") Long id,
            HttpServletResponse response,
            Model model
    ) {
        model.addAttribute("activeNav", "doctors");
        try {
            PublicDoctorCardDTO doctor = customerPortalService.getPublicDoctorById(id);
            List<PublicDoctorCardDTO> relatedDoctors = customerPortalService
                    .getPublicDoctors(null, null, null)
                    .stream()
                    .filter(d -> !Objects.equals(d.getStaffId(), id))
                    .limit(4)
                    .collect(Collectors.toList());

            model.addAttribute("doctor", doctor);
            model.addAttribute("relatedDoctors", relatedDoctors);
            return "doctors/detail";
        } catch (IllegalArgumentException ex) {
            if (response != null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
            model.addAttribute("notFoundTitle", "Không tìm thấy hồ sơ bác sĩ");
            model.addAttribute("notFoundMessage", ex.getMessage());
            model.addAttribute("backUrl", "/doctors");
            model.addAttribute("backLabel", "Quay lại danh sách bác sĩ");
            return "portal/not-found";
        }
    }

    @GetMapping("/about")
    public String showAboutPage(Model model) {
        model.addAttribute("activeNav", "about");
        model.addAttribute("branches", customerPortalService.getActiveBranches());
        model.addAttribute("featuredDoctors", customerPortalService.getFeaturedDoctors(4));
        model.addAttribute("categories", serviceCatalogService.getActiveCategories());
        return "portal/about";
    }

    @GetMapping({"/contact", "/branches"})
    public String showContactAndBranchesPage(Model model) {
        List<Branch> branches = customerPortalService.getActiveBranches();
        model.addAttribute("activeNav", "contact");
        model.addAttribute("branches", branches);
        return "portal/contact";
    }

    @GetMapping("/privacy")
    public String showPrivacyPolicy(Model model) {
        model.addAttribute("activeNav", "policy");
        model.addAttribute("policyType", "privacy");
        model.addAttribute("pageTitle", "Chính sách bảo mật & Quyền riêng tư");
        model.addAttribute("branches", customerPortalService.getActiveBranches());
        return "portal/policy";
    }

    @GetMapping("/terms")
    public String showTermsOfService(Model model) {
        model.addAttribute("activeNav", "policy");
        model.addAttribute("policyType", "terms");
        model.addAttribute("pageTitle", "Điều khoản sử dụng & Quy định đặt lịch");
        model.addAttribute("branches", customerPortalService.getActiveBranches());
        return "portal/policy";
    }
}
