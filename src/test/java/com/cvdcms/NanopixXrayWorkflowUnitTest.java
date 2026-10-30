package com.cvdcms;

import com.cvdcms.DTO.NanopixReconciliationRequestDTO;
import com.cvdcms.DTO.NanopixSyncRequestDTO;
import com.cvdcms.DTO.NanopixSyncStatsDTO;
import com.cvdcms.DTO.NanopixXrayResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.NanopixSyncStatus;
import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.NanoPixIntegrationService;
import com.cvdcms.service.impl.NanoPixIntegrationServiceImpl;
import com.cvdcms.service.impl.NanopixXrayServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Test kiểm thử chức năng FT12: Quản lý kết quả phim X-quang NanoPix (Eighteeth):
 * 1. Nhận một kết quả hợp lệ.
 * 2. Nhận lại cùng mã tham chiếu và không tạo bản ghi trùng.
 * 3. Kết quả không xác định được bệnh nhân phải vào danh sách chưa đối chiếu.
 * 4. Không thể liên kết đến bệnh nhân hoặc lượt khám không hợp lệ.
 * 5. Liên kết thành công tạo lịch sử truy vết.
 * 6. Người không có quyền không thể xem ảnh hoặc thực hiện liên kết.
 * 7. Lỗi nguồn dữ liệu được ghi nhận đúng và không báo thành công giả.
 * 8. Lỗi khi lưu liên kết không để dữ liệu ở trạng thái dở dang.
 * 9. Kiểm tra tính toàn vẹn và tích hợp chỉ định CLS không bị ảnh hưởng.
 */
@ExtendWith(MockitoExtension.class)
class NanopixXrayWorkflowUnitTest {

    @Mock private NanopixXraySyncLogRepository nanopixRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private StaffProfileRepository staffProfileRepository;
    @Mock private ParaclinicalOrderItemRepository paraclinicalOrderItemRepository;
    @Mock private ParaclinicalResultAttachmentRepository attachmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private NanoPixIntegrationService nanoPixIntegrationService;
    @Mock private BranchRepository branchRepository;
    @Mock private StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    private NanoPixIntegrationServiceImpl integrationService;
    private NanopixXrayServiceImpl xrayService;

    private User doctorUser;
    private User patientUser;
    private StaffProfile doctorStaff;
    private PatientProfile patient1;
    private Visit visit1;
    private NanopixXraySyncLog sampleSyncLog;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        integrationService = new NanoPixIntegrationServiceImpl(
                nanopixRepository,
                patientProfileRepository,
                visitRepository,
                staffProfileRepository,
                paraclinicalOrderItemRepository,
                attachmentRepository
        );
        ReflectionTestUtils.setField(integrationService, "uploadDir", "target/test-uploads");

        xrayService = new NanopixXrayServiceImpl(
                nanopixRepository,
                patientProfileRepository,
                visitRepository,
                staffProfileRepository,
                paraclinicalOrderItemRepository,
                attachmentRepository,
                userRepository,
                nanoPixIntegrationService,
                branchRepository,
                staffBranchAssignmentRepository
        );

        // Doctor User
        doctorUser = new User();
        doctorUser.setUserId(10L);
        doctorUser.setUsername("doctor_nam");
        Role doctorRole = new Role();
        doctorRole.setRoleId(2L);
        doctorRole.setRoleName("BAC_SI");
        doctorUser.setRoles(Set.of(doctorRole));

        doctorStaff = new StaffProfile();
        doctorStaff.setStaffId(100L);
        doctorStaff.setUser(doctorUser);
        doctorStaff.setFullName("BS. Nguyễn Văn Nam");

        // Patient User (không có quyền vào trang X-quang)
        patientUser = new User();
        patientUser.setUserId(20L);
        patientUser.setUsername("patient_an");
        Role patientRole = new Role();
        patientRole.setRoleId(5L);
        patientRole.setRoleName("BENH_NHAN");
        patientUser.setRoles(Set.of(patientRole));

        // Patient Profile
        patient1 = new PatientProfile();
        patient1.setProfileId(200L);
        patient1.setPatientCode("BN2026001");
        patient1.setFullName("Lê Thị An");

