package com.cvdcms.repository;

import com.cvdcms.entity.LabExternalOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LabExternalOrderRepository extends JpaRepository<LabExternalOrder, Long> {

    Optional<LabExternalOrder> findByOrderItem_OrderItemId(Long orderItemId);

    List<LabExternalOrder> findByOrderItem_Order_PatientProfile_ProfileIdOrderBySentAtDesc(Long profileId);
}
