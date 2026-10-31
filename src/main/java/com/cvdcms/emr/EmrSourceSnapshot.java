package com.cvdcms.emr;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "emr_source_snapshots")
@Getter
@Setter
@NoArgsConstructor
public class EmrSourceSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "snapshot_id")
    private Long snapshotId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, unique = true)
    private EmrRecordVersion version;

    @Column(name = "source_json", nullable = false, columnDefinition = "json")
    private String sourceJson;

    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @CreationTimestamp
    @Column(name = "captured_at", nullable = false, updatable = false)
    private LocalDateTime capturedAt;
}
