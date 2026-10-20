package com.cvdcms.entity;

import com.cvdcms.entity.enums.QueueStatus;
import com.cvdcms.entity.enums.QueueType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "queue_tickets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_queue_no", columnNames = {"branch_id", "queue_date", "queue_type", "is_priority", "ticket_number"})
        },
        indexes = {
                @Index(name = "idx_queue_board", columnList = "branch_id, queue_date, queue_type, status, priority_score")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class QueueTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ticket_id")
    private Long ticketId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "queue_type", nullable = false, length = 30)
    private QueueType queueType = QueueType.EXAM;

    @Column(name = "queue_date", nullable = false)
    private LocalDate queueDate;

    @Column(name = "ticket_number", nullable = false)
    private Integer ticketNumber;

    @Column(name = "is_priority", nullable = false)
    private Boolean isPriority = false;

    @Column(name = "display_number", nullable = false, length = 10)
    private String displayNumber;

    @Column(name = "is_emergency", nullable = false)
    private Boolean isEmergency = false;

    @Column(name = "priority_score", nullable = false)
    private Integer priorityScore = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QueueStatus status = QueueStatus.WAITING;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "called_at")
    private LocalDateTime calledAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
