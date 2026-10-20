package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "system_audit_logs",
        indexes = {
                @Index(name = "idx_audit_table_id", columnList = "entity_table, entity_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class SystemAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "entity_table", nullable = false, length = 60)
    private String entityTable;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "old_values_json", columnDefinition = "json")
    private String oldValuesJson;

    @Column(name = "new_values_json", columnDefinition = "json")
    private String newValuesJson;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
