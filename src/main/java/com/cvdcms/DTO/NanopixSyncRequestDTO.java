package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * DTO nhận dữ liệu ảnh chụp từ máy NanoPix hoặc form nạp dữ liệu kiểm soát (Controlled Ingestion).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NanopixSyncRequestDTO {

    /**
     * Mã nghiên cứu / mã tham chiếu duy nhất từ máy Eighteeth NanoPix (bắt buộc để chống trùng).
     * Ví dụ: 20261025_193542_kim_ngoc
     */
    private String externalStudyUid;

    /**
     * Tên bệnh nhân hiển thị trên phần mềm NanoPix (nhập tại máy chụp).
     */
    private String rawPatientName;

    /**
     * Mã bệnh nhân nếu nguồn có gõ đúng mã BN của CV-DCMS.
     */
    private String externalPatientId;

    /**
     * Thời điểm chụp phim X-quang trên cảm biến.
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime capturedAt;

    /**
     * Số serial sensor NanoPix (ví dụ: EN1E0408A032).
     */
    private String deviceSerialNumber;

    /**
     * Model máy cảm biến (ví dụ: NanoPix1).
     */
    private String deviceModel;

    /**
     * Vị trí răng (ví dụ: 18, 21, 36, 47...).
     */
    private String toothNumbers;

    /**
     * Ghi chú từ kỹ thuật viên chụp X-quang.
     */
    private String notes;

    /**
     * URL ảnh nếu nguồn đã lưu trữ ở một kho nội bộ.
     */
    private String imageUrl;
}
