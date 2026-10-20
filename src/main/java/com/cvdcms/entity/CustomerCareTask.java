package com.cvdcms.entity;

import com.cvdcms.entity.enums.CustomerCareTaskStatus;
import com.cvdcms.entity.enums.CustomerCareTaskType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "customer_care_tasks",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_cct_code", columnNames = "task_code")
        },
        indexes = {
                @Index(name = "idx_cct_assigned", columnList = "assigned_staff_id, due_date, status")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class CustomerCareTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "task_code", nullable = false, length = 20, unique = true)
    private String taskCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 30)
    private CustomerCareTaskType taskType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id")
    private Prescription prescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_staff_id")
    private StaffProfile assignedStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalated_to_doctor_id")
    private StaffProfile escalatedToDoctor;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CustomerCareTaskStatus status = CustomerCareTaskStatus.PENDING;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "doctor_response", columnDefinition = "TEXT")
    private String doctorResponse;

    @PrePersist
    public void prePersist() {
        if (this.taskCode == null || this.taskCode.isEmpty()) {
            this.taskCode = "CSKH" + System.currentTimeMillis();
        }
    }
}
