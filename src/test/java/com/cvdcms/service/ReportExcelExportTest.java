package com.cvdcms.service;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.DisplayName;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.DTO.report.*;
import com.cvdcms.controller.AdminReportController;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.repository.*;
import com.cvdcms.service.impl.ReportServiceImpl;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExportExcel")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class ReportExcelExportTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private OperatingExpenseRepository operatingExpenseRepository;
    @Mock
    private VisitRepository visitRepository;
    @Mock
    private AppointmentRepository appointmentRepository;

    private ReportServiceImpl reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportServiceImpl(
                invoiceRepository,
                paymentRepository,
                refundRepository,
                operatingExpenseRepository,
                visitRepository,
                appointmentRepository
        );
    }


    @Test
    @DisplayName("UTCID01 - N - Xuất Excel các loại báo cáo tạo file .xlsx hợp lệ mở được bằng POI")
    void UTCID01() throws Exception {
        LocalDate start = LocalDate.of(2026, 11, 1);
                LocalDate end = LocalDate.of(2026, 11, 30);
                List<Long> branchIds = List.of(1L);
        
                // Mock dữ liệu tổng quan
                List<Object[]> emptyBreakdown = Collections.singletonList(new Object[]{
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000000"), BigDecimal.ZERO
                });
                when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(emptyBreakdown);
                when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(new BigDecimal("5000000"));
                when(paymentRepository.sumAdvanceDepositByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                when(paymentRepository.sumDepositWalletUsedByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                when(paymentRepository.findDailyRealPaymentsForPeriod(any(), any(), any())).thenReturn(new ArrayList<>());
                when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(new ArrayList<>());
        
                // Test xuất DASHBOARD
                byte[] dashboardExcel = reportService.exportReportToExcel("DASHBOARD", branchIds, start, end, "Chi nhánh Quận 1");
                assertNotNull(dashboardExcel);
                assertTrue(dashboardExcel.length > 0);
        
                try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(dashboardExcel))) {
                    Sheet sheet = wb.getSheetAt(0);
                    assertNotNull(sheet);
                    assertTrue(sheet.getLastRowNum() > 3);
                }
        
                // Test xuất DEBTS
                PatientDebtSummaryDTO pDebt = new PatientDebtSummaryDTO(
                        1L, "BN123", "=cmd|' /C calc'!A0", "0988776655",
                        BigDecimal.ZERO, 2L,
                        new BigDecimal("2000000"), new BigDecimal("1000000"), new BigDecimal("1000000"),
                        LocalDateTime.now()
                );
                when(invoiceRepository.findAllPatientDebtsByBranches(any())).thenReturn(List.of(pDebt));
        
                byte[] debtExcel = reportService.exportReportToExcel("DEBTS", branchIds, start, end, "Chi nhánh Quận 1");
                assertNotNull(debtExcel);
        
                try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(debtExcel))) {
                    Sheet sheet = wb.getSheetAt(0);
                    assertNotNull(sheet);
                    // Dòng 3 là dòng dữ liệu bệnh nhân đầu tiên sau dòng tiêu đề (0) và header (2)
                    String sanitizedName = sheet.getRow(3).getCell(2).getStringCellValue();
                    // Đảm bảo ký tự '=' đã được tiền tố dấu nháy đơn "'" chống injection công thức
                    assertTrue(sanitizedName.startsWith("'="));
                }
    }

    @Test
    @DisplayName("UTCID02 - N - Xuất riêng báo cáo FINANCIAL")
    void UTCID02() throws Exception {
        LocalDate start = LocalDate.of(2026, 11, 1);
                LocalDate end = LocalDate.of(2026, 11, 30);
                List<Long> branchIds = List.of(1L);
        
                // Mock dữ liệu tổng quan
                List<Object[]> emptyBreakdown = Collections.singletonList(new Object[]{
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000000"), BigDecimal.ZERO
                });
                lenient().when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(emptyBreakdown);
                lenient().when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(new BigDecimal("5000000"));
                lenient().when(paymentRepository.sumAdvanceDepositByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(paymentRepository.sumDepositWalletUsedByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(paymentRepository.findDailyRealPaymentsForPeriod(any(), any(), any())).thenReturn(new ArrayList<>());
                lenient().when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(new ArrayList<>());
        
                byte[] bytes=reportService.exportReportToExcel("FINANCIAL",branchIds,start,end,"Cơ sở 1");assertNotNull(bytes);try(Workbook workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))){assertTrue(workbook.getNumberOfSheets()>0);assertTrue(workbook.getSheetAt(0).getLastRowNum()>2);assertNotNull(workbook.getSheetAt(0).getRow(0));}
    }

    @Test
    @DisplayName("UTCID03 - N - Xuất riêng báo cáo OPERATIONAL")
    void UTCID03() throws Exception {
        LocalDate start = LocalDate.of(2026, 11, 1);
                LocalDate end = LocalDate.of(2026, 11, 30);
                List<Long> branchIds = List.of(1L);
        
                // Mock dữ liệu tổng quan
                List<Object[]> emptyBreakdown = Collections.singletonList(new Object[]{
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000000"), BigDecimal.ZERO
                });
                lenient().when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(emptyBreakdown);
                lenient().when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(new BigDecimal("5000000"));
                lenient().when(paymentRepository.sumAdvanceDepositByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(paymentRepository.sumDepositWalletUsedByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(paymentRepository.findDailyRealPaymentsForPeriod(any(), any(), any())).thenReturn(new ArrayList<>());
                lenient().when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(new ArrayList<>());
        
                byte[] bytes=reportService.exportReportToExcel("OPERATIONAL",branchIds,start,end,"Cơ sở 1");assertNotNull(bytes);try(Workbook workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))){assertTrue(workbook.getNumberOfSheets()>0);assertTrue(workbook.getSheetAt(0).getLastRowNum()>2);assertNotNull(workbook.getSheetAt(0).getRow(0));}
    }

    @Test
    @DisplayName("UTCID04 - N - Xuất riêng báo cáo DEBTS")
    void UTCID04() throws Exception {
        LocalDate start = LocalDate.of(2026, 11, 1);
                LocalDate end = LocalDate.of(2026, 11, 30);
                List<Long> branchIds = List.of(1L);
        
                // Mock dữ liệu tổng quan
                List<Object[]> emptyBreakdown = Collections.singletonList(new Object[]{
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000000"), BigDecimal.ZERO
                });
                lenient().when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(emptyBreakdown);
                lenient().when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(new BigDecimal("5000000"));
                lenient().when(paymentRepository.sumAdvanceDepositByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(paymentRepository.sumDepositWalletUsedByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(paymentRepository.findDailyRealPaymentsForPeriod(any(), any(), any())).thenReturn(new ArrayList<>());
                lenient().when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(new ArrayList<>());
        
                byte[] bytes=reportService.exportReportToExcel("DEBTS",branchIds,start,end,"Cơ sở 1");assertNotNull(bytes);try(Workbook workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))){assertTrue(workbook.getNumberOfSheets()>0);assertTrue(workbook.getSheetAt(0).getLastRowNum()>2);assertNotNull(workbook.getSheetAt(0).getRow(0));}
    }

    @Test
    @DisplayName("UTCID05 - N - Xuất riêng báo cáo OVERVIEW")
    void UTCID05() throws Exception {
        LocalDate start = LocalDate.of(2026, 11, 1);
                LocalDate end = LocalDate.of(2026, 11, 30);
                List<Long> branchIds = List.of(1L);
        
                // Mock dữ liệu tổng quan
                List<Object[]> emptyBreakdown = Collections.singletonList(new Object[]{
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000000"), BigDecimal.ZERO
                });
                lenient().when(invoiceRepository.sumInvoiceBreakdownByBranches(any(), any(), any())).thenReturn(emptyBreakdown);
                lenient().when(paymentRepository.sumRealRevenueByBranches(any(), any(), any())).thenReturn(new BigDecimal("5000000"));
                lenient().when(paymentRepository.sumAdvanceDepositByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(paymentRepository.sumDepositWalletUsedByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(paymentRepository.findDailyRealPaymentsForPeriod(any(), any(), any())).thenReturn(new ArrayList<>());
                lenient().when(refundRepository.sumRefundAmountByBranches(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(operatingExpenseRepository.sumActiveExpenses(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                lenient().when(visitRepository.countVisitsByStatusAndBranches(any(), any(), any())).thenReturn(new ArrayList<>());
        
                byte[] bytes=reportService.exportReportToExcel("OVERVIEW",branchIds,start,end,"Cơ sở 1");assertNotNull(bytes);try(Workbook workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))){assertTrue(workbook.getNumberOfSheets()>0);assertTrue(workbook.getSheetAt(0).getLastRowNum()>2);assertNotNull(workbook.getSheetAt(0).getRow(0));}
    }
}
