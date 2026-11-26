package com.cvdcms.controller;

import com.cvdcms.DTO.ClinicalCompletionCheckDTO;
import com.cvdcms.DTO.ClinicalCompletionRequestDTO;
import com.cvdcms.entity.Visit;
import com.cvdcms.service.DiagnosisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/doctor/visits")
@RequiredArgsConstructor
public class DoctorExaminationRestController {

    private final DiagnosisService diagnosisService;
    private final com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ClinicQueueCoordinatorService queueCoordinator;

    private Long resolveEffectiveBranchForQueue(com.cvdcms.entity.User user, Long requestedBranchId, jakarta.servlet.http.HttpSession session) {
        if (requestedBranchId != null) {
            return requestedBranchId;
        }
        if (user != null && doctorVisitAccess.isAdmin(user) && session != null
                && session.getAttribute("currentBranchId") instanceof Long sBranchId) {
            return sBranchId;
        }
        return null;
    }

    /**
     * Lấy trạng thái bàn khám hiện tại của bác sĩ (lượt đang gọi, đang khám, số lượng chờ, cảnh báo cấp cứu).
     * Chỉ đọc, không tự chuyển trạng thái hàng đợi.
     */
    @GetMapping("/queue-status")
    public ResponseEntity<com.cvdcms.service.ClinicQueueCoordinatorService.DoctorDeskStatusDTO> getDoctorDeskQueueStatus(
            @RequestParam(value = "branchId", required = false) Long branchId,
            jakarta.servlet.http.HttpSession session) {
        com.cvdcms.entity.User user = doctorVisitAccess.requireAuthenticatedUser(session);
        Long effectiveBranchId = resolveEffectiveBranchForQueue(user, branchId, session);
        return ResponseEntity.ok(queueCoordinator.getDoctorDeskStatus(user, effectiveBranchId));
    }

