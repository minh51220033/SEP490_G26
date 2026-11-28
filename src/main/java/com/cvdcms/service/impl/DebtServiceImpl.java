package com.cvdcms.service.impl;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
import com.cvdcms.repository.InvoiceRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.service.DebtService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * [Phân hệ]: Quản lý Công nợ Bệnh nhân
 * [Vai trò]: Service đọc dữ liệu (read-only) tổng hợp danh sách công nợ, chi tiết tổng hóa đơn/đã thanh toán/còn nợ/ví tạm ứng và các hóa đơn chưa thanh toán đủ của bệnh nhân theo phạm vi chi nhánh phân quyền.
 */
@Service
@Transactional(readOnly = true)
public class DebtServiceImpl implements DebtService {

    private final InvoiceRepository invoiceRepository;
    private final PatientProfileRepository patientProfileRepository;

    /**
     * [Hàm hỗ trợ]: Khởi tạo service công nợ với repository hóa đơn và hồ sơ bệnh nhân.
     */
    public DebtServiceImpl(InvoiceRepository invoiceRepository, PatientProfileRepository patientProfileRepository) {
        this.invoiceRepository = invoiceRepository;
        this.patientProfileRepository = patientProfileRepository;
    }

    /**
     * [Chức năng]: Truy vấn phân trang danh sách công nợ bệnh nhân theo một chi nhánh và từ khóa (overload đơn giản).
     * [Mục đích]: Ủy quyền sang overload đầy đủ của `getPatientDebts`, giới hạn phạm vi chi nhánh theo `branchId` nếu có.
     */
    @Override
    public Page<PatientDebtSummaryDTO> getPatientDebts(Long branchId, String keyword, Pageable pageable) {
        return getPatientDebts(branchId, branchId != null ? List.of(branchId) : null, null, keyword, pageable);
    }

    /**
     * [Chức năng]: Truy vấn phân trang danh sách công nợ bệnh nhân theo chi nhánh, danh sách chi nhánh được phép, trạng thái thanh toán và từ khóa (không lọc khoảng ngày).
     * [Mục đích]: Ủy quyền sang hàm `getPatientDebts` đầy đủ với `startDate = null`, `endDate = null`.
     */
    @Override
    public Page<PatientDebtSummaryDTO> getPatientDebts(
            Long branchId,
            List<Long> allowedBranchIds,
            InvoicePaymentStatus paymentStatus,
            String keyword,
            Pageable pageable
    ) {
        return getPatientDebts(branchId, allowedBranchIds, paymentStatus, keyword, null, null, pageable);
    }

    /**
     * [Chức năng]: Truy vấn phân trang danh sách bệnh nhân còn công nợ kèm lọc chi nhánh, trạng thái thanh toán (`UNPAID`/`PARTIAL`), từ khóa và khoảng thời gian phát hành hóa đơn.
     * [Mục đích]: Áp dụng bảo vệ phân quyền chi nhánh nghiêm ngặt (trả trang rỗng nếu nhân sự không có chi nhánh phân công hoặc chọn `branchId` ngoài `allowedBranchIds`), chỉ Admin (`allowedBranchIds == null`) mới được truy vấn toàn hệ thống.
     */
    @Override
    public Page<PatientDebtSummaryDTO> getPatientDebts(
            Long branchId,
            List<Long> allowedBranchIds,
            InvoicePaymentStatus paymentStatus,
            String keyword,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    ) {
        // Nếu kế toán không có chi nhánh nào được phân công, từ chối và trả về rỗng ngay lập tức
        if (allowedBranchIds != null && allowedBranchIds.isEmpty()) {
            return Page.empty(pageable);
        }

        String kw = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;

        // Nếu người dùng chọn branchId cụ thể
        if (branchId != null) {
            // Kiểm tra phân quyền: nếu không phải admin và branchId không thuộc allowedBranchIds thì từ chối
            if (allowedBranchIds != null && !allowedBranchIds.contains(branchId)) {
                return Page.empty(pageable);
            }
            return invoiceRepository.findPatientDebtsByBranch(branchId, paymentStatus, kw, startDate, endDate, pageable);
        }

        // Nếu branchId == null:
        // Với non-admin: chỉ truy vấn trong các chi nhánh được phân công
        if (allowedBranchIds != null) {
            return invoiceRepository.findPatientDebtsByBranches(allowedBranchIds, paymentStatus, kw, startDate, endDate, pageable);
        }

        // Với Admin: truy vấn toàn hệ thống
        return invoiceRepository.findPatientDebtsByBranch(null, paymentStatus, kw, startDate, endDate, pageable);
    }

    /**
     * [Chức năng]: Lấy tóm tắt công nợ toàn hệ thống của một bệnh nhân theo `profileId`.
     * [Mục đích]: Overload không giới hạn chi nhánh (dành cho Admin hoặc truy vấn tổng hợp toàn chuỗi), ủy quyền cho `getPatientDebtDetail(profileId, null)`.
     */
    @Override
    public PatientDebtSummaryDTO getPatientDebtDetail(Long profileId) {
        return getPatientDebtDetail(profileId, (List<Long>) null);
    }

