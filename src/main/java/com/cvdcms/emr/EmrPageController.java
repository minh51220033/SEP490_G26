package com.cvdcms.emr;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/emr/records")
public class EmrPageController {
    private final EmrModuleService service;

    public EmrPageController(EmrModuleService service) {
        this.service = service;
    }

    @ModelAttribute("roleNames")
    public Collection<?> roleNames(HttpSession session) {
        Object names = session == null ? null : session.getAttribute("roleNames");
        return names instanceof Collection<?> collection ? collection : List.of();
    }

    @ModelAttribute("displayName")
    public Object displayName(HttpSession session) {
        return session == null ? null : session.getAttribute("displayName");
    }

    @GetMapping
    public String list(@RequestParam(required = false) String query, RedirectAttributes redirect) {
        if (query != null && !query.isBlank()) {
            redirect.addAttribute("query", query.trim());
        }
        return "redirect:/doctor/patient-records";
    }

    @GetMapping("/{recordId}")
    public String detail(HttpSession session, Model model, @PathVariable Long recordId) {
        model.addAttribute("detail", service.detail(session, recordId));
        return "emr/detail";
    }

    @PostMapping
    public String create(HttpSession session, @RequestParam Long visitId, RedirectAttributes redirect) {
        try {
            Long id = service.getOrCreate(session, visitId);
            redirect.addFlashAttribute("success", "Đã mở / tạo bản nháp bệnh án điện tử.");
            return "redirect:/emr/records/" + id;
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", message(ex));
            redirect.addFlashAttribute("documentError", message(ex));
            return "redirect:/doctor/patient-records";
        }
    }

    @GetMapping("/from-visit/{visitId}")
    public String fromVisitGet(HttpSession session, @PathVariable Long visitId, RedirectAttributes redirect) {
        try {
            Long id = service.getOrCreate(session, visitId);
            return "redirect:/emr/records/" + id;
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", message(ex));
            redirect.addFlashAttribute("documentError", message(ex));
            return "redirect:/doctor/patient-records";
        }
    }

    @PostMapping("/from-visit/{visitId}")
    public String fromVisitPost(HttpSession session, @PathVariable Long visitId, RedirectAttributes redirect) {
        try {
            Long id = service.getOrCreate(session, visitId);
            redirect.addFlashAttribute("success", "Đã mở / tạo bản nháp bệnh án điện tử từ ca khám.");
            return "redirect:/emr/records/" + id;
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", message(ex));
            redirect.addFlashAttribute("documentError", message(ex));
            return "redirect:/doctor/patient-records";
        }
    }

    @PostMapping(path = {"/{recordId}/refresh", "/{recordId}/sync-examination", "/{recordId}/sync"})
    public String refresh(HttpSession session, @PathVariable Long recordId, RedirectAttributes redirect) {
        return run(recordId, redirect, "Đã đồng bộ lại dữ liệu khám nguồn mới nhất.",
                () -> service.refresh(session, recordId));
    }

    @PostMapping("/{recordId}/patient-demographics")
    public String updatePatientDemographics(HttpSession session, @PathVariable Long recordId,
            @RequestParam Map<String, String> fields, RedirectAttributes redirect) {
        return run(recordId, redirect, "Đã cập nhật thông tin hành chính bệnh nhân.",
                () -> service.updatePatientDemographics(session, recordId, fields));
    }

    @PostMapping(path = {"/{recordId}/delete", "/{recordId}/soft-delete"})
    public String deleteRecord(HttpSession session, @PathVariable Long recordId, RedirectAttributes redirect) {
        Long patientId = null;
        try {
            var d = service.detail(session, recordId);
            if (d != null && d.record() != null) {
                patientId = d.record().patientId();
            }
            service.deleteDraft(session, recordId);
            redirect.addFlashAttribute("success", "Đã xóa bản nháp bệnh án điện tử thành công.");
            redirect.addFlashAttribute("documentSuccess", "Đã xóa bản nháp bệnh án điện tử thành công.");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", message(ex));
            redirect.addFlashAttribute("documentError", message(ex));
        }
        if (patientId != null) {
            return "redirect:/doctor/patient-records/" + patientId + "#medical";
        }
        return "redirect:/doctor/patient-records";
    }

    @PostMapping("/{recordId}/validate")
    public String validate(HttpSession session, @PathVariable Long recordId, RedirectAttributes redirect) {
        try {
            var result = service.validate(session, recordId);
            if (result.ready()) redirect.addFlashAttribute("success", "Hồ sơ đầy đủ và sẵn sàng khóa.");
            else redirect.addFlashAttribute("error", "Hồ sơ còn thiếu: " + String.join("; ", result.missing()));
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", message(ex));
        }
        return "redirect:/emr/records/" + recordId;
    }

    @PostMapping("/{recordId}/lock")
    public String lock(HttpSession session, @PathVariable Long recordId, RedirectAttributes redirect) {
        return run(recordId, redirect, "Đã khóa phiên bản. Nội dung không còn được chỉnh sửa.",
                () -> service.lock(session, recordId));
    }

    @PostMapping(path = {"/{recordId}/doctor-sign", "/{recordId}/doctor-sign-test"})
    public String doctorSign(HttpSession session, @PathVariable Long recordId,
            @RequestParam(defaultValue = "false") boolean confirmed, RedirectAttributes redirect) {
        return run(recordId, redirect, "Đã hoàn tất chứng thực chữ ký số của bác sĩ điều trị.",
                () -> service.doctorSign(session, recordId, confirmed));
    }

    @PostMapping("/{recordId}/amendments")
    public String amendment(HttpSession session, @PathVariable Long recordId,
            @RequestParam String reason, RedirectAttributes redirect) {
        return run(recordId, redirect, "Đã tạo phiên bản sửa đổi mới.",
                () -> service.createAmendment(session, recordId, reason));
    }

    @PostMapping("/{recordId}/documents/{documentCode}")
    public String updateDocument(HttpSession session, @PathVariable Long recordId,
            @PathVariable String documentCode, @RequestParam Map<String, String> fields,
            RedirectAttributes redirect) {
        try {
            service.updateDocument(session, recordId, documentCode, fields);
            redirect.addFlashAttribute("success", "Đã lưu nội dung bổ sung của biểu mẫu.");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", message(ex));
        }
        return "redirect:/emr/records/" + recordId + "?document=" + documentCode + "&mode=edit";
    }

    @GetMapping("/{recordId}/export.json")
    @ResponseBody
    public ResponseEntity<byte[]> exportJson(HttpSession session, @PathVariable Long recordId) {
        String json = service.exportJson(session, recordId);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=emr-" + recordId + "-vietnamese.json")
                .contentLength(body.length)
                .body(body);
    }

    @GetMapping("/{recordId}/attachments/{attachmentId}")
    @ResponseBody
    public ResponseEntity<byte[]> attachment(HttpSession session, @PathVariable Long recordId,
            @PathVariable Long attachmentId) {
        var file = service.downloadAttachment(session, recordId, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=emr-attachment-" + attachmentId)
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(file.bytes().length).body(file.bytes());
    }

    private String run(Long recordId, RedirectAttributes redirect, String success, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", success);
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", message(ex));
        }
        return "redirect:/emr/records/" + recordId;
    }

    private String message(RuntimeException ex) {
        if (ex instanceof ResponseStatusException response) {
            String reason = response.getReason();
            if (reason != null && !reason.isBlank()) return reason;
        }
        return ex.getMessage() == null ? "Không thể thực hiện thao tác." : ex.getMessage();
    }
}
