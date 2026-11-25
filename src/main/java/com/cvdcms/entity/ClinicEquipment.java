package com.cvdcms.entity;

import com.cvdcms.entity.enums.EquipmentAllocationMode;
import com.cvdcms.entity.enums.EquipmentStatus;
import com.cvdcms.entity.enums.EquipmentType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "clinic_equipments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_equip_branch_code", columnNames = {"branch_id", "equipment_code"}),
                @UniqueConstraint(name = "uk_equip_serial", columnNames = {"device_serial_number"})
        },
        indexes = {
                @Index(name = "idx_equip_branch_mode_status", columnList = "branch_id, allocation_mode, status")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ClinicEquipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "equipment_id")
    private Long equipmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_mode", nullable = false, length = 30)
    private EquipmentAllocationMode allocationMode = EquipmentAllocationMode.FIXED_ROOM;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixed_room_id")
    private Room fixedRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_room_id")
    private Room currentRoom;

    @Column(name = "equipment_code", nullable = false, length = 30)
    private String equipmentCode;

    @Column(name = "equipment_name", nullable = false, length = 150)
    private String equipmentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "equipment_type", nullable = false, length = 50)
    private EquipmentType equipmentType = EquipmentType.OTHER;

    @Column(name = "device_serial_number", length = 100)
    private String deviceSerialNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private EquipmentStatus status = EquipmentStatus.PENDING_VERIFICATION;
}
