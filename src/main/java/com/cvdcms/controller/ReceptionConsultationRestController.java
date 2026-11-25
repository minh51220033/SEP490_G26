package com.cvdcms.controller;

import com.cvdcms.service.ReceptionConsultationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/reception/consultations/api")
public class ReceptionConsultationRestController {

    private final ReceptionConsultationService service;

    public ReceptionConsultationRestController(ReceptionConsultationService service) {
        this.service = service;
    }

    @GetMapping("/options")
    public ReceptionConsultationService.ReferenceOptions options(HttpSession session) {
        return service.options(session);
    }

    @GetMapping("/summary")
    public ReceptionConsultationService.ConsultationSummary summary(
            HttpSession session,
            @RequestParam(required = false) Long branchId
    ) {
        return service.summary(session, branchId);
    }

    @GetMapping
    public ReceptionConsultationService.ConsultationPage list(
            HttpSession session,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        return service.list(session, branchId, status, keyword, createdFrom, createdTo, page, size);
    }

    @GetMapping("/{id}")
    public ReceptionConsultationService.ConsultationDetail detail(
            HttpSession session,
            @PathVariable Long id
    ) {
        return service.detail(session, id);
    }

    @GetMapping("/{id}/assignees")
    public List<ReceptionConsultationService.AssigneeOption> assignees(
            HttpSession session,
            @PathVariable Long id
    ) {
        return service.assignees(session, id);
    }

    @GetMapping("/{id}/verify-patient")
    public ReceptionConsultationService.VerificationResult verifyPatient(
            HttpSession session,
            @PathVariable Long id,
            @RequestParam(required = false) String query
    ) {
        return service.verifyPatientCandidates(session, id, query);
    }

    @PostMapping("/{id}/claim")
    public ReceptionConsultationService.ConsultationDetail claim(
            HttpSession session,
            @PathVariable Long id,
            @RequestBody(required = false) ReceptionConsultationService.ClaimRequest request,
            HttpServletRequest httpRequest
    ) {
        return service.claim(session, id, request, httpRequest);
    }

    @PostMapping("/{id}/contact")
    public ReceptionConsultationService.ConsultationDetail contact(
            HttpSession session,
            @PathVariable Long id,
            @RequestBody ReceptionConsultationService.ContactRequest request,
            HttpServletRequest httpRequest
    ) {
        return service.contact(session, id, request, httpRequest);
    }

    @PostMapping("/{id}/handover")
    public ReceptionConsultationService.ConsultationDetail handover(
            HttpSession session,
            @PathVariable Long id,
            @RequestBody ReceptionConsultationService.HandoverRequest request,
            HttpServletRequest httpRequest
    ) {
        return service.handover(session, id, request, httpRequest);
    }

    @PostMapping("/{id}/close")
    public ReceptionConsultationService.ConsultationDetail close(
            HttpSession session,
            @PathVariable Long id,
            @RequestBody ReceptionConsultationService.CloseRequest request,
            HttpServletRequest httpRequest
    ) {
        return service.close(session, id, request, httpRequest);
    }

    @PostMapping("/{id}/convert")
    public ReceptionConsultationService.ConvertResult convert(
            HttpSession session,
            @PathVariable Long id,
            @RequestBody ReceptionConsultationService.ConvertRequest request,
            HttpServletRequest httpRequest
    ) {
        return service.convertToAppointment(session, id, request, httpRequest);
    }
}
