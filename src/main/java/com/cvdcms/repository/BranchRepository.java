
package com.cvdcms.repository;

import com.cvdcms.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    // Kiểm tra mã chi nhánh đã tồn tại hay chưa.
    boolean existsByBranchCode(String branchCode);

    // Lấy danh sách chi nhánh theo thứ tự tên để hiển thị trên giao diện.
    List<Branch> findAllByOrderByBranchNameAsc();

    // Lấy các chi nhánh đang hoạt động để phục vụ đặt lịch.
    List<Branch> findByIsActiveTrueOrderByBranchNameAsc();
}
