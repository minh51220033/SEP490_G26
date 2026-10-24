package com.cvdcms.repository;

import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.enums.StaffStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
