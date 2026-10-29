package com.cvdcms.reception;
import com.cvdcms.entity.PatientProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
public interface ReceptionProfileRepository extends JpaRepository<PatientProfile, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PatientProfile p WHERE p.profileId = :id") Optional<PatientProfile> lockById(@Param("id") Long id);
    @Query("""
            SELECT p FROM PatientProfile p WHERE p.status = 'ACTIVE' AND
            (LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :term, '%')) OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :term, '%'))
            OR p.phoneNumber LIKE CONCAT('%', :term, '%') OR p.idCardNumber LIKE CONCAT('%', :term, '%'))
            ORDER BY p.fullName, p.profileId""")
    List<PatientProfile> search(@Param("term") String term, org.springframework.data.domain.Pageable page);
}
