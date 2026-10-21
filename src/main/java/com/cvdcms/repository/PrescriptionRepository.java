package com.cvdcms.repository;

import com.cvdcms.entity.Prescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    List<Prescription> findByVisit_VisitIdOrderByCreatedAtDesc(Long visitId);

    List<Prescription> findByPatientProfile_ProfileIdOrderByCreatedAtDesc(Long profileId);
}
