package com.cvdcms.repository;

import com.cvdcms.entity.DoctorServicePermission;
import com.cvdcms.entity.enums.DoctorServicePermissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DoctorServicePermissionRepository extends JpaRepository<DoctorServicePermission, Long> {

    List<DoctorServicePermission> findByServiceServiceIdAndStatusOrderByPermissionIdAsc(
            Long serviceId,
            DoctorServicePermissionStatus status
    );

    List<DoctorServicePermission> findByStaffStaffIdAndStatusOrderByPermissionIdAsc(
            Long staffId,
            DoctorServicePermissionStatus status
    );

    List<DoctorServicePermission> findByStaffStaffIdOrderByPermissionIdAsc(Long staffId);

    boolean existsByStaffStaffIdAndServiceServiceIdAndStatusIn(
            Long staffId,
            Long serviceId,
            Collection<DoctorServicePermissionStatus> statuses
    );
}
