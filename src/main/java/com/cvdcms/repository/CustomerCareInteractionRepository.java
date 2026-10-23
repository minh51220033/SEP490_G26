package com.cvdcms.repository;

import com.cvdcms.entity.CustomerCareInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository truy vấn dữ liệu bảng lịch sử tương tác chăm sóc khách hàng (customer_care_interactions - FT44).
 */
@Repository
public interface CustomerCareInteractionRepository extends JpaRepository<CustomerCareInteraction, Long> {

    /**
     * Lấy danh sách các lần liên hệ/tương tác của một công việc CSKH (mới nhất trước).
     */
    @Query("""
        SELECT i
        FROM CustomerCareInteraction i
        JOIN FETCH i.task t
        JOIN FETCH i.patientProfile p
        JOIN FETCH i.staff s
        WHERE t.taskId = :taskId
        ORDER BY i.createdAt DESC, i.interactionId DESC
        """)
    List<CustomerCareInteraction> findByTaskIdWithDetails(@Param("taskId") Long taskId);

    /**
     * Lấy toàn bộ lịch sử tương tác CSKH của một bệnh nhân theo thời gian giảm dần (phục vụ SCR-44.6 Timeline).
     */
    @Query("""
        SELECT i
        FROM CustomerCareInteraction i
        JOIN FETCH i.task t
        JOIN FETCH t.branch b
        JOIN FETCH i.patientProfile p
        JOIN FETCH i.staff s
        WHERE p.profileId = :profileId
        ORDER BY i.createdAt DESC, i.interactionId DESC
        """)
    List<CustomerCareInteraction> findByProfileIdWithDetails(@Param("profileId") Long profileId);

    /**
     * Lấy toàn bộ các lần tương tác thuộc một chi nhánh (để lấy lần tương tác gần nhất & lịch hẹn gọi lại next_retry_at).
     */
    @Query("""
        SELECT i
        FROM CustomerCareInteraction i
        JOIN FETCH i.task t
        JOIN FETCH i.patientProfile p
        JOIN FETCH i.staff s
        WHERE (:branchId IS NULL OR t.branch.branchId = :branchId)
        ORDER BY i.createdAt DESC, i.interactionId DESC
        """)
    List<CustomerCareInteraction> findAllByBranchOrderByCreatedAtDesc(@Param("branchId") Long branchId);
}
