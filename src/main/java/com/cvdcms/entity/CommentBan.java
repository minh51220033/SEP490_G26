package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "comment_bans",
        indexes = {
                @Index(
                        name = "idx_ban_user_active",
                        columnList = "user_id,is_active"
                ),
                @Index(
                        name = "idx_ban_banned_at",
                        columnList = "banned_at"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CommentBan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ban_id")
    private Long banId;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = false
    )
    private User user;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "banned_by",
            nullable = false
    )
    private User bannedBy;


    @Column(
            name = "reason",
            nullable = false,
            length = 500
    )
    private String reason;


    @Column(
            name = "banned_at",
            nullable = false
    )
    private LocalDateTime bannedAt;


    // NULL = ban vĩnh viễn
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;


    @Column(
            name = "is_active",
            nullable = false
    )
    private Boolean active = true;
}