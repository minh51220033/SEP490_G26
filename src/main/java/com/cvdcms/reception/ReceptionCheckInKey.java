package com.cvdcms.reception;

import com.cvdcms.entity.Visit;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "reception_checkin_keys", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reception_checkin_key", columnNames = "request_key"),
        @UniqueConstraint(name = "uk_reception_checkin_visit", columnNames = "visit_id")})
@Getter @Setter @NoArgsConstructor
public class ReceptionCheckInKey {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") private Long id;
    @Column(name = "request_key", nullable = false, length = 36) private String requestKey;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "visit_id", nullable = false) private Visit visit;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
}
