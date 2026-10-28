package com.cvdcms.repository;

import com.cvdcms.entity.AppointmentSessionWindow;
import com.cvdcms.entity.enums.PreferredSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppointmentSessionWindowRepository extends JpaRepository<AppointmentSessionWindow, Long> {
    Optional<AppointmentSessionWindow> findByBranch_BranchIdAndPreferredSession(Long branchId, PreferredSession session);
}
