package com.cvdcms.repository;

import com.cvdcms.entity.StaffProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DispatchDoctorRepository extends JpaRepository<StaffProfile, Long> {
    @Query("select distinct a.staff from StaffBranchAssignment a where a.branch.branchId = :branch and a.role.roleName in ('BAC_SI','DOCTOR') and a.status = com.cvdcms.entity.enums.AssignmentStatus.ACTIVE and a.startDate <= :day and (a.endDate is null or a.endDate >= :day) and a.staff.status = com.cvdcms.entity.enums.StaffStatus.ACTIVE order by a.staff.fullName")
    List<StaffProfile> eligible(@Param("branch") Long branch, @Param("day") LocalDate day);
}