package com.cvdcms.emr;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "emr_documents", uniqueConstraints =
        @UniqueConstraint(name = "uk_emr_version_document", columnNames = {"version_id", "document_code"}))
@Getter
@Setter
@NoArgsConstructor
public class EmrDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_id")
    private Long documentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private EmrRecordVersion version;

    @Column(name = "document_code", nullable = false, length = 50)
    private String documentCode;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "document_type", nullable = false, length = 40)
    private String documentType;

    @Column(name = "template_version", nullable = false)
    private Integer templateVersion = 1;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "content_json", nullable = false, columnDefinition = "json")
    private String contentJson;

    @Column(name = "document_hash", nullable = false, length = 64)
    private String documentHash;
}
