package com.cvdcms.repository;

import com.cvdcms.entity.StaffSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface DispatchScheduleRepository extends JpaRepository<StaffSchedule, Long> {
    @Query("select count(s) from StaffSchedule s where s.staff.staffId = :doctor and s.branch.branchId = :branch and s.workDate = :day and s.status = com.cvdcms.entity.enums.ScheduleStatus.SCHEDULED and s.shift.isActive = true and s.shift.startTime <= :time and s.shift.endTime >= :time")
    long working(@Param("doctor") Long doctor, @Param("branch") Long branch, @Param("day") LocalDate day, @Param("time") LocalTime time);

    @Query("select count(s) from StaffSchedule s where s.staff.staffId = :doctor and s.branch.branchId = :branch and s.workDate = :day and s.status = com.cvdcms.entity.enums.ScheduleStatus.SCHEDULED and s.shift.isActive = true")
    long scheduledToday(@Param("doctor") Long doctor, @Param("branch") Long branch, @Param("day") LocalDate day);

    @Query("select s from StaffSchedule s join fetch s.shift sh where s.branch.branchId = :branch and s.workDate = :day and s.status = com.cvdcms.entity.enums.ScheduleStatus.SCHEDULED and sh.isActive = true order by sh.startTime asc")
    List<StaffSchedule> findTodaySchedules(@Param("branch") Long branch, @Param("day") LocalDate day);
}