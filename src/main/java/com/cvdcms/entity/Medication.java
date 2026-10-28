package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "medications",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_drug_code", columnNames = "drug_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Medication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "medication_id")
    private Long medicationId;

    @Column(name = "drug_code", nullable = false, length = 30, unique = true)
    private String drugCode;

    @Column(name = "drug_name", nullable = false, length = 200)
    private String drugName;

    @Column(name = "active_ingredient", length = 200)
    private String activeIngredient;

    @Column(name = "unit", nullable = false, length = 30)
    private String unit = "Viên";

    @Column(name = "sale_price", nullable = false, precision = 15, scale = 2)
    private java.math.BigDecimal salePrice = java.math.BigDecimal.ZERO;

    @Column(name = "usage_instruction", length = 255)
    private String usageInstruction;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    private Material material;
}
