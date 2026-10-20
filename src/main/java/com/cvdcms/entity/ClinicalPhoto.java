package com.cvdcms.entity;

import com.cvdcms.entity.enums.PhotoCategory;
import com.cvdcms.entity.enums.PhotoStage;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "clinical_photos")
@Getter
@Setter
@NoArgsConstructor
public class ClinicalPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "photo_id")
    private Long photoId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_id")
    private PerformedService performedService;

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_stage", nullable = false, length = 20)
    private PhotoStage photoStage = PhotoStage.BEFORE;

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_category", nullable = false, length = 20)
    private PhotoCategory photoCategory = PhotoCategory.INTRAORAL;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "caption", length = 255)
    private String caption;

    @CreationTimestamp
    @Column(name = "captured_at", nullable = false, updatable = false)
    private LocalDateTime capturedAt;
}
