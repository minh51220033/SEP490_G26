package com.cvdcms.entity.enums;

public enum ServiceType {
    PROCEDURE("Thủ thuật nha khoa"),
    LAB("Xét nghiệm"),
    XRAY("X-Ray"),
    CONSULTATION("Khám / Tư vấn"),
    PRODUCT("Sản phẩm");

    private final String label;

    ServiceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isProduct() {
        return this == PRODUCT;
    }
}
