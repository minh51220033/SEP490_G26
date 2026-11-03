package com.cvdcms.controller;

import com.cvdcms.DTO.LaboOrderRequestDTO;
import com.cvdcms.DTO.LaboOrderResponseDTO;
import com.cvdcms.entity.LaboOrder;
import com.cvdcms.entity.Supplier;
import com.cvdcms.entity.enums.LaboOrderStatus;
import com.cvdcms.repository.SupplierRepository;
import com.cvdcms.service.LaboOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit Test tầng Controller cho FT13 - Xét nghiệm & Mẫu gửi ngoài Labo (Role Bác sĩ)
 * Controller: LaboRestController
 */
@ExtendWith(MockitoExtension.class)
class LaboRestControllerTest {

    @Mock
    private LaboOrderService laboOrderService;

    @Mock
    private SupplierRepository supplierRepository;

    private LaboRestController laboRestController;

    private LaboOrder mockOrder;
    private Supplier mockSupplier;

    @BeforeEach
    void setUp() {
        laboRestController = new LaboRestController(laboOrderService, supplierRepository);

        mockSupplier = new Supplier();
        mockSupplier.setSupplierId(10L);
        mockSupplier.setSupplierCode("LB01");
        mockSupplier.setSupplierName("Labo Nha khoa Sài Gòn");

        mockOrder = new LaboOrder();
        mockOrder.setLaboId(101L);
        mockOrder.setLaboCode("LB-20261103-01");
        mockOrder.setMaterialType("Răng sứ Cercon HT");
        mockOrder.setQuantity(2);
        mockOrder.setUnitPrice(BigDecimal.valueOf(1500000));
        mockOrder.setCostToClinic(BigDecimal.valueOf(3000000));
        mockOrder.setStatus(LaboOrderStatus.SENT);
    }

    @Test
    @DisplayName("getOrdersByVisit - Lấy danh sách phiếu gửi xưởng theo ca khám")
    void getOrdersByVisit_Success() {
        LaboOrderResponseDTO dto = LaboOrderResponseDTO.fromEntity(mockOrder);
        when(laboOrderService.getOrdersByVisit(200L)).thenReturn(List.of(dto));
        when(laboOrderService.getLaboStatistics(200L, null)).thenReturn(Map.of("total", 1));

        ResponseEntity<Map<String, Object>> response = laboRestController.getOrdersByVisit(200L);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertNotNull(response.getBody().get("data"));
    }

    @Test
    @DisplayName("getOrderById - Tìm thấy phiếu labo trả về 200 OK")
    void getOrderById_Found() {
        LaboOrderResponseDTO dto = LaboOrderResponseDTO.fromEntity(mockOrder);
        when(laboOrderService.getOrderById(101L)).thenReturn(dto);

        ResponseEntity<Map<String, Object>> response = laboRestController.getOrderById(101L);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals(dto, response.getBody().get("data"));
    }

    @Test
    @DisplayName("getOrderById - Không tìm thấy trả về 404 Not Found")
    void getOrderById_NotFound() {
        when(laboOrderService.getOrderById(999L)).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = laboRestController.getOrderById(999L);

        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
    }

    @Test
    @DisplayName("createOrderJson - Tạo phiếu labo qua JSON thành công")
    void createOrderJson_Success() throws IOException {
        LaboOrderRequestDTO request = LaboOrderRequestDTO.builder()
                .visitId(200L)
                .materialType("Răng sứ Cercon HT")
                .quantity(2)
                .build();

        LaboOrderResponseDTO created = LaboOrderResponseDTO.fromEntity(mockOrder);
        when(laboOrderService.createOrder(any(LaboOrderRequestDTO.class), isNull())).thenReturn(created);

        ResponseEntity<Map<String, Object>> response = laboRestController.createOrderJson(request);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
    }

    @Test
    @DisplayName("createOrderJson - Xử lý lỗi khi tạo phiếu thất bại (trả về 400 Bad Request)")
    void createOrderJson_Exception_BadRequest() throws IOException {
        when(laboOrderService.createOrder(any(), isNull()))
                .thenThrow(new IllegalArgumentException("Vui lòng chọn khách hàng"));

        ResponseEntity<Map<String, Object>> response = laboRestController.createOrderJson(new LaboOrderRequestDTO());

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
    }

    @Test
    @DisplayName("createOrderMultipart - Tạo phiếu labo kèm đính kèm ảnh mẫu răng")
    void createOrderMultipart_Success() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "imageFile", "mau_rang.jpg", "image/jpeg", "image bytes".getBytes()
        );

        LaboOrderRequestDTO request = new LaboOrderRequestDTO();
        LaboOrderResponseDTO created = LaboOrderResponseDTO.fromEntity(mockOrder);
        when(laboOrderService.createOrder(eq(request), eq(file))).thenReturn(created);

        ResponseEntity<Map<String, Object>> response = laboRestController.createOrderMultipart(request, file);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
    }

    @Test
    @DisplayName("updateStatus - Cập nhật trạng thái phiếu xưởng thành công")
    void updateStatus_Success() {
        LaboOrderResponseDTO updated = LaboOrderResponseDTO.fromEntity(mockOrder);
        when(laboOrderService.updateStatus(101L, LaboOrderStatus.DELIVERED, "Đã giao răng đúng hẹn"))
                .thenReturn(updated);

        Map<String, String> body = Map.of("status", "DELIVERED", "note", "Đã giao răng đúng hẹn");
        ResponseEntity<Map<String, Object>> response = laboRestController.updateStatus(101L, body);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
    }

    @Test
    @DisplayName("updateStatus - Lỗi khi enum trạng thái không hợp lệ (trả về 400)")
    void updateStatus_InvalidStatus_BadRequest() {
        Map<String, String> body = Map.of("status", "INVALID_STATUS_CODE");
        ResponseEntity<Map<String, Object>> response = laboRestController.updateStatus(101L, body);

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
    }

    @Test
    @DisplayName("deleteOrder - Bác sĩ xóa phiếu xưởng thành công")
    void deleteOrder_Success() {
        doNothing().when(laboOrderService).deleteOrder(101L);

        ResponseEntity<Map<String, Object>> response = laboRestController.deleteOrder(101L);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        verify(laboOrderService, times(1)).deleteOrder(101L);
    }

    @Test
    @DisplayName("getSuppliers - Lấy danh sách xưởng đối tác Labo")
    void getSuppliers_Success() {
        when(supplierRepository.findAll()).thenReturn(List.of(mockSupplier));

        ResponseEntity<Map<String, Object>> response = laboRestController.getSuppliers();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, ((List<?>) response.getBody().get("data")).size());
    }
}

