package com.cvdcms.controller;

import com.cvdcms.DTO.TreatmentPlanRequestDTO;
import com.cvdcms.DTO.TreatmentPlanResponseDTO;
import com.cvdcms.DTO.TreatmentPlanInstallmentDTO;
import com.cvdcms.DTO.InstallmentPaymentRequestDTO;
import com.cvdcms.DTO.PerformedServiceRequestDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.service.PerformedServiceService;
import com.cvdcms.service.TreatmentPlanService;
import com.cvdcms.repository.TreatmentPlanItemRepository;
import com.cvdcms.repository.VisitRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [Phân hệ]: Kế hoạch điều trị & Thu theo đợt (Treatment Plans & Installments)
 * [Vai trò]: REST Controller cung cấp API quản lý vòng đời kế hoạch điều trị (tạo, sửa, xóa, đổi trạng thái, bàn giao kế toán), thực hiện bước thủ thuật trong ca khám và quản lý lịch trả góp/thu tiền theo đợt, nhắc lịch thu tiền.
 */
@Slf4j
@RestController
@RequestMapping("/api/doctor/treatment-plans")
@RequiredArgsConstructor
public class TreatmentPlanRestController {

    private final TreatmentPlanService treatmentPlanService;
    private final PerformedServiceService performedServiceService;
    private final TreatmentPlanItemRepository treatmentPlanItemRepository;
    private final VisitRepository visitRepository;
    private final com.cvdcms.repository.PerformedServiceRepository performedServiceRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.EmrRecordRepository emrRecordRepository;

    /**
     * [Hàm hỗ trợ]: Lấy HttpSession hiện tại từ RequestContextHolder nếu đang nằm trong ngữ cảnh HTTP request.
     */
    private jakarta.servlet.http.HttpSession currentHttpSession() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra
                ? sra.getRequest().getSession(false) : null;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra lời gọi hiện tại có đang chạy trong ngữ cảnh HTTP Servlet Request hay không (phân biệt với unit test gọi trực tiếp).
     */
    private boolean isHttpRequestActive() {
        return org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes;
    }

