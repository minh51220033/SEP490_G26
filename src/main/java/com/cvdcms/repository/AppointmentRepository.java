package com.cvdcms.repository;

import com.cvdcms.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository truy vấn lịch hẹn (appointments) phục vụ liên kết và tổng hợp công việc CSKH (FT44).
 * Sử dụng INNER JOIN FETCH cho patientProfile & branch và kiểm tra tính toàn vẹn khoá ngoại
 * để tránh lỗi EntityNotFoundException khi dữ liệu test có khoá ngoại mồ côi.
 */
@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

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
}
