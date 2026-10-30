package com.cvdcms.service.impl;

import com.cvdcms.DTO.OwnerDistributionItemDTO;
import com.cvdcms.DTO.OwnerDistributionRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.OwnerDistributionStatus;
import com.cvdcms.entity.enums.RefundMethod;
import com.cvdcms.repository.*;
import com.cvdcms.service.OwnerDistributionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@Transactional
public class OwnerDistributionServiceImpl implements OwnerDistributionService {

    private final OwnerDistributionRepository ownerDistributionRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    public OwnerDistributionServiceImpl(
            OwnerDistributionRepository ownerDistributionRepository,
            StaffProfileRepository staffProfileRepository,
            BranchRepository branchRepository,
            UserRepository userRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository
    ) {
        this.ownerDistributionRepository = ownerDistributionRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OwnerDistribution> getDistributions(
            Integer month,
            Integer year,
            OwnerDistributionStatus status,
            Pageable pageable
    ) {
        return getDistributions(null, month, year, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OwnerDistribution> getDistributions(
            java.util.List<Long> allowedBranchIds,
            Integer month,
            Integer year,
            OwnerDistributionStatus status,
            Pageable pageable
    ) {
        return ownerDistributionRepository.findFilteredDistributions(allowedBranchIds, month, year, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public OwnerDistribution getDistributionDetail(Long distributionId) {
        return ownerDistributionRepository.findWithDetailsById(distributionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đợt phân chia lợi nhuận: " + distributionId));
    }

    @Override
    public OwnerDistribution createProposal(OwnerDistributionRequestDTO dto, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng"));

        if (!Boolean.TRUE.equals(currentUser.getIsActive()) || !"ACTIVE".equalsIgnoreCase(currentUser.getStatus())) {
            throw new AccessDeniedException("Tài khoản người dùng đã bị vô hiệu hóa");
        }

        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        Branch branch = null;
        if (dto.getBranchId() != null) {
            validateBranchAccess(dto.getBranchId(), currentUserId);
            branch = branchRepository.findById(dto.getBranchId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh: " + dto.getBranchId()));
        } else {
            // Không truyền branchId = Đề xuất toàn hệ thống -> Chỉ Quản trị viên (Admin) mới có quyền
            if (!isAdmin) {
                throw new AccessDeniedException("Chỉ Quản trị viên (Admin) mới có quyền lập đề xuất phân chia lợi nhuận toàn hệ thống");
            }
        }

        // 1. Kiểm tra ngân sách (Budget Invariant)
        if (dto.getDistributableAmount() == null || dto.getDistributableAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Lợi nhuận có thể chia phải lớn hơn 0");
        }

        BigDecimal retainedAmount = dto.getRetainedAmount() != null ? dto.getRetainedAmount() : BigDecimal.ZERO;
        if (retainedAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Số tiền giữ lại tái đầu tư không được âm");
        }
        if (retainedAmount.compareTo(dto.getDistributableAmount()) >= 0) {
            throw new IllegalArgumentException("Số tiền giữ lại (" + retainedAmount + " đ) không được lớn hơn hoặc bằng tổng lợi nhuận (" + dto.getDistributableAmount() + " đ)");
        }

        BigDecimal maxAvailablePool = dto.getDistributableAmount().subtract(retainedAmount);

        // 2. Kiểm tra danh sách người nhận
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw new IllegalArgumentException("Danh sách người nhận phân phối không được để trống");
        }

        java.util.Set<Long> recipientIds = new java.util.HashSet<>();
        BigDecimal calculatedTotal = BigDecimal.ZERO;
        BigDecimal totalPercentage = BigDecimal.ZERO;

        StaffProfile submitter = staffProfileRepository.findByUser_UserId(currentUserId).orElse(null);

        OwnerDistribution distribution = new OwnerDistribution();
        distribution.setBranch(branch);
        distribution.setMonth(dto.getMonth());
        distribution.setYear(dto.getYear());
        distribution.setDistributableAmount(dto.getDistributableAmount());
        distribution.setRetainedAmount(retainedAmount);
        distribution.setBasisNotes(dto.getBasisNotes() != null ? dto.getBasisNotes().trim() : null);
        distribution.setStatus(OwnerDistributionStatus.SUBMITTED);
        distribution.setSubmittedBy(submitter);
        distribution.setSubmittedAt(LocalDateTime.now());

        for (OwnerDistributionItemDTO itemDTO : dto.getItems()) {
            if (itemDTO.getRecipientStaffId() == null) {
                throw new IllegalArgumentException("Chưa chọn thành viên nhận phân phối");
            }

            if (!recipientIds.add(itemDTO.getRecipientStaffId())) {
                throw new IllegalArgumentException("Trùng lặp người nhận trong danh sách phân bổ (Staff ID: " + itemDTO.getRecipientStaffId() + ")");
            }

            if (itemDTO.getAmount() == null || itemDTO.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số tiền chia cho từng thành viên phải lớn hơn 0");
            }

            StaffProfile recipient = staffProfileRepository.findById(itemDTO.getRecipientStaffId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chủ sở hữu / cổ đông: " + itemDTO.getRecipientStaffId()));

            if (recipient.getStatus() != com.cvdcms.entity.enums.StaffStatus.ACTIVE) {
                throw new IllegalArgumentException("Thành viên " + recipient.getFullName() + " hiện không ở trạng thái hoạt động");
            }

            OwnerDistributionItem item = new OwnerDistributionItem();
            item.setDistribution(distribution);
            item.setRecipientStaff(recipient);
            item.setPercentage(itemDTO.getPercentage());
            item.setAmount(itemDTO.getAmount());
            item.setNote(itemDTO.getNote() != null ? itemDTO.getNote().trim() : null);

            calculatedTotal = calculatedTotal.add(itemDTO.getAmount());
            if (itemDTO.getPercentage() != null) {
                totalPercentage = totalPercentage.add(itemDTO.getPercentage());
            }

            distribution.getItems().add(item);
        }

        // Kiểm tra tổng chia không được vượt quá quỹ ngân sách cho phép
        if (calculatedTotal.compareTo(maxAvailablePool) > 0) {
            throw new IllegalArgumentException("Tổng số tiền phân chia (" + calculatedTotal
                    + " đ) vượt quá ngân sách khả dụng sau khi giữ lại (" + maxAvailablePool
                    + " đ = " + dto.getDistributableAmount() + " đ - " + retainedAmount + " đ)");
        }

        if (totalPercentage.compareTo(new BigDecimal("100.00")) > 0) {
            throw new IllegalArgumentException("Tổng tỷ lệ phân phối (" + totalPercentage + "%) vượt quá 100%");
        }

        // Server là nơi duy nhất xác định totalDistributedAmount
        distribution.setTotalDistributedAmount(calculatedTotal);

        return ownerDistributionRepository.save(distribution);
    }

    @Override
    public OwnerDistribution approveDistribution(Long distributionId, Long currentUserId) {
        if (currentUserId == null || !userRepository.hasActiveAdminRole(currentUserId)) {
            throw new AccessDeniedException("Chỉ Quản trị viên (Admin) mới có quyền phê duyệt phân chia lợi nhuận");
        }

        OwnerDistribution distribution = getDistributionDetail(distributionId);
        if (distribution.getStatus() != OwnerDistributionStatus.SUBMITTED) {
            throw new IllegalStateException("Đề xuất phải ở trạng thái Chờ duyệt (SUBMITTED)");
        }

        BigDecimal maxAvailablePool = distribution.getDistributableAmount().subtract(distribution.getRetainedAmount());
        if (distribution.getTotalDistributedAmount().compareTo(maxAvailablePool) > 0) {
            throw new IllegalStateException("Dữ liệu ngân sách không hợp lệ: Tổng chia (" + distribution.getTotalDistributedAmount()
                    + " đ) vượt quá quỹ khả dụng (" + maxAvailablePool + " đ)");
        }

        StaffProfile approver = staffProfileRepository.findByUser_UserId(currentUserId).orElse(null);

        distribution.setStatus(OwnerDistributionStatus.APPROVED);
        distribution.setApprovedBy(approver);
        distribution.setApprovedAt(LocalDateTime.now());
        distribution.setRejectionReason(null);

        return ownerDistributionRepository.save(distribution);
    }

    @Override
    public OwnerDistribution rejectDistribution(Long distributionId, String reason, Long currentUserId) {
        if (currentUserId == null || !userRepository.hasActiveAdminRole(currentUserId)) {
            throw new AccessDeniedException("Chỉ Quản trị viên (Admin) mới có quyền từ chối phê duyệt phân chia lợi nhuận");
        }

        OwnerDistribution distribution = getDistributionDetail(distributionId);
        if (distribution.getStatus() != OwnerDistributionStatus.SUBMITTED) {
            throw new IllegalStateException("Đề xuất không ở trạng thái Chờ duyệt (SUBMITTED)");
        }

        distribution.setStatus(OwnerDistributionStatus.REJECTED);
        distribution.setRejectionReason(reason != null ? reason.trim() : "Quản trị viên từ chối phê duyệt");

        return ownerDistributionRepository.save(distribution);
    }

    @Override
    public OwnerDistribution payDistribution(Long distributionId, RefundMethod paymentMethod, String paymentReference, Long currentUserId) {
        if (currentUserId == null || !userRepository.hasActiveAdminRole(currentUserId)) {
            throw new AccessDeniedException("Chỉ Quản trị viên (Admin) mới có quyền xác nhận chi trả lợi nhuận");
        }

        OwnerDistribution distribution = getDistributionDetail(distributionId);
        if (distribution.getStatus() != OwnerDistributionStatus.APPROVED) {
            throw new IllegalStateException("Đề xuất phân chia phải được phê duyệt (APPROVED) trước khi chi trả");
        }

        StaffProfile payer = staffProfileRepository.findByUser_UserId(currentUserId).orElse(null);

        distribution.setStatus(OwnerDistributionStatus.PAID);
        distribution.setPaidBy(payer);
        distribution.setPaidAt(LocalDateTime.now());
        distribution.setPaymentMethod(paymentMethod != null ? paymentMethod : RefundMethod.BANK_TRANSFER);
        distribution.setPaymentReference(paymentReference != null ? paymentReference.trim() : null);

        // Lưu ý theo yêu cầu nghiệp vụ: Phân chia lợi nhuận TÁCH BIỆT với chi phí vận hành (FT25),
        // KHÔNG tạo OperatingExpense để không làm sai lệch chi phí kinh doanh của phòng khám.
        return ownerDistributionRepository.save(distribution);
    }

    private void validateBranchAccess(Long branchId, Long currentUserId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Người dùng chưa đăng nhập");
        }
        if (userRepository.hasActiveAdminRole(currentUserId)) {
            return;
        }
        boolean hasAssignment = staffBranchAssignmentRepository.hasActiveUserAssignment(
                currentUserId, branchId, com.cvdcms.entity.enums.AssignmentStatus.ACTIVE);
        if (!hasAssignment) {
            throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu của chi nhánh này");
        }
    }
}
