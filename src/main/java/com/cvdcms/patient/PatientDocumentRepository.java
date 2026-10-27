package com.cvdcms.patient;

import com.cvdcms.entity.PatientDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PatientDocumentRepository extends JpaRepository<PatientDocument, Long> {
    List<PatientDocument> findByPatientProfile_ProfileIdOrderByUploadedAtDesc(Long profileId);

    Optional<PatientDocument> findByDocumentIdAndPatientProfile_ProfileId(Long documentId, Long profileId);
}
