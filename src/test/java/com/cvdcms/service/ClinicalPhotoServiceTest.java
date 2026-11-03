package com.cvdcms.service;

import com.cvdcms.DTO.ClinicalPhotoResponseDTO;
import com.cvdcms.entity.ClinicalPhoto;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.PhotoCategory;
import com.cvdcms.entity.enums.PhotoStage;
import com.cvdcms.repository.ClinicalPhotoRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.impl.ClinicalPhotoServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Test tầng Service cho FT17 - Lưu trữ ảnh lâm sàng (Role Bác sĩ)
 * Service: ClinicalPhotoServiceImpl
 */
@ExtendWith(MockitoExtension.class)
class ClinicalPhotoServiceTest {

    @Mock private ClinicalPhotoRepository clinicalPhotoRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private VisitRepository visitRepository;

    private ClinicalPhotoServiceImpl clinicalPhotoServiceImpl;

    @TempDir
    Path tempUploadDir;

    private PatientProfile mockPatient;
    private Visit mockVisit;
    private ClinicalPhoto mockPhoto;

    @BeforeEach
    void setUp() {
        clinicalPhotoServiceImpl = new ClinicalPhotoServiceImpl(
                clinicalPhotoRepository,
                patientProfileRepository,
                visitRepository
        );
        ReflectionTestUtils.setField(clinicalPhotoServiceImpl, "uploadDir", tempUploadDir.toString());

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(1L);
        mockPatient.setFullName("Pham Van Dong");
        mockPatient.setPatientCode("BN-30001");

        mockVisit = new Visit();
        mockVisit.setVisitId(300L);
        mockVisit.setVisitCode("KB-300");
        mockVisit.setPatientProfile(mockPatient);

        mockPhoto = new ClinicalPhoto();
        mockPhoto.setPhotoId(50L);
        mockPhoto.setPatientProfile(mockPatient);
        mockPhoto.setVisit(mockVisit);
        mockPhoto.setPhotoStage(PhotoStage.BEFORE);
        mockPhoto.setPhotoCategory(PhotoCategory.INTRAORAL);
        mockPhoto.setImageUrl("/uploads/clinical_photos/cp_before_sample.jpg");
        mockPhoto.setCaption("Ảnh trước khi chỉnh nha");
        mockPhoto.setCapturedAt(LocalDateTime.now());
    }

