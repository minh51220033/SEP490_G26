package com.cvdcms.repository;

import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.enums.StaffStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// Truy vấn hồ sơ nhân sự (staff_profiles) - FT41 & Clinical modules.
@Repository
public interface StaffProfileRepository extends JpaRepository<StaffProfile, Long> {

    boolean existsByStaffCode(String staffCode);

    boolean existsByIdCardNumber(String idCardNumber);

    boolean existsByPracticeLicenseNo(String practiceLicenseNo);

    boolean existsByUserUserId(Long userId);

    Optional<StaffProfile> findByStaffCode(String staffCode);

    List<StaffProfile> findByStatus(StaffStatus status);

    Optional<StaffProfile> findByIdCardNumber(String idCardNumber);

    Optional<StaffProfile> findByPracticeLicenseNo(String practiceLicenseNo);

    Optional<StaffProfile> findByUserUserId(Long userId);

    Optional<StaffProfile> findByUser_UserId(Long userId);

    List<StaffProfile> findAllByOrderByStaffCodeAsc();

    // Danh sách chuyên khoa đang có để đổ vào bộ lọc.
    @Query("SELECT DISTINCT s.specialty FROM StaffProfile s "
            + "WHERE s.specialty IS NOT NULL AND s.specialty <> '' ORDER BY s.specialty")
    List<String> findDistinctSpecialties();

    // Danh sách vị trí công việc đang có để đổ vào bộ lọc nâng cao
    @Query("SELECT DISTINCT s.positionTitle FROM StaffProfile s WHERE s.positionTitle IS NOT NULL AND s.positionTitle != '' ORDER BY s.positionTitle")
    List<String> findDistinctPositionTitles();

    /**
     * Khóa bi quan dòng hồ sơ bác sĩ làm chốt tài nguyên chung cho mọi giao dịch đặt lịch
     * của cùng bác sĩ đó (bất kể bác sĩ trực nhiều ca hay chưa có lịch phân ca trong ngày).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT s FROM StaffProfile s WHERE s.staffId = :staffId")
    Optional<StaffProfile> lockById(@Param("staffId") Long staffId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT s FROM StaffProfile s WHERE s.staffId IN :staffIds ORDER BY s.staffId ASC")
    List<StaffProfile> findByIdInForUpdateOrderByStaffIdAsc(@Param("staffIds") java.util.Collection<Long> staffIds);
}
