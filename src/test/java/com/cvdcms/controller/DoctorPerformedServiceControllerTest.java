package com.cvdcms.controller;

import com.cvdcms.DTO.PerformedServiceRequestDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.service.PerformedServiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Test tầng Controller cho FT16 - Ghi nhận dịch vụ thực hiện thực tế (Role Bác sĩ)
 * Controller: PerformedServiceRestController
 */
@ExtendWith(MockitoExtension.class)
class DoctorPerformedServiceControllerTest {

    @Mock
    private PerformedServiceService performedServiceService;

    private PerformedServiceRestController controller;

    @BeforeEach
    void setUp() {
        controller = new PerformedServiceRestController(performedServiceService, org.mockito.Mockito.mock(com.cvdcms.security.DoctorVisitAccess.class));
    }

    @Test
    @DisplayName("FT16-CTRL-01: API lấy danh mục dịch vụ điều trị khả dụng trả về HTTP 200 OK")
    void testController_GetActiveServicesCatalog() {
        ServiceDTO svcDto = ServiceDTO.builder()
                .serviceId(50L)
                .serviceCode("DV-TRAM-01")
                .serviceName("Trám răng thẩm mỹ")
                .basePrice(BigDecimal.valueOf(450_000))
                .build();

        when(performedServiceService.getAllActiveServices()).thenReturn(List.of(svcDto));

        ResponseEntity<List<ServiceDTO>> response = controller.getActiveServicesCatalog();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("DV-TRAM-01", response.getBody().get(0).getServiceCode());
    }

    @Test
    @DisplayName("FT16-CTRL-02: API lấy dịch vụ đã thực hiện theo ca khám trả về HTTP 200 OK")
    void testController_GetPerformedServicesByVisit() {
        PerformedServiceResponseDTO respDto = PerformedServiceResponseDTO.builder()
                .performedId(101L)
                .serviceName("Trám răng thẩm mỹ Composite")
                .finalPrice(BigDecimal.valueOf(450_000))
                .statusDisplay("Đã hoàn thành")
                .build();

        when(performedServiceService.getPerformedServicesByVisitId(100L)).thenReturn(List.of(respDto));

        ResponseEntity<List<PerformedServiceResponseDTO>> response = controller.getPerformedServicesByVisit(100L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("Trám răng thẩm mỹ Composite", response.getBody().get(0).getServiceName());
    }

    @Test
    @DisplayName("FT16-CTRL-03: API ghi nhận dịch vụ thực hiện thành công trả về HTTP 201 CREATED")
    void testController_CreatePerformedService_Success() {
        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(100L)
                .serviceId(50L)
                .doctorId(200L)
                .build();

        PerformedServiceResponseDTO respDto = PerformedServiceResponseDTO.builder()
                .performedId(101L)
                .serviceName("Trám răng")
                .status("COMPLETED")
                .build();

        when(performedServiceService.createPerformedService(request)).thenReturn(respDto);

        ResponseEntity<Map<String, Object>> response = controller.createPerformedService(request, null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("success"));
        assertEquals("Ghi nhận dịch vụ thực hiện thành công (COMPLETED)!", response.getBody().get("message"));
    }

    @Test
    @DisplayName("FT16-CTRL-04: API ghi nhận dịch vụ thất bại do bệnh án đã khóa trả về HTTP 400 Bad Request")
    void testController_CreatePerformedService_LockedVisit_ReturnsBadRequest() {
        PerformedServiceRequestDTO request = PerformedServiceRequestDTO.builder()
                .visitId(100L)
                .serviceId(50L)
                .doctorId(200L)
                .build();

        when(performedServiceService.createPerformedService(request))
                .thenThrow(new IllegalStateException("Bệnh án lâm sàng đã hoàn tất và khóa bất biến (BR-EMR-02)."));

        ResponseEntity<Map<String, Object>> response = controller.createPerformedService(request, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("success"));
        assertTrue(response.getBody().get("message").toString().contains("BR-EMR-02"));
    }

    @Test
    @DisplayName("FT16-CTRL-05: API xóa/hủy dịch vụ thực hiện thành công trả về HTTP 200 OK")
    void testController_CancelPerformedService_Success() {
        doNothing().when(performedServiceService).cancelPerformedService(101L);

        ResponseEntity<Map<String, Object>> response = controller.cancelPerformedService(101L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("success"));
        assertEquals("Đã xóa dịch vụ thực hiện thành công!", response.getBody().get("message"));
    }

    @Test
    @DisplayName("FT16-CTRL-06: API xóa/hủy dịch vụ thất bại do bệnh án đã khóa trả về HTTP 400 Bad Request")
    void testController_CancelPerformedService_LockedVisit_ReturnsBadRequest() {
        doThrow(new IllegalStateException("Bệnh án lâm sàng đã khóa bất biến (BR-EMR-02)."))
                .when(performedServiceService).cancelPerformedService(101L);

        ResponseEntity<Map<String, Object>> response = controller.cancelPerformedService(101L, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("success"));
        assertTrue(response.getBody().get("message").toString().contains("BR-EMR-02"));
    }
}

