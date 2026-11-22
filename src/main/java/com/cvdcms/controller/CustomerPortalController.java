package com.cvdcms.controller;

import com.cvdcms.DTO.PublicDoctorCardDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.User;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Controller
public class CustomerPortalController {

    private final CustomerPortalService customerPortalService;
    private final ServiceCatalogService serviceCatalogService;

    @Autowired(required = false)
    private UserRepository userRepository;

    @Autowired(required = false)
    private PatientProfileRepository patientProfileRepository;

    public CustomerPortalController(
            CustomerPortalService customerPortalService,
            ServiceCatalogService serviceCatalogService
    ) {
        this.customerPortalService = customerPortalService;
        this.serviceCatalogService = serviceCatalogService;
    }

    private Branch resolveUserPortalBranch(HttpSession session) {
        if (session == null) {
            return null;
        }
        List<Branch> activeBranches = customerPortalService.getActiveBranches();
        if (session.getAttribute("loggedInUser") instanceof User sessionUser && sessionUser.getUserId() != null) {
            User dbUser = userRepository != null
                    ? userRepository.findById(sessionUser.getUserId()).orElse(sessionUser)
                    : sessionUser;
            if (dbUser != null && dbUser.getBranch() != null && Boolean.TRUE.equals(dbUser.getBranch().getIsActive())) {
                return dbUser.getBranch();
            }
            if (patientProfileRepository != null) {
                PatientProfile defaultProfile = patientProfileRepository
                        .findFirstByUser_UserIdAndIsDefaultTrue(sessionUser.getUserId())
                        .orElse(null);
                if (defaultProfile != null
                        && defaultProfile.getPreferredBranch() != null
                        && Boolean.TRUE.equals(defaultProfile.getPreferredBranch().getIsActive())) {
                    return defaultProfile.getPreferredBranch();
                }
            }
        }
        Object selectedBranchIdObj = session.getAttribute("selectedPortalBranchId");
        if (selectedBranchIdObj instanceof Long selectedId && activeBranches != null) {
            for (Branch b : activeBranches) {
                if (selectedId.equals(b.getBranchId())) {
                    return b;
                }
            }
        }
        return null;
    }

    public String showPublicDoctors(
            String keyword,
            String specialty,
            Long branchId,
            int page,
            int size,
            Model model
    ) {
        return showPublicDoctors(keyword, specialty, branchId, page, size, null, model);
    }

