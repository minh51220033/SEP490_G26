package com.cvdcms.entity.enums;

import lombok.Getter;

@Getter
public enum InsuranceParticipationStatus {
    UNVERIFIED("Chưa xác minh hồ sơ bảo hiểm"),
    PARTICIPATING("Tham gia bảo hiểm bắt buộc"),
    EXEMPT("Miễn / Không thuộc diện tham gia");

    private final String description;

    InsuranceParticipationStatus(String description) {
        this.description = description;
    }
}
