package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "chat_conversation_user_states",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_chat_conv_user_state", columnNames = {"conversation_id", "user_id"})
        },
        indexes = {
                @Index(name = "idx_ccus_user", columnList = "user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ChatConversationUserState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "state_id")
    private Long stateId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;

    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;
}
