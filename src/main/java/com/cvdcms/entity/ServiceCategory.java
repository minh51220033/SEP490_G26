package com.cvdcms.entity;

import com.cvdcms.entity.enums.ServiceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "service_categories",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_svc_cat_code", columnNames = "category_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ServiceCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "category_code", nullable = false, length = 30, unique = true)
    private String categoryCode;

    @Column(name = "category_name", nullable = false, length = 150)
    private String categoryName;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_type", nullable = false, length = 30)
    private ServiceType categoryType = ServiceType.PROCEDURE;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
