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
 * và các trang xem bản in chuẩn A4 cho Phiếu nhập kho / Phiếu xuất kho.
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
        model.addAttribute("receipt", receipt);
        model.addAttribute("printedAt", LocalDateTime.now());
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
        model.addAttribute("issue", issue);
        model.addAttribute("printedAt", LocalDateTime.now());
        return "inventory/print-issue";
    }
}
