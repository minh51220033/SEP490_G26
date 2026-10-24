package com.cvdcms.repository;

import com.cvdcms.entity.Payment;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.entity.enums.PaymentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentCode(String paymentCode);

    List<Payment> findByInvoice_InvoiceIdOrderByPaidAtDesc(Long invoiceId);

    List<Payment> findByPatientProfile_ProfileIdOrderByPaidAtDesc(Long profileId);

    List<Payment> findByPatientProfile_ProfileIdAndPaymentTypeOrderByPaidAtDesc(
            Long profileId,
            PaymentType paymentType
    );

    @Query("""
        SELECT p FROM Payment p
        JOIN FETCH p.patientProfile pp
        JOIN FETCH p.branch b
        JOIN FETCH p.cashier c
        LEFT JOIN FETCH p.invoice inv
        WHERE p.paymentId = :paymentId
    """)
    Optional<Payment> findWithDetailsById(@Param("paymentId") Long paymentId);

    @Query("""
        SELECT p FROM Payment p
        JOIN p.patientProfile pp
        JOIN p.branch b
        LEFT JOIN p.invoice inv
        WHERE (:branchId IS NULL OR b.branchId = :branchId)
          AND (:paymentType IS NULL OR p.paymentType = :paymentType)
          AND (:paymentMethod IS NULL OR p.paymentMethod = :paymentMethod)
          AND (:startDate IS NULL OR p.paidAt >= :startDate)
          AND (:endDate IS NULL OR p.paidAt <= :endDate)
          AND (
              :keyword IS NULL OR :keyword = ''
              OR LOWER(p.paymentCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(pp.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(pp.patientCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR pp.phoneNumber LIKE CONCAT('%', :keyword, '%')
              OR (inv IS NOT NULL AND LOWER(inv.invoiceCode) LIKE LOWER(CONCAT('%', :keyword, '%')))
          )
    """)
    Page<Payment> findFilteredPayments(
            @Param("branchId") Long branchId,
            @Param("paymentType") PaymentType paymentType,
            @Param("paymentMethod") PaymentMethod paymentMethod,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    Optional<Payment> findByTransactionRef(String transactionRef);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    @Query("""
        SELECT p FROM Payment p
        WHERE p.patientProfile.profileId = :profileId
          AND (:branchIds IS NULL OR p.branch.branchId IN :branchIds)
        ORDER BY p.paidAt DESC
    """)
    List<Payment> findByPatientAndBranches(
            @Param("profileId") Long profileId,
            @Param("branchIds") List<Long> branchIds
    );

    @Query("""
        SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
        WHERE (:branchId IS NULL OR p.branch.branchId = :branchId)
          AND (:paymentType IS NULL OR p.paymentType = :paymentType)
          AND (:startDate IS NULL OR p.paidAt >= :startDate)
          AND (:endDate IS NULL OR p.paidAt <= :endDate)
    """)
    BigDecimal sumAmount(
            @Param("branchId") Long branchId,
            @Param("paymentType") PaymentType paymentType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("""
        SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
        WHERE (:branchId IS NULL OR p.branch.branchId = :branchId)
          AND p.paymentMethod != com.cvdcms.entity.enums.PaymentMethod.DEPOSIT_WALLET
          AND (:startDate IS NULL OR p.paidAt >= :startDate)
          AND (:endDate IS NULL OR p.paidAt <= :endDate)
    """)
    BigDecimal sumRealRevenue(
            @Param("branchId") Long branchId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("""
        SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
        WHERE (:branchId IS NULL OR p.branch.branchId = :branchId)
          AND p.paymentMethod = com.cvdcms.entity.enums.PaymentMethod.DEPOSIT_WALLET
          AND (:startDate IS NULL OR p.paidAt >= :startDate)
          AND (:endDate IS NULL OR p.paidAt <= :endDate)
    """)
    BigDecimal sumDepositWalletUsed(
            @Param("branchId") Long branchId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );
}
