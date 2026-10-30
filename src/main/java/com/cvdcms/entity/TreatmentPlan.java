package com.cvdcms.entity;

import com.cvdcms.entity.enums.TreatmentPlanStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "treatment_plans",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_plan_code", columnNames = "plan_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class TreatmentPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "plan_code", nullable = false, length = 20, unique = true)
    private String planCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private StaffProfile doctor;

    @Column(name = "plan_name", nullable = false, columnDefinition = "TEXT")
    private String planName;

    @Column(name = "total_estimated_cost", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalEstimatedCost = BigDecimal.ZERO;

    @Column(name = "final_estimated_cost", nullable = false, precision = 15, scale = 2)
    private BigDecimal finalEstimatedCost = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TreatmentPlanStatus status = TreatmentPlanStatus.DRAFT;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "expected_end_date")
    private LocalDate expectedEndDate;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TreatmentPlanStage> stages = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (this.planCode == null || this.planCode.isEmpty()) {
            this.planCode = "KHDT" + System.currentTimeMillis();
        }
    }
}

