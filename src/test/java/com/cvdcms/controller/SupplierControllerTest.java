package com.cvdcms.controller;

import com.cvdcms.DTO.SupplierCreateDTO;
import com.cvdcms.DTO.SupplierUpdateDTO;
import com.cvdcms.entity.Supplier;
import com.cvdcms.service.SupplierService;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierControllerTest {

    @Mock
    private SupplierService supplierService;

    @InjectMocks
    private SupplierController controller;

    @Test
    void list_ThrowsIllegalArgument_WhenPaginationOrSortInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("", null, -1, 10, "supplierName", "asc"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("", null, 0, 0, "supplierName", "asc"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("", null, 0, 101, "supplierName", "asc"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("", null, 0, 10, "address", "asc"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("", null, 0, 10, "supplierName", "wrong"));
        verifyNoInteractions(supplierService);
    }

    @Test
    void list_DelegatesToService_WithValidatedSortAndFilter() {
        Page<Supplier> expected = new PageImpl<>(List.of(new Supplier()));
        when(supplierService.search(eq("Denta"), eq(true), any(Pageable.class))).thenReturn(expected);

        Page<Supplier> result = controller.list("Denta", true, 1, 25, "supplierCode", "DESC");

        assertSame(expected, result);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(supplierService).search(eq("Denta"), eq(true), captor.capture());
        assertEquals(1, captor.getValue().getPageNumber());
        assertEquals(25, captor.getValue().getPageSize());
        assertEquals(Sort.Direction.DESC, captor.getValue().getSort().getOrderFor("supplierCode").getDirection());
    }

    @Test
    void detailCreateUpdateActivateDeactivate_DelegateToService() {
        Supplier supplier = new Supplier();
        supplier.setSupplierId(3L);
        supplier.setSupplierCode("NCC01");

        SupplierCreateDTO createDto = new SupplierCreateDTO();
        createDto.setSupplierCode("NCC01");
        createDto.setSupplierName("Công ty Nha khoa ABC");

        SupplierUpdateDTO updateDto = new SupplierUpdateDTO();
        updateDto.setSupplierName("Công ty ABC Updated");

        when(supplierService.getById(3L)).thenReturn(supplier);
        when(supplierService.create(createDto)).thenReturn(supplier);
        when(supplierService.update(3L, updateDto)).thenReturn(supplier);
        when(supplierService.deactivate(3L)).thenReturn(supplier);
        when(supplierService.activate(3L)).thenReturn(supplier);

        ResponseEntity<Supplier> createdRes = controller.create(createDto);
        assertEquals(HttpStatus.CREATED, createdRes.getStatusCode());
        assertEquals(3L, createdRes.getBody().getSupplierId());

        assertSame(supplier, controller.detail(3L));
        assertSame(supplier, controller.update(3L, updateDto));
        assertSame(supplier, controller.deactivate(3L));
        assertSame(supplier, controller.activate(3L));
    }

    @Test
    void handleBadRequest_ReturnsBadRequestMessageBody() {
        ResponseEntity<Map<String, String>> response = controller.handleBadRequest(new IllegalArgumentException("Mã NCC bị trùng"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Mã NCC bị trùng", response.getBody().get("message"));
    }
}
