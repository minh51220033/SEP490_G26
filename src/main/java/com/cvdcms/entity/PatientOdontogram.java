package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "patient_odontograms",
        indexes = {
                @Index(name = "idx_odontogram_profile_date", columnList = "profile_id, is_deleted, chart_date, created_at"),
                @Index(name = "idx_odontogram_visit", columnList = "visit_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class PatientOdontogram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "odontogram_id")
    private Long odontogramId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @Column(name = "chart_date", nullable = false)
    private LocalDate chartDate;

    @Column(name = "chart_type", nullable = false, length = 16)
    private String chartType = "ADULT";

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(name = "status_count", nullable = false)
    private Integer statusCount = 0;

    @Column(name = "plan_count", nullable = false)
    private Integer planCount = 0;

    @Column(name = "affected_teeth_count", nullable = false)
    private Integer affectedTeethCount = 0;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "updated_by_name", length = 150)
    private String updatedByName;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    @PrePersist
    public void prePersist() {
        if (this.chartDate == null) {
            this.chartDate = LocalDate.now();
        }
        if (this.chartType == null || this.chartType.isBlank()) {
            this.chartType = "ADULT";
        }
        if (this.payloadJson == null || this.payloadJson.isBlank()) {
            this.payloadJson = "{}";
        }
        if (this.statusCount == null) {
            this.statusCount = 0;
        }
        if (this.planCount == null) {
            this.planCount = 0;
        }
        if (this.affectedTeethCount == null) {
            this.affectedTeethCount = 0;
        }
        if (this.isDeleted == null) {
            this.isDeleted = false;
        }
    }
}
