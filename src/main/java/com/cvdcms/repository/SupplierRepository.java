package com.cvdcms.repository;

import com.cvdcms.entity.Supplier;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    boolean existsBySupplierCodeIgnoreCase(String supplierCode);

    boolean existsBySupplierCodeIgnoreCaseAndSupplierIdNot(
            String supplierCode, Long supplierId);

    Optional<Supplier> findBySupplierCodeIgnoreCase(String supplierCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Supplier s WHERE s.supplierId = :supplierId")
    Optional<Supplier> findByIdForUpdate(@Param("supplierId") Long supplierId);

    @Query("""
        SELECT s FROM Supplier s
        WHERE (:keyword IS NULL
               OR LOWER(s.supplierCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(COALESCE(s.phoneNumber, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(COALESCE(s.taxCode, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
          AND (:active IS NULL OR s.isActive = :active)
        """)
    Page<Supplier> searchSuppliers(
            @Param("keyword") String keyword,
            @Param("active") Boolean active,
            Pageable pageable);
}
