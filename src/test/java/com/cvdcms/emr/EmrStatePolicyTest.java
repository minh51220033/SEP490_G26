package com.cvdcms.emr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmrStatePolicyTest {

    @Test
    void draftCanRefreshAndValidateButCannotSign() {
        assertTrue(EmrStatePolicy.canRefresh(EmrRecordStatus.DRAFT));
        assertTrue(EmrStatePolicy.canValidate(EmrRecordStatus.DRAFT));
        assertFalse(EmrStatePolicy.canDoctorSign(EmrRecordStatus.DRAFT));
    }

    @Test
    void readyRecordCanOnlyMoveToLockStep() {
        assertTrue(EmrStatePolicy.canLock(EmrRecordStatus.READY_TO_LOCK));
        assertFalse(EmrStatePolicy.canLock(EmrRecordStatus.DRAFT));
        assertFalse(EmrStatePolicy.canAmend(EmrRecordStatus.READY_TO_LOCK));
    }

    @Test
    void doctorCanSignLockedRecordAndLegacyPendingRecord() {
        assertTrue(EmrStatePolicy.canDoctorSign(EmrRecordStatus.PATIENT_SIGNATURE_PENDING));
        assertTrue(EmrStatePolicy.canDoctorSign(EmrRecordStatus.DOCTOR_SIGNATURE_PENDING));
    }

    @Test
    void signedRecordIsImmutableAndCanOnlyBeAmended() {
        assertFalse(EmrStatePolicy.canRefresh(EmrRecordStatus.SIGNED));
        assertFalse(EmrStatePolicy.canValidate(EmrRecordStatus.SIGNED));
        assertFalse(EmrStatePolicy.canLock(EmrRecordStatus.SIGNED));
        assertTrue(EmrStatePolicy.canAmend(EmrRecordStatus.SIGNED));
    }
}
