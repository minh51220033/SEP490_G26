package com.cvdcms.repository;

import com.cvdcms.entity.PatientProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface PatientProfileRepository extends JpaRepository<PatientProfile, Long> {

    // Kiểm tra người dùng đã có hồ sơ mặc định hay chưa
    boolean existsByUser_UserIdAndIsDefaultTrue(Long userId);

    // Lấy hồ sơ mặc định của tài khoản
    Optional<PatientProfile> findFirstByUser_UserIdAndIsDefaultTrue(Long userId);

    // Lấy hồ sơ mặc định của tài khoản có trạng thái chỉ định (ví dụ "ACTIVE")
    Optional<PatientProfile> findFirstByUser_UserIdAndIsDefaultTrueAndStatus(Long userId, String status);

    Optional<PatientProfile> findByPatientCode(String patientCode);

    // Lấy danh sách tất cả hồ sơ thuộc tài khoản đang đăng nhập
    List<PatientProfile> findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(Long userId);

    Optional<PatientProfile> findByProfileIdAndUser_UserId(Long profileId, Long userId);

    boolean existsByUser_UserId(Long userId);

    List<PatientProfile> findAllByUser_UserId(Long userId);

    @Query("""
    SELECT p FROM PatientProfile p
    WHERE LOWER(p.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
       OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
       OR p.phoneNumber LIKE CONCAT('%', :keyword, '%')
       OR p.profileId = :profileId
    ORDER BY p.profileId DESC
    """)
    List<PatientProfile> searchPatientsByKeywordAndId(
            @Param("keyword") String keyword,
            @Param("profileId") Long profileId
    );

    default List<PatientProfile> searchPatients(String query) {
        String keyword = query == null ? "" : query.trim();
        Long profileId = null;

        try {
            profileId = Long.valueOf(keyword);
        } catch (NumberFormatException ignored) {
            // Từ khóa không phải ID: vẫn tìm theo tên, mã và số điện thoại.
        }

        return searchPatientsByKeywordAndId(keyword, profileId);
    }

    List<PatientProfile> findTop15ByOrderByProfileIdDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PatientProfile p WHERE p.profileId = :profileId")
    Optional<PatientProfile> findByIdForUpdate(@Param("profileId") Long profileId);
}
