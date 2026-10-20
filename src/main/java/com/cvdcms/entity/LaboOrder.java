package com.cvdcms.entity;

import com.cvdcms.entity.enums.LaboOrderStatus;
import com.cvdcms.entity.enums.LaboOrderType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
        name = "labo_orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_labo_code", columnNames = "labo_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class LaboOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "labo_id")
    private Long laboId;

    @Column(name = "labo_code", nullable = false, length = 20, unique = true)
    private String laboCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private StaffProfile doctor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    private LaboOrderType orderType = LaboOrderType.NEW;

    @Column(name = "tooth_positions", nullable = false, length = 100)
    private String toothPositions;

    @Column(name = "material_type", nullable = false, length = 100)
    private String materialType;

    @Column(name = "shade_color", nullable = false, length = 50)
    private String shadeColor;

    @Column(name = "cost_to_clinic", nullable = false, precision = 15, scale = 2)
    private BigDecimal costToClinic = BigDecimal.ZERO;

    @Column(name = "sent_date", nullable = false)
    private LocalDate sentDate;

    @Column(name = "try_in_date")
    private LocalDate tryInDate;

    @Column(name = "expected_date", nullable = false)
    private LocalDate expectedDate;

    @Column(name = "received_date")
    private LocalDate receivedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LaboOrderStatus status = LaboOrderStatus.SENT;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @PrePersist
    public void prePersist() {
        if (this.laboCode == null || this.laboCode.isEmpty()) {
            this.laboCode = "LABO" + System.currentTimeMillis();
        }
    }
}
