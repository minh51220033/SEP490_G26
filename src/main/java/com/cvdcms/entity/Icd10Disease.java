package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Entity
@Table(
        name = "icd10_diseases",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_icd10_code", columnNames = "icd10_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Icd10Disease implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "icd10_id")
    private Long icd10Id;

    @Column(name = "icd10_code", nullable = false, length = 20, unique = true)
    private String icd10Code;

    @Column(name = "disease_name_vi", nullable = false, length = 255)
    private String diseaseNameVi;

    @Column(name = "disease_name_en", length = 255)
    private String diseaseNameEn;

    @Column(name = "is_dental_common", nullable = false)
    private Boolean isDentalCommon = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
