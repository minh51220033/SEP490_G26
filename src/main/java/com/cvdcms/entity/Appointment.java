package com.cvdcms.entity;

import com.cvdcms.entity.enums.AppointmentSource;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.PreferredSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(
        name = "appointments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_appt_code", columnNames = "appointment_code")
        },
        indexes = {
                @Index(name = "idx_appt_branch_date", columnList = "branch_id, appointment_date, status")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "appointment_code", nullable = false, length = 20, unique = true)
    private String appointmentCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private StaffProfile doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private Service service;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_session", nullable = false, length = 20)
    private PreferredSession preferredSession = PreferredSession.ANY;

    @Column(name = "expected_time")
    private LocalTime expectedTime;

    @Column(name = "initial_symptoms", length = 500)
    private String initialSymptoms;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private AppointmentSource source = AppointmentSource.ONLINE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AppointmentStatus status = AppointmentStatus.PENDING;

    @Column(name = "reschedule_count", nullable = false)
    private Integer rescheduleCount = 0;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.appointmentCode == null || this.appointmentCode.isEmpty()) {
            this.appointmentCode = "LH" + System.currentTimeMillis();
        }
    }
}
