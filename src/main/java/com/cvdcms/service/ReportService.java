package com.cvdcms.service;

import com.cvdcms.DTO.report.FinancialReportDTO;
import com.cvdcms.DTO.report.OperationalReportDTO;
import com.cvdcms.DTO.report.ReportDashboardDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;

public interface ReportService {

    ReportDashboardDTO getDashboardReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName);

    ReportDashboardDTO getDashboardReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName, BigDecimal taxRate);

    FinancialReportDTO getFinancialReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate);

    FinancialReportDTO getFinancialReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, BigDecimal taxRate);

    OperationalReportDTO getOperationalReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate);

    byte[] exportReportToExcel(String reportType, Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName);

    byte[] exportReportToExcel(String reportType, Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName, BigDecimal taxRate);
}
