package com.cvdcms.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "doctor_cover_grants")
@Getter @Setter
public class DoctorCoverGrant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "covering_doctor_id", nullable = false)
    private StaffProfile coveringDoctor;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "granted_by_user_id", nullable = false)
    private User grantedBy;
    @Column(nullable = false, length = 255)
    private String reason;
    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
}
