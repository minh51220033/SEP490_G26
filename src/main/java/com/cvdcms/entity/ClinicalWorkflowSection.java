package com.cvdcms.entity;

import com.cvdcms.entity.enums.SectionCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "clinical_workflow_sections",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_cws", columnNames = {"version_id", "section_code"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ClinicalWorkflowSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "section_id")
    private Long sectionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private ClinicalWorkflowVersion version;

    @Enumerated(EnumType.STRING)
    @Column(name = "section_code", nullable = false, length = 10)
    private SectionCode sectionCode;

    @Column(name = "section_title", nullable = false, length = 200)
    private String sectionTitle;

    @Column(name = "content", columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;
}
