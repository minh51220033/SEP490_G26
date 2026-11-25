package com.cvdcms.repository;

import com.cvdcms.entity.DoctorRoomAssignment;
import com.cvdcms.entity.enums.DoctorAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DoctorRoomAssignmentRepository extends JpaRepository<DoctorRoomAssignment, Long> {

    List<DoctorRoomAssignment> findByBranchBranchIdAndStaffStaffIdAndStatusOrderByIsPrimaryDescAssignmentIdAsc(
            Long branchId,
            Long staffId,
            DoctorAssignmentStatus status
    );

    List<DoctorRoomAssignment> findByBranchBranchIdAndStaffStaffIdOrderByAssignmentIdAsc(Long branchId, Long staffId);

    List<DoctorRoomAssignment> findByRoomRoomIdAndStatus(Long roomId, DoctorAssignmentStatus status);
}
