package com.cvdcms.controller;

import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.DTO.SupplierPaymentCreateDTO;
import com.cvdcms.DTO.SupplierPaymentResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.SupplierPaymentService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierPaymentControllerTest {

    @Mock
    private SupplierPaymentService paymentService;

    @InjectMocks
    private SupplierPaymentController controller;

    private MockHttpSession session;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUserId(15L);
        session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
    }

    @Test
    void getDebtSummaries_ThrowsUnauthorized_WhenSessionMissing_AndDelegatesWhenValid() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getDebtSummaries(1L, null, new MockHttpSession()));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());

        List<SupplierDebtSummaryDTO> expected = List.of(new SupplierDebtSummaryDTO());
        when(paymentService.getDebtSummaries(1L, "NCC", 15L)).thenReturn(expected);

        assertSame(expected, controller.getDebtSummaries(1L, "NCC", session));
    }

    @Test
    void listPayments_ValidatesPagingAndFallsBackSortField() {
        assertThrows(ResponseStatusException.class,
                () -> controller.listPayments(1L, null, null, null, null, -1, 10, "paymentDate", "desc", session));
        assertThrows(ResponseStatusException.class,
                () -> controller.listPayments(1L, null, null, null, null, 0, 101, "paymentDate", "desc", session));

        Page<SupplierPaymentResponseDTO> expected = new PageImpl<>(List.of());
        when(paymentService.searchPayments(eq(1L), eq(2L), eq("PT"), any(), any(), any(Pageable.class), eq(15L)))
                .thenReturn(expected);

        Page<SupplierPaymentResponseDTO> result = controller.listPayments(
                1L, 2L, "PT", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                0, 20, "unknownSort", "asc", session);

        assertSame(expected, result);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentService).searchPayments(eq(1L), eq(2L), eq("PT"), any(), any(), captor.capture(), eq(15L));
        Sort.Order order = captor.getValue().getSort().getOrderFor("paymentDate");
        assertNotNull(order);
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    void createPayment_DelegatesToServiceWithLoggedInUserId() {
        SupplierPaymentCreateDTO createDto = new SupplierPaymentCreateDTO();
        SupplierPaymentResponseDTO expected = new SupplierPaymentResponseDTO();
        expected.setSupplierPaymentId(100L);
        when(paymentService.createPayment(createDto, 15L)).thenReturn(expected);

        SupplierPaymentResponseDTO res = controller.createPayment(createDto, session);
        assertSame(expected, res);
    }

    @Test
    void exceptionHandlers_FormatBadRequestAndResponseStatusResponses() {
        ResponseEntity<Map<String, String>> bad = controller.handleBadRequest(new IllegalArgumentException("Số tiền không hợp lệ"));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertEquals("Số tiền không hợp lệ", bad.getBody().get("message"));

        ResponseEntity<Map<String, String>> rse = controller.handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, rse.getStatusCode());
        assertEquals("Không thể xử lý yêu cầu thanh toán nhà cung cấp.", rse.getBody().get("message"));
    }
}
