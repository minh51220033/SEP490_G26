package com.cvdcms.entity;

import com.cvdcms.entity.enums.ReservationResourceType;
import com.cvdcms.entity.enums.ReservationStatus;
import com.cvdcms.entity.enums.ReservationUsageScope;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "appointment_resource_reservations",
        indexes = {
                @Index(
                        name = "idx_res_lookup",
                        columnList = "branch_id, resource_type, resource_id, status, start_at, end_at"
                ),
                @Index(
                        name = "idx_res_appt_status",
                        columnList = "appointment_id, status"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class AppointmentResourceReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long reservationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private ReservationResourceType resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_scope", nullable = false, length = 20)
    private ReservationUsageScope usageScope;

    @Column(name = "is_room_exclusive", nullable = false)
    private Boolean isRoomExclusive = false;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.RESERVED;

    @Column(name = "released_reason", length = 50)
    private String releasedReason;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
