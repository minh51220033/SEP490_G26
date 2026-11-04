package com.cvdcms.repository;

import com.cvdcms.entity.SystemAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SystemAuditLogRepository extends JpaRepository<SystemAuditLog, Long> {

    List<SystemAuditLog> findByEntityTableAndEntityIdOrderByCreatedAtDesc(String entityTable, Long entityId);

    @Query(
        value = """
            SELECT l FROM SystemAuditLog l
            LEFT JOIN FETCH l.user u
            LEFT JOIN FETCH l.branch b
            WHERE (:branchId IS NULL OR (b IS NOT NULL AND b.branchId = :branchId))
              AND (:userId IS NULL OR (u IS NOT NULL AND u.userId = :userId))
              AND (:action IS NULL OR :action = '' OR l.action = :action)
              AND (:entityTable IS NULL OR :entityTable = '' OR l.entityTable = :entityTable)
              AND (:startDate IS NULL OR l.createdAt >= :startDate)
              AND (:endDate IS NULL OR l.createdAt <= :endDate)
              AND (
                  :keyword IS NULL OR :keyword = ''
                  OR LOWER(l.action) LIKE LOWER(CONCAT('%', :keyword, '%'))
                  OR LOWER(l.entityTable) LIKE LOWER(CONCAT('%', :keyword, '%'))
                  OR (l.ipAddress IS NOT NULL AND LOWER(l.ipAddress) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  OR (u IS NOT NULL AND LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')))
              )
            ORDER BY l.createdAt DESC
        """,
        countQuery = """
            SELECT COUNT(l) FROM SystemAuditLog l
            LEFT JOIN l.user u
            LEFT JOIN l.branch b
            WHERE (:branchId IS NULL OR (b IS NOT NULL AND b.branchId = :branchId))
              AND (:userId IS NULL OR (u IS NOT NULL AND u.userId = :userId))
              AND (:action IS NULL OR :action = '' OR l.action = :action)
              AND (:entityTable IS NULL OR :entityTable = '' OR l.entityTable = :entityTable)
              AND (:startDate IS NULL OR l.createdAt >= :startDate)
              AND (:endDate IS NULL OR l.createdAt <= :endDate)
              AND (
                  :keyword IS NULL OR :keyword = ''
                  OR LOWER(l.action) LIKE LOWER(CONCAT('%', :keyword, '%'))
                  OR LOWER(l.entityTable) LIKE LOWER(CONCAT('%', :keyword, '%'))
                  OR (l.ipAddress IS NOT NULL AND LOWER(l.ipAddress) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  OR (u IS NOT NULL AND LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')))
              )
        """
    )
    Page<SystemAuditLog> findFilteredLogs(
            @Param("branchId") Long branchId,
            @Param("userId") Long userId,
            @Param("action") String action,
            @Param("entityTable") String entityTable,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("SELECT DISTINCT l.action FROM SystemAuditLog l WHERE l.action IS NOT NULL AND l.action <> '' ORDER BY l.action ASC")
    List<String> findDistinctActions();

    @Query("SELECT DISTINCT l.entityTable FROM SystemAuditLog l WHERE l.entityTable IS NOT NULL AND l.entityTable <> '' ORDER BY l.entityTable ASC")
    List<String> findDistinctEntityTables();

    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    @Query("SELECT COUNT(l) FROM SystemAuditLog l WHERE l.action LIKE 'CREATE%' OR l.action LIKE '%INSERT%' OR l.action LIKE '%ADD%'")
    long countCreateActions();

    @Query("SELECT COUNT(l) FROM SystemAuditLog l WHERE l.action LIKE 'UPDATE%' OR l.action LIKE '%EDIT%' OR l.action LIKE '%CHANGE%'")
    long countUpdateActions();

    @Query("SELECT COUNT(l) FROM SystemAuditLog l WHERE l.action LIKE 'DELETE%' OR l.action LIKE '%CANCEL%' OR l.action LIKE '%REFUND%' OR l.action LIKE '%BAN%' OR l.action LIKE '%REJECT%'")
    long countSensitiveActions();
}
