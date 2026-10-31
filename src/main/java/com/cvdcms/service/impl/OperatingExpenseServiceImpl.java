package com.cvdcms.service.impl;

import com.cvdcms.DTO.OperatingExpenseRequestDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.OperatingExpense;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.ExpenseSourceType;
import com.cvdcms.entity.enums.RefundMethod;
import com.cvdcms.entity.SystemAuditLog;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.OperatingExpenseRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.SystemAuditLogRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.OperatingExpenseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class OperatingExpenseServiceImpl implements OperatingExpenseService {

    private final OperatingExpenseRepository operatingExpenseRepository;
    private final BranchRepository branchRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final UserRepository userRepository;
    private final SystemAuditLogRepository systemAuditLogRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public OperatingExpenseServiceImpl(
            OperatingExpenseRepository operatingExpenseRepository,
            BranchRepository branchRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            UserRepository userRepository,
            SystemAuditLogRepository systemAuditLogRepository
    ) {
        this.operatingExpenseRepository = operatingExpenseRepository;
        this.branchRepository = branchRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.userRepository = userRepository;
        this.systemAuditLogRepository = systemAuditLogRepository;
    }

    public OperatingExpenseServiceImpl(
            OperatingExpenseRepository operatingExpenseRepository,
            BranchRepository branchRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            UserRepository userRepository
    ) {
        this(operatingExpenseRepository, branchRepository, staffBranchAssignmentRepository, userRepository, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OperatingExpense> getExpenses(
            Long branchId,
            List<Long> allowedBranchIds,
            String category,
            ExpenseSourceType sourceType,
            RefundMethod paymentMethod,
            LocalDate startDate,
            LocalDate endDate,
            Boolean isCancelled,
            String keyword,
            Pageable pageable
    ) {
        List<Long> targetBranchIds;
        if (allowedBranchIds != null) {
            if (allowedBranchIds.isEmpty()) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
            if (branchId != null) {
                if (!allowedBranchIds.contains(branchId)) {
                    return new PageImpl<>(Collections.emptyList(), pageable, 0);
                }
                targetBranchIds = List.of(branchId);
            } else {
                targetBranchIds = allowedBranchIds;
            }
        } else {
            targetBranchIds = branchId != null ? List.of(branchId) : null;
        }

        return operatingExpenseRepository.findFilteredExpenses(
                targetBranchIds,
                category,
                sourceType,
                paymentMethod,
                startDate,
                endDate,
                isCancelled,
                keyword,
                pageable
        );
    }

    @Override
    @Transactional(readOnly = true)
    public OperatingExpense getExpenseDetail(Long expenseId, Long currentUserId) {
        OperatingExpense expense = operatingExpenseRepository.findWithDetailsById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi với ID: " + expenseId));

        if (currentUserId != null && !userRepository.hasActiveAdminRole(currentUserId)) {
            validateBranchAccess(expense.getBranch().getBranchId(), currentUserId);
        }

        return expense;
    }

    @Override
    public OperatingExpense createManualExpense(OperatingExpenseRequestDTO dto, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin người dùng"));

        validateBranchAccess(dto.getBranchId(), currentUserId);

        Branch branch = branchRepository.findById(dto.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Chi nhánh không tồn tại"));

        OperatingExpense expense = new OperatingExpense();
        expense.setBranch(branch);
        expense.setCategory(dto.getCategory().trim());
        expense.setAmount(dto.getAmount());
        expense.setExpenseDate(dto.getExpenseDate() != null ? dto.getExpenseDate() : LocalDate.now());
        expense.setRecipientName(dto.getRecipientName() != null ? dto.getRecipientName().trim() : null);
        expense.setPaymentMethod(dto.getPaymentMethod() != null ? dto.getPaymentMethod() : RefundMethod.BANK_TRANSFER);
        expense.setSourceType(ExpenseSourceType.MANUAL);
        expense.setDescription(dto.getDescription().trim());
        expense.setCreatedBy(currentUser);
        expense.setIsCancelled(false);

        return operatingExpenseRepository.save(expense);
    }

    @Override
    public OperatingExpense cancelExpense(Long expenseId, String reason, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin người dùng"));

        OperatingExpense expense = operatingExpenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi với ID: " + expenseId));

        validateBranchAccess(expense.getBranch().getBranchId(), currentUserId);

        if (expense.getSourceType() != ExpenseSourceType.MANUAL) {
            throw new IllegalStateException("Không thể hủy phiếu chi tự động từ nguồn " + expense.getSourceType()
                    + ". Phiếu chi bảng lương/tạm ứng được quản lý tập trung tại module Bảng lương.");
        }

        if (Boolean.TRUE.equals(expense.getIsCancelled())) {
            throw new IllegalStateException("Phiếu chi này đã bị hủy trước đó.");
        }

        expense.setIsCancelled(true);
        expense.setCancelledReason(reason != null ? reason.trim() : "Hủy phiếu chi");
        expense.setCancelledBy(currentUser);
        expense.setCancelledAt(LocalDateTime.now());

        return operatingExpenseRepository.save(expense);
    }

    @Override
    public OperatingExpense updateManualExpense(Long expenseId, OperatingExpenseRequestDTO dto, String editReason, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin người dùng"));

        OperatingExpense expense = operatingExpenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi với ID: " + expenseId));

        if (expense.getSourceType() != ExpenseSourceType.MANUAL) {
            throw new IllegalStateException("Chỉ có thể chỉnh sửa phiếu chi thủ công (MANUAL). Phiếu chi từ nguồn "
                    + expense.getSourceType() + " không được phép sửa trực tiếp.");
        }

        if (expense.getBranch() != null) {
            validateBranchAccess(expense.getBranch().getBranchId(), currentUserId);
        }

        if (Boolean.TRUE.equals(expense.getIsCancelled())) {
            throw new IllegalStateException("Không thể chỉnh sửa phiếu chi đã bị hủy.");
        }

        if (editReason == null || editReason.trim().isEmpty()) {
            throw new IllegalArgumentException("Bắt buộc nhập lý do chỉnh sửa phiếu chi.");
        }

        if (dto.getBranchId() != null && !dto.getBranchId().equals(expense.getBranch().getBranchId())) {
            validateBranchAccess(dto.getBranchId(), currentUserId);
            Branch newBranch = branchRepository.findById(dto.getBranchId())
                    .orElseThrow(() -> new IllegalArgumentException("Chi nhánh mới không tồn tại"));
            expense.setBranch(newBranch);
        }

        BigDecimal oldAmount = expense.getAmount();
        String oldCategory = expense.getCategory();
        LocalDate oldExpenseDate = expense.getExpenseDate();
        String oldRecipient = expense.getRecipientName();
        RefundMethod oldMethod = expense.getPaymentMethod();
        String oldDescription = expense.getDescription();
        Long oldBranchId = expense.getBranch() != null ? expense.getBranch().getBranchId() : null;

        expense.setCategory(dto.getCategory().trim());
        expense.setAmount(dto.getAmount());
        expense.setExpenseDate(dto.getExpenseDate() != null ? dto.getExpenseDate() : LocalDate.now());
        expense.setRecipientName(dto.getRecipientName() != null ? dto.getRecipientName().trim() : null);
        expense.setPaymentMethod(dto.getPaymentMethod() != null ? dto.getPaymentMethod() : RefundMethod.BANK_TRANSFER);
        expense.setDescription(dto.getDescription() != null ? dto.getDescription().trim() : "");

        OperatingExpense saved = operatingExpenseRepository.save(expense);

        if (systemAuditLogRepository != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> oldValues = new LinkedHashMap<>();
                oldValues.put("amount", oldAmount != null ? oldAmount.toString() : null);
                oldValues.put("category", oldCategory);
                oldValues.put("expenseDate", oldExpenseDate != null ? oldExpenseDate.toString() : null);
                oldValues.put("recipientName", oldRecipient);
                oldValues.put("paymentMethod", oldMethod != null ? oldMethod.name() : null);
                oldValues.put("description", oldDescription);
                oldValues.put("branchId", oldBranchId);

                Map<String, Object> newValues = new LinkedHashMap<>();
                newValues.put("amount", saved.getAmount() != null ? saved.getAmount().toString() : null);
                newValues.put("category", saved.getCategory());
                newValues.put("expenseDate", saved.getExpenseDate() != null ? saved.getExpenseDate().toString() : null);
                newValues.put("recipientName", saved.getRecipientName());
                newValues.put("paymentMethod", saved.getPaymentMethod() != null ? saved.getPaymentMethod().name() : null);
                newValues.put("description", saved.getDescription());
                newValues.put("branchId", saved.getBranch() != null ? saved.getBranch().getBranchId() : null);
                newValues.put("editReason", editReason.trim());

                SystemAuditLog auditLog = new SystemAuditLog();
                auditLog.setUser(currentUser);
                auditLog.setBranch(saved.getBranch());
                auditLog.setAction("UPDATE_MANUAL_EXPENSE");
                auditLog.setEntityTable("operating_expenses");
                auditLog.setEntityId(saved.getExpenseId());
                auditLog.setOldValuesJson(mapper.writeValueAsString(oldValues));
                auditLog.setNewValuesJson(mapper.writeValueAsString(newValues));
                systemAuditLogRepository.save(auditLog);
            } catch (Exception ex) {
                throw new IllegalStateException("Không thể ghi nhật ký kiểm toán cho chi phí: " + ex.getMessage(), ex);
            }
        }

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getTotalActiveExpenses(Long branchId, List<Long> allowedBranchIds, LocalDate startDate, LocalDate endDate) {
        return getTotalActiveExpenses(branchId, allowedBranchIds, null, null, null, startDate, endDate, null);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getTotalActiveExpenses(
            Long branchId,
            List<Long> allowedBranchIds,
            String category,
            ExpenseSourceType sourceType,
            RefundMethod paymentMethod,
            LocalDate startDate,
            LocalDate endDate,
            String keyword
    ) {
        List<Long> targetBranchIds;
        if (allowedBranchIds != null) {
            if (allowedBranchIds.isEmpty()) {
                return BigDecimal.ZERO;
            }
            if (branchId != null) {
                if (!allowedBranchIds.contains(branchId)) {
                    return BigDecimal.ZERO;
                }
                targetBranchIds = List.of(branchId);
            } else {
                targetBranchIds = allowedBranchIds;
            }
        } else {
            targetBranchIds = branchId != null ? List.of(branchId) : null;
        }

        return operatingExpenseRepository.sumFilteredActiveExpenses(
                targetBranchIds,
                category,
                sourceType,
                paymentMethod,
                startDate,
                endDate,
                keyword
        );
    }

    @Override
    public List<String> getCommonExpenseCategories() {
        return List.of(
                "Chi phí thuê mặt bằng",
                "Điện nước & Tiện ích",
                "Vật tư y tế tiêu hao ngoài kho",
                "Bảo trì thiết bị nha khoa",
                "Quảng cáo & Marketing",
                "Văn phòng phẩm & Hành chính",
                "Chi phí vận chuyển & Labo",
                "Tiếp khách & Hội họp",
                "Đào tạo chuyên môn",
                "Chi phí khác"
        );
    }

    private void validateBranchAccess(Long branchId, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }
        if (userRepository.hasActiveAdminRole(currentUserId)) {
            return;
        }
        boolean hasAssignment = staffBranchAssignmentRepository.hasActiveUserAssignment(
                currentUserId, branchId, AssignmentStatus.ACTIVE);
        if (!hasAssignment) {
            throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu của chi nhánh này");
        }
    }
}
