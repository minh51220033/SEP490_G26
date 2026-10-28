package com.cvdcms.repository;

import com.cvdcms.entity.StaffSchedule;
import com.cvdcms.entity.enums.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

// Truy vấn lịch phân ca (staff_schedules) - FT33.
@Repository
public interface StaffScheduleRepository extends JpaRepository<StaffSchedule, Long> {

    // Toàn bộ ca đã xếp của một chi nhánh trong khoảng ngày (dựng ma trận tuần SCR-33.1).
    @Query("SELECT s FROM StaffSchedule s JOIN FETCH s.staff JOIN FETCH s.shift "
            + "WHERE s.branch.branchId = :branchId AND s.workDate BETWEEN :from AND :to "
            + "ORDER BY s.workDate ASC, s.shift.startTime ASC, s.staff.fullName ASC")
    List<StaffSchedule> findByBranchAndRange(@Param("branchId") Long branchId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    // Lịch cá nhân của một nhân viên trong khoảng ngày (SCR-33.3).
    @Query("SELECT s FROM StaffSchedule s JOIN FETCH s.branch JOIN FETCH s.shift "
            + "WHERE s.staff.staffId = :staffId AND s.workDate BETWEEN :from AND :to "
            + "ORDER BY s.workDate ASC, s.shift.startTime ASC")
    List<StaffSchedule> findByStaffAndRange(@Param("staffId") Long staffId,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to);

    boolean existsByStaffStaffIdAndWorkDateAndShiftShiftId(Long staffId, LocalDate workDate, Long shiftId);

    // Danh sách ca của nhân sự trong một ngày để kiểm tra trùng giờ (SCR-33.1).
    @Query("SELECT s FROM StaffSchedule s JOIN FETCH s.branch JOIN FETCH s.shift "
            + "WHERE s.staff.staffId = :staffId AND s.workDate = :workDate AND s.status != :cancelled")
    List<StaffSchedule> findByStaffStaffIdAndWorkDateAndStatusNot(
            @Param("staffId") Long staffId,
            @Param("workDate") LocalDate workDate,
            @Param("cancelled") ScheduleStatus cancelled);

    // Bác sĩ khả dụng: có ca SCHEDULED tại chi nhánh vào ngày chỉ định (API bàn giao cho đặt lịch).
    @Query("SELECT s FROM StaffSchedule s JOIN FETCH s.staff JOIN FETCH s.shift "
            + "WHERE s.branch.branchId = :branchId AND s.workDate = :date AND s.status = :status "
            + "ORDER BY s.shift.startTime ASC, s.staff.fullName ASC")
    List<StaffSchedule> findByBranchAndDateAndStatus(@Param("branchId") Long branchId,
                                                     @Param("date") LocalDate date,
                                                     @Param("status") ScheduleStatus status);
}
