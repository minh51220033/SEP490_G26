package com.cvdcms.service.impl;

import com.cvdcms.DTO.CashierPendingPatientDTO;
import com.cvdcms.DTO.PendingVisitSummaryDTO;
import com.cvdcms.DTO.PrescriptionItemResponseDTO;
import com.cvdcms.DTO.PrescriptionResponseDTO;
import com.cvdcms.DTO.UnpaidInvoiceSummaryDTO;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.PerformedService;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.InvoiceStatus;
import com.cvdcms.entity.enums.PrescriptionStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.InvoiceRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.PrescriptionRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.CashierOverviewService;
import com.cvdcms.service.InvoiceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * [Phân hệ]: Tổng quan Thu ngân & Hàng đợi Lập hóa đơn / Thu tiền
 * [Vai trò]: Service tổng hợp danh sách bệnh nhân đang chờ lập hóa đơn (dịch vụ hoàn tất hoặc đơn thuốc chưa thanh toán) và/hoặc còn hóa đơn nợ theo đúng phạm vi chi nhánh phân quyền.
 */
@Service
@Transactional(readOnly = false)
public class CashierOverviewServiceImpl implements CashierOverviewService {

    private final PatientProfileRepository patientProfileRepository;
    private final InvoiceRepository invoiceRepository;
    private final VisitRepository visitRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final InvoiceService invoiceService;

    /**
     * [Hàm hỗ trợ]: Khởi tạo service tổng quan thu ngân với các repository bệnh nhân, hóa đơn, lượt khám, đơn thuốc và `InvoiceService`.
     */
    public CashierOverviewServiceImpl(
            PatientProfileRepository patientProfileRepository,
            InvoiceRepository invoiceRepository,
            VisitRepository visitRepository,
            PrescriptionRepository prescriptionRepository,
            InvoiceService invoiceService
    ) {
        this.patientProfileRepository = patientProfileRepository;
        this.invoiceRepository = invoiceRepository;
        this.visitRepository = visitRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.invoiceService = invoiceService;
    }

