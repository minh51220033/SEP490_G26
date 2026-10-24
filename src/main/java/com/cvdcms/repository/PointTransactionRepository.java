package com.cvdcms.repository;

import com.cvdcms.entity.PointTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

    List<PointTransaction> findByPatientProfile_ProfileIdOrderByCreatedAtDesc(Long profileId);

    List<PointTransaction> findByInvoice_InvoiceId(Long invoiceId);
}
