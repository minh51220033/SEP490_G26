package com.cvdcms.controller;

import com.cvdcms.DTO.StockIssueCreateDTO;
import com.cvdcms.DTO.StockIssueResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.StockIssueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockIssueControllerTest {

    @Mock
    private StockIssueService issueService;

    @InjectMocks
    private StockIssueController controller;

    private MockHttpSession session;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUserId(8L);
        session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
    }

    @Test
    void listIssues_FallsBackUnknownSortFieldAndDelegatesToService() {
        Page<StockIssueResponseDTO> expected = new PageImpl<>(List.of());
        when(issueService.searchIssues(eq(1L), eq("INTERNAL"), eq(true), eq("PX"), any(), any(), any(Pageable.class), eq(8L)))
                .thenReturn(expected);

        Page<StockIssueResponseDTO> result = controller.listIssues(
                1L, "INTERNAL", true, "PX", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28),
                0, 20, "invalidField", "asc", session);

        assertSame(expected, result);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(issueService).searchIssues(eq(1L), eq("INTERNAL"), eq(true), eq("PX"), any(), any(), captor.capture(), eq(8L));
        Sort.Order order = captor.getValue().getSort().getOrderFor("issueDate");
        assertNotNull(order);
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    void listTransfers_ValidatesPagingAndDelegatesToSearchTransfers() {
        assertThrows(ResponseStatusException.class,
                () -> controller.listTransfers(1L, 2L, null, null, null, -1, 10, "issueDate", "desc", session));
        assertThrows(ResponseStatusException.class,
                () -> controller.listTransfers(1L, 2L, null, null, null, 0, 101, "issueDate", "desc", session));

        Page<StockIssueResponseDTO> expected = new PageImpl<>(List.of());
        when(issueService.searchTransfers(eq(1L), eq(2L), eq("DC"), isNull(), isNull(), any(Pageable.class), eq(8L)))
                .thenReturn(expected);

        assertSame(expected, controller.listTransfers(1L, 2L, "DC", null, null, 0, 10, "issueCode", "desc", session));
    }

    @Test
    void detailCreateIssueAndCreateTransfer_DelegateCorrectly() {
        StockIssueResponseDTO detail = new StockIssueResponseDTO();
        detail.setIssueId(55L);
        when(issueService.getById(55L, 8L)).thenReturn(detail);
        assertSame(detail, controller.detail(55L, session));

        StockIssueCreateDTO issueDto = new StockIssueCreateDTO();
        issueDto.setIssueType("INTERNAL");
        when(issueService.create(issueDto, 8L)).thenReturn(detail);
        assertSame(detail, controller.createIssue(issueDto, session));

        StockIssueCreateDTO transferDto = new StockIssueCreateDTO();
        transferDto.setIssueType("INTERNAL");
        when(issueService.create(transferDto, 8L)).thenReturn(detail);
        controller.createTransfer(transferDto, session);
        assertEquals("TRANSFER", transferDto.getIssueType());
    }

    @Test
    void exportPdf_SetsIssueVsTransferFilenameAndDisposition() {
        StockIssueResponseDTO issueDto = new StockIssueResponseDTO();
        issueDto.setIssueId(11L);
        issueDto.setIssueCode("PX-011");
        issueDto.setIssueType("INTERNAL");
        issueDto.setIssueDate(LocalDate.of(2026, 4, 1));
        when(issueService.getById(11L, 8L)).thenReturn(issueDto);

        ResponseEntity<byte[]> issuePdf = controller.exportPdf(11L, false, session);
        assertEquals(MediaType.APPLICATION_PDF, issuePdf.getHeaders().getContentType());
        assertTrue(issuePdf.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("PhieuXuatKho_PX-011.pdf"));
        assertTrue(issuePdf.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("inline"));

        StockIssueResponseDTO transferDto = new StockIssueResponseDTO();
        transferDto.setIssueId(12L);
        transferDto.setIssueCode("DC-012");
        transferDto.setIssueType("TRANSFER");
        transferDto.setIssueDate(LocalDate.of(2026, 4, 1));
        when(issueService.getById(12L, 8L)).thenReturn(transferDto);

        ResponseEntity<byte[]> transferPdf = controller.exportPdf(12L, true, session);
        assertTrue(transferPdf.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("PhieuDieuChuyen_DC-012.pdf"));
        assertTrue(transferPdf.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("attachment"));
    }

    @Test
    void requireUserAndExceptionHandlers_ReturnExpectedErrors() {
        ResponseStatusException unauth = assertThrows(ResponseStatusException.class,
                () -> controller.detail(1L, null));
        assertEquals(HttpStatus.UNAUTHORIZED, unauth.getStatusCode());

        assertEquals("Lỗi xuất kho", controller.handleBadRequest(new IllegalArgumentException("Lỗi xuất kho")).getBody().get("message"));
        assertEquals("Không thể xử lý yêu cầu phiếu xuất / điều chuyển.",
                controller.handleResponseStatus(new ResponseStatusException(HttpStatus.BAD_REQUEST)).getBody().get("message"));
    }
}
