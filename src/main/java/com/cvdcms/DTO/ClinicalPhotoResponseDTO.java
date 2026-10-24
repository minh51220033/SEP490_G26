package com.cvdcms.DTO;

import com.cvdcms.entity.ClinicalPhoto;
import com.cvdcms.entity.enums.PhotoCategory;
import com.cvdcms.entity.enums.PhotoStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalPhotoResponseDTO {

    private Long photoId;
    private Long profileId;
    private String patientName;
    private Long visitId;
    private String visitCode;
    private PhotoStage photoStage;
    private String photoStageText;
    private String photoStageBadgeClass;
    private PhotoCategory photoCategory;
    private String photoCategoryText;
    private String imageUrl;
    private String caption;
    private LocalDateTime capturedAt;
    private String capturedAtFormatted;

    public static ClinicalPhotoResponseDTO fromEntity(ClinicalPhoto photo) {
        if (photo == null) return null;

        String stageText = "Trước điều trị";
        String badgeClass = "badge-success";
        if (photo.getPhotoStage() != null) {
            switch (photo.getPhotoStage()) {
                case BEFORE:
                    stageText = "Trước điều trị (Before)";
                    badgeClass = "bg-warning text-dark";
                    break;
                case DURING:
                    stageText = "Trong quá trình (During)";
                    badgeClass = "bg-info text-white";
                    break;
                case AFTER:
                    stageText = "Sau điều trị (After)";
                    badgeClass = "bg-success text-white";
                    break;
            }
        }

        String catText = "Khác";
        if (photo.getPhotoCategory() != null) {
            switch (photo.getPhotoCategory()) {
                case SMILE:
                    catText = "Nụ cười";
                    break;
                case INTRAORAL:
                    catText = "Trong miệng";
                    break;
                case OCCLUSAL:
                    catText = "Mặt nhai";
                    break;
                case EXTRAORAL:
                    catText = "Mặt ngoài / Chân dung";
                    break;
                case OTHER:
                default:
                    catText = "Khác";
                    break;
            }
        }

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        String formattedDate = photo.getCapturedAt() != null ? photo.getCapturedAt().format(dtf) : "";

        return ClinicalPhotoResponseDTO.builder()
                .photoId(photo.getPhotoId())
                .profileId(photo.getPatientProfile() != null ? photo.getPatientProfile().getProfileId() : null)
                .patientName(photo.getPatientProfile() != null ? photo.getPatientProfile().getFullName() : null)
                .visitId(photo.getVisit() != null ? photo.getVisit().getVisitId() : null)
                .visitCode(photo.getVisit() != null ? photo.getVisit().getVisitCode() : null)
                .photoStage(photo.getPhotoStage())
                .photoStageText(stageText)
                .photoStageBadgeClass(badgeClass)
                .photoCategory(photo.getPhotoCategory())
                .photoCategoryText(catText)
                .imageUrl(photo.getImageUrl())
                .caption(photo.getCaption())
                .capturedAt(photo.getCapturedAt())
                .capturedAtFormatted(formattedDate)
                .build();
    }
}
