package com.cvdcms.entity;

import com.cvdcms.entity.enums.TransitionRuleScope;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "branch_room_transition_rules",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_transition_rule_active", columnNames = {"rule_signature", "active_guard"})
        },
        indexes = {
                @Index(name = "idx_transition_branch_scope", columnList = "branch_id, rule_scope, is_active")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class BranchRoomTransitionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_scope", nullable = false, length = 30)
    private TransitionRuleScope ruleScope;

    @Column(name = "from_floor_label", length = 50)
    private String fromFloorLabel;

    @Column(name = "to_floor_label", length = 50)
    private String toFloorLabel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_room_id")
    private Room fromRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_room_id")
    private Room toRoom;

    @Column(name = "travel_buffer_minutes", nullable = false)
    private Integer travelBufferMinutes = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "rule_signature", nullable = false, length = 150)
    private String ruleSignature;

    @Column(name = "active_guard")
    private Byte activeGuard = 1;

    @PrePersist
    @PreUpdate
    public void computeSignatureAndGuard() {
        Long bId = branch != null ? branch.getBranchId() : 0L;
        String scopeStr = ruleScope != null ? ruleScope.name() : "UNKNOWN";
        Long fRoom = fromRoom != null && fromRoom.getRoomId() != null ? fromRoom.getRoomId() : 0L;
        Long tRoom = toRoom != null && toRoom.getRoomId() != null ? toRoom.getRoomId() : 0L;
        String fFloor = (fromFloorLabel != null && !fromFloorLabel.isBlank()) ? fromFloorLabel.trim().toUpperCase() : "*";
        String tFloor = (toFloorLabel != null && !toFloorLabel.isBlank()) ? toFloorLabel.trim().toUpperCase() : "*";
        this.ruleSignature = bId + ":" + scopeStr + ":" + fRoom + ":" + tRoom + ":" + fFloor + ":" + tFloor;
        this.activeGuard = Boolean.TRUE.equals(this.isActive) ? (byte) 1 : null;
    }
}
