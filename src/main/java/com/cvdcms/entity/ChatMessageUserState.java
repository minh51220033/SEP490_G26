package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "chat_message_user_states",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_chat_msg_user_state", columnNames = {"message_id", "user_id"})
        },
        indexes = {
                @Index(name = "idx_cmus_conv_user", columnList = "conversation_id, user_id"),
                @Index(name = "idx_cmus_user_read", columnList = "user_id, is_read")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ChatMessageUserState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "state_id")
    private Long stateId;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
