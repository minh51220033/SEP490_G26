package com.cvdcms.repository;

import com.cvdcms.entity.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// Truy vấn dữ liệu chấm công (attendance_records) - FT33 (SCR-33.2, SCR-33.4).
@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    Optional<AttendanceRecord> findByStaffStaffIdAndWorkDate(Long staffId, LocalDate workDate);

    // Lịch sử chấm công cá nhân trong khoảng ngày.
    @Query("SELECT a FROM AttendanceRecord a JOIN FETCH a.branch "
            + "WHERE a.staff.staffId = :staffId AND a.workDate BETWEEN :from AND :to "
            + "ORDER BY a.workDate DESC")
    List<AttendanceRecord> findByStaffAndRange(@Param("staffId") Long staffId,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    // Lịch sử chấm công toàn chi nhánh trong khoảng ngày.
    @Query("SELECT a FROM AttendanceRecord a JOIN FETCH a.staff "
            + "WHERE a.branch.branchId = :branchId AND a.workDate BETWEEN :from AND :to "
            + "ORDER BY a.workDate DESC, a.staff.fullName ASC")
    List<AttendanceRecord> findByBranchAndRange(@Param("branchId") Long branchId,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);
}
