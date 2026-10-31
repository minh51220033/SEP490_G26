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

    @Override
    @Transactional(readOnly = true)
    public List<CommissionPolicy> getAllPolicies() {
        return commissionPolicyRepository.findAllWithDetails();
    }

    @Override
    @Transactional(readOnly = true)
    public CommissionPolicy getPolicyById(Long policyId) {
        return commissionPolicyRepository.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chính sách hoa hồng: " + policyId));
    }

    @Override
    public CommissionPolicy savePolicy(CommissionPolicyDTO dto) {
        return savePolicy(dto, null);
    }

    @Override
    public CommissionPolicy savePolicy(CommissionPolicyDTO dto, Long currentUserId) {
        if (currentUserId == null || !userRepository.hasActiveAdminRole(currentUserId)) {
            throw new org.springframework.security.access.AccessDeniedException("Chỉ Quản trị viên (Admin) mới có quyền tạo hoặc chỉnh sửa chính sách hoa hồng");
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
            policy.setService(serviceRepository.findById(dto.getServiceId()).orElse(null));
        } else {
            policy.setService(null);
        }

        if (dto.getRoleId() != null) {
            policy.setRole(roleRepository.findById(dto.getRoleId().intValue()).orElse(null));
        } else {
            policy.setRole(null);
        }

        if (dto.getStaffId() != null) {
            policy.setStaff(staffProfileRepository.findById(dto.getStaffId()).orElse(null));
        } else {
            policy.setStaff(null);
        }

        return commissionPolicyRepository.save(policy);
    }

    @Override
    public void togglePolicyStatus(Long policyId, boolean isActive) {
        togglePolicyStatus(policyId, isActive, null);
    }

    @Override
    public void togglePolicyStatus(Long policyId, boolean isActive, Long currentUserId) {
        if (currentUserId == null || !userRepository.hasActiveAdminRole(currentUserId)) {
            throw new org.springframework.security.access.AccessDeniedException("Chỉ Quản trị viên (Admin) mới có quyền thay đổi trạng thái chính sách hoa hồng");
        }
        CommissionPolicy policy = getPolicyById(policyId);
        policy.setIsActive(isActive);
        commissionPolicyRepository.save(policy);
    }

    @Override
    public void deletePolicy(Long policyId) {
        deletePolicy(policyId, null);
    }

    @Override
    public void deletePolicy(Long policyId, Long currentUserId) {
        if (currentUserId == null || !userRepository.hasActiveAdminRole(currentUserId)) {
            throw new org.springframework.security.access.AccessDeniedException("Chỉ Quản trị viên (Admin) mới có quyền xóa chính sách hoa hồng");
        }
        commissionPolicyRepository.deleteById(policyId);
    }
}
