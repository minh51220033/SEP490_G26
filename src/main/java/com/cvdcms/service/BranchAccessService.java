
package com.cvdcms.service;

/**
 * Kiểm tra quyền truy cập của tài khoản đối với một cơ sở.
 */
public interface BranchAccessService {

    /**
     * Kiểm tra tài khoản có được truy cập cơ sở hay không.
     *
     * @param userId ID tài khoản đăng nhập
     * @param branchId ID cơ sở cần truy cập
     * @return true nếu có quyền, ngược lại false
     */
    boolean canAccessBranch(Long userId, Long branchId);

    /**
     * Kiểm tra tài khoản có quyền ADMIN đang hoạt động hay không.
     */
    boolean isAdmin(Long userId);
}
