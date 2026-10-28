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

    @GetMapping({"/doctor/labo", "/doctor/labo-management", "/labo"})
    public String laboManagement(
            @RequestParam(value = "tab", defaultValue = "orders") String tab,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) LaboOrderStatus status,
            @RequestParam(value = "supplierId", required = false) Long supplierId,
            Model model
    ) {
        List<LaboOrderResponseDTO> orders = laboOrderService.getAllOrders(keyword, status, supplierId, null, null);
        Map<String, Object> stats = laboOrderService.getLaboStatistics(null, null);
        List<Supplier> suppliers = supplierRepository.findAll();

        model.addAttribute("activeMenu", "labo");
        model.addAttribute("currentTab", tab);
        model.addAttribute("orders", orders);
        model.addAttribute("stats", stats);
        model.addAttribute("suppliers", suppliers);
        model.addAttribute("patients", patientProfileRepository.findAll());
        model.addAttribute("branches", branchRepository.findAll());
        model.addAttribute("allStatuses", LaboOrderStatus.values());
        model.addAttribute("pageTitle", "Quản lý Labo & Xét nghiệm - DentalSoft");

        return "doctor/labo-management";
    }
}
