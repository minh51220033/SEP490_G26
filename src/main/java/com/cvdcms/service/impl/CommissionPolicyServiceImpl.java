package com.cvdcms.service.impl;

import com.cvdcms.DTO.CommissionPolicyDTO;
import com.cvdcms.entity.CommissionPolicy;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.repository.CommissionPolicyRepository;
import com.cvdcms.repository.RoleRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.service.CommissionPolicyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * [Phân hệ]: Tài chính - Cấu hình chính sách hoa hồng
 * [Vai trò]: Triển khai nghiệp vụ quản lý chính sách tính hoa hồng theo dịch vụ, vai trò và nhân sự.
 */
@Service
@Transactional
public class CommissionPolicyServiceImpl implements CommissionPolicyService {

    private final CommissionPolicyRepository commissionPolicyRepository;
    private final ServiceRepository serviceRepository;
    private final RoleRepository roleRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final com.cvdcms.repository.UserRepository userRepository;

    public CommissionPolicyServiceImpl(
            CommissionPolicyRepository commissionPolicyRepository,
            ServiceRepository serviceRepository,
            RoleRepository roleRepository,
            StaffProfileRepository staffProfileRepository,
            com.cvdcms.repository.UserRepository userRepository
    ) {
        this.commissionPolicyRepository = commissionPolicyRepository;
        this.serviceRepository = serviceRepository;
        this.roleRepository = roleRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.userRepository = userRepository;
    }

    /**
     * [Chức năng]: Lấy toàn bộ danh sách chính sách hoa hồng kèm chi tiết dịch vụ, vai trò và nhân sự.
     * [Mục đích]: Cung cấp dữ liệu hiển thị tại màn hình cấu hình hoa hồng (/payroll/commission-config) với truy vấn tối ưu tải sẵn quan hệ.
     */
    @Override
    @Transactional(readOnly = true)
    public List<CommissionPolicy> getAllPolicies() {
        return commissionPolicyRepository.findAllWithDetails();
    }

    /**
     * [Chức năng]: Lấy chi tiết một chính sách hoa hồng theo ID.
     * [Mục đích]: Tra cứu bản ghi CommissionPolicy trong cơ sở dữ liệu và báo lỗi nếu không tồn tại.
     */
    @Override
    @Transactional(readOnly = true)
    public CommissionPolicy getPolicyById(Long policyId) {
        return commissionPolicyRepository.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chính sách hoa hồng: " + policyId));
    }

