package com.cvdcms.entity;

import com.cvdcms.entity.enums.ParaclinicalOrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "paraclinical_orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pco_code", columnNames = "order_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ParaclinicalOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "order_code", nullable = false, length = 20, unique = true)
    private String orderCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private StaffProfile doctor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ParaclinicalOrderStatus status = ParaclinicalOrderStatus.ORDERED;

    @CreationTimestamp
    @Column(name = "order_time", nullable = false, updatable = false)
    private LocalDateTime orderTime;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ParaclinicalOrderItem> orderItems = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (this.orderCode == null || this.orderCode.isEmpty()) {
            this.orderCode = "CLS" + System.currentTimeMillis();
        }
    }
}
