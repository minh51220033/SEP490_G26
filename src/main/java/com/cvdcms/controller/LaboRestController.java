package com.cvdcms.controller;

import com.cvdcms.DTO.LaboOrderRequestDTO;
import com.cvdcms.DTO.LaboOrderResponseDTO;
import com.cvdcms.entity.Supplier;
import com.cvdcms.entity.enums.LaboOrderStatus;
import com.cvdcms.repository.SupplierRepository;
import com.cvdcms.service.LaboOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doctor/labo-orders")
@RequiredArgsConstructor
@Slf4j
public class LaboRestController {

    private final LaboOrderService laboOrderService;
    private final SupplierRepository supplierRepository;

    @GetMapping("/visit/{visitId}")
    public ResponseEntity<Map<String, Object>> getOrdersByVisit(@PathVariable Long visitId) {
        List<LaboOrderResponseDTO> list = laboOrderService.getOrdersByVisit(visitId);
        Map<String, Object> stats = laboOrderService.getLaboStatistics(visitId, null);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", list);
        res.put("stats", stats);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/patient/{profileId}")
    public ResponseEntity<Map<String, Object>> getOrdersByPatient(@PathVariable Long profileId) {
        List<LaboOrderResponseDTO> list = laboOrderService.getOrdersByPatient(profileId);
        Map<String, Object> stats = laboOrderService.getLaboStatistics(null, profileId);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", list);
        res.put("stats", stats);
        return ResponseEntity.ok(res);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllOrders(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) LaboOrderStatus status,
            @RequestParam(value = "supplierId", required = false) Long supplierId,
            @RequestParam(value = "visitId", required = false) Long visitId,
            @RequestParam(value = "profileId", required = false) Long profileId
    ) {
        List<LaboOrderResponseDTO> list = laboOrderService.getAllOrders(keyword, status, supplierId, visitId, profileId);
        Map<String, Object> stats = laboOrderService.getLaboStatistics(visitId, profileId);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", list);
        res.put("stats", stats);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/{laboId}")
    public ResponseEntity<Map<String, Object>> getOrderById(@PathVariable Long laboId) {
        LaboOrderResponseDTO order = laboOrderService.getOrderById(laboId);
        if (order == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "Không tìm thấy phiếu đặt xưởng ID: " + laboId);
            return ResponseEntity.status(404).body(err);
        }
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", order);
        return ResponseEntity.ok(res);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createOrderJson(@RequestBody LaboOrderRequestDTO request) {
        Map<String, Object> res = new HashMap<>();
        try {
            LaboOrderResponseDTO created = laboOrderService.createOrder(request, null);
            res.put("success", true);
            res.put("message", "Tạo phiếu đặt xưởng / gửi mẫu thành công!");
            res.put("data", created);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("Error creating labo order: ", e);
            res.put("success", false);
            res.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(res);
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> createOrderMultipart(
            @ModelAttribute LaboOrderRequestDTO request,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile
    ) {
        Map<String, Object> res = new HashMap<>();
        try {
            LaboOrderResponseDTO created = laboOrderService.createOrder(request, imageFile);
            res.put("success", true);
            res.put("message", "Tạo phiếu đặt xưởng & đính kèm ảnh thành công!");
            res.put("data", created);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("Error creating labo order multipart: ", e);
            res.put("success", false);
            res.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(res);
        }
    }

    @PutMapping(value = "/{laboId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> updateOrderJson(
            @PathVariable Long laboId,
            @RequestBody LaboOrderRequestDTO request
    ) {
        Map<String, Object> res = new HashMap<>();
        try {
            LaboOrderResponseDTO updated = laboOrderService.updateOrder(laboId, request, null);
            res.put("success", true);
            res.put("message", "Cập nhật phiếu đặt xưởng thành công!");
            res.put("data", updated);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("Error updating labo order: ", e);
            res.put("success", false);
            res.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(res);
        }
    }

    @PatchMapping("/{laboId}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @PathVariable Long laboId,
            @RequestBody Map<String, String> body
    ) {
        Map<String, Object> res = new HashMap<>();
        try {
            String statusStr = body.get("status");
            String note = body.get("note");
            LaboOrderStatus status = LaboOrderStatus.valueOf(statusStr.toUpperCase());
            LaboOrderResponseDTO updated = laboOrderService.updateStatus(laboId, status, note);
            res.put("success", true);
            res.put("message", "Cập nhật trạng thái phiếu thành công!");
            res.put("data", updated);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("Error updating labo status: ", e);
            res.put("success", false);
            res.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(res);
        }
    }

    @PostMapping("/{laboId}/upload-image")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @PathVariable Long laboId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "directUrl", required = false) String directUrl
    ) {
        Map<String, Object> res = new HashMap<>();
        try {
            LaboOrderResponseDTO updated = laboOrderService.uploadImage(laboId, file, directUrl);
            res.put("success", true);
            res.put("message", "Đính kèm ảnh giấy xét nghiệm / phiếu labo thành công!");
            res.put("data", updated);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("Error uploading labo image: ", e);
            res.put("success", false);
            res.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(res);
        }
    }

    @DeleteMapping("/{laboId}")
    public ResponseEntity<Map<String, Object>> deleteOrder(@PathVariable Long laboId) {
        Map<String, Object> res = new HashMap<>();
        try {
            laboOrderService.deleteOrder(laboId);
            res.put("success", true);
            res.put("message", "Đã xóa phiếu đặt xưởng thành công!");
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("Error deleting labo order: ", e);
            res.put("success", false);
            res.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(res);
        }
    }

    @GetMapping("/suppliers")
    public ResponseEntity<Map<String, Object>> getSuppliers() {
        List<Supplier> suppliers = supplierRepository.findAll();
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", suppliers);
        return ResponseEntity.ok(res);
    }
}
