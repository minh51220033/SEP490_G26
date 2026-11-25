package com.cvdcms.repository;

import com.cvdcms.entity.ClinicEquipment;
import com.cvdcms.entity.enums.EquipmentStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClinicEquipmentRepository extends JpaRepository<ClinicEquipment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT e FROM ClinicEquipment e WHERE e.equipmentId = :equipmentId")
    Optional<ClinicEquipment> findByIdForUpdate(@Param("equipmentId") Long equipmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT e FROM ClinicEquipment e WHERE e.equipmentId IN :equipmentIds ORDER BY e.equipmentId ASC")
    List<ClinicEquipment> findByIdInForUpdateOrderByEquipmentIdAsc(@Param("equipmentIds") Collection<Long> equipmentIds);

    List<ClinicEquipment> findByBranchBranchIdOrderByEquipmentCodeAsc(Long branchId);

    List<ClinicEquipment> findByBranchBranchIdAndStatusOrderByEquipmentCodeAsc(Long branchId, EquipmentStatus status);

    boolean existsByBranchBranchIdAndEquipmentCode(Long branchId, String equipmentCode);

    boolean existsByDeviceSerialNumber(String deviceSerialNumber);

    boolean existsByDeviceSerialNumberAndEquipmentIdNot(String deviceSerialNumber, Long equipmentId);

    boolean existsByBranchBranchIdAndEquipmentCodeAndEquipmentIdNot(Long branchId, String equipmentCode, Long equipmentId);
}
