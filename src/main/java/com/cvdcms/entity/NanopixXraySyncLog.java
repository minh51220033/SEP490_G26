package com.cvdcms.entity;

import com.cvdcms.entity.enums.NanopixSyncStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "nanopix_xray_sync_logs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_nanopix_uid", columnNames = "external_study_uid")
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

    @Column(name = "external_study_uid", nullable = false, length = 100, unique = true)
    private String externalStudyUid;

    @Column(name = "external_patient_id", length = 50)
    private String externalPatientId;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 20)
    private NanopixSyncStatus syncStatus = NanopixSyncStatus.UNMATCHED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_item_id")
    private ParaclinicalOrderItem matchedItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_profile_id")
    private PatientProfile matchedProfile;

    @Column(name = "is_manual_linked", nullable = false)
    private Boolean isManualLinked = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_by")
    private StaffProfile linkedBy;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @CreationTimestamp
    @Column(name = "sync_time", nullable = false, updatable = false)
    private LocalDateTime syncTime;
}
