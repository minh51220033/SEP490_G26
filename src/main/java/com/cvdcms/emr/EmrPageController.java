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
    public String list(HttpSession session, Model model,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) EmrRecordStatus status,
            @RequestParam(required = false) java.time.LocalDate fromDate,
            @RequestParam(required = false) java.time.LocalDate toDate,
            @RequestParam(required = false) String doctor,
            @RequestParam(required = false) String branch,
            @RequestParam(defaultValue = "0") int page) {
        var dashboard = service.dashboard(session, query, status, fromDate, toDate, doctor, branch);
        int pageSize = 10;
        int pageCount = Math.max(1, (dashboard.records().size() + pageSize - 1) / pageSize);
        int safePage = Math.max(0, Math.min(page, pageCount - 1));
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("pagedRecords", dashboard.records().subList(safePage * pageSize,
                Math.min((safePage + 1) * pageSize, dashboard.records().size())));
        model.addAttribute("page", safePage);
        model.addAttribute("pageCount", pageCount);
        model.addAttribute("query", query);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("doctor", doctor);
        model.addAttribute("branch", branch);
        model.addAttribute("statuses", EmrRecordStatus.values());
        return "emr/list";
    }

    @GetMapping("/{recordId}")
    public String detail(HttpSession session, Model model, @PathVariable Long recordId) {
        model.addAttribute("detail", service.detail(session, recordId));
        return "emr/detail";
    }

    @PostMapping
    public String create(HttpSession session, @RequestParam Long visitId, RedirectAttributes redirect) {
        try {
            Long id = service.create(session, visitId);
            redirect.addFlashAttribute("success", "Đã tạo bản nháp bệnh án điện tử.");
            return "redirect:/emr/records/" + id;
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", message(ex));
            return "redirect:/emr/records";
        }
    }

    @PostMapping("/{recordId}/refresh")
    public String refresh(HttpSession session, @PathVariable Long recordId, RedirectAttributes redirect) {
        return run(recordId, redirect, "Đã đồng bộ lại dữ liệu khám nguồn.",
                () -> service.refresh(session, recordId));
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

    @PostMapping("/{recordId}/doctor-sign-test")
    public String doctorSign(HttpSession session, @PathVariable Long recordId,
            @RequestParam(defaultValue = "false") boolean confirmed, RedirectAttributes redirect) {
        return run(recordId, redirect, "Đã hoàn tất chữ ký thử nghiệm của bác sĩ.",
                () -> service.doctorSignForTest(session, recordId, confirmed));
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