    /**
     * Nút "Vào khám" chung của bác sĩ: xác định đúng lượt Đang gọi (hoặc mở lại ca Đang khám hiện tại).
     */
    @PostMapping("/enter-exam")
    public ResponseEntity<Map<String, Object>> enterExamination(
            @RequestParam(value = "branchId", required = false) Long branchId,
            jakarta.servlet.http.HttpSession session) {
        com.cvdcms.entity.User user = doctorVisitAccess.requireAuthenticatedUser(session);
        Long effectiveBranchId = resolveEffectiveBranchForQueue(user, branchId, session);
        try {
            var res = queueCoordinator.enterExaminationForDoctor(user, effectiveBranchId);
            Map<String, Object> body = new HashMap<>();
            body.put("success", true);
            body.put("visitId", res.visit().getVisitId());
            body.put("visitToken", res.visitToken());
            body.put("visitCode", res.visit().getVisitCode());
            body.put("displayNumber", res.ticket().getDisplayNumber());
            body.put("patientName", res.visit().getPatientProfile() != null ? res.visit().getPatientProfile().getFullName() : "");
            body.put("reopenedActiveSession", res.reopenedActiveSession());
            body.put("redirectUrl", "/doctor/examination/" + res.visitToken() + "/diagnoses");
            return ResponseEntity.ok(body);
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                    "success", false,
                    "message", ex.getReason() != null ? ex.getReason() : "Không thể vào khám."));
        }
    }

    /**
     * Bác sĩ ghi nhận "Vắng mặt" đối với đúng lượt đang ở trạng thái Đang gọi, lưu dấu vết và tự gọi lượt kế tiếp.
     */
    @PostMapping("/call-absent")
    public ResponseEntity<Map<String, Object>> markCalledPatientAbsent(
            @RequestParam(value = "branchId", required = false) Long branchId,
            @RequestBody(required = false) Map<String, Object> body,
            jakarta.servlet.http.HttpSession session) {
        com.cvdcms.entity.User user = doctorVisitAccess.requireAuthenticatedUser(session);
        Long ticketId = null;
        String reason = null;
        if (body != null) {
            Object rawTicketId = body.get("ticketId");
            if (rawTicketId instanceof Number num) {
                ticketId = num.longValue();
            } else if (rawTicketId != null && !rawTicketId.toString().isBlank()) {
                try {
                    ticketId = Long.parseLong(rawTicketId.toString().trim());
                } catch (NumberFormatException ignored) {
                }
            }
            Object rawReason = body.get("reason");
            if (rawReason != null) {
                reason = rawReason.toString();
            }
        }
        if (ticketId == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "message", "Thiếu mã phiếu đang gọi (ticketId) để xác nhận vắng mặt."));
        }
        try {
            Long effectiveBranchId = resolveEffectiveBranchForQueue(user, branchId, session);
            var result = queueCoordinator.markCalledPatientAbsent(user, effectiveBranchId, ticketId, reason);
            var deskStatus = queueCoordinator.getDoctorDeskStatus(user, effectiveBranchId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("absentDisplayNumber", result.absentTicket().getDisplayNumber());
            resp.put("nextCalledDisplayNumber", result.nextCalledTicket() != null ? result.nextCalledTicket().getDisplayNumber() : null);
            resp.put("message", result.nextCalledTicket() != null
                    ? "Đã ghi nhận vắng mặt lượt " + result.absentTicket().getDisplayNumber() + ". Hệ thống tự động gọi lượt tiếp theo: " + result.nextCalledTicket().getDisplayNumber() + "."
                    : "Đã ghi nhận vắng mặt lượt " + result.absentTicket().getDisplayNumber() + ". Hiện không có bệnh nhân đang chờ.");
            resp.put("deskStatus", deskStatus);
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                    "success", false,
                    "message", ex.getReason() != null ? ex.getReason() : "Không thể ghi nhận vắng mặt."));
        }
    }

    /**
     * Lấy danh sách bác sĩ đủ quyền chuyên môn, trạng thái rảnh/bận và sơ đồ phòng cho Modal Chuyển BS.
     */
    @GetMapping("/{visitId}/handover-options")
    public ResponseEntity<?> getHandoverOptions(
            @PathVariable Long visitId,
            @RequestParam(value = "serviceId", required = false) Long serviceId,
            jakarta.servlet.http.HttpSession session) {
        doctorVisitAccess.require(session, visitId);
        com.cvdcms.entity.User user = doctorVisitAccess.requireAuthenticatedUser(session);
        return ResponseEntity.ok(queueCoordinator.getHandoverOptions(user, visitId, serviceId));
    }

    /**
     * Chuyển ca bệnh sang bác sĩ khác qua Hàng đợi ưu tiên + giải phóng ghế hiện tại + cập nhật TV.
     */
    @PostMapping("/{visitId}/handover")
    public ResponseEntity<Map<String, Object>> handoverToDoctor(
            @PathVariable Long visitId,
            @RequestBody Map<String, Object> body,
            jakarta.servlet.http.HttpSession session) {
        doctorVisitAccess.requireActiveClinicalExam(session, visitId);
        com.cvdcms.entity.User user = doctorVisitAccess.requireAuthenticatedUser(session);
        Long targetDoctorId = parseLongObj(body != null ? body.get("targetDoctorId") : null);
        Long targetRoomId = parseLongObj(body != null ? body.get("targetRoomId") : null);
        Long serviceId = parseLongObj(body != null ? body.get("serviceId") : null);
        String serviceName = body != null && body.get("serviceName") != null ? body.get("serviceName").toString() : null;
        String note = body != null && body.get("note") != null ? body.get("note").toString() : null;
        boolean returnToSender = body != null && Boolean.TRUE.equals(body.get("returnToSender"));
        try {
            var res = queueCoordinator.handoverVisitToDoctor(
                    user, visitId, targetDoctorId, targetRoomId, serviceId, serviceName, note, returnToSender);
            Map<String, Object> out = new HashMap<>();
            out.put("success", res.success());
            out.put("visitId", res.visitId());
            out.put("targetDoctorId", res.targetDoctorId());
            out.put("targetDoctorName", res.targetDoctorName());
            out.put("targetRoomId", res.targetRoomId());
            out.put("targetRoomName", res.targetRoomName());
            out.put("targetCalledImmediately", res.targetCalledImmediately());
            out.put("nextOutgoingDisplayNumber", res.nextOutgoingDisplayNumber());
            out.put("message", res.message());
            return ResponseEntity.ok(out);
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                    "success", false,
                    "message", ex.getReason() != null ? ex.getReason() : "Không thể chuyển bác sĩ."));
        } catch (Exception ex) {
            log.error("Lỗi khi chuyển bác sĩ ca khám {}: ", visitId, ex);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage() != null ? ex.getMessage() : "Lỗi khi chuyển bác sĩ."));
        }
    }

    private Long parseLongObj(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number num) {
            return num.longValue();
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Kiểm tra điều kiện hoàn tất ca khám lâm sàng (ICD-10, dịch vụ COMPLETED, etc.)
     */
    @GetMapping("/{visitId}/completion-check")
    public ResponseEntity<ClinicalCompletionCheckDTO> checkCompletionReadiness(@PathVariable Long visitId, jakarta.servlet.http.HttpSession session) {
        doctorVisitAccess.require(session, visitId);
        ClinicalCompletionCheckDTO checkDTO = diagnosisService.checkClinicalCompletionReadiness(visitId);
        return ResponseEntity.ok(checkDTO);
    }

    /**
     * Bác sĩ bấm "Hoàn tất ca khám & Chuyển thanh toán"
     * Cập nhật visit.status = 'WAITING_PAYMENT', khóa bệnh án lâm sàng (BR-EMR-02)
     */
    @PostMapping("/{visitId}/complete-clinical")
    public ResponseEntity<Map<String, Object>> completeClinicalExamination(
            @PathVariable Long visitId,
            @RequestBody(required = false) ClinicalCompletionRequestDTO request,
            jakarta.servlet.http.HttpSession session
    ) {
        doctorVisitAccess.requireActiveClinicalExam(session, visitId);
        String doctorNotes = (request != null) ? request.getDoctorNotes() : null;
        try {
            Visit updatedVisit = diagnosisService.completeClinicalExamination(visitId, doctorNotes);

            String message = (updatedVisit.getStatus() == com.cvdcms.entity.enums.VisitStatus.COMPLETED)
                    ? "Đã hoàn tất ca khám lâm sàng thành công (Khóa bệnh án BR-EMR-02)!"
                    : "Đã hoàn tất ca khám và chuyển viện phí sang Kế toán (Chờ thanh toán) thành công!";

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", message);
            response.put("visitId", updatedVisit.getVisitId());
            response.put("visitCode", updatedVisit.getVisitCode());
            response.put("status", updatedVisit.getStatus().name());

            return ResponseEntity.ok(response);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception ex) {
            log.error("Lỗi khi hoàn tất ca khám lâm sàng: ", ex);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi hoàn tất ca khám: " + ex.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Mở lại ca khám lâm sàng (Chỉ cho phép khi đang ở trạng thái WAITING_PAYMENT và Kế toán chưa xuất hóa đơn)
     */
    @PostMapping("/{visitId}/reopen-clinical")
    public ResponseEntity<Map<String, Object>> reopenClinicalExamination(
            @PathVariable Long visitId,
            @RequestBody(required = false) Map<String, String> body,
            jakarta.servlet.http.HttpSession session
    ) {
        doctorVisitAccess.require(session, visitId);
        String reason = (body != null) ? body.get("reason") : null;
        try {
            Visit updatedVisit = diagnosisService.reopenClinicalExamination(visitId, reason);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Đã mở lại ca khám lâm sàng thành công. Bác sĩ có thể tiếp tục bổ sung thông tin.");
            response.put("visitId", updatedVisit.getVisitId());
            response.put("status", updatedVisit.getStatus().name());

            return ResponseEntity.ok(response);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception ex) {
            log.error("Lỗi khi mở lại ca khám lâm sàng: ", ex);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Lỗi máy chủ khi mở lại ca khám: " + ex.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Lấy thông tin khám tổng quát (Triệu chứng, Tiền sử dị ứng/bệnh nền, Mô mềm/TMJ)
     */
    @GetMapping("/{visitId}/general-examination")
    public ResponseEntity<com.cvdcms.DTO.GeneralExaminationRequestDTO> getGeneralExamination(@PathVariable Long visitId, jakarta.servlet.http.HttpSession session) {
        doctorVisitAccess.require(session, visitId);
        return ResponseEntity.ok(diagnosisService.getGeneralExamination(visitId));
    }

    /**
     * Bác sĩ lưu kết quả khám tổng quát
     */
    @PostMapping("/{visitId}/general-examination")
    public ResponseEntity<Map<String, Object>> saveGeneralExamination(
            @PathVariable Long visitId,
            @RequestBody com.cvdcms.DTO.GeneralExaminationRequestDTO request,
            jakarta.servlet.http.HttpSession session
    ) {
        doctorVisitAccess.requireActiveClinicalExam(session, visitId);
        Map<String, Object> response = new HashMap<>();
        try {
            if (request == null) {
                request = new com.cvdcms.DTO.GeneralExaminationRequestDTO();
            }
            request.setVisitId(visitId);
            diagnosisService.saveGeneralExamination(request);

            response.put("success", true);
            response.put("message", "Đã lưu thông tin khám tổng quát thành công!");
            return ResponseEntity.ok(response);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            response.put("success", false);
            response.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception ex) {
            log.error("Lỗi khi lưu khám tổng quát ca khám {}: ", visitId, ex);
            response.put("success", false);
            response.put("message", "Lỗi máy chủ khi lưu thông tin khám tổng quát: " + ex.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Bác sĩ lưu / cập nhật lời dặn và hướng dẫn chăm sóc tại nhà (SCR-18.x)
     */
    @PostMapping("/{visitId}/instructions")
    public ResponseEntity<Map<String, Object>> saveDoctorInstructions(
            @PathVariable Long visitId,
            @RequestBody Map<String, String> body,
            jakarta.servlet.http.HttpSession session
    ) {
        doctorVisitAccess.requireActiveClinicalExam(session, visitId);
        Map<String, Object> response = new HashMap<>();
        try {
            String adviceNotes = (body != null) ? body.get("adviceNotes") : "";
            diagnosisService.saveDoctorInstructions(visitId, adviceNotes);

            response.put("success", true);
            response.put("message", "Đã lưu lời dặn của bác sĩ thành công!");
            response.put("adviceNotes", adviceNotes);
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            log.error("Lỗi khi lưu lời dặn bác sĩ ca khám {}: ", visitId, ex);
            response.put("success", false);
            response.put("message", "Lỗi khi lưu lời dặn bác sĩ: " + ex.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}
