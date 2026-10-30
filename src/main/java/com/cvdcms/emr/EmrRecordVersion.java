package com.cvdcms.emr;

import com.cvdcms.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "emr_record_versions", uniqueConstraints =
        @UniqueConstraint(name = "uk_emr_record_version", columnNames = {"record_id", "version_no"}))
@Getter
@Setter
@NoArgsConstructor
public class EmrRecordVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "version_id")
    private Long versionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id", nullable = false)
    private EmrRecord record;

    @Column(name = "version_no", nullable = false)
    private Integer versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private EmrVersionStatus status = EmrVersionStatus.DRAFT;

    @Column(name = "template_code", nullable = false, length = 50)
    private String templateCode = "OUTPATIENT_DENTAL";

    @Column(name = "template_version", nullable = false)
    private Integer templateVersion = 1;

    @Column(name = "amendment_reason", length = 500)
    private String amendmentReason;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @OneToOne(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private EmrSourceSnapshot snapshot;

    @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, documentId ASC")
    private List<EmrDocument> documents = new ArrayList<>();

    @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<EmrSignature> signatures = new ArrayList<>();
}
