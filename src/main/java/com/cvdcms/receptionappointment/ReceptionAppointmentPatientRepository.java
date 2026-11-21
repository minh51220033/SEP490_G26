package com.cvdcms.receptionappointment;

import com.cvdcms.entity.PatientProfile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReceptionAppointmentPatientRepository extends JpaRepository<PatientProfile, Long> {
    @Query("""
        SELECT p FROM PatientProfile p WHERE p.status = 'ACTIVE'
        ORDER BY p.profileId DESC
        """)
    List<PatientProfile> findRecentBookable(Pageable pageable);

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
        ORDER BY p.profileId DESC
        """)
    List<PatientProfile> findRecentBookableByBranches(@Param("branchIds") Collection<Long> branchIds, Pageable pageable);

    @Query("""
        SELECT p FROM PatientProfile p WHERE p.status = 'ACTIVE'
          AND (LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
               OR p.phoneNumber LIKE CONCAT('%', :query, '%')
               OR (p.idCardNumber IS NOT NULL AND p.idCardNumber LIKE CONCAT('%', :query, '%')))
        ORDER BY p.fullName, p.profileId
        """)
    List<PatientProfile> search(@Param("query") String query);

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
          AND (LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
               OR p.phoneNumber LIKE CONCAT('%', :query, '%')
               OR (p.idCardNumber IS NOT NULL AND p.idCardNumber LIKE CONCAT('%', :query, '%')))
        ORDER BY p.fullName, p.profileId
        """)
    List<PatientProfile> searchByBranches(@Param("branchIds") Collection<Long> branchIds,
                                            @Param("query") String query,
                                            Pageable pageable);

    @Query("""
        SELECT p FROM PatientProfile p
        LEFT JOIN FETCH p.preferredBranch pb
        WHERE p.status = 'ACTIVE'
          AND p.phoneNumber = :phone
        ORDER BY p.profileId
        """)
    List<PatientProfile> findExactActiveByPhone(@Param("phone") String phone);

    @Query("""
        SELECT p FROM PatientProfile p
        LEFT JOIN FETCH p.preferredBranch pb
        WHERE p.status = 'ACTIVE'
          AND p.phoneNumber IN :phones
        ORDER BY p.profileId
        """)
    List<PatientProfile> findExactActiveByPhones(@Param("phones") Collection<String> phones);

    @Query("""
        SELECT p FROM PatientProfile p
        LEFT JOIN FETCH p.preferredBranch pb
        WHERE p.status = 'ACTIVE'
          AND p.idCardNumber = :idCard
        ORDER BY p.profileId
        """)
    List<PatientProfile> findExactActiveByIdCard(@Param("idCard") String idCard);

    @Query("""
        SELECT p FROM PatientProfile p
        LEFT JOIN FETCH p.preferredBranch pb
        WHERE p.status = 'ACTIVE'
          AND LOWER(p.patientCode) = LOWER(:patientCode)
        ORDER BY p.profileId
        """)
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
          )
        """)
    boolean isProfileAccessibleInBranches(@Param("profileId") Long profileId,
                                          @Param("branchIds") Collection<Long> branchIds);

    @Query("""
        SELECT p FROM PatientProfile p WHERE p.profileId = :id AND p.status = 'ACTIVE'
        """)
    Optional<PatientProfile> findBookable(@Param("id") Long id);
}
