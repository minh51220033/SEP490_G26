package com.cvdcms.repository;

import com.cvdcms.entity.DentalChair;
import com.cvdcms.entity.enums.ChairStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DentalChairRepository extends JpaRepository<DentalChair, Long> {

    List<DentalChair> findByBranchBranchIdOrderByChairCodeAsc(Long branchId);

    List<DentalChair> findByRoomRoomIdOrderByChairCodeAsc(Long roomId);

    List<DentalChair> findByBranchBranchIdAndStatusOrderByChairCodeAsc(Long branchId, ChairStatus status);

    boolean existsByBranchBranchIdAndChairCode(Long branchId, String chairCode);

    boolean existsByBranchBranchIdAndChairCodeAndChairIdNot(Long branchId, String chairCode, Long chairId);

    long countByBranchBranchId(Long branchId);

    long countByBranchBranchIdAndStatus(Long branchId, ChairStatus status);

    long countByRoomRoomId(Long roomId);
}
