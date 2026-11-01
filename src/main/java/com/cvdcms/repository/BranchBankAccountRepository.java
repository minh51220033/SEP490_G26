package com.cvdcms.repository;

import com.cvdcms.entity.BranchBankAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BranchBankAccountRepository extends JpaRepository<BranchBankAccount, Long> {

    List<BranchBankAccount> findByBranch_BranchId(Long branchId);

    List<BranchBankAccount> findByBranch_BranchIdOrderByIsDefaultDescCreatedAtDesc(Long branchId);

    List<BranchBankAccount> findByBranch_BranchIdAndIsActiveTrueOrderByIsDefaultDescCreatedAtDesc(Long branchId);

    Optional<BranchBankAccount> findByBranch_BranchIdAndIsDefaultTrueAndIsActiveTrue(Long branchId);

    Optional<BranchBankAccount> findByBranch_BranchIdAndIsDefaultTrue(Long branchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BranchBankAccount b WHERE b.branch.branchId = :branchId")
    List<BranchBankAccount> findByBranchIdForUpdate(@Param("branchId") Long branchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BranchBankAccount b WHERE b.accountId = :accountId")
    Optional<BranchBankAccount> findByIdForUpdate(@Param("accountId") Long accountId);

    @Query("SELECT b FROM BranchBankAccount b JOIN FETCH b.branch ORDER BY b.branch.branchName ASC, b.isDefault DESC, b.createdAt DESC")
    List<BranchBankAccount> findAllWithBranchOrdered();

    @Query("SELECT b FROM BranchBankAccount b JOIN FETCH b.branch WHERE b.branch.branchId = :branchId ORDER BY b.isDefault DESC, b.createdAt DESC")
    List<BranchBankAccount> findByBranchIdWithBranchOrdered(@Param("branchId") Long branchId);

    boolean existsByBranch_BranchIdAndAccountNumberAndBankBin(Long branchId, String accountNumber, String bankBin);

    @Query("SELECT b FROM BranchBankAccount b WHERE b.branch.branchId = :branchId AND b.bankBin = :bankBin AND UPPER(b.accountNumber) = UPPER(:accountNumber)")
    List<BranchBankAccount> findDuplicates(@Param("branchId") Long branchId, @Param("bankBin") String bankBin, @Param("accountNumber") String accountNumber);
}
