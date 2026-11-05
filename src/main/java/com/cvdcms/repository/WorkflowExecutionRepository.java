package com.cvdcms.repository;

import com.cvdcms.entity.WorkflowExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, Long> {

    List<WorkflowExecution> findByVisit_VisitId(Long visitId);

    Optional<WorkflowExecution> findFirstByVisit_VisitIdAndVersion_Template_Service_ServiceId(Long visitId, Long serviceId);

    // FT43: khóa bản ghi khi cập nhật bước để tránh hai thao tác đồng thời làm sai thứ tự bước
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WorkflowExecution e where e.executionId = :id")
    Optional<WorkflowExecution> findForUpdate(@Param("id") Long id);

    // FT43: bảng tiến độ quy trình theo ngày bắt đầu thực hiện hoặc ngày check-in của lượt khám
    @Query("select e from WorkflowExecution e join fetch e.visit v join fetch e.patientProfile p join fetch e.branch b "
            + "where ((e.startedAt >= :from and e.startedAt < :to) or (v.checkInAt >= :from and v.checkInAt < :to)) "
            + "and (:allBranches = true or b.branchId in :branchIds) "
            + "order by e.startedAt desc")
    List<WorkflowExecution> findBoard(@Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      @Param("allBranches") boolean allBranches,
                                      @Param("branchIds") Collection<Long> branchIds);
}
