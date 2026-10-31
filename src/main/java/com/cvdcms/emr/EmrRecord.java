package com.cvdcms.emr;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.Visit;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "emr_records", uniqueConstraints = {
        @UniqueConstraint(name = "uk_emr_record_code", columnNames = "record_code"),
        @UniqueConstraint(name = "uk_emr_record_visit", columnNames = "visit_id")
}, indexes = {
        @Index(name = "idx_emr_patient_created", columnList = "profile_id,created_at"),
        @Index(name = "idx_emr_branch_status", columnList = "branch_id,status")
})
@Getter
@Setter
@NoArgsConstructor
public class EmrRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "record_code", nullable = false, length = 40)
    private String recordCode;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private EmrRecordStatus status = EmrRecordStatus.DRAFT;

    @Column(name = "current_version_no", nullable = false)
    private Integer currentVersionNumber = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @OneToMany(mappedBy = "record", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("versionNumber DESC")
    private List<EmrRecordVersion> versions = new ArrayList<>();
}
