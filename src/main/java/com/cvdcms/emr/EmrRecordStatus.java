package com.cvdcms.emr;

public enum EmrRecordStatus {
    DRAFT("Bản nháp"),
    READY_TO_LOCK("Sẵn sàng khóa"),
    PATIENT_SIGNATURE_PENDING("Chờ bác sĩ ký (hồ sơ cũ)"),
    DOCTOR_SIGNATURE_PENDING("Chờ bác sĩ ký"),
    SIGNED("Đã ký thử"),
    AMENDMENT_DRAFT("Bản sửa đổi");

    private final String displayName;

    EmrRecordStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
