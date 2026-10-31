package com.cvdcms.emr;

import java.util.EnumSet;
import java.util.Set;

public final class EmrStatePolicy {
    private EmrStatePolicy() {}

    private static final Set<EmrRecordStatus> EDITABLE =
            EnumSet.of(EmrRecordStatus.DRAFT, EmrRecordStatus.AMENDMENT_DRAFT, EmrRecordStatus.READY_TO_LOCK);

    public static boolean canRefresh(EmrRecordStatus status) {
        return EDITABLE.contains(status);
    }

    public static boolean canValidate(EmrRecordStatus status) {
        return status == EmrRecordStatus.DRAFT
                || status == EmrRecordStatus.AMENDMENT_DRAFT
                || status == EmrRecordStatus.READY_TO_LOCK;
    }

    public static boolean canLock(EmrRecordStatus status) {
        return status == EmrRecordStatus.READY_TO_LOCK;
    }

    public static boolean canPatientSign(EmrRecordStatus status) {
        return status == EmrRecordStatus.PATIENT_SIGNATURE_PENDING;
    }

    public static boolean canDoctorSign(EmrRecordStatus status) {
        return status == EmrRecordStatus.DOCTOR_SIGNATURE_PENDING;
    }

    public static boolean canAmend(EmrRecordStatus status) {
        return status == EmrRecordStatus.SIGNED;
    }
}
