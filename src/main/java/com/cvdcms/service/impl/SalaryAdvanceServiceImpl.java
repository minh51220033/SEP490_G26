package com.cvdcms.service.impl;

import com.cvdcms.DTO.SalaryAdvanceRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.ExpenseSourceType;
import com.cvdcms.entity.enums.RefundMethod;
import com.cvdcms.entity.enums.SalaryAdvanceStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.SalaryAdvanceService;
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
import java.util.List;

@Service
@Transactional
public class SalaryAdvanceServiceImpl implements SalaryAdvanceService {

    private final SalaryAdvanceRepository salaryAdvanceRepository;
    private final OperatingExpenseRepository operatingExpenseRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;

    public SalaryAdvanceServiceImpl(
            SalaryAdvanceRepository salaryAdvanceRepository,
            OperatingExpenseRepository operatingExpenseRepository,
            StaffProfileRepository staffProfileRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            BranchRepository branchRepository,
            UserRepository userRepository
    ) {
        this.salaryAdvanceRepository = salaryAdvanceRepository;
        this.operatingExpenseRepository = operatingExpenseRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SalaryAdvance> getAdvances(
            Long branchId,
            List<Long> allowedBranchIds,
            SalaryAdvanceStatus status,
            Long staffId,
            LocalDateTime startDate,
            LocalDateTime endDate,
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

        return salaryAdvanceRepository.findFilteredAdvances(
                targetBranchIds,
                status,
                staffId,
                startDate,
                endDate,
                keyword,
                pageable
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SalaryAdvance getAdvanceDetail(Long advanceId, Long currentUserId) {
        SalaryAdvance advance = salaryAdvanceRepository.findWithDetailsById(advanceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn tạm ứng: " + advanceId));

        if (currentUserId != null && !userRepository.hasActiveAdminRole(currentUserId)) {
            validateBranchAccess(advance.getBranch().getBranchId(), currentUserId);
        }

        return advance;
    }

    @Override
    public SalaryAdvance createAdvanceRequest(SalaryAdvanceRequestDTO dto, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng"));

        if (!Boolean.TRUE.equals(currentUser.getIsActive()) || !"ACTIVE".equalsIgnoreCase(currentUser.getStatus())) {
            throw new AccessDeniedException("Tài khoản người dùng đã bị vô hiệu hóa");
        }

        StaffProfile staff = staffProfileRepository.findById(dto.getStaffId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên"));

        if (staff.getStatus() != StaffStatus.ACTIVE) {
            throw new IllegalArgumentException("Nhân viên " + staff.getFullName() + " hiện không ở trạng thái hoạt động (ACTIVE)");
        }

        Branch branch = branchRepository.findById(dto.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh"));

        validateBranchAccess(branch.getBranchId(), currentUserId);

        // Kiểm tra nhân viên nhận tạm ứng có quan hệ phân công đang hoạt động tại chi nhánh đề nghị
        boolean hasAssignment = staffBranchAssignmentRepository.hasActiveAssignment(
                staff.getStaffId(),
                branch.getBranchId(),
                AssignmentStatus.ACTIVE
        );
        if (!hasAssignment) {
            throw new IllegalArgumentException("Nhân viên " + staff.getFullName() + " không có phân công hoạt động tại chi nhánh " + branch.getBranchName());
        }

        SalaryAdvance advance = new SalaryAdvance();
        advance.setStaff(staff);
        advance.setBranch(branch);
        advance.setAmount(dto.getAmount());
        advance.setReason(dto.getReason().trim());
        advance.setPaymentMethod(dto.getPaymentMethod() != null ? dto.getPaymentMethod() : RefundMethod.BANK_TRANSFER);
        advance.setStatus(SalaryAdvanceStatus.PENDING);
        advance.setAllocatedAmount(BigDecimal.ZERO);
        advance.setRemainingAmount(dto.getAmount());
        advance.setCreatedBy(currentUser);
        advance.setNotes(dto.getNotes() != null ? dto.getNotes().trim() : null);

        return salaryAdvanceRepository.save(advance);
    }

    @Override
    public SalaryAdvance approveAdvance(Long advanceId, Long approverUserId) {
        if (approverUserId == null || !userRepository.hasActiveAdminRole(approverUserId)) {
            throw new AccessDeniedException("Chỉ Quản trị viên (Admin) mới có quyền duyệt đơn tạm ứng lương");
        }

        SalaryAdvance advance = salaryAdvanceRepository.findByIdForUpdate(advanceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn tạm ứng: " + advanceId));

        if (advance.getStatus() != SalaryAdvanceStatus.PENDING) {
            throw new IllegalStateException("Đơn tạm ứng không ở trạng thái chờ duyệt (PENDING)");
        }

        StaffProfile approverStaff = staffProfileRepository.findByUser_UserId(approverUserId).orElse(null);

        advance.setStatus(SalaryAdvanceStatus.APPROVED);
        advance.setApprovedBy(approverStaff);
        advance.setApprovedAt(LocalDateTime.now());

        return salaryAdvanceRepository.save(advance);
    }

    @Override
    public SalaryAdvance payAdvance(Long advanceId, RefundMethod paymentMethod, Long payerUserId) {
        if (payerUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }

        User payerUser = userRepository.findById(payerUserId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin người dùng"));

        if (!Boolean.TRUE.equals(payerUser.getIsActive()) || !"ACTIVE".equalsIgnoreCase(payerUser.getStatus())) {
            throw new AccessDeniedException("Tài khoản người dùng đã bị vô hiệu hóa");
        }

        SalaryAdvance advance = salaryAdvanceRepository.findByIdForUpdate(advanceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn tạm ứng: " + advanceId));

        // Kiểm tra quyền tài chính tại chính chi nhánh của đơn tạm ứng từ dữ liệu DB
        validateBranchAccess(advance.getBranch().getBranchId(), payerUserId);

        if (advance.getStatus() != SalaryAdvanceStatus.APPROVED) {
            throw new IllegalStateException("Đơn tạm ứng phải được duyệt (APPROVED) trước khi chi tiền");
        }

        if (paymentMethod != null) {
            advance.setPaymentMethod(paymentMethod);
        }
        advance.setStatus(SalaryAdvanceStatus.PAID);
        advance.setPaidAt(LocalDateTime.now());
        advance.setAllocatedAmount(BigDecimal.ZERO);
        advance.setRemainingAmount(advance.getAmount());

        SalaryAdvance savedAdvance = salaryAdvanceRepository.save(advance);

        // Tạo phiếu chi thực tế (Operating Expense) nếu chưa có
        if (!operatingExpenseRepository.existsBySalaryAdvance_AdvanceId(savedAdvance.getAdvanceId())) {
            OperatingExpense expense = new OperatingExpense();
            expense.setBranch(savedAdvance.getBranch());
            expense.setCategory("Tạm ứng lương");
            expense.setAmount(savedAdvance.getAmount());
            expense.setExpenseDate(LocalDate.now());
            expense.setRecipientName(savedAdvance.getStaff().getFullName());
            expense.setPaymentMethod(savedAdvance.getPaymentMethod());
            expense.setSourceType(ExpenseSourceType.SALARY_ADVANCE);
            expense.setSalaryAdvance(savedAdvance);
            expense.setDescription("Chi tạm ứng lương cho " + savedAdvance.getStaff().getFullName()
                    + " (" + savedAdvance.getAdvanceCode() + ") - Lý do: " + savedAdvance.getReason());
            expense.setCreatedBy(payerUser);
            expense.setIsCancelled(false);
            operatingExpenseRepository.save(expense);
        }

        return savedAdvance;
    }

    @Override
    public SalaryAdvance rejectAdvance(Long advanceId, String reason, Long rejecterUserId) {
        if (rejecterUserId == null || !userRepository.hasActiveAdminRole(rejecterUserId)) {
            throw new AccessDeniedException("Chỉ Quản trị viên (Admin) mới có quyền từ chối đơn tạm ứng lương");
        }

        SalaryAdvance advance = salaryAdvanceRepository.findByIdForUpdate(advanceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn tạm ứng: " + advanceId));

        if (advance.getStatus() != SalaryAdvanceStatus.PENDING) {
            throw new IllegalStateException("Chỉ có thể từ chối đơn ở trạng thái chờ duyệt (PENDING)");
        }

        advance.setStatus(SalaryAdvanceStatus.REJECTED);
        advance.setNotes((advance.getNotes() != null ? advance.getNotes() + " | " : "") + "Từ chối: " + reason);

        return salaryAdvanceRepository.save(advance);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalaryAdvance> getActiveAdvancesForStaff(Long staffId) {
        return salaryAdvanceRepository.findActiveAdvancesForSettlement(
                staffId,
                SalaryAdvanceStatus.PAID,
                BigDecimal.ZERO
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
