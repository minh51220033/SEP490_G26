package com.cvdcms.repository;

import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.enums.StaffStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StaffProfileRepository extends JpaRepository<StaffProfile, Long> {
    Optional<StaffProfile> findByStaffCode(String staffCode);
    List<StaffProfile> findByStatus(StaffStatus status);

    /**
     * Tìm hồ sơ nhân viên theo ID tài khoản User được liên kết.
     *
     * StaffProfile phải có thuộc tính user và thuộc tính user
     * phải có thuộc tính userId.
     */
    Optional<StaffProfile> findByUser_UserId(Long userId);
}
