package com.cvdcms.entity;

import com.cvdcms.entity.enums.ConsultationRequestStatus;
import com.cvdcms.entity.enums.PreferredSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(
        name = "booking_consultation_requests",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_bcr_request_code", columnNames = "request_code"),
                @UniqueConstraint(name = "uk_bcr_request_key", columnNames = "request_key"),
                @UniqueConstraint(name = "uk_bcr_convert_key", columnNames = "convert_request_key")
        },
        indexes = {
                @Index(name = "idx_bcr_status_branch_created", columnList = "status, branch_id, created_at"),
                @Index(name = "idx_bcr_phone_branch_date", columnList = "guest_phone, branch_id, preferred_date, preferred_session, status"),
                @Index(name = "idx_bcr_handler_status", columnList = "handled_by_user_id, status, updated_at"),
                @Index(name = "idx_bcr_branch_created", columnList = "branch_id, created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class BookingConsultationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "request_code", nullable = false, length = 30, unique = true)
    private String requestCode;

    @Column(name = "request_key", nullable = false, length = 64, unique = true)
    private String requestKey;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preferred_doctor_id")
    private StaffProfile preferredDoctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private Service service;

    @Column(name = "guest_name", nullable = false, length = 150)
    private String guestName;

    @Column(name = "guest_phone", nullable = false, length = 20)
    private String guestPhone;

    @Column(name = "preferred_date", nullable = false)
    private LocalDate preferredDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_session", nullable = false, length = 20)
    private PreferredSession preferredSession;

    @Column(name = "preferred_time")
    private LocalTime preferredTime;

    @Column(name = "notes", length = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ConsultationRequestStatus status = ConsultationRequestStatus.NEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "converted_appointment_id")
    private Appointment convertedAppointment;

    @Column(name = "converted_at")
    private LocalDateTime convertedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "close_reason", length = 500)
    private String closeReason;

    @Column(name = "convert_request_key", length = 64, unique = true)
    private String convertRequestKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by_user_id")
    private User handledByUser;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "last_contacted_at")
    private LocalDateTime lastContactedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
