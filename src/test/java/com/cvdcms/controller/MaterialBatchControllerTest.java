package com.cvdcms.controller;

import com.cvdcms.DTO.MaterialBatchDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.MaterialBatchService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaterialBatchControllerTest {

    @Mock
    private MaterialBatchService batchService;

    @Mock
    private BranchAccessService branchAccessService;

    @InjectMocks
    private MaterialBatchController controller;

    private MockHttpSession session;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUserId(12L);
        session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
    }

    @Test
    void getBatches_ThrowsBadRequest_WhenBranchOrPagingInvalid() {
        assertThrows(ResponseStatusException.class,
                () -> controller.getBatches(0L, null, null, null, 0, 10, "expiryDate", "asc", session));
        assertThrows(ResponseStatusException.class,
                () -> controller.getBatches(1L, null, null, null, -1, 10, "expiryDate", "asc", session));
        assertThrows(ResponseStatusException.class,
                () -> controller.getBatches(1L, null, null, null, 0, 101, "expiryDate", "asc", session));
    }

    @Test
    void getBatches_ThrowsUnauthorizedOrForbidden_WhenAccessCheckFails() {
        ResponseStatusException unauth = assertThrows(ResponseStatusException.class,
                () -> controller.getBatches(1L, null, null, null, 0, 10, "expiryDate", "asc", null));
        assertEquals(HttpStatus.UNAUTHORIZED, unauth.getStatusCode());

        when(branchAccessService.canAccessBranch(12L, 1L)).thenReturn(false);
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> controller.getBatches(1L, null, null, null, 0, 10, "expiryDate", "asc", session));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
    }

    @Test
    void getBatches_ThrowsBadRequest_WhenSortFieldOrDirectionUnsupported() {
        when(branchAccessService.canAccessBranch(12L, 1L)).thenReturn(true);

        ResponseStatusException badSort = assertThrows(ResponseStatusException.class,
                () -> controller.getBatches(1L, null, null, null, 0, 10, "materialName", "asc", session));
        assertEquals(HttpStatus.BAD_REQUEST, badSort.getStatusCode());

        ResponseStatusException badDir = assertThrows(ResponseStatusException.class,
                () -> controller.getBatches(1L, null, null, null, 0, 10, "expiryDate", "up", session));
        assertEquals(HttpStatus.BAD_REQUEST, badDir.getStatusCode());
    }

    @Test
    void getBatches_DelegatesToBatchService_WithDateRangeAndSort() {
        when(branchAccessService.canAccessBranch(12L, 1L)).thenReturn(true);
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        Page<MaterialBatchDTO> expected = new PageImpl<>(List.of());
        when(batchService.searchBatches(eq(1L), eq("LOT"), eq(from), eq(to), any(Pageable.class)))
                .thenReturn(expected);

        Page<MaterialBatchDTO> result = controller.getBatches(
                1L, "LOT", from, to, 0, 25, "quantityRemaining", "desc", session);

        assertSame(expected, result);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(batchService).searchBatches(eq(1L), eq("LOT"), eq(from), eq(to), captor.capture());
        assertEquals(Sort.Direction.DESC, captor.getValue().getSort().getOrderFor("quantityRemaining").getDirection());
    }

    @Test
    void getBatchesWithStock_ValidatesAccessAndReturnsAvailableBatches() {
        assertThrows(ResponseStatusException.class,
                () -> controller.getBatchesWithStock(null, 5L, session));

        when(branchAccessService.canAccessBranch(12L, 1L)).thenReturn(true);
        MaterialBatchDTO dto = new MaterialBatchDTO(
                99L, 1L, "CS1", 5L, "VT05", "Găng tay", "Hộp",
                "LOT-99", LocalDate.now().plusDays(90), BigDecimal.TEN, new BigDecimal("50000"),
                1L, "NCC A", "AVAILABLE", 90L, "NONE"
        );
        when(batchService.getBatchesWithStock(1L, 5L)).thenReturn(List.of(dto));

        List<MaterialBatchDTO> result = controller.getBatchesWithStock(1L, 5L, session);
        assertEquals(1, result.size());
        assertEquals(99L, result.get(0).getBatchId());
    }

    @Test
    void exceptionHandlers_ReturnExpectedStatusAndDefaultFallback() {
        ResponseEntity<Map<String, String>> bad = controller.handleBadRequest(new IllegalArgumentException("Bad arg"));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertEquals("Bad arg", bad.getBody().get("message"));

        ResponseEntity<Map<String, String>> rse = controller.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertEquals(HttpStatus.NOT_FOUND, rse.getStatusCode());
        assertEquals("Không thể xử lý yêu cầu lô vật tư.", rse.getBody().get("message"));
    }
}
