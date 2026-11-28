package com.cvdcms.controller;

import com.cvdcms.DTO.PerformedServiceRequestDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.service.PerformedServiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [Phân hệ]: Dịch vụ thực hiện lâm sàng (Performed Services)
 * [Vai trò]: REST Controller cung cấp API cho bác sĩ tra cứu danh mục dịch vụ, xem, ghi nhận hoàn thành và hủy/xóa dịch vụ thực hiện trong ca khám.
 */
@Slf4j
@RestController
@RequestMapping("/api/doctor")
@RequiredArgsConstructor
public class PerformedServiceRestController {

    private final PerformedServiceService performedServiceService;
    private final com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    /**
     * [Chức năng]: Lấy danh sách danh mục dịch vụ nha khoa đang hoạt động (GET /api/doctor/services/catalog).
     * [Mục đích]: Phục vụ màn hình khám bệnh tải danh sách dịch vụ ACTIVE (thuộc nhóm dịch vụ đang mở) để bác sĩ chỉ định thực hiện hoặc lập kế hoạch điều trị.
     */
    @GetMapping("/services/catalog")
    public ResponseEntity<List<ServiceDTO>> getActiveServicesCatalog() {
        return ResponseEntity.ok(performedServiceService.getAllActiveServices());
    }

    /**
     * [Chức năng]: Lấy danh sách các dịch vụ đã thực hiện của một lượt khám (GET /api/doctor/performed-services).
     * [Mục đích]: Kiểm tra quyền truy cập lượt khám qua DoctorVisitAccess.require, sau đó gọi PerformedServiceService lấy chi tiết các dịch vụ thực hiện theo visitId.
     */
    @GetMapping("/performed-services")
    public ResponseEntity<List<PerformedServiceResponseDTO>> getPerformedServicesByVisit(
            @RequestParam("visitId") Long visitId, jakarta.servlet.http.HttpSession session
    ) {
        doctorVisitAccess.require(session, visitId);
        return ResponseEntity.ok(performedServiceService.getPerformedServicesByVisitId(visitId));
    }

    /**
     * [Chức năng]: Ghi nhận một dịch vụ/thủ thuật thực hiện mới trong ca khám (POST /api/doctor/performed-services).
     * [Mục đích]: Kiểm tra lượt khám đang trong phiên khám lâm sàng hợp lệ và điều kiện cho phép thực hiện dịch vụ (kể cả ràng buộc dịch vụ đặt trước/kế hoạch), gọi PerformedServiceService.createPerformedService để lưu bản ghi trạng thái COMPLETED, trừ vật tư và tính hoa hồng, trả về HTTP 201 hoặc 400 nếu vi phạm nghiệp vụ.
     */
    @PostMapping("/performed-services")
    public ResponseEntity<Map<String, Object>> createPerformedService(
            @Valid @RequestBody PerformedServiceRequestDTO requestDTO, jakarta.servlet.http.HttpSession session
    ) {
        doctorVisitAccess.requireActiveClinicalExam(session, requestDTO.getVisitId());
        log.info("API createPerformedService called with: visitId={}, serviceId={}",
                requestDTO.getVisitId(), requestDTO.getServiceId());

        try {
            doctorVisitAccess.assertVisitServiceExecutionAllowed(
                    requestDTO.getVisitId(), requestDTO.getServiceId(), requestDTO.getPlanItemId(), true);
            PerformedServiceResponseDTO responseDTO = performedServiceService.createPerformedService(requestDTO);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Ghi nhận dịch vụ thực hiện thành công (COMPLETED)!");
            result.put("data", responseDTO);

            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * [Chức năng]: Hủy hoặc xóa một bản ghi dịch vụ thực hiện trong ca khám (DELETE /api/doctor/performed-services/{id}).
     * [Mục đích]: Xác thực quyền thao tác trên bản ghi dịch vụ thực hiện qua DoctorVisitAccess.requirePerformedService, sau đó gọi PerformedServiceService.cancelPerformedService để hoàn tác vật tư, tiến độ kế hoạch điều trị, quy trình lâm sàng và hoa hồng chờ duyệt.
     */
    @DeleteMapping("/performed-services/{id}")
    public ResponseEntity<Map<String, Object>> cancelPerformedService(
            @PathVariable("id") Long id, jakarta.servlet.http.HttpSession session
    ) {
        doctorVisitAccess.requirePerformedService(session, id);
        log.info("API cancelPerformedService called with id={}", id);
        try {
            performedServiceService.cancelPerformedService(id);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Đã xóa dịch vụ thực hiện thành công!");

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
}
