package com.cvdcms.controller;

import com.cvdcms.DTO.ClinicalPhotoResponseDTO;
import com.cvdcms.entity.ClinicalPhoto;
import com.cvdcms.entity.enums.PhotoCategory;
import com.cvdcms.entity.enums.PhotoStage;
import com.cvdcms.service.ClinicalPhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit Test tầng Controller cho FT17 - Lưu trữ và quản lý ảnh lâm sàng (Role Bác sĩ)
 * Controller: ClinicalPhotoRestController
 */
@ExtendWith(MockitoExtension.class)
class ClinicalPhotoRestControllerTest {

    @Mock
    private ClinicalPhotoService clinicalPhotoService;

    private ClinicalPhotoRestController clinicalPhotoRestController;

    private ClinicalPhoto mockPhoto;

    @BeforeEach
    void setUp() {
        clinicalPhotoRestController = new ClinicalPhotoRestController(clinicalPhotoService);

        mockPhoto = new ClinicalPhoto();
        mockPhoto.setPhotoId(50L);
        mockPhoto.setPhotoStage(PhotoStage.BEFORE);
        mockPhoto.setPhotoCategory(PhotoCategory.INTRAORAL);
        mockPhoto.setImageUrl("/uploads/clinical_photos/cp_sample.jpg");
        mockPhoto.setCaption("Ảnh trước khi chỉnh nha");
        mockPhoto.setCapturedAt(LocalDateTime.now());
    }

    @Test
    @DisplayName("getPhotosByPatient - Lấy danh sách ảnh lâm sàng và thống kê theo bệnh nhân")
    void getPhotosByPatient_Success() {
        ClinicalPhotoResponseDTO dto = ClinicalPhotoResponseDTO.fromEntity(mockPhoto);
        when(clinicalPhotoService.getPhotosByProfile(1L)).thenReturn(List.of(dto));
        when(clinicalPhotoService.getPhotoStatistics(1L)).thenReturn(Map.of("total", 1));

        ResponseEntity<Map<String, Object>> response = clinicalPhotoRestController.getPhotosByPatient(1L);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertNotNull(response.getBody().get("data"));
    }

    @Test
    @DisplayName("getPhotosByVisit - Lấy danh sách ảnh lâm sàng gắn với lượt khám cụ thể")
    void getPhotosByVisit_Success() {
        ClinicalPhotoResponseDTO dto = ClinicalPhotoResponseDTO.fromEntity(mockPhoto);
        when(clinicalPhotoService.getPhotosByVisit(300L)).thenReturn(List.of(dto));

        ResponseEntity<Map<String, Object>> response = clinicalPhotoRestController.getPhotosByVisit(300L);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals(1, ((List<?>) response.getBody().get("data")).size());
    }

    @Test
    @DisplayName("uploadPhoto - Bác sĩ upload ảnh lâm sàng thành công trả về 200 OK")
    void uploadPhoto_Success() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "rang_before.jpg", MediaType.IMAGE_JPEG_VALUE, "image content".getBytes()
        );

        ClinicalPhotoResponseDTO dto = ClinicalPhotoResponseDTO.fromEntity(mockPhoto);
        when(clinicalPhotoService.uploadPhoto(eq(1L), eq(300L), eq(PhotoStage.BEFORE), eq(PhotoCategory.INTRAORAL), eq("Ảnh hàm trên"), eq(file)))
                .thenReturn(dto);

        ResponseEntity<Map<String, Object>> response = clinicalPhotoRestController.uploadPhoto(
                1L, 300L, "BEFORE", "INTRAORAL", "Ảnh hàm trên", file
        );

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
    }

    @Test
    @DisplayName("uploadPhoto - Báo lỗi 400 Bad Request khi stage hoặc category không hợp lệ")
    void uploadPhoto_InvalidStage_BadRequest() {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "bytes".getBytes());

        ResponseEntity<Map<String, Object>> response = clinicalPhotoRestController.uploadPhoto(
                1L, 300L, "UNKNOWN_STAGE", "INTRAORAL", null, file
        );

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
    }

    @Test
    @DisplayName("uploadPhoto - Báo lỗi 500 khi server gặp lỗi IOException lúc ghi file")
    void uploadPhoto_IOException_InternalServerError() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "bytes".getBytes());

        when(clinicalPhotoService.uploadPhoto(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IOException("Disk write error"));

        ResponseEntity<Map<String, Object>> response = clinicalPhotoRestController.uploadPhoto(
                1L, 300L, "BEFORE", "INTRAORAL", null, file
        );

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
    }

    @Test
    @DisplayName("deletePhoto - Xóa ảnh lâm sàng thành công")
    void deletePhoto_Success() throws IOException {
        doNothing().when(clinicalPhotoService).deletePhoto(50L);

        ResponseEntity<Map<String, Object>> response = clinicalPhotoRestController.deletePhoto(50L);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        verify(clinicalPhotoService, times(1)).deletePhoto(50L);
    }

    @Test
    @DisplayName("updatePhoto - Cập nhật thông tin chú thích (caption) và phân loại ảnh thành công")
    void updatePhoto_Success() {
        ClinicalPhotoResponseDTO dto = ClinicalPhotoResponseDTO.fromEntity(mockPhoto);
        when(clinicalPhotoService.updatePhoto(50L, "Ảnh sau gắn mắc cài", PhotoStage.DURING, PhotoCategory.EXTRAORAL))
                .thenReturn(dto);

        Map<String, String> body = Map.of(
                "caption", "Ảnh sau gắn mắc cài",
                "stage", "DURING",
                "category", "EXTRAORAL"
        );

        ResponseEntity<Map<String, Object>> response = clinicalPhotoRestController.updatePhoto(50L, body);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
    }
}

