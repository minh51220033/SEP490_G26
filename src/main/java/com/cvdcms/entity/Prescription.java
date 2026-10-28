package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "prescriptions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_presc_code", columnNames = "prescription_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Prescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "prescription_id")
    private Long prescriptionId;

    @Column(name = "prescription_code", nullable = false, length = 20, unique = true)
    private String prescriptionCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private StaffProfile doctor;

    @Column(name = "diagnosis_text", nullable = false, length = 255)
    private String diagnosisText;

    @Column(name = "advice_notes", columnDefinition = "TEXT")
    private String adviceNotes;

    @Column(name = "followup_date")
    private LocalDate followupDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private com.cvdcms.entity.enums.PrescriptionStatus status = com.cvdcms.entity.enums.PrescriptionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "purchase_choice", length = 30)
    private com.cvdcms.entity.enums.PrescriptionPurchaseChoice purchaseChoice;

    @Column(name = "choice_note", length = 255)
    private String choiceNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by")
    private StaffProfile handledBy;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "prescription", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PrescriptionItem> items = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (this.prescriptionCode == null || this.prescriptionCode.isEmpty()) {
            this.prescriptionCode = "TOA" + System.currentTimeMillis();
        }
    }
}
