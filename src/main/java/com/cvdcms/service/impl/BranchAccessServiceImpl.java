
package com.cvdcms.service.impl;

import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.BranchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triển khai nghiệp vụ kiểm tra quyền truy cập cơ sở.
 */
@Service
public class BranchAccessServiceImpl implements BranchAccessService {

    private final UserRepository userRepository;
    private BranchService branchService;

    public BranchAccessServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Autowired(required = false)
    public void setBranchService(@Lazy BranchService branchService) {
        this.branchService = branchService;
    }

    /**
     * ADMIN được truy cập mọi cơ sở hợp lệ.
     * QUAN_LY_KHO chỉ được truy cập cơ sở được phân công ACTIVE.
     */
    @Override
    public boolean canAccessBranch(Long userId, Long branchId) {
        if (userId == null || userId <= 0
                || branchId == null || branchId <= 0) {
            return false;
        }

        if (userRepository.hasActiveAdminRole(userId)) {
            return true;
        }


        Long count = userRepository.countActiveWarehouseAssignments(
                userId, branchId
        );

        if (count != null && count > 0) {
            return true;
        }

        Long invPermCount = userRepository.countActiveInventoryPermissionForBranch(userId, branchId);
        if (invPermCount != null && invPermCount > 0) {
            return true;
        }

        return branchService != null
                && userRepository.hasActiveSupplierManagementRole(userId)
                && branchService.canUserAccessBranch(userId, branchId);
    }


    @Override
    public boolean isAdmin(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }

        return userRepository.hasActiveAdminRole(userId);
    }
}