    /**
     * [Chức năng]: Tổng hợp chi tiết tình hình tài chính của một bệnh nhân (tổng phát sinh, tổng đã trả, tổng nợ còn lại, số dư ví tạm ứng, số hóa đơn còn nợ và ngày hóa đơn gần nhất) trong phạm vi `branchIds`.
     * [Mục đích]: Phục vụ Dashboard tài chính bệnh nhân; bảo vệ phân quyền bằng cách trả số liệu 0 nếu `branchIds` là danh sách rỗng, còn `branchIds == null` (Admin) sẽ cộng dồn trên toàn bộ các chi nhánh.
     */
    @Override
    public PatientDebtSummaryDTO getPatientDebtDetail(Long profileId, List<Long> branchIds) {
        PatientProfile patient = patientProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân ID: " + profileId));

        PatientDebtSummaryDTO dto = new PatientDebtSummaryDTO();
        dto.setProfileId(patient.getProfileId());
        dto.setPatientCode(patient.getPatientCode());
        dto.setFullName(patient.getFullName());
        dto.setPhoneNumber(patient.getPhoneNumber());
        dto.setDepositBalance(patient.getDepositBalance() != null ? patient.getDepositBalance() : BigDecimal.ZERO);

        // Bảo vệ quyền chi nhánh: nếu danh sách chi nhánh được phép là rỗng -> không trả dữ liệu nợ
        if (branchIds != null && branchIds.isEmpty()) {
            dto.setTotalBilled(BigDecimal.ZERO);
            dto.setTotalPaid(BigDecimal.ZERO);
            dto.setTotalDebt(BigDecimal.ZERO);
            dto.setUnpaidInvoicesCount(0);
            dto.setLastInvoiceDate(null);
            return dto;
        }

        BigDecimal totalBilled;
        BigDecimal totalPaid;
        BigDecimal totalDebt;
        List<Invoice> unpaidInvoices;
        List<Invoice> validInvoices;

        if (branchIds != null) {
            totalBilled = invoiceRepository.sumTotalInvoicedByPatientAndBranches(profileId, branchIds);
            totalPaid = invoiceRepository.sumTotalPaidByPatientAndBranches(profileId, branchIds);
            totalDebt = invoiceRepository.sumTotalDebtByPatientAndBranches(profileId, branchIds);
            unpaidInvoices = invoiceRepository.findUnpaidInvoicesByPatientAndBranches(profileId, branchIds);
            validInvoices = invoiceRepository.findInvoicesByPatientAndBranches(profileId, branchIds);
        } else {
            totalBilled = invoiceRepository.sumTotalInvoicedByPatient(profileId);
            totalPaid = invoiceRepository.sumTotalPaidByPatient(profileId);
            totalDebt = invoiceRepository.sumTotalDebtByPatient(profileId);
            unpaidInvoices = invoiceRepository.findByPatientProfile_ProfileIdAndPaymentStatusIn(
                    profileId,
                    List.of(InvoicePaymentStatus.UNPAID, InvoicePaymentStatus.PARTIAL)
            );
            validInvoices = invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(profileId, InvoiceStatus.CANCELLED);
        }

        dto.setTotalBilled(totalBilled != null ? totalBilled : BigDecimal.ZERO);
        dto.setTotalPaid(totalPaid != null ? totalPaid : BigDecimal.ZERO);
        dto.setTotalDebt(totalDebt != null ? totalDebt : BigDecimal.ZERO);
        dto.setUnpaidInvoicesCount(unpaidInvoices != null ? unpaidInvoices.size() : 0);

        if (validInvoices != null && !validInvoices.isEmpty()) {
            dto.setLastInvoiceDate(validInvoices.get(0).getIssuedAt());
        }

        return dto;
    }

    /**
     * [Chức năng]: Lấy toàn bộ danh sách hóa đơn chưa thanh toán đủ (`UNPAID`, `PARTIAL`) của một bệnh nhân trên tất cả chi nhánh.
     * [Mục đích]: Overload tiện ích ủy quyền cho `getUnpaidInvoicesForPatient(profileId, null)`.
     */
    @Override
    public List<Invoice> getUnpaidInvoicesForPatient(Long profileId) {
        return getUnpaidInvoicesForPatient(profileId, (List<Long>) null);
    }

    /**
     * [Chức năng]: Lấy danh sách hóa đơn còn nợ (`UNPAID`, `PARTIAL`) của một bệnh nhân trong giới hạn `branchIds`.
     * [Mục đích]: Cung cấp danh sách hóa đơn cần thu nợ cho API và modal thu tiền, đảm bảo nhân sự chi nhánh chỉ thao tác được trên các hóa đơn thuộc chi nhánh được phân quyền.
     */
    @Override
    public List<Invoice> getUnpaidInvoicesForPatient(Long profileId, List<Long> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return Collections.emptyList();
        }
        if (branchIds != null) {
            return invoiceRepository.findUnpaidInvoicesByPatientAndBranches(profileId, branchIds);
        }
        return invoiceRepository.findByPatientProfile_ProfileIdAndPaymentStatusIn(
                profileId,
                List.of(InvoicePaymentStatus.UNPAID, InvoicePaymentStatus.PARTIAL)
        );
    }
}
