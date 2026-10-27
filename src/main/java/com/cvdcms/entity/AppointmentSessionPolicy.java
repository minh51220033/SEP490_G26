package com.cvdcms.entity;

import com.cvdcms.entity.enums.PreferredSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "appointment_session_policies", uniqueConstraints =
        @UniqueConstraint(name = "uk_appt_session_policy", columnNames = {"branch_id", "appointment_date", "preferred_session"}))
@Getter @Setter
public class AppointmentSessionPolicy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_id")
    private Long policyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_session", nullable = false, length = 20)
    private PreferredSession preferredSession;

    @Column(name = "branch_capacity", nullable = false)
    private Integer branchCapacity;

    @Column(name = "doctor_capacity", nullable = false)
    private Integer doctorCapacity;
}
