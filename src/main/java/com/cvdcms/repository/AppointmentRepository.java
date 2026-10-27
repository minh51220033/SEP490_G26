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

/**
 * Repository truy vấn lịch hẹn (appointments) phục vụ liên kết và tổng hợp công việc CSKH (FT44).
 * Sử dụng INNER JOIN FETCH cho patientProfile & branch và kiểm tra tính toàn vẹn khoá ngoại
 * để tránh lỗi EntityNotFoundException khi dữ liệu test có khoá ngoại mồ côi.
 */
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
    /**
     * Lấy danh sách lịch hẹn hợp lệ theo chi nhánh kèm thông tin bệnh nhân, bác sĩ và dịch vụ.
     */
    @Query("""
        SELECT a
        FROM Appointment a
        JOIN FETCH a.patientProfile p
        JOIN FETCH a.branch b
        LEFT JOIN FETCH a.doctor d
        LEFT JOIN FETCH a.service srv
        WHERE (:branchId IS NULL OR b.branchId = :branchId)
          AND (a.doctor IS NULL OR d.staffId IS NOT NULL)
          AND (a.service IS NULL OR srv.serviceId IS NOT NULL)
        ORDER BY a.appointmentDate DESC, a.appointmentId DESC
        """)
    List<Appointment> findByBranchWithDetails(@Param("branchId") Long branchId);

    /**
     * Lấy danh sách lịch hẹn hợp lệ của một bệnh nhân cụ thể.
     */
    @Query("""
        SELECT a
        FROM Appointment a
        JOIN FETCH a.patientProfile p
        JOIN FETCH a.branch b
        LEFT JOIN FETCH a.doctor d
        LEFT JOIN FETCH a.service srv
        WHERE p.profileId = :profileId
          AND (a.doctor IS NULL OR d.staffId IS NOT NULL)
          AND (a.service IS NULL OR srv.serviceId IS NOT NULL)
        ORDER BY a.appointmentDate DESC, a.appointmentId DESC
        """)
    List<Appointment> findByProfileIdWithDetails(@Param("profileId") Long profileId);
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
