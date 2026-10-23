package com.cvdcms.repository;

import com.cvdcms.entity.ClinicalPhoto;
import com.cvdcms.entity.enums.PhotoStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClinicalPhotoRepository extends JpaRepository<ClinicalPhoto, Long> {

    List<ClinicalPhoto> findByPatientProfile_ProfileIdOrderByCapturedAtDesc(Long profileId);

    List<ClinicalPhoto> findByVisit_VisitIdOrderByCapturedAtDesc(Long visitId);

    List<ClinicalPhoto> findByPatientProfile_ProfileIdAndPhotoStageOrderByCapturedAtDesc(Long profileId, PhotoStage photoStage);

    long countByPatientProfile_ProfileId(Long profileId);

    long countByPatientProfile_ProfileIdAndPhotoStage(Long profileId, PhotoStage photoStage);
}
