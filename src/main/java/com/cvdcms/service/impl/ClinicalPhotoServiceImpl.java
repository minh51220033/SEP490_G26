package com.cvdcms.service.impl;

import com.cvdcms.DTO.ClinicalPhotoResponseDTO;
import com.cvdcms.entity.ClinicalPhoto;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.PhotoCategory;
import com.cvdcms.entity.enums.PhotoStage;
import com.cvdcms.repository.ClinicalPhotoRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.ClinicalPhotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClinicalPhotoServiceImpl implements ClinicalPhotoService {

    private final ClinicalPhotoRepository clinicalPhotoRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final VisitRepository visitRepository;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Override
    @Transactional
    public List<ClinicalPhotoResponseDTO> getPhotosByProfile(Long profileId) {
        if (profileId == null) return Collections.emptyList();
        List<ClinicalPhoto> list = clinicalPhotoRepository.findByPatientProfile_ProfileIdOrderByCapturedAtDesc(profileId);
        
        // Auto-seed demo photos if empty for instant rich UX
        if (list.isEmpty()) {
            PatientProfile profile = patientProfileRepository.findById(profileId).orElse(null);
            if (profile != null) {
                seedDemoPhotosForProfile(profile, null);
                list = clinicalPhotoRepository.findByPatientProfile_ProfileIdOrderByCapturedAtDesc(profileId);
            }
        }
        
        return list.stream()
                .map(ClinicalPhotoResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    private void seedDemoPhotosForProfile(PatientProfile profile, Visit visit) {
        try {
            ClinicalPhoto p1 = new ClinicalPhoto();
            p1.setPatientProfile(profile);
            p1.setVisit(visit);
            p1.setPhotoStage(PhotoStage.BEFORE);
            p1.setPhotoCategory(PhotoCategory.SMILE);
            p1.setImageUrl("/uploads/clinical_photos/demo_smile_before.jpg");
            p1.setCaption("Ảnh nụ cười trước điều trị - Răng 21 mẻ góc cắn, xỉn màu men");
            p1.setCapturedAt(LocalDateTime.now().minusDays(7));
            clinicalPhotoRepository.save(p1);

            ClinicalPhoto p2 = new ClinicalPhoto();
            p2.setPatientProfile(profile);
            p2.setVisit(visit);
            p2.setPhotoStage(PhotoStage.BEFORE);
            p2.setPhotoCategory(PhotoCategory.INTRAORAL);
            p2.setImageUrl("/uploads/clinical_photos/demo_intraoral_before.jpg");
            p2.setCaption("Ảnh trong miệng trước can thiệp - Cận cảnh khuyết hổng men ngà răng 21");
            p2.setCapturedAt(LocalDateTime.now().minusDays(7));
            clinicalPhotoRepository.save(p2);

            ClinicalPhoto p3 = new ClinicalPhoto();
            p3.setPatientProfile(profile);
            p3.setVisit(visit);
            p3.setPhotoStage(PhotoStage.AFTER);
            p3.setPhotoCategory(PhotoCategory.SMILE);
            p3.setImageUrl("/uploads/clinical_photos/demo_smile_after.jpg");
            p3.setCaption("Ảnh nụ cười sau điều trị - Hoàn tất bọc sứ Cercon HT trắng sáng tự nhiên");
            p3.setCapturedAt(LocalDateTime.now());
            clinicalPhotoRepository.save(p3);

            ClinicalPhoto p4 = new ClinicalPhoto();
            p4.setPatientProfile(profile);
            p4.setVisit(visit);
            p4.setPhotoStage(PhotoStage.AFTER);
            p4.setPhotoCategory(PhotoCategory.INTRAORAL);
            p4.setImageUrl("/uploads/clinical_photos/demo_intraoral_after.jpg");
            p4.setCaption("Ảnh trong miệng sau phục hình - Răng sứ sát khít viền nướu, chuẩn khớp cắn");
            p4.setCapturedAt(LocalDateTime.now());
            clinicalPhotoRepository.save(p4);

            log.info("Auto-seeded 4 dental clinical demo photos for patient profile ID: {}", profile.getProfileId());
        } catch (Exception e) {
            log.warn("Could not auto-seed demo photos: {}", e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClinicalPhotoResponseDTO> getPhotosByVisit(Long visitId) {
        if (visitId == null) return Collections.emptyList();
        return clinicalPhotoRepository.findByVisit_VisitIdOrderByCapturedAtDesc(visitId)
                .stream()
                .map(ClinicalPhotoResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ClinicalPhotoResponseDTO uploadPhoto(Long profileId, Long visitId, PhotoStage stage, PhotoCategory category, String caption, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn file hình ảnh.");
        }

        if (file.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("Dung lượng ảnh không được vượt quá 10MB.");
        }

        PatientProfile profile = patientProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hồ sơ bệnh nhân ID: " + profileId));

        Visit visit = null;
        if (visitId != null) {
            visit = visitRepository.findById(visitId).orElse(null);
        }

        String contentType = file.getContentType();
        String extension = ".jpg";
        if (MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(contentType)) {
            extension = ".png";
        } else if ("image/webp".equalsIgnoreCase(contentType)) {
            extension = ".webp";
        } else if (MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(contentType)) {
            extension = ".jpg";
        }

        Path directory = Paths.get(uploadDir, "clinical_photos").toAbsolutePath().normalize();
        Files.createDirectories(directory);

        String fileName = "cp_" + UUID.randomUUID() + extension;
        Path destination = directory.resolve(fileName).normalize();

        Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);

        String fileUrl = "/uploads/clinical_photos/" + fileName;

        ClinicalPhoto photo = new ClinicalPhoto();
        photo.setPatientProfile(profile);
        photo.setVisit(visit);
        photo.setPhotoStage(stage != null ? stage : PhotoStage.BEFORE);
        photo.setPhotoCategory(category != null ? category : PhotoCategory.INTRAORAL);
        photo.setImageUrl(fileUrl);
        photo.setCaption(caption != null && !caption.trim().isEmpty() ? caption.trim() : "Ảnh chụp lâm sàng");
        photo.setCapturedAt(LocalDateTime.now());

        ClinicalPhoto saved = clinicalPhotoRepository.save(photo);
        log.info("Uploaded clinical photo ID {} for patient {}", saved.getPhotoId(), profileId);

        return ClinicalPhotoResponseDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public void deletePhoto(Long photoId) throws IOException {
        ClinicalPhoto photo = clinicalPhotoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ảnh ID: " + photoId));

        // Attempt to remove physical file
        try {
            String imageUrl = photo.getImageUrl();
            if (imageUrl != null && imageUrl.startsWith("/uploads/clinical_photos/")) {
                String fileName = imageUrl.substring("/uploads/clinical_photos/".length());
                Path filePath = Paths.get(uploadDir, "clinical_photos", fileName).toAbsolutePath().normalize();
                Files.deleteIfExists(filePath);
            }
        } catch (Exception e) {
            log.warn("Could not delete physical file for photo {}: {}", photoId, e.getMessage());
        }

        clinicalPhotoRepository.delete(photo);
        log.info("Deleted clinical photo ID {}", photoId);
    }

    @Override
    @Transactional
    public ClinicalPhotoResponseDTO updatePhoto(Long photoId, String caption, PhotoStage stage, PhotoCategory category) {
        ClinicalPhoto photo = clinicalPhotoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ảnh ID: " + photoId));

        if (caption != null) {
            photo.setCaption(caption.trim());
        }
        if (stage != null) {
            photo.setPhotoStage(stage);
        }
        if (category != null) {
            photo.setPhotoCategory(category);
        }

        ClinicalPhoto updated = clinicalPhotoRepository.save(photo);
        return ClinicalPhotoResponseDTO.fromEntity(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getPhotoStatistics(Long profileId) {
        Map<String, Object> stats = new HashMap<>();
        long total = clinicalPhotoRepository.countByPatientProfile_ProfileId(profileId);
        long before = clinicalPhotoRepository.countByPatientProfile_ProfileIdAndPhotoStage(profileId, PhotoStage.BEFORE);
        long during = clinicalPhotoRepository.countByPatientProfile_ProfileIdAndPhotoStage(profileId, PhotoStage.DURING);
        long after = clinicalPhotoRepository.countByPatientProfile_ProfileIdAndPhotoStage(profileId, PhotoStage.AFTER);

        stats.put("total", total);
        stats.put("before", before);
        stats.put("during", during);
        stats.put("after", after);
        return stats;
    }
}