    @Test
    @DisplayName("uploadPhoto - Upload thành công ảnh phân loại BEFORE (Trước điều trị)")
    void uploadPhoto_Success_Before() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "rang_sau.jpg", MediaType.IMAGE_JPEG_VALUE, "fake photo content".getBytes()
        );

        when(patientProfileRepository.findById(1L)).thenReturn(Optional.of(mockPatient));
        when(visitRepository.findById(300L)).thenReturn(Optional.of(mockVisit));
        when(clinicalPhotoRepository.save(any(ClinicalPhoto.class))).thenAnswer(invocation -> {
            ClinicalPhoto photo = invocation.getArgument(0);
            photo.setPhotoId(100L);
            return photo;
        });

        ClinicalPhotoResponseDTO result = clinicalPhotoServiceImpl.uploadPhoto(
                1L, 300L, PhotoStage.BEFORE, PhotoCategory.INTRAORAL, "Ảnh khớp cắn trước điều trị", file
        );

        assertNotNull(result);
        assertEquals(100L, result.getPhotoId());
        assertEquals(PhotoStage.BEFORE, result.getPhotoStage());
        assertEquals(PhotoCategory.INTRAORAL, result.getPhotoCategory());
        assertEquals("Ảnh khớp cắn trước điều trị", result.getCaption());
        assertTrue(result.getImageUrl().startsWith("/uploads/clinical_photos/cp_"));
        verify(clinicalPhotoRepository, times(1)).save(any(ClinicalPhoto.class));
    }

    @Test
    @DisplayName("uploadPhoto - Upload thành công ảnh phân loại AFTER (Sau điều trị hoàn tất)")
    void uploadPhoto_Success_After() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "ket_qua.png", MediaType.IMAGE_PNG_VALUE, "result image data".getBytes()
        );

        when(patientProfileRepository.findById(1L)).thenReturn(Optional.of(mockPatient));
        when(clinicalPhotoRepository.save(any(ClinicalPhoto.class))).thenAnswer(inv -> {
            ClinicalPhoto p = inv.getArgument(0);
            p.setPhotoId(101L);
            return p;
        });

        ClinicalPhotoResponseDTO result = clinicalPhotoServiceImpl.uploadPhoto(
                1L, null, PhotoStage.AFTER, PhotoCategory.SMILE, "Kết quả nụ cười sau tháo niềng", file
        );

        assertNotNull(result);
        assertEquals(101L, result.getPhotoId());
        assertEquals(PhotoStage.AFTER, result.getPhotoStage());
        assertTrue(result.getImageUrl().endsWith(".png"));
    }

    @Test
    @DisplayName("uploadPhoto - Ném IllegalArgumentException khi file rỗng (Empty/Null)")
    void uploadPhoto_EmptyFile_ThrowsIllegalArgumentException() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "", "image/jpeg", new byte[0]);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                clinicalPhotoServiceImpl.uploadPhoto(1L, 300L, PhotoStage.BEFORE, PhotoCategory.INTRAORAL, null, emptyFile));

        assertEquals("Vui lòng chọn file hình ảnh.", ex.getMessage());
        verify(clinicalPhotoRepository, never()).save(any());
    }

    @Test
    @DisplayName("uploadPhoto - Ném IllegalArgumentException khi dung lượng file vượt quá 10MB")
    void uploadPhoto_FileSizeExceeded_ThrowsIllegalArgumentException() {
        byte[] largeContent = new byte[11 * 1024 * 1024];
        MockMultipartFile largeFile = new MockMultipartFile("file", "large.jpg", "image/jpeg", largeContent);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                clinicalPhotoServiceImpl.uploadPhoto(1L, 300L, PhotoStage.BEFORE, PhotoCategory.INTRAORAL, null, largeFile));

        assertEquals("Dung lượng ảnh không được vượt quá 10MB.", ex.getMessage());
        verify(clinicalPhotoRepository, never()).save(any());
    }

    @Test
    @DisplayName("uploadPhoto - Ném IllegalArgumentException khi không tìm thấy hồ sơ bệnh nhân")
    void uploadPhoto_PatientNotFound_ThrowsIllegalArgumentException() {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "content".getBytes());
        when(patientProfileRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                clinicalPhotoServiceImpl.uploadPhoto(999L, 300L, PhotoStage.BEFORE, PhotoCategory.INTRAORAL, null, file));
    }

    @Test
    @DisplayName("getPhotosByProfile - Lọc danh sách ảnh theo hồ sơ bệnh nhân")
    void getPhotosByProfile_Success() {
        when(clinicalPhotoRepository.findByPatientProfile_ProfileIdOrderByCapturedAtDesc(1L))
                .thenReturn(List.of(mockPhoto));

        List<ClinicalPhotoResponseDTO> photos = clinicalPhotoServiceImpl.getPhotosByProfile(1L);

        assertNotNull(photos);
        assertEquals(1, photos.size());
        assertEquals("Ảnh trước khi chỉnh nha", photos.get(0).getCaption());
    }

    @Test
    @DisplayName("getPhotosByVisit - Lọc danh sách ảnh theo lượt khám")
    void getPhotosByVisit_Success() {
        when(clinicalPhotoRepository.findByVisit_VisitIdOrderByCapturedAtDesc(300L))
                .thenReturn(List.of(mockPhoto));

        List<ClinicalPhotoResponseDTO> photos = clinicalPhotoServiceImpl.getPhotosByVisit(300L);

        assertNotNull(photos);
        assertEquals(1, photos.size());
        assertEquals(300L, photos.get(0).getVisitId());
    }

    @Test
    @DisplayName("updatePhoto - Cập nhật caption, stage và category thành công")
    void updatePhoto_Success() {
        when(clinicalPhotoRepository.findById(50L)).thenReturn(Optional.of(mockPhoto));
        when(clinicalPhotoRepository.save(any(ClinicalPhoto.class))).thenAnswer(inv -> inv.getArgument(0));

        ClinicalPhotoResponseDTO updated = clinicalPhotoServiceImpl.updatePhoto(
                50L, "Ảnh sau 3 tháng chỉnh nha", PhotoStage.DURING, PhotoCategory.INTRAORAL
        );

        assertNotNull(updated);
        assertEquals("Ảnh sau 3 tháng chỉnh nha", updated.getCaption());
        assertEquals(PhotoStage.DURING, updated.getPhotoStage());
        verify(clinicalPhotoRepository, times(1)).save(mockPhoto);
    }

    @Test
    @DisplayName("deletePhoto - Xóa ảnh trong DB và xóa file vật lý trên ổ đĩa thành công")
    void deletePhoto_Success() throws IOException {
        Path dir = tempUploadDir.resolve("clinical_photos");
        Files.createDirectories(dir);
        Path filePath = dir.resolve("cp_to_delete.jpg");
        Files.write(filePath, "fake image".getBytes());

        mockPhoto.setImageUrl("/uploads/clinical_photos/cp_to_delete.jpg");
        when(clinicalPhotoRepository.findById(50L)).thenReturn(Optional.of(mockPhoto));

        clinicalPhotoServiceImpl.deletePhoto(50L);

        verify(clinicalPhotoRepository, times(1)).delete(mockPhoto);
        assertFalse(Files.exists(filePath));
    }

    @Test
    @DisplayName("getPhotoStatistics - Thống kê chính xác số lượng ảnh theo các giai đoạn BEFORE/DURING/AFTER")
    void getPhotoStatistics_AccurateCounts() {
        when(clinicalPhotoRepository.countByPatientProfile_ProfileId(1L)).thenReturn(5L);
        when(clinicalPhotoRepository.countByPatientProfile_ProfileIdAndPhotoStage(1L, PhotoStage.BEFORE)).thenReturn(2L);
        when(clinicalPhotoRepository.countByPatientProfile_ProfileIdAndPhotoStage(1L, PhotoStage.DURING)).thenReturn(1L);
        when(clinicalPhotoRepository.countByPatientProfile_ProfileIdAndPhotoStage(1L, PhotoStage.AFTER)).thenReturn(2L);

        Map<String, Object> stats = clinicalPhotoServiceImpl.getPhotoStatistics(1L);

        assertNotNull(stats);
        assertEquals(5L, stats.get("total"));
        assertEquals(2L, stats.get("before"));
        assertEquals(1L, stats.get("during"));
        assertEquals(2L, stats.get("after"));
    }
}

