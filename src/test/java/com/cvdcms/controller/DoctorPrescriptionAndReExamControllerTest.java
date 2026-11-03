package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.service.PrescriptionService;
import com.cvdcms.service.ReExaminationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit Test tầng Controller cho FT18 - Kê đơn thuốc & Lịch hẹn tái khám (Role Bác sĩ)
 * Controllers: PrescriptionRestController, ReExaminationRestController
 */
@ExtendWith(MockitoExtension.class)
class DoctorPrescriptionAndReExamControllerTest {

    @Mock
    private PrescriptionService prescriptionService;

    @Mock
    private ReExaminationService reExaminationService;

    private PrescriptionRestController prescriptionRestController;
    private ReExaminationRestController reExaminationRestController;

    @BeforeEach
    void setUp() {
        prescriptionRestController = new PrescriptionRestController(prescriptionService);
        reExaminationRestController = new ReExaminationRestController(reExaminationService);
    }

    @Test
    @DisplayName("FT18-PRESC-CTRL-01: Controller kê đơn thuốc thành công trả về HTTP 201 CREATED")
    void testController_CreatePrescription_Success() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
                .visitId(100L)
                .doctorId(200L)
                .build();

        PrescriptionResponseDTO respDto = PrescriptionResponseDTO.builder()
                .prescriptionId(50L)
                .prescriptionCode("DT10001")
                .adviceNotes("Uống sau khi ăn")
                .build();

        when(prescriptionService.createPrescription(request)).thenReturn(respDto);

        ResponseEntity<?> response = prescriptionRestController.createPrescription(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> map = (Map<?, ?>) response.getBody();
        assertEquals(true, map.get("success"));
        assertEquals("Đã kê đơn thuốc thành công!", map.get("message"));
    }

    @Test
    @DisplayName("FT18-PRESC-CTRL-02: Controller kê đơn thất bại do vi phạm ràng buộc trả về HTTP 400 Bad Request")
    void testController_CreatePrescription_BadRequestOnException() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder().build();

        when(prescriptionService.createPrescription(request))
                .thenThrow(new IllegalArgumentException("Đơn thuốc phải có ít nhất một loại thuốc"));

        ResponseEntity<?> response = prescriptionRestController.createPrescription(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        Map<?, ?> map = (Map<?, ?>) response.getBody();
        assertEquals(false, map.get("success"));
    }

    @Test
    @DisplayName("FT18-PRESC-CTRL-03: Controller xóa đơn thuốc thành công trả về HTTP 200 OK")
    void testController_DeletePrescription_Success() {
        doNothing().when(prescriptionService).deletePrescription(50L);

        ResponseEntity<?> response = prescriptionRestController.deletePrescription(50L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        Map<?, ?> map = (Map<?, ?>) response.getBody();
        assertEquals(true, map.get("success"));
        assertEquals("Đã xóa đơn thuốc thành công!", map.get("message"));
    }

    @Test
    @DisplayName("FT18-REEXAM-CTRL-01: Controller tạo lịch hẹn tái khám thành công trả về HTTP 200 OK")
    void testController_CreateReExamination_Success() {
        ReExaminationRequestDTO request = ReExaminationRequestDTO.builder()
                .patientProfileId(10L)
                .appointmentDate(LocalDate.now().plusDays(7))
                .expectedTime("09:30")
                .followupReason("Cắt chỉ sau nhổ răng 8")
                .build();

        ReExaminationResponseDTO respDto = ReExaminationResponseDTO.builder()
                .appointmentId(888L)
                .appointmentCode("LH20261110001")
                .appointmentDate(LocalDate.now().plusDays(7))
                .build();

        when(reExaminationService.createReExamination(request)).thenReturn(respDto);

        ResponseEntity<ReExaminationResponseDTO> response = reExaminationRestController.createReExamination(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(888L, response.getBody().getAppointmentId());
        assertEquals("LH20261110001", response.getBody().getAppointmentCode());
    }

    @Test
    @DisplayName("FT18-REEXAM-CTRL-02: Controller dời lịch hẹn và cập nhật CSKH thành công trả về HTTP 200 OK")
    void testController_RescheduleAndCareTask_Success() {
        LocalDate newDate = LocalDate.now().plusDays(10);
        ReExaminationResponseDTO respDto = ReExaminationResponseDTO.builder()
                .appointmentId(888L)
                .appointmentDate(newDate)
                .build();

        when(reExaminationService.updateAppointmentDate(eq(888L), eq(newDate), eq("10:00:00"), eq(PreferredSession.MORNING)))
                .thenReturn(respDto);

        ResponseEntity<ReExaminationResponseDTO> resResp = reExaminationRestController.reschedule(
                888L, newDate, "10:00:00", PreferredSession.MORNING
        );

        assertEquals(HttpStatus.OK, resResp.getStatusCode());
        assertNotNull(resResp.getBody());
        assertEquals(newDate, resResp.getBody().getAppointmentDate());

        CareTaskUpdateDTO careDto = new CareTaskUpdateDTO();
        careDto.setTaskId(777L);
        doNothing().when(reExaminationService).updateCareTaskStatus(careDto);

        ResponseEntity<Map<String, Object>> careResp = reExaminationRestController.updateCareTask(careDto);
        assertEquals(HttpStatus.OK, careResp.getStatusCode());
        assertTrue((Boolean) careResp.getBody().get("success"));
    }

    @Test
    @DisplayName("FT18-REEXAM-CTRL-03: Controller hủy lịch hẹn tái khám trả về HTTP 200 OK")
    void testController_CancelAppointment_Success() {
        doNothing().when(reExaminationService).cancelAppointment(eq(888L), eq("Khách hủy do hết đau"));

        ResponseEntity<Map<String, Object>> response = reExaminationRestController.cancelAppointment(888L, "Khách hủy do hết đau");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
    }
}
