package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(
        name = "materials",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_mat_code", columnNames = "material_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Material {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "material_id")
    private Long materialId;

    @Column(name = "material_code", nullable = false, length = 30, unique = true)
    private String materialCode;

    @Column(name = "material_name", nullable = false, length = 200)
    private String materialName;

    @Column(name = "category", nullable = false, length = 100)
    private String category;

    @Column(name = "unit", nullable = false, length = 30)
    private String unit;

    @Column(name = "min_stock_threshold", nullable = false, precision = 12, scale = 3)
    private BigDecimal minStockThreshold = new BigDecimal("10.000");

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
