package com.cvdcms.repository;
import com.cvdcms.entity.PatientProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
public interface ReceptionProfileRepository extends JpaRepository<PatientProfile, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PatientProfile p WHERE p.profileId = :id") Optional<PatientProfile> lockById(@Param("id") Long id);
    @Query("""
            SELECT p FROM PatientProfile p WHERE p.status = 'ACTIVE'
            ORDER BY p.profileId DESC""")
    List<PatientProfile> findRecentActive(Pageable page);

    @Query("""
            SELECT p FROM PatientProfile p
            LEFT JOIN p.preferredBranch pb
            WHERE p.status = 'ACTIVE'
              AND (
                  pb.branchId IN :branchIds
                  OR EXISTS (
                      SELECT 1 FROM Appointment a
                      WHERE a.patientProfile.profileId = p.profileId
                        AND a.branch.branchId IN :branchIds
                  )
                  OR EXISTS (
                      SELECT 1 FROM Visit v
                      WHERE v.patientProfile.profileId = p.profileId
                        AND v.branch.branchId IN :branchIds
                  )
              )
            ORDER BY p.profileId DESC""")
    List<PatientProfile> findRecentActiveByBranches(@Param("branchIds") Collection<Long> branchIds, Pageable page);

    @Query("""
            SELECT p FROM PatientProfile p WHERE p.status = 'ACTIVE' AND
            (LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :term, '%'))
            OR p.phoneNumber LIKE CONCAT('%', :term, '%') OR p.idCardNumber LIKE CONCAT('%', :term, '%'))
            ORDER BY p.fullName, p.profileId""")
    List<PatientProfile> search(@Param("term") String term, Pageable page);

    @Query("""
            SELECT p FROM PatientProfile p
            LEFT JOIN p.preferredBranch pb
            WHERE p.status = 'ACTIVE'
              AND (
                  pb.branchId IN :branchIds
                  OR EXISTS (
                      SELECT 1 FROM Appointment a
                      WHERE a.patientProfile.profileId = p.profileId
                        AND a.branch.branchId IN :branchIds
                  )
                  OR EXISTS (
                      SELECT 1 FROM Visit v
                      WHERE v.patientProfile.profileId = p.profileId
                        AND v.branch.branchId IN :branchIds
                  )
              )
              AND (LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :term, '%'))
                   OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :term, '%'))
                   OR p.phoneNumber LIKE CONCAT('%', :term, '%')
                   OR (p.idCardNumber IS NOT NULL AND p.idCardNumber LIKE CONCAT('%', :term, '%')))
            ORDER BY p.fullName, p.profileId""")
    List<PatientProfile> searchByBranches(@Param("branchIds") Collection<Long> branchIds,
                                            @Param("term") String term,
                                            Pageable page);

    @Query("""
            SELECT p FROM PatientProfile p
            LEFT JOIN FETCH p.preferredBranch pb
            WHERE p.status = 'ACTIVE'
              AND p.phoneNumber = :phone
            ORDER BY p.profileId""")
    List<PatientProfile> findExactActiveByPhone(@Param("phone") String phone);

    @Query("""
            SELECT p FROM PatientProfile p
            LEFT JOIN FETCH p.preferredBranch pb
            WHERE p.status = 'ACTIVE'
              AND p.idCardNumber = :idCard
            ORDER BY p.profileId""")
    List<PatientProfile> findExactActiveByIdCard(@Param("idCard") String idCard);

    @Query("""
            SELECT p FROM PatientProfile p
            LEFT JOIN FETCH p.preferredBranch pb
            WHERE p.status = 'ACTIVE'
              AND LOWER(p.patientCode) = LOWER(:patientCode)
            ORDER BY p.profileId""")
    List<PatientProfile> findExactActiveByPatientCode(@Param("patientCode") String patientCode);

    @Query("""
            SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
            FROM PatientProfile p
            LEFT JOIN p.preferredBranch pb
            WHERE p.profileId = :profileId
              AND p.status = 'ACTIVE'
              AND (
                  pb.branchId IN :branchIds
                  OR EXISTS (
                      SELECT 1 FROM Appointment a
                      WHERE a.patientProfile.profileId = p.profileId
                        AND a.branch.branchId IN :branchIds
                  )
                  OR EXISTS (
                      SELECT 1 FROM Visit v
                      WHERE v.patientProfile.profileId = p.profileId
                        AND v.branch.branchId IN :branchIds
                  )
              )""")
    boolean isProfileAccessibleInBranches(@Param("profileId") Long profileId,
                                          @Param("branchIds") Collection<Long> branchIds);
}
