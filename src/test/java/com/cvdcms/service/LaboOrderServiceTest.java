package com.cvdcms.service;

import com.cvdcms.DTO.LaboOrderRequestDTO;
import com.cvdcms.DTO.LaboOrderResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.LaboOrderStatus;
import com.cvdcms.entity.enums.LaboOrderType;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.LaboOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Test tầng Service cho FT13 - Xét nghiệm & Mẫu gửi ngoài Labo (Role Bác sĩ)
 * Service: LaboOrderServiceImpl
 */
@ExtendWith(MockitoExtension.class)
class LaboOrderServiceTest {

    @Mock private LaboOrderRepository laboOrderRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private PatientProfileRepository patientProfileRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private StaffProfileRepository staffProfileRepository;

    private LaboOrderServiceImpl laboOrderServiceImpl;

    @TempDir
    Path tempUploadDir;

    private Visit mockVisit;
    private PatientProfile mockPatient;
    private Supplier mockSupplier;
    private StaffProfile mockDoctor;
    private Branch mockBranch;
    private LaboOrder mockOrder;

    @BeforeEach
    void setUp() {
        laboOrderServiceImpl = new LaboOrderServiceImpl(
                laboOrderRepository,
                visitRepository,
                patientProfileRepository,
                supplierRepository,
                branchRepository,
                staffProfileRepository
        );
        ReflectionTestUtils.setField(laboOrderServiceImpl, "uploadDir", tempUploadDir.toString());

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(1L);
        mockPatient.setPatientCode("BN-10001");
        mockPatient.setFullName("Tran Thi Mai");

        mockDoctor = new StaffProfile();
        mockDoctor.setStaffId(5L);
        mockDoctor.setFullName("BS. Hoang Van C");

        mockBranch = new Branch();
        mockBranch.setBranchId(1L);
        mockBranch.setBranchName("Chi nhánh Trung tâm");

        mockSupplier = new Supplier();
        mockSupplier.setSupplierId(10L);
        mockSupplier.setSupplierCode("LB01");
        mockSupplier.setSupplierName("Labo Nha khoa Sài Gòn");

        mockVisit = new Visit();
        mockVisit.setVisitId(200L);
        mockVisit.setVisitCode("KB-200");
        mockVisit.setPatientProfile(mockPatient);
        mockVisit.setPrimaryDoctor(mockDoctor);
        mockVisit.setBranch(mockBranch);

        mockOrder = new LaboOrder();
        mockOrder.setLaboId(101L);
        mockOrder.setLaboCode("LB-20261103-01");
        mockOrder.setVisit(mockVisit);
        mockOrder.setPatientProfile(mockPatient);
        mockOrder.setDoctor(mockDoctor);
        mockOrder.setSupplier(mockSupplier);
        mockOrder.setMaterialType("Răng sứ Cercon HT");
        mockOrder.setToothPositions("R11, R12");
        mockOrder.setQuantity(2);
        mockOrder.setUnitPrice(BigDecimal.valueOf(1500000));
        mockOrder.setCostToClinic(BigDecimal.valueOf(3000000));
        mockOrder.setStatus(LaboOrderStatus.SENT);
        mockOrder.setSentDate(LocalDate.now());
    }

    @Test
    @DisplayName("createOrder - Khởi tạo đơn Labo gửi đối tác ngoài (trạng thái SENT, tính đúng giá thành)")
    void createOrder_Success() throws IOException {
        LaboOrderRequestDTO dto = LaboOrderRequestDTO.builder()
                .visitId(200L)
                .supplierId(10L)
                .materialType("Răng sứ Zirconia DDBio")
                .toothPositions("R21, R22")
                .shadeColor("A2")
                .quantity(3)
                .unitPrice(BigDecimal.valueOf(1200000))
                .sentDate(LocalDate.now())
                .expectedDate(LocalDate.now().plusDays(4))
                .status(LaboOrderStatus.SENT)
                .build();

        when(visitRepository.findById(200L)).thenReturn(Optional.of(mockVisit));
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(mockSupplier));
        when(laboOrderRepository.save(any(LaboOrder.class))).thenAnswer(invocation -> {
            LaboOrder o = invocation.getArgument(0);
            o.setLaboId(999L);
            o.setLaboCode("LB-999");
            return o;
        });

        LaboOrderResponseDTO result = laboOrderServiceImpl.createOrder(dto, null);

