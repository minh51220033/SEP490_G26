package com.cvdcms.entity;

import com.cvdcms.entity.enums.HandoverFromType;
import com.cvdcms.entity.enums.HandoverToRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_handover_logs")
@Getter
@Setter
@NoArgsConstructor
public class ChatHandoverLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "handover_id")
    private Long handoverId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ChatConversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_type", nullable = false, length = 20)
    private HandoverFromType fromType = HandoverFromType.BOT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_staff_id")
    private StaffProfile fromStaff;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_role", nullable = false, length = 20)
    private HandoverToRole toRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_staff_id")
    private StaffProfile toStaff;

    @Column(name = "handover_reason", length = 500)
    private String handoverReason;

    @CreationTimestamp
    @Column(name = "handed_over_at", nullable = false, updatable = false)
    private LocalDateTime handedOverAt;
}
