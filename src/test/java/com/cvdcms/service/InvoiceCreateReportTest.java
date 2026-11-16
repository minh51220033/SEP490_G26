package com.cvdcms.service;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.DisplayName;

import com.cvdcms.DTO.*;
import com.cvdcms.config.FinanceAccessInterceptor;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.*;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("CreateInvoice")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class InvoiceCreateReportTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceItemRepository invoiceItemRepository;
    @Mock
    private InvoiceAuditLogRepository invoiceAuditLogRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private VisitRepository visitRepository;
    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private StaffProfileRepository staffProfileRepository;
    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private PerformedServiceRepository performedServiceRepository;
    @Mock
    private PromotionRepository promotionRepository;
    @Mock
    private PatientVoucherRepository patientVoucherRepository;
    @Mock
    private PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    @Mock
    private PointTransactionRepository pointTransactionRepository;
    @Mock
    private MembershipTierRepository membershipTierRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private PrescriptionItemRepository prescriptionItemRepository;
    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private BranchInventoryRepository branchInventoryRepository;
    @Mock
    private MedicationRepository medicationRepository;
    @Mock
    private StockIssueRepository stockIssueRepository;

    private InvoiceServiceImpl invoiceService;

    private User mockAccountantUser;

    private StaffProfile mockAccountantStaff;

    private Branch mockBranch;
    private PatientProfile mockPatient;
    private Visit mockVisit;
    private com.cvdcms.entity.Service mockService;
    private PerformedService mockPerformedService;
    private MembershipTier mockTier;

    @BeforeEach
    void setUp() {
        invoiceService = new InvoiceServiceImpl(
                invoiceRepository, invoiceItemRepository, invoiceAuditLogRepository,
                visitRepository, patientProfileRepository, branchRepository,
                staffProfileRepository, staffBranchAssignmentRepository,
                serviceRepository, performedServiceRepository, promotionRepository,
                patientVoucherRepository, patientLoyaltyWalletRepository,
                pointTransactionRepository, userRepository, paymentRepository, refundRepository,
                prescriptionRepository, prescriptionItemRepository, materialBatchRepository,
                branchInventoryRepository, medicationRepository, stockIssueRepository
        );
        invoiceService.setMembershipTierRepository(membershipTierRepository);

        mockBranch = new Branch();
        mockBranch.setBranchId(1L);
        mockBranch.setBranchName("Cơ sở Quận 1");

        mockTier = new MembershipTier();
        mockTier.setTierId(2L);
        mockTier.setTierName("Thành viên Bạc");
        mockTier.setDiscountPercent(new BigDecimal("5.00"));
        mockTier.setPointValue(new BigDecimal("10.00"));
        mockTier.setSpendPerPoint(new BigDecimal("10000.00"));
        mockTier.setMinTotalSpent(new BigDecimal("5000000.00"));

        mockPatient = new PatientProfile();
        mockPatient.setProfileId(1L);
        mockPatient.setPatientCode("BN000101");
        mockPatient.setFullName("Nguyễn Văn An");
        mockPatient.setPhoneNumber("0987654321");
        mockPatient.setDepositBalance(BigDecimal.ZERO);
        mockPatient.setMembershipTier(mockTier);

        mockVisit = new Visit();
        mockVisit.setVisitId(10L);
        mockVisit.setVisitCode("VIS10001");
        mockVisit.setStatus(VisitStatus.WAITING_PAYMENT);
        mockVisit.setPatientProfile(mockPatient);
        mockVisit.setBranch(mockBranch);

        mockAccountantUser = new User();
        mockAccountantUser.setUserId(1L);
        mockAccountantUser.setUsername("ketoan_an");
        mockAccountantUser.setStatus("ACTIVE");
        mockAccountantUser.setIsActive(true);

        mockAccountantStaff = new StaffProfile();
        mockAccountantStaff.setStaffId(1L);
        mockAccountantStaff.setStaffCode("NVKT01");
        mockAccountantStaff.setFullName("Kế Toán An");
        mockAccountantStaff.setStatus(StaffStatus.ACTIVE);
        mockAccountantStaff.setUser(mockAccountantUser);

        mockService = new com.cvdcms.entity.Service();
        mockService.setServiceId(100L);
        mockService.setServiceName("Trám răng thẩm mỹ Composite");
        mockService.setBasePrice(new BigDecimal("500000.00"));

        mockPerformedService = new PerformedService();
        mockPerformedService.setPerformedId(200L);
        mockPerformedService.setVisit(mockVisit);
        mockPerformedService.setPatientProfile(mockPatient);
        mockPerformedService.setBranch(mockBranch);
        mockPerformedService.setService(mockService);
        mockPerformedService.setQuantity(new BigDecimal("2"));
        mockPerformedService.setUnitPrice(new BigDecimal("500000.00"));
        mockPerformedService.setDiscountAmount(BigDecimal.ZERO);
        mockPerformedService.setFinalPrice(new BigDecimal("1000000.00"));
        mockPerformedService.setStatus(PerformedServiceStatus.COMPLETED);
    }

    private void mockCashierAuthSuccess() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockAccountantUser));
        when(staffProfileRepository.findByUser_UserId(1L)).thenReturn(Optional.of(mockAccountantStaff));
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
    }


    @Test
    @DisplayName("UTCID01 - A - Chặn lập hóa đơn trùng cho cùng một dịch vụ đã thực hiện")
    void UTCID01() throws Exception {
        mockCashierAuthSuccess();
                when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
                when(performedServiceRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(mockPerformedService));
                // Dịch vụ 200 đã nằm trong một hóa đơn còn hiệu lực khác
                when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(200L)).thenReturn(true);
        
                InvoiceCreateRequestDTO request = new InvoiceCreateRequestDTO();
                request.setVisitId(10L);
                InvoiceItemRequestDTO item = new InvoiceItemRequestDTO();
                item.setPerformedId(200L);
                item.setServiceId(100L);
                request.setItems(List.of(item));
        
                IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                        invoiceService.createInvoice(request, 1L));
                assertTrue(ex.getMessage().contains("đã được lập hóa đơn còn hiệu lực trước đó"));
    }

    @Test
    @DisplayName("UTCID02 - A - Dịch vụ bỏ tick không được xuất hiện trong preview và hóa đơn lưu; không chọn gì thì báo lỗi")
    void UTCID02() throws Exception {
        mockCashierAuthSuccess();
                when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
                when(performedServiceRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(mockPerformedService));
                when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(200L)).thenReturn(false);
                when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        
                // Form chỉ gửi dịch vụ 200 (dịch vụ thứ hai đã bị bỏ tick ở giao diện và không nằm trong items)
                InvoiceCreateRequestDTO request = new InvoiceCreateRequestDTO();
                request.setVisitId(10L);
                InvoiceItemRequestDTO item1 = new InvoiceItemRequestDTO();
                item1.setPerformedId(200L);
                item1.setServiceId(100L);
                item1.setUnitPrice(new BigDecimal("500000.00"));
                item1.setQuantity(new BigDecimal("2"));
                request.setItems(List.of(item1));
        
                InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(request);
                assertEquals(new BigDecimal("1000000.00"), preview.getTotalServiceAmount());
        
                Invoice saved = invoiceService.createInvoice(request, 1L);
                assertNotNull(saved);
                assertEquals(1, saved.getItems().size());
                assertEquals(200L, saved.getItems().get(0).getPerformedService().getPerformedId());
        
                // Bỏ chọn tất cả (items rỗng)
                InvoiceCreateRequestDTO emptyRequest = new InvoiceCreateRequestDTO();
                emptyRequest.setVisitId(10L);
                emptyRequest.setItems(Collections.emptyList());
        
                assertThrows(IllegalArgumentException.class, () -> invoiceService.calculatePreview(emptyRequest));
                assertThrows(IllegalArgumentException.class, () -> invoiceService.createInvoice(emptyRequest, 1L));
    }

    @Test
    @DisplayName("UTCID03 - N - Preview và Hóa đơn lưu khớp chính xác số tiền sau khi áp dụng giảm trừ thành viên")
    void UTCID03() throws Exception {
        mockCashierAuthSuccess();
                when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
                when(performedServiceRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(mockPerformedService));
                when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(200L)).thenReturn(false);
                when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        
                InvoiceCreateRequestDTO request = new InvoiceCreateRequestDTO();
                request.setVisitId(10L);
                InvoiceItemRequestDTO item = new InvoiceItemRequestDTO();
                item.setPerformedId(200L);
                item.setServiceId(100L);
                item.setUnitPrice(new BigDecimal("500000.00"));
                item.setQuantity(new BigDecimal("2"));
                request.setItems(List.of(item));
        
                InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(request);
                Invoice saved = invoiceService.createInvoice(request, 1L);
        
                // Tổng dịch vụ: 1.000.000đ, Giảm Bạc 5%: 50.000đ -> Phải trả: 950.000đ
                assertEquals(new BigDecimal("1000000.00"), preview.getTotalServiceAmount());
                assertEquals(new BigDecimal("50000.00"), preview.getTotalDiscountAmount());
                assertEquals(new BigDecimal("950000.00"), preview.getTotalAmount());
        
                assertEquals(preview.getTotalServiceAmount(), saved.getTotalServiceAmount());
                assertEquals(preview.getTotalDiscountAmount(), saved.getDiscountAmount());
                assertEquals(preview.getTotalAmount(), saved.getTotalAmount());
                assertEquals(new BigDecimal("950000.00"), saved.getRemainingDebt());
    }

    @Test
    @DisplayName("UTCID04 - N - Dịch vụ 2.000.000đ, giảm giá lâm sàng 200.000đ: Preview và Lưu đều ra 1.800.000đ")
    void UTCID04() throws Exception {
        mockCashierAuthSuccess();
        
                // Tạo PerformedService giá 2.000.000đ, giảm giá lâm sàng 200.000đ
                com.cvdcms.entity.Service service2M = new com.cvdcms.entity.Service();
                service2M.setServiceId(105L);
                service2M.setServiceName("Trồng răng Implant");
                service2M.setBasePrice(new BigDecimal("2000000.00"));
        
                PerformedService ps = new PerformedService();
                ps.setPerformedId(205L);
                ps.setVisit(mockVisit);
                ps.setPatientProfile(mockPatient);
                ps.setBranch(mockBranch);
                ps.setService(service2M);
                ps.setQuantity(BigDecimal.ONE);
                ps.setUnitPrice(new BigDecimal("2000000.00"));
                ps.setDiscountAmount(new BigDecimal("200000.00")); // Giảm lâm sàng 200.000đ
                ps.setFinalPrice(new BigDecimal("1800000.00"));
                ps.setStatus(PerformedServiceStatus.COMPLETED);
        
                when(performedServiceRepository.findById(205L)).thenReturn(Optional.of(ps));
                when(performedServiceRepository.findByIdForUpdate(205L)).thenReturn(Optional.of(ps));
                when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
                when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(205L)).thenReturn(false);
                when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
                // 1. Kiểm tra Preview
                InvoiceCreateRequestDTO request = new InvoiceCreateRequestDTO();
                request.setVisitId(10L);
                request.setProfileId(1L);
                request.setBranchId(1L);
                InvoiceItemRequestDTO itemDTO = new InvoiceItemRequestDTO();
                itemDTO.setPerformedId(205L);
                itemDTO.setUnitPrice(new BigDecimal("2000000.00"));
                itemDTO.setQuantity(BigDecimal.ONE);
                request.setItems(List.of(itemDTO));
        
                // Bệnh nhân không có ưu đãi tier
                mockPatient.setMembershipTier(null);
        
                InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(request);
                // Preview phải tính (2.000.000 * 1) - 200.000 = 1.800.000đ
                assertEquals(new BigDecimal("1800000.00"), preview.getTotalServiceAmount());
                assertEquals(new BigDecimal("1800000.00"), preview.getTotalAmount());
        
                // 2. Kiểm tra Lưu hóa đơn
                Invoice saved = invoiceService.createInvoice(request, 1L);
                assertEquals(new BigDecimal("1800000.00"), saved.getTotalServiceAmount());
                assertEquals(new BigDecimal("1800000.00"), saved.getTotalAmount());
                assertEquals(new BigDecimal("1800000.00"), saved.getRemainingDebt());
    }

    @Test
    @DisplayName("UTCID05 - B - Điểm đổi vượt số dư thực có được giới hạn nhất quán cả lúc preview và lúc lưu")
    void UTCID05() throws Exception {
        mockCashierAuthSuccess();
        
                PerformedService ps = new PerformedService();
                ps.setPerformedId(206L);
                ps.setVisit(mockVisit);
                ps.setPatientProfile(mockPatient);
                ps.setBranch(mockBranch);
                ps.setService(mockService);
                ps.setQuantity(BigDecimal.ONE);
                ps.setUnitPrice(new BigDecimal("500000.00"));
                ps.setDiscountAmount(BigDecimal.ZERO);
                ps.setStatus(PerformedServiceStatus.COMPLETED);
        
                when(performedServiceRepository.findById(206L)).thenReturn(Optional.of(ps));
                when(performedServiceRepository.findByIdForUpdate(206L)).thenReturn(Optional.of(ps));
                when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));
                when(invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(206L)).thenReturn(false);
                when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
                // Ví chỉ có 50 điểm
                PatientLoyaltyWallet wallet = new PatientLoyaltyWallet();
                wallet.setWalletId(1L);
                wallet.setPatientProfile(mockPatient);
                wallet.setCurrentPoints(50);
                wallet.setTotalSpentAmount(BigDecimal.ZERO);
                when(patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(1L)).thenReturn(Optional.of(wallet));
        
                mockPatient.setMembershipTier(null);
        
                // Yêu cầu đổi 500 điểm (vượt quá 50 điểm thực có)
                InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
                req.setVisitId(10L);
                req.setProfileId(1L);
                req.setBranchId(1L);
                req.setPointsToRedeem(500);
                InvoiceItemRequestDTO item = new InvoiceItemRequestDTO();
                item.setPerformedId(206L);
                req.setItems(List.of(item));
        
                // Preview chỉ được đổi 50 điểm (50 * 10 = 500đ)
                InvoiceCalculationPreviewDTO preview = invoiceService.calculatePreview(req);
                assertEquals(new BigDecimal("500.00"), preview.getPointDiscountAmount());
                assertEquals(new BigDecimal("499500.00"), preview.getTotalAmount());
        
                // Lưu: điểm chưa bị trừ tại thời điểm tạo hóa đơn (CONFIRMED) theo Requirement 1
                Invoice saved = invoiceService.createInvoice(req, 1L);
                assertEquals(new BigDecimal("500.00"), saved.getPointDiscount());
                assertEquals(new BigDecimal("499500.00"), saved.getTotalAmount());
                assertEquals(50, wallet.getCurrentPoints()); // Vẫn còn 50 điểm trong ví
        
                // Điểm chỉ được trừ khi chốt thanh toán (finalizeInvoiceBenefits)
                when(patientLoyaltyWalletRepository.findByProfileIdForUpdate(1L)).thenReturn(Optional.of(wallet));
                invoiceService.finalizeInvoiceBenefits(saved);
                assertEquals(0, wallet.getCurrentPoints()); // Số dư về 0 sau khi chốt thanh toán
    }

    @Test
    @DisplayName("UTCID06 - N - [Unit] Dịch vụ 500.000đ + thuốc 50.000đ => MỘT hóa đơn MIXED 550.000đ")
    void UTCID06() throws Exception {
        mockCashierAuthSuccess();
                mockPatient.setMembershipTier(null);
                when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));
        
                // Dịch vụ 500.000đ
                mockPerformedService.setQuantity(BigDecimal.ONE);
                mockPerformedService.setUnitPrice(new BigDecimal("500000.00"));
                mockPerformedService.setFinalPrice(new BigDecimal("500000.00"));
                when(performedServiceRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(mockPerformedService));
        
                // Thuốc 50.000đ
                Prescription presc = new Prescription();
                presc.setPrescriptionId(900L);
                presc.setVisit(mockVisit);
                presc.setBranch(mockBranch);
                presc.setStatus(PrescriptionStatus.PENDING);
        
                Material mat = new Material();
                mat.setMaterialId(10L);
                mat.setMaterialName("Paracetamol 500mg");
        
                Medication med = new Medication();
                med.setMedicationId(11L);
                med.setMaterial(mat);
                med.setDrugName("Paracetamol 500mg");
                med.setSalePrice(new BigDecimal("5000.00"));
        
                PrescriptionItem pItem = new PrescriptionItem();
                pItem.setItemId(901L);
                pItem.setPrescription(presc);
                pItem.setMedication(med);
                pItem.setDrugName("Paracetamol 500mg");
                pItem.setQuantity(new BigDecimal("10.00"));
                pItem.setBoughtQuantity(BigDecimal.ZERO);
                pItem.setUnitPrice(new BigDecimal("5000.00"));
                presc.setItems(new java.util.ArrayList<>(List.of(pItem)));
        
                BranchInventory inv = new BranchInventory();
                inv.setBranch(mockBranch);
                inv.setMaterial(mat);
                inv.setCurrentStock(new BigDecimal("100.00"));
        
                MaterialBatch batch = new MaterialBatch();
                batch.setBatchId(12L);
                batch.setBatchNumber("PARA-01");
                batch.setMaterial(mat);
                batch.setBranch(mockBranch);
                batch.setQuantityRemaining(new BigDecimal("100.00"));
                batch.setExpiryDate(LocalDate.now().plusMonths(12));
        
                when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(presc));
                when(prescriptionItemRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(pItem));
                when(branchInventoryRepository.findForUpdate(eq(1L), eq(10L))).thenReturn(Optional.of(inv));
                when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(10L), any(LocalDate.class)))
                        .thenReturn(List.of(batch));
                when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));
        
                // Request: 1 dịch vụ + 10 viên thuốc (5.000đ/viên = 50.000đ)
                InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
                req.setVisitId(mockVisit.getVisitId());
                req.setProfileId(mockPatient.getProfileId());
                req.setBranchId(mockBranch.getBranchId());
        
                InvoiceItemRequestDTO sItem = new InvoiceItemRequestDTO();
                sItem.setPerformedId(200L);
                sItem.setServiceId(100L);
                sItem.setQuantity(BigDecimal.ONE);
                sItem.setUnitPrice(new BigDecimal("500000.00"));
                req.setItems(List.of(sItem));
        
                req.setPrescriptionId(900L);
                req.setBuyMedicineAtClinic(true);
                req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, new BigDecimal("10.00"))));
        
                Invoice invoice = invoiceService.createInvoice(req, 1L);
        
                assertNotNull(invoice);
                assertEquals(InvoiceType.MIXED, invoice.getInvoiceType(), "Hóa đơn gồm cả dịch vụ và thuốc phải là loại MIXED");
                assertEquals(new BigDecimal("500000.00"), invoice.getTotalServiceAmount());
                assertEquals(new BigDecimal("50000.00"), invoice.getTotalMedicineAmount());
                assertEquals(new BigDecimal("550000.00"), invoice.getTotalAmount(), "Tổng tiền phải bằng 500.000 + 50.000 = 550.000");
                assertEquals(2, invoice.getItems().size(), "Hóa đơn phải có chính xác 2 items (1 dịch vụ, 1 thuốc)");
                assertEquals(new BigDecimal("10.00"), pItem.getBoughtQuantity(), "Đơn thuốc phải được cập nhật boughtQuantity = 10");
                assertEquals(PrescriptionStatus.CLINIC_PURCHASE_COMPLETED, presc.getStatus());
                verify(stockIssueRepository).save(any(StockIssue.class));
    }

    @Test
    @DisplayName("UTCID07 - N - [Unit] Không chọn mua thuốc => Hóa đơn SERVICE 500.000đ, totalMedicineAmount = 0")
    void UTCID07() throws Exception {
        mockCashierAuthSuccess();
                mockPatient.setMembershipTier(null);
                when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));
        
                mockPerformedService.setQuantity(BigDecimal.ONE);
                mockPerformedService.setUnitPrice(new BigDecimal("500000.00"));
                mockPerformedService.setFinalPrice(new BigDecimal("500000.00"));
                when(performedServiceRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(mockPerformedService));
                when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));
        
                InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
                req.setVisitId(mockVisit.getVisitId());
                req.setProfileId(mockPatient.getProfileId());
                req.setBranchId(mockBranch.getBranchId());
        
                InvoiceItemRequestDTO sItem = new InvoiceItemRequestDTO();
                sItem.setPerformedId(200L);
                sItem.setServiceId(100L);
                sItem.setQuantity(BigDecimal.ONE);
                sItem.setUnitPrice(new BigDecimal("500000.00"));
                req.setItems(List.of(sItem));
                req.setBuyMedicineAtClinic(false); // Không mua thuốc tại PK
        
                Invoice invoice = invoiceService.createInvoice(req, 1L);
        
                assertNotNull(invoice);
                assertEquals(InvoiceType.SERVICE, invoice.getInvoiceType());
                assertEquals(new BigDecimal("500000.00"), invoice.getTotalServiceAmount());
                assertEquals(new BigDecimal("0.00"), invoice.getTotalMedicineAmount());
                assertEquals(new BigDecimal("500000.00"), invoice.getTotalAmount());
                verify(prescriptionRepository, never()).findByIdForUpdate(anyLong());
                verify(stockIssueRepository, never()).save(any(StockIssue.class));
    }

    @Test
    @DisplayName("UTCID08 - N - [Unit] Không có dịch vụ nhưng có thuốc hợp lệ => Hóa đơn MEDICINE 50.000đ")
    void UTCID08() throws Exception {
        mockCashierAuthSuccess();
                when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));
        
                Prescription presc = new Prescription();
                presc.setPrescriptionId(900L);
                presc.setVisit(mockVisit);
                presc.setBranch(mockBranch);
                presc.setStatus(PrescriptionStatus.PENDING);
        
                Material mat = new Material();
                mat.setMaterialId(10L);
                mat.setMaterialName("Amoxicillin 500mg");
        
                Medication med = new Medication();
                med.setMedicationId(11L);
                med.setMaterial(mat);
                med.setDrugName("Amoxicillin 500mg");
                med.setSalePrice(new BigDecimal("5000.00"));
        
                PrescriptionItem pItem = new PrescriptionItem();
                pItem.setItemId(901L);
                pItem.setPrescription(presc);
                pItem.setMedication(med);
                pItem.setDrugName("Amoxicillin 500mg");
                pItem.setQuantity(new BigDecimal("10.00"));
                pItem.setBoughtQuantity(BigDecimal.ZERO);
                pItem.setUnitPrice(new BigDecimal("5000.00"));
                presc.setItems(new java.util.ArrayList<>(List.of(pItem)));
        
                BranchInventory inv = new BranchInventory();
                inv.setBranch(mockBranch);
                inv.setMaterial(mat);
                inv.setCurrentStock(new BigDecimal("100.00"));
        
                MaterialBatch batch = new MaterialBatch();
                batch.setBatchId(12L);
                batch.setBatchNumber("AMOX-01");
                batch.setMaterial(mat);
                batch.setBranch(mockBranch);
                batch.setQuantityRemaining(new BigDecimal("100.00"));
                batch.setExpiryDate(LocalDate.now().plusMonths(12));
        
                when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(presc));
                when(prescriptionItemRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(pItem));
                when(branchInventoryRepository.findForUpdate(eq(1L), eq(10L))).thenReturn(Optional.of(inv));
                when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(10L), any(LocalDate.class)))
                        .thenReturn(List.of(batch));
                when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));
        
                // items rỗng, chỉ chọn mua thuốc
                InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
                req.setVisitId(mockVisit.getVisitId());
                req.setProfileId(mockPatient.getProfileId());
                req.setBranchId(mockBranch.getBranchId());
                req.setItems(Collections.emptyList());
                req.setPrescriptionId(900L);
                req.setBuyMedicineAtClinic(true);
                req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, new BigDecimal("10.00"))));
        
                Invoice invoice = invoiceService.createInvoice(req, 1L);
        
                assertNotNull(invoice);
                assertEquals(InvoiceType.MEDICINE, invoice.getInvoiceType(), "Hóa đơn chỉ có thuốc phải có loại MEDICINE");
                assertEquals(new BigDecimal("0.00"), invoice.getTotalServiceAmount());
                assertEquals(new BigDecimal("50000.00"), invoice.getTotalMedicineAmount());
                assertEquals(new BigDecimal("50000.00"), invoice.getTotalAmount());
    }

    @Test
    @DisplayName("UTCID09 - N - [Unit] Mua một phần thuốc (kê 10, mua 4) => tính đúng 20.000đ, còn lại 6")
    void UTCID09() throws Exception {
        mockCashierAuthSuccess();
                when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));
        
                Prescription presc = new Prescription();
                presc.setPrescriptionId(900L);
                presc.setVisit(mockVisit);
                presc.setBranch(mockBranch);
                presc.setStatus(PrescriptionStatus.PENDING);
        
                Material mat = new Material();
                mat.setMaterialId(10L);
                mat.setMaterialName("Paracetamol 500mg");
        
                Medication med = new Medication();
                med.setMedicationId(11L);
                med.setMaterial(mat);
                med.setDrugName("Paracetamol 500mg");
                med.setSalePrice(new BigDecimal("5000.00"));
        
                PrescriptionItem pItem = new PrescriptionItem();
                pItem.setItemId(901L);
                pItem.setPrescription(presc);
                pItem.setMedication(med);
                pItem.setDrugName("Paracetamol 500mg");
                pItem.setQuantity(new BigDecimal("10.00"));
                pItem.setBoughtQuantity(BigDecimal.ZERO);
                pItem.setUnitPrice(new BigDecimal("5000.00"));
                presc.setItems(new java.util.ArrayList<>(List.of(pItem)));
        
                BranchInventory inv = new BranchInventory();
                inv.setBranch(mockBranch);
                inv.setMaterial(mat);
                inv.setCurrentStock(new BigDecimal("100.00"));
        
                MaterialBatch batch = new MaterialBatch();
                batch.setBatchId(12L);
                batch.setBatchNumber("PARA-01");
                batch.setMaterial(mat);
                batch.setBranch(mockBranch);
                batch.setQuantityRemaining(new BigDecimal("100.00"));
                batch.setExpiryDate(LocalDate.now().plusMonths(12));
        
                when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(presc));
                when(prescriptionItemRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(pItem));
                when(branchInventoryRepository.findForUpdate(eq(1L), eq(10L))).thenReturn(Optional.of(inv));
                when(materialBatchRepository.findAvailableBatchesFefoForUpdate(eq(1L), eq(10L), any(LocalDate.class)))
                        .thenReturn(List.of(batch));
                when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));
        
                InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
                req.setVisitId(mockVisit.getVisitId());
                req.setProfileId(mockPatient.getProfileId());
                req.setBranchId(mockBranch.getBranchId());
                req.setItems(Collections.emptyList());
                req.setPrescriptionId(900L);
                req.setBuyMedicineAtClinic(true);
                req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, new BigDecimal("4.00")))); // Mua 4 viên
        
                Invoice invoice = invoiceService.createInvoice(req, 1L);
        
                assertNotNull(invoice);
                assertEquals(new BigDecimal("20000.00"), invoice.getTotalMedicineAmount());
                assertEquals(new BigDecimal("4.00"), pItem.getBoughtQuantity());
                assertEquals(PrescriptionStatus.CLINIC_PURCHASE_PARTIAL, presc.getStatus());
    }

    @Test
    @DisplayName("UTCID10 - A - [Unit] Bắt lỗi khi đơn thuốc không thuộc ca khám được chọn")
    void UTCID10() throws Exception {
        mockCashierAuthSuccess();
                when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));
        
                Visit anotherVisit = new Visit();
                anotherVisit.setVisitId(999L);
                anotherVisit.setVisitCode("VIS999");
        
                Prescription prescOfAnotherVisit = new Prescription();
                prescOfAnotherVisit.setPrescriptionId(900L);
                prescOfAnotherVisit.setVisit(anotherVisit);
                prescOfAnotherVisit.setBranch(mockBranch);
        
                when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(prescOfAnotherVisit));
        
                InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
                req.setVisitId(mockVisit.getVisitId());
                req.setProfileId(mockPatient.getProfileId());
                req.setBranchId(mockBranch.getBranchId());
                req.setPrescriptionId(900L);
                req.setBuyMedicineAtClinic(true);
                req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, BigDecimal.ONE)));
        
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                        () -> invoiceService.createInvoice(req, 1L));
                assertTrue(ex.getMessage().contains("không thuộc ca khám"));
    }

    @Test
    @DisplayName("UTCID11 - A - [Unit] Thuốc chưa có giá bán hợp lệ phải báo lỗi cấu hình, không đoán giá")
    void UTCID11() throws Exception {
        mockCashierAuthSuccess();
                when(visitRepository.findById(mockVisit.getVisitId())).thenReturn(Optional.of(mockVisit));
        
                Prescription presc = new Prescription();
                presc.setPrescriptionId(900L);
                presc.setVisit(mockVisit);
                presc.setBranch(mockBranch);
        
                Medication medWithoutPrice = new Medication();
                medWithoutPrice.setMedicationId(11L);
                medWithoutPrice.setDrugName("Thuốc Thử Nghiệm");
                medWithoutPrice.setSalePrice(null); // Không có giá
        
                PrescriptionItem pItem = new PrescriptionItem();
                pItem.setItemId(901L);
                pItem.setPrescription(presc);
                pItem.setMedication(medWithoutPrice);
                pItem.setUnitPrice(null); // Đơn thuốc cũng không có giá
                pItem.setQuantity(BigDecimal.ONE);
                pItem.setBoughtQuantity(BigDecimal.ZERO);
                presc.setItems(new java.util.ArrayList<>(List.of(pItem)));
        
                when(prescriptionRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(presc));
                when(prescriptionItemRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(pItem));
        
                InvoiceCreateRequestDTO req = new InvoiceCreateRequestDTO();
                req.setVisitId(mockVisit.getVisitId());
                req.setProfileId(mockPatient.getProfileId());
                req.setBranchId(mockBranch.getBranchId());
                req.setPrescriptionId(900L);
                req.setBuyMedicineAtClinic(true);
                req.setMedicineItems(List.of(new MedicineItemRequestDTO(901L, BigDecimal.ONE)));
        
                IllegalStateException ex = assertThrows(IllegalStateException.class,
                        () -> invoiceService.createInvoice(req, 1L));
                assertTrue(ex.getMessage().contains("chưa có giá bán hợp lệ"));
    }

    @Test
    @DisplayName("UTCID12 - A - Request lập hóa đơn null")
    void UTCID12() throws Exception {
        assertThrows(IllegalArgumentException.class,()->invoiceService.createInvoice(null,1L));verify(invoiceRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID13 - A - Thiếu visitId")
    void UTCID13() throws Exception {
        assertThrows(IllegalArgumentException.class,()->invoiceService.createInvoice(new InvoiceCreateRequestDTO(),1L));verify(invoiceRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID14 - A - Ca khám không tồn tại")
    void UTCID14() throws Exception {
        InvoiceCreateRequestDTO d=new InvoiceCreateRequestDTO();d.setVisitId(99L);assertThrows(IllegalArgumentException.class,()->invoiceService.createInvoice(d,1L));verify(invoiceRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID15 - A - Ca khám chưa bàn giao thanh toán")
    void UTCID15() throws Exception {
        mockVisit.setStatus(VisitStatus.IN_EXAM);when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));InvoiceCreateRequestDTO d=new InvoiceCreateRequestDTO();d.setVisitId(10L);assertThrows(IllegalStateException.class,()->invoiceService.createInvoice(d,1L));verify(invoiceRepository,never()).save(any());
    }

    @Test
    @DisplayName("UTCID16 - A - Profile gửi lên khác hồ sơ lượt khám")
    void UTCID16() throws Exception {
        when(visitRepository.findById(10L)).thenReturn(Optional.of(mockVisit));InvoiceCreateRequestDTO d=new InvoiceCreateRequestDTO();d.setVisitId(10L);d.setProfileId(999L);assertThrows(IllegalArgumentException.class,()->invoiceService.createInvoice(d,1L));verify(invoiceRepository,never()).save(any());
    }
}
