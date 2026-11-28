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

/**
 * [Phân hệ]: Tài chính - Phân chia lợi nhuận chủ sở hữu
 * [Vai trò]: Triển khai quy trình lập đề xuất, phê duyệt, từ chối và xác nhận chi trả lợi nhuận cho các chủ sở hữu/cổ đông (tách biệt khỏi chi phí hoạt động).
 */
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

    /**
     * [Chức năng]: Tra cứu phân trang các đợt phân chia lợi nhuận theo tháng, năm và trạng thái trên toàn hệ thống.
     * [Mục đích]: Ủy quyền sang hàm lọc có phạm vi chi nhánh với allowedBranchIds = null.
     */
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

    /**
     * [Chức năng]: Tra cứu phân trang các đợt phân chia lợi nhuận trong phạm vi chi nhánh được phép theo tháng, năm và trạng thái.
     * [Mục đích]: Gọi OwnerDistributionRepository.findFilteredDistributions để hiển thị danh sách tại màn hình phân chia lợi nhuận.
     */
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

    /**
     * [Chức năng]: Lấy thông tin chi tiết của một đợt phân chia lợi nhuận cùng danh sách thành viên nhận phân bổ.
     * [Mục đích]: Tải đầy đủ entity OwnerDistribution qua findWithDetailsById và ném lỗi nếu không tồn tại.
     */
    @Override
    @Transactional(readOnly = true)
    public OwnerDistribution getDistributionDetail(Long distributionId) {
        return ownerDistributionRepository.findWithDetailsById(distributionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đợt phân chia lợi nhuận: " + distributionId));
    }

    /**
     * [Chức năng]: Lập và trình duyệt đề xuất phân chia lợi nhuận mới ở trạng thái SUBMITTED.
     * [Mục đích]: Kiểm tra tài khoản hoạt động, quyền chi nhánh hoặc quyền FINANCE_OWNER_DISTRIBUTIONS cho toàn hệ thống, xác thực bất biến ngân sách (tổng chia <= lợi nhuận - giữ lại, tổng tỷ lệ <= 100%, không trùng người nhận đang ACTIVE) và lưu bảng owner_distributions cùng owner_distribution_items.
     */
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
            // Không truyền branchId = Đề xuất toàn hệ thống -> Admin hoặc tài khoản được cấp FINANCE_OWNER_DISTRIBUTIONS
            if (!isAdmin && !userRepository.hasActivePermission(currentUserId, "FINANCE_OWNER_DISTRIBUTIONS")) {
                throw new AccessDeniedException("Bạn không có quyền lập đề xuất phân chia lợi nhuận toàn hệ thống");
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

    /**
     * [Chức năng]: Phê duyệt đề xuất phân chia lợi nhuận, chuyển trạng thái từ SUBMITTED sang APPROVED.
     * [Mục đích]: Kiểm tra quyền Admin/FINANCE_OWNER_DISTRIBUTIONS, xác minh trạng thái SUBMITTED và đối soát lại tổng phân bổ không vượt quá quỹ khả dụng trước khi ghi nhận người duyệt và thời gian duyệt.
     */
    @Override
    public OwnerDistribution approveDistribution(Long distributionId, Long currentUserId) {
        if (currentUserId == null || (!userRepository.hasActiveAdminRole(currentUserId)
                && !userRepository.hasActivePermission(currentUserId, "FINANCE_OWNER_DISTRIBUTIONS"))) {
            throw new AccessDeniedException("Bạn không có quyền phê duyệt phân chia lợi nhuận");
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

    /**
     * [Chức năng]: Từ chối đề xuất phân chia lợi nhuận đang chờ duyệt, chuyển trạng thái sang REJECTED kèm lý do.
     * [Mục đích]: Kiểm tra quyền Admin/FINANCE_OWNER_DISTRIBUTIONS, xác nhận trạng thái SUBMITTED và lưu lý do từ chối vào bảng owner_distributions.
     */
    @Override
    public OwnerDistribution rejectDistribution(Long distributionId, String reason, Long currentUserId) {
        if (currentUserId == null || (!userRepository.hasActiveAdminRole(currentUserId)
                && !userRepository.hasActivePermission(currentUserId, "FINANCE_OWNER_DISTRIBUTIONS"))) {
            throw new AccessDeniedException("Bạn không có quyền từ chối phê duyệt phân chia lợi nhuận");
        }

        OwnerDistribution distribution = getDistributionDetail(distributionId);
        if (distribution.getStatus() != OwnerDistributionStatus.SUBMITTED) {
            throw new IllegalStateException("Đề xuất không ở trạng thái Chờ duyệt (SUBMITTED)");
        }

        distribution.setStatus(OwnerDistributionStatus.REJECTED);
        distribution.setRejectionReason(reason != null ? reason.trim() : "Quản trị viên từ chối phê duyệt");

        return ownerDistributionRepository.save(distribution);
    }

    /**
     * [Chức năng]: Xác nhận đã chi trả lợi nhuận cho đợt phân phối đã phê duyệt, chuyển trạng thái từ APPROVED sang PAID.
     * [Mục đích]: Kiểm tra quyền Admin/FINANCE_OWNER_DISTRIBUTIONS, lưu phương thức/mã tham chiếu/người chi trả và giữ nguyên tắc tách biệt (không tạo OperatingExpense).
     */
    @Override
    public OwnerDistribution payDistribution(Long distributionId, RefundMethod paymentMethod, String paymentReference, Long currentUserId) {
        if (currentUserId == null || (!userRepository.hasActiveAdminRole(currentUserId)
                && !userRepository.hasActivePermission(currentUserId, "FINANCE_OWNER_DISTRIBUTIONS"))) {
            throw new AccessDeniedException("Bạn không có quyền xác nhận chi trả lợi nhuận");
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

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng có quyền truy cập chi nhánh chỉ định thông qua vai trò Admin, phân công ACTIVE hoặc chi nhánh mặc định.
     */
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
            boolean isMultiBranch = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(currentUserId, com.cvdcms.entity.enums.AssignmentStatus.ACTIVE).isEmpty()
                    && userRepository.findById(currentUserId).map(u -> u.getBranch() == null || branchId.equals(u.getBranch().getBranchId())).orElse(false);
            if (!isMultiBranch) {
                throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu của chi nhánh này");
            }
        }
    }
}
