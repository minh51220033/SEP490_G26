package com.cvdcms.patient;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/patients")
public class PatientRestController {
    private final PatientService service;

    public PatientRestController(PatientService service) { this.service = service; }

    @GetMapping
    public Page<PatientView> list(@RequestParam(required = false) String query,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) Long branchId,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "10") int size, HttpSession session) {
        return service.list(session, query, dob, status, branchId, page, size);
    }

    @GetMapping("/duplicate-candidates")
    public List<DuplicateCandidate> candidates(@RequestParam(required = false) String fullName,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
                                                @RequestParam(required = false) String phoneNumber,
                                                @RequestParam(required = false) Long excludeId,
                                                HttpSession session) {
        return service.candidates(session, fullName, dob, phoneNumber, excludeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientView create(@Valid @RequestBody PatientProfileForm form, HttpSession session) {
        return service.create(session, form);
    }

    @GetMapping("/{id}")
    public PatientView get(@PathVariable Long id, HttpSession session) { return service.get(session, id); }

    @PatchMapping("/{id}")
    public PatientView update(@PathVariable Long id, @Valid @RequestBody PatientProfileForm form,
                              HttpSession session) { return service.update(session, id, form); }

    @GetMapping("/{id}/visits")
    public List<VisitView> visits(@PathVariable Long id, HttpSession session) {
        return service.visits(session, id);
    }

    @GetMapping("/{id}/visits/{visitId}")
    public PatientService.VisitDetails visit(@PathVariable Long id, @PathVariable Long visitId,
                                             HttpSession session) {
        return service.visit(session, id, visitId);
    }
}
