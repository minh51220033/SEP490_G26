package com.cvdcms.config;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.BranchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * Controller Advice cung cấp dữ liệu cơ sở dùng chung cho toàn bộ giao diện hệ thống CV-DCMS:
 * - currentBranchId: ID chi nhánh hiện tại đang được chọn (null = Toàn hệ thống với ADMIN).
 * - currentBranch: Entity chi nhánh đang được chọn.
 * - currentBranchName: Tên chi nhánh hiện tại (hoặc "Toàn hệ thống").
 * - accessibleBranches: Danh sách chi nhánh người dùng có quyền thao tác.
 * - isAdmin: Cờ xác định vai trò quản trị viên.
 */
@ControllerAdvice
public class GlobalBranchControllerAdvice {

    private final BranchRepository branchRepository;
    private final BranchService branchService;
    private final BranchAccessService branchAccessService;

    public GlobalBranchControllerAdvice(
            BranchRepository branchRepository,
            BranchService branchService,
            BranchAccessService branchAccessService
    ) {
        this.branchRepository = branchRepository;
        this.branchService = branchService;
        this.branchAccessService = branchAccessService;
    }

    @ModelAttribute
    public void populateBranchContext(HttpServletRequest request, HttpSession session, Model model) {
        if (session == null) {
            return;
        }

        Object loggedInUserObj = session.getAttribute("loggedInUser");
        if (!(loggedInUserObj instanceof User sessionUser) || sessionUser.getUserId() == null) {
            return;
        }

        Long userId = sessionUser.getUserId();
        boolean isAdmin = branchAccessService.isAdmin(userId);
        model.addAttribute("isAdmin", isAdmin);

        // 1. Lấy danh sách chi nhánh người dùng được phép truy cập
        List<Branch> accessibleBranches = branchService.getUserAccessibleBranches(userId);
        List<Branch> allActiveBranches = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();

        model.addAttribute("accessibleBranches", accessibleBranches);
        model.addAttribute("allActiveBranches", allActiveBranches);

        // 2. Xác định currentBranchId
        Long currentBranchId = null;
        String branchIdParam = request.getParameter("branchId");

        if (branchIdParam != null && !branchIdParam.isBlank() && !"ALL".equalsIgnoreCase(branchIdParam) && !"0".equals(branchIdParam)) {
            try {
                Long paramId = Long.valueOf(branchIdParam.trim());
                if (isAdmin) {
                    currentBranchId = paramId;
                    session.setAttribute("currentBranchId", currentBranchId);
                } else {
                    boolean allowed = accessibleBranches.stream().anyMatch(b -> b.getBranchId().equals(paramId));
                    if (allowed) {
                        currentBranchId = paramId;
                        session.setAttribute("currentBranchId", currentBranchId);
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        } else if (branchIdParam != null && (branchIdParam.isBlank() || "0".equals(branchIdParam) || "ALL".equalsIgnoreCase(branchIdParam))) {
            if (isAdmin) {
                currentBranchId = null;
                session.removeAttribute("currentBranchId");
            }
        } else {
            Object sessionBranchIdObj = session.getAttribute("currentBranchId");
            if (sessionBranchIdObj instanceof Long sId) {
                if (isAdmin) {
                    currentBranchId = sId;
                } else {
                    boolean allowed = accessibleBranches.stream().anyMatch(b -> b.getBranchId().equals(sId));
                    if (allowed) {
                        currentBranchId = sId;
                    }
                }
            }
        }

        // Với nhân sự thường: nếu chưa có currentBranchId, mặc định lấy cơ sở đầu tiên được phân công
        if (!isAdmin && currentBranchId == null && !accessibleBranches.isEmpty()) {
            currentBranchId = accessibleBranches.get(0).getBranchId();
            session.setAttribute("currentBranchId", currentBranchId);
        }

        Branch currentBranch = null;
        if (currentBranchId != null) {
            for (Branch b : allActiveBranches) {
                if (b.getBranchId().equals(currentBranchId)) {
                    currentBranch = b;
                    break;
                }
            }
            if (currentBranch == null) {
                currentBranch = branchRepository.findById(currentBranchId).orElse(null);
            }
        }

        String currentBranchName = currentBranch != null ? currentBranch.getBranchName() : "Toàn hệ thống";
        String currentBranchCode = currentBranch != null && currentBranch.getBranchCode() != null && !currentBranch.getBranchCode().isBlank()
                ? currentBranch.getBranchCode()
                : (currentBranch != null ? currentBranch.getBranchName() : "DEMO");
        session.setAttribute("currentBranchName", currentBranchName);

        model.addAttribute("currentBranchId", currentBranchId);
        model.addAttribute("currentBranch", currentBranch);
        model.addAttribute("currentBranchName", currentBranchName);
        model.addAttribute("currentBranchCode", currentBranchCode);
    }
}
