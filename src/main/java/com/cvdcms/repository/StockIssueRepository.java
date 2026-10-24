package com.cvdcms.repository;

import com.cvdcms.entity.StockIssue;
import com.cvdcms.entity.enums.StockIssueType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface StockIssueRepository extends JpaRepository<StockIssue, Long> {

    boolean existsByIssueCode(String issueCode);

    @Query(
            value = """
                SELECT si
                FROM StockIssue si
                JOIN FETCH si.branch b
                LEFT JOIN FETCH si.destinationBranch db
                JOIN FETCH si.issuer iss
                WHERE b.branchId = :branchId
                  AND (:issueType IS NULL OR si.issueType = :issueType)
                  AND (:excludeTransfer = FALSE OR si.issueType <> com.cvdcms.entity.enums.StockIssueType.TRANSFER)
                  AND (:keyword IS NULL
                       OR LOWER(si.issueCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(si.notes, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(iss.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:fromDate IS NULL OR si.issueDate >= :fromDate)
                  AND (:toDate IS NULL OR si.issueDate <= :toDate)
                """,
            countQuery = """
                SELECT COUNT(si)
                FROM StockIssue si
                JOIN si.branch b
                JOIN si.issuer iss
                WHERE b.branchId = :branchId
                  AND (:issueType IS NULL OR si.issueType = :issueType)
                  AND (:excludeTransfer = FALSE OR si.issueType <> com.cvdcms.entity.enums.StockIssueType.TRANSFER)
                  AND (:keyword IS NULL
                       OR LOWER(si.issueCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(si.notes, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(iss.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:fromDate IS NULL OR si.issueDate >= :fromDate)
                  AND (:toDate IS NULL OR si.issueDate <= :toDate)
                """
    )
    Page<StockIssue> searchIssues(
            @Param("branchId") Long branchId,
            @Param("issueType") StockIssueType issueType,
            @Param("excludeTransfer") boolean excludeTransfer,
            @Param("keyword") String keyword,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable
    );

    @Query(
            value = """
                SELECT si
                FROM StockIssue si
                JOIN FETCH si.branch b
                LEFT JOIN FETCH si.destinationBranch db
                JOIN FETCH si.issuer iss
                WHERE si.issueType = com.cvdcms.entity.enums.StockIssueType.TRANSFER
                  AND (b.branchId = :branchId OR (db IS NOT NULL AND db.branchId = :branchId))
                  AND (:destinationBranchId IS NULL OR (db IS NOT NULL AND db.branchId = :destinationBranchId))
                  AND (:keyword IS NULL
                       OR LOWER(si.issueCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(si.notes, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(b.branchName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(db.branchName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:fromDate IS NULL OR si.issueDate >= :fromDate)
                  AND (:toDate IS NULL OR si.issueDate <= :toDate)
                """,
            countQuery = """
                SELECT COUNT(si)
                FROM StockIssue si
                JOIN si.branch b
                LEFT JOIN si.destinationBranch db
                WHERE si.issueType = com.cvdcms.entity.enums.StockIssueType.TRANSFER
                  AND (b.branchId = :branchId OR (db IS NOT NULL AND db.branchId = :branchId))
                  AND (:destinationBranchId IS NULL OR (db IS NOT NULL AND db.branchId = :destinationBranchId))
                  AND (:keyword IS NULL
                       OR LOWER(si.issueCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(si.notes, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(b.branchName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(db.branchName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:fromDate IS NULL OR si.issueDate >= :fromDate)
                  AND (:toDate IS NULL OR si.issueDate <= :toDate)
                """
    )
    Page<StockIssue> searchTransfers(
            @Param("branchId") Long branchId,
            @Param("destinationBranchId") Long destinationBranchId,
            @Param("keyword") String keyword,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable
    );

    @Query("""
        SELECT DISTINCT si
        FROM StockIssue si
        JOIN FETCH si.branch b
        LEFT JOIN FETCH si.destinationBranch db
        JOIN FETCH si.issuer iss
        LEFT JOIN FETCH si.items i
        LEFT JOIN FETCH i.material m
        LEFT JOIN FETCH i.batch mb
        WHERE si.issueId = :issueId
        """)
    Optional<StockIssue> findByIdWithDetails(@Param("issueId") Long issueId);
}
