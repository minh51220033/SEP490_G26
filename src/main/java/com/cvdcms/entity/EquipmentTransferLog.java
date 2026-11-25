package com.cvdcms.entity;

import com.cvdcms.entity.enums.EquipmentTransferType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "equipment_transfer_logs",
        indexes = {
                @Index(name = "idx_equip_transfer_equip", columnList = "equipment_id, transferred_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class EquipmentTransferLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transfer_id")
    private Long transferId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipment_id", nullable = false)
    private ClinicEquipment equipment;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", nullable = false, length = 30)
    private EquipmentTransferType transferType;

    @Column(name = "from_branch_id", nullable = false)
    private Long fromBranchId;

    @Column(name = "to_branch_id", nullable = false)
    private Long toBranchId;

    @Column(name = "from_room_id")
    private Long fromRoomId;

    @Column(name = "to_room_id")
    private Long toRoomId;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "transferred_by", nullable = false)
    private Long transferredBy;

    @Column(name = "transferred_at", nullable = false)
    private LocalDateTime transferredAt;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;
}
