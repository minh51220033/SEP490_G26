package com.cvdcms.patientportal;

import com.cvdcms.repository.InvoiceRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/portal/records")
public class PortalPatientPageController {
    private final PortalPatientService patients;
    private final PatientTransferService transfers;
    private final PortalAccess access;
    private final InvoiceRepository invoiceRepository;

    public PortalPatientPageController(PortalPatientService patients, PatientTransferService transfers,
                                       PortalAccess access, InvoiceRepository invoiceRepository) {
        this.patients = patients;
        this.transfers = transfers;
        this.access = access;
        this.invoiceRepository = invoiceRepository;
    }

    @ModelAttribute("roleNames")
    public List<String> roles(HttpSession session) {
        access.requireUser(session);
        return List.of("BENH_NHAN");
    }

    @GetMapping
    public String list(HttpSession session, Model model) {
        model.addAttribute("records", patients.list(session));
        return "portal/records/list";
    }

    @GetMapping("/new")
    public String create(HttpSession session, Model model) {
        model.addAttribute("branches", patients.branches(session));
        model.addAttribute("record", null);
        return "portal/records/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(HttpSession session, @PathVariable Long id, Model model) {
        PortalProfileView record = patients.get(session, id);
        model.addAttribute("record", record);
        model.addAttribute("branches", patients.branches(session));
        return "portal/records/form";
    }

    @GetMapping("/{id}")
    public String detail(HttpSession session, @PathVariable Long id, Model model) {
        model.addAttribute("record", patients.get(session, id));
        model.addAttribute("visits", patients.visits(session, id));
        model.addAttribute("photos", patients.photos(session, id));
        model.addAttribute("plans", patients.plans(session, id));
        model.addAttribute("invoices", invoiceRepository.findByPatientProfile_ProfileIdOrderByIssuedAtDesc(id));
        return "portal/records/detail";
    }

    @GetMapping("/{id}/visits/{visitId}")
    public String visit(HttpSession session, @PathVariable Long id, @PathVariable Long visitId, Model model) {
        model.addAttribute("record", patients.get(session, id));
        model.addAttribute("detail", patients.visit(session, id, visitId));
        return "portal/records/visit";
    }

    @GetMapping("/{id}/transfer")
    public String transfer(HttpSession session, @PathVariable Long id, Model model) {
        PortalProfileView record = patients.get(session, id);
        if (record.self()) throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
        model.addAttribute("record", record);
        return "portal/records/transfer";
    }

    @PostMapping("/{id}/transfer")
    public String startTransfer(HttpSession session, @PathVariable Long id,
            @Valid @ModelAttribute TransferStartForm form, BindingResult errors, Model model) {
        if (!errors.hasErrors()) {
            try {
                var started = transfers.start(session, id, form);
                return "redirect:/portal/records/" + id + "/transfer/" + started.requestId();
            } catch (ResponseStatusException ex) {
                model.addAttribute("error", ex.getReason());
            }
        } else model.addAttribute("error", "Email nhận không hợp lệ.");
        model.addAttribute("record", patients.get(session, id));
        return "portal/records/transfer";
    }

    @GetMapping("/{id}/transfer/{requestId}")
    public String verifyTransfer(HttpSession session, @PathVariable Long id,
            @PathVariable Long requestId, Model model) {
        model.addAttribute("record", patients.get(session, id));
        model.addAttribute("request", transfers.inspect(session, id, requestId));
        return "portal/records/transfer-confirm";
    }

    @PostMapping("/{id}/transfer/{requestId}")
    public String confirmTransfer(HttpSession session, @PathVariable Long id,
            @PathVariable Long requestId, @Valid @ModelAttribute TransferConfirmForm form,
            BindingResult errors, Model model, RedirectAttributes redirect) {
        if (!errors.hasErrors()) {
            try {
                PortalProfileView record = patients.get(session, id);
                var completed = transfers.confirm(session, id, requestId, form);
                redirect.addFlashAttribute("transferredCode", record.code());
                redirect.addFlashAttribute("transferredName", record.fullName());
                redirect.addFlashAttribute("targetEmail", completed.targetEmail());
                return "redirect:/portal/records/transfer-complete";
            } catch (ResponseStatusException ex) {
                model.addAttribute("error", ex.getReason());
            }
        } else model.addAttribute("error", "Vui lòng nhập hai mã xác minh gồm 6 chữ số.");
        model.addAttribute("record", patients.get(session, id));
        model.addAttribute("request", transfers.inspect(session, id, requestId));
        return "portal/records/transfer-confirm";
    }

    @GetMapping("/transfer-complete")
    public String transferComplete(Model model) {
        if (!model.containsAttribute("transferredCode")) return "redirect:/portal/records";
        return "portal/records/transfer-complete";
    }
}