    /**
     * [Chức năng]: Lấy danh sách kế hoạch điều trị theo mã hồ sơ bệnh nhân (GET /api/doctor/treatment-plans).
     * [Mục đích]: Kiểm tra quyền đọc hồ sơ bệnh nhân qua DoctorVisitAccess.requirePatientRead rồi gọi TreatmentPlanService.getPlansByPatientId trả về danh sách phác đồ kèm giai đoạn, thủ thuật và đợt thanh toán.
     */
    @GetMapping
    public ResponseEntity<List<TreatmentPlanResponseDTO>> getPlansByPatient(@RequestParam Long patientId) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requirePatientRead(currentHttpSession(), patientId);
        }
        List<TreatmentPlanResponseDTO> list = treatmentPlanService.getPlansByPatientId(patientId);
        return ResponseEntity.ok(list);
    }

    /**
     * [Chức năng]: Lấy thông tin chi tiết một kế hoạch điều trị theo ID (GET /api/doctor/treatment-plans/{id}).
     * [Mục đích]: Kiểm tra quyền đọc phác đồ qua DoctorVisitAccess.requireTreatmentPlanRead và gọi TreatmentPlanService.getPlanById trả về chi tiết tiến độ và lịch thanh toán.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TreatmentPlanResponseDTO> getPlanById(@PathVariable Long id) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireTreatmentPlanRead(currentHttpSession(), id);
        }
        TreatmentPlanResponseDTO dto = treatmentPlanService.getPlanById(id);
        return ResponseEntity.ok(dto);
    }

    /**
     * [Chức năng]: Tạo mới kế hoạch điều trị cho bệnh nhân trong lượt khám đang phục vụ (POST /api/doctor/treatment-plans - SCR-15.2).
     * [Mục đích]: Bắt buộc có visitId đang ở trạng thái khám lâm sàng hợp lệ, kiểm tra quyền chi nhánh và điều kiện bao gồm dịch vụ đặt trước, sau đó gọi TreatmentPlanService.createPlan để lưu phác đồ (tự động đánh dấu bàn giao kế toán) và trả về HTTP 201.
     */
    @PostMapping
    public ResponseEntity<?> createPlan(@RequestBody TreatmentPlanRequestDTO request) {
        if (request == null || request.getVisitId() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Phải gọi bệnh nhân vào khám trước khi lập phác đồ điều trị (thiếu mã lượt khám đang phục vụ)."
            ));
        }
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            try {
                jakarta.servlet.http.HttpSession session = currentHttpSession();
                doctorVisitAccess.requireActiveClinicalExam(session, request.getVisitId());
                if (request.getBranchId() != null) {
                    doctorVisitAccess.requireBranch(session, request.getBranchId());
                }
            } catch (org.springframework.web.server.ResponseStatusException ex) {
                return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                        "success", false,
                        "message", ex.getReason() != null
                                ? ex.getReason()
                                : "Bệnh nhân chưa được gọi vào khám, không thể lập phác đồ điều trị."
                ));
            }
        }
        try {
            if (doctorVisitAccess != null && isHttpRequestActive()) {
                doctorVisitAccess.assertTreatmentPlanIncludesBookedService(request.getVisitId(), request);
            }
            TreatmentPlanResponseDTO created = treatmentPlanService.createPlan(request);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã tạo kế hoạch điều trị thành công!");
            response.put("data", created);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Validation error createPlan: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Internal error createPlan: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi tạo kế hoạch điều trị: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * [Chức năng]: Cập nhật trạng thái của kế hoạch điều trị (PUT /api/doctor/treatment-plans/{id}/status).
     * [Mục đích]: Kiểm tra quyền ghi kế hoạch qua DoctorVisitAccess.requireTreatmentPlanWrite, gọi TreatmentPlanService.updatePlanStatus chuyển trạng thái (DRAFT, PROPOSED, ACCEPTED, IN_PROGRESS, COMPLETED, CANCELLED).
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestParam String status) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireTreatmentPlanWrite(currentHttpSession(), id);
        }
        try {
            TreatmentPlanResponseDTO updated = treatmentPlanService.updatePlanStatus(id, status);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã cập nhật trạng thái kế hoạch thành công!");
            response.put("data", updated);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * [Chức năng]: Cập nhật nội dung, danh sách dịch vụ, giai đoạn và lộ trình chia đợt của kế hoạch điều trị (PUT /api/doctor/treatment-plans/{id}).
     * [Mục đích]: Kiểm tra quyền chỉnh sửa kế hoạch và phạm vi chi nhánh, gọi TreatmentPlanService.updatePlan để đối soát các bước đã khóa/thực hiện và giữ nguyên lộ trình thanh toán nếu đã phát sinh thu tiền.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updatePlan(@PathVariable Long id, @RequestBody TreatmentPlanRequestDTO request) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            jakarta.servlet.http.HttpSession session = currentHttpSession();
            doctorVisitAccess.requireTreatmentPlanWrite(session, id);
            if (request != null && request.getBranchId() != null) {
                doctorVisitAccess.requireBranch(session, request.getBranchId());
            }
        }
        try {
            TreatmentPlanResponseDTO updated = treatmentPlanService.updatePlan(id, request);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã cập nhật kế hoạch điều trị thành công!");
            response.put("data", updated);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Internal error updatePlan: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi cập nhật kế hoạch điều trị: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * [Chức năng]: Bàn giao kế hoạch điều trị sang bộ phận Kế toán/Thu ngân xử lý thu tiền (POST /api/doctor/treatment-plans/{id}/handover-accounting).
     * [Mục đích]: Kiểm tra quyền ghi trên phác đồ qua DoctorVisitAccess.requireTreatmentPlanWrite, gọi TreatmentPlanService.handoverToAccounting đánh dấu cờ sentToAccounting = true và lưu mốc thời gian bàn giao để hiển thị trên màn hình thu ngân/kế toán.
     */
    @PostMapping("/{id}/handover-accounting")
    public ResponseEntity<?> handoverToAccounting(@PathVariable Long id) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireTreatmentPlanWrite(currentHttpSession(), id);
        }
        try {
            TreatmentPlanResponseDTO updated = treatmentPlanService.handoverToAccounting(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã chuyển kế hoạch điều trị sang bộ phận Kế toán thành công!");
            response.put("data", updated);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Lỗi khi chuyển kế hoạch điều trị sang Kế toán: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi chuyển kế hoạch sang Kế toán.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * [Chức năng]: Cập nhật trạng thái một giai đoạn trong kế hoạch điều trị (PUT /api/doctor/treatment-plans/stages/{stageId}/status - SCR-15.3).
     * [Mục đích]: Kiểm tra quyền thao tác giai đoạn qua DoctorVisitAccess.requireTreatmentStage, gọi TreatmentPlanService.updateStageStatus cập nhật mốc hoàn thành, đồng bộ các bước thủ thuật con, tiến độ phác đồ và quy trình lâm sàng.
     */
    @PutMapping("/stages/{stageId}/status")
    public ResponseEntity<?> updateStageStatus(@PathVariable Long stageId, @RequestParam String status) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireTreatmentStage(currentHttpSession(), stageId);
        }
        try {
            TreatmentPlanResponseDTO updatedPlan = treatmentPlanService.updateStageStatus(stageId, status);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã cập nhật trạng thái giai đoạn thành công!");
            response.put("data", updatedPlan);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error updating stage status: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * [Chức năng]: Cập nhật trạng thái từng bước thủ thuật/dịch vụ trong giai đoạn điều trị (PUT /api/doctor/treatment-plans/items/{itemId}/status).
     * [Mục đích]: Kiểm tra quyền thao tác trên bước kế hoạch qua DoctorVisitAccess.requireTreatmentPlanItem, gọi TreatmentPlanService.updateItemStatus cập nhật trạng thái thủ thuật, hoàn tác dịch vụ thực hiện nếu đưa về PLANNED/CANCELLED và đồng bộ tiến độ toàn kế hoạch.
     */
    @PutMapping("/items/{itemId}/status")
    public ResponseEntity<?> updateItemStatus(@PathVariable Long itemId, @RequestParam String status) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireTreatmentPlanItem(currentHttpSession(), itemId);
        }
        try {
            TreatmentPlanResponseDTO updatedPlan = treatmentPlanService.updateItemStatus(itemId, status);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã cập nhật trạng thái thủ thuật thành công!");
            response.put("data", updatedPlan);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error updating item status: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * [Chức năng]: Thực hiện một bước thủ thuật từ Kế hoạch điều trị ngay trong ca khám hiện tại (POST /api/doctor/treatment-plans/items/{itemId}/execute-in-visit - FT15 sang FT16).
     * [Mục đích]: Xác thực quyền bác sĩ/ca khám đang mở, kiểm tra trạng thái bước phác đồ chưa hủy/chưa hoàn thành/chưa ghi nhận trùng, kiểm tra khớp bệnh nhân và khóa bất biến EMR (BR-EMR-02), định giá 0 đ nếu thuộc phác đồ trả góp theo đợt (tránh thu trùng) hoặc lấy đơn giá dự kiến nếu đơn lẻ, tạo PerformedService qua PerformedServiceService và đồng bộ tiến độ kế hoạch.
     */
    @PostMapping("/items/{itemId}/execute-in-visit")
    public ResponseEntity<?> executeItemInVisit(
            @PathVariable Long itemId,
            @RequestParam Long visitId,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long assistantId,
            @RequestParam(required = false) String toothNumbers,
            @RequestParam(defaultValue = "false") boolean patientConfirmed) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            jakarta.servlet.http.HttpSession session = currentHttpSession();
            doctorVisitAccess.requireTreatmentPlanItem(session, itemId);
            doctorVisitAccess.requireActiveClinicalExam(session, visitId);
        }
        try {
            com.cvdcms.entity.TreatmentPlanItem item = treatmentPlanItemRepository.findById(itemId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bước thủ thuật phù hợp."));

            if (item.getStatus() == com.cvdcms.entity.enums.TreatmentPlanItemStatus.CANCELLED) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Bước thủ thuật này đã bị hủy, không thể thực hiện."
                ));
            }
            if (item.getPlan() != null && item.getPlan().getStatus() == com.cvdcms.entity.enums.TreatmentPlanStatus.CANCELLED) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Kế hoạch điều trị đã bị hủy, không thể thực hiện bước thủ thuật."
                ));
            }
            if (item.getStatus() == com.cvdcms.entity.enums.TreatmentPlanItemStatus.COMPLETED) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Bước thủ thuật này đã ở trạng thái Đã thực hiện, không thể thực hiện lại."
                ));
            }

            boolean alreadyPerformed = performedServiceRepository.existsByPlanItem_PlanItemIdAndStatusNot(
                    itemId, com.cvdcms.entity.enums.PerformedServiceStatus.CANCELLED);
            if (alreadyPerformed) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Bước phác đồ này đã được ghi nhận dịch vụ thực hiện, không thể tạo trùng lặp."
                ));
            }

            com.cvdcms.entity.Visit visit = visitRepository.findById(visitId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám phù hợp."));

            if (visit.getStatus() == com.cvdcms.entity.enums.VisitStatus.WAITING_PAYMENT ||
                visit.getStatus() == com.cvdcms.entity.enums.VisitStatus.COMPLETED ||
                visit.getStatus() == com.cvdcms.entity.enums.VisitStatus.CANCELLED) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Ca khám đã hoàn tất lâm sàng hoặc đã khóa/hủy (BR-EMR-02). Không thể thêm dịch vụ thực hiện."
                ));
            }
            com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                    visit,
                    emrRecordRepository,
                    "Bệnh án lâm sàng đã hoàn tất và khóa bất biến. Không thể thêm dịch vụ thực hiện");

            if (item.getPlan() != null && item.getPlan().getPatientProfile() != null &&
                !item.getPlan().getPatientProfile().getProfileId().equals(visit.getPatientProfile().getProfileId())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Ca khám không khớp với hồ sơ bệnh nhân của kế hoạch điều trị."
                ));
            }
            if (doctorVisitAccess != null && isHttpRequestActive()) {
                doctorVisitAccess.assertVisitServiceExecutionAllowed(
                        visitId,
                        item.getService() != null ? item.getService().getServiceId() : null,
                        itemId,
                        patientConfirmed);
            }

            Long docId;
            if (doctorVisitAccess != null) {
                if (isHttpRequestActive()) {
                    jakarta.servlet.http.HttpSession session = currentHttpSession();
                    com.cvdcms.entity.User currentUser = doctorVisitAccess.requireAuthenticatedUser(session);
                    if (!doctorVisitAccess.isAdmin(currentUser)) {
                        docId = doctorVisitAccess.resolveActingDoctor(session, visit, null).getStaffId();
                        if (doctorId != null && !doctorId.equals(docId)) {
                            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                                    "success", false,
                                    "message", "Chỉ quản trị viên mới được chỉ định bác sĩ thực hiện khác."
                            ));
                        }
                    } else {
                        docId = doctorVisitAccess.resolveActingDoctor(session, visit, doctorId).getStaffId();
                    }
                } else {
                    docId = doctorVisitAccess.resolveActingDoctor(currentHttpSession(), visit, doctorId).getStaffId();
                }
            } else if (doctorId != null) {
                docId = doctorId;
            } else if (visit.getPrimaryDoctor() != null) {
                docId = visit.getPrimaryDoctor().getStaffId();
            } else {
                throw new IllegalStateException("Ca khám chưa được phân công bác sĩ chính. Vui lòng phân công bác sĩ tại màn Điều phối trước khi thực hiện thủ thuật.");
            }

            String effectiveTeeth = (toothNumbers != null && !toothNumbers.isBlank())
                    ? toothNumbers.trim()
                    : (item.getToothNumbers() != null ? item.getToothNumbers().trim() : null);
            boolean requiresTooth = item.getService() != null && Boolean.TRUE.equals(item.getService().getRequiresTooth());
            if (requiresTooth && (effectiveTeeth == null || effectiveTeeth.isBlank())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Dịch vụ \"" + item.getService().getServiceName()
                                + "\" yêu cầu chọn vị trí răng FDI trước khi thực hiện. Vui lòng cập nhật số răng trên KHDT hoặc thực hiện qua Quy trình điều trị."
                ));
            }

            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
            if (toothNumbers != null && !toothNumbers.isBlank()) {
                item.setToothNumbers(effectiveTeeth);
                if (requiresTooth) {
                    long toothCount = java.util.regex.Pattern.compile("\\b[1-8][1-8]\\b")
                            .matcher(effectiveTeeth)
                            .results()
                            .map(java.util.regex.MatchResult::group)
                            .distinct()
                            .count();
                    if (toothCount > 0) {
                        qty = BigDecimal.valueOf(toothCount);
                        item.setQuantity(qty);
                    }
                }
                treatmentPlanItemRepository.save(item);
            }

            boolean isInstallmentPlan = item.getPlan() != null && Boolean.TRUE.equals(item.getPlan().getIsInstallment());
            BigDecimal unitPrice = isInstallmentPlan
                    ? BigDecimal.ZERO
                    : (item.getEstimatedPrice() != null
                        ? item.getEstimatedPrice()
                        : (item.getService() != null && item.getService().getBasePrice() != null
                            ? item.getService().getBasePrice()
                            : BigDecimal.ZERO));
            BigDecimal finalPrice = isInstallmentPlan ? BigDecimal.ZERO : unitPrice.multiply(qty);

            String stepDisplayName = item.getStepName() != null && !item.getStepName().isBlank()
                    ? item.getStepName()
                    : (item.getService() != null ? item.getService().getServiceName() : "Bước thủ thuật");
            String stageSuffix = item.getStage() != null ? " (" + item.getStage().getStageName() + ")" : "";
            String clinicalNotes = isInstallmentPlan
                    ? "Đã bao gồm trong KHĐT: " + stepDisplayName + stageSuffix
                    : "Từ KHDT: " + stepDisplayName + stageSuffix;

            PerformedServiceRequestDTO req = PerformedServiceRequestDTO.builder()
                    .visitId(visitId)
                    .serviceId(item.getService().getServiceId())
                    .stepName(stepDisplayName)
                    .toothNumbers(effectiveTeeth)
                    .quantity(qty)
                    .unitPrice(unitPrice)
                    .discountAmount(BigDecimal.ZERO)
                    .finalPrice(finalPrice)
                    .doctorId(docId)
                    .assistantId(assistantId)
                    .planItemId(itemId)
                    .clinicalNotes(clinicalNotes)
                    .build();

            PerformedServiceResponseDTO ps = performedServiceService.createPerformedService(req);
            TreatmentPlanResponseDTO planDto = treatmentPlanService.syncPlanProgressAndStatus(item.getPlan().getPlanId());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", isInstallmentPlan
                    ? "Đã ghi nhận thủ thuật vào ca khám (0 đ — đã nằm trong gói phác đồ trả theo đợt)!"
                    : "Đã thực hiện dịch vụ trong ca khám hôm nay & hoàn tất bước trong lộ trình!");
            response.put("performedService", ps);
            response.put("plan", planDto);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error executing plan item in visit: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * [Chức năng]: Xóa kế hoạch điều trị (DELETE /api/doctor/treatment-plans/{id}).
     * [Mục đích]: Kiểm tra quyền ghi kế hoạch qua DoctorVisitAccess.requireTreatmentPlanWrite, gọi TreatmentPlanService.deletePlan để kiểm tra ràng buộc chưa thanh toán/chưa xuất hóa đơn, gỡ liên kết dịch vụ/quy trình và xóa phác đồ.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePlan(@PathVariable Long id) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireTreatmentPlanWrite(currentHttpSession(), id);
        }
        try {
            treatmentPlanService.deletePlan(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã xóa kế hoạch điều trị thành công!");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * [Chức năng]: Lấy danh sách các đợt thanh toán trả góp của một kế hoạch điều trị (GET /api/doctor/treatment-plans/{id}/installments).
     * [Mục đích]: Kiểm tra quyền đọc phác đồ và gọi TreatmentPlanService.getPlanInstallments để hiển thị lịch thu theo đợt, số tiền dự kiến, đã thu và còn lại.
     */
    @GetMapping("/{id}/installments")
    public ResponseEntity<List<TreatmentPlanInstallmentDTO>> getInstallments(@PathVariable Long id) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireTreatmentPlanRead(currentHttpSession(), id);
        }
        return ResponseEntity.ok(treatmentPlanService.getPlanInstallments(id));
    }

    /**
     * [Chức năng]: Thiết lập hoặc cập nhật lịch chia đợt thanh toán (lịch trả góp) cho kế hoạch điều trị (POST /api/doctor/treatment-plans/{id}/installments).
     * [Mục đích]: Kiểm tra quyền thao tác tài chính phác đồ qua DoctorVisitAccess.requireTreatmentPlanFinanceWrite, gọi TreatmentPlanService.saveInstallments để lưu lịch các đợt khi kế hoạch chưa phát sinh thanh toán và bảo đảm tổng tiền các đợt không vượt chi phí phác đồ.
     */
    @PostMapping("/{id}/installments")
    public ResponseEntity<?> saveInstallments(
            @PathVariable Long id,
            @RequestBody List<TreatmentPlanInstallmentDTO> installmentDTOs) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireTreatmentPlanFinanceWrite(currentHttpSession(), id);
        }
        try {
            TreatmentPlanResponseDTO updated = treatmentPlanService.saveInstallments(id, installmentDTOs);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã cập nhật lộ trình thanh toán theo đợt thành công!");
            response.put("data", updated);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * [Chức năng]: Ghi nhận giao dịch thu tiền theo đợt linh hoạt cho kế hoạch điều trị trả góp (POST /api/doctor/treatment-plans/{id}/installments/pay).
     * [Mục đích]: Xác thực người thu ngân từ HttpSession (trả 401 nếu chưa đăng nhập), gọi TreatmentPlanService.recordInstallmentPayment để kiểm tra quyền PAYMENT_COLLECT, hạch toán bảng Payment (hỗ trợ tiền mặt/chuyển khoản/POS/ví cọc), phân bổ tự động số tiền thu vào các đợt trả góp và cập nhật tổng đã thu; trả về HTTP 403 nếu không đủ quyền.
     */
    @PostMapping("/{id}/installments/pay")
    public ResponseEntity<?> recordInstallmentPayment(
            @PathVariable Long id,
            @RequestBody InstallmentPaymentRequestDTO request) {
        Long cashierUserId = null;
        if (isHttpRequestActive()) {
            jakarta.servlet.http.HttpSession session = currentHttpSession();
            Object loggedObj = session != null ? session.getAttribute("loggedInUser") : null;
            if (!(loggedObj instanceof com.cvdcms.entity.User u) || u.getUserId() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "message", "Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục."));
            }
            cashierUserId = u.getUserId();
        }
        try {
            TreatmentPlanResponseDTO updated = treatmentPlanService.recordInstallmentPayment(
                    id, request, cashierUserId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã ghi nhận thanh toán thành công!");
            response.put("data", updated);
            return ResponseEntity.ok(response);
        } catch (org.springframework.security.access.AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", ade.getMessage()));
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * [Chức năng]: Đánh dấu đã liên hệ nhắc lịch thanh toán đợt tới cho bệnh nhân (POST /api/doctor/treatment-plans/installments/{installmentId}/remind).
     * [Mục đích]: Kiểm tra quyền thao tác trên đợt thanh toán qua DoctorVisitAccess.requireTreatmentInstallment, gọi TreatmentPlanService.markReminderSent lưu trạng thái đã nhắc, thời điểm nhắc và ghi chú trao đổi của lễ tân.
     */
    @PostMapping("/installments/{installmentId}/remind")
    public ResponseEntity<?> markReminderSent(
            @PathVariable Long installmentId,
            @RequestBody(required = false) Map<String, String> body) {
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            doctorVisitAccess.requireTreatmentInstallment(currentHttpSession(), installmentId);
        }
        try {
            String notes = body != null ? body.get("notes") : null;
            TreatmentPlanInstallmentDTO updated = treatmentPlanService.markReminderSent(installmentId, notes);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã ghi nhận nhắc lịch thanh toán cho bệnh nhân!");
            response.put("data", updated);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * [Chức năng]: Tra cứu danh sách phân trang các đợt thanh toán sắp tới hạn hoặc quá hạn (GET /api/doctor/treatment-plans/installments/upcoming).
     * [Mục đích]: Hỗ trợ lễ tân/thu ngân theo dõi và nhắc hẹn thu tiền theo đợt; tự động ràng buộc phạm vi chi nhánh được phép truy cập của nhân sự hiện tại, lọc theo khoảng ngày dự kiến, trạng thái đợt và từ khóa qua TreatmentPlanService.searchUpcomingInstallments.
     */
    @GetMapping("/installments/upcoming")
    public ResponseEntity<?> getUpcomingInstallments(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long scopedBranchId = branchId;
        java.util.Set<Long> allowedBranches = null;
        if (doctorVisitAccess != null && isHttpRequestActive()) {
            jakarta.servlet.http.HttpSession session = currentHttpSession();
            com.cvdcms.entity.User user = doctorVisitAccess.requireAuthenticatedUser(session);
            if (branchId != null) {
                doctorVisitAccess.requireBranch(session, branchId);
            } else {
                allowedBranches = doctorVisitAccess.resolveAccessibleBranchIds(user);
                if (allowedBranches != null) {
                    if (allowedBranches.isEmpty()) {
                        return ResponseEntity.ok(org.springframework.data.domain.Page.empty());
                    }
                    if (allowedBranches.size() == 1) {
                        scopedBranchId = allowedBranches.iterator().next();
                    }
                }
            }
        }
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        if (allowedBranches != null && allowedBranches.size() > 1 && scopedBranchId == null) {
            org.springframework.data.domain.Page<TreatmentPlanInstallmentDTO> allAllowed = treatmentPlanService.searchUpcomingInstallments(
                    null, startDate, endDate, status, keyword, org.springframework.data.domain.PageRequest.of(0, 500));
            java.util.Set<Long> filterSet = allowedBranches;
            List<TreatmentPlanInstallmentDTO> filtered = allAllowed.getContent().stream()
                    .filter(item -> item.getBranchId() != null && filterSet.contains(item.getBranchId()))
                    .toList();
            int fromIdx = Math.min(page * size, filtered.size());
            int toIdx = Math.min(fromIdx + size, filtered.size());
            return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(filtered.subList(fromIdx, toIdx), pageable, filtered.size()));
        }
        org.springframework.data.domain.Page<TreatmentPlanInstallmentDTO> result = treatmentPlanService.searchUpcomingInstallments(
                scopedBranchId, startDate, endDate, status, keyword, pageable);
        return ResponseEntity.ok(result);
    }
}
