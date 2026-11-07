package com.cvdcms.patient;

import com.cvdcms.entity.Branch;
import com.cvdcms.repository.BranchRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/patients")
public class PatientRestController {
    private final PatientService service;
    private final ReceptionPatientRepository patientRepository;
    private final PatientSearchRepository searchRepository;
    private final BranchRepository branchRepository;
    private final PatientAccess access;

    public PatientRestController(PatientService service,
                                 ReceptionPatientRepository patientRepository,
                                 PatientSearchRepository searchRepository,
                                 BranchRepository branchRepository,
                                 PatientAccess access) {
        this.service = service;
        this.patientRepository = patientRepository;
        this.searchRepository = searchRepository;
        this.branchRepository = branchRepository;
        this.access = access;
    }

    @GetMapping
    public Page<PatientView> list(@RequestParam(required = false) String query,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String customerSource,
                                  @RequestParam(required = false) Long branchId,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "10") int size, HttpSession session) {
        if (customerSource != null && !customerSource.isBlank()) {
            var scope = access.require(session);
            if (branchId != null && !scope.permits(branchId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            Long selectedBranchId = scope.admin() ? branchId : scope.branchId();
            int safePage = Math.max(0, page);
            int safeSize = Math.min(50, Math.max(1, size));
            String term = (query != null && !query.isBlank()) ? query.trim() : null;
            String state = (status != null && !status.isBlank()) ? status.trim() : null;
            return searchRepository.searchWithSource(selectedBranchId, term, dob, state, customerSource.trim(),
                    PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "profileId")))
                    .map(PatientView::from);
        }
        return service.list(session, query, dob, status, branchId, page, size);
    }

    @GetMapping("/duplicate-candidates")
    public List<DuplicateCandidate> candidates(@RequestParam(required = false) String fullName,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
                                                @RequestParam(required = false) String phoneNumber,
                                                @RequestParam(required = false) String idCardNumber,
                                                @RequestParam(required = false) Long excludeId,
                                                HttpSession session) {
        return service.candidates(session, fullName, dob, phoneNumber, idCardNumber, excludeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientView create(@Valid @RequestBody PatientProfileForm form, HttpSession session) {
        return service.create(session, form);
    }

    /**
     * Thêm nhanh bệnh nhân mới từ modal Quick Add với nguồn tiếp nhận
     */
    @PostMapping("/quick-create")
    @ResponseStatus(HttpStatus.CREATED)
    public PatientView quickCreate(@Valid @RequestBody QuickPatientRequest req, HttpSession session) {
        var scope = access.require(session);

        Long targetBranchId = req.branchId();
        if (targetBranchId == null || !scope.permits(targetBranchId)) {
            targetBranchId = scope.branchId();
        }
        Branch branch;
        if (targetBranchId != null) {
            branch = branchRepository.findById(targetBranchId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chi nhánh không tồn tại."));
        } else {
            branch = branchRepository.findByIsActiveTrueOrderByBranchNameAsc().stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không có chi nhánh khả dụng."));
        }

        ReceptionPatientRecord profile = new ReceptionPatientRecord();
        String codeSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        profile.setPatientCode("BN" + codeSuffix);
        profile.setFullName(req.fullName() != null ? req.fullName().trim() : "Bệnh nhân mới");
        profile.setPhoneNumber(req.phoneNumber() != null ? req.phoneNumber().trim() : "0900000000");
        profile.setDob(req.dob() != null ? req.dob() : LocalDate.of(1990, 1, 1));
        profile.setGender(req.gender() != null && !req.gender().isBlank() ? req.gender().trim() : "Nam");
        profile.setAddress(req.address() != null ? req.address().trim() : "");
        profile.setCustomerSource(req.customerSource() != null && !req.customerSource().isBlank() ? req.customerSource().trim() : "Vãng lai");
        profile.setPreferredBranch(branch);
        profile.setStatus("ACTIVE");
        profile.setIsDefault(false);
        profile.setMedicalHistoryCompleted(false);
        profile.setDepositBalance(BigDecimal.ZERO);

        if (req.allergyDetails() != null && !req.allergyDetails().isBlank()) {
            profile.setAllergyDetails(req.allergyDetails().trim());
            profile.setHasAllergies(true);
        }
        if (req.underlyingDiseaseDetails() != null && !req.underlyingDiseaseDetails().isBlank()) {
            profile.setUnderlyingDiseaseDetails(req.underlyingDiseaseDetails().trim());
            profile.setHasUnderlyingDisease(true);
        }

        patientRepository.saveAndFlush(profile);
        return PatientView.from(profile);
    }

    /**
     * Cập nhật nhanh thông tin và nguồn khách hàng từ modal Chỉnh sửa
     */
    @PutMapping("/{id}/quick-update")
    public PatientView quickUpdate(@PathVariable Long id, @RequestBody QuickUpdatePatientRequest req, HttpSession session) {
        var scope = access.require(session);
        ReceptionPatientRecord profile = patientRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));
        if (!scope.permits(profile.getPreferredBranch().getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền thao tác trên chi nhánh này.");
        }

        if (req.fullName() != null && !req.fullName().isBlank()) {
            profile.setFullName(req.fullName().trim());
        }
        if (req.phoneNumber() != null && !req.phoneNumber().isBlank()) {
            profile.setPhoneNumber(req.phoneNumber().trim());
        }
        if (req.dob() != null) {
            profile.setDob(req.dob());
        }
        if (req.gender() != null && !req.gender().isBlank()) {
            profile.setGender(req.gender().trim());
        }
        if (req.address() != null) {
            profile.setAddress(req.address().trim());
        }
        if (req.customerSource() != null && !req.customerSource().isBlank()) {
            profile.setCustomerSource(req.customerSource().trim());
        }
        if (req.status() != null && !req.status().isBlank()) {
            profile.setStatus(req.status().trim());
        }

        patientRepository.saveAndFlush(profile);
        return PatientView.from(profile);
    }

    /**
     * Soft-delete / Lưu trữ hồ sơ bệnh nhân
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> softDelete(@PathVariable Long id, HttpSession session) {
        var scope = access.require(session);
        ReceptionPatientRecord profile = patientRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));
        if (!scope.permits(profile.getPreferredBranch().getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền thao tác trên chi nhánh này.");
        }
        profile.setStatus("INACTIVE");
        patientRepository.saveAndFlush(profile);
        return Map.of("success", true, "message", "Đã lưu trữ / xóa mềm hồ sơ bệnh nhân thành công.", "id", id);
    }

    @PostMapping("/{id}/soft-delete")
    public Map<String, Object> softDeletePost(@PathVariable Long id, HttpSession session) {
        return softDelete(id, session);
    }

    /**
     * Khôi phục hồ sơ đã lưu trữ
     */
    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable Long id, HttpSession session) {
        var scope = access.require(session);
        ReceptionPatientRecord profile = patientRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));
        if (!scope.permits(profile.getPreferredBranch().getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền thao tác trên chi nhánh này.");
        }
        profile.setStatus("ACTIVE");
        patientRepository.saveAndFlush(profile);
        return Map.of("success", true, "message", "Đã khôi phục hồ sơ bệnh nhân thành công.", "id", id);
    }

    @GetMapping("/{id}")
    public PatientView get(@PathVariable Long id, HttpSession session) { return service.get(session, id); }

    @PatchMapping("/{id}")
    public PatientView update(@PathVariable Long id, @Valid @RequestBody PatientProfileForm form,
                              HttpSession session) { return service.update(session, id, form); }

    @GetMapping("/{id}/visits")
    public List<ReceptionVisitView> visits(@PathVariable Long id, HttpSession session) {
        return service.visits(session, id);
    }

    @GetMapping("/{id}/visits/{visitId}")
    public PatientService.VisitDetails visit(@PathVariable Long id, @PathVariable Long visitId,
                                             HttpSession session) {
        return service.visit(session, id, visitId);
    }

    public record QuickPatientRequest(
            String fullName,
            String phoneNumber,
            LocalDate dob,
            String gender,
            String address,
            String customerSource,
            Long branchId,
            String allergyDetails,
            String underlyingDiseaseDetails
    ) {}

    public record QuickUpdatePatientRequest(
            String fullName,
            String phoneNumber,
            LocalDate dob,
            String gender,
            String address,
            String customerSource,
            String status
    ) {}
}
