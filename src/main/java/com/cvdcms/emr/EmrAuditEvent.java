package com.cvdcms.emr;

import com.cvdcms.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "emr_audit_events", indexes = @Index(name = "idx_emr_audit_record_time", columnList = "record_id,created_at"))
@Getter
@Setter
@NoArgsConstructor
public class EmrAuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id", nullable = false)
    private EmrRecord record;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id")
    private EmrRecordVersion version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actorUser;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "details", length = 1000)
    private String details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
