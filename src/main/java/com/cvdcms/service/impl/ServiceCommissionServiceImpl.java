package com.cvdcms.service.impl;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.CommissionRoleType;
import com.cvdcms.entity.enums.CommissionStatus;
import com.cvdcms.entity.enums.CommissionType;
import com.cvdcms.entity.enums.PayrollStatus;
import com.cvdcms.entity.enums.PerformedServiceStatus;
import com.cvdcms.repository.CommissionPolicyRepository;
import com.cvdcms.repository.InvoiceItemRepository;
import com.cvdcms.repository.PayrollItemRepository;
import com.cvdcms.repository.PayrollPeriodRepository;
import com.cvdcms.repository.ServiceCommissionRepository;
import com.cvdcms.service.PayrollService;
import com.cvdcms.service.ServiceCommissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * [Phân hệ]: Tài chính - Tính toán và đồng bộ hoa hồng dịch vụ
 * [Vai trò]: Triển khai nghiệp vụ tính hoa hồng bác sĩ/trợ thủ theo chính sách ưu tiên, hủy hoặc tính lại hoa hồng khi dịch vụ thay đổi và đồng bộ lại dòng bảng lương nháp.
 */
@Service
@Transactional
public class ServiceCommissionServiceImpl implements ServiceCommissionService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final CommissionPolicyRepository commissionPolicyRepository;
    private final ServiceCommissionRepository serviceCommissionRepository;
    private final InvoiceItemRepository invoiceItemRepository;

    @Autowired(required = false)
    private PayrollPeriodRepository payrollPeriodRepository;

    @Autowired(required = false)
    private PayrollItemRepository payrollItemRepository;

    @Lazy
    @Autowired(required = false)
    private PayrollService payrollService;

    /**
     * [Hàm hỗ trợ]: Gán PayrollPeriodRepository phục vụ tiêm phụ thuộc tùy chọn và kiểm thử đơn vị.
     */
    public void setPayrollPeriodRepository(PayrollPeriodRepository payrollPeriodRepository) {
        this.payrollPeriodRepository = payrollPeriodRepository;
    }

    /**
     * [Hàm hỗ trợ]: Gán PayrollItemRepository phục vụ tiêm phụ thuộc tùy chọn và kiểm thử đơn vị.
     */
    public void setPayrollItemRepository(PayrollItemRepository payrollItemRepository) {
        this.payrollItemRepository = payrollItemRepository;
    }

    /**
     * [Hàm hỗ trợ]: Gán PayrollService phục vụ tiêm phụ thuộc tùy chọn và gọi tính lại dòng lương nháp.
     */
    public void setPayrollService(PayrollService payrollService) {
        this.payrollService = payrollService;
    }

    @Autowired
    public ServiceCommissionServiceImpl(
            CommissionPolicyRepository commissionPolicyRepository,
            ServiceCommissionRepository serviceCommissionRepository,
            @Autowired(required = false) InvoiceItemRepository invoiceItemRepository
    ) {
        this.commissionPolicyRepository = commissionPolicyRepository;
        this.serviceCommissionRepository = serviceCommissionRepository;
        this.invoiceItemRepository = invoiceItemRepository;
    }

    /**
     * [Chức năng]: Tính và tạo bản ghi hoa hồng PENDING cho bác sĩ chính và trợ thủ của một dịch vụ đã hoàn thành (COMPLETED).
     * [Mục đích]: Được gọi khi hoàn tất dịch vụ lâm sàng hoặc chốt hóa đơn để tự động ghi nhận hoa hồng vào bảng service_commissions.
     */
    @Override
    public void calculateCommissionsForPerformedService(PerformedService ps) {
        calculateAndReturnCommissions(ps);
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra dịch vụ ở trạng thái COMPLETED, tính doanh thu cơ sở và tạo hoa hồng cho bác sĩ chính và trợ thủ (nếu khác bác sĩ chính), trả về danh sách bản ghi vừa tạo.
     */
    private List<ServiceCommission> calculateAndReturnCommissions(PerformedService ps) {
        if (ps == null || ps.getPerformedId() == null || ps.getService() == null) {
            return List.of();
        }
        if (ps.getStatus() != PerformedServiceStatus.COMPLETED) {
            return List.of();
        }

        BigDecimal revenueBase = resolveRevenueBase(ps);
        List<ServiceCommission> created = new ArrayList<>();

        StaffProfile doctor = ps.getDoctor();
        if (doctor != null && doctor.getStaffId() != null) {
            ServiceCommission docSc = createCommissionIfApplicable(ps, doctor, CommissionRoleType.MAIN_DOCTOR, revenueBase);
            if (docSc != null) {
                created.add(docSc);
            }
        }

        StaffProfile assistant = ps.getAssistant();
        if (assistant != null && assistant.getStaffId() != null
                && (doctor == null || !assistant.getStaffId().equals(doctor.getStaffId()))) {
            ServiceCommission astSc = createCommissionIfApplicable(ps, assistant, CommissionRoleType.ASSISTANT, revenueBase);
            if (astSc != null) {
                created.add(astSc);
            }
        }
        return created;
    }

    private record DraftBindingContext(
            Set<Long> affectedDraftItemIds,
            Map<Long, PayrollItem> preservedItemsByStaffId,
            Map<Long, PayrollPeriod> lockedDraftPeriodsById
    ) {}

    /**
     * [Chức năng]: Hủy các bản ghi hoa hồng chưa chi trả gắn với một dịch vụ thực hiện và cập nhật lại các dòng lương nháp (DRAFT) bị ảnh hưởng.
     * [Mục đích]: Đảm bảo khi hủy/điều chỉnh dịch vụ lâm sàng, hoa hồng chưa chốt được gỡ bỏ an toàn và tổng lương/tạm ứng của dòng lương nháp được tính toán lại qua PayrollService.
     */
    @Override
    public void cancelPendingCommissionsForPerformedService(Long performedId) {
        DraftBindingContext ctx = cancelAndCaptureDraftBindings(performedId);
        for (Long itemId : ctx.affectedDraftItemIds()) {
            payrollService.recalculateDraftItemAfterCommissionChange(itemId);
        }
    }

    /**
     * [Hàm hỗ trợ]: Khóa theo thứ tự toàn cục (PayrollPeriod -> ServiceCommission), chặn sửa/hủy nếu hoa hồng đã PAID hoặc gắn kỳ SUBMITTED/APPROVED/PAID, xóa hoa hồng PENDING và lưu ngữ cảnh dòng lương DRAFT cần tính lại.
     */
    private DraftBindingContext cancelAndCaptureDraftBindings(Long performedId) {
        if (performedId == null || serviceCommissionRepository == null) {
            return new DraftBindingContext(Set.of(), Map.of(), Map.of());
        }
        List<ServiceCommission> initial = serviceCommissionRepository.findByPerformedService_PerformedId(performedId);
        if (initial == null || initial.isEmpty()) {
            return new DraftBindingContext(Set.of(), Map.of(), Map.of());
        }

        // Tuân thủ thứ tự lock toàn cục: PayrollPeriod -> ServiceCommission
        Map<Long, PayrollPeriod> lockedPeriods = new LinkedHashMap<>();
        if (payrollPeriodRepository != null) {
            initial.stream()
                    .map(ServiceCommission::getPayrollItem)
                    .filter(Objects::nonNull)
                    .map(PayrollItem::getPayrollPeriod)
                    .filter(Objects::nonNull)
                    .map(PayrollPeriod::getPeriodId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .forEach(pid -> payrollPeriodRepository.findByIdForUpdate(pid)
                            .ifPresent(p -> lockedPeriods.put(pid, p)));
        }

        List<ServiceCommission> locked = serviceCommissionRepository.findByPerformedService_PerformedIdForUpdate(performedId);
        List<ServiceCommission> existing = (locked != null && !locked.isEmpty())
                ? locked
                : serviceCommissionRepository.findByPerformedService_PerformedId(performedId);
        if (existing == null || existing.isEmpty()) {
            return new DraftBindingContext(Set.of(), Map.of(), Map.of());
        }

        Set<Long> draftItemIdsToRecalc = new LinkedHashSet<>();
        Map<Long, PayrollItem> preservedItemsByStaffId = new LinkedHashMap<>();
        Map<Long, PayrollPeriod> lockedDraftPeriods = new LinkedHashMap<>();
        boolean deletedAny = false;

        for (ServiceCommission sc : existing) {
            if (sc.getStatus() == CommissionStatus.PAID) {
                throw new IllegalStateException("Hoa hồng ID " + sc.getCommissionId()
                        + " đã được chi trả (PAID). Không thể hủy hoặc sửa dịch vụ đã tất toán lương.");
            }
            if (sc.getPayrollItem() != null) {
                PayrollItem pItem = sc.getPayrollItem();
                PayrollPeriod period = pItem.getPayrollPeriod();
                if (period != null && period.getPeriodId() != null && lockedPeriods.containsKey(period.getPeriodId())) {
                    period = lockedPeriods.get(period.getPeriodId());
                }
                if (period == null || period.getStatus() == PayrollStatus.PAID) {
                    throw new IllegalStateException("Hoa hồng ID " + sc.getCommissionId()
                            + " đã gắn vào kỳ lương chi trả (PAID), không thể sửa hoặc hủy dịch vụ.");
                }
                if (period.getStatus() == PayrollStatus.SUBMITTED || period.getStatus() == PayrollStatus.APPROVED) {
                    throw new IllegalStateException("Hoa hồng đang gắn vào kỳ lương ở trạng thái " + period.getStatus()
                            + ". Vui lòng trả bảng lương về Nháp (DRAFT) trước khi sửa hoặc hủy dịch vụ.");
                }
                if (period.getStatus() != PayrollStatus.DRAFT) {
                    throw new IllegalStateException("Trạng thái kỳ lương không hợp lệ để sửa hoa hồng: " + period.getStatus());
                }
                if (payrollService == null) {
                    throw new IllegalStateException("Không thể tính lại dòng lương Nháp vì thiếu PayrollService.");
                }
                if (pItem.getItemId() != null) {
                    draftItemIdsToRecalc.add(pItem.getItemId());
                    if (sc.getStaff() != null && sc.getStaff().getStaffId() != null) {
                        preservedItemsByStaffId.putIfAbsent(sc.getStaff().getStaffId(), pItem);
                    }
                    if (period.getPeriodId() != null) {
                        lockedDraftPeriods.putIfAbsent(period.getPeriodId(), period);
                    }
                }
                sc.setPayrollItem(null);
                serviceCommissionRepository.delete(sc);
                deletedAny = true;
            } else {
                if (sc.getStatus() != CommissionStatus.PENDING) {
                    throw new IllegalStateException("Hoa hồng ID " + sc.getCommissionId()
                            + " đang ở trạng thái " + sc.getStatus() + ", không thể tự động hủy.");
                }
                serviceCommissionRepository.delete(sc);
                deletedAny = true;
            }
        }

        if (deletedAny) {
            serviceCommissionRepository.flush();
        }
        return new DraftBindingContext(draftItemIdsToRecalc, preservedItemsByStaffId, lockedDraftPeriods);
    }

    /**
     * [Chức năng]: Tính lại toàn bộ hoa hồng của một dịch vụ thực hiện sau khi sửa bác sĩ/trợ thủ/giá và gắn lại vào kỳ lương nháp (DRAFT) tương ứng.
     * [Mục đích]: Hủy hoa hồng cũ kèm lưu ngữ cảnh kỳ lương nháp, sinh lại hoa hồng mới, tự động liên kết lại với PayrollItem nháp phù hợp và gọi PayrollService.recalculateDraftItemAfterCommissionChange cho tất cả dòng bị ảnh hưởng.
     */
    @Override
    public void recalculateCommissionsForPerformedService(PerformedService performedService) {
        if (performedService == null || performedService.getPerformedId() == null) {
            return;
        }
        DraftBindingContext ctx = cancelAndCaptureDraftBindings(performedService.getPerformedId());
        List<ServiceCommission> newlyCreated = calculateAndReturnCommissions(performedService);

        Set<Long> itemsToRecalc = new LinkedHashSet<>(ctx.affectedDraftItemIds());
        boolean reboundAny = false;

        for (ServiceCommission sc : newlyCreated) {
            Long staffId = sc.getStaff() != null ? sc.getStaff().getStaffId() : null;
            if (staffId == null) {
                continue;
            }
            PayrollItem targetItem = resolveRebindTargetItem(performedService, sc, staffId, ctx);
            if (targetItem != null) {
                sc.setPayrollItem(targetItem);
                serviceCommissionRepository.save(sc);
                if (targetItem.getItemId() != null) {
                    itemsToRecalc.add(targetItem.getItemId());
                }
                reboundAny = true;
            }
        }

        if (reboundAny) {
            serviceCommissionRepository.flush();
        }
        for (Long itemId : itemsToRecalc) {
            payrollService.recalculateDraftItemAfterCommissionChange(itemId);
        }
    }

    /**
     * [Hàm hỗ trợ]: Xác định dòng lương DRAFT mục tiêu để gắn lại hoa hồng mới (ưu tiên dòng lương cũ của nhân sự hoặc tìm theo kỳ lương DRAFT cùng chi nhánh/tháng/năm).
     */
    private PayrollItem resolveRebindTargetItem(
            PerformedService ps,
            ServiceCommission sc,
            Long staffId,
            DraftBindingContext ctx
    ) {
        PayrollItem preserved = ctx.preservedItemsByStaffId().get(staffId);
        if (preserved != null) {
            return preserved;
        }
        if (payrollItemRepository == null || ctx.lockedDraftPeriodsById().isEmpty()
                || sc.getCalculatedAt() == null || ps.getBranch() == null || ps.getBranch().getBranchId() == null) {
            return null;
        }
        Long psBranchId = ps.getBranch().getBranchId();
        int calcMonth = sc.getCalculatedAt().getMonthValue();
        int calcYear = sc.getCalculatedAt().getYear();
        for (PayrollPeriod period : ctx.lockedDraftPeriodsById().values()) {
            if (period.getStatus() == PayrollStatus.DRAFT
                    && period.getBranch() != null
                    && psBranchId.equals(period.getBranch().getBranchId())
                    && Objects.equals(period.getMonth(), calcMonth)
                    && Objects.equals(period.getYear(), calcYear)) {
                PayrollItem found = payrollItemRepository
                        .findByPayrollPeriod_PeriodIdAndStaff_StaffId(period.getPeriodId(), staffId)
                        .orElse(null);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * [Chức năng]: Đảm bảo tất cả dịch vụ thực hiện thuộc một hóa đơn đã được tính hoa hồng đầy đủ (idempotent).
     * [Mục đích]: Duyệt các InvoiceItem của hóa đơn và gọi calculateCommissionsForPerformedService cho từng dịch vụ gắn kèm nhằm tránh bỏ sót hoa hồng khi thu ngân thanh toán hóa đơn.
     */
    @Override
    public void ensureCommissionsForInvoice(Invoice invoice) {
        if (invoice == null || invoice.getInvoiceId() == null || invoiceItemRepository == null) {
            return;
        }
        List<InvoiceItem> items = invoiceItemRepository.findByInvoice_InvoiceIdOrderByLineOrderAscItemIdAsc(invoice.getInvoiceId());
        if (items == null || items.isEmpty()) {
            return;
        }
        for (InvoiceItem item : items) {
            if (item.getPerformedService() != null) {
                calculateCommissionsForPerformedService(item.getPerformedService());
            }
        }
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra chống trùng lặp, tìm chính sách hoa hồng phù hợp nhất và tạo bản ghi ServiceCommission PENDING nếu số tiền hoa hồng lớn hơn 0.
     */
    private ServiceCommission createCommissionIfApplicable(
            PerformedService ps,
            StaffProfile staff,
            CommissionRoleType roleType,
            BigDecimal revenueBase
    ) {
        if (serviceCommissionRepository.existsByPerformedService_PerformedIdAndStaff_StaffIdAndRoleType(
                ps.getPerformedId(), staff.getStaffId(), roleType)) {
            return null;
        }

        CommissionPolicy policy = findBestPolicy(staff, ps.getService().getServiceId());
        if (policy == null || policy.getCommissionValue() == null) {
            return null;
        }

        BigDecimal commissionAmount = calculateAmount(policy, revenueBase);
        if (commissionAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        ServiceCommission sc = new ServiceCommission();
        sc.setPerformedService(ps);
        sc.setStaff(staff);
        sc.setRoleType(roleType);
        sc.setCommissionAmount(commissionAmount);
        sc.setStatus(CommissionStatus.PENDING);
        sc.setCalculatedAt(ps.getCompletedAt() != null ? ps.getCompletedAt() : LocalDateTime.now());
        return serviceCommissionRepository.save(sc);
    }

    /**
     * [Hàm hỗ trợ]: Tìm chính sách hoa hồng đang kích hoạt (isActive = true) có độ ưu tiên cụ thể cao nhất khớp với nhân sự, vai trò và dịch vụ.
     */
    private CommissionPolicy findBestPolicy(StaffProfile staff, Long serviceId) {
        if (commissionPolicyRepository == null || staff == null || staff.getStaffId() == null || serviceId == null) {
            return null;
        }

        List<Long> roleIds = resolveActiveRoleIds(staff);
        List<CommissionPolicy> candidates = new ArrayList<>();
        if (roleIds.isEmpty()) {
            List<CommissionPolicy> matched = commissionPolicyRepository.findApplicablePolicies(
                    staff.getStaffId(), null, serviceId);
            if (matched != null) {
                candidates.addAll(matched);
            }
        } else {
            for (Long roleId : roleIds) {
                List<CommissionPolicy> matched = commissionPolicyRepository.findApplicablePolicies(
                        staff.getStaffId(), roleId, serviceId);
                if (matched != null) {
                    candidates.addAll(matched);
                }
            }
        }

        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(cp -> Boolean.TRUE.equals(cp.getIsActive()))
                .max(Comparator
                        .comparingInt(this::specificityScore)
                        .thenComparing(cp -> cp.getPolicyId() != null ? cp.getPolicyId() : 0L))
                .orElse(null);
    }

    /**
     * [Hàm hỗ trợ]: Tính điểm độ đặc hiệu của chính sách hoa hồng theo trọng số (Nhân sự: +4, Dịch vụ: +2, Vai trò: +1) để chọn chính sách ưu tiên nhất.
     */
    private int specificityScore(CommissionPolicy cp) {
        int score = 0;
        if (cp.getStaff() != null) {
            score += 4;
        }
        if (cp.getService() != null) {
            score += 2;
        }
        if (cp.getRole() != null) {
            score += 1;
        }
        return score;
    }

    /**
     * [Hàm hỗ trợ]: Trích xuất danh sách ID các vai trò (Role) đang hoạt động gắn với tài khoản người dùng của nhân sự.
     */
    private List<Long> resolveActiveRoleIds(StaffProfile staff) {
        if (staff.getUser() == null || staff.getUser().getRoles() == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Role r : staff.getUser().getRoles()) {
            if (r != null && r.getRoleId() != null && !Boolean.FALSE.equals(r.getIsActive())) {
                ids.add(r.getRoleId());
            }
        }
        return ids;
    }

    /**
     * [Hàm hỗ trợ]: Tính doanh thu cơ sở của dịch vụ thực hiện (ưu tiên finalPrice, nếu trống thì tính đơn giá * số lượng - giảm giá, tối thiểu bằng 0).
     */
    private BigDecimal resolveRevenueBase(PerformedService ps) {
        if (ps.getFinalPrice() != null) {
            return ps.getFinalPrice().max(BigDecimal.ZERO);
        }
        BigDecimal unit = ps.getUnitPrice() != null ? ps.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal qty = ps.getQuantity() != null ? ps.getQuantity() : BigDecimal.ONE;
        BigDecimal discount = ps.getDiscountAmount() != null ? ps.getDiscountAmount() : BigDecimal.ZERO;
        return unit.multiply(qty).subtract(discount).max(BigDecimal.ZERO);
    }

    /**
     * [Hàm hỗ trợ]: Tính số tiền hoa hồng theo loại chính sách (số tiền cố định FIXED_AMOUNT hoặc phần trăm PERCENTAGE trên doanh thu cơ sở, làm tròn 2 chữ số thập phân).
     */
    private BigDecimal calculateAmount(CommissionPolicy policy, BigDecimal revenueBase) {
        BigDecimal value = policy.getCommissionValue();
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (policy.getCommissionType() == CommissionType.FIXED_AMOUNT) {
            return value.setScale(2, RoundingMode.HALF_UP);
        }
        // PERCENTAGE
        if (revenueBase.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return revenueBase.multiply(value).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
