package com.cvdcms.repository;

import com.cvdcms.entity.WorkShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

// Truy vấn ca làm việc (work_shifts) - FT33.
@Repository
public interface WorkShiftRepository extends JpaRepository<WorkShift, Long> {

    // Ca đang hoạt động của một chi nhánh (hoặc ca dùng chung branch_id = NULL), sắp theo giờ bắt đầu.
    List<WorkShift> findByBranchBranchIdAndIsActiveTrueOrderByStartTimeAsc(Long branchId);

    List<WorkShift> findByBranchIsNullAndIsActiveTrueOrderByStartTimeAsc();

    long countByBranchBranchId(Long branchId);
}
