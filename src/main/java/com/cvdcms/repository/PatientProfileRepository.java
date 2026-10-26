
package com.cvdcms.repository;

import com.cvdcms.entity.PatientProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatientProfileRepository
        extends JpaRepository<PatientProfile, Long> {

    // Kiểm tra người dùng đã có hồ sơ mặc định hay chưa.
    boolean existsByUser_UserIdAndIsDefaultTrue(Long userId);
    //Bổ sung phương thức lấy hồ sơ mặc định của tài khoản:
    Optional<PatientProfile> findFirstByUser_UserIdAndIsDefaultTrue(Long userId);

    //Lấy danh sách tất cả hồ sơ thuộc tài khoản đang đăng nhập.
    //Chỉ tìm được hồ sơ nếu hồ sơ đó thuộc đúng tài khoản.
    //Kiểm tra tài khoản đã có hồ sơ nào chưa.
    List<PatientProfile> findByUser_UserIdOrderByIsDefaultDescProfileIdDesc(
            Long userId);

    Optional<PatientProfile> findByProfileIdAndUser_UserId(
            Long profileId,
            Long userId);

    boolean existsByUser_UserId(Long userId);

    List<PatientProfile> findAllByUser_UserId(Long userId);

    @org.springframework.data.jpa.repository.Query("""
    SELECT p FROM PatientProfile p
    WHERE LOWER(p.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
       OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
       OR p.phoneNumber LIKE CONCAT('%', :keyword, '%')
       OR p.profileId = :profileId
    ORDER BY p.profileId DESC
    """)
    List<PatientProfile> searchPatientsByKeywordAndId(
            @org.springframework.data.repository.query.Param("keyword")
            String keyword,

            @org.springframework.data.repository.query.Param("profileId")
            Long profileId
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

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM PatientProfile p WHERE p.profileId = :profileId")
    Optional<PatientProfile> findByIdForUpdate(@org.springframework.data.repository.query.Param("profileId") Long profileId);
}
