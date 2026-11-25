package com.cvdcms.entity;

import com.cvdcms.entity.enums.DoctorShiftAssignmentRole;
import com.cvdcms.entity.enums.DoctorShiftAssignmentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "doctor_shift_assignments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_dsa_active_staff_shift", columnNames = "active_slot_key")
        },
        indexes = {
                @Index(name = "idx_dsa_branch_date_shift_room", columnList = "branch_id, work_date, shift_id, room_id, status"),
                @Index(name = "idx_dsa_staff_date_status", columnList = "staff_id, work_date, status"),
                @Index(name = "idx_dsa_schedule", columnList = "staff_schedule_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class DoctorShiftAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shift_assignment_id")
    private Long shiftAssignmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_schedule_id", nullable = false)
    private StaffSchedule staffSchedule;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chair_id")
    private DentalChair chair;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private StaffProfile staff;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_role", nullable = false, length = 20)
    private DoctorShiftAssignmentRole assignmentRole = DoctorShiftAssignmentRole.CO_ATTENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DoctorShiftAssignmentStatus status = DoctorShiftAssignmentStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaces_shift_assignment_id")
    private DoctorShiftAssignment replacesShiftAssignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_for_staff_id")
    private StaffProfile replacedForStaff;

    @Column(name = "replacement_reason", length = 500)
    private String replacementReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_user_id")
    private User replacedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_request_id")
    private LeaveRequest leaveRequest;

    @Column(name = "room_permission_overridden", nullable = false)
    private Boolean roomPermissionOverridden = false;

    @Column(name = "active_slot_key", length = 64)
    private String activeSlotKey;

    @Column(name = "note", length = 500)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static String buildActiveSlotKey(Long staffId, LocalDate workDate, Long shiftId) {
        return staffId + "#" + workDate + "#" + shiftId;
    }

    public void syncActiveSlotKey() {
        if (this.status == DoctorShiftAssignmentStatus.ACTIVE
                && this.staff != null && this.staff.getStaffId() != null
                && this.workDate != null
                && this.shift != null && this.shift.getShiftId() != null) {
            this.activeSlotKey = buildActiveSlotKey(this.staff.getStaffId(), this.workDate, this.shift.getShiftId());
        } else {
            this.activeSlotKey = null;
        }
    }
}
