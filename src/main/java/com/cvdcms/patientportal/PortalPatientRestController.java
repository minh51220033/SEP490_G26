package com.cvdcms.patientportal;

import com.cvdcms.patient.VisitView;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/portal/patients")
public class PortalPatientRestController {
    private final PortalPatientService patients;
    private final PatientTransferService transfers;

    public PortalPatientRestController(PortalPatientService patients, PatientTransferService transfers) {
        this.patients = patients; this.transfers = transfers;
    }

    @GetMapping
    public List<PortalProfileView> list(HttpSession session) { return patients.list(session); }

    @GetMapping("/duplicate-candidates")
    public DuplicateCheck candidates(HttpSession session,
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
            @RequestParam(required = false) String phoneNumber,
            @RequestParam(required = false) Long excludeId) {
        return new DuplicateCheck(patients.hasDuplicateCandidates(session, fullName, dob, phoneNumber, excludeId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortalProfileView create(HttpSession session, @Valid @RequestBody PortalProfileForm form) {
        return patients.create(session, form);
    }

    @GetMapping("/{id}")
    public PortalProfileView get(HttpSession session, @PathVariable Long id) { return patients.get(session, id); }

    @PostMapping("/{id}/default")
    public PortalProfileView setDefault(HttpSession session, @PathVariable Long id) {
        return patients.setDefault(session, id);
    }

    @PostMapping(value = "/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PortalProfileView uploadAvatar(HttpSession session, @PathVariable Long id,
            @RequestParam("avatar") MultipartFile avatar) {
        return patients.uploadAvatar(session, id, avatar);
    }

    @PatchMapping("/{id}")
    public PortalProfileView update(HttpSession session, @PathVariable Long id,
                                    @Valid @RequestBody PortalProfileForm form) {
        return patients.update(session, id, form);
    }

    @GetMapping("/{id}/visits")
    public List<VisitView> visits(HttpSession session, @PathVariable Long id) {
        return patients.visits(session, id);
    }

    @GetMapping("/{id}/visits/{visitId}")
    public PortalPatientService.VisitDetails visit(HttpSession session, @PathVariable Long id,
                                                   @PathVariable Long visitId) {
        return patients.visit(session, id, visitId);
    }

    @PostMapping("/{id}/transfer-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public PatientTransferService.TransferStarted startTransfer(HttpSession session, @PathVariable Long id,
                                                                 @Valid @RequestBody TransferStartForm form) {
        return transfers.start(session, id, form);
    }

    @PostMapping("/{id}/transfer-requests/{requestId}/confirm")
    public PatientTransferService.TransferCompleted confirmTransfer(HttpSession session,
            @PathVariable Long id, @PathVariable Long requestId,
            @Valid @RequestBody TransferConfirmForm form) {
        return transfers.confirm(session, id, requestId, form);
    }

    public record DuplicateCheck(boolean duplicate) {}
}
