package com.cvdcms.controller;

import com.cvdcms.DTO.BranchBankAccountDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.BranchBankAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/bank-accounts")
public class AdminBankAccountController {

    private final BranchBankAccountService branchBankAccountService;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;

    // Danh sách ngân hàng phổ biến tại Việt Nam hỗ trợ chuẩn VietQR
    public static final List<Map<String, String>> POPULAR_BANKS = List.of(
            Map.of("bin", "970436", "shortName", "VCB", "name", "Vietcombank (Ngoại thương Việt Nam)"),
            Map.of("bin", "970422", "shortName", "MB", "name", "MBBank (Quân đội)"),
            Map.of("bin", "970407", "shortName", "TCB", "name", "Techcombank (Kỹ thương)"),
            Map.of("bin", "970415", "shortName", "CTG", "name", "VietinBank (Công thương)"),
            Map.of("bin", "970418", "shortName", "BIDV", "name", "BIDV (Đầu tư và Phát triển)"),
            Map.of("bin", "970405", "shortName", "VBA", "name", "Agribank (Nông nghiệp)"),
            Map.of("bin", "970416", "shortName", "ACB", "name", "ACB (Á Châu)"),
            Map.of("bin", "970432", "shortName", "VPB", "name", "VPBank (Việt Nam Thịnh Vượng)"),
            Map.of("bin", "970423", "shortName", "TPB", "name", "TPBank (Tiên Phong)"),
            Map.of("bin", "970403", "shortName", "STB", "name", "Sacombank (Sài Gòn Thương Tín)"),
            Map.of("bin", "970437", "shortName", "HDB", "name", "HDBank (Phát triển TP.HCM)"),
            Map.of("bin", "970443", "shortName", "SHB", "name", "SHB (Sài Gòn - Hà Nội)"),
            Map.of("bin", "970441", "shortName", "VIB", "name", "VIB (Quốc tế)"),
            Map.of("bin", "970426", "shortName", "MSB", "name", "MSB (Hàng hải)"),
            Map.of("bin", "970448", "shortName", "OCB", "name", "OCB (Phương Đông)"),
            Map.of("bin", "970440", "shortName", "SEAB", "name", "SeABank (Đông Nam Á)"),
            Map.of("bin", "970428", "shortName", "NAB", "name", "Nam A Bank (Nam Á)")
    );

    @Autowired
    public AdminBankAccountController(
            BranchBankAccountService branchBankAccountService,
            BranchRepository branchRepository,
            UserRepository userRepository
    ) {
        this.branchBankAccountService = branchBankAccountService;
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;
    }

    private void checkAdminAccess(HttpSession session, Model model) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User u) || u.getUserId() == null) {
            throw new AccessDeniedException("Vui lòng đăng nhập để tiếp tục.");
        }
        if (!userRepository.hasActiveAdminRole(u.getUserId())) {
            throw new AccessDeniedException("Chỉ Quản trị viên (Admin) mới có quyền cấu hình tài khoản ngân hàng nhận tiền theo chi nhánh.");
        }
        model.addAttribute("activeMenu", "bank-accounts");
        model.addAttribute("roleNames", session.getAttribute("roleNames"));
        model.addAttribute("displayName", session.getAttribute("displayName"));
        model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
        model.addAttribute("isAdmin", true);
    }

    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập.");
    }

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isEmpty()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping
    public String listBankAccounts(
            @RequestParam(required = false) Long branchId,
            Model model,
            HttpSession session
    ) {
        checkAdminAccess(session, model);

        List<Branch> branches = branchRepository.findAll();
        List<BranchBankAccountDTO> accounts = branchBankAccountService.getAccountsByBranch(branchId);

        model.addAttribute("branches", branches);
        model.addAttribute("selectedBranchId", branchId);
        model.addAttribute("accounts", accounts);
        model.addAttribute("popularBanks", POPULAR_BANKS);

        return "admin/bank-accounts/list";
    }

    @PostMapping("/create")
    public String createBankAccount(
            @ModelAttribute BranchBankAccountDTO dto,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            if (!userRepository.hasActiveAdminRole(currentUserId)) {
                throw new AccessDeniedException("Chỉ Quản trị viên mới có quyền thêm tài khoản ngân hàng.");
            }

            branchBankAccountService.createAccount(dto, currentUserId, getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage", "Thêm tài khoản ngân hàng nhận tiền mới thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể thêm tài khoản: " + e.getMessage());
        }
        return "redirect:/admin/bank-accounts" + (dto.getBranchId() != null ? "?branchId=" + dto.getBranchId() : "");
    }

    @PostMapping("/{id}/update")
    public String updateBankAccount(
            @PathVariable Long id,
            @ModelAttribute BranchBankAccountDTO dto,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            if (!userRepository.hasActiveAdminRole(currentUserId)) {
                throw new AccessDeniedException("Chỉ Quản trị viên mới có quyền sửa tài khoản ngân hàng.");
            }

            branchBankAccountService.updateAccount(id, dto, currentUserId, getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật tài khoản ngân hàng thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể cập nhật tài khoản: " + e.getMessage());
        }
        return "redirect:/admin/bank-accounts" + (dto.getBranchId() != null ? "?branchId=" + dto.getBranchId() : "");
    }

    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(
            @PathVariable Long id,
            @RequestParam boolean active,
            @RequestParam(required = false) Long branchId,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            if (!userRepository.hasActiveAdminRole(currentUserId)) {
                throw new AccessDeniedException("Chỉ Quản trị viên mới có quyền thay đổi trạng thái tài khoản ngân hàng.");
            }

            branchBankAccountService.toggleAccountStatus(id, active, currentUserId, getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage",
                    active ? "Đã kích hoạt tài khoản ngân hàng thành công!" : "Đã ngừng sử dụng tài khoản ngân hàng thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể thay đổi trạng thái: " + e.getMessage());
        }
        return "redirect:/admin/bank-accounts" + (branchId != null ? "?branchId=" + branchId : "");
    }

    @PostMapping("/{id}/set-default")
    public String setDefault(
            @PathVariable Long id,
            @RequestParam(required = false) Long branchId,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            if (!userRepository.hasActiveAdminRole(currentUserId)) {
                throw new AccessDeniedException("Chỉ Quản trị viên mới có quyền đặt tài khoản mặc định.");
            }

            branchBankAccountService.setDefaultAccount(id, currentUserId, getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage", "Đã đặt tài khoản làm mặc định nhận tiền của chi nhánh!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể đặt mặc định: " + e.getMessage());
        }
        return "redirect:/admin/bank-accounts" + (branchId != null ? "?branchId=" + branchId : "");
    }

    @PostMapping("/{id}/delete")
    public String deleteAccount(
            @PathVariable Long id,
            @RequestParam(required = false) Long branchId,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            if (!userRepository.hasActiveAdminRole(currentUserId)) {
                throw new AccessDeniedException("Chỉ Quản trị viên mới có quyền xóa tài khoản.");
            }

            branchBankAccountService.deleteAccount(id, currentUserId, getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage", "Xóa tài khoản ngân hàng thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/bank-accounts" + (branchId != null ? "?branchId=" + branchId : "");
    }
}
