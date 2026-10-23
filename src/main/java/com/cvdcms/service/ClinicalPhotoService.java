package com.cvdcms.service;

import com.cvdcms.DTO.ClinicalPhotoResponseDTO;
import com.cvdcms.entity.enums.PhotoCategory;
import com.cvdcms.entity.enums.PhotoStage;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface ClinicalPhotoService {

    List<ClinicalPhotoResponseDTO> getPhotosByProfile(Long profileId);

    List<ClinicalPhotoResponseDTO> getPhotosByVisit(Long visitId);

    ClinicalPhotoResponseDTO uploadPhoto(Long profileId, Long visitId, PhotoStage stage, PhotoCategory category, String caption, MultipartFile file) throws IOException;

    void deletePhoto(Long photoId) throws IOException;

    ClinicalPhotoResponseDTO updatePhoto(Long photoId, String caption, PhotoStage stage, PhotoCategory category);

    Map<String, Object> getPhotoStatistics(Long profileId);
}
