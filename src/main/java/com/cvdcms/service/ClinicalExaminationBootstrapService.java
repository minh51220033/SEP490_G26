package com.cvdcms.service;

import com.cvdcms.entity.ClinicalExamination;
import com.cvdcms.entity.StaffProfile;

public interface ClinicalExaminationBootstrapService {

    ClinicalExamination ensureForAssignedVisit(Long visitId);

    void syncDoctorOnQueueTransfer(Long visitId, StaffProfile newDoctor);

    boolean cleanupOnRevertCheckIn(Long visitId);
}
