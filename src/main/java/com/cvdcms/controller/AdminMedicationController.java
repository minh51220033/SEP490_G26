package com.cvdcms.controller;

import com.cvdcms.DTO.MedicationDTO;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.MedicationService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/admin/medications")
@RequiredArgsConstructor
public class AdminMedicationController {

    private static final String LIST_VIEW       = "admin/medications/list";
    private static final String REDIRECT_LIST   = "redirect:/admin/medications";
    private static final String DELETE_TOKEN_KEY = "medicationDeleteToken";

    private final MedicationService medicationService;
    private final UserRepository    userRepository;

    // ── 1. DANH SÁCH ─────────────────────────────────────────────────────────
    @GetMapping
    public String list(@RequestParam(value = "q", required = false, defaultValue = "") String keyword,
                       Model model, HttpSession session) {
        List<MedicationDTO> medications = keyword.isBlank()
                ? medicationService.getAllMedications()
                : medicationService.searchMedications(keyword);
        addLayoutAttributes(session, model);
        generateDeleteToken(session, model);
        model.addAttribute("medications", medications);
        model.addAttribute("keyword", keyword);
        model.addAttribute("pageTitle", "Quản lý danh mục thuốc");
        model.addAttribute("activeMenu", "medications");
        return LIST_VIEW;
    }

    // ── 2. THÊM MỚI ──────────────────────────────────────────────────────────
    @PostMapping("/create")
    public String create(@ModelAttribute MedicationDTO dto, RedirectAttributes ra) {
        try {
            medicationService.createMedication(dto);
            ra.addFlashAttribute("successMessage", "Đã thêm thuốc '" + dto.getDrugName() + "' thành công.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return REDIRECT_LIST;
    }

    // ── 3. CẬP NHẬT ──────────────────────────────────────────────────────────
    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id, @ModelAttribute MedicationDTO dto, RedirectAttributes ra) {
        try {
            medicationService.updateMedication(id, dto);
            ra.addFlashAttribute("successMessage", "Đã cập nhật thuốc thành công.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return REDIRECT_LIST;
    }

    // ── 4. BẬT / TẮT ─────────────────────────────────────────────────────────
    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        try {
            medicationService.toggleMedicationStatus(id);
            ra.addFlashAttribute("successMessage", "Đã thay đổi trạng thái thuốc.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return REDIRECT_LIST;
    }

    // ── 5. XÓA ───────────────────────────────────────────────────────────────
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false) String deleteToken,
                         HttpSession session, RedirectAttributes ra) {
        requireDeletePermission(session, deleteToken);
        try {
            medicationService.deleteMedication(id);
            ra.addFlashAttribute("successMessage", "Đã xóa thuốc thành công.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return REDIRECT_LIST;
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private void requireDeletePermission(HttpSession session, String deleteToken) {
        Object sessionUser = session.getAttribute("loggedInUser");
        if (!(sessionUser instanceof User user)
                || user.getUserId() == null
                || !userRepository.hasActiveAdminRole(user.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ Admin đang hoạt động mới được xóa thuốc.");
        }
        if (deleteToken == null || !deleteToken.equals(session.getAttribute(DELETE_TOKEN_KEY))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Phiên xác nhận không hợp lệ. Vui lòng tải lại trang.");
        }
    }

    private void generateDeleteToken(HttpSession session, Model model) {
        synchronized (session) {
            if (!(session.getAttribute(DELETE_TOKEN_KEY) instanceof String)) {
                session.setAttribute(DELETE_TOKEN_KEY, UUID.randomUUID().toString());
            }
            model.addAttribute("deleteToken", session.getAttribute(DELETE_TOKEN_KEY));
        }
    }

    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("roleNames", session.getAttribute("roleNames"));
        Object sessionUser = session.getAttribute("loggedInUser");
        if (sessionUser instanceof User user) {
            model.addAttribute("displayName", user.getUsername());
        }
    }
}