        Branch branch1 = new Branch();
        branch1.setBranchId(1L);
        branch1.setBranchName("Cơ sở Cầu Giấy");

        doctorUser.setBranch(branch1);

        // Visit
        visit1 = new Visit();
        visit1.setVisitId(500L);
        visit1.setVisitCode("KHAM-20261025-01");
        visit1.setPatientProfile(patient1);
        visit1.setBranch(branch1);
        visit1.setStatus(VisitStatus.WAITING_PARACLINICAL);

        // Sample X-ray log
        sampleSyncLog = new NanopixXraySyncLog();
        sampleSyncLog.setSyncLogId(1L);
        sampleSyncLog.setExternalStudyUid("20261025_193542_kim_ngoc");
        sampleSyncLog.setRawPatientName("kim ngoc");
        sampleSyncLog.setImageUrl("/uploads/nanopix/demo_xray.jpg");
        sampleSyncLog.setSyncStatus(NanopixSyncStatus.UNMATCHED);
        sampleSyncLog.setCapturedAt(LocalDateTime.now());
        sampleSyncLog.setDeviceSerialNumber("EN1E0408A032");
    }

    @Test
    @DisplayName("Case 1: Nhận một kết quả X-quang hợp lệ từ máy NanoPix")
    void ingestXray_valid_success() throws IOException {
        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
                .externalStudyUid("20261025_100101_test")
                .rawPatientName("Tran Van Test")
                .deviceSerialNumber("EN1E0408A032")
                .build();

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg", new byte[]{1, 2, 3, 4}
        );

        when(nanopixRepository.existsByExternalStudyUid("20261025_100101_test")).thenReturn(false);
        when(nanopixRepository.save(any(NanopixXraySyncLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NanopixXraySyncLog result = integrationService.ingestXray(req, file, 100L);

        assertNotNull(result);
        assertEquals("20261025_100101_test", result.getExternalStudyUid());
        assertEquals("Tran Van Test", result.getRawPatientName());
        assertTrue(result.getImageUrl().startsWith("/uploads/nanopix/"));
        verify(nanopixRepository, times(1)).save(any(NanopixXraySyncLog.class));
    }

    @Test
    @DisplayName("Case 2: Nhận lại cùng mã tham chiếu (externalStudyUid) -> Chặn và không tạo bản ghi trùng")
    void ingestXray_duplicateStudyUid_throwsException() {
        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
                .externalStudyUid("20261025_193542_kim_ngoc")
                .build();

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );

        when(nanopixRepository.existsByExternalStudyUid("20261025_193542_kim_ngoc")).thenReturn(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                integrationService.ingestXray(req, file, 100L)
        );

        assertTrue(ex.getMessage().contains("đã tồn tại trên hệ thống"));
        verify(nanopixRepository, never()).save(any());
    }

    @Test
    @DisplayName("Case 3: Kết quả không xác định được bệnh nhân phải lưu ở trạng thái UNMATCHED")
    void ingestXray_unknownPatient_savedAsUnmatched() throws IOException {
        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
                .externalStudyUid("20261025_999999_unknown")
                .rawPatientName("Khong Co Trong He Thong")
                .externalPatientId("BN_INVALID_999")
                .build();

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );

        when(nanopixRepository.existsByExternalStudyUid("20261025_999999_unknown")).thenReturn(false);
        when(patientProfileRepository.findByPatientCode("BN_INVALID_999")).thenReturn(Optional.empty());
        when(nanopixRepository.save(any(NanopixXraySyncLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NanopixXraySyncLog result = integrationService.ingestXray(req, file, null);

        assertNotNull(result);
        assertEquals(NanopixSyncStatus.UNMATCHED, result.getSyncStatus());
        assertNull(result.getMatchedProfile());
        assertNull(result.getMatchedVisit());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("Case 4: Không thể liên kết nếu lượt khám không thuộc về bệnh nhân được chọn")
    void reconcile_visitNotBelongToPatient_throwsException() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(doctorUser));
        when(nanopixRepository.findById(1L)).thenReturn(Optional.of(sampleSyncLog));
        when(patientProfileRepository.findById(200L)).thenReturn(Optional.of(patient1));

        // Lượt khám thuộc bệnh nhân khác (Profile ID 999L)
        PatientProfile otherPatient = new PatientProfile();
        otherPatient.setProfileId(999L);
        otherPatient.setFullName("Bệnh nhân khác");

        Visit mismatchVisit = new Visit();
        mismatchVisit.setVisitId(888L);
        mismatchVisit.setVisitCode("KHAM-OTHER");
        mismatchVisit.setPatientProfile(otherPatient);

        when(visitRepository.findById(888L)).thenReturn(Optional.of(mismatchVisit));

        NanopixReconciliationRequestDTO dto = NanopixReconciliationRequestDTO.builder()
                .profileId(200L)
                .visitId(888L)
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                xrayService.reconcileXray(1L, dto, 10L)
        );

        assertTrue(ex.getMessage().contains("không thuộc về bệnh nhân"));
        verify(nanopixRepository, never()).save(any());
    }

    @Test
    @DisplayName("Case 5: Liên kết thành công tạo vết truy vết (is_manual_linked, linked_by, linked_at)")
    void reconcile_validPatientAndVisit_successWithAudit() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(doctorUser));
        when(nanopixRepository.findById(1L)).thenReturn(Optional.of(sampleSyncLog));
        when(patientProfileRepository.findById(200L)).thenReturn(Optional.of(patient1));
        when(visitRepository.findById(500L)).thenReturn(Optional.of(visit1));
        when(staffProfileRepository.findByUser_UserId(10L)).thenReturn(Optional.of(doctorStaff));
        when(nanopixRepository.save(any(NanopixXraySyncLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NanopixReconciliationRequestDTO dto = NanopixReconciliationRequestDTO.builder()
                .profileId(200L)
                .visitId(500L)
                .toothNumbers("21, 22")
                .notes("Đã đối chiếu khớp ca khám")
                .build();

        NanopixXrayResponseDTO result = xrayService.reconcileXray(1L, dto, 10L);

        assertNotNull(result);
        assertEquals("MATCHED", result.getSyncStatus());
        assertEquals("BN2026001", result.getPatientCode());
        assertEquals("KHAM-20261025-01", result.getVisitCode());
        assertEquals("21, 22", result.getToothNumbers());
        assertTrue(result.getIsManualLinked());
        assertEquals("BS. Nguyễn Văn Nam", result.getLinkedByName());
        assertNotNull(result.getLinkedAt());
    }

    @Test
    @DisplayName("Case 6: Người không có quyền (Patient, Kho...) bị chặn bằng lỗi 403 FORBIDDEN")
    void unauthorizedRole_throwsForbiddenException() {
        when(userRepository.findById(20L)).thenReturn(Optional.of(patientUser));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                xrayService.getUnmatchedXrays("", 20L)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Bạn không có quyền truy cập"));
    }

    @Test
    @DisplayName("Case 7: Lỗi nguồn dữ liệu (File không đúng định dạng hoặc quá lớn) không được báo thành công giả")
    void ingestXray_invalidFile_throwsException() {
        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
                .externalStudyUid("20261025_exe_file")
                .build();

        MockMultipartFile exeFile = new MockMultipartFile(
                "file", "virus.exe", "application/octet-stream", new byte[]{1, 2, 3}
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                integrationService.ingestXray(req, fileWithInvalidExt(exeFile), 100L)
        );

        assertTrue(ex.getMessage().contains("Định dạng file không được hỗ trợ"));
    }

    private MultipartFile fileWithInvalidExt(MultipartFile file) {
        return file;
    }

    @Test
    @DisplayName("Case 8: Lỗi khi lưu đối chiếu với ID phim không tồn tại ném lỗi 404 NOT_FOUND")
    void reconcile_nonExistentSyncLog_throwsNotFound() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(doctorUser));
        when(nanopixRepository.findById(9999L)).thenReturn(Optional.empty());

        NanopixReconciliationRequestDTO dto = NanopixReconciliationRequestDTO.builder()
                .profileId(200L)
                .visitId(500L)
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                xrayService.reconcileXray(9999L, dto, 10L)
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("Case 9: Đối chiếu gắn với Chỉ định CLS X-quang -> Tự động hoàn thành chỉ định và tạo Attachment")
    void reconcile_withParaclinicalOrderItem_createsAttachmentAndCompletes() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(doctorUser));
        when(nanopixRepository.findById(1L)).thenReturn(Optional.of(sampleSyncLog));
        when(patientProfileRepository.findById(200L)).thenReturn(Optional.of(patient1));
        when(visitRepository.findById(500L)).thenReturn(Optional.of(visit1));

        ParaclinicalOrder order = new ParaclinicalOrder();
        order.setOrderId(70L);
        order.setVisit(visit1);

        ParaclinicalOrderItem item = new ParaclinicalOrderItem();
        item.setOrderItemId(701L);
        item.setOrder(order);
        item.setStatus(ParaclinicalOrderItemStatus.WAITING);

        when(paraclinicalOrderItemRepository.findById(701L)).thenReturn(Optional.of(item));
        when(nanopixRepository.save(any(NanopixXraySyncLog.class))).thenAnswer(inv -> inv.getArgument(0));

        NanopixReconciliationRequestDTO dto = NanopixReconciliationRequestDTO.builder()
                .profileId(200L)
                .visitId(500L)
                .orderItemId(701L)
                .toothNumbers("36")
                .build();

        NanopixXrayResponseDTO result = xrayService.reconcileXray(1L, dto, 10L);

        assertNotNull(result);
        assertEquals(ParaclinicalOrderItemStatus.COMPLETED, item.getStatus());
        verify(paraclinicalOrderItemRepository, times(1)).save(item);
        verify(attachmentRepository, times(1)).save(any());
    }


    @Test
    @DisplayName("Case 10: Giới hạn cơ sở - Bác sĩ cơ sở 1 không thể đối chiếu ca khám thuộc cơ sở 2 (Chặn 403 FORBIDDEN)")
    void doctorFromBranch1_cannotReconcileVisitFromBranch2_throwsForbidden() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(doctorUser));
        when(nanopixRepository.findById(1L)).thenReturn(Optional.of(sampleSyncLog));
        when(patientProfileRepository.findById(200L)).thenReturn(Optional.of(patient1));

        Branch branch2 = new Branch();
        branch2.setBranchId(2L);
        branch2.setBranchName("Cơ sở Đống Đa");

        Visit visitBranch2 = new Visit();
        visitBranch2.setVisitId(502L);
        visitBranch2.setVisitCode("KHAM-BRANCH2-01");
        visitBranch2.setPatientProfile(patient1);
        visitBranch2.setBranch(branch2);
        visitBranch2.setStatus(VisitStatus.WAITING_PARACLINICAL);

        when(visitRepository.findById(502L)).thenReturn(Optional.of(visitBranch2));

        NanopixReconciliationRequestDTO dto = NanopixReconciliationRequestDTO.builder()
                .profileId(200L)
                .visitId(502L)
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                xrayService.reconcileXray(1L, dto, 10L)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("cơ sở khác"));
    }

    public static void main(String[] args) {
        NanopixXrayWorkflowUnitTest test = new NanopixXrayWorkflowUnitTest();
        int passed = 0;
        int failed = 0;

        System.out.println("=======================================================");
        System.out.println(" RUNNING NanopixXrayWorkflowUnitTest (FT12 Test Suite)");
        System.out.println("=======================================================");

        for (java.lang.reflect.Method m : NanopixXrayWorkflowUnitTest.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(org.junit.jupiter.api.Test.class)) {
                try {
                    test.setUp();
                    m.invoke(test);
                    String desc = m.isAnnotationPresent(org.junit.jupiter.api.DisplayName.class)
                            ? m.getAnnotation(org.junit.jupiter.api.DisplayName.class).value()
                            : m.getName();
                    System.out.println("  [PASS] " + m.getName() + ": " + desc);
                    passed++;
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    System.err.println("  [FAIL] " + m.getName() + ": " + cause.getMessage());
                    cause.printStackTrace();
                    failed++;
                }
            }
        }

        System.out.println("=======================================================");
        System.out.println(" TEST SUMMARY: " + passed + " PASSED, " + failed + " FAILED");
        System.out.println("=======================================================");

        if (failed > 0) System.exit(1);
    }
}
