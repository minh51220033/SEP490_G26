package com.cvdcms.repository;

import com.cvdcms.entity.AppointmentResourceReservation;
import com.cvdcms.entity.enums.ReservationResourceType;
import com.cvdcms.entity.enums.ReservationStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface AppointmentResourceReservationRepository extends JpaRepository<AppointmentResourceReservation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("""
        SELECT r FROM AppointmentResourceReservation r
        WHERE r.appointment.appointmentId IN :appointmentIds
          AND r.status = :status
        ORDER BY r.reservationId ASC
    """)
    List<AppointmentResourceReservation> lockActiveByAppointmentIdsOrderByReservationIdAsc(
            @Param("appointmentIds") Collection<Long> appointmentIds,
            @Param("status") ReservationStatus status);

    @Query("""
        SELECT r FROM AppointmentResourceReservation r
        WHERE r.branch.branchId = :branchId
          AND r.resourceType = :resourceType
          AND r.resourceId IN :resourceIds
          AND r.status = :status
          AND r.startAt < :endAt
          AND r.endAt > :startAt
        ORDER BY r.startAt ASC, r.reservationId ASC
    """)
    List<AppointmentResourceReservation> findOverlappingReservations(
            @Param("branchId") Long branchId,
            @Param("resourceType") ReservationResourceType resourceType,
            @Param("resourceIds") Collection<Long> resourceIds,
            @Param("status") ReservationStatus status,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    List<AppointmentResourceReservation> findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(
            Long appointmentId,
            ReservationStatus status);

    List<AppointmentResourceReservation> findByAppointment_AppointmentIdOrderByReservationIdAsc(Long appointmentId);

    @Query("""
        SELECT r FROM AppointmentResourceReservation r
        JOIN FETCH r.appointment a
        LEFT JOIN FETCH a.assignedRoom ar
        WHERE r.resourceType = com.cvdcms.entity.enums.ReservationResourceType.DOCTOR
          AND r.resourceId IN :staffIds
          AND r.status = com.cvdcms.entity.enums.ReservationStatus.RESERVED
          AND r.startAt < :endAt
          AND r.endAt > :startAt
        ORDER BY r.startAt ASC, r.reservationId ASC
    """)
    List<AppointmentResourceReservation> findActiveDoctorReservationsInRange(
            @Param("staffIds") Collection<Long> staffIds,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    @Query("""
        SELECT r FROM AppointmentResourceReservation r
        JOIN FETCH r.appointment a
        WHERE r.branch.branchId = :branchId
          AND r.resourceType IN :resourceTypes
          AND r.status = com.cvdcms.entity.enums.ReservationStatus.RESERVED
          AND r.startAt < :endAt
          AND r.endAt > :startAt
        ORDER BY r.startAt ASC, r.reservationId ASC
    """)
    List<AppointmentResourceReservation> findActiveBranchReservationsByTypesInRange(
            @Param("branchId") Long branchId,
            @Param("resourceTypes") Collection<ReservationResourceType> resourceTypes,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);
}
