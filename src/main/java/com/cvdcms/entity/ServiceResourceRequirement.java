package com.cvdcms.entity;

import com.cvdcms.entity.enums.EquipmentType;
import com.cvdcms.entity.enums.ResourceRequirementKind;
import com.cvdcms.entity.enums.ResourceUsagePhase;
import com.cvdcms.entity.enums.RoomType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "service_resource_requirements",
        indexes = {
                @Index(name = "idx_svc_res_req_service", columnList = "service_id, resource_kind")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ServiceResourceRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "requirement_id")
    private Long requirementId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_kind", nullable = false, length = 30)
    private ResourceRequirementKind resourceKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 30)
    private RoomType roomType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @Enumerated(EnumType.STRING)
    @Column(name = "equipment_type", length = 50)
    private EquipmentType equipmentType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "equipment_id")
    private ClinicEquipment equipment;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_phase", nullable = false, length = 20)
    private ResourceUsagePhase usagePhase = ResourceUsagePhase.PROCEDURE;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = true;
}
