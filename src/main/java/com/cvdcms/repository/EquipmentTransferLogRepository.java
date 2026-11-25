package com.cvdcms.repository;

import com.cvdcms.entity.EquipmentTransferLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EquipmentTransferLogRepository extends JpaRepository<EquipmentTransferLog, Long> {

    List<EquipmentTransferLog> findByEquipmentEquipmentIdOrderByTransferredAtDesc(Long equipmentId);
}
