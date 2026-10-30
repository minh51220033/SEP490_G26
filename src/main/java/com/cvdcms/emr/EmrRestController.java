package com.cvdcms.emr;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/emr/records")
public class EmrRestController {
    private final EmrModuleService service;

    public EmrRestController(EmrModuleService service) {
        this.service = service;
    }

    @GetMapping
    public EmrModuleService.Dashboard list(HttpSession session,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) EmrRecordStatus status) {
        return service.dashboard(session, query, status);
    }

    @GetMapping("/{recordId}")
    public EmrModuleService.RecordDetail detail(HttpSession session, @PathVariable Long recordId) {
        return service.detail(session, recordId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse create(HttpSession session, @RequestBody CreateRequest request) {
        return new IdResponse(service.create(session, request.visitId()));
    }

    @PostMapping("/{recordId}/refresh-snapshot")
    public void refresh(HttpSession session, @PathVariable Long recordId) {
        service.refresh(session, recordId);
    }

    @PostMapping("/{recordId}/validate")
    public EmrModuleService.ValidationResult validate(HttpSession session, @PathVariable Long recordId) {
        return service.validate(session, recordId);
    }

    @PostMapping("/{recordId}/lock")
    public void lock(HttpSession session, @PathVariable Long recordId) {
        service.lock(session, recordId);
    }

    @PostMapping("/{recordId}/test-signatures/patient")
    public void patientSign(HttpSession session, @PathVariable Long recordId,
            @RequestBody(required = false) PatientSignRequest request) {
        service.patientSignForTest(session, recordId, request == null ? null : request.signerName());
    }

    @PostMapping("/{recordId}/test-signatures/doctor")
    public void doctorSign(HttpSession session, @PathVariable Long recordId) {
        service.doctorSignForTest(session, recordId);
    }

    @PostMapping("/{recordId}/amendments")
    public void amendment(HttpSession session, @PathVariable Long recordId,
            @RequestBody AmendmentRequest request) {
        service.createAmendment(session, recordId, request.reason());
    }

    public record CreateRequest(Long visitId) {}
    public record PatientSignRequest(String signerName) {}
    public record AmendmentRequest(String reason) {}
    public record IdResponse(Long id) {}
}
