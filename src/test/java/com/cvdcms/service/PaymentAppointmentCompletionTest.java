package com.cvdcms.service;

import com.cvdcms.DTO.PaymentRequestDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.Payment;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.InvoiceAuditLogRepository;
import com.cvdcms.repository.InvoiceRepository;
import com.cvdcms.repository.MembershipTierRepository;
import com.cvdcms.repository.PatientLoyaltyWalletRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.PaymentRepository;
import com.cvdcms.repository.PointTransactionRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAppointmentCompletionTest {

    @Mock PaymentRepository payments;
    @Mock InvoiceRepository invoices;
    @Mock InvoiceAuditLogRepository auditLogs;
    @Mock PatientProfileRepository patients;
    @Mock BranchRepository branches;
    @Mock StaffProfileRepository staff;
    @Mock StaffBranchAssignmentRepository assignments;
    @Mock PatientLoyaltyWalletRepository wallets;
    @Mock PointTransactionRepository points;
    @Mock MembershipTierRepository tiers;
    @Mock UserRepository users;
    @Mock LoyaltyService loyalty;
    @Mock InvoiceService invoiceService;
    @Mock VisitRepository visits;
    @Mock AppointmentLifecycleService lifecycle;

    private PaymentServiceImpl service;
    private Invoice invoice;
    private Visit visit;

    @BeforeEach
    void setUp() {
        service = new PaymentServiceImpl(
                payments, invoices, auditLogs, patients, branches, staff, assignments,
                wallets, points, tiers, users, loyalty, invoiceService, visits, lifecycle);

        User cashierUser = new User();
        cashierUser.setUserId(7L);
        cashierUser.setIsActive(true);
        cashierUser.setStatus("ACTIVE");

        StaffProfile cashier = new StaffProfile();
        cashier.setStaffId(70L);
        cashier.setStatus(StaffStatus.ACTIVE);

        Branch branch = new Branch();
        branch.setBranchId(1L);

        PatientProfile patient = new PatientProfile();
        patient.setProfileId(2L);

        visit = new Visit();
        visit.setVisitId(3L);
        visit.setStatus(VisitStatus.WAITING_PAYMENT);

        invoice = new Invoice();
        invoice.setInvoiceId(4L);
        invoice.setBranch(branch);
        invoice.setPatientProfile(patient);
        invoice.setVisit(visit);
        invoice.setTotalAmount(new BigDecimal("100000"));
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setRemainingDebt(new BigDecimal("100000"));
        invoice.setPaymentStatus(InvoicePaymentStatus.UNPAID);

        when(invoices.findByIdForUpdate(4L)).thenReturn(Optional.of(invoice));
        when(users.findById(7L)).thenReturn(Optional.of(cashierUser));
        when(staff.findByUser_UserId(7L)).thenReturn(Optional.of(cashier));
        when(users.hasActiveAdminRole(7L)).thenReturn(true);
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(visits.lockById(3L)).thenReturn(Optional.of(visit));
    }

    @Test
    void fullPaymentCompletesVisitAndDelegatesAppointmentCompletionWithActor() {
        PaymentRequestDTO request = new PaymentRequestDTO();
        request.setInvoiceId(4L);
        request.setAmount(new BigDecimal("100000"));
        request.setPaymentMethod(PaymentMethod.CASH);

        service.processPayment(request, 7L);

        assertEquals(InvoicePaymentStatus.PAID, invoice.getPaymentStatus());
        assertEquals(VisitStatus.COMPLETED, visit.getStatus());
        verify(visits).saveAndFlush(visit);
        verify(lifecycle).completeByVisit(3L, 7L);
    }
}
