package com.cvdcms.repository;

import com.cvdcms.entity.BookingConsultationRequest;
import com.cvdcms.entity.enums.ConsultationRequestStatus;
import com.cvdcms.entity.enums.PreferredSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingConsultationRequestRepository extends JpaRepository<BookingConsultationRequest, Long> {

    Optional<BookingConsultationRequest> findByRequestKey(String requestKey);

    Optional<BookingConsultationRequest> findByConvertRequestKey(String convertRequestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BookingConsultationRequest r WHERE r.requestId = :id")
    Optional<BookingConsultationRequest> lockById(@Param("id") Long id);

    @Query("""
            SELECT r FROM BookingConsultationRequest r
            JOIN FETCH r.branch b
            LEFT JOIN FETCH r.preferredDoctor d
            LEFT JOIN FETCH r.service s
            LEFT JOIN FETCH r.handledByUser h
            LEFT JOIN FETCH r.convertedAppointment a
            WHERE r.requestId = :id
            """)
    Optional<BookingConsultationRequest> findDetailById(@Param("id") Long id);

    List<BookingConsultationRequest> findByGuestPhoneAndBranch_BranchIdAndPreferredDateAndPreferredSessionAndStatusInOrderByRequestIdDesc(
            String guestPhone,
            Long branchId,
            LocalDate preferredDate,
            PreferredSession preferredSession,
            Collection<ConsultationRequestStatus> statuses
    );

    @Query(
            value = """
                    SELECT r FROM BookingConsultationRequest r
                    JOIN FETCH r.branch b
                    LEFT JOIN FETCH r.preferredDoctor d
                    LEFT JOIN FETCH r.service s
                    LEFT JOIN FETCH r.handledByUser h
                    LEFT JOIN FETCH r.convertedAppointment a
                    WHERE (:filterByBranches = false OR b.branchId IN :branchIds)
                      AND (:filterByStatuses = false OR r.status IN :statuses)
                      AND (:keyword IS NULL OR :keyword = '' OR
                           LOWER(r.guestName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                           r.guestPhone LIKE CONCAT('%', :keyword, '%') OR
                           LOWER(r.requestCode) LIKE LOWER(CONCAT('%', :keyword, '%')))
                      AND (:createdFrom IS NULL OR r.createdAt >= :createdFrom)
                      AND (:createdTo IS NULL OR r.createdAt <= :createdTo)
                    ORDER BY
                      CASE
                        WHEN r.status = com.cvdcms.entity.enums.ConsultationRequestStatus.NEW THEN 0
                        WHEN r.status = com.cvdcms.entity.enums.ConsultationRequestStatus.CONTACTED THEN 1
                        WHEN r.status = com.cvdcms.entity.enums.ConsultationRequestStatus.CONVERTED THEN 2
                        ELSE 3
                      END ASC,
                      r.createdAt DESC,
                      r.requestId DESC
                    """,
            countQuery = """
                    SELECT COUNT(r) FROM BookingConsultationRequest r
                    WHERE (:filterByBranches = false OR r.branch.branchId IN :branchIds)
                      AND (:filterByStatuses = false OR r.status IN :statuses)
                      AND (:keyword IS NULL OR :keyword = '' OR
                           LOWER(r.guestName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                           r.guestPhone LIKE CONCAT('%', :keyword, '%') OR
                           LOWER(r.requestCode) LIKE LOWER(CONCAT('%', :keyword, '%')))
                      AND (:createdFrom IS NULL OR r.createdAt >= :createdFrom)
                      AND (:createdTo IS NULL OR r.createdAt <= :createdTo)
                    """
    )
    Page<BookingConsultationRequest> searchPaged(
            @Param("filterByBranches") boolean filterByBranches,
            @Param("branchIds") Collection<Long> branchIds,
            @Param("filterByStatuses") boolean filterByStatuses,
            @Param("statuses") Collection<ConsultationRequestStatus> statuses,
            @Param("keyword") String keyword,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo,
            Pageable pageable
    );

    @Query("""
            SELECT r.status, COUNT(r) FROM BookingConsultationRequest r
            WHERE (:filterByBranches = false OR r.branch.branchId IN :branchIds)
            GROUP BY r.status
            """)
    List<Object[]> countStatusBuckets(
            @Param("filterByBranches") boolean filterByBranches,
            @Param("branchIds") Collection<Long> branchIds
    );

    @Query("""
            SELECT COUNT(r) FROM BookingConsultationRequest r
            WHERE (:filterByBranches = false OR r.branch.branchId IN :branchIds)
              AND r.status = com.cvdcms.entity.enums.ConsultationRequestStatus.NEW
              AND r.handledByUser IS NULL
            """)
    long countUnclaimedNew(
            @Param("filterByBranches") boolean filterByBranches,
            @Param("branchIds") Collection<Long> branchIds
    );

    @Query("""
            SELECT MAX(r.updatedAt) FROM BookingConsultationRequest r
            WHERE (:filterByBranches = false OR r.branch.branchId IN :branchIds)
            """)
    LocalDateTime findLatestUpdatedAt(
            @Param("filterByBranches") boolean filterByBranches,
            @Param("branchIds") Collection<Long> branchIds
    );
}
