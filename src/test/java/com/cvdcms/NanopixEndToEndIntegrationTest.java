//package com.cvdcms;
//
//import com.cvdcms.DTO.*;
//import com.cvdcms.controller.NanopixXrayRestController;
//import com.cvdcms.controller.SecuredUploadResourceController;
//import com.cvdcms.entity.*;
//import com.cvdcms.entity.enums.*;
//import com.cvdcms.repository.*;
//import com.cvdcms.service.impl.NanoPixIntegrationServiceImpl;
//import com.cvdcms.service.impl.NanopixXrayServiceImpl;
//import com.cvdcms.service.impl.ParaclinicalOrderServiceImpl;
//import org.junit.jupiter.api.*;
//import org.junit.jupiter.api.io.TempDir;
//import org.mockito.Mock;
//import org.mockito.MockitoAnnotations;
//import org.springframework.core.io.Resource;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.mock.web.MockHttpSession;
//import org.springframework.mock.web.MockMultipartFile;
//import org.springframework.test.util.ReflectionTestUtils;
//import org.springframework.web.server.ResponseStatusException;
//
//import java.io.IOException;
//import java.math.BigDecimal;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.time.LocalDateTime;
//import java.util.*;
//import java.util.concurrent.atomic.AtomicLong;
//import java.util.stream.Collectors;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * Bộ kiểm thử End-to-End 16 kịch bản bắt buộc (Section VII) cho luồng
// * Tự động đồng bộ ảnh X-quang NanoPix vào đúng bệnh án & phiếu chỉ định CV-DCMS.
// */
//@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
//class NanopixEndToEndIntegrationTest {
//
//    @TempDir
//    Path tempUploadDir;
//
//    @Mock private NanopixXraySyncLogRepository nanopixRepository;
//    @Mock private PatientProfileRepository patientProfileRepository;
//    @Mock private VisitRepository visitRepository;
//    @Mock private StaffProfileRepository staffProfileRepository;
//    @Mock private ParaclinicalOrderRepository paraclinicalOrderRepository;
//    @Mock private ParaclinicalOrderItemRepository paraclinicalOrderItemRepository;
//    @Mock private ParaclinicalResultAttachmentRepository attachmentRepository;
//    @Mock private ServiceRepository serviceRepository;
//    @Mock private ServiceCategoryRepository serviceCategoryRepository;
//    @Mock private UserRepository userRepository;
//    @Mock private BranchRepository branchRepository;
//    @Mock private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
//
//    private ParaclinicalOrderServiceImpl orderService;
//    private NanoPixIntegrationServiceImpl integrationService;
//    private NanopixXrayServiceImpl xrayService;
//    private NanopixXrayRestController restController;
//    private SecuredUploadResourceController uploadResourceController;
//
//    // In-memory stateful stores simulating DB tables for realistic E2E persistence
//    private final Map<Long, PatientProfile> patientStore = new LinkedHashMap<>();
//    private final Map<Long, Visit> visitStore = new LinkedHashMap<>();
//    private final Map<Long, ParaclinicalOrder> orderStore = new LinkedHashMap<>();
//    private final Map<Long, ParaclinicalOrderItem> orderItemStore = new LinkedHashMap<>();
//    private final Map<Long, NanopixXraySyncLog> syncLogStore = new LinkedHashMap<>();
//    private final List<ParaclinicalResultAttachment> attachmentStore = new ArrayList<>();
//
//    private final AtomicLong orderIdSeq = new AtomicLong(100);
//    private final AtomicLong orderItemIdSeq = new AtomicLong(1000);
//    private final AtomicLong syncLogIdSeq = new AtomicLong(5000);
//    private final AtomicLong attachmentIdSeq = new AtomicLong(9000);
//
//    private Branch branch1;
//    private Branch branch2;
//    private User doctorUser;
//    private User patientUser;
//    private StaffProfile doctorStaff;
//    private Service xrayPeriapicalService;
//    private Service xrayPanoService;
//
//    private PatientProfile patientA;
//    private PatientProfile patientB;
//    private Visit visitA;
//    private Visit visitB;
//
//    private static final byte[] VALID_JPEG_BYTES = new byte[]{
//            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01
//    };
//
//    @BeforeEach
//    void setUp() {
//        MockitoAnnotations.openMocks(this);
//
//        patientStore.clear();
//        visitStore.clear();
//        orderStore.clear();
//        orderItemStore.clear();
//        syncLogStore.clear();
//        attachmentStore.clear();
//
//        branch1 = new Branch();
//        branch1.setBranchId(1L);
//        branch1.setBranchName("Cơ sở Cầu Giấy");
//
//        branch2 = new Branch();
//        branch2.setBranchId(2L);
//        branch2.setBranchName("Cơ sở Đống Đa");
//
//        Role doctorRole = new Role();
//        doctorRole.setRoleId(2L);
//        doctorRole.setRoleName("BAC_SI");
//
//        doctorUser = new User();
//        doctorUser.setUserId(10L);
//        doctorUser.setUsername("dr_hieu");
//        doctorUser.setBranch(branch1);
//        doctorUser.setRoles(Set.of(doctorRole));
//
//        doctorStaff = new StaffProfile();
//        doctorStaff.setStaffId(100L);
//        doctorStaff.setUser(doctorUser);
//        doctorStaff.setFullName("BS. Nguyễn Minh Hiếu");
//
//        Role benhNhanRole = new Role();
//        benhNhanRole.setRoleId(5L);
//        benhNhanRole.setRoleName("BENH_NHAN");
//
//        patientUser = new User();
//        patientUser.setUserId(99L);
//        patientUser.setUsername("benhnhan_user");
//        patientUser.setRoles(Set.of(benhNhanRole));
//
//        xrayPeriapicalService = new Service();
//        xrayPeriapicalService.setServiceId(11L);
//        xrayPeriapicalService.setServiceCode("CLS-XQ-CHOP");
//        xrayPeriapicalService.setServiceName("Chụp X-quang cận chóp kỹ thuật số NanoPix");
//        xrayPeriapicalService.setBasePrice(BigDecimal.valueOf(150000));
//        xrayPeriapicalService.setIsParaclinical(true);
//        xrayPeriapicalService.setServiceType(ServiceType.XRAY);
//
//        xrayPanoService = new Service();
//        xrayPanoService.setServiceId(12L);
//        xrayPanoService.setServiceCode("CLS-XQ-BW");
//        xrayPanoService.setServiceName("Chụp X-quang cánh cắn Bitewing NanoPix");
//        xrayPanoService.setBasePrice(BigDecimal.valueOf(200000));
//        xrayPanoService.setIsParaclinical(true);
//        xrayPanoService.setServiceType(ServiceType.XRAY);
//
//        patientA = new PatientProfile();
//        patientA.setProfileId(201L);
//        patientA.setPatientCode("BN000201");
//        patientA.setFullName("Trần Thị Lan Anh");
//        patientStore.put(patientA.getProfileId(), patientA);
//
//        patientB = new PatientProfile();
//        patientB.setProfileId(202L);
//        patientB.setPatientCode("BN000202");
//        patientB.setFullName("Phạm Quốc Bảo");
//        patientStore.put(patientB.getProfileId(), patientB);
//
//        visitA = new Visit();
//        visitA.setVisitId(609L);
//        visitA.setVisitCode("KHAM-609");
//        visitA.setPatientProfile(patientA);
//        visitA.setPrimaryDoctor(doctorStaff);
//        visitA.setBranch(branch1);
//        visitA.setStatus(VisitStatus.IN_EXAM);
//        visitStore.put(visitA.getVisitId(), visitA);
//
//        visitB = new Visit();
//        visitB.setVisitId(610L);
//        visitB.setVisitCode("KHAM-610");
//        visitB.setPatientProfile(patientB);
//        visitB.setPrimaryDoctor(doctorStaff);
//        visitB.setBranch(branch1);
//        visitB.setStatus(VisitStatus.IN_EXAM);
//        visitStore.put(visitB.getVisitId(), visitB);
//
//        wireRepositories();
//
//        orderService = new ParaclinicalOrderServiceImpl(
//                paraclinicalOrderRepository,
//                paraclinicalOrderItemRepository,
//                visitRepository,
//                serviceRepository,
//                serviceCategoryRepository,
//                staffProfileRepository
//        );
//
//        integrationService = new NanoPixIntegrationServiceImpl(
//                nanopixRepository,
//                patientProfileRepository,
//                visitRepository,
//                staffProfileRepository,
//                paraclinicalOrderItemRepository,
//                attachmentRepository
//        );
//        ReflectionTestUtils.setField(integrationService, "uploadDir", tempUploadDir.toString());
//
//        xrayService = new NanopixXrayServiceImpl(
//                nanopixRepository,
//                patientProfileRepository,
//                visitRepository,
//                staffProfileRepository,
//                paraclinicalOrderItemRepository,
//                attachmentRepository,
//                userRepository,
//                integrationService,
//                branchRepository,
//                staffBranchAssignmentRepository
//        );
//
//        restController = new NanopixXrayRestController(xrayService);
//        ReflectionTestUtils.setField(restController, "configuredApiKey", "TEST_NANOPIX_SECRET_KEY");
//
//        uploadResourceController = new SecuredUploadResourceController(userRepository, nanopixRepository);
//        ReflectionTestUtils.setField(uploadResourceController, "uploadDir", tempUploadDir.toString());
//    }
//
//    private void wireRepositories() {
//        when(userRepository.findById(10L)).thenReturn(Optional.of(doctorUser));
//        when(userRepository.findById(99L)).thenReturn(Optional.of(patientUser));
//        when(staffProfileRepository.findById(100L)).thenReturn(Optional.of(doctorStaff));
//        when(staffProfileRepository.findByUser_UserId(10L)).thenReturn(Optional.of(doctorStaff));
//
//        when(serviceRepository.findById(11L)).thenReturn(Optional.of(xrayPeriapicalService));
//        when(serviceRepository.findById(12L)).thenReturn(Optional.of(xrayPanoService));
//
//        when(patientProfileRepository.findById(anyLong())).thenAnswer(inv ->
//                Optional.ofNullable(patientStore.get(inv.getArgument(0))));
//        when(patientProfileRepository.findByPatientCode(anyString())).thenAnswer(inv -> {
//            String code = inv.getArgument(0);
//            return patientStore.values().stream()
//                    .filter(p -> p.getPatientCode() != null && p.getPatientCode().equalsIgnoreCase(code))
//                    .findFirst();
//        });
//
//        when(visitRepository.findById(anyLong())).thenAnswer(inv ->
//                Optional.ofNullable(visitStore.get(inv.getArgument(0))));
//        when(visitRepository.findByVisitCode(anyString())).thenAnswer(inv -> {
//            String code = inv.getArgument(0);
//            return visitStore.values().stream()
//                    .filter(v -> v.getVisitCode() != null && v.getVisitCode().equalsIgnoreCase(code))
//                    .findFirst();
//        });
//        when(visitRepository.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(anyLong())).thenAnswer(inv -> {
//            Long pid = inv.getArgument(0);
//            return visitStore.values().stream()
//                    .filter(v -> v.getPatientProfile() != null && pid.equals(v.getPatientProfile().getProfileId()))
//                    .collect(Collectors.toList());
//        });
//
//        when(paraclinicalOrderRepository.findByVisit_VisitIdOrderByOrderTimeDesc(anyLong())).thenAnswer(inv -> {
//            Long vid = inv.getArgument(0);
//            return orderStore.values().stream()
//                    .filter(o -> o.getVisit() != null && vid.equals(o.getVisit().getVisitId()))
//                    .collect(Collectors.toList());
//        });
//        when(paraclinicalOrderRepository.save(any(ParaclinicalOrder.class))).thenAnswer(inv -> {
//            ParaclinicalOrder o = inv.getArgument(0);
//            if (o.getOrderId() == null) {
//                o.setOrderId(orderIdSeq.incrementAndGet());
//            }
//            orderStore.put(o.getOrderId(), o);
//            return o;
//        });
//
//        when(paraclinicalOrderItemRepository.save(any(ParaclinicalOrderItem.class))).thenAnswer(inv -> {
//            ParaclinicalOrderItem item = inv.getArgument(0);
//            if (item.getOrderItemId() == null) {
//                item.setOrderItemId(orderItemIdSeq.incrementAndGet());
//            }
//            orderItemStore.put(item.getOrderItemId(), item);
//            if (item.getOrder() != null && !item.getOrder().getOrderItems().contains(item)) {
//                item.getOrder().getOrderItems().add(item);
//            }
//            return item;
//        });
//        when(paraclinicalOrderItemRepository.findById(anyLong())).thenAnswer(inv ->
//                Optional.ofNullable(orderItemStore.get(inv.getArgument(0))));
//        when(paraclinicalOrderItemRepository.findByExternalRefIdIgnoreCase(anyString())).thenAnswer(inv -> {
//            String ref = inv.getArgument(0);
//            return orderItemStore.values().stream()
//                    .filter(i -> i.getExternalRefId() != null && i.getExternalRefId().equalsIgnoreCase(ref))
//                    .findFirst();
//        });
//        when(paraclinicalOrderItemRepository.findByOrder_OrderCodeIgnoreCase(anyString())).thenAnswer(inv -> {
//            String code = inv.getArgument(0);
//            return orderItemStore.values().stream()
//                    .filter(i -> i.getOrder() != null && code.equalsIgnoreCase(i.getOrder().getOrderCode()))
//                    .collect(Collectors.toList());
//        });
//        when(paraclinicalOrderItemRepository.findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(anyLong())).thenAnswer(inv -> {
//            Long vid = inv.getArgument(0);
//            return orderItemStore.values().stream()
//                    .filter(i -> i.getOrder() != null && i.getOrder().getVisit() != null
//                            && vid.equals(i.getOrder().getVisit().getVisitId()))
//                    .collect(Collectors.toList());
//        });
//
//        when(attachmentRepository.save(any(ParaclinicalResultAttachment.class))).thenAnswer(inv -> {
//            ParaclinicalResultAttachment att = inv.getArgument(0);
//            if (att.getAttachmentId() == null) {
//                att.setAttachmentId(attachmentIdSeq.incrementAndGet());
//            }
//            if (att.getUploadedAt() == null) {
//                att.setUploadedAt(LocalDateTime.now());
//            }
//            attachmentStore.add(att);
//            if (att.getOrderItem() != null) {
//                if (att.getOrderItem().getAttachments() == null) {
//                    att.getOrderItem().setAttachments(new ArrayList<>());
//                }
//                if (!att.getOrderItem().getAttachments().contains(att)) {
//                    att.getOrderItem().getAttachments().add(att);
//                }
//            }
//            return att;
//        });
//
//        when(nanopixRepository.existsByExternalStudyUid(anyString())).thenAnswer(inv -> {
//            String uid = inv.getArgument(0);
//            return syncLogStore.values().stream().anyMatch(l -> uid.equals(l.getExternalStudyUid()));
//        });
//        when(nanopixRepository.save(any(NanopixXraySyncLog.class))).thenAnswer(inv -> {
//            NanopixXraySyncLog log = inv.getArgument(0);
//            if (log.getSyncLogId() == null) {
//                log.setSyncLogId(syncLogIdSeq.incrementAndGet());
//            }
//            syncLogStore.put(log.getSyncLogId(), log);
//            return log;
//        });
//        when(nanopixRepository.findById(anyLong())).thenAnswer(inv ->
//                Optional.ofNullable(syncLogStore.get(inv.getArgument(0))));
//        when(nanopixRepository.findFirstByImageUrlOrderByCapturedAtDesc(anyString())).thenAnswer(inv -> {
//            String url = inv.getArgument(0);
//            return syncLogStore.values().stream()
//                    .filter(l -> url.equals(l.getImageUrl()))
//                    .findFirst();
//        });
//    }
//
//    private String extractFileNameFromImageUrl(String imageUrl) {
//        return imageUrl.substring("/uploads/nanopix/".length());
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 1, 2, 3: BÁC SĨ TẠO CHỈ ĐỊNH -> AGENT GỬI ẢNH -> TỰ ĐỘNG GẮN ĐÚNG HỒ SƠ
//    // =========================================================================
//    @Test
//    @Order(1)
//    @DisplayName("Cases 1, 2, 3: Bác sĩ tạo chỉ định cho BN A -> Agent gửi ảnh -> Tự động gắn đúng phiếu chỉ định, cập nhật COMPLETED và hiển thị ảnh")
//    void scenario1_2_3_createOrder_agentIngests_autoMatchesToExactOrderItemAndVisit() throws IOException {
//        // Case 1: Bác sĩ tạo chỉ định X-quang cho Bệnh nhân A tại lượt khám 609
//        ParaclinicalOrderCreateDTO createDTO = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(11L)
//                .toothNumbers("R36")
//                .notes("Đau nhức răng 36, nghi viêm quanh chóp")
//                .build();
//
//        ParaclinicalOrderItemResponseDTO createdOrder = orderService.createOrder(609L, createDTO, 100L);
//
//        assertNotNull(createdOrder);
//        assertNotNull(createdOrder.getExternalRefId(), "Phải tự động sinh Mã chụp (externalRefId) XQ...");
//        assertTrue(createdOrder.getExternalRefId().startsWith("XQ"));
//        assertEquals("WAITING", createdOrder.getStatus());
//
//        // Case 2: Windows Background Sync Agent gửi ảnh X-quang mới kèm mã chụp XQ...
//        MockMultipartFile file = new MockMultipartFile(
//                "file", createdOrder.getExternalRefId() + "_R36.jpg", "image/jpeg", VALID_JPEG_BYTES
//        );
//
//        ResponseEntity<NanopixXrayResponseDTO> ingestResp = restController.ingestXray(
//                "NP_CASE123_HASH0001",
//                patientA.getFullName(),
//                patientA.getPatientCode(),
//                null,
//                createdOrder.getExternalRefId(),
//                visitA.getVisitCode(),
//                1L,
//                LocalDateTime.now(),
//                "EN1E0408A032",
//                "NanoPix1",
//                "R36",
//                "Đồng bộ tự động",
//                file,
//                "TEST_NANOPIX_SECRET_KEY",
//                new MockHttpSession()
//        );
//
//        assertEquals(HttpStatus.CREATED, ingestResp.getStatusCode());
//        NanopixXrayResponseDTO body = ingestResp.getBody();
//        assertNotNull(body);
//
//        // Case 3: Ảnh tự động gắn đúng phiếu chỉ định, đúng lần khám, đúng bệnh nhân A
//        assertEquals("MATCHED", body.getSyncStatus());
//        assertEquals(patientA.getPatientCode(), body.getPatientCode());
//        assertEquals(visitA.getVisitCode(), body.getVisitCode());
//        assertFalse(body.getIsManualLinked(), "Phải được ghi nhận là tự động khớp (isManualLinked = false)");
//
//        // Kiểm tra phiếu chỉ định trong tab Khám & Chẩn đoán của Bác sĩ đã sang COMPLETED và có ảnh đính kèm
//        List<ParaclinicalOrderItemResponseDTO> visitOrders = orderService.getOrdersByVisit(609L);
//        assertEquals(1, visitOrders.size());
//        ParaclinicalOrderItemResponseDTO updatedItem = visitOrders.get(0);
//        assertEquals("COMPLETED", updatedItem.getStatus());
//        assertNotNull(updatedItem.getPrimaryImageUrl());
//        assertTrue(updatedItem.getPrimaryImageUrl().startsWith("/uploads/nanopix/"));
//        assertNotNull(updatedItem.getAttachments());
//        assertEquals(1, updatedItem.getAttachments().size());
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 4: CÓ BỆNH NHÂN B CÙNG THỜI ĐIỂM -> ẢNH CỦA A KHÔNG GẮN NHẦM SANG B
//    // =========================================================================
//    @Test
//    @Order(2)
//    @DisplayName("Case 4: Hai bệnh nhân A và B cùng chờ chụp X-quang -> Ảnh của BN A tuyệt đối không gắn nhầm sang BN B")
//    void scenario4_twoConcurrentPatients_imageOfANeverAttachesToB() throws IOException {
//        ParaclinicalOrderCreateDTO dtoA = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(11L)
//                .toothNumbers("R16")
//                .build();
//        ParaclinicalOrderItemResponseDTO orderA = orderService.createOrder(visitA.getVisitId(), dtoA, 100L);
//
//        ParaclinicalOrderCreateDTO dtoB = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(11L)
//                .toothNumbers("R16")
//                .build();
//        ParaclinicalOrderItemResponseDTO orderB = orderService.createOrder(visitB.getVisitId(), dtoB, 100L);
//
//        // Gửi ảnh của BN A theo mã bệnh nhân A
//        NanopixSyncRequestDTO reqA = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_CONCURRENT_A_001")
//                .externalPatientId(patientA.getPatientCode())
//                .rawPatientName(patientA.getFullName())
//                .toothNumbers("R16")
//                .build();
//        MockMultipartFile fileA = new MockMultipartFile("file", "BN000201_R16.jpg", "image/jpeg", VALID_JPEG_BYTES);
//
//        NanopixXraySyncLog logA = integrationService.ingestXray(reqA, fileA, null);
//
//        assertEquals(NanopixSyncStatus.MATCHED, logA.getSyncStatus());
//        assertEquals(patientA.getProfileId(), logA.getMatchedProfile().getProfileId());
//        assertEquals(visitA.getVisitId(), logA.getMatchedVisit().getVisitId());
//
//        // Phiếu của A phải COMPLETED, còn phiếu của B phải giữ nguyên WAITING và không có ảnh
//        assertEquals(ParaclinicalOrderItemStatus.COMPLETED, orderItemStore.get(orderA.getOrderItemId()).getStatus());
//        assertEquals(ParaclinicalOrderItemStatus.WAITING, orderItemStore.get(orderB.getOrderItemId()).getStatus());
//        assertTrue(orderItemStore.get(orderB.getOrderItemId()).getAttachments().isEmpty(),
//                "Phiếu chỉ định của BN B tuyệt đối không được nhận nhầm ảnh của BN A");
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 5: HAI BỆNH NHÂN TRÙNG HỌ TÊN NHƯNG KHÁC MÃ
//    // =========================================================================
//    @Test
//    @Order(3)
//    @DisplayName("Case 5: Hai bệnh nhân trùng tên 'Nguyễn Văn An' nhưng khác mã -> Chỉ gửi tên phải vào UNMATCHED; gửi kèm mã BN gắn đúng người")
//    void scenario5_twoPatientsWithIdenticalName_neverMatchesByNameAlone() throws IOException {
//        PatientProfile an1 = new PatientProfile();
//        an1.setProfileId(301L);
//        an1.setPatientCode("BN000301");
//        an1.setFullName("Nguyễn Văn An");
//        patientStore.put(301L, an1);
//
//        PatientProfile an2 = new PatientProfile();
//        an2.setProfileId(302L);
//        an2.setPatientCode("BN000302");
//        an2.setFullName("Nguyễn Văn An");
//        patientStore.put(302L, an2);
//
//        Visit vAn1 = new Visit();
//        vAn1.setVisitId(701L);
//        vAn1.setVisitCode("KHAM-701");
//        vAn1.setPatientProfile(an1);
//        vAn1.setBranch(branch1);
//        vAn1.setStatus(VisitStatus.WAITING_PARACLINICAL);
//        visitStore.put(701L, vAn1);
//
//        Visit vAn2 = new Visit();
//        vAn2.setVisitId(702L);
//        vAn2.setVisitCode("KHAM-702");
//        vAn2.setPatientProfile(an2);
//        vAn2.setBranch(branch1);
//        vAn2.setStatus(VisitStatus.WAITING_PARACLINICAL);
//        visitStore.put(702L, vAn2);
//
//        ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(11L)
//                .build();
//        orderService.createOrder(701L, dto, 100L);
//        orderService.createOrder(702L, dto, 100L);
//
//        MockMultipartFile file = new MockMultipartFile("file", "Nguyen_Van_An.jpg", "image/jpeg", VALID_JPEG_BYTES);
//
//        // 5a: Chỉ có tên "Nguyễn Văn An", không có mã BN hay mã chụp -> Bắt buộc vào UNMATCHED
//        NanopixSyncRequestDTO reqNameOnly = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_SAME_NAME_ONLY")
//                .rawPatientName("Nguyễn Văn An")
//                .build();
//        NanopixXraySyncLog logUnmatched = integrationService.ingestXray(reqNameOnly, file, null);
//        assertEquals(NanopixSyncStatus.UNMATCHED, logUnmatched.getSyncStatus());
//        assertNull(logUnmatched.getMatchedProfile());
//
//        // 5b: Có mã BN000302 -> Gắn chính xác vào Nguyễn Văn An thứ 2 (Profile 302L)
//        NanopixSyncRequestDTO reqWithCode = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_SAME_NAME_WITH_CODE")
//                .rawPatientName("Nguyễn Văn An")
//                .externalPatientId("BN000302")
//                .build();
//        NanopixXraySyncLog logMatched = integrationService.ingestXray(reqWithCode, file, null);
//        assertEquals(NanopixSyncStatus.MATCHED, logMatched.getSyncStatus());
//        assertEquals(302L, logMatched.getMatchedProfile().getProfileId());
//        assertEquals(702L, logMatched.getMatchedVisit().getVisitId());
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 6: MỘT BỆNH NHÂN CÓ NHIỀU LẦN KHÁM
//    // =========================================================================
//    @Test
//    @Order(4)
//    @DisplayName("Case 6: Một bệnh nhân có nhiều lần khám -> Chọn đúng lần khám đang hoạt động; nếu có 2 lần khám cùng hoạt động mà thiếu visitCode thì vào UNMATCHED")
//    void scenario6_onePatientWithMultipleVisits() throws IOException {
//        // Thêm 1 lượt khám cũ đã COMPLETED của BN A
//        Visit oldVisit = new Visit();
//        oldVisit.setVisitId(600L);
//        oldVisit.setVisitCode("KHAM-600-OLD");
//        oldVisit.setPatientProfile(patientA);
//        oldVisit.setBranch(branch1);
//        oldVisit.setStatus(VisitStatus.COMPLETED);
//        visitStore.put(600L, oldVisit);
//
//        ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(11L)
//                .build();
//        orderService.createOrder(visitA.getVisitId(), dto, 100L);
//
//        MockMultipartFile file = new MockMultipartFile("file", "BN000201.jpg", "image/jpeg", VALID_JPEG_BYTES);
//
//        // 6a: Có 1 visit cũ COMPLETED và 1 visit hiện tại IN_EXAM -> Khớp đúng vào visit hiện tại (609L)
//        NanopixSyncRequestDTO req1 = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_MULTI_VISIT_1")
//                .externalPatientId(patientA.getPatientCode())
//                .build();
//        NanopixXraySyncLog log1 = integrationService.ingestXray(req1, file, null);
//        assertEquals(NanopixSyncStatus.MATCHED, log1.getSyncStatus());
//        assertEquals(visitA.getVisitId(), log1.getMatchedVisit().getVisitId());
//
//        // 6b: Giả sử bệnh nhân có 2 visit cùng đang mở mà không truyền visitCode/orderCode -> Phải vào UNMATCHED
//        Visit secondActiveVisit = new Visit();
//        secondActiveVisit.setVisitId(699L);
//        secondActiveVisit.setVisitCode("KHAM-699-ACTIVE2");
//        secondActiveVisit.setPatientProfile(patientA);
//        secondActiveVisit.setBranch(branch1);
//        secondActiveVisit.setStatus(VisitStatus.WAITING_PARACLINICAL);
//        visitStore.put(699L, secondActiveVisit);
//
//        NanopixSyncRequestDTO reqAmbiguousVisit = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_MULTI_VISIT_AMBIGUOUS")
//                .externalPatientId(patientA.getPatientCode())
//                .build();
//        NanopixXraySyncLog log2 = integrationService.ingestXray(reqAmbiguousVisit, file, null);
//        assertEquals(NanopixSyncStatus.UNMATCHED, log2.getSyncStatus());
//        assertTrue(log2.getErrorMessage().contains("lượt khám mở đồng thời"));
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 7: MỘT LẦN KHÁM CÓ NHIỀU CHỈ ĐỊNH X-QUANG
//    // =========================================================================
//    @Test
//    @Order(5)
//    @DisplayName("Case 7: Một lần khám có 2 chỉ định X-quang -> Không có mã chụp/số răng thì vào UNMATCHED; có mã chụp XQ... hoặc số răng R... thì gắn đúng phiếu")
//    void scenario7_oneVisitWithMultipleXrayOrders_disambiguatesByOrderCodeOrTooth() throws IOException {
//        ParaclinicalOrderCreateDTO dto1 = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(11L)
//                .toothNumbers("R16")
//                .build();
//        ParaclinicalOrderItemResponseDTO item1 = orderService.createOrder(visitA.getVisitId(), dto1, 100L);
//
//        ParaclinicalOrderCreateDTO dto2 = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(12L)
//                .toothNumbers("R48")
//                .build();
//        ParaclinicalOrderItemResponseDTO item2 = orderService.createOrder(visitA.getVisitId(), dto2, 100L);
//
//        MockMultipartFile file = new MockMultipartFile("file", "xray.jpg", "image/jpeg", VALID_JPEG_BYTES);
//
//        // 7a: Chỉ gửi mã BN, không gửi mã chụp hay số răng -> Có 2 phiếu đang chờ -> Bắt buộc vào UNMATCHED (không đoán .findFirst())
//        NanopixSyncRequestDTO reqAmbiguous = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_MULTI_ORDER_AMBIGUOUS")
//                .externalPatientId(patientA.getPatientCode())
//                .build();
//        NanopixXraySyncLog logAmbiguous = integrationService.ingestXray(reqAmbiguous, file, null);
//        assertEquals(NanopixSyncStatus.UNMATCHED, logAmbiguous.getSyncStatus());
//        assertEquals(ParaclinicalOrderItemStatus.WAITING, orderItemStore.get(item1.getOrderItemId()).getStatus());
//        assertEquals(ParaclinicalOrderItemStatus.WAITING, orderItemStore.get(item2.getOrderItemId()).getStatus());
//
//        // 7b: Gửi kèm số răng R48 -> Khớp chính xác vào phiếu chỉ định số 2 (R48), phiếu số 1 (R16) vẫn WAITING
//        NanopixSyncRequestDTO reqTooth48 = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_MULTI_ORDER_R48")
//                .externalPatientId(patientA.getPatientCode())
//                .toothNumbers("R48")
//                .build();
//        NanopixXraySyncLog logTooth48 = integrationService.ingestXray(reqTooth48, file, null);
//        assertEquals(NanopixSyncStatus.MATCHED, logTooth48.getSyncStatus());
//        assertEquals(ParaclinicalOrderItemStatus.WAITING, orderItemStore.get(item1.getOrderItemId()).getStatus());
//        assertEquals(ParaclinicalOrderItemStatus.COMPLETED, orderItemStore.get(item2.getOrderItemId()).getStatus());
//
//        // 7c: Gửi ảnh tiếp theo kèm mã chụp XQ... của phiếu số 1 -> Khớp chính xác vào phiếu số 1
//        NanopixSyncRequestDTO reqOrder1 = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_MULTI_ORDER_XQ1")
//                .orderCode(item1.getExternalRefId())
//                .build();
//        NanopixXraySyncLog logOrder1 = integrationService.ingestXray(reqOrder1, file, null);
//        assertEquals(NanopixSyncStatus.MATCHED, logOrder1.getSyncStatus());
//        assertEquals(ParaclinicalOrderItemStatus.COMPLETED, orderItemStore.get(item1.getOrderItemId()).getStatus());
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 8: CÙNG MỘT ẢNH ĐƯỢC GỬI LẠI NHIỀU LẦN -> KHÔNG TẠO BẢN GHI TRÙNG
//    // =========================================================================
//    @Test
//    @Order(6)
//    @DisplayName("Case 8: Cùng một ảnh (cùng externalStudyUid) được gửi lại nhiều lần -> Bị chặn 409 CONFLICT, không tạo bản ghi trùng")
//    void scenario8_duplicateImageSentMultipleTimes_noDuplicateRecordCreated() throws IOException {
//        MockMultipartFile file = new MockMultipartFile("file", "dup.jpg", "image/jpeg", VALID_JPEG_BYTES);
//        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_DUP_TEST_001")
//                .rawPatientName("Test Duplicate")
//                .build();
//
//        NanopixXraySyncLog first = integrationService.ingestXray(req, file, null);
//        assertNotNull(first);
//        assertEquals(1, syncLogStore.size());
//
//        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
//                integrationService.ingestXray(req, file, null)
//        );
//        assertEquals(HttpStatus.CONFLICT.value(), restController.handleConflict(ex).getStatusCode().value());
//        assertEquals(1, syncLogStore.size(), "Không được tạo thêm bản ghi trùng lặp");
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 9: AGENT GỬI ẢNH THIẾU METADATA -> VÀO UNMATCHED, KHÔNG GẮN BỪA
//    // =========================================================================
//    @Test
//    @Order(7)
//    @DisplayName("Case 9: Agent gửi ảnh thiếu toàn bộ metadata định danh -> Lưu vào UNMATCHED, giữ nguyên trạng thái WAITING của phiếu chỉ định")
//    void scenario9_missingMetadata_goesToUnmatched() throws IOException {
//        ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(11L)
//                .build();
//        ParaclinicalOrderItemResponseDTO order = orderService.createOrder(visitA.getVisitId(), dto, 100L);
//
//        MockMultipartFile file = new MockMultipartFile("file", "20261025_193542.jpg", "image/jpeg", VALID_JPEG_BYTES);
//        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_MISSING_META_001")
//                .build();
//
//        NanopixXraySyncLog result = integrationService.ingestXray(req, file, null);
//
//        assertEquals(NanopixSyncStatus.UNMATCHED, result.getSyncStatus());
//        assertNull(result.getMatchedProfile());
//        assertNull(result.getMatchedVisit());
//        assertEquals(ParaclinicalOrderItemStatus.WAITING, orderItemStore.get(order.getOrderItemId()).getStatus());
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 10: FILE ẢNH LỖI HOẶC KHÔNG HỢP LỆ -> TỪ CHỐI 400, KHÔNG LƯU RÁC
//    // =========================================================================
//    @Test
//    @Order(8)
//    @DisplayName("Case 10: File ảnh rỗng, sai định dạng (.exe) hoặc nội dung hỏng (CORRUPT/script) -> Bị từ chối 400 BAD_REQUEST")
//    void scenario10_corruptedOrInvalidFile_rejectedCleanly() {
//        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_CORRUPT_001")
//                .build();
//
//        // 10a: File rỗng
//        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);
//        assertThrows(IllegalArgumentException.class, () -> integrationService.ingestXray(req, emptyFile, null));
//
//        // 10b: File thực thi giả mạo đuôi .jpg (header MZ)
//        MockMultipartFile fakeJpgExe = new MockMultipartFile(
//                "file", "malware.jpg", "image/jpeg", "MZ\u0090\u0000\u0003\u0000".getBytes(StandardCharsets.ISO_8859_1)
//        );
//        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
//                integrationService.ingestXray(req, fakeJpgExe, null)
//        );
//        assertEquals(HttpStatus.BAD_REQUEST.value(), restController.handleBadRequest(ex).getStatusCode().value());
//
//        // 10c: File nội dung hỏng
//        MockMultipartFile corruptFile = new MockMultipartFile(
//                "file", "broken.jpg", "image/jpeg", "CORRUPT_IMAGE_DATA".getBytes(StandardCharsets.UTF_8)
//        );
//        assertThrows(IllegalArgumentException.class, () -> integrationService.ingestXray(req, corruptFile, null));
//
//        assertTrue(syncLogStore.isEmpty(), "Không được lưu bản ghi DB cho file lỗi");
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 11: MẤT KẾT NỐI TẠM THỜI RỒI GỬI LẠI (RETRY) -> THÀNH CÔNG & KHÔNG TRÙNG
//    // =========================================================================
//    @Test
//    @Order(9)
//    @DisplayName("Case 11: Lần gửi đầu thất bại do lỗi kết nối/DB tạm thời -> Agent gửi lại cùng studyUid thành công và gắn đúng phiếu")
//    void scenario11_temporaryFailureThenRetry_succeedsOnSecondAttempt() throws IOException {
//        ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(11L)
//                .build();
//        ParaclinicalOrderItemResponseDTO order = orderService.createOrder(visitA.getVisitId(), dto, 100L);
//
//        MockMultipartFile file = new MockMultipartFile("file", "retry_xray.jpg", "image/jpeg", VALID_JPEG_BYTES);
//        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_RETRY_001")
//                .orderCode(order.getExternalRefId())
//                .build();
//
//        // Lần 1: Giả lập DB ném lỗi kết nối tạm thời ở lần gọi save đầu tiên
//        when(nanopixRepository.save(any(NanopixXraySyncLog.class)))
//                .thenThrow(new RuntimeException("Simulated transient DB timeout"))
//                .thenAnswer(inv -> {
//                    NanopixXraySyncLog log = inv.getArgument(0);
//                    log.setSyncLogId(syncLogIdSeq.incrementAndGet());
//                    syncLogStore.put(log.getSyncLogId(), log);
//                    return log;
//                });
//
//        assertThrows(RuntimeException.class, () -> integrationService.ingestXray(req, file, null));
//        assertTrue(syncLogStore.isEmpty(), "Lần 1 lỗi không được lưu bản ghi dở dang");
//
//        // Lần 2 (Retry với cùng studyUid): Thành công
//        NanopixXraySyncLog retryResult = integrationService.ingestXray(req, file, null);
//        assertNotNull(retryResult);
//        assertEquals(NanopixSyncStatus.MATCHED, retryResult.getSyncStatus());
//        assertEquals(ParaclinicalOrderItemStatus.COMPLETED, orderItemStore.get(order.getOrderItemId()).getStatus());
//        assertEquals(1, syncLogStore.size());
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 12: SERVER NHẬN FILE NHƯNG LƯU DB LỖI -> DỌN DẸP FILE VẬT LÝ TRÊN ĐĨA
//    // =========================================================================
//    @Test
//    @Order(10)
//    @DisplayName("Case 12: Server đã ghi file lên đĩa nhưng lưu DB thất bại -> Tự động xóa file vật lý mồ côi trên đĩa")
//    void scenario12_dbSaveFails_deletesOrphanPhysicalFileFromDisk() throws IOException {
//        Path nanopixSubDir = tempUploadDir.resolve("nanopix");
//        long filesBefore = Files.exists(nanopixSubDir) ? Files.list(nanopixSubDir).count() : 0;
//
//        when(nanopixRepository.save(any(NanopixXraySyncLog.class)))
//                .thenThrow(new RuntimeException("Database constraint failure"));
//
//        MockMultipartFile file = new MockMultipartFile("file", "rollback_test.jpg", "image/jpeg", VALID_JPEG_BYTES);
//        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_ROLLBACK_FILE_001")
//                .build();
//
//        assertThrows(RuntimeException.class, () -> integrationService.ingestXray(req, file, null));
//
//        long filesAfter = Files.exists(nanopixSubDir) ? Files.list(nanopixSubDir).count() : 0;
//        assertEquals(filesBefore, filesAfter, "File vật lý phải được dọn dẹp ngay khi lưu DB thất bại");
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 13: DB CÓ BẢN GHI NHƯNG FILE ẢNH VẬT LÝ BỊ THIẾU -> TRẢ VỀ 404 RÕ RÀNG
//    // =========================================================================
//    @Test
//    @Order(11)
//    @DisplayName("Case 13: DB có bản ghi đường dẫn ảnh nhưng file vật lý trên ổ đĩa bị mất -> Trả về 404 NOT_FOUND an toàn")
//    void scenario13_dbHasRecordButPhysicalFileMissing_returns404() {
//        MockHttpSession doctorSession = new MockHttpSession();
//        doctorSession.setAttribute("loggedInUser", doctorUser);
//
//        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
//                uploadResourceController.getSecuredFile("nanopix", "missing_file_999.jpg", doctorSession)
//        );
//        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 14: NGƯỜI DÙNG KHÔNG CÓ QUYỀN -> BỊ CHẶN 401/403
//    // =========================================================================
//    @Test
//    @Order(12)
//    @DisplayName("Case 14: Người dùng chưa đăng nhập, sai API key, sai Role hoặc khác Chi nhánh không thể tải ảnh/xem ảnh/đối chiếu")
//    void scenario14_unauthorizedAccess_blockedStrictly() throws IOException {
//        ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(11L)
//                .build();
//        ParaclinicalOrderItemResponseDTO order = orderService.createOrder(visitA.getVisitId(), dto, 100L);
//
//        MockMultipartFile file = new MockMultipartFile("file", "sec_test.jpg", "image/jpeg", VALID_JPEG_BYTES);
//        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_SEC_001")
//                .orderCode(order.getExternalRefId())
//                .build();
//        NanopixXraySyncLog log = integrationService.ingestXray(req, file, null);
//        String fileName = extractFileNameFromImageUrl(log.getImageUrl());
//
//        // 14a: Gửi ingest với sai X-API-KEY và không có session -> 401 UNAUTHORIZED
//        ResponseStatusException badKeyEx = assertThrows(ResponseStatusException.class, () ->
//                restController.ingestXray("NP_BAD_KEY", null, null, null, null, null, null, null, null, null, null, null,
//                        file, "WRONG_SECRET_KEY", new MockHttpSession())
//        );
//        assertEquals(HttpStatus.UNAUTHORIZED, badKeyEx.getStatusCode());
//
//        // 14b: Truy cập file ảnh /uploads/nanopix/... mà chưa đăng nhập -> 401 UNAUTHORIZED
//        ResponseStatusException anonEx = assertThrows(ResponseStatusException.class, () ->
//                uploadResourceController.getSecuredFile("nanopix", fileName, new MockHttpSession())
//        );
//        assertEquals(HttpStatus.UNAUTHORIZED, anonEx.getStatusCode());
//
//        // 14c: Tài khoản Bệnh nhân gọi API danh sách X-quang của phòng khám -> 403 FORBIDDEN
//        MockHttpSession patientSession = new MockHttpSession();
//        patientSession.setAttribute("loggedInUser", patientUser);
//        ResponseStatusException forbiddenEx = assertThrows(ResponseStatusException.class, () ->
//                restController.getUnmatchedXrays(null, patientSession)
//        );
//        assertEquals(HttpStatus.FORBIDDEN, forbiddenEx.getStatusCode());
//
//        // 14d: Tài khoản Bệnh nhân khác truy cập trực tiếp URL ảnh X-quang của BN A -> 403 FORBIDDEN
//        ResponseStatusException patientFileForbidden = assertThrows(ResponseStatusException.class, () ->
//                uploadResourceController.getSecuredFile("nanopix", fileName, patientSession)
//        );
//        assertEquals(HttpStatus.FORBIDDEN, patientFileForbidden.getStatusCode());
//
//        // 14e: Bác sĩ thuộc Cơ sở 2 truy cập trực tiếp file X-quang của Cơ sở 1 -> 403 FORBIDDEN
//        Role docRole = new Role();
//        docRole.setRoleId(2L);
//        docRole.setRoleName("BAC_SI");
//        User doctorBranch2 = new User();
//        doctorBranch2.setUserId(88L);
//        doctorBranch2.setUsername("dr_branch2");
//        doctorBranch2.setBranch(branch2);
//        doctorBranch2.setRoles(Set.of(docRole));
//        when(userRepository.findById(88L)).thenReturn(Optional.of(doctorBranch2));
//
//        MockHttpSession branch2Session = new MockHttpSession();
//        branch2Session.setAttribute("loggedInUser", doctorBranch2);
//        ResponseStatusException crossBranchForbidden = assertThrows(ResponseStatusException.class, () ->
//                uploadResourceController.getSecuredFile("nanopix", fileName, branch2Session)
//        );
//        assertEquals(HttpStatus.FORBIDDEN, crossBranchForbidden.getStatusCode());
//    }
//
//    // =========================================================================
//    // KỊCH BẢN 15 & 16: BÁC SĨ CÓ QUYỀN XEM ẢNH & DỮ LIỆU BỀN VỮNG SAU LOGOUT / LOGIN LẠI
//    // =========================================================================
//    @Test
//    @Order(13)
//    @DisplayName("Cases 15 & 16: Bác sĩ có quyền xem được ảnh (200 OK) và sau khi đăng xuất rồi đăng nhập lại (session mới) ảnh vẫn gắn đúng bệnh án")
//    void scenario15_16_authorizedDoctorViewsImage_andPersistsAcrossLogoutAndRelogin() throws IOException {
//        // Bác sĩ tạo chỉ định và Agent đồng bộ ảnh
//        ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder()
//                .serviceId(11L)
//                .toothNumbers("R26")
//                .build();
//        ParaclinicalOrderItemResponseDTO order = orderService.createOrder(visitA.getVisitId(), dto, 100L);
//
//        MockMultipartFile file = new MockMultipartFile("file", "persist_xray.jpg", "image/jpeg", VALID_JPEG_BYTES);
//        NanopixSyncRequestDTO req = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_PERSIST_001")
//                .orderCode(order.getExternalRefId())
//                .build();
//        NanopixXraySyncLog log = integrationService.ingestXray(req, file, null);
//        assertEquals(NanopixSyncStatus.MATCHED, log.getSyncStatus());
//        String fileName = extractFileNameFromImageUrl(log.getImageUrl());
//
//        // Case 15: Bác sĩ đang đăng nhập tải ảnh qua SecuredUploadResourceController -> 200 OK
//        MockHttpSession session1 = new MockHttpSession();
//        session1.setAttribute("loggedInUser", doctorUser);
//        ResponseEntity<Resource> imgResp1 = uploadResourceController.getSecuredFile("nanopix", fileName, session1);
//        assertEquals(HttpStatus.OK, imgResp1.getStatusCode());
//        assertNotNull(imgResp1.getBody());
//
//        // Case 16: Bác sĩ đăng xuất (invalidate session1) và đăng nhập lại bằng session2 mới
//        session1.invalidate();
//
//        MockHttpSession session2AfterRelogin = new MockHttpSession();
//        session2AfterRelogin.setAttribute("loggedInUser", doctorUser);
//
//        // Mở lại màn hình khám của bệnh nhân A -> Phiếu chỉ định vẫn COMPLETED và nguyên vẹn đường dẫn ảnh
//        List<ParaclinicalOrderItemResponseDTO> ordersAfterRelogin = orderService.getOrdersByVisit(visitA.getVisitId());
//        assertEquals(1, ordersAfterRelogin.size());
//        ParaclinicalOrderItemResponseDTO persistedItem = ordersAfterRelogin.get(0);
//        assertEquals("COMPLETED", persistedItem.getStatus());
//        assertEquals(order.getExternalRefId(), persistedItem.getExternalRefId());
//        assertEquals(log.getImageUrl(), persistedItem.getPrimaryImageUrl());
//        assertEquals(1, persistedItem.getAttachments().size());
//
//        // Tải lại file ảnh với session mới -> 200 OK
//        ResponseEntity<Resource> imgResp2 = uploadResourceController.getSecuredFile("nanopix", fileName, session2AfterRelogin);
//        assertEquals(HttpStatus.OK, imgResp2.getStatusCode());
//    }
//
//    // =========================================================================
//    // KỊCH BẢN BỔ SUNG A & C: SINH MÃ XQ ĐỒNG THỜI + MÂU THUẪN METADATA + PHIẾU HỦY/KHÓA
//    // =========================================================================
//    @Test
//    @Order(14)
//    @DisplayName("Kiểm tra sinh mã XQ duy nhất khi tạo nhiều chỉ định liên tiếp, mâu thuẫn metadata (BN/Visit/XQ) và phiếu đã hủy/ca khám đã khóa")
//    void scenarioExtra_uniqueXqGeneration_metadataConflicts_andCancelledOrLockedOrders() throws IOException {
//        // 1. Tạo 20 chỉ định liên tiếp -> tất cả mã XQ phải hoàn toàn duy nhất (không trùng lặp)
//        Set<String> generatedRefs = new HashSet<>();
//        for (int i = 0; i < 20; i++) {
//            ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder()
//                    .serviceId(11L)
//                    .toothNumbers("R" + (11 + i))
//                    .build();
//            ParaclinicalOrderItemResponseDTO created = orderService.createOrder(visitA.getVisitId(), dto, 100L);
//            assertNotNull(created.getExternalRefId());
//            assertTrue(generatedRefs.add(created.getExternalRefId()), "Mã chụp XQ không được trùng lặp: " + created.getExternalRefId());
//        }
//        assertEquals(20, generatedRefs.size());
//
//        ParaclinicalOrderItem sampleItemA = orderItemStore.values().iterator().next();
//        MockMultipartFile file = new MockMultipartFile("file", "conflict.jpg", "image/jpeg", VALID_JPEG_BYTES);
//
//        // 2. Mâu thuẫn giữa mã XQ (thuộc BN A) và externalPatientId (gửi mã BN B) -> Phải vào UNMATCHED
//        NanopixSyncRequestDTO conflictPatientReq = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_CONFLICT_PATIENT_01")
//                .orderCode(sampleItemA.getExternalRefId())
//                .externalPatientId(patientB.getPatientCode())
//                .build();
//        NanopixXraySyncLog logConflictPatient = integrationService.ingestXray(conflictPatientReq, file, null);
//        assertEquals(NanopixSyncStatus.UNMATCHED, logConflictPatient.getSyncStatus());
//        assertTrue(logConflictPatient.getErrorMessage().contains("Mâu thuẫn định danh"));
//
//        // 3. Mâu thuẫn giữa mã XQ (thuộc Visit A) và visitCode (gửi Visit B) -> Phải vào UNMATCHED
//        NanopixSyncRequestDTO conflictVisitReq = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_CONFLICT_VISIT_01")
//                .orderCode(sampleItemA.getExternalRefId())
//                .visitCode(visitB.getVisitCode())
//                .build();
//        NanopixXraySyncLog logConflictVisit = integrationService.ingestXray(conflictVisitReq, file, null);
//        assertEquals(NanopixSyncStatus.UNMATCHED, logConflictVisit.getSyncStatus());
//        assertTrue(logConflictVisit.getErrorMessage().contains("Mâu thuẫn lượt khám"));
//
//        // 4. Phiếu chỉ định đã bị hủy (CANCELLED) -> Phải vào UNMATCHED
//        sampleItemA.setStatus(ParaclinicalOrderItemStatus.CANCELLED);
//        NanopixSyncRequestDTO cancelledOrderReq = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_CANCELLED_ITEM_01")
//                .orderCode(sampleItemA.getExternalRefId())
//                .build();
//        NanopixXraySyncLog logCancelled = integrationService.ingestXray(cancelledOrderReq, file, null);
//        assertEquals(NanopixSyncStatus.UNMATCHED, logCancelled.getSyncStatus());
//        assertTrue(logCancelled.getErrorMessage().contains("đã bị hủy"));
//
//        // 5. Ca khám đã đóng/khóa bệnh án (WAITING_PAYMENT / COMPLETED) -> Phải vào UNMATCHED
//        sampleItemA.setStatus(ParaclinicalOrderItemStatus.WAITING);
//        visitA.setStatus(VisitStatus.WAITING_PAYMENT);
//        NanopixSyncRequestDTO lockedVisitReq = NanopixSyncRequestDTO.builder()
//                .externalStudyUid("NP_LOCKED_VISIT_01")
//                .orderCode(sampleItemA.getExternalRefId())
//                .build();
//        NanopixXraySyncLog logLocked = integrationService.ingestXray(lockedVisitReq, file, null);
//        assertEquals(NanopixSyncStatus.UNMATCHED, logLocked.getSyncStatus());
//        assertTrue(logLocked.getErrorMessage().contains("khóa bệnh án"));
//    }
//}
