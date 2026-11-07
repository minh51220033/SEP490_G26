package com.cvdcms.patient;

import com.cvdcms.DTO.ClinicalPhotoResponseDTO;
import com.cvdcms.DTO.LaboOrderResponseDTO;
import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.emr.EmrRecord;
import com.cvdcms.emr.EmrRecordRepository;
import com.cvdcms.entity.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.ClinicalPhotoService;
import com.cvdcms.service.CustomerCareService;
import com.cvdcms.service.DebtService;
import com.cvdcms.service.LaboOrderService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class PatientOverviewService {

    private final UserRepository users;
    private final PatientProfileRepository profileRepository;
    private final ReceptionPatientRepository receptionProfiles;
    private final PatientSearchRepository searchRepository;
    private final BranchRepository branchRepository;
    private final DebtService debtService;
    private final PatientVisitReadRepository visitReadRepository;
    private final AppointmentRepository appointmentRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final CustomerCareService customerCareService;
    private final LaboOrderService laboOrderService;
    private final ClinicalPhotoService clinicalPhotoService;
    private final InvoiceRepository invoiceRepository;
    private final EmrRecordRepository emrRecordRepository;
    private final PatientDocumentStorageService documentStorageService;
    private final PatientAuditRepository patientAuditRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final PerformedServiceRepository performedServiceRepository;
    private final ClinicalExaminationRepository clinicalExaminationRepository;
    private final ExaminationDiagnosisRepository examinationDiagnosisRepository;

    public PatientOverviewService(UserRepository users,
                                  PatientProfileRepository profileRepository,
                                  ReceptionPatientRepository receptionProfiles,
                                  PatientSearchRepository searchRepository,
                                  BranchRepository branchRepository,
                                  DebtService debtService,
                                  PatientVisitReadRepository visitReadRepository,
                                  AppointmentRepository appointmentRepository,
                                  PrescriptionRepository prescriptionRepository,
                                  CustomerCareService customerCareService,
                                  LaboOrderService laboOrderService,
                                  ClinicalPhotoService clinicalPhotoService,
                                  InvoiceRepository invoiceRepository,
                                  EmrRecordRepository emrRecordRepository,
                                  PatientDocumentStorageService documentStorageService,
                                  PatientAuditRepository patientAuditRepository,
                                  TreatmentPlanRepository treatmentPlanRepository,
                                  PerformedServiceRepository performedServiceRepository,
                                  ClinicalExaminationRepository clinicalExaminationRepository,
                                  ExaminationDiagnosisRepository examinationDiagnosisRepository) {
        this.users = users;
        this.profileRepository = profileRepository;
        this.receptionProfiles = receptionProfiles;
        this.searchRepository = searchRepository;
        this.branchRepository = branchRepository;
        this.debtService = debtService;
        this.visitReadRepository = visitReadRepository;
        this.appointmentRepository = appointmentRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.customerCareService = customerCareService;
        this.laboOrderService = laboOrderService;
        this.clinicalPhotoService = clinicalPhotoService;
        this.invoiceRepository = invoiceRepository;
        this.emrRecordRepository = emrRecordRepository;
        this.documentStorageService = documentStorageService;
        this.patientAuditRepository = patientAuditRepository;
        this.treatmentPlanRepository = treatmentPlanRepository;
        this.performedServiceRepository = performedServiceRepository;
        this.clinicalExaminationRepository = clinicalExaminationRepository;
        this.examinationDiagnosisRepository = examinationDiagnosisRepository;
    }

    public User requireAdminOrManager(HttpSession session) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (!(principal instanceof User current) || current.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        User user = users.findById(current.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản bị khóa hoặc không hoạt động.");
        }
        boolean isAdmin = user.getRoles().stream().anyMatch(r ->
                Boolean.TRUE.equals(r.getIsActive()) && ("ADMIN".equalsIgnoreCase(r.getRoleName()) || "ROLE_ADMIN".equalsIgnoreCase(r.getRoleName())))
                || users.hasActiveAdminRole(user.getUserId());
        boolean hasPermission = users.hasActivePermission(user.getUserId(), "PATIENT_PROFILE_MANAGE");

        if (!isAdmin && !hasPermission) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chức năng dành cho Quản trị viên.");
        }
        return user;
    }

    public Page<PatientOverviewDTO> searchPatients(Long branchId, String query, LocalDate dob, String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String cleanQuery = query != null && !query.isBlank() ? query.trim() : null;
        String cleanStatus = status != null && !status.isBlank() ? status.trim() : null;

        Page<ReceptionPatientRecord> records = searchRepository.search(
                branchId, cleanQuery, dob, cleanStatus,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "profileId"))
        );

        return records.map(r -> {
            PatientDebtSummaryDTO debt = null;
            try {
                debt = debtService.getPatientDebtDetail(r.getProfileId());
            } catch (Exception ignored) {
            }
            PatientOverviewDTO dto = new PatientOverviewDTO();
            dto.setId(r.getProfileId());
            dto.setCode(r.getPatientCode());
            dto.setFullName(r.getFullName());
            dto.setDob(r.getDob());
            dto.setGender(r.getGender());
            dto.setPhoneNumber(r.getPhoneNumber());
            dto.setAddress(r.getAddress());
            if (r.getPreferredBranch() != null) {
                dto.setBranchId(r.getPreferredBranch().getBranchId());
                dto.setBranchName(r.getPreferredBranch().getBranchName());
            }
            dto.setStatus(r.getStatus());
            dto.setCustomerSource(r.getCustomerSource() != null ? r.getCustomerSource() : "Trực tiếp");
            dto.setDepositBalance(r.getDepositBalance() != null ? r.getDepositBalance() : java.math.BigDecimal.ZERO);
            if (debt != null) {
                dto.setTotalBilled(debt.getTotalBilled());
                dto.setTotalPaid(debt.getTotalPaid());
                dto.setTotalDebt(debt.getTotalDebt());
                dto.setUnpaidInvoicesCount(debt.getUnpaidInvoicesCount());
                if (debt.getDepositBalance() != null && debt.getDepositBalance().compareTo(java.math.BigDecimal.ZERO) > 0) {
                    dto.setDepositBalance(debt.getDepositBalance());
                }
            }
            return dto;
        });
    }

    public PatientOverviewDTO getPatientOverview(Long profileId) {
        PatientDebtSummaryDTO debt = null;
        try {
            debt = debtService.getPatientDebtDetail(profileId);
        } catch (Exception ignored) {
        }

        PatientProfile profile = null;
        try {
            profile = profileRepository.findById(profileId).orElse(null);
        } catch (Exception ignored) {
            profile = null;
        }
        if (profile != null) {
            return PatientOverviewDTO.from(profile, debt);
        }

        ReceptionPatientRecord record = receptionProfiles.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân ID: " + profileId));
        return PatientOverviewDTO.from(record, debt);
    }

    public List<Branch> getAllActiveBranches() {
        return branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
    }

    public List<VisitView> getPatientVisits(Long profileId) {
        return visitReadRepository.forPatient(profileId).stream().map(VisitView::from).toList();
    }

    public List<Appointment> getPatientAppointments(Long profileId) {
        return appointmentRepository.findByProfileIdWithDetails(profileId);
    }

    public Map<String, Object> getPatientCareHistory(Long profileId, Long branchId, Long userId) {
        try {
            Map<String, Object> careMap = new LinkedHashMap<>(customerCareService.getPatientCareHistory(profileId, branchId, userId));
            Object interactions = careMap.get("interactions");
            careMap.put("timeline", interactions != null ? interactions : List.of());
            return careMap;
        } catch (Exception ex) {
            return Map.of("timeline", List.of(), "interactions", List.of(), "totalInteractions", 0, "totalTasks", 0);
        }
    }

    public List<Prescription> getPatientPrescriptions(Long profileId) {
        return prescriptionRepository.findByPatientProfile_ProfileIdOrderByCreatedAtDesc(profileId);
    }

    public List<LaboOrderResponseDTO> getPatientLaboOrders(Long profileId) {
        return laboOrderService.getOrdersByPatient(profileId);
    }

    public List<ClinicalPhotoResponseDTO> getPatientPhotos(Long profileId) {
        return clinicalPhotoService.getPhotosByProfile(profileId);
    }

    public List<Invoice> getPatientInvoices(Long profileId) {
        return invoiceRepository.findByPatientProfile_ProfileIdOrderByIssuedAtDesc(profileId);
    }

    public List<EmrRecord> getPatientEmrRecords(Long profileId) {
        return emrRecordRepository.findAllByPatientProfileId(profileId);
    }

    public List<PatientDocumentView> getPatientDocuments(Long profileId) {
        return documentStorageService.list(profileId);
    }

    public List<SystemAuditLog> getPatientAudits(Long profileId) {
        return patientAuditRepository.findByEntityTableAndEntityIdOrderByCreatedAtDesc("patient_profiles", profileId);
    }

    public List<TreatmentPlan> getPatientTreatmentPlans(Long profileId) {
        return treatmentPlanRepository.findByProfileIdWithDetails(profileId);
    }

    public List<PerformedService> getPatientPerformedServices(Long profileId) {
        return performedServiceRepository.findByProfileIdWithDetails(profileId);
    }

    public List<ExaminationDiagnosis> getPatientDiagnoses(Long profileId) {
        return examinationDiagnosisRepository.findByProfileIdWithDetails(profileId);
    }

    public List<ClinicalExamination> getPatientExaminations(Long profileId) {
        return clinicalExaminationRepository.findByProfileIdWithDetails(profileId);
    }
}

