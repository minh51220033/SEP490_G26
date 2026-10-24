package com.cvdcms.controller;

import com.cvdcms.DTO.ClinicalPhotoResponseDTO;
import com.cvdcms.entity.enums.PhotoCategory;
import com.cvdcms.entity.enums.PhotoStage;
import com.cvdcms.service.ClinicalPhotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doctor/clinical-photos")
@RequiredArgsConstructor
@Slf4j
public class ClinicalPhotoRestController {

    private final ClinicalPhotoService clinicalPhotoService;

    @GetMapping("/patient/{profileId}")
    public ResponseEntity<Map<String, Object>> getPhotosByPatient(@PathVariable Long profileId) {
        List<ClinicalPhotoResponseDTO> photos = clinicalPhotoService.getPhotosByProfile(profileId);
        Map<String, Object> stats = clinicalPhotoService.getPhotoStatistics(profileId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", photos);
        response.put("stats", stats);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/visit/{visitId}")
    public ResponseEntity<Map<String, Object>> getPhotosByVisit(@PathVariable Long visitId) {
        List<ClinicalPhotoResponseDTO> photos = clinicalPhotoService.getPhotosByVisit(visitId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", photos);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadPhoto(
            @RequestParam("profileId") Long profileId,
            @RequestParam(value = "visitId", required = false) Long visitId,
            @RequestParam(value = "stage", defaultValue = "BEFORE") String stageStr,
            @RequestParam(value = "category", defaultValue = "INTRAORAL") String categoryStr,
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam("file") MultipartFile file) {

        Map<String, Object> response = new HashMap<>();
        try {
            PhotoStage stage = PhotoStage.valueOf(stageStr.toUpperCase());
            PhotoCategory category = PhotoCategory.valueOf(categoryStr.toUpperCase());

            ClinicalPhotoResponseDTO photo = clinicalPhotoService.uploadPhoto(profileId, visitId, stage, category, caption, file);
            response.put("success", true);
            response.put("message", "Tải ảnh lâm sàng lên thành công!");
            response.put("data", photo);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (IOException e) {
            log.error("Error saving clinical photo: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Lỗi lưu file ảnh trên máy chủ: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/{photoId}")
    public ResponseEntity<Map<String, Object>> deletePhoto(@PathVariable Long photoId) {
        Map<String, Object> response = new HashMap<>();
        try {
            clinicalPhotoService.deletePhoto(photoId);
            response.put("success", true);
            response.put("message", "Đã xóa ảnh lâm sàng thành công!");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error deleting clinical photo {}: {}", photoId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Không thể xóa ảnh: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PutMapping("/{photoId}")
    public ResponseEntity<Map<String, Object>> updatePhoto(
            @PathVariable Long photoId,
            @RequestBody Map<String, String> body) {

        Map<String, Object> response = new HashMap<>();
        try {
            String caption = body.get("caption");
            PhotoStage stage = body.get("stage") != null ? PhotoStage.valueOf(body.get("stage").toUpperCase()) : null;
            PhotoCategory category = body.get("category") != null ? PhotoCategory.valueOf(body.get("category").toUpperCase()) : null;

            ClinicalPhotoResponseDTO updated = clinicalPhotoService.updatePhoto(photoId, caption, stage, category);
            response.put("success", true);
            response.put("message", "Cập nhật thông tin ảnh thành công!");
            response.put("data", updated);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Lỗi cập nhật ảnh: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}
