package com.cvdcms.controller;

import com.cvdcms.DTO.InvoiceCreateRequestDTO;
import com.cvdcms.DTO.MedicineInvoiceCreateRequestDTO;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.User;
import com.cvdcms.repository.*;
import com.cvdcms.service.InvoiceService;
import com.cvdcms.service.PaymentService;
import com.cvdcms.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoicePageControllerReturnUrlTest {

    @Mock
    private InvoiceService invoiceService;
    @Mock
    private PaymentService paymentService;
    @Mock
    private RefundService refundService;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private VisitRepository visitRepository;
    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private PromotionRepository promotionRepository;
    @Mock
    private PatientVoucherRepository patientVoucherRepository;
    @Mock
    private PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private TreatmentPlanRepository treatmentPlanRepository;

    private InvoicePageController controller;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        controller = new InvoicePageController(
                invoiceService,
                paymentService,
                refundService,
                branchRepository,
                visitRepository,
                patientProfileRepository,
                promotionRepository,
                patientVoucherRepository,
                patientLoyaltyWalletRepository,
                userRepository,
                staffBranchAssignmentRepository,
                prescriptionRepository,
                treatmentPlanRepository
        );

        User user = new User();
        user.setUserId(1L);
        user.setUsername("cashier");

        session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
    }

    @Test
    @DisplayName("GET /invoices/create stores valid returnUrl in model and ignores unsafe external URLs")
    void createInvoiceForm_handlesReturnUrl() {
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(branchRepository.findAll()).thenReturn(Collections.emptyList());
        when(visitRepository.findVisitsByStatusAndBranch(any(), any())).thenReturn(Collections.emptyList());
        when(invoiceService.getHandedOverPrescriptions(any(), any(), any())).thenReturn(Collections.emptyList());

        Model validModel = new ConcurrentModel();
        String view = controller.createInvoiceForm(null, null, null, "/payments/patient/15", validModel, session);
        assertEquals("finance/invoices/create", view);
        assertEquals("/payments/patient/15", validModel.getAttribute("returnUrl"));

        Model unsafeModel = new ConcurrentModel();
        controller.createInvoiceForm(null, null, null, "https://evil.example/phish", unsafeModel, session);
        assertNull(unsafeModel.getAttribute("returnUrl"));
    }

    @Test
    @DisplayName("POST /invoices/create forwards returnUrl to invoice detail on success and preserves it on error")
    void saveInvoice_forwardsAndPreservesReturnUrl() {
        InvoiceCreateRequestDTO request = new InvoiceCreateRequestDTO();
        request.setVisitId(99L);
        request.setProfileId(15L);

        Invoice created = new Invoice();
        created.setInvoiceId(501L);
        created.setInvoiceCode("HD501");

        when(invoiceService.createInvoice(eq(request), eq(1L))).thenReturn(created);

        RedirectAttributesModelMap successRedirect = new RedirectAttributesModelMap();
        String successView = controller.saveInvoice(request, "/payments/patient/15", successRedirect, session);
        assertEquals("redirect:/invoices/501", successView);
        assertEquals("/payments/patient/15", successRedirect.getAttribute("returnUrl"));

        when(invoiceService.createInvoice(eq(request), eq(1L))).thenThrow(new RuntimeException("Validation error"));

        RedirectAttributesModelMap errorRedirect = new RedirectAttributesModelMap();
        String errorView = controller.saveInvoice(request, "/payments/patient/15", errorRedirect, session);
        assertEquals("redirect:/invoices/create", errorView);
        assertEquals("99", errorRedirect.getAttribute("visitId"));
        assertEquals("/payments/patient/15", errorRedirect.getAttribute("returnUrl"));
    }

    @Test
    @DisplayName("POST /invoices/prescriptions/{id}/choice preserves returnUrl when redirecting back to create")
    void updatePrescriptionChoice_preservesReturnUrl() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updatePrescriptionChoice(7L, "EXTERNAL", "note", null, 15L, "/payments/patient/15", redirectAttributes, session);

        verify(invoiceService).recordPrescriptionChoice(7L, "EXTERNAL", "note", 1L);
        assertEquals("redirect:/invoices/create", view);
        assertEquals("15", redirectAttributes.getAttribute("profileId"));
        assertEquals("/payments/patient/15", redirectAttributes.getAttribute("returnUrl"));
    }

    @Test
    @DisplayName("GET /invoices/{id} sets sanitized returnUrl on model")
    void invoiceDetail_setsReturnUrl() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(501L);
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(invoiceService.getInvoiceWithDetails(501L)).thenReturn(invoice);
        when(paymentService.getPaymentsByInvoiceId(501L)).thenReturn(Collections.emptyList());
        when(refundService.getRefundsByInvoiceId(501L)).thenReturn(Collections.emptyList());
        when(invoiceService.getInvoiceAuditLogs(501L)).thenReturn(Collections.emptyList());

        Model model = new ConcurrentModel();
        String view = controller.invoiceDetail(501L, "/payments/patient/15", model, session);
        assertEquals("finance/invoices/detail", view);
        assertEquals("/payments/patient/15", model.getAttribute("returnUrl"));
    }

    @Test
    @DisplayName("POST /invoices/create-medicine forwards returnUrl to invoice detail")
    void saveMedicineInvoice_forwardsReturnUrl() {
        MedicineInvoiceCreateRequestDTO request = new MedicineInvoiceCreateRequestDTO();
        request.setPrescriptionId(22L);

        Invoice created = new Invoice();
        created.setInvoiceId(602L);
        created.setInvoiceCode("HD602");

        when(invoiceService.createMedicineInvoice(eq(request), eq(1L))).thenReturn(created);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.saveMedicineInvoice(request, "/payments/dashboard", redirectAttributes, session);
        assertEquals("redirect:/invoices/602", view);
        assertEquals("/payments/dashboard", redirectAttributes.getAttribute("returnUrl"));
    }
}
