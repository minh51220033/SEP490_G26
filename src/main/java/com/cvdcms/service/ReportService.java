package com.cvdcms.service;

import com.cvdcms.DTO.report.FinancialReportDTO;
import com.cvdcms.DTO.report.OperationalReportDTO;
import com.cvdcms.DTO.report.ReportDashboardDTO;

import java.time.LocalDate;
import java.util.Collection;

public interface ReportService {

    ReportDashboardDTO getDashboardReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName);

    FinancialReportDTO getFinancialReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate);

    OperationalReportDTO getOperationalReport(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate);

    byte[] exportReportToExcel(String reportType, Collection<Long> branchIds, LocalDate startDate, LocalDate endDate, String branchName);

    long countTodayAppointments(Collection<Long> branchIds, LocalDate date);

    long countWaitingPatients(Collection<Long> branchIds, LocalDate date);

    long countNewPatients(Collection<Long> branchIds, LocalDate startDate, LocalDate endDate);
}
