package com.cvdcms.entity;

import com.cvdcms.entity.enums.ServiceStatus;
import com.cvdcms.entity.enums.ServiceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "services",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_service_code", columnNames = "service_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Service {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "service_id")
    private Long serviceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private ServiceCategory category;

    @Column(name = "service_code", nullable = false, length = 30, unique = true)
    private String serviceCode;

    @Column(name = "service_name", nullable = false, length = 200)
    private String serviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 30)
    private ServiceType serviceType = ServiceType.PROCEDURE;

    @Column(name = "unit", nullable = false, length = 30)
    private String unit = "Lần";

    @Column(name = "base_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal basePrice = BigDecimal.ZERO;

    @Column(name = "surcharge_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal surchargeAmount = BigDecimal.ZERO;

    @Column(name = "requires_tooth", nullable = false)
    private Boolean requiresTooth = false;

    @Column(name = "is_paraclinical", nullable = false)
    private Boolean isParaclinical = false;

    @Column(name = "result_template_json", columnDefinition = "json")
    private String resultTemplateJson;

    @Column(name = "technical_requirements", columnDefinition = "TEXT")
    private String technicalRequirements;

    @Column(name = "is_bookable_online", nullable = false)
    private Boolean isBookableOnline = false;

    @Column(name = "warranty_months")
    private Integer warrantyMonths;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ServiceStatus status = ServiceStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
