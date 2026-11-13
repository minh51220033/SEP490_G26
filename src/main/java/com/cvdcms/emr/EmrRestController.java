package com.cvdcms.emr;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/emr/records")
public class EmrRestController {
    private final EmrModuleService service;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.security.DoctorVisitAccess doctorVisitAccess;

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
        return new IdResponse(service.getOrCreate(session, request.visitId()));
    }

    @PostMapping("/from-visit/{visitId}")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse fromVisit(HttpSession session, @PathVariable Long visitId) {
        return new IdResponse(service.getOrCreate(session, visitId));
    }

    @GetMapping("/visit/{visitId}/snapshot")
    public java.util.Map<String, Object> getVisitSnapshot(HttpSession session, @PathVariable Long visitId) {
        if (doctorVisitAccess != null) {
            doctorVisitAccess.requireVisitRead(session, visitId);
        }
        return service.getVisitSnapshot(visitId);
    }

    @PostMapping(path = {"/{recordId}/refresh-snapshot", "/{recordId}/sync-examination", "/{recordId}/sync"})
    public void refresh(HttpSession session, @PathVariable Long recordId) {
        service.refresh(session, recordId);
    }

    @PostMapping(path = {"/{recordId}/patient-demographics", "/{recordId}/demographics"})
    public void updateDemographics(HttpSession session, @PathVariable Long recordId,
            @RequestBody java.util.Map<String, String> request) {
        service.updatePatientDemographics(session, recordId, request);
    }

    @DeleteMapping("/{recordId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(HttpSession session, @PathVariable Long recordId) {
        service.deleteDraft(session, recordId);
    }

    @PostMapping("/{recordId}/delete")
    public void deleteDraftPost(HttpSession session, @PathVariable Long recordId) {
        service.deleteDraft(session, recordId);
    }

    @PostMapping("/{recordId}/validate")
    public EmrModuleService.ValidationResult validate(HttpSession session, @PathVariable Long recordId) {
        return service.validate(session, recordId);
    }

    @PostMapping("/{recordId}/lock")
    public void lock(HttpSession session, @PathVariable Long recordId) {
        service.lock(session, recordId);
    }

    @PostMapping(path = {"/{recordId}/signatures/doctor", "/{recordId}/test-signatures/doctor"})
    public void doctorSign(HttpSession session, @PathVariable Long recordId,
            @RequestBody TestSignRequest request) {
        service.doctorSign(session, recordId, request != null && request.confirmed());
    }

    @PostMapping("/{recordId}/signatures/upload")
    public java.util.Map<String, String> uploadSignature(HttpSession session, @PathVariable Long recordId,
            @RequestBody java.util.Map<String, String> payload) {
        service.detail(session, recordId);
        String dataUrl = payload != null ? payload.get("signatureData") : null;
        String signerType = payload != null ? payload.getOrDefault("signerType", "doctor") : "doctor";
        String url = com.cvdcms.util.InventoryFileStorageUtil.saveSignatureDataUrl(dataUrl, "emr-" + signerType);
        return java.util.Map.of("signatureUrl", url);
    }

    @PostMapping("/{recordId}/amendments")
    public void amendment(HttpSession session, @PathVariable Long recordId,
            @RequestBody AmendmentRequest request) {
        service.createAmendment(session, recordId, request.reason());
    }

    public record CreateRequest(Long visitId) {}
    public record TestSignRequest(boolean confirmed) {}
    public record AmendmentRequest(String reason) {}
    public record IdResponse(Long id) {}
}