    /**
     * [Chức năng]: Lưu chính sách hoa hồng (hàm quá tải không truyền ID người thao tác).
     * [Mục đích]: Ủy quyền sang savePolicy(dto, null) để dùng chung quy trình kiểm tra quyền và xác thực dữ liệu.
     */
    @Override
    public CommissionPolicy savePolicy(CommissionPolicyDTO dto) {
        return savePolicy(dto, null);
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng có vai trò Admin hoặc quyền FINANCE_COMMISSION_CONFIG đang hoạt động hay không trước khi thay đổi chính sách hoa hồng.
     */
    private void requireCommissionPermission(Long currentUserId, String actionMessage) {
        if (currentUserId == null || (!userRepository.hasActiveAdminRole(currentUserId)
                && !userRepository.hasActivePermission(currentUserId, "FINANCE_COMMISSION_CONFIG"))) {
            throw new org.springframework.security.access.AccessDeniedException(actionMessage);
        }
    }

    /**
     * [Chức năng]: Tạo mới hoặc cập nhật một chính sách hoa hồng kèm kiểm tra phân quyền và ràng buộc giá trị.
     * [Mục đích]: Kiểm tra quyền FINANCE_COMMISSION_CONFIG, xác thực tên, loại hoa hồng, mức hoa hồng không âm và không vượt quá 100% nếu tính theo phần trăm, liên kết dịch vụ/vai trò/nhân sự tương ứng và lưu vào bảng commission_policies.
     */
    @Override
    public CommissionPolicy savePolicy(CommissionPolicyDTO dto, Long currentUserId) {
        requireCommissionPermission(currentUserId, "Bạn không có quyền tạo hoặc chỉnh sửa chính sách hoa hồng");

        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu chính sách hoa hồng không được để trống.");
        }
        if (dto.getPolicyName() == null || dto.getPolicyName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tên chính sách hoa hồng không được để trống.");
        }
        if (dto.getCommissionType() == null) {
            throw new IllegalArgumentException("Loại hoa hồng không được để trống.");
        }
        if (dto.getCommissionValue() == null) {
            throw new IllegalArgumentException("Giá trị hoa hồng không được để trống.");
        }
        if (dto.getCommissionValue().compareTo(java.math.BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Giá trị hoa hồng không được âm.");
        }
        if (dto.getCommissionType() == com.cvdcms.entity.enums.CommissionType.PERCENTAGE
                && dto.getCommissionValue().compareTo(new java.math.BigDecimal("100.00")) > 0) {
            throw new IllegalArgumentException("Tỷ lệ hoa hồng phần trăm không được vượt quá 100%.");
        }

        CommissionPolicy policy;
        if (dto.getPolicyId() != null) {
            policy = getPolicyById(dto.getPolicyId());
        } else {
            policy = new CommissionPolicy();
        }

        policy.setPolicyName(dto.getPolicyName().trim());
        policy.setCommissionType(dto.getCommissionType());
        policy.setCommissionValue(dto.getCommissionValue());
        policy.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : false); // Default OFF

        if (dto.getServiceId() != null) {
            policy.setService(serviceRepository.findById(dto.getServiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Dịch vụ với ID " + dto.getServiceId() + " không tồn tại.")));
        } else {
            policy.setService(null);
        }

        if (dto.getRoleId() != null) {
            policy.setRole(roleRepository.findById(dto.getRoleId().intValue())
                    .orElseThrow(() -> new IllegalArgumentException("Vai trò với ID " + dto.getRoleId() + " không tồn tại.")));
        } else {
            policy.setRole(null);
        }

        if (dto.getStaffId() != null) {
            policy.setStaff(staffProfileRepository.findById(dto.getStaffId())
                    .orElseThrow(() -> new IllegalArgumentException("Nhân viên với ID " + dto.getStaffId() + " không tồn tại.")));
        } else {
            policy.setStaff(null);
        }

        return commissionPolicyRepository.save(policy);
    }

    /**
     * [Chức năng]: Bật hoặc tắt trạng thái áp dụng của chính sách hoa hồng (hàm quá tải không truyền ID người thao tác).
     * [Mục đích]: Ủy quyền sang togglePolicyStatus(policyId, isActive, null) để kiểm tra quyền thống nhất.
     */
    @Override
    public void togglePolicyStatus(Long policyId, boolean isActive) {
        togglePolicyStatus(policyId, isActive, null);
    }

    /**
     * [Chức năng]: Bật hoặc tắt trạng thái hoạt động (isActive) của một chính sách hoa hồng theo ID.
     * [Mục đích]: Kiểm tra quyền FINANCE_COMMISSION_CONFIG, tải chính sách và cập nhật cờ kích hoạt trong bảng commission_policies.
     */
    @Override
    public void togglePolicyStatus(Long policyId, boolean isActive, Long currentUserId) {
        requireCommissionPermission(currentUserId, "Bạn không có quyền thay đổi trạng thái chính sách hoa hồng");
        CommissionPolicy policy = getPolicyById(policyId);
        policy.setIsActive(isActive);
        commissionPolicyRepository.save(policy);
    }

    /**
     * [Chức năng]: Xóa chính sách hoa hồng theo ID (hàm quá tải không truyền ID người thao tác).
     * [Mục đích]: Ủy quyền sang deletePolicy(policyId, null) để kiểm tra quyền thống nhất.
     */
    @Override
    public void deletePolicy(Long policyId) {
        deletePolicy(policyId, null);
    }

    /**
     * [Chức năng]: Xóa vĩnh viễn một chính sách hoa hồng khỏi cơ sở dữ liệu theo ID.
     * [Mục đích]: Kiểm tra quyền FINANCE_COMMISSION_CONFIG trước khi gọi commissionPolicyRepository.deleteById.
     */
    @Override
    public void deletePolicy(Long policyId, Long currentUserId) {
        requireCommissionPermission(currentUserId, "Bạn không có quyền xóa chính sách hoa hồng");
        commissionPolicyRepository.deleteById(policyId);
    }
}
