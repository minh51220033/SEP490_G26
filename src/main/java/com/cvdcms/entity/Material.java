package com.cvdcms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(
        name = "materials",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_mat_code", columnNames = "material_code"),
                // Một thuốc chỉ được liên kết với tối đa một mặt hàng kho (MySQL cho phép nhiều giá trị NULL).
                @UniqueConstraint(name = "uk_materials_medication", columnNames = "medication_id")
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

    /**
     * Liên kết tùy chọn tới danh mục thuốc (bảng medications).
     * - NULL: vật tư thông thường, không phải thuốc.
     * - Khác NULL: mặt hàng kho được đồng bộ tự động khi Admin tạo thuốc.
     * Không serialize trực tiếp (tránh lazy-loading khi trả JSON); giao diện dùng getMedicationId().
     */
    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "medication_id",
            foreignKey = @ForeignKey(name = "fk_materials_medication")
    )
    private Medication medication;

    /** ID thuốc liên kết (null nếu là vật tư thông thường) — phục vụ JSON/giao diện kho. */
    @JsonProperty("medicationId")
    public Long getMedicationId() {
        return medication == null ? null : medication.getMedicationId();
    }
}
