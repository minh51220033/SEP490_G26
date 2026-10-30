package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.*;
import java.util.*;

interface DispatchVisitRepository extends JpaRepository<Visit, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Visit v where v.visitId = :id")
    Optional<Visit> lock(@Param("id") Long id);

    @Query("select v from Visit v join fetch v.patientProfile where v.branch.branchId = :branch and v.checkInAt >= :start and v.checkInAt < :end order by v.checkInAt, v.visitId")
    List<Visit> today(@Param("branch") Long branch, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("select count(v) from Visit v where v.primaryDoctor.staffId = :doctor and v.status = :status and v.visitId <> :id")
    long doctorLoad(@Param("doctor") Long doctor, @Param("status") VisitStatus status, @Param("id") Long id);

    @Query("select count(v) from Visit v where v.chairId = :chair and v.visitId <> :id and v.status in :statuses")
    long occupiedChair(@Param("chair") Long chair, @Param("id") Long id, @Param("statuses") Set<VisitStatus> statuses);
}

interface DispatchDoctorRepository extends JpaRepository<StaffProfile, Long> {
    @Query("select distinct a.staff from StaffBranchAssignment a where a.branch.branchId = :branch and a.role.roleName in ('BAC_SI','DOCTOR') and a.status = com.cvdcms.entity.enums.AssignmentStatus.ACTIVE and a.startDate <= :day and (a.endDate is null or a.endDate >= :day) and a.staff.status = com.cvdcms.entity.enums.StaffStatus.ACTIVE order by a.staff.fullName")
    List<StaffProfile> eligible(@Param("branch") Long branch, @Param("day") LocalDate day);
}

interface DispatchScheduleRepository extends JpaRepository<StaffSchedule, Long> {
    @Query("select count(s) from StaffSchedule s where s.staff.staffId = :doctor and s.branch.branchId = :branch and s.workDate = :day and s.status = com.cvdcms.entity.enums.ScheduleStatus.SCHEDULED and s.shift.isActive = true and s.shift.startTime <= :time and s.shift.endTime >= :time")
    long working(@Param("doctor") Long doctor, @Param("branch") Long branch, @Param("day") LocalDate day, @Param("time") LocalTime time);
}

interface DispatchRoomRepository extends JpaRepository<Room, Long> {
    List<Room> findByBranchBranchIdAndStatusOrderByRoomCode(Long branch, RoomStatus status);
}

interface DispatchChairRepository extends JpaRepository<DentalChair, Long> {
    List<DentalChair> findByBranchBranchIdOrderByChairCode(Long branch);
}

interface DispatchTicketRepository extends JpaRepository<QueueTicket, Long> {
    Optional<QueueTicket> findByVisitVisitIdAndQueueType(Long visit, QueueType type);
    @Query("select coalesce(max(t.ticketNumber), 0) from QueueTicket t where t.branch.branchId = :branch and t.queueDate = :day and t.queueType = :type and t.isPriority = :priority")
    int maxNumber(@Param("branch") Long branch, @Param("day") LocalDate day, @Param("type") QueueType type, @Param("priority") boolean priority);
    @Query("select t from QueueTicket t join fetch t.visit v join fetch v.patientProfile where t.branch.branchId = :branch and t.queueDate = :day and t.queueType = :type order by t.isEmergency desc, t.isPriority desc, t.ticketNumber")
    List<QueueTicket> board(@Param("branch") Long branch, @Param("day") LocalDate day, @Param("type") QueueType type);
}

interface DispatchBranchRepository extends JpaRepository<Branch, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Branch b where b.branchId = :id")
    Optional<Branch> lock(@Param("id") Long id);
}
