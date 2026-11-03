package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.service.DiagnosisService;
import com.cvdcms.service.ParaclinicalOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit Test tầng Controller cho FT11 - Khám lâm sàng & Chỉ định Cận lâm sàng (Role Bác sĩ)
 * Controllers: DoctorExaminationRestController, DoctorParaclinicalRestController
 */
@ExtendWith(MockitoExtension.class)
class DoctorExaminationControllerTest {

    @Mock
    private DiagnosisService diagnosisService;

    @Mock
    private ParaclinicalOrderService paraclinicalOrderService;

    private DoctorExaminationRestController doctorExaminationRestController;
    private DoctorParaclinicalRestController doctorParaclinicalRestController;

    private Visit mockVisit;

    @BeforeEach
    void setUp() {
        doctorExaminationRestController = new DoctorExaminationRestController(diagnosisService);
        doctorParaclinicalRestController = new DoctorParaclinicalRestController(paraclinicalOrderService);

        mockVisit = new Visit();
        mockVisit.setVisitId(100L);
        mockVisit.setVisitCode("KB-100");
        mockVisit.setStatus(VisitStatus.IN_EXAM);
    }

    @Nested
    @DisplayName("Tests for DoctorExaminationRestController")
    class DoctorExaminationRestControllerTests {

        @Test
        @DisplayName("checkCompletionReadiness - Kiểm tra điều kiện hoàn tất ca khám trả về 200 OK")
        void checkCompletionReadiness_Success() {
            ClinicalCompletionCheckDTO checkDTO = ClinicalCompletionCheckDTO.builder()
                    .visitId(100L)
                    .ready(true)
                    .hasDiagnosis(true)
                    .hasCompletedServices(true)
                    .build();

            when(diagnosisService.checkClinicalCompletionReadiness(100L)).thenReturn(checkDTO);

            ResponseEntity<ClinicalCompletionCheckDTO> response = doctorExaminationRestController.checkCompletionReadiness(100L);

            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().isReady());
            verify(diagnosisService, times(1)).checkClinicalCompletionReadiness(100L);
        }

        @Test
        @DisplayName("completeClinicalExamination - Hoàn tất ca khám thành công (trạng thái WAITING_PAYMENT)")
        void completeClinicalExamination_Success() {
            mockVisit.setStatus(VisitStatus.WAITING_PAYMENT);
            when(diagnosisService.completeClinicalExamination(eq(100L), any())).thenReturn(mockVisit);

            ClinicalCompletionRequestDTO request = new ClinicalCompletionRequestDTO();
            request.setDoctorNotes("Bệnh nhân ổn định, hẹn tái khám sau 7 ngày");

            ResponseEntity<Map<String, Object>> response = doctorExaminationRestController.completeClinicalExamination(100L, request);

            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            Map<String, Object> body = response.getBody();
            assertNotNull(body);
            assertTrue((Boolean) body.get("success"));
            assertEquals("WAITING_PAYMENT", body.get("status"));
        }

        @Test
        @DisplayName("completeClinicalExamination - Báo lỗi 400 Bad Request khi thiếu điều kiện (ICD-10/Dịch vụ)")
        void completeClinicalExamination_ValidationFailed_BadRequest() {
            when(diagnosisService.completeClinicalExamination(eq(100L), any()))
                    .thenThrow(new IllegalStateException("Chưa chọn chẩn đoán bệnh chính (ICD-10)"));

            ResponseEntity<Map<String, Object>> response = doctorExaminationRestController.completeClinicalExamination(100L, null);

            assertNotNull(response);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertFalse((Boolean) response.getBody().get("success"));
        }

        @Test
        @DisplayName("reopenClinicalExamination - Mở lại ca khám thành công khi chưa xuất hóa đơn")
        void reopenClinicalExamination_Success() {
            mockVisit.setStatus(VisitStatus.IN_EXAM);
            when(diagnosisService.reopenClinicalExamination(eq(100L), any())).thenReturn(mockVisit);

            Map<String, String> body = Map.of("reason", "Cần bổ sung chỉ định");
            ResponseEntity<Map<String, Object>> response = doctorExaminationRestController.reopenClinicalExamination(100L, body);

            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue((Boolean) response.getBody().get("success"));
            assertEquals("IN_EXAM", response.getBody().get("status"));
        }

        @Test
        @DisplayName("getGeneralExamination - Lấy thông tin khám tổng quát thành công")
        void getGeneralExamination_Success() {
            GeneralExaminationRequestDTO examDTO = GeneralExaminationRequestDTO.builder()
                    .visitId(100L)
                    .initialSymptoms("Đau nhức răng 36")
                    .build();

            when(diagnosisService.getGeneralExamination(100L)).thenReturn(examDTO);

            ResponseEntity<GeneralExaminationRequestDTO> response = doctorExaminationRestController.getGeneralExamination(100L);

            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("Đau nhức răng 36", response.getBody().getInitialSymptoms());
        }

        @Test
        @DisplayName("saveGeneralExamination - Bác sĩ lưu kết quả khám tổng quát thành công")
        void saveGeneralExamination_Success() {
            doNothing().when(diagnosisService).saveGeneralExamination(any());

            ResponseEntity<Map<String, Object>> response = doctorExaminationRestController.saveGeneralExamination(100L, new GeneralExaminationRequestDTO());

            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue((Boolean) response.getBody().get("success"));
        }

        @Test
        @DisplayName("saveDoctorInstructions - Lưu lời dặn và hướng dẫn chăm sóc sau khám thành công")
        void saveDoctorInstructions_Success() {
            doNothing().when(diagnosisService).saveDoctorInstructions(100L, "Kiêng đồ cay nóng");

            Map<String, String> body = Map.of("adviceNotes", "Kiêng đồ cay nóng");
            ResponseEntity<Map<String, Object>> response = doctorExaminationRestController.saveDoctorInstructions(100L, body);

            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue((Boolean) response.getBody().get("success"));
        }
    }

    @Nested
    @DisplayName("Tests for DoctorParaclinicalRestController")
    class DoctorParaclinicalRestControllerTests {

        @Test
        @DisplayName("getOrdersByVisit - Lấy danh sách chỉ định cận lâm sàng của ca khám")
        void getOrdersByVisit_Success() {
            ParaclinicalOrderItemResponseDTO itemDTO = ParaclinicalOrderItemResponseDTO.builder()
                    .orderItemId(1L)
                    .orderCode("CĐ1001")
                    .serviceName("Chụp X-quang quanh chóp")
                    .status("WAITING")
                    .build();

            when(paraclinicalOrderService.getOrdersByVisit(100L)).thenReturn(List.of(itemDTO));

            ResponseEntity<List<ParaclinicalOrderItemResponseDTO>> response = doctorParaclinicalRestController.getOrdersByVisit(100L);

            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(1, response.getBody().size());
        }

        @Test
        @DisplayName("createOrder - Bác sĩ tạo chỉ định CLS thành công trả về 201 Created")
        void createOrder_Success() {
            ParaclinicalOrderCreateDTO createDTO = ParaclinicalOrderCreateDTO.builder()
                    .serviceId(50L)
                    .toothNumbers("R36")
                    .build();

            ParaclinicalOrderItemResponseDTO createdDTO = ParaclinicalOrderItemResponseDTO.builder()
                    .orderItemId(2L)
                    .serviceId(50L)
                    .status("WAITING")
                    .build();

            when(paraclinicalOrderService.createOrder(eq(100L), any(ParaclinicalOrderCreateDTO.class), isNull()))
                    .thenReturn(createdDTO);

            ResponseEntity<?> response = doctorParaclinicalRestController.createOrder(100L, createDTO);

            assertNotNull(response);
            assertEquals(HttpStatus.CREATED, response.getStatusCode());
        }

        @Test
        @DisplayName("createOrder - Lỗi nghiệp vụ (ca khám đã khóa EMR) trả về 400 Bad Request")
        void createOrder_LockedVisit_BadRequest() {
            when(paraclinicalOrderService.createOrder(eq(100L), any(), isNull()))
                    .thenThrow(new IllegalStateException("Hồ sơ bệnh án đã khóa (BR-EMR-02)."));

            ResponseEntity<?> response = doctorParaclinicalRestController.createOrder(100L, new ParaclinicalOrderCreateDTO());

            assertNotNull(response);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        @Test
        @DisplayName("updateStatus - Cập nhật trạng thái chỉ định sang COMPLETED thành công")
        void updateStatus_Success() {
            ParaclinicalOrderItemResponseDTO updatedDTO = ParaclinicalOrderItemResponseDTO.builder()
                    .orderItemId(1L)
                    .status("COMPLETED")
                    .build();

            when(paraclinicalOrderService.updateStatus(1L, ParaclinicalOrderItemStatus.COMPLETED, "Kết quả rõ"))
                    .thenReturn(updatedDTO);

            Map<String, String> payload = Map.of("status", "COMPLETED", "resultSummary", "Kết quả rõ");
            ResponseEntity<?> response = doctorParaclinicalRestController.updateStatus(1L, payload);

            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        @DisplayName("deleteOrder - Bác sĩ xóa chỉ định CLS thành công")
        void deleteOrder_Success() {
            doNothing().when(paraclinicalOrderService).deleteOrder(1L);

            ResponseEntity<?> response = doctorParaclinicalRestController.deleteOrder(1L);

            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(paraclinicalOrderService, times(1)).deleteOrder(1L);
        }
    }
}

