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
 * Controller MVC điều hướng giao diện chính của chức năng Quản lý kho ({@code /inventory})
 * và các trang xem trước &amp; in phiếu chuẩn A4 tích hợp trong hệ thống:
 * <ul>
 *   <li>Phiếu nhập kho (Stock-in): {@code /inventory/receipts/{receiptId}/print}</li>
 *   <li>Phiếu xuất kho (Stock-out): {@code /inventory/issues/{issueId}/print}</li>
 *   <li>Phiếu điều chuyển kho liên chi nhánh (Stock Transfer): {@code /inventory/transfers/{transferId}/print}</li>
 * </ul>
 * Được gọi từ thanh điều hướng chính và hàm {@code printInventoryDocument()} trong {@code inventory.js}.
 */
@Controller
public class InventoryPageController {

    private final StockReceiptService receiptService;
    private final StockIssueService issueService;

    /**
     * Khởi tạo controller với các service đọc chi tiết phiếu nhập và phiếu xuất/điều chuyển.
     *
     * @param receiptService service truy vấn chi tiết phiếu nhập kho
     * @param issueService   service truy vấn chi tiết phiếu xuất kho và điều chuyển
     */
    public InventoryPageController(
            StockReceiptService receiptService,
            StockIssueService issueService) {
        this.receiptService = receiptService;
        this.issueService = issueService;
    }

    /**
     * Hiển thị trang quản lý kho tổng hợp (tồn kho, lô/HSD, nhập/xuất/điều chuyển, danh mục vật tư, công nợ NCC).
     * <p>Endpoint: {@code GET /inventory}</p>
     * <p>Xử lý: kiểm tra {@code loggedInUser} trong {@link HttpSession}; nếu chưa đăng nhập thì chuyển hướng về {@code /login}.</p>
     *
     * @param session phiên đăng nhập hiện tại của người dùng
     * @return tên view Thymeleaf {@code inventory/list} hoặc {@code redirect:/login}
     */
    @GetMapping("/inventory")
    public String inventoryPage(HttpSession session) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            return "redirect:/login";
        }
        return "inventory/list";
    }

    /**
     * Hiển thị trang xem trước và in Phiếu nhập kho chuẩn A4.
     * <p>Endpoint: {@code GET /inventory/receipts/{receiptId}/print}</p>
     * <p>Xử lý: xác thực người dùng trong session, gọi {@link StockReceiptService#getById(Long, Long)}
     * (kiểm tra quyền truy cập chi nhánh), nạp thông tin layout, dữ liệu phiếu ({@code receipt}) và thời điểm in ({@code printedAt}) vào {@link Model}.</p>
     * <p>Được gọi từ: nút In phiếu nhập trong {@code inventory.js} ({@code printInventoryDocument}).</p>
     *
     * @param receiptId mã định danh phiếu nhập kho cần in
     * @param session   phiên đăng nhập hiện tại
     * @param model     model truyền dữ liệu sang template Thymeleaf
     * @return view {@code inventory/print-receipt} hoặc {@code redirect:/login}
     */
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

    /**
     * Hiển thị trang xem trước và in Phiếu xuất kho chuẩn A4 (tự động chuyển sang mẫu điều chuyển nếu {@code issueType == "TRANSFER"}).
     * <p>Endpoint: {@code GET /inventory/issues/{issueId}/print}</p>
     * <p>Xử lý: xác thực phiên đăng nhập, tải chi tiết phiếu qua {@link StockIssueService#getById(Long, Long)},
     * nạp dữ liệu {@code issue}, {@code printedAt} và chọn template {@code print-transfer} hoặc {@code print-issue} tùy loại phiếu.</p>
     * <p>Được gọi từ: nút In phiếu xuất trong {@code inventory.js} ({@code printInventoryDocument}).</p>
     *
     * @param issueId mã định danh phiếu xuất kho cần in
     * @param session phiên đăng nhập hiện tại
     * @param model   model truyền dữ liệu sang template Thymeleaf
     * @return view {@code inventory/print-issue}, {@code inventory/print-transfer} hoặc {@code redirect:/login}
     */
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

    /**
     * Hiển thị trang xem trước và in Phiếu điều chuyển kho liên chi nhánh chuẩn A4.
     * <p>Endpoint: {@code GET /inventory/transfers/{transferId}/print}</p>
     * <p>Xử lý: xác thực phiên đăng nhập, truy vấn chi tiết phiếu điều chuyển qua {@link StockIssueService#getById(Long, Long)}
     * và truyền dữ liệu sang template {@code inventory/print-transfer}.</p>
     * <p>Được gọi từ: nút In phiếu điều chuyển trong {@code inventory.js} ({@code printInventoryDocument}).</p>
     *
     * @param transferId mã định danh phiếu điều chuyển (bản ghi {@code StockIssue} loại {@code TRANSFER})
     * @param session    phiên đăng nhập hiện tại
     * @param model      model truyền dữ liệu sang template Thymeleaf
     * @return view {@code inventory/print-transfer} hoặc {@code redirect:/login}
     */
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

    /**
     * Nạp các thuộc tính dùng chung cho layout khung giao diện ({@code roleNames}, {@code displayName}) từ {@link HttpSession} vào {@link Model}.
     *
     * @param session phiên làm việc hiện tại
     * @param model   model Thymeleaf cần bổ sung thuộc tính
     */
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