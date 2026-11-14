package com.cvdcms.controller;

import com.cvdcms.DTO.LaboOrderResponseDTO;
import com.cvdcms.entity.Supplier;
import com.cvdcms.entity.enums.LaboOrderStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.SupplierRepository;
import com.cvdcms.service.LaboOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@Slf4j
public class LaboPageController {

    private final LaboOrderService laboOrderService;
    private final SupplierRepository supplierRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    @GetMapping({"/doctor/labo", "/doctor/labo-management", "/labo"})
    public String laboManagement(
            @RequestParam(value = "tab", defaultValue = "orders") String tab,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) LaboOrderStatus status,
            @RequestParam(value = "supplierId", required = false) Long supplierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model
    ) {
        if (size != 10 && size != 25 && size != 50 && size != 100) {
            size = 10;
        }
        com.cvdcms.entity.User currentUser = null;
        java.util.Set<Long> allowedBranchIds = null;
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (doctorVisitAccess != null && attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
            currentUser = doctorVisitAccess.requireAuthenticatedUser(sra.getRequest().getSession(false));
            allowedBranchIds = doctorVisitAccess.resolveAccessibleBranchIds(currentUser);
        }
        List<LaboOrderResponseDTO> allOrders = laboOrderService.getAllOrders(keyword, status, supplierId, null, null);
        if (currentUser != null && doctorVisitAccess != null) {
            final com.cvdcms.entity.User filterUser = currentUser;
            allOrders = allOrders.stream()
                    .filter(o -> doctorVisitAccess.isBranchAllowed(filterUser, o.getBranchId()))
                    .toList();
        }
        long totalItems = allOrders.size();
        int totalPages = totalItems == 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        int currentPage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        int fromIndex = totalItems == 0 ? 0 : currentPage * size;
        int toIndex = (int) Math.min(fromIndex + size, totalItems);
        List<LaboOrderResponseDTO> pagedOrders = totalItems == 0 ? List.of() : allOrders.subList(fromIndex, toIndex);

        long toItem = toIndex;
        long fromItem = totalItems == 0 ? 0 : Math.min(((long) currentPage * size) + 1, toItem);

        Map<String, Object> stats = laboOrderService.getLaboStatistics(null, null);
        List<Supplier> suppliers = supplierRepository.findAll();

        var patientList = patientProfileRepository.findAll();
        var branchList = branchRepository.findAll();
        if (allowedBranchIds != null) {
            final java.util.Set<Long> branchScope = allowedBranchIds;
            patientList = patientList.stream()
                    .filter(p -> p.getPreferredBranch() != null && branchScope.contains(p.getPreferredBranch().getBranchId()))
                    .toList();
            branchList = branchList.stream()
                    .filter(b -> b.getBranchId() != null && branchScope.contains(b.getBranchId()))
                    .toList();
        }

        model.addAttribute("activeMenu", "labo");
        model.addAttribute("currentTab", tab);
        model.addAttribute("orders", pagedOrders);
        model.addAttribute("allOrders", allOrders);
        model.addAttribute("items", pagedOrders);
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("supplierId", supplierId);
        model.addAttribute("stats", stats);
        model.addAttribute("suppliers", suppliers);
        model.addAttribute("patients", patientList);
        model.addAttribute("branches", branchList);
        model.addAttribute("allStatuses", LaboOrderStatus.values());
        model.addAttribute("pageTitle", "Quản lý Labo & Xét nghiệm - DentalSoft");

        return "doctor/labo-management";
    }
}
