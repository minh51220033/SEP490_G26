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

/**
 * [Phân hệ]: Quản trị Tài khoản Ngân hàng Chi nhánh (VietQR)
 * [Vai trò]: Controller giao diện quản trị (`/admin/bank-accounts`) cho phép cấu hình danh sách tài khoản nhận chuyển khoản và tạo mã VietQR theo từng chi nhánh.
 */
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

    /**
     * [Hàm hỗ trợ]: Khởi tạo controller với service tài khoản ngân hàng chi nhánh và các repository chi nhánh, người dùng.
     */
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

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng có vai trò Admin hoặc quyền `BRANCH_MANAGE`/`ACCOUNT_MANAGE` để cấu hình tài khoản ngân hàng chi nhánh.
     */
    private boolean hasBankAccountAccess(Long userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.hasActiveAdminRole(userId)
                || userRepository.hasActivePermission(userId, "BRANCH_MANAGE")
                || userRepository.hasActivePermission(userId, "ACCOUNT_MANAGE");
    }

    /**
     * [Hàm hỗ trợ]: Xác thực đăng nhập, kiểm tra quyền cấu hình tài khoản ngân hàng và nạp thông tin phiên/menu sidebar vào `Model`.
     */
    private void checkAdminAccess(HttpSession session, Model model) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User u) || u.getUserId() == null) {
            throw new AccessDeniedException("Vui lòng đăng nhập để tiếp tục.");
        }
        if (!hasBankAccountAccess(u.getUserId())) {
            throw new AccessDeniedException("Tài khoản không có quyền cấu hình tài khoản ngân hàng nhận tiền theo chi nhánh.");
        }
        model.addAttribute("activeMenu", "bank-accounts");
        model.addAttribute("roleNames", session.getAttribute("roleNames"));
        model.addAttribute("displayName", session.getAttribute("displayName"));
        model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
        model.addAttribute("isAdmin", userRepository.hasActiveAdminRole(u.getUserId()));
    }

    /**
     * [Hàm hỗ trợ]: Lấy `userId` của người dùng đang đăng nhập từ `HttpSession` hoặc ném `AccessDeniedException` nếu chưa đăng nhập.
     */
    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập.");
    }

    /**
     * [Hàm hỗ trợ]: Trích xuất địa chỉ IP client (ưu tiên header `X-Forwarded-For`) để lưu nhật ký kiểm toán (`SystemAuditLog`).
     */
    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isEmpty()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * [Chức năng]: Hiển thị trang danh sách tài khoản ngân hàng nhận chuyển khoản theo chi nhánh (`GET /admin/bank-accounts`).
     * [Mục đích]: Kiểm tra quyền quản trị, tải danh sách chi nhánh, danh sách ngân hàng hỗ trợ chuẩn VietQR (`POPULAR_BANKS`) và các tài khoản đã cấu hình từ `BranchBankAccountService` để render view `admin/bank-accounts/list`.
     */
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

    /**
     * [Chức năng]: Xử lý tạo mới tài khoản ngân hàng nhận tiền cho chi nhánh (`POST /admin/bank-accounts/create`).
     * [Mục đích]: Xác thực quyền người thao tác, gọi `branchBankAccountService.createAccount` kèm IP ghi audit log, trả flash message kết quả và chuyển hướng về `returnUrl` hoặc trang danh sách theo chi nhánh.
     */
    @PostMapping("/create")
    public String createBankAccount(
            @ModelAttribute BranchBankAccountDTO dto,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            if (!hasBankAccountAccess(currentUserId)) {
                throw new AccessDeniedException("Tài khoản không có quyền thao tác tài khoản ngân hàng.");
            }

            branchBankAccountService.createAccount(dto, currentUserId, getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage", "Thêm tài khoản ngân hàng nhận tiền mới thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể thêm tài khoản: " + e.getMessage());
        }
        if (returnUrl != null && !returnUrl.isBlank()) {
            return "redirect:" + returnUrl;
        }
        return "redirect:/admin/bank-accounts" + (dto.getBranchId() != null ? "?branchId=" + dto.getBranchId() : "");
    }

    /**
     * [Chức năng]: Xử lý cập nhật thông tin tài khoản ngân hàng chi nhánh (`POST /admin/bank-accounts/{id}/update`).
     * [Mục đích]: Xác thực quyền người thao tác, gọi `branchBankAccountService.updateAccount` (bảo toàn lịch sử giao dịch nếu tài khoản đã từng phát sinh phiếu thu), gắn thông báo kết quả và chuyển hướng về trang gọi.
     */
    @PostMapping("/{id}/update")
    public String updateBankAccount(
            @PathVariable Long id,
            @ModelAttribute BranchBankAccountDTO dto,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            if (!hasBankAccountAccess(currentUserId)) {
                throw new AccessDeniedException("Tài khoản không có quyền sửa tài khoản ngân hàng.");
            }

            branchBankAccountService.updateAccount(id, dto, currentUserId, getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật tài khoản ngân hàng thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể cập nhật tài khoản: " + e.getMessage());
        }
        if (returnUrl != null && !returnUrl.isBlank()) {
            return "redirect:" + returnUrl;
        }
        return "redirect:/admin/bank-accounts" + (dto.getBranchId() != null ? "?branchId=" + dto.getBranchId() : "");
    }

    /**
     * [Chức năng]: Bật hoặc ngừng sử dụng tài khoản ngân hàng chi nhánh (`POST /admin/bank-accounts/{id}/toggle-status`).
     * [Mục đích]: Kiểm tra quyền quản trị và gọi `branchBankAccountService.toggleAccountStatus` để kích hoạt hoặc vô hiệu hóa tài khoản (tự động bỏ cờ mặc định khi ngừng hoạt động) kèm ghi audit log.
     */
    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(
            @PathVariable Long id,
            @RequestParam boolean active,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            if (!hasBankAccountAccess(currentUserId)) {
                throw new AccessDeniedException("Tài khoản không có quyền thay đổi trạng thái tài khoản ngân hàng.");
            }

            branchBankAccountService.toggleAccountStatus(id, active, currentUserId, getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage",
                    active ? "Đã kích hoạt tài khoản ngân hàng thành công!" : "Đã ngừng sử dụng tài khoản ngân hàng thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể thay đổi trạng thái: " + e.getMessage());
        }
        if (returnUrl != null && !returnUrl.isBlank()) {
            return "redirect:" + returnUrl;
        }
        return "redirect:/admin/bank-accounts" + (branchId != null ? "?branchId=" + branchId : "");
    }

    /**
     * [Chức năng]: Đặt một tài khoản ngân hàng làm tài khoản mặc định nhận chuyển khoản/VietQR của chi nhánh (`POST /admin/bank-accounts/{id}/set-default`).
     * [Mục đích]: Kiểm tra quyền quản trị, gọi `branchBankAccountService.setDefaultAccount` để đảm bảo mỗi chi nhánh chỉ có duy nhất một tài khoản mặc định đang hoạt động và ghi audit log.
     */
    @PostMapping("/{id}/set-default")
    public String setDefault(
            @PathVariable Long id,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            if (!hasBankAccountAccess(currentUserId)) {
                throw new AccessDeniedException("Tài khoản không có quyền đặt tài khoản mặc định.");
            }

            branchBankAccountService.setDefaultAccount(id, currentUserId, getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage", "Đã đặt tài khoản làm mặc định nhận tiền của chi nhánh!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể đặt mặc định: " + e.getMessage());
        }
        if (returnUrl != null && !returnUrl.isBlank()) {
            return "redirect:" + returnUrl;
        }
        return "redirect:/admin/bank-accounts" + (branchId != null ? "?branchId=" + branchId : "");
    }

    /**
     * [Chức năng]: Xóa cứng tài khoản ngân hàng chưa từng phát sinh giao dịch (`POST /admin/bank-accounts/{id}/delete`).
     * [Mục đích]: Kiểm tra quyền quản trị, ủy quyền cho `branchBankAccountService.deleteAccount` (chặn xóa nếu tài khoản đã gắn với phiếu thu quá khứ nhằm bảo toàn lịch sử tài chính) và chuyển hướng kèm thông báo.
     */
    @PostMapping("/{id}/delete")
    public String deleteAccount(
            @PathVariable Long id,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            if (!hasBankAccountAccess(currentUserId)) {
                throw new AccessDeniedException("Tài khoản không có quyền xóa tài khoản.");
            }

            branchBankAccountService.deleteAccount(id, currentUserId, getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage", "Xóa tài khoản ngân hàng thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        if (returnUrl != null && !returnUrl.isBlank()) {
            return "redirect:" + returnUrl;
        }
        return "redirect:/admin/bank-accounts" + (branchId != null ? "?branchId=" + branchId : "");
    }
}