    @GetMapping({"/doctors", "/doctors/"})
    public String showPublicDoctors(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String specialty,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size,
            HttpSession session,
            Model model
    ) {
        String searchKeyword = keyword == null ? "" : keyword.trim();
        String selectedSpecialty = specialty == null ? "" : specialty.trim();

        Branch userBranch = resolveUserPortalBranch(session);
        Long effectiveBranchId = userBranch != null ? userBranch.getBranchId() : branchId;

        List<PublicDoctorCardDTO> allMatching = customerPortalService.getPublicDoctors(
                searchKeyword,
                selectedSpecialty,
                effectiveBranchId
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
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("currentPortalBranch", userBranch);

        return "doctors/list";
    }

    public String showPublicDoctorDetail(
            Long id,
            HttpServletResponse response,
            Model model
    ) {
        return showPublicDoctorDetail(id, null, response, model);
    }

    @GetMapping("/doctors/{id}")
    public String showPublicDoctorDetail(
            @PathVariable("id") Long id,
            HttpSession session,
            HttpServletResponse response,
            Model model
    ) {
        model.addAttribute("activeNav", "doctors");
        try {
            PublicDoctorCardDTO doctor = customerPortalService.getPublicDoctorById(id);
            Branch userBranch = resolveUserPortalBranch(session);
            Long targetBranchId = userBranch != null ? userBranch.getBranchId() : null;
            List<PublicDoctorCardDTO> relatedDoctors = customerPortalService
                    .getPublicDoctors(null, null, targetBranchId)
                    .stream()
                    .filter(d -> !Objects.equals(d.getStaffId(), id))
                    .limit(4)
                    .collect(Collectors.toList());

            model.addAttribute("doctor", doctor);
            model.addAttribute("relatedDoctors", relatedDoctors);
            model.addAttribute("currentPortalBranch", userBranch);
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.PatientProfileService patientProfileService;

    @PostMapping("/portal/switch-branch")
    public String switchPortalBranch(
            @RequestParam("branchId") Long branchId,
            @RequestParam(value = "redirectUrl", required = false, defaultValue = "/") String redirectUrl,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        if (session == null
                || !(session.getAttribute("loggedInUser") instanceof User sessionUser)
                || sessionUser.getUserId() == null) {
            return "redirect:/login";
        }

        String safeRedirect = (redirectUrl != null && redirectUrl.startsWith("/") && !redirectUrl.startsWith("//"))
                ? redirectUrl
                : "/";

        try {
            Branch targetBranch = applyBranchSwitch(sessionUser, branchId, session);
            redirectAttributes.addFlashAttribute(
                    "branchSwitchSuccess",
                    "Đã chuyển sang cơ sở " + targetBranch.getBranchName() + " thành công!"
            );
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("branchSwitchError", ex.getMessage());
        }
        return "redirect:" + safeRedirect;
    }

    @PostMapping("/portal/switch-branch/ajax")
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<java.util.Map<String, Object>> switchPortalBranchAjax(
            @RequestParam(value = "branchId", required = false) Long branchId,
            HttpSession session
    ) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (session == null
                || !(session.getAttribute("loggedInUser") instanceof User sessionUser)
                || sessionUser.getUserId() == null) {
            body.put("success", false);
            body.put("loginRequired", true);
            body.put("loginUrl", "/login");
            body.put("message", "Vui lòng đăng nhập để chuyển cơ sở khám.");
            return org.springframework.http.ResponseEntity
                    .status(org.springframework.http.HttpStatus.UNAUTHORIZED)
                    .body(body);
        }

        try {
            Branch targetBranch = applyBranchSwitch(sessionUser, branchId, session);
            body.put("success", true);
            body.put("branchId", targetBranch.getBranchId());
            body.put("branchName", targetBranch.getBranchName());
            body.put("branchAddress", targetBranch.getAddress());
            body.put("branchPhone", targetBranch.getPhone());
            body.put("branchEmail", targetBranch.getEmail());
            if (targetBranch.getOpeningTime() != null && targetBranch.getClosingTime() != null) {
                body.put("openingHours", String.format("%02d:%02d - %02d:%02d",
                        targetBranch.getOpeningTime().getHour(),
                        targetBranch.getOpeningTime().getMinute(),
                        targetBranch.getClosingTime().getHour(),
                        targetBranch.getClosingTime().getMinute()));
            }
            body.put("message", "Đã chuyển sang cơ sở " + targetBranch.getBranchName() + " thành công!");
            return org.springframework.http.ResponseEntity.ok(body);
        } catch (IllegalArgumentException ex) {
            body.put("success", false);
            body.put("message", ex.getMessage());
            return org.springframework.http.ResponseEntity
                    .status(org.springframework.http.HttpStatus.BAD_REQUEST)
                    .body(body);
        }
    }

    private Branch applyBranchSwitch(User sessionUser, Long branchId, HttpSession session) {
        if (patientProfileService != null) {
            com.cvdcms.service.PatientProfileService.BranchSwitchResult result =
                    patientProfileService.switchUserPreferredBranch(sessionUser.getUserId(), branchId);
            Branch branch = result.branch();
            session.setAttribute("loggedInUser", result.user());
            session.setAttribute("selectedPortalBranchId", branch.getBranchId());
            session.setAttribute("currentBranchName", branch.getBranchName());
            session.setAttribute("currentBranchAddress", branch.getAddress() != null ? branch.getAddress() : "");
            if (result.defaultProfile() != null) {
                session.setAttribute("defaultPatientProfile", result.defaultProfile());
            }
            return branch;
        }

        if (branchId == null) {
            throw new IllegalArgumentException("Vui lòng chọn cơ sở khám.");
        }
        List<Branch> activeBranches = customerPortalService.getActiveBranches();
        Branch targetBranch = null;
        if (activeBranches != null) {
            for (Branch b : activeBranches) {
                if (branchId.equals(b.getBranchId())) {
                    targetBranch = b;
                    break;
                }
            }
        }
        if (targetBranch == null || !Boolean.TRUE.equals(targetBranch.getIsActive())) {
            throw new IllegalArgumentException("Cơ sở không tồn tại hoặc hiện không hoạt động.");
        }

        if (userRepository != null) {
            User dbUser = userRepository.findById(sessionUser.getUserId()).orElse(null);
            if (dbUser != null) {
                dbUser.setBranch(targetBranch);
                userRepository.save(dbUser);
                session.setAttribute("loggedInUser", dbUser);
            }
        }
        if (patientProfileRepository != null) {
            List<PatientProfile> profiles = patientProfileRepository
                    .findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(sessionUser.getUserId());
            for (PatientProfile p : profiles) {
                p.setPreferredBranch(targetBranch);
            }
            if (!profiles.isEmpty()) {
                patientProfileRepository.saveAll(profiles);
                session.setAttribute("defaultPatientProfile", profiles.get(0));
            }
        }
        session.setAttribute("selectedPortalBranchId", targetBranch.getBranchId());
        session.setAttribute("currentBranchName", targetBranch.getBranchName());
        return targetBranch;
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
