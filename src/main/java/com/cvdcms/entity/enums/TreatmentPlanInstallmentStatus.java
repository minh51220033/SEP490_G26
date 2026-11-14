package com.cvdcms.entity.enums;

public enum TreatmentPlanInstallmentStatus {
    PENDING("Chưa thanh toán", "badge bg-secondary text-white"),
    PARTIAL("Đã đóng một phần", "badge bg-warning text-dark"),
    PAID("Đã hoàn thành đợt", "badge bg-success text-white"),
    OVERDUE("Quá hạn", "badge bg-danger text-white");

    private final String displayName;
    private final String badgeClass;

    TreatmentPlanInstallmentStatus(String displayName, String badgeClass) {
        this.displayName = displayName;
        this.badgeClass = badgeClass;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBadgeClass() {
        return badgeClass;
    }
}

