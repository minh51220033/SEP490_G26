package com.cvdcms.emr;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "emr_attachments")
@Getter @Setter
public class EmrAttachment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attachment_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private EmrRecordVersion version;
    @Column(name = "source_photo_id")
    private Long sourcePhotoId;
    @Column(name = "original_name", nullable = false)
    private String originalName;
    @Column(name = "mime_type", nullable = false)
    private String mimeType;
    @Column(name = "byte_size", nullable = false)
    private Long byteSize;
    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;
    @Column(name = "storage_name", nullable = false)
    private String storageName;
    private String caption;
    @Column(name = "captured_at")
    private LocalDateTime capturedAt;
}