    /**
     * [Chức năng]: Truy vấn phân trang danh sách bệnh nhân cần xử lý tại quầy thu ngân (chờ lập hóa đơn và/hoặc còn công nợ).
     * [Mục đích]: Phục vụ bảng hàng đợi trên màn hình Tổng quan Thu ngân; hỗ trợ tìm theo tên/SĐT/mã BN/mã hóa đơn/mã lượt khám, giới hạn chặt theo `allowedBranchIds`, lọc theo trạng thái (`PENDING_INVOICE`, `PENDING_PAYMENT`, `PENDING_BOTH`) và khoảng ngày phát sinh, sau đó sắp xếp theo thời điểm hoạt động gần nhất giảm dần.
     */
    @Override
    public Page<CashierPendingPatientDTO> getPendingCashierPatients(
            String keyword,
            Long branchId,
            java.time.LocalDate fromDate,
            java.time.LocalDate toDate,
            String status,
            List<Long> allowedBranchIds,
            Pageable pageable
    ) {
        List<Long> effectiveBranchIds = allowedBranchIds;
        if (branchId != null) {
            if (allowedBranchIds != null) {
                if (!allowedBranchIds.contains(branchId)) {
                    return Page.empty(pageable);
                }
                effectiveBranchIds = List.of(branchId);
            } else {
                effectiveBranchIds = List.of(branchId);
            }
        } else if (allowedBranchIds != null && allowedBranchIds.isEmpty()) {
            return Page.empty(pageable);
        }

        boolean hasKeyword = (keyword != null && !keyword.trim().isEmpty());
        List<PatientProfile> candidatePatients;

        if (hasKeyword) {
            String kw = keyword.trim();
            Set<PatientProfile> matched = new LinkedHashSet<>(patientProfileRepository.searchPatients(kw));

            // Hỗ trợ tìm kiếm nhanh theo mã hóa đơn nếu người dùng chỉ nhớ mã HĐ
            try {
                invoiceRepository.findByInvoiceCode(kw).ifPresent(inv -> {
                    if (inv.getPatientProfile() != null) matched.add(inv.getPatientProfile());
                });
            } catch (Exception ignored) {
            }

            // Hỗ trợ tìm kiếm theo mã lượt khám nếu người dùng chỉ nhớ mã ca khám
            try {
                visitRepository.findByVisitCode(kw).ifPresent(v -> {
                    if (v.getPatientProfile() != null) matched.add(v.getPatientProfile());
                });
            } catch (Exception ignored) {
            }

            try {
                List<Visit> matchedVisits = visitRepository.findFilteredVisits(null, null, null, branchId, kw);
                if (matchedVisits != null) {
                    for (Visit mv : matchedVisits) {
                        if (mv.getPatientProfile() != null) {
                            matched.add(mv.getPatientProfile());
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            if (matched.isEmpty()) {
                return Page.empty(pageable);
            }
            if (effectiveBranchIds != null) {
                final List<Long> bIds = effectiveBranchIds;
                // Lọc bệnh nhân liên quan đến các chi nhánh được phân quyền / lựa chọn
                candidatePatients = matched.stream().filter(p -> {
                    List<Invoice> invs = invoiceRepository.findInvoicesByPatientAndBranches(p.getProfileId(), bIds);
                    if (!invs.isEmpty()) return true;
                    List<Visit> visits = visitRepository.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(p.getProfileId());
                    return visits.stream().anyMatch(v -> v.getBranch() != null && bIds.contains(v.getBranch().getBranchId()));
                }).toList();
            } else {
                candidatePatients = new ArrayList<>(matched);
            }
        } else {
            // Danh sách mặc định: chỉ lấy bệnh nhân có khả năng thỏa điều kiện A hoặc B
            Set<Long> candidateProfileIds = new LinkedHashSet<>();
            List<Long> debtIds = invoiceRepository.findPatientProfileIdsWithDebtInBranches(effectiveBranchIds);
            if (debtIds != null) candidateProfileIds.addAll(debtIds);

            List<Long> waitingVisitIds = visitRepository.findPatientProfileIdsByStatusAndBranches(VisitStatus.WAITING_PAYMENT, effectiveBranchIds);
            if (waitingVisitIds != null) candidateProfileIds.addAll(waitingVisitIds);

            List<Long> pendingPrescIds = prescriptionRepository.findPatientProfileIdsByStatusNotInAndBranches(
                    List.of(PrescriptionStatus.CANCELLED, PrescriptionStatus.EXTERNAL_PURCHASE, PrescriptionStatus.CLINIC_PURCHASE_COMPLETED),
                    effectiveBranchIds
            );
            if (pendingPrescIds != null) candidateProfileIds.addAll(pendingPrescIds);

            if (candidateProfileIds.isEmpty()) {
                return Page.empty(pageable);
            }
            candidatePatients = patientProfileRepository.findAllById(candidateProfileIds);
        }

        List<CashierPendingPatientDTO> resultList = new ArrayList<>();

        for (PatientProfile patient : candidatePatients) {
            CashierPendingPatientDTO dto = buildPatientDTO(patient, effectiveBranchIds);

            if (!hasKeyword) {
                // Không có từ khóa: chỉ giữ bệnh nhân thỏa A (chờ lập) HOẶC B (còn nợ)
                if (!dto.isHasUnbilledItems() && !dto.isHasUnpaidInvoices()) {
                    continue;
                }
            }

            // Lọc theo trạng thái xử lý
            if (!matchesStatus(dto, status)) {
                continue;
            }

            // Lọc theo khoảng thời gian phát sinh/hoạt động
            if (!matchesDateRange(dto, fromDate, toDate)) {
                continue;
            }

            resultList.add(dto);
        }

        // Sắp xếp: Thời điểm phát sinh việc chờ xử lý gần nhất giảm dần, profileId giảm dần làm khóa phụ
        resultList.sort(Comparator.comparing(CashierPendingPatientDTO::getLatestActivityTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CashierPendingPatientDTO::getProfileId, Comparator.nullsLast(Comparator.reverseOrder())));

        int total = resultList.size();
        int start = (int) pageable.getOffset();
        if (start >= total && total > 0) {
            int lastPage = (total - 1) / pageable.getPageSize();
            start = lastPage * pageable.getPageSize();
        }
        int end = Math.min(start + pageable.getPageSize(), total);
        List<CashierPendingPatientDTO> pageContent = (start < total) ? resultList.subList(start, end) : Collections.emptyList();

        return new PageImpl<>(pageContent, pageable, total);
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra hồ sơ bệnh nhân chờ thu ngân có khớp với bộ lọc trạng thái (`ALL`, `PENDING_INVOICE`, `PENDING_PAYMENT`, `PENDING_BOTH`) hay không.
     */
    private boolean matchesStatus(CashierPendingPatientDTO dto, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) {
            return true;
        }
        String s = status.trim().toUpperCase();
        if ("PENDING_INVOICE".equals(s)) {
            return dto.isHasUnbilledItems();
        } else if ("PENDING_PAYMENT".equals(s)) {
            return dto.isHasUnpaidInvoices();
        } else if ("PENDING_BOTH".equals(s)) {
            return dto.isHasUnbilledItems() && dto.isHasUnpaidInvoices();
        }
        return true;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra bệnh nhân có ít nhất một lượt khám chờ lập hóa đơn, hóa đơn còn nợ hoặc hoạt động gần nhất nằm trong khoảng `[fromDate, toDate]`.
     */
    private boolean matchesDateRange(CashierPendingPatientDTO dto, java.time.LocalDate fromDate, java.time.LocalDate toDate) {
        if (fromDate == null && toDate == null) {
            return true;
        }
        if (dto.getPendingVisits() != null) {
            for (PendingVisitSummaryDTO pv : dto.getPendingVisits()) {
                if (pv.getCheckInAt() != null && isDateInRange(pv.getCheckInAt().toLocalDate(), fromDate, toDate)) {
                    return true;
                }
            }
        }
        if (dto.getUnpaidInvoices() != null) {
            for (UnpaidInvoiceSummaryDTO inv : dto.getUnpaidInvoices()) {
                if (inv.getIssuedAt() != null && isDateInRange(inv.getIssuedAt().toLocalDate(), fromDate, toDate)) {
                    return true;
                }
            }
        }
        if (dto.getLatestActivityTime() != null && isDateInRange(dto.getLatestActivityTime().toLocalDate(), fromDate, toDate)) {
            return true;
        }
        return false;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra một ngày mục tiêu `target` có nằm trong đoạn đóng `[fromDate, toDate]` hay không.
     */
    private boolean isDateInRange(java.time.LocalDate target, java.time.LocalDate fromDate, java.time.LocalDate toDate) {
        if (target == null) return false;
        if (fromDate != null && target.isBefore(fromDate)) return false;
        if (toDate != null && target.isAfter(toDate)) return false;
        return true;
    }

    /**
     * [Chức năng]: Lấy chi tiết các khoản chờ lập hóa đơn và hóa đơn còn nợ của một bệnh nhân cụ thể trong phạm vi chi nhánh được phân quyền.
     * [Mục đích]: Phục vụ màn hình chi tiết công nợ `/payments/debts/{profileId}`; trả `Optional.empty()` nếu nhân sự không có chi nhánh được phép hoặc bệnh nhân không còn khoản chờ xử lý nào.
     */
    @Override
    public Optional<CashierPendingPatientDTO> findDebtDetail(Long profileId, List<Long> allowedBranchIds) {
        if (allowedBranchIds != null && allowedBranchIds.isEmpty()) {
            return Optional.empty();
        }
        return patientProfileRepository.findById(profileId)
                .map(patient -> buildPatientDTO(patient, allowedBranchIds))
                .filter(detail -> !detail.getUnpaidInvoices().isEmpty() || !detail.getPendingVisits().isEmpty());
    }

    /**
     * [Hàm hỗ trợ]: Tổng hợp đầy đủ trạng thái thu ngân của một bệnh nhân gồm tổng hóa đơn/đã thu/còn nợ/ví tạm ứng, danh sách hóa đơn chưa thanh toán đủ, danh sách lượt khám `WAITING_PAYMENT` có dịch vụ/đơn thuốc chưa lập hóa đơn (kể cả đơn thuốc lẻ tại phòng khám) và phân loại trạng thái xử lý.
     */
    private CashierPendingPatientDTO buildPatientDTO(PatientProfile patient, List<Long> allowedBranchIds) {
        CashierPendingPatientDTO dto = new CashierPendingPatientDTO();
        dto.setProfileId(patient.getProfileId());
        dto.setPatientCode(patient.getPatientCode());
        dto.setFullName(patient.getFullName());
        dto.setPhoneNumber(patient.getPhoneNumber());
        dto.setDepositBalance(patient.getDepositBalance() != null ? patient.getDepositBalance() : BigDecimal.ZERO);

        // 1. Tính toán Hóa đơn & Công nợ (Điều kiện B)
        List<Invoice> activeInvoices;
        if (allowedBranchIds != null) {
            activeInvoices = invoiceRepository.findInvoicesByPatientAndBranches(patient.getProfileId(), allowedBranchIds);
        } else {
            activeInvoices = invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(patient.getProfileId(), InvoiceStatus.CANCELLED);
        }

        BigDecimal totalBilled = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalDebt = BigDecimal.ZERO;
        List<UnpaidInvoiceSummaryDTO> unpaidList = new ArrayList<>();
        LocalDateTime latestInvoiceTime = null;

        for (Invoice inv : activeInvoices) {
            if (inv.getStatus() == InvoiceStatus.CANCELLED) continue;
            if (inv.getTotalAmount() != null) totalBilled = totalBilled.add(inv.getTotalAmount());
            if (inv.getPaidAmount() != null) totalPaid = totalPaid.add(inv.getPaidAmount());
            BigDecimal debt = inv.getRemainingDebt() != null ? inv.getRemainingDebt() : BigDecimal.ZERO;
            if (debt.compareTo(BigDecimal.ZERO) > 0) {
                totalDebt = totalDebt.add(debt);
                UnpaidInvoiceSummaryDTO u = UnpaidInvoiceSummaryDTO.builder()
                        .invoiceId(inv.getInvoiceId())
                        .invoiceCode(inv.getInvoiceCode())
                        .issuedAt(inv.getIssuedAt())
                        .branchId(inv.getBranch() != null ? inv.getBranch().getBranchId() : null)
                        .branchName(inv.getBranch() != null ? inv.getBranch().getBranchName() : "")
                        .totalAmount(inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO)
                        .paidAmount(inv.getPaidAmount() != null ? inv.getPaidAmount() : BigDecimal.ZERO)
                        .remainingDebt(debt)
                        .build();
                unpaidList.add(u);
            }
            if (inv.getIssuedAt() != null && (latestInvoiceTime == null || inv.getIssuedAt().isAfter(latestInvoiceTime))) {
                latestInvoiceTime = inv.getIssuedAt();
            }
        }

        dto.setTotalBilled(totalBilled);
        dto.setTotalPaid(totalPaid);
        dto.setRemainingDebt(totalDebt);
        dto.setUnpaidInvoiceCount(unpaidList.size());
        dto.setUnpaidInvoices(unpaidList);
        dto.setHasUnpaidInvoices(!unpaidList.isEmpty());

        // 2. Tính toán Ca khám & Khoản chờ lập hóa đơn (Điều kiện A)
        List<Visit> waitingVisits = visitRepository.findWaitingVisitsByProfileIdAndBranches(
                patient.getProfileId(),
                VisitStatus.WAITING_PAYMENT,
                allowedBranchIds
        );

        List<PendingVisitSummaryDTO> pendingVisitList = new ArrayList<>();
        LocalDateTime latestVisitTime = null;
        Set<Long> countedPrescriptionIds = new HashSet<>();

        for (Visit v : waitingVisits) {
            List<PerformedService> unbilledServices = invoiceService.getUnbilledPerformedServices(v.getVisitId());
            List<PrescriptionResponseDTO> prescriptions = invoiceService.getHandedOverPrescriptions(
                    v.getBranch() != null ? v.getBranch().getBranchId() : null,
                    v.getVisitId(),
                    patient.getProfileId()
            );

            int unbilledPrescCount = 0;
            if (prescriptions != null) {
                for (PrescriptionResponseDTO p : prescriptions) {
                    if (isPrescriptionEligibleForBilling(p)) {
                        unbilledPrescCount++;
                        if (p.getPrescriptionId() != null) {
                            countedPrescriptionIds.add(p.getPrescriptionId());
                        }
                    }
                }
            }

            if (!unbilledServices.isEmpty() || unbilledPrescCount > 0) {
                PendingVisitSummaryDTO pvd = PendingVisitSummaryDTO.builder()
                        .visitId(v.getVisitId())
                        .visitCode(v.getVisitCode())
                        .checkInAt(v.getCheckInAt())
                        .branchId(v.getBranch() != null ? v.getBranch().getBranchId() : null)
                        .branchName(v.getBranch() != null ? v.getBranch().getBranchName() : "")
                        .doctorName(v.getPrimaryDoctor() != null ? v.getPrimaryDoctor().getFullName() : null)
                        .unbilledServiceCount(unbilledServices.size())
                        .unbilledPrescriptionCount(unbilledPrescCount)
                        .build();
                pendingVisitList.add(pvd);
            }

            if (v.getCheckInAt() != null && (latestVisitTime == null || v.getCheckInAt().isAfter(latestVisitTime))) {
                latestVisitTime = v.getCheckInAt();
            }
        }

        // Bổ sung đơn thuốc chờ mua tại phòng khám không gắn với ca khám WAITING_PAYMENT
        List<PrescriptionResponseDTO> allPatientPrescriptions = invoiceService.getHandedOverPrescriptions(null, null, patient.getProfileId());
        int standalonePrescCount = 0;
        if (allPatientPrescriptions != null) {
            for (PrescriptionResponseDTO p : allPatientPrescriptions) {
                if (p.getPrescriptionId() != null && countedPrescriptionIds.contains(p.getPrescriptionId())) {
                    continue;
                }
                if (p.getPrescriptionId() != null) {
                    // Kiểm tra cơ sở của đơn thuốc, nếu không thuộc allowedBranchIds thì bỏ qua để không đếm nhầm đơn cơ sở khác
                    com.cvdcms.entity.Prescription pres = prescriptionRepository.findById(p.getPrescriptionId()).orElse(null);
                    if (pres != null) {
                        if (allowedBranchIds != null && pres.getBranch() != null && !allowedBranchIds.contains(pres.getBranch().getBranchId())) {
                            continue;
                        }
                        if (pres.getPurchaseChoice() == com.cvdcms.entity.enums.PrescriptionPurchaseChoice.EXTERNAL
                                || pres.getStatus() == PrescriptionStatus.EXTERNAL_PURCHASE
                                || pres.getStatus() == PrescriptionStatus.CLINIC_PURCHASE_COMPLETED
                                || pres.getStatus() == PrescriptionStatus.CANCELLED) {
                            continue;
                        }
                    }
                }
                if (isPrescriptionEligibleForBilling(p)) {
                    standalonePrescCount++;
                }
            }
        }

        int totalUnbilledCases = pendingVisitList.size() + (standalonePrescCount > 0 ? 1 : 0);
        dto.setUnbilledVisitCount(totalUnbilledCases);
        dto.setPendingVisits(pendingVisitList);
        dto.setHasUnbilledItems(totalUnbilledCases > 0);

        // 3. Xác định Trạng thái xử lý
        if (dto.isHasUnbilledItems() && dto.isHasUnpaidInvoices()) {
            dto.setProcessingStatus("PENDING_BOTH");
            dto.setStatusDisplay("Chờ lập HĐ & Thu tiền");
        } else if (dto.isHasUnbilledItems()) {
            dto.setProcessingStatus("PENDING_INVOICE");
            dto.setStatusDisplay("Chờ lập hóa đơn");
        } else if (dto.isHasUnpaidInvoices()) {
            dto.setProcessingStatus("PENDING_PAYMENT");
            dto.setStatusDisplay("Chờ thu tiền");
        } else if (dto.getTotalBilled().compareTo(BigDecimal.ZERO) > 0) {
            dto.setProcessingStatus("SETTLED");
            dto.setStatusDisplay("Không có khoản chờ xử lý");
        } else {
            dto.setProcessingStatus("NO_INVOICE");
            dto.setStatusDisplay("Chưa có hóa đơn");
        }

        // 4. Cơ sở liên quan
        Set<String> branchSet = new LinkedHashSet<>();
        for (PendingVisitSummaryDTO v : pendingVisitList) {
            if (v.getBranchName() != null && !v.getBranchName().isBlank()) {
                branchSet.add(v.getBranchName());
            }
        }
        for (Invoice inv : activeInvoices) {
            if (inv.getBranch() != null && inv.getBranch().getBranchName() != null) {
                branchSet.add(inv.getBranch().getBranchName());
            }
        }
        if (branchSet.isEmpty()) {
            dto.setBranchNames("Toàn hệ thống");
        } else {
            dto.setBranchNames(String.join(", ", branchSet));
        }

        // 5. Thời điểm phát sinh gần nhất
        LocalDateTime latestActivity = latestVisitTime;
        if (latestInvoiceTime != null && (latestActivity == null || latestInvoiceTime.isAfter(latestActivity))) {
            latestActivity = latestInvoiceTime;
        }
        if (latestActivity == null) {
            latestActivity = patient.getCreatedAt();
        }
        dto.setLatestActivityTime(latestActivity);

        return dto;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra đơn thuốc có đủ điều kiện lập hóa đơn tại phòng khám hay không (chưa hủy, không mua ngoài, chưa hoàn tất mua tại phòng khám và vẫn còn ít nhất một dòng thuốc có `remainingQuantity > 0`).
     */
    private boolean isPrescriptionEligibleForBilling(PrescriptionResponseDTO p) {
        if (p == null) return false;
        String status = p.getStatus();
        if ("EXTERNAL_PURCHASE".equals(status) || "CANCELLED".equals(status) || "CLINIC_PURCHASE_COMPLETED".equals(status)
                || "EXTERNAL".equalsIgnoreCase(p.getPurchaseChoice())) {
            return false;
        }
        if (p.getItems() == null || p.getItems().isEmpty()) {
            return false;
        }
        for (PrescriptionItemResponseDTO it : p.getItems()) {
            if (it.getRemainingQuantity() != null && it.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
                return true;
            }
        }
        return false;
    }
}

