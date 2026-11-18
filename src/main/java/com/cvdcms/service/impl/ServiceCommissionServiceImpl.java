package com.cvdcms.service.impl;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.CommissionRoleType;
import com.cvdcms.entity.enums.CommissionStatus;
import com.cvdcms.entity.enums.CommissionType;
import com.cvdcms.entity.enums.PayrollStatus;
import com.cvdcms.entity.enums.PerformedServiceStatus;
import com.cvdcms.repository.CommissionPolicyRepository;
import com.cvdcms.repository.InvoiceItemRepository;
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

@Service
@Transactional
public class ServiceCommissionServiceImpl implements ServiceCommissionService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final CommissionPolicyRepository commissionPolicyRepository;
    private final ServiceCommissionRepository serviceCommissionRepository;
    private final InvoiceItemRepository invoiceItemRepository;

    @Autowired(required = false)
    private PayrollPeriodRepository payrollPeriodRepository;

    @Lazy
    @Autowired(required = false)
    private PayrollService payrollService;

    public void setPayrollPeriodRepository(PayrollPeriodRepository payrollPeriodRepository) {
        this.payrollPeriodRepository = payrollPeriodRepository;
    }

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

    @Override
    public void calculateCommissionsForPerformedService(PerformedService ps) {
        if (ps == null || ps.getPerformedId() == null || ps.getService() == null) {
            return;
        }
        if (ps.getStatus() != PerformedServiceStatus.COMPLETED) {
            return;
        }

        BigDecimal revenueBase = resolveRevenueBase(ps);

        StaffProfile doctor = ps.getDoctor();
        if (doctor != null && doctor.getStaffId() != null) {
            createCommissionIfApplicable(ps, doctor, CommissionRoleType.MAIN_DOCTOR, revenueBase);
        }

        StaffProfile assistant = ps.getAssistant();
        if (assistant != null && assistant.getStaffId() != null
                && (doctor == null || !assistant.getStaffId().equals(doctor.getStaffId()))) {
            createCommissionIfApplicable(ps, assistant, CommissionRoleType.ASSISTANT, revenueBase);
        }
    }

    @Override
    public void cancelPendingCommissionsForPerformedService(Long performedId) {
        if (performedId == null || serviceCommissionRepository == null) {
            return;
        }
        List<ServiceCommission> initial = serviceCommissionRepository.findByPerformedService_PerformedId(performedId);
        if (initial == null || initial.isEmpty()) {
            return;
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
            return;
        }

        Set<Long> draftItemIdsToRecalc = new LinkedHashSet<>();
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
        for (Long itemId : draftItemIdsToRecalc) {
            payrollService.recalculateDraftItemAfterCommissionChange(itemId);
        }
    }

    @Override
    public void recalculateCommissionsForPerformedService(PerformedService performedService) {
        if (performedService == null || performedService.getPerformedId() == null) {
            return;
        }
        cancelPendingCommissionsForPerformedService(performedService.getPerformedId());
        calculateCommissionsForPerformedService(performedService);
    }

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

    private void createCommissionIfApplicable(
            PerformedService ps,
            StaffProfile staff,
            CommissionRoleType roleType,
            BigDecimal revenueBase
    ) {
        if (serviceCommissionRepository.existsByPerformedService_PerformedIdAndStaff_StaffIdAndRoleType(
                ps.getPerformedId(), staff.getStaffId(), roleType)) {
            return;
        }

        CommissionPolicy policy = findBestPolicy(staff, ps.getService().getServiceId());
        if (policy == null || policy.getCommissionValue() == null) {
            return;
        }

        BigDecimal commissionAmount = calculateAmount(policy, revenueBase);
        if (commissionAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        ServiceCommission sc = new ServiceCommission();
        sc.setPerformedService(ps);
        sc.setStaff(staff);
        sc.setRoleType(roleType);
        sc.setCommissionAmount(commissionAmount);
        sc.setStatus(CommissionStatus.PENDING);
        sc.setCalculatedAt(ps.getCompletedAt() != null ? ps.getCompletedAt() : LocalDateTime.now());
        serviceCommissionRepository.save(sc);
    }

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

    private BigDecimal resolveRevenueBase(PerformedService ps) {
        if (ps.getFinalPrice() != null) {
            return ps.getFinalPrice().max(BigDecimal.ZERO);
        }
        BigDecimal unit = ps.getUnitPrice() != null ? ps.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal qty = ps.getQuantity() != null ? ps.getQuantity() : BigDecimal.ONE;
        BigDecimal discount = ps.getDiscountAmount() != null ? ps.getDiscountAmount() : BigDecimal.ZERO;
        return unit.multiply(qty).subtract(discount).max(BigDecimal.ZERO);
    }

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
