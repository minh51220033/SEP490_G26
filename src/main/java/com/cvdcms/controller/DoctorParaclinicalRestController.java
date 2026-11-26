package com.cvdcms.controller;

import com.cvdcms.DTO.ParaclinicalOrderCreateDTO;
import com.cvdcms.DTO.ParaclinicalOrderItemResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;
import com.cvdcms.service.ParaclinicalOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/doctor")
public class DoctorParaclinicalRestController {

    private final ParaclinicalOrderService paraclinicalOrderService;
    private final com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;
    private final com.cvdcms.service.NanoPixIntegrationService nanoPixIntegrationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ClinicalPhotoService clinicalPhotoService;

    @org.springframework.beans.factory.annotation.Autowired
    public DoctorParaclinicalRestController(ParaclinicalOrderService paraclinicalOrderService,
                                           com.cvdcms.security.DoctorVisitAccess doctorVisitAccess,
                                           com.cvdcms.service.NanoPixIntegrationService nanoPixIntegrationService) {
        this.paraclinicalOrderService = paraclinicalOrderService;
        this.doctorVisitAccess = doctorVisitAccess;
        this.nanoPixIntegrationService = nanoPixIntegrationService;
    }

    public DoctorParaclinicalRestController(ParaclinicalOrderService paraclinicalOrderService,
                                           com.cvdcms.security.DoctorVisitAccess doctorVisitAccess) {
        this(paraclinicalOrderService, doctorVisitAccess, null);
    }

