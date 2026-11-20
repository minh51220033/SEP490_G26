package com.cvdcms.controller;

import com.cvdcms.DTO.StockIssueResponseDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.StockIssueService;
import com.cvdcms.service.StockReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryPageControllerTest {

    @Mock
    private StockReceiptService receiptService;

    @Mock
    private StockIssueService issueService;

    @InjectMocks
    private InventoryPageController controller;

    private MockHttpSession session;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUserId(4L);
        user.setUsername("thukho01");
        session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
        session.setAttribute("roleNames", List.of("WAREHOUSE"));
    }

    @Test
    void inventoryPage_RedirectsToLoginWhenUnauthenticated_AndReturnsViewWhenAuthenticated() {
        assertEquals("redirect:/login", controller.inventoryPage(null));
        assertEquals("redirect:/login", controller.inventoryPage(new MockHttpSession()));
        assertEquals("inventory/list", controller.inventoryPage(session));
    }

    @Test
    void printReceiptPage_RedirectsWhenUnauthenticated_AndPopulatesModelWhenAuthenticated() {
        Model unauthModel = new ConcurrentModel();
        assertEquals("redirect:/login", controller.printReceiptPage(1L, new MockHttpSession(), unauthModel));

        StockReceiptResponseDTO receipt = new StockReceiptResponseDTO(
                1L, "PN-001", 1L, 2L, LocalDate.now(), new BigDecimal("1000"));
        when(receiptService.getById(1L, 4L)).thenReturn(receipt);

        Model model = new ConcurrentModel();
        String view = controller.printReceiptPage(1L, session, model);

        assertEquals("inventory/print-receipt", view);
        assertSame(receipt, model.getAttribute("receipt"));
        assertNotNull(model.getAttribute("printedAt"));
        assertEquals("inventory", model.getAttribute("activeMenu"));
        assertEquals("thukho01", model.getAttribute("displayName"));
        assertEquals(List.of("WAREHOUSE"), model.getAttribute("roleNames"));
    }

    @Test
    void printIssuePage_SelectsPrintIssueOrPrintTransferViewBasedOnIssueType() {
        Model unauthModel = new ConcurrentModel();
        assertEquals("redirect:/login", controller.printIssuePage(10L, null, unauthModel));

        StockIssueResponseDTO normalIssue = new StockIssueResponseDTO();
        normalIssue.setIssueId(10L);
        normalIssue.setIssueType("INTERNAL");
        when(issueService.getById(10L, 4L)).thenReturn(normalIssue);

        Model issueModel = new ConcurrentModel();
        assertEquals("inventory/print-issue", controller.printIssuePage(10L, session, issueModel));
        assertEquals("Xem trước & in phiếu xuất kho", issueModel.getAttribute("pageTitle"));

        StockIssueResponseDTO transferIssue = new StockIssueResponseDTO();
        transferIssue.setIssueId(11L);
        transferIssue.setIssueType("TRANSFER");
        when(issueService.getById(11L, 4L)).thenReturn(transferIssue);

        Model transferModel = new ConcurrentModel();
        assertEquals("inventory/print-transfer", controller.printIssuePage(11L, session, transferModel));
        assertEquals("Xem trước & in phiếu điều chuyển kho", transferModel.getAttribute("pageTitle"));
    }

    @Test
    void printTransferPage_RedirectsWhenUnauthenticated_AndReturnsTransferViewWhenAuthenticated() {
        assertEquals("redirect:/login", controller.printTransferPage(20L, new MockHttpSession(), new ConcurrentModel()));

        StockIssueResponseDTO transfer = new StockIssueResponseDTO();
        transfer.setIssueId(20L);
        when(issueService.getById(20L, 4L)).thenReturn(transfer);

        Model model = new ConcurrentModel();
        assertEquals("inventory/print-transfer", controller.printTransferPage(20L, session, model));
        assertSame(transfer, model.getAttribute("issue"));
        assertEquals("inventory", model.getAttribute("activeMenu"));
    }
}
