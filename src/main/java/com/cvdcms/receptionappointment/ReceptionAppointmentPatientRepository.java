package com.cvdcms.receptionappointment;

import com.cvdcms.entity.PatientProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReceptionAppointmentPatientRepository extends JpaRepository<PatientProfile, Long> {
    @Query("""
        SELECT p FROM PatientProfile p WHERE p.status = 'ACTIVE'
          AND (p.preferredBranch.branchId = :branchId OR EXISTS
              (SELECT a.appointmentId FROM Appointment a WHERE a.patientProfile = p AND a.branch.branchId = :branchId))
          AND (LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :query, '%'))
               OR p.phoneNumber LIKE CONCAT('%', :query, '%'))
        ORDER BY p.fullName, p.profileId
        """)
    List<PatientProfile> search(@Param("branchId") Long branchId, @Param("query") String query);

    @Query("""
        SELECT p FROM PatientProfile p WHERE p.profileId = :id AND p.status = 'ACTIVE'
          AND (p.preferredBranch.branchId = :branchId OR EXISTS
              (SELECT a.appointmentId FROM Appointment a WHERE a.patientProfile = p AND a.branch.branchId = :branchId))
        """)
    Optional<PatientProfile> findBookable(@Param("id") Long id, @Param("branchId") Long branchId);
}
