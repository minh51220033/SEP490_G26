package com.cvdcms.entity;

import java.time.LocalDateTime;

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

    @Convert(converter = ChairStatusConverter.class)
    @Column(name = "status", nullable = false, length = 20)
    private ChairStatus status = ChairStatus.AVAILABLE;

    @Column(name = "turnover_started_at")
    private LocalDateTime turnoverStartedAt;

    @Column(name = "turnover_duration_minutes")
    private Integer turnoverDurationMinutes;
    /**
     * Converts chair status safely, mapping legacy CLEANING rows in DB to AVAILABLE.
     */
    @Converter
    public static class ChairStatusConverter implements AttributeConverter<ChairStatus, String> {
        @Override
        public String convertToDatabaseColumn(ChairStatus attribute) {
            return (attribute != null ? attribute : ChairStatus.AVAILABLE).name();
        }

        @Override
        public ChairStatus convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank() || "CLEANING".equalsIgnoreCase(dbData.trim())) {
                return ChairStatus.AVAILABLE;
            }
            try {
                return ChairStatus.valueOf(dbData.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return ChairStatus.AVAILABLE;
            }
        }
    }
}
