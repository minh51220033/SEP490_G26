package com.cvdcms.repository;

import com.cvdcms.entity.PatientOdontogram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatientOdontogramRepository extends JpaRepository<PatientOdontogram, Long> {

    List<PatientOdontogram> findByPatientProfile_ProfileIdAndIsDeletedFalseOrderByChartDateDescCreatedAtDesc(Long profileId);

    Optional<PatientOdontogram> findByOdontogramIdAndIsDeletedFalse(Long odontogramId);

    long countByPatientProfile_ProfileIdAndIsDeletedFalse(Long profileId);
}