        assertNotNull(result);
        assertEquals(999L, result.getLaboId());
        assertEquals("Răng sứ Zirconia DDBio", result.getMaterialType());
        assertEquals(3, result.getQuantity());
        assertEquals(BigDecimal.valueOf(1200000), result.getUnitPrice());
        assertEquals(BigDecimal.valueOf(3600000), result.getCostToClinic());
        assertEquals(LaboOrderStatus.SENT, result.getStatus());
        verify(laboOrderRepository, times(1)).save(any(LaboOrder.class));
    }

    @Test
    @DisplayName("createOrder - Ném IllegalArgumentException khi request DTO null")
    void createOrder_NullDto_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                laboOrderServiceImpl.createOrder(null, null));
    }

    @Test
    @DisplayName("createOrder - Ném IllegalArgumentException khi không tìm thấy bệnh nhân theo profileId")
    void createOrder_PatientNotFound_ThrowsIllegalArgumentException() {
        LaboOrderRequestDTO dto = LaboOrderRequestDTO.builder()
                .profileId(9999L)
                .build();

        when(patientProfileRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                laboOrderServiceImpl.createOrder(dto, null));
    }

    @Test
    @DisplayName("createOrder - Ném IllegalArgumentException khi không tìm thấy nhà cung cấp Labo")
    void createOrder_SupplierNotFound_ThrowsIllegalArgumentException() {
        LaboOrderRequestDTO dto = LaboOrderRequestDTO.builder()
                .visitId(200L)
                .supplierId(9999L)
                .build();

        when(visitRepository.findById(200L)).thenReturn(Optional.of(mockVisit));
        when(supplierRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                laboOrderServiceImpl.createOrder(dto, null));
    }

    @Test
    @DisplayName("updateStatus - Chuyển sang DELIVERED (Đã có kết quả) tự động điền receivedDate và ghi chú")
    void updateStatus_ToDelivered_SetsReceivedDateAndNote() {
        when(laboOrderRepository.findById(101L)).thenReturn(Optional.of(mockOrder));
        when(laboOrderRepository.save(any(LaboOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        LaboOrderResponseDTO result = laboOrderServiceImpl.updateStatus(
                101L, LaboOrderStatus.DELIVERED, "Mẫu răng hoàn thiện, khớp cắn chuẩn"
        );

        assertNotNull(result);
        assertEquals(LaboOrderStatus.DELIVERED, result.getStatus());
        assertEquals(LocalDate.now(), mockOrder.getReceivedDate());
        assertTrue(mockOrder.getNotes().contains("Mẫu răng hoàn thiện"));
        verify(laboOrderRepository, times(1)).save(mockOrder);
    }

    @Test
    @DisplayName("updateStatus - Ném IllegalArgumentException khi không tìm thấy đơn Labo cần đổi trạng thái")
    void updateStatus_OrderNotFound_ThrowsIllegalArgumentException() {
        when(laboOrderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                laboOrderServiceImpl.updateStatus(999L, LaboOrderStatus.DELIVERED, "test"));
    }

    @Test
    @DisplayName("uploadImage - Đính kèm link ảnh chụp kết quả hoặc phiếu xét nghiệm thành công")
    void uploadImage_WithDirectUrl_Success() throws IOException {
        when(laboOrderRepository.findById(101L)).thenReturn(Optional.of(mockOrder));
        when(laboOrderRepository.save(any(LaboOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        LaboOrderResponseDTO result = laboOrderServiceImpl.uploadImage(
                101L, null, "https://storage.clinic.vn/labo/phieu_ket_qua_01.jpg"
        );

        assertNotNull(result);
        assertEquals("https://storage.clinic.vn/labo/phieu_ket_qua_01.jpg", result.getImageUrl());
    }

    @Test
    @DisplayName("uploadImage - Upload file hình ảnh vật lý thành công và lưu vào thư mục cấu hình")
    void uploadImage_WithFile_Success() throws IOException {
        when(laboOrderRepository.findById(101L)).thenReturn(Optional.of(mockOrder));
        when(laboOrderRepository.save(any(LaboOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "labo_doc.png", "image/png", "fake image content".getBytes()
        );

        LaboOrderResponseDTO result = laboOrderServiceImpl.uploadImage(101L, file, null);

        assertNotNull(result);
        assertNotNull(result.getImageUrl());
        assertTrue(result.getImageUrl().startsWith("/uploads/labo/labo_"));
    }

    @Test
    @DisplayName("deleteOrder - Bác sĩ xóa phiếu labo thành công")
    void deleteOrder_Success() {
        doNothing().when(laboOrderRepository).deleteById(101L);

        laboOrderServiceImpl.deleteOrder(101L);

        verify(laboOrderRepository, times(1)).deleteById(101L);
    }

    @Test
    @DisplayName("getLaboStatistics - Tính toán chính xác các chỉ số thống kê (total, sent, delivered, withImage)")
    void getLaboStatistics_AccurateCounts() {
        LaboOrder order1 = new LaboOrder();
        order1.setStatus(LaboOrderStatus.SENT);

        LaboOrder order2 = new LaboOrder();
        order2.setStatus(LaboOrderStatus.PROCESSING);

        LaboOrder order3 = new LaboOrder();
        order3.setStatus(LaboOrderStatus.DELIVERED);
        order3.setImageUrl("/uploads/labo/test.jpg");

        when(laboOrderRepository.findByVisit_VisitIdOrderBySentDateDesc(200L))
                .thenReturn(List.of(order1, order2, order3));

        Map<String, Object> stats = laboOrderServiceImpl.getLaboStatistics(200L, null);

        assertNotNull(stats);
        assertEquals(3L, stats.get("total"));
        assertEquals(1L, stats.get("sent"));
        assertEquals(1L, stats.get("processing"));
        assertEquals(1L, stats.get("delivered"));
        assertEquals(1L, stats.get("withImage"));
    }
}

