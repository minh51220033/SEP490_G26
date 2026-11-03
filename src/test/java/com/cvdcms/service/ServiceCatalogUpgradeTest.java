package com.cvdcms.service;

import com.cvdcms.DTO.ServiceCreateDTO;
import com.cvdcms.DTO.ServiceUpdateDTO;
import com.cvdcms.controller.AdminServiceCatalogController;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.ServiceCategory;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.ServiceStatus;
import com.cvdcms.entity.enums.ServiceType;
import com.cvdcms.repository.ServiceCategoryRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.impl.ServiceCatalogServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceCatalogUpgradeTest {

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private ServiceCategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    private Validator validator;
    private ServiceCatalogServiceImpl serviceCatalogService;
    private AdminServiceCatalogController controller;
    private ServiceCategory activeCategory;
    private final List<Path> createdTestFiles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        serviceCatalogService = new ServiceCatalogServiceImpl(
                serviceRepository,
                categoryRepository,
                validator
        );
        controller = new AdminServiceCatalogController(serviceCatalogService, userRepository);

        activeCategory = new ServiceCategory();
        activeCategory.setCategoryId(1L);
        activeCategory.setCategoryCode("NHA_CHU");
        activeCategory.setCategoryName("Nha chu & Điều trị nướu");
        activeCategory.setCategoryType(ServiceType.PROCEDURE);
        activeCategory.setDisplayOrder(1);
        activeCategory.setIsActive(true);

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(activeCategory));
    }

    @AfterEach
    void tearDown() {
        for (Path p : createdTestFiles) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
    }

    private byte[] createValidPngBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89
        };
    }

    private String createPhysicalTestServiceImage(String filename) throws IOException {
        Path dir = Paths.get("uploads", "services").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);
        Files.write(file, createValidPngBytes());
        createdTestFiles.add(file);
        return "/uploads/services/" + filename;
    }

    private ServiceCreateDTO buildValidCreateDTO() {
        ServiceCreateDTO dto = new ServiceCreateDTO();
        dto.setCategoryId(1L);
        dto.setServiceCode("dv_cao_voi_01");
        dto.setServiceName("Cạo vôi răng siêu âm");
        dto.setServiceType(ServiceType.PROCEDURE);
        dto.setUnit("Lần");
        dto.setBasePrice(new BigDecimal("250000.00"));
        dto.setSurchargeAmount(BigDecimal.ZERO);
        dto.setRequiresTooth(false);
        dto.setIsBookableOnline(true);
        dto.setWarrantyMonths(6);
        dto.setStatus(ServiceStatus.ACTIVE);
        return dto;
    }

    private ServiceUpdateDTO buildValidUpdateDTO() {
        ServiceUpdateDTO dto = new ServiceUpdateDTO();
        dto.setCategoryId(1L);
        dto.setServiceName("Cạo vôi răng & Đánh bóng Pro");
        dto.setServiceType(ServiceType.PROCEDURE);
        dto.setUnit("Lần");
        dto.setBasePrice(new BigDecimal("300000.00"));
        dto.setSurchargeAmount(new BigDecimal("20000.00"));
        dto.setRequiresTooth(false);
        dto.setIsBookableOnline(true);
        dto.setWarrantyMonths(12);
        dto.setStatus(ServiceStatus.ACTIVE);
        return dto;
    }

    // 1. Tạo dịch vụ mới không có ảnh & không có mô tả
    @Test
    void createService_WithoutImageAndDescription_SavesSuccessfully() {
        ServiceCreateDTO dto = buildValidCreateDTO();
        dto.setImageUrl(null);
        dto.setDescription("   ");

        serviceCatalogService.createService(dto);

        ArgumentCaptor<Service> captor = ArgumentCaptor.forClass(Service.class);
        verify(serviceRepository).save(captor.capture());
        Service saved = captor.getValue();
        assertEquals("DV_CAO_VOI_01", saved.getServiceCode());
        assertEquals("Cạo vôi răng siêu âm", saved.getServiceName());
        assertNull(saved.getImageUrl());
        assertNull(saved.getDescription());
    }

    // 2. Tạo dịch vụ mới có ảnh và mô tả CKEditor HTML
    @Test
    void createService_WithValidImageAndCkEditorHtml_SavesSanitizedHtmlAndImageUrl() throws IOException {
        String imgUrl = createPhysicalTestServiceImage("test-create-service.png");
        ServiceCreateDTO dto = buildValidCreateDTO();
        dto.setImageUrl(imgUrl);
        dto.setDescription("<h3>Quy trình thực hiện</h3><p><strong>Bước 1:</strong> Khám tổng quát</p><ul><li>Làm sạch mảng bám</li></ul>");

        serviceCatalogService.createService(dto);

        ArgumentCaptor<Service> captor = ArgumentCaptor.forClass(Service.class);
        verify(serviceRepository).save(captor.capture());
        Service saved = captor.getValue();
        assertEquals(imgUrl, saved.getImageUrl());
        assertNotNull(saved.getDescription());
        assertTrue(saved.getDescription().contains("<h3>Quy trình thực hiện</h3>"));
        assertTrue(saved.getDescription().contains("<strong>Bước 1:</strong>"));
        assertTrue(saved.getDescription().contains("<li>Làm sạch mảng bám</li>"));
    }

    // 3. Ngăn chặn XSS trong mô tả dịch vụ (script, event handler, URL nguy hiểm)
    @Test
    void createService_WithMaliciousHtml_StripsScriptEventHandlersAndJavascriptUrls() {
        ServiceCreateDTO dto = buildValidCreateDTO();
        dto.setDescription(
                "<p onclick=\"alert('xss')\" style=\"background:url(javascript:alert(1))\">Mô tả an toàn</p>"
                + "<script>window.stealCookies()</script>"
                + "<img src=\"/uploads/services/pic.png\" onerror=\"alert(2)\">"
                + "<a href=\"javascript:evil()\">Liên kết độc hại</a>"
        );

        serviceCatalogService.createService(dto);

        ArgumentCaptor<Service> captor = ArgumentCaptor.forClass(Service.class);
        verify(serviceRepository).save(captor.capture());
        Service saved = captor.getValue();
        String sanitized = saved.getDescription();

        assertNotNull(sanitized);
        assertTrue(sanitized.contains("Mô tả an toàn"));
        assertFalse(sanitized.toLowerCase().contains("<script"));
        assertFalse(sanitized.toLowerCase().contains("onclick"));
        assertFalse(sanitized.toLowerCase().contains("onerror"));
        assertFalse(sanitized.toLowerCase().contains("javascript:"));
    }

    // 4. Không để database lưu đường dẫn ảnh không tồn tại
    @Test
    void createService_WithNonExistentImageUrl_ThrowsIllegalArgumentException() {
        ServiceCreateDTO dto = buildValidCreateDTO();
        dto.setImageUrl("/uploads/services/non-existent-file-99999.png");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> serviceCatalogService.createService(dto)
        );
        assertTrue(ex.getMessage().contains("không tồn tại"));
        verify(serviceRepository, never()).save(any());
    }

    // 5. Thay ảnh dịch vụ: cập nhật ảnh mới và xóa file vật lý cũ khi không dùng chung
    @Test
    void updateService_ReplaceImage_DeletesUnusedOldFileAndSavesNewImage() throws IOException {
        String oldImgUrl = createPhysicalTestServiceImage("test-old-replace.png");
        String newImgUrl = createPhysicalTestServiceImage("test-new-replace.png");
        Path oldFilePath = Paths.get("uploads", "services", "test-old-replace.png").toAbsolutePath().normalize();

        Service existing = new Service();
        existing.setServiceId(10L);
        existing.setCategory(activeCategory);
        existing.setServiceCode("DV_TEST_10");
        existing.setServiceName("Dịch vụ cũ");
        existing.setServiceType(ServiceType.PROCEDURE);
        existing.setImageUrl(oldImgUrl);
        existing.setStatus(ServiceStatus.ACTIVE);

        when(serviceRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByImageUrlAndServiceIdNot(oldImgUrl, 10L)).thenReturn(false);

        ServiceUpdateDTO updateDto = buildValidUpdateDTO();
        updateDto.setImageUrl(newImgUrl);
        updateDto.setDescription("<p>Cập nhật mô tả mới</p>");

        serviceCatalogService.updateService(10L, updateDto);

        assertEquals(newImgUrl, existing.getImageUrl());
        assertFalse(Files.exists(oldFilePath), "File ảnh cũ không còn dùng chung phải được dọn dẹp");
    }

    // 6. Không xóa file vật lý nếu ảnh cũ đang được dịch vụ khác sử dụng chung
    @Test
    void updateService_RemoveSharedImage_DoesNotDeleteSharedPhysicalFile() throws IOException {
        String sharedImgUrl = createPhysicalTestServiceImage("test-shared-image.png");
        Path sharedFilePath = Paths.get("uploads", "services", "test-shared-image.png").toAbsolutePath().normalize();

        Service existing = new Service();
        existing.setServiceId(11L);
        existing.setCategory(activeCategory);
        existing.setServiceCode("DV_TEST_11");
        existing.setServiceName("Dịch vụ dùng chung ảnh");
        existing.setServiceType(ServiceType.PROCEDURE);
        existing.setImageUrl(sharedImgUrl);
        existing.setStatus(ServiceStatus.ACTIVE);

        when(serviceRepository.findById(11L)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByImageUrlAndServiceIdNot(sharedImgUrl, 11L)).thenReturn(true);

        ServiceUpdateDTO updateDto = buildValidUpdateDTO();
        updateDto.setImageUrl(""); // Xóa ảnh khỏi dịch vụ 11

        serviceCatalogService.updateService(11L, updateDto);

        assertNull(existing.getImageUrl());
        assertTrue(Files.exists(sharedFilePath), "File ảnh đang được dịch vụ khác dùng chung KHÔNG được xóa vật lý");
    }

    // 7. Lưu dịch vụ khi không thay đổi ảnh (giữ nguyên ảnh hiện tại)
    @Test
    void updateService_KeepSameImage_PreservesImageAndFile() throws IOException {
        String currentImgUrl = createPhysicalTestServiceImage("test-keep-same.png");
        Path currentFilePath = Paths.get("uploads", "services", "test-keep-same.png").toAbsolutePath().normalize();

        Service existing = new Service();
        existing.setServiceId(12L);
        existing.setCategory(activeCategory);
        existing.setServiceCode("DV_TEST_12");
        existing.setServiceName("Dịch vụ giữ nguyên ảnh");
        existing.setServiceType(ServiceType.PROCEDURE);
        existing.setImageUrl(currentImgUrl);
        existing.setStatus(ServiceStatus.ACTIVE);

        when(serviceRepository.findById(12L)).thenReturn(Optional.of(existing));

        ServiceUpdateDTO updateDto = buildValidUpdateDTO();
        updateDto.setImageUrl(currentImgUrl);

        serviceCatalogService.updateService(12L, updateDto);

        assertEquals(currentImgUrl, existing.getImageUrl());
        assertTrue(Files.exists(currentFilePath));
    }

    // 8. Kiểm tra Upload Image Endpoint trên AdminServiceCatalogController
    @Test
    void uploadServiceImage_ValidPng_Returns200AndSavesFile() throws IOException {
        MockHttpSession session = new MockHttpSession();
        User admin = new User();
        admin.setUserId(1L);
        admin.setUsername("admin");
        session.setAttribute("loggedInUser", admin);
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        MockMultipartFile validPng = new MockMultipartFile(
                "file",
                "dental-service.png",
                "image/png",
                createValidPngBytes()
        );

        ResponseEntity<Map<String, Object>> response = controller.uploadServiceImage(validPng, null, session);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        String uploadedUrl = (String) response.getBody().get("url");
        assertNotNull(uploadedUrl);
        assertTrue(uploadedUrl.startsWith("/uploads/services/"));
        assertTrue(uploadedUrl.endsWith(".png"));

        Path savedFile = Paths.get("uploads").toAbsolutePath().normalize()
                .resolve(uploadedUrl.substring("/uploads/".length())).normalize();
        createdTestFiles.add(savedFile);
        assertTrue(Files.exists(savedFile));
    }

    // 9. Kiểm tra Upload Image Endpoint: Từ chối file giả mạo hoặc sai định dạng
    @Test
    void uploadServiceImage_InvalidFormatOrFakeImage_Returns400() {
        MockHttpSession session = new MockHttpSession();
        User admin = new User();
        admin.setUserId(1L);
        admin.setUsername("admin");
        session.setAttribute("loggedInUser", admin);
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        // File .exe đổi tên hoặc sai MIME
        MockMultipartFile exeFile = new MockMultipartFile(
                "file",
                "malware.exe",
                "application/octet-stream",
                new byte[]{1, 2, 3, 4}
        );
        ResponseEntity<Map<String, Object>> res1 = controller.uploadServiceImage(exeFile, null, session);
        assertEquals(HttpStatus.BAD_REQUEST, res1.getStatusCode());

        // File giả mạo: tên .png, MIME image/png nhưng nội dung là script text
        MockMultipartFile fakePng = new MockMultipartFile(
                "file",
                "fake.png",
                "image/png",
                "<script>alert(1)</script>".getBytes()
        );
        ResponseEntity<Map<String, Object>> res2 = controller.uploadServiceImage(fakePng, null, session);
        assertEquals(HttpStatus.BAD_REQUEST, res2.getStatusCode());
    }
}
