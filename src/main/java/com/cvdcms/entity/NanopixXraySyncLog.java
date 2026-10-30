package com.cvdcms.entity;

import com.cvdcms.entity.enums.NanopixSyncStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Quản lý kết quả phim X-quang tích hợp máy cảm biến NanoPix (FT12):
 * - Lưu vết đồng bộ từ phần mềm máy chụp Eighteeth NanoPix
 * - Lưu thông tin định danh máy (Study UID, Serial Number)
 * - Quản lý trạng thái khớp hồ sơ (MATCHED, UNMATCHED, ERROR)
 * - Lưu vết người thực hiện liên kết thủ công (linked_by, linked_at)
 */
@Entity
@Table(
        name = "nanopix_xray_sync_logs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_nanopix_uid", columnNames = "external_study_uid")
        },
        indexes = {
                @Index(name = "idx_nanopix_sync_status", columnList = "sync_status"),
                @Index(name = "idx_nanopix_captured_at", columnList = "captured_at"),
                @Index(name = "idx_nanopix_profile", columnList = "matched_profile_id"),
                @Index(name = "idx_nanopix_visit", columnList = "matched_visit_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class NanopixXraySyncLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sync_log_id")
    private Long syncLogId;

    /**
     * Mã nghiên cứu / mã tham chiếu duy nhất từ phần mềm Eighteeth NanoPix (Study UID / Mã chụp).
     * Ví dụ: 20261025_193542_kim_ngoc hoặc study UID phát sinh từ máy.
     */
    @Column(name = "external_study_uid", nullable = false, length = 100, unique = true)
    private String externalStudyUid;

    /**
     * Mã bệnh nhân trên phần mềm nguồn NanoPix (nếu có nhập trên máy).
     */
    @Column(name = "external_patient_id", length = 50)
    private String externalPatientId;

    /**
     * Tên bệnh nhân hiển thị nguyên bản trên phần mềm NanoPix (phục vụ đối chiếu).
     */
    @Column(name = "raw_patient_name", length = 100)
    private String rawPatientName;

    /**
     * Đường dẫn lưu trữ ảnh X-quang an toàn trên hệ thống.
     */
    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    /**
     * Trạng thái đồng bộ & khớp hồ sơ: MATCHED, UNMATCHED, ERROR.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 20)
    private NanopixSyncStatus syncStatus = NanopixSyncStatus.UNMATCHED;

    /**
     * Hồ sơ bệnh nhân đã được liên kết trong hệ thống CV-DCMS.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_profile_id")
    private PatientProfile matchedProfile;

    /**
     * Ca khám (Visit) đã được liên kết trong hệ thống CV-DCMS.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_visit_id")
    private Visit matchedVisit;

    /**
     * Co so / Chi nhanh thuc hien chup X-quang hoac lien ket.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /**
     * Chỉ định cận lâm sàng X-quang tương ứng trong ca khám (nếu có).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_item_id")
    private ParaclinicalOrderItem matchedItem;

    /**
     * Vị trí răng chụp (ví dụ: Răng 18, 21, 36, 47...).
     */
    @Column(name = "tooth_numbers", length = 50)
    private String toothNumbers;

    /**
     * Cờ đánh dấu đã được liên kết thủ công bởi nhân viên/bác sĩ.
     */
    @Column(name = "is_manual_linked", nullable = false)
    private Boolean isManualLinked = false;

    /**
     * Nhân viên thực hiện đối chiếu / liên kết thủ công.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_by")
    private StaffProfile linkedBy;

    /**
     * Thời điểm thực hiện đối chiếu / liên kết thủ công.
     */
    @Column(name = "linked_at")
    private LocalDateTime linkedAt;

    /**
     * Thời điểm chụp ảnh trên máy cảm biến NanoPix.
     */
    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    /**
     * Số serial của cảm biến NanoPix (ví dụ: EN1E0408A032).
     */
    @Column(name = "device_serial_number", length = 100)
    private String deviceSerialNumber = "EN1E0408A032";

    /**
     * Model cảm biến NanoPix (ví dụ: NanoPix1).
     */
    @Column(name = "device_model", length = 50)
    private String deviceModel = "NanoPix1";

    /**
     * Thông báo lỗi kết nối hoặc nguyên nhân chưa khớp đối chiếu.
     */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /**
     * Ghi chú chuyên môn hoặc giải thích đối chiếu.
     */
    @Column(name = "notes", length = 500)
    private String notes;

    /**
     * Thời điểm hệ thống nhận và ghi nhận log đồng bộ.
     */
    @CreationTimestamp
    @Column(name = "sync_time", nullable = false, updatable = false)
    private LocalDateTime syncTime;
}
