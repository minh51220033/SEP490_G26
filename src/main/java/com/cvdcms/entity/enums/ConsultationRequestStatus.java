package com.cvdcms.entity.enums;

public enum ConsultationRequestStatus {
    NEW,
    CONTACTED,
    CONVERTED,
    CLOSED,
    /** Giữ lại để tương thích ngược với dữ liệu/enum cũ ở Đợt 2; được xử lý tương đương CLOSED. */
    CANCELLED;

    public boolean isClosedEquivalent() {
        return this == CLOSED || this == CANCELLED;
    }

    public boolean isTerminal() {
        return this == CONVERTED || this == CLOSED || this == CANCELLED;
    }
}
