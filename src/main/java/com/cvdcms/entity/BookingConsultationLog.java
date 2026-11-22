package com.cvdcms.entity;

import com.cvdcms.entity.enums.ConsultationContactResult;
import com.cvdcms.entity.enums.ConsultationLogAction;
import com.cvdcms.entity.enums.ConsultationRequestStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "booking_consultation_logs",
        indexes = {
                @Index(name = "idx_bcl_request_created", columnList = "request_id, created_at, log_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class BookingConsultationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private BookingConsultationRequest consultationRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private ConsultationLogAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 20)
    private ConsultationRequestStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 20)
    private ConsultationRequestStatus newStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_result", length = 30)
    private ConsultationContactResult contactResult;

    @Column(name = "notes", length = 1000)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actorUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private User targetUser;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
