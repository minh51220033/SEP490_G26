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
        name = "doctor_shift_replacement_logs",
        indexes = {
                @Index(name = "idx_dsrl_branch_date", columnList = "branch_id, work_date"),
                @Index(name = "idx_dsrl_orig_staff", columnList = "replaced_for_staff_id, work_date"),
                @Index(name = "idx_dsrl_repl_staff", columnList = "replacement_staff_id, work_date")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class DoctorShiftReplacementLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "replacement_log_id")
    private Long replacementLogId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    private WorkShift shift;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "replaced_for_staff_id", nullable = false)
    private StaffProfile replacedForStaff;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "replacement_staff_id", nullable = false)
    private StaffProfile replacementStaff;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_shift_assignment_id", nullable = false)
    private DoctorShiftAssignment originalShiftAssignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "replacement_shift_assignment_id", nullable = false)
    private DoctorShiftAssignment replacementShiftAssignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_request_id")
    private LeaveRequest leaveRequest;

    @Column(name = "room_permission_overridden", nullable = false)
    private Boolean roomPermissionOverridden = false;

    @Column(name = "is_emergency", nullable = false)
    private Boolean isEmergency = false;

    @Column(name = "transferred_appointment_count", nullable = false)
    private Integer transferredAppointmentCount = 0;

    @Column(name = "needs_reassignment_count", nullable = false)
    private Integer needsReassignmentCount = 0;

    @Column(name = "replacement_reason", nullable = false, length = 500)
    private String replacementReason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "replaced_by_user_id", nullable = false)
    private User replacedByUser;

    @CreationTimestamp
    @Column(name = "replaced_at", nullable = false, updatable = false)
    private LocalDateTime replacedAt;
}
