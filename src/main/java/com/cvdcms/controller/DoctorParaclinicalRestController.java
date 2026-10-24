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
@RequiredArgsConstructor
public class DoctorParaclinicalRestController {

    private final ParaclinicalOrderService paraclinicalOrderService;

    /**
     * SCR-11.3: Lấy danh sách chỉ định cận lâm sàng theo lượt khám
     */
    @GetMapping("/visits/{visitId}/paraclinical-orders")
    public ResponseEntity<List<ParaclinicalOrderItemResponseDTO>> getOrdersByVisit(
            @PathVariable("visitId") Long visitId
    ) {
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
            @RequestBody ParaclinicalOrderCreateDTO dto
    ) {
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
     * Cập nhật trạng thái chỉ định (WAITING -> COMPLETED)
     */
    @PutMapping("/paraclinical-orders/{orderItemId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable("orderItemId") Long orderItemId,
            @RequestBody Map<String, String> payload
    ) {
        try {
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
    public ResponseEntity<?> deleteOrder(@PathVariable("orderItemId") Long orderItemId) {
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
}