    /**
     * Tải file ảnh/PDF kết quả cận lâm sàng (Panorama, CT, X-quang, Xét nghiệm ngoài viện, Ảnh chụp thường)
     */
    @PostMapping(value = "/paraclinical-orders/{orderItemId}/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadResultImage(
            @PathVariable("orderItemId") Long orderItemId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "resultSummary", required = false) String resultSummary,
            @RequestParam(value = "stage", required = false) String stageStr,
            @RequestParam(value = "category", required = false) String categoryStr,
            @RequestParam(value = "activeVisitId", required = false) Long activeVisitId,
            @RequestParam(value = "labPartnerName", required = false) String labPartnerName,
            jakarta.servlet.http.HttpSession session
    ) {
        if (activeVisitId != null) {
            doctorVisitAccess.requireActiveClinicalExam(session, activeVisitId);
        } else {
            doctorVisitAccess.requireParaclinicalItem(session, orderItemId);
        }
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Vui lòng chọn file ảnh hoặc PDF kết quả cần tải lên."));
            }
            String fileUrl = nanoPixIntegrationService.storeParaclinicalFile(file);
            boolean isPdf = fileUrl.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
            if (activeVisitId != null || isPdf || (labPartnerName != null && !labPartnerName.isBlank())) {
                ParaclinicalOrderItemResponseDTO updated = paraclinicalOrderService.recordExternalLabResult(
                        orderItemId, activeVisitId, labPartnerName, resultSummary, fileUrl, file.getOriginalFilename(), isPdf);
                return ResponseEntity.ok(updated);
            }
            ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder()
                    .imageUrl(fileUrl)
                    .resultSummary(resultSummary != null && !resultSummary.isBlank() ? resultSummary.trim() : "Đã tải ảnh kết quả")
                    .status("COMPLETED")
                    .build();
            ParaclinicalOrderItemResponseDTO updated = paraclinicalOrderService.updateOrder(orderItemId, dto);
            if (clinicalPhotoService != null) {
                com.cvdcms.entity.enums.PhotoStage stage = parseStageSafe(stageStr);
                com.cvdcms.entity.enums.PhotoCategory category = parseCategorySafe(categoryStr);
                String caption = updated != null && updated.getServiceName() != null ? updated.getServiceName() : "Ảnh cận lâm sàng";
                if (resultSummary != null && !resultSummary.isBlank()) {
                    caption = caption + " — " + resultSummary.trim();
                }
                clinicalPhotoService.registerPhotoForOrderItem(orderItemId, stage, category, caption, fileUrl);
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Lỗi khi tải file kết quả cận lâm sàng cho orderItemId {}: {}", orderItemId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Lỗi khi tải file kết quả: " + e.getMessage()));
        }
    }

    /**
     * Ghi nhận / tải lên kết quả xét nghiệm máu ngoài viện (hỗ trợ cả khi phiếu được tạo từ ca khám trước)
     */
    @PostMapping(value = "/paraclinical-orders/{orderItemId}/external-result", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadExternalLabResult(
            @PathVariable("orderItemId") Long orderItemId,
            @RequestParam(value = "activeVisitId", required = false) Long activeVisitId,
            @RequestParam(value = "labPartnerName", required = false) String labPartnerName,
            @RequestParam(value = "resultSummary", required = false) String resultSummary,
            @RequestParam(value = "file", required = false) org.springframework.web.multipart.MultipartFile file,
            jakarta.servlet.http.HttpSession session
    ) {
        if (activeVisitId != null) {
            doctorVisitAccess.requireActiveClinicalExam(session, activeVisitId);
        } else {
            doctorVisitAccess.requireParaclinicalItem(session, orderItemId);
        }
        try {
            String fileUrl = null;
            String fileName = null;
            boolean isPdf = false;
            if (file != null && !file.isEmpty()) {
                fileUrl = nanoPixIntegrationService.storeParaclinicalFile(file);
                fileName = file.getOriginalFilename();
                isPdf = fileUrl.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
            } else if (resultSummary == null || resultSummary.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Vui lòng tải file kết quả hoặc nhập tóm tắt chỉ số xét nghiệm."));
            }
            ParaclinicalOrderItemResponseDTO updated = paraclinicalOrderService.recordExternalLabResult(
                    orderItemId, activeVisitId, labPartnerName, resultSummary, fileUrl, fileName, isPdf);
            return ResponseEntity.ok(updated);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Lỗi khi ghi nhận kết quả xét nghiệm ngoài viện cho orderItemId {}: {}", orderItemId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Lỗi khi lưu kết quả xét nghiệm ngoài viện: " + e.getMessage()));
        }
    }

    /**
     * SCR-11.3: Lấy danh sách chỉ định cận lâm sàng theo lượt khám
     */
    @GetMapping("/visits/{visitId}/paraclinical-orders")
    public ResponseEntity<List<ParaclinicalOrderItemResponseDTO>> getOrdersByVisit(
            @PathVariable("visitId") Long visitId, jakarta.servlet.http.HttpSession session
    ) {
        doctorVisitAccess.require(session, visitId);
        List<ParaclinicalOrderItemResponseDTO> list = paraclinicalOrderService.getOrdersByVisit(visitId);
        return ResponseEntity.ok(list);
    }

    /**
     * Lấy danh mục dịch vụ cận lâm sàng (X-quang, Xét nghiệm, CT) để bác sĩ chọn
     */
    @GetMapping("/paraclinical-services")
    public ResponseEntity<List<ServiceDTO>> getParaclinicalServices() {
        List<ServiceDTO> list = paraclinicalOrderService.getParaclinicalServices();
        return ResponseEntity.ok(list);
    }

    /**
     * SCR-11.2: Tạo chỉ định cận lâm sàng mới vào cơ sở dữ liệu
     */
    @PostMapping("/visits/{visitId}/paraclinical-orders")
    public ResponseEntity<?> createOrder(
            @PathVariable("visitId") Long visitId,
            @RequestBody ParaclinicalOrderCreateDTO dto, jakarta.servlet.http.HttpSession session
    ) {
        doctorVisitAccess.requireActiveClinicalExam(session, visitId);
        try {
            ParaclinicalOrderItemResponseDTO created = paraclinicalOrderService.createOrder(visitId, dto, null);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalStateException e) {
            log.warn("Lỗi nghiệp vụ khi tạo chỉ định CLS: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Lỗi hệ thống khi tạo chỉ định CLS", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Đã có lỗi xảy ra khi lưu chỉ định cận lâm sàng: " + e.getMessage()));
        }
    }

    /**
     * Cập nhật thông tin chỉ định cận lâm sàng (Vị trí răng, kết quả, link ảnh phim chụp, trạng thái)
     */
    @PutMapping("/paraclinical-orders/{orderItemId}")
    public ResponseEntity<?> updateOrder(
            @PathVariable("orderItemId") Long orderItemId,
            @RequestBody ParaclinicalOrderCreateDTO dto
    ) {
        var requestAttrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (requestAttrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
            doctorVisitAccess.requireParaclinicalItem(sra.getRequest().getSession(false), orderItemId);
        }
        try {
            ParaclinicalOrderItemResponseDTO updated = paraclinicalOrderService.updateOrder(orderItemId, dto);
            return ResponseEntity.ok(updated);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Lỗi khi cập nhật chỉ định cận lâm sàng: " + e.getMessage()));
        }
    }

    /**
     * Cập nhật trạng thái chỉ định (WAITING -> COMPLETED)
     */
    @PutMapping("/paraclinical-orders/{orderItemId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable("orderItemId") Long orderItemId,
            @RequestBody Map<String, String> payload, jakarta.servlet.http.HttpSession session
    ) {
        doctorVisitAccess.requireParaclinicalItem(session, orderItemId);
        try {
            if (payload.containsKey("imageUrl") || payload.containsKey("toothNumbers") || payload.containsKey("serviceId")) {
                ParaclinicalOrderCreateDTO dto = ParaclinicalOrderCreateDTO.builder()
                        .serviceId(payload.get("serviceId") != null && !payload.get("serviceId").isBlank() ? Long.valueOf(payload.get("serviceId")) : null)
                        .toothNumbers(payload.get("toothNumbers"))
                        .resultSummary(payload.get("resultSummary"))
                        .status(payload.get("status"))
                        .imageUrl(payload.get("imageUrl"))
                        .build();
                ParaclinicalOrderItemResponseDTO updated = paraclinicalOrderService.updateOrder(orderItemId, dto);
                return ResponseEntity.ok(updated);
            }
            String statusStr = payload.getOrDefault("status", "COMPLETED");
            String resultSummary = payload.get("resultSummary");
            ParaclinicalOrderItemStatus status = ParaclinicalOrderItemStatus.valueOf(statusStr);

            ParaclinicalOrderItemResponseDTO updated = paraclinicalOrderService.updateStatus(orderItemId, status, resultSummary);
            return ResponseEntity.ok(updated);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Lỗi khi cập nhật trạng thái chỉ định: " + e.getMessage()));
        }
    }

    /**
     * Xóa chỉ định cận lâm sàng khỏi cơ sở dữ liệu
     */
    @DeleteMapping("/paraclinical-orders/{orderItemId}")
    public ResponseEntity<?> deleteOrder(@PathVariable("orderItemId") Long orderItemId, jakarta.servlet.http.HttpSession session) {
        doctorVisitAccess.requireParaclinicalItem(session, orderItemId);
        try {
            paraclinicalOrderService.deleteOrder(orderItemId);
            return ResponseEntity.ok(Map.of("message", "Đã xóa chỉ định thành công."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Lỗi khi xóa chỉ định: " + e.getMessage()));
        }
    }

    private com.cvdcms.entity.enums.PhotoStage parseStageSafe(String stageStr) {
        if (stageStr == null || stageStr.isBlank()) {
            return com.cvdcms.entity.enums.PhotoStage.BEFORE;
        }
        try {
            return com.cvdcms.entity.enums.PhotoStage.valueOf(stageStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return com.cvdcms.entity.enums.PhotoStage.BEFORE;
        }
    }

    private com.cvdcms.entity.enums.PhotoCategory parseCategorySafe(String categoryStr) {
        if (categoryStr == null || categoryStr.isBlank()) {
            return com.cvdcms.entity.enums.PhotoCategory.OTHER;
        }
        String normalized = categoryStr.trim().toUpperCase();
        if ("XRAY".equals(normalized) || "PANORAMIC".equals(normalized)
                || "CEPHALOMETRIC".equals(normalized) || "CT_CONEBEAM".equals(normalized)
                || "CT".equals(normalized)) {
            return com.cvdcms.entity.enums.PhotoCategory.OTHER;
        }
        try {
            return com.cvdcms.entity.enums.PhotoCategory.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return com.cvdcms.entity.enums.PhotoCategory.OTHER;
        }
    }
}
