package com.cvdcms.controller;

import com.cvdcms.DTO.StockReceiptCreateDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.StockReceiptService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockReceiptControllerTest {

    @Mock
    private StockReceiptService receiptService;

    @InjectMocks
    private StockReceiptController controller;

    private MockHttpSession session;
    private User user;
    private final List<Path> createdFiles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUserId(7L);
        session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Path p : createdFiles) {
            Files.deleteIfExists(p);
        }
    }

    @Test
    void list_ThrowsBadRequest_WhenPagingInvalid_AndFallsBackToReceiptDateSort() {
        assertThrows(ResponseStatusException.class,
                () -> controller.list(1L, null, null, null, null, -1, 10, "receiptDate", "desc", session));
        assertThrows(ResponseStatusException.class,
                () -> controller.list(1L, null, null, null, null, 0, 101, "receiptDate", "desc", session));

        Page<StockReceiptResponseDTO> expected = new PageImpl<>(List.of());
        when(receiptService.search(eq(1L), eq(3L), eq("PN"), any(), any(), any(Pageable.class), eq(7L)))
                .thenReturn(expected);

        Page<StockReceiptResponseDTO> result = controller.list(
                1L, 3L, "PN", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                0, 15, "invalidField", "asc", session);

        assertSame(expected, result);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(receiptService).search(eq(1L), eq(3L), eq("PN"), any(), any(), captor.capture(), eq(7L));
        Sort.Order primaryOrder = captor.getValue().getSort().getOrderFor("receiptDate");
        assertNotNull(primaryOrder);
        assertEquals(Sort.Direction.ASC, primaryOrder.getDirection());
    }

    @Test
    void detailAndCreate_DelegateToServiceWithLoggedInUser() {
        StockReceiptResponseDTO response = new StockReceiptResponseDTO(10L, "PN-010", 1L, 2L, LocalDate.now(), new BigDecimal("50000"));
        when(receiptService.getById(10L, 7L)).thenReturn(response);

        StockReceiptCreateDTO createDto = new StockReceiptCreateDTO();
        createDto.setBranchId(1L);
        when(receiptService.create(createDto, 7L)).thenReturn(response);

        assertSame(response, controller.detail(10L, session));
        assertSame(response, controller.create(createDto, session));
    }

    @Test
    void uploadInvoice_SavesFileAndReturnsAttachmentMetadata() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "invoice.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2});

        Map<String, String> res = controller.uploadInvoice(file, session);

        String url = res.get("attachmentUrl");
        assertNotNull(url);
        assertTrue(url.startsWith("/uploads/invoices/"));
        assertEquals("invoice.png", res.get("fileName"));
        createdFiles.add(Path.of(url.substring(1)));
        assertTrue(Files.exists(createdFiles.get(0)));
    }

    @Test
    void createMultipart_AttachesSavedInvoiceOnlyWhenFileNonEmpty() {
        StockReceiptCreateDTO dto = new StockReceiptCreateDTO();
        dto.setBranchId(1L);
        StockReceiptResponseDTO saved = new StockReceiptResponseDTO();
        saved.setReceiptId(20L);
        when(receiptService.create(dto, 7L)).thenReturn(saved);

        MockMultipartFile emptyFile = new MockMultipartFile("invoiceFile", "", "application/pdf", new byte[0]);
        assertSame(saved, controller.createMultipart(dto, emptyFile, session));
        assertNull(dto.getAttachmentUrl());

        MockMultipartFile pdf = new MockMultipartFile("invoiceFile", "hd.pdf", "application/pdf", "%PDF-1.4\n".getBytes());
        assertSame(saved, controller.createMultipart(dto, pdf, session));
        assertNotNull(dto.getAttachmentUrl());
        createdFiles.add(Path.of(dto.getAttachmentUrl().substring(1)));
    }

    @Test
    void exportPdf_ReturnsPdfBytesWithInlineOrAttachmentDisposition() {
        StockReceiptResponseDTO dto = new StockReceiptResponseDTO(
                15L, "PN-2026-015", 1L, 2L, LocalDate.of(2026, 3, 15), new BigDecimal("100000"));
        dto.setBranchName("Cơ sở 1");
        dto.setSupplierName("NCC Dental");
        when(receiptService.getById(15L, 7L)).thenReturn(dto);

        ResponseEntity<byte[]> inlineRes = controller.exportPdf(15L, false, session);
        assertEquals(HttpStatus.OK, inlineRes.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, inlineRes.getHeaders().getContentType());
        assertTrue(inlineRes.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("inline"));
        assertTrue(inlineRes.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("PhieuNhapKho_PN-2026-015.pdf"));
        assertTrue(inlineRes.getBody() != null && inlineRes.getBody().length > 100);

        ResponseEntity<byte[]> attachRes = controller.exportPdf(15L, true, session);
        assertTrue(attachRes.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("attachment"));
    }

    @Test
    void requireUserAndExceptionHandlers_ReturnUnauthorizedAndErrorMessages() {
        ResponseStatusException unauth = assertThrows(ResponseStatusException.class,
                () -> controller.detail(1L, new MockHttpSession()));
        assertEquals(HttpStatus.UNAUTHORIZED, unauth.getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST,
                controller.handleBadRequest(new IllegalArgumentException("Dữ liệu sai")).getStatusCode());
        assertEquals("Không thể xử lý yêu cầu phiếu nhập.",
                controller.handleResponseStatus(new ResponseStatusException(HttpStatus.FORBIDDEN)).getBody().get("message"));
    }
}
