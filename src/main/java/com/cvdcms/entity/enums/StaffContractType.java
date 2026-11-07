package com.cvdcms.entity.enums;

import lombok.Getter;

@Getter
public enum StaffContractType {
    FULL_CONTRACT("HĐLĐ từ 3 tháng trở lên (Biểu lũy tiến)"),
    FULL_TIME_OFFICIAL("HĐLĐ chính thức (Biểu lũy tiến)"),
    PROBATION_OR_CASUAL("Thử việc / Dưới 3 tháng (Khấu trừ 10%)"),
    FULL_TIME_PROBATION("HĐLĐ thử việc (Khấu trừ 10%)"),
    PART_TIME_HOURLY("Bán thời gian / Theo ca (Khấu trừ 10%)"),
    COLLABORATOR("Cộng tác viên / Vãng lai (Khấu trừ 10%)"),
    NON_RESIDENT("Cá nhân không cư trú (Khấu trừ 20%)");

    private final String description;

    StaffContractType(String description) {
        this.description = description;
    }
}
