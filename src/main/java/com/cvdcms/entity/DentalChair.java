package com.cvdcms.entity;

import com.cvdcms.entity.enums.ChairStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "dental_chairs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_chair", columnNames = {"branch_id", "chair_code"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class DentalChair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chair_id")
    private Long chairId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(name = "chair_code", nullable = false, length = 20)
    private String chairCode;

    @Column(name = "chair_name", nullable = false, length = 50)
    private String chairName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChairStatus status = ChairStatus.AVAILABLE;
}
