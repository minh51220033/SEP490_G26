package com.cvdcms.service;

import com.cvdcms.DTO.CommissionPolicyDTO;
import com.cvdcms.entity.CommissionPolicy;

import java.util.List;

public interface CommissionPolicyService {

    List<CommissionPolicy> getAllPolicies();

    CommissionPolicy getPolicyById(Long policyId);

    CommissionPolicy savePolicy(CommissionPolicyDTO dto);

    CommissionPolicy savePolicy(CommissionPolicyDTO dto, Long currentUserId);

    void togglePolicyStatus(Long policyId, boolean isActive);

    void togglePolicyStatus(Long policyId, boolean isActive, Long currentUserId);

    void deletePolicy(Long policyId);

    void deletePolicy(Long policyId, Long currentUserId);
}
