package com.cvdcms.repository;

import com.cvdcms.entity.PatientProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
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

    List<PatientProfile> findByPhoneNumber(String phoneNumber);

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

    @Query("""
        SELECT p FROM PatientProfile p
        LEFT JOIN FETCH p.membershipTier t
        LEFT JOIN FETCH p.preferredBranch b
        WHERE LOWER(p.status) = 'active'
          AND (:branchId IS NULL OR b.branchId = :branchId)
          AND (
              :keyword IS NULL OR :keyword = ''
              OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR p.phoneNumber LIKE CONCAT('%', :keyword, '%')
          )
        ORDER BY p.profileId DESC
    """)
    List<PatientProfile> searchActivePatientsForLoyalty(
            @Param("keyword") String keyword,
            @Param("branchId") Long branchId
    );

    @Query("""
        SELECT COUNT(p) FROM PatientProfile p
        WHERE (:branchIds IS NULL OR p.preferredBranch.branchId IN :branchIds)
          AND (:startDate IS NULL OR p.createdAt >= :startDate)
          AND (:endDate IS NULL OR p.createdAt <= :endDate)
    """)
    long countNewPatientsByBranchesAndPeriod(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startDate") java.time.LocalDateTime startDate,
            @Param("endDate") java.time.LocalDateTime endDate
    );
}
