package com.cvdcms.emr;

public enum EmrRecordStatus {
    DRAFT,
    READY_TO_LOCK,
    PATIENT_SIGNATURE_PENDING,
    DOCTOR_SIGNATURE_PENDING,
    SIGNED,
    AMENDMENT_DRAFT
}
