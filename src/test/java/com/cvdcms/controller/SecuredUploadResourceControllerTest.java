package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.NanopixXraySyncLog;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.repository.NanopixXraySyncLogRepository;
import com.cvdcms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecuredUploadResourceControllerTest {

    private SecuredUploadResourceController controller;
    private UserRepository userRepository;
    private NanopixXraySyncLogRepository nanopixRepository;
    private MockHttpSession sessionWithDoctor;
    private MockHttpSession sessionWithPatient;
    private MockHttpSession sessionWithAdmin;
    private MockHttpSession emptySession;

    private User doctorUser;
    private User patientUser;
    private User adminUser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        userRepository = mock(UserRepository.class);
        nanopixRepository = mock(NanopixXraySyncLogRepository.class);

        controller = new SecuredUploadResourceController(userRepository, nanopixRepository);
        ReflectionTestUtils.setField(controller, "uploadDir", tempDir.toString());

        Branch branch1 = new Branch();
        branch1.setBranchId(1L);
        branch1.setBranchName("Cơ sở 1");

        Branch branch2 = new Branch();
        branch2.setBranchId(2L);
        branch2.setBranchName("Cơ sở 2");

        // Doctor (Branch 1)
        doctorUser = new User();
        doctorUser.setUserId(100L);
        doctorUser.setUsername("bacsi");
        doctorUser.setBranch(branch1);
        Role doctorRole = new Role();
        doctorRole.setRoleId(2L);
        doctorRole.setRoleName("BAC_SI");
        doctorUser.setRoles(Set.of(doctorRole));

        sessionWithDoctor = new MockHttpSession();
        sessionWithDoctor.setAttribute("loggedInUser", doctorUser);

        // Patient
        patientUser = new User();
        patientUser.setUserId(200L);
        patientUser.setUsername("benhnhan");
        Role patientRole = new Role();
        patientRole.setRoleId(6L);
        patientRole.setRoleName("BENH_NHAN");
        patientUser.setRoles(Set.of(patientRole));

        sessionWithPatient = new MockHttpSession();
        sessionWithPatient.setAttribute("loggedInUser", patientUser);

        // Admin (Branch 1)
        adminUser = new User();
        adminUser.setUserId(1L);
        adminUser.setUsername("admin");
        adminUser.setBranch(branch1);
        Role adminRole = new Role();
        adminRole.setRoleId(1L);
        adminRole.setRoleName("ADMIN");
        adminUser.setRoles(Set.of(adminRole));

        sessionWithAdmin = new MockHttpSession();
        sessionWithAdmin.setAttribute("loggedInUser", adminUser);

        emptySession = new MockHttpSession();

        when(userRepository.findById(100L)).thenReturn(Optional.of(doctorUser));
        when(userRepository.findById(200L)).thenReturn(Optional.of(patientUser));
        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));
    }

    @Test
    @DisplayName("1. Từ chối truy cập ẩn danh (không session) -> Ném 401 UNAUTHORIZED")
    void getSecuredFile_unauthenticated_returns401() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.getSecuredFile("nanopix", "xray_01.jpg", emptySession)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Vui lòng đăng nhập"));
    }

    @Test
    @DisplayName("2. Bác sĩ đã đăng nhập truy cập tệp hợp lệ -> Trả về 200 OK với inline resource")
    void getSecuredFile_authenticated_returns200() throws IOException {
        Path categoryDir = tempDir.resolve("nanopix");
        Files.createDirectories(categoryDir);
        Path testFile = categoryDir.resolve("xray_01.jpg");
        Files.writeString(testFile, "MOCK_XRAY_IMAGE_DATA");

        ResponseEntity<Resource> response = controller.getSecuredFile("nanopix", "xray_01.jpg", sessionWithDoctor);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getHeaders().getFirst("Cache-Control").contains("private"));
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("inline"));
    }

    @Test
    @DisplayName("3. Chặn tấn công Path Traversal (chứa .. hoặc kí tự phân tách) -> Ném 400 BAD_REQUEST")
    void getSecuredFile_pathTraversal_returns400() {
        ResponseStatusException ex1 = assertThrows(ResponseStatusException.class, () ->
                controller.getSecuredFile("clinical_photos", "../secret.txt", sessionWithDoctor)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex1.getStatusCode());

        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class, () ->
                controller.getSecuredFile("invoices", "sub/file.pdf", sessionWithDoctor)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex2.getStatusCode());
    }

    @Test
    @DisplayName("4. Truy cập tệp không tồn tại trên ổ cứng -> Ném 404 NOT_FOUND")
    void getSecuredFile_fileNotFound_returns404() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.getSecuredFile("invoices", "non_existent.pdf", sessionWithDoctor)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("5. Truy cập danh mục không được hỗ trợ -> Ném 404 NOT_FOUND")
    void getSecuredFile_invalidCategory_returns404() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.getSecuredFile("unknown_folder", "file.txt", sessionWithDoctor)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("6. Chặn bệnh nhân truy cập chữ ký nội bộ (signatures) -> Ném 403 FORBIDDEN")
    void getSecuredFile_patientBlockedFromSignatures_returns403() throws IOException {
        Path categoryDir = tempDir.resolve("signatures");
        Files.createDirectories(categoryDir);
        Path testFile = categoryDir.resolve("staff_sign.png");
        Files.writeString(testFile, "MOCK_SIGNATURE");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.getSecuredFile("signatures", "staff_sign.png", sessionWithPatient)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("7. Chặn bệnh nhân truy cập đơn hàng Labo nội bộ -> Ném 403 FORBIDDEN")
    void getSecuredFile_patientBlockedFromLabo_returns403() throws IOException {
        Path categoryDir = tempDir.resolve("labo");
        Files.createDirectories(categoryDir);
        Path testFile = categoryDir.resolve("labo_01.pdf");
        Files.writeString(testFile, "MOCK_LABO");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.getSecuredFile("labo", "labo_01.pdf", sessionWithPatient)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("8. Cho phép bệnh nhân truy cập phim X-quang thuộc hồ sơ cá nhân của mình -> Trả về 200 OK")
    void getSecuredFile_patientAllowedForOwnXray() throws IOException {
        Path categoryDir = tempDir.resolve("nanopix");
        Files.createDirectories(categoryDir);
        Path testFile = categoryDir.resolve("own_xray.jpg");
        Files.writeString(testFile, "PATIENT_XRAY");

        PatientProfile ownProfile = new PatientProfile();
        ownProfile.setUser(patientUser);

        NanopixXraySyncLog syncLog = new NanopixXraySyncLog();
        syncLog.setImageUrl("/uploads/nanopix/own_xray.jpg");
        syncLog.setMatchedProfile(ownProfile);

        when(nanopixRepository.findFirstByImageUrlOrderByCapturedAtDesc("/uploads/nanopix/own_xray.jpg"))
                .thenReturn(Optional.of(syncLog));

        ResponseEntity<Resource> response = controller.getSecuredFile("nanopix", "own_xray.jpg", sessionWithPatient);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("9. Chặn bệnh nhân truy cập phim X-quang của bệnh nhân khác -> Ném 403 FORBIDDEN")
    void getSecuredFile_patientBlockedFromOtherPatientXray_returns403() throws IOException {
        Path categoryDir = tempDir.resolve("nanopix");
        Files.createDirectories(categoryDir);
        Path testFile = categoryDir.resolve("other_xray.jpg");
        Files.writeString(testFile, "OTHER_XRAY");

        User otherPatient = new User();
        otherPatient.setUserId(999L);
        PatientProfile otherProfile = new PatientProfile();
        otherProfile.setUser(otherPatient);

        NanopixXraySyncLog syncLog = new NanopixXraySyncLog();
        syncLog.setImageUrl("/uploads/nanopix/other_xray.jpg");
        syncLog.setMatchedProfile(otherProfile);

        when(nanopixRepository.findFirstByImageUrlOrderByCapturedAtDesc("/uploads/nanopix/other_xray.jpg"))
                .thenReturn(Optional.of(syncLog));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.getSecuredFile("nanopix", "other_xray.jpg", sessionWithPatient)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("10. Chặn bệnh nhân truy cập phim X-quang CHƯA KHỚP (UNMATCHED) -> Ném 403 FORBIDDEN")
    void getSecuredFile_patientBlockedFromUnmatchedXray_returns403() throws IOException {
        Path categoryDir = tempDir.resolve("nanopix");
        Files.createDirectories(categoryDir);
        Path testFile = categoryDir.resolve("unmatched_xray.jpg");
        Files.writeString(testFile, "UNMATCHED_XRAY");

        NanopixXraySyncLog syncLog = new NanopixXraySyncLog();
        syncLog.setImageUrl("/uploads/nanopix/unmatched_xray.jpg");
        syncLog.setMatchedProfile(null); // Chưa khớp hồ sơ nào

        when(nanopixRepository.findFirstByImageUrlOrderByCapturedAtDesc("/uploads/nanopix/unmatched_xray.jpg"))
                .thenReturn(Optional.of(syncLog));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.getSecuredFile("nanopix", "unmatched_xray.jpg", sessionWithPatient)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("11. Chặn nhân sự cơ sở 1 xem phim X-quang thuộc cơ sở 2 (Branch Isolation) -> Ném 403 FORBIDDEN")
    void getSecuredFile_staffBlockedFromOtherBranchXray_returns403() throws IOException {
        Path categoryDir = tempDir.resolve("nanopix");
        Files.createDirectories(categoryDir);
        Path testFile = categoryDir.resolve("branch2_xray.jpg");
        Files.writeString(testFile, "BRANCH2_XRAY");

        Branch branch2 = new Branch();
        branch2.setBranchId(2L);

        NanopixXraySyncLog syncLog = new NanopixXraySyncLog();
        syncLog.setImageUrl("/uploads/nanopix/branch2_xray.jpg");
        syncLog.setBranch(branch2);

        when(nanopixRepository.findFirstByImageUrlOrderByCapturedAtDesc("/uploads/nanopix/branch2_xray.jpg"))
                .thenReturn(Optional.of(syncLog));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.getSecuredFile("nanopix", "branch2_xray.jpg", sessionWithDoctor)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("12. Admin được quyền xem phim X-quang liên chi nhánh -> Trả về 200 OK")
    void getSecuredFile_adminAllowedAcrossBranches_returns200() throws IOException {
        Path categoryDir = tempDir.resolve("nanopix");
        Files.createDirectories(categoryDir);
        Path testFile = categoryDir.resolve("branch2_xray_admin.jpg");
        Files.writeString(testFile, "BRANCH2_XRAY_DATA");

        Branch branch2 = new Branch();
        branch2.setBranchId(2L);

        NanopixXraySyncLog syncLog = new NanopixXraySyncLog();
        syncLog.setImageUrl("/uploads/nanopix/branch2_xray_admin.jpg");
        syncLog.setBranch(branch2);

        when(nanopixRepository.findFirstByImageUrlOrderByCapturedAtDesc("/uploads/nanopix/branch2_xray_admin.jpg"))
                .thenReturn(Optional.of(syncLog));

        ResponseEntity<Resource> response = controller.getSecuredFile("nanopix", "branch2_xray_admin.jpg", sessionWithAdmin);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
