package com.cvdcms.entity;

import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.entity.enums.VisitType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "visits",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_visit_code", columnNames = "visit_code")
        },
        indexes = {
                @Index(name = "idx_visit_branch_date", columnList = "branch_id, check_in_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Visit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "visit_id")
    private Long visitId;

    @Column(name = "visit_code", nullable = false, length = 20, unique = true)
    private String visitCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "visit_type", nullable = false, length = 20)
    private VisitType visitType = VisitType.WALK_IN;

    @Column(name = "check_in_at", nullable = false)
    private LocalDateTime checkInAt;

    @Column(name = "initial_symptoms", length = 500)
    private String initialSymptoms;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_doctor_id")
    private StaffProfile primaryDoctor;

    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "chair_id")
    private Long chairId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private VisitStatus status = VisitStatus.WAITING;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.checkInAt == null) {
            this.checkInAt = LocalDateTime.now();
        }
        if (this.visitCode == null || this.visitCode.isEmpty()) {
            this.visitCode = "VIS" + System.currentTimeMillis();
        }
    }
}
