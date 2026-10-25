package com.cvdcms.repository;

import com.cvdcms.entity.StaffSchedule;
import com.cvdcms.entity.enums.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface StaffScheduleRepository extends JpaRepository<StaffSchedule, Long> {
    @Query("SELECT s FROM StaffSchedule s JOIN FETCH s.staff JOIN FETCH s.shift WHERE s.branch.branchId = :branchId AND s.workDate = :date AND s.status = :status")
    List<StaffSchedule> findForBooking(@Param("branchId") Long branchId, @Param("date") LocalDate date,
                                       @Param("status") ScheduleStatus status);
}
