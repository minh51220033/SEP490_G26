package com.cvdcms.controller;

import com.cvdcms.DTO.StockIssueResponseDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.StockIssueService;
import com.cvdcms.service.StockReceiptService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDateTime;

/**
 * Controller điều hướng giao diện chính của chức năng Quản lý kho (/inventory)
 * và các trang xem trước & in phiếu chuẩn A4 tích hợp trong hệ thống:
 * 1. Phiếu nhập kho (Stock-in)
 * 2. Phiếu xuất kho (Stock-out)
 * 3. Phiếu điều chuyển kho liên chi nhánh (Stock Transfer)
 */
@Controller
public class InventoryPageController {

    private final StockReceiptService receiptService;
    private final StockIssueService issueService;

    public InventoryPageController(
            StockReceiptService receiptService,
            StockIssueService issueService) {
        this.receiptService = receiptService;
        this.issueService = issueService;
    }

    @GetMapping("/inventory")
    public String inventoryPage(HttpSession session) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            return "redirect:/login";
        }
        return "inventory/list";
    }

    @GetMapping("/inventory/receipts/{receiptId}/print")
    public String printReceiptPage(
            @PathVariable Long receiptId,
            HttpSession session,
            Model model) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            return "redirect:/login";
        }
        StockReceiptResponseDTO receipt = receiptService.getById(receiptId, user.getUserId());
        addLayoutAttributes(session, model);
        model.addAttribute("receipt", receipt);
        model.addAttribute("printedAt", LocalDateTime.now());
        model.addAttribute("pageTitle", "Xem trước & in phiếu nhập kho");
        model.addAttribute("activeMenu", "inventory");
        return "inventory/print-receipt";
    }

    @GetMapping("/inventory/issues/{issueId}/print")
    public String printIssuePage(
            @PathVariable Long issueId,
            HttpSession session,
            Model model) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            return "redirect:/login";
        }
        StockIssueResponseDTO issue = issueService.getById(issueId, user.getUserId());
        addLayoutAttributes(session, model);
        model.addAttribute("issue", issue);
        model.addAttribute("printedAt", LocalDateTime.now());
        model.addAttribute("activeMenu", "inventory");

        if ("TRANSFER".equalsIgnoreCase(issue.getIssueType())) {
            model.addAttribute("pageTitle", "Xem trước & in phiếu điều chuyển kho");
            return "inventory/print-transfer";
        }
        model.addAttribute("pageTitle", "Xem trước & in phiếu xuất kho");
        return "inventory/print-issue";
    }

    @GetMapping("/inventory/transfers/{transferId}/print")
    public String printTransferPage(
            @PathVariable Long transferId,
            HttpSession session,
            Model model) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            return "redirect:/login";
        }
        StockIssueResponseDTO issue = issueService.getById(transferId, user.getUserId());
        addLayoutAttributes(session, model);
        model.addAttribute("issue", issue);
        model.addAttribute("printedAt", LocalDateTime.now());
        model.addAttribute("pageTitle", "Xem trước & in phiếu điều chuyển kho");
        model.addAttribute("activeMenu", "inventory");
        return "inventory/print-transfer";
    }

    private void addLayoutAttributes(HttpSession session, Model model) {
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            Object sessionUser = session.getAttribute("loggedInUser");
            if (sessionUser instanceof User user) {
                model.addAttribute("displayName", user.getUsername());
            }
        }
    }
}