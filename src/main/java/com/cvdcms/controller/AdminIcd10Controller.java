package com.cvdcms.controller;

import com.cvdcms.DTO.Icd10DiseaseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.Icd10DiseaseService;
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
@RequestMapping("/admin/icd10")
@RequiredArgsConstructor
public class AdminIcd10Controller {

    private static final String LIST_VIEW        = "admin/icd10/list";
    private static final String REDIRECT_LIST    = "redirect:/admin/icd10";
    private static final String DELETE_TOKEN_KEY = "icd10DeleteToken";

    private final Icd10DiseaseService icd10DiseaseService;
    private final UserRepository      userRepository;

    // ── 1. DANH SÁCH ─────────────────────────────────────────────────────────
    @GetMapping
    public String list(@RequestParam(value = "q", required = false, defaultValue = "") String keyword,
                       Model model, HttpSession session) {
        List<Icd10DiseaseDTO> diseases = keyword.isBlank()
                ? icd10DiseaseService.getAllDiseases()
                : icd10DiseaseService.adminSearch(keyword);
        addLayoutAttributes(session, model);
        generateDeleteToken(session, model);
        model.addAttribute("diseases", diseases);
        model.addAttribute("keyword", keyword);
        model.addAttribute("pageTitle", "Quản lý danh mục ICD-10");
        model.addAttribute("activeMenu", "icd10");
        return LIST_VIEW;
    }

    // ── 2. THÊM MỚI ──────────────────────────────────────────────────────────
    @PostMapping("/create")
    public String create(@ModelAttribute Icd10DiseaseDTO dto, RedirectAttributes ra) {
        try {
            icd10DiseaseService.createDisease(dto);
            ra.addFlashAttribute("successMessage",
                "Đã thêm mã ICD-10 '" + dto.getIcd10Code() + " - " + dto.getDiseaseNameVi() + "' thành công.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } catch (Exception e) {
            log.error("Lỗi khi thêm mã ICD-10: ", e);
            ra.addFlashAttribute("errorMessage", "Không thể thêm mã ICD-10: " + e.getMessage());
        }
        return REDIRECT_LIST;
    }

    // ── 3. CẬP NHẬT ──────────────────────────────────────────────────────────
    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id, @ModelAttribute Icd10DiseaseDTO dto, RedirectAttributes ra) {
        try {
            icd10DiseaseService.updateDisease(id, dto);
            ra.addFlashAttribute("successMessage", "Đã cập nhật mã ICD-10 thành công.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } catch (Exception e) {
            log.error("Lỗi khi cập nhật mã ICD-10: ", e);
            ra.addFlashAttribute("errorMessage", "Không thể cập nhật mã ICD-10: " + e.getMessage());
        }
        return REDIRECT_LIST;
    }

    // ── 4. BẬT / TẮT ─────────────────────────────────────────────────────────
    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        try {
            icd10DiseaseService.toggleDiseaseStatus(id);
            ra.addFlashAttribute("successMessage", "Đã thay đổi trạng thái mã ICD-10.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } catch (Exception e) {
            log.error("Lỗi khi thay đổi trạng thái mã ICD-10: ", e);
            ra.addFlashAttribute("errorMessage", "Không thể thay đổi trạng thái mã ICD-10: " + e.getMessage());
        }
        return REDIRECT_LIST;
    }

    // ── 5. XÓA ───────────────────────────────────────────────────────────────
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false) String deleteToken,
                         HttpSession session, RedirectAttributes ra) {
        try {
            requireDeletePermission(session, deleteToken);
            icd10DiseaseService.deleteDisease(id);
            ra.addFlashAttribute("successMessage", "Đã xóa mã ICD-10 thành công.");
        } catch (ResponseStatusException e) {
            ra.addFlashAttribute("errorMessage", e.getReason());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } catch (Exception e) {
            log.error("Lỗi khi xóa mã ICD-10: ", e);
            ra.addFlashAttribute("errorMessage", "Không thể xóa mã ICD-10: " + e.getMessage());
        }
        return REDIRECT_LIST;
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private void requireDeletePermission(HttpSession session, String deleteToken) {
        Object sessionUser = session.getAttribute("loggedInUser");
        if (!(sessionUser instanceof User user)
                || user.getUserId() == null
                || (!userRepository.hasActiveAdminRole(user.getUserId()) && !userRepository.hasActivePermission(user.getUserId(), "CATALOG_MANAGE"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ Admin đang hoạt động mới được xóa mã ICD-10.");
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
