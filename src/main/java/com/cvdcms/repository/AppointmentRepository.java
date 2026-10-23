package com.cvdcms.repository;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.enums.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findByPatientProfile_ProfileIdOrderByAppointmentDateDesc(Long profileId);

    List<Appointment> findByDoctor_StaffIdAndAppointmentDate(Long doctorId, LocalDate appointmentDate);

    List<Appointment> findByBranch_BranchIdAndAppointmentDate(Long branchId, LocalDate appointmentDate);

    List<Appointment> findByAppointmentDate(LocalDate appointmentDate);

    List<Appointment> findByAppointmentDateBetween(LocalDate startDate, LocalDate endDate);

    long countByAppointmentDate(LocalDate appointmentDate);

    long countByAppointmentDateBetween(LocalDate startDate, LocalDate endDate);

    long countByAppointmentDateBeforeAndStatusIn(LocalDate date, List<AppointmentStatus> statuses);

    Optional<Appointment> findByAppointmentCode(String appointmentCode);

    @Query("SELECT a FROM Appointment a " +
           "LEFT JOIN FETCH a.patientProfile p " +
           "LEFT JOIN FETCH a.doctor d " +
           "LEFT JOIN FETCH a.service s " +
           "LEFT JOIN FETCH a.branch b " +
           "WHERE (:startDate IS NULL OR a.appointmentDate >= :startDate) " +
           "AND (:endDate IS NULL OR a.appointmentDate <= :endDate) " +
           "AND (:doctorId IS NULL OR a.doctor.staffId = :doctorId) " +
           "AND (:status IS NULL OR a.status = :status) " +
           "AND (:keyword IS NULL OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "     OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "     OR p.phoneNumber LIKE CONCAT('%', :keyword, '%') " +
           "     OR LOWER(a.appointmentCode) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY a.appointmentDate ASC, a.expectedTime ASC")
    List<Appointment> filterAppointments(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("doctorId") Long doctorId,
            @Param("status") AppointmentStatus status,
            @Param("keyword") String keyword
    );
}
