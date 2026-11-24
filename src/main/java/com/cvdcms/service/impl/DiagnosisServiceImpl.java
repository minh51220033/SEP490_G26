package com.cvdcms.service.impl;

import com.cvdcms.DTO.ClinicalCompletionCheckDTO;
import com.cvdcms.DTO.DiagnosisRequestDTO;
import com.cvdcms.DTO.DiagnosisResponseDTO;
import com.cvdcms.DTO.GeneralExaminationRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.DiagnosisType;
import com.cvdcms.entity.enums.PerformedServiceStatus;
import com.cvdcms.entity.enums.TreatmentPlanStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.DiagnosisService;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@Transactional
public class DiagnosisServiceImpl implements DiagnosisService {

    private final ExaminationDiagnosisRepository diagnosisRepository;
    private final Icd10DiseaseRepository icd10DiseaseRepository;
    private final VisitRepository visitRepository;
    private final ClinicalExaminationRepository clinicalExamRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final PerformedServiceRepository performedServiceRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final AppointmentRepository appointmentRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final InvoiceRepository invoiceRepository;
    private final ClinicalExaminationBootstrapService examinations;
    @Autowired(required = false)
    private DentalChairRepository dentalChairs;
    @Autowired(required = false)
    private ClinicalQueueTicketRepository queueTickets;
    @Autowired(required = false)
    private SystemAuditLogRepository auditLogs;
    @Autowired(required = false)
    private com.cvdcms.service.ParaclinicalOrderService paraclinicalOrderService;
    @Autowired(required = false)
    private com.cvdcms.repository.ParaclinicalOrderItemRepository paraclinicalOrderItemRepository;
    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher careEventPublisher;
    @Autowired(required = false)
    private com.cvdcms.emr.EmrRecordRepository emrRecordRepository;
    @Autowired(required = false)
    private com.cvdcms.service.AppointmentLifecycleService appointmentLifecycleService;

    @Autowired(required = false)
    private com.cvdcms.service.ClinicQueueCoordinatorService queueCoordinator;
    @Autowired(required = false)
    private BranchRepository branchRepository;

    @Autowired
    public DiagnosisServiceImpl(
            ExaminationDiagnosisRepository diagnosisRepository,
            Icd10DiseaseRepository icd10DiseaseRepository,
            VisitRepository visitRepository,
            ClinicalExaminationRepository clinicalExamRepository,
            StaffProfileRepository staffProfileRepository,
            PerformedServiceRepository performedServiceRepository,
            PrescriptionRepository prescriptionRepository,
            TreatmentPlanRepository treatmentPlanRepository,
            AppointmentRepository appointmentRepository,
            PatientProfileRepository patientProfileRepository,
            InvoiceRepository invoiceRepository,
            ClinicalExaminationBootstrapService examinations) {
        this.diagnosisRepository = diagnosisRepository;
        this.icd10DiseaseRepository = icd10DiseaseRepository;
        this.visitRepository = visitRepository;
        this.clinicalExamRepository = clinicalExamRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.performedServiceRepository = performedServiceRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.treatmentPlanRepository = treatmentPlanRepository;
        this.appointmentRepository = appointmentRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.invoiceRepository = invoiceRepository;
        this.examinations = examinations;
    }

    public DiagnosisServiceImpl(
            ExaminationDiagnosisRepository diagnosisRepository,
            Icd10DiseaseRepository icd10DiseaseRepository,
            VisitRepository visitRepository,
            ClinicalExaminationRepository clinicalExamRepository,
            StaffProfileRepository staffProfileRepository,
            PerformedServiceRepository performedServiceRepository,
            PrescriptionRepository prescriptionRepository,
            AppointmentRepository appointmentRepository,
            PatientProfileRepository patientProfileRepository,
            InvoiceRepository invoiceRepository) {
        this(
                diagnosisRepository,
                icd10DiseaseRepository,
                visitRepository,
                clinicalExamRepository,
                staffProfileRepository,
                performedServiceRepository,
                prescriptionRepository,
                null,
                appointmentRepository,
                patientProfileRepository,
                invoiceRepository,
                null);
    }

    private static final DateTimeFormatter AUDIT_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private com.cvdcms.entity.User resolveCurrentSessionUser() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes web) {
            var session = web.getRequest().getSession(false);
            if (session != null && session.getAttribute("loggedInUser") instanceof com.cvdcms.entity.User u) {
                return u;
            }
        }
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagnosisResponseDTO> getDiagnosesByVisitId(Long visitId) {
        if (visitId == null) {
            return List.of();
        }
        return diagnosisRepository.findByVisitIdWithDetails(visitId)
                .stream()
                .map(this::mapToDTO)
                .toList();
    }

    @Override
    public DiagnosisResponseDTO addDiagnosis(DiagnosisRequestDTO request) {
        if (request == null || request.getVisitId() == null) {
            throw new IllegalArgumentException("Thông tin chẩn đoán không hợp lệ: thiếu mã ca khám (visitId).");
        }
        if (request.getIcd10Code() == null || request.getIcd10Code().trim().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn mã bệnh ICD-10.");
        }

        Visit visit = visitRepository.findWithDetailsById(request.getVisitId())
                .orElseThrow(
                        () -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + request.getVisitId()));

        // Quy tắc BR-EMR-02: Khóa bệnh án lâm sàng bất biến khi đã chuyển thanh toán, hoàn thành, hủy hoặc khóa EMR
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Bệnh án lâm sàng đã được hoàn tất và khóa bất biến. Không thể thêm chẩn đoán mới");

        Icd10Disease disease = icd10DiseaseRepository.findByIcd10Code(request.getIcd10Code().trim())
                .orElseThrow(
                        () -> new IllegalArgumentException("Không tìm thấy mã bệnh ICD-10: " + request.getIcd10Code()));

        // Tìm hoặc tạo đợt khám lâm sàng ClinicalExamination tương ứng
        ClinicalExamination exam = clinicalExamRepository.findByVisit_VisitId(visit.getVisitId())
                .orElseGet(() -> createDefaultExamination(visit));

        // Nếu chẩn đoán mới là MAIN và đã có MAIN trước đó, chuyển MAIN cũ thành
        // SECONDARY để bệnh án chuẩn y khoa
        DiagnosisType type = request.getDiagnosisType() != null ? request.getDiagnosisType() : DiagnosisType.MAIN;
        if (type == DiagnosisType.MAIN) {
            List<ExaminationDiagnosis> existing = diagnosisRepository.findByVisit_VisitId(visit.getVisitId());
            for (ExaminationDiagnosis ed : existing) {
                if (ed.getDiagnosisType() == DiagnosisType.MAIN) {
                    ed.setDiagnosisType(DiagnosisType.SECONDARY);
                    diagnosisRepository.save(ed);
                }
            }
        }

        String diagnosisText = (request.getDiagnosisText() != null && !request.getDiagnosisText().trim().isEmpty())
                ? request.getDiagnosisText().trim()
                : disease.getDiseaseNameVi();

        ExaminationDiagnosis diagnosis = new ExaminationDiagnosis();
        diagnosis.setVisit(visit);
        diagnosis.setClinicalExamination(exam);
        diagnosis.setIcd10Disease(disease);
        diagnosis.setDiagnosisText(diagnosisText);
        diagnosis.setDiagnosisType(type);
        diagnosis.setToothNumbers(request.getToothNumbers() != null ? request.getToothNumbers().trim() : null);
        diagnosis.setNotes(request.getNotes() != null ? request.getNotes().trim() : null);

        ExaminationDiagnosis saved = diagnosisRepository.save(diagnosis);
        return mapToDTO(saved);
    }

    @Override
    public void deleteDiagnosis(Long diagnosisId) {
        if (diagnosisId == null) {
            return;
        }
        ExaminationDiagnosis diagnosis = diagnosisRepository.findById(diagnosisId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chẩn đoán có ID: " + diagnosisId));

        // Quy tắc BR-EMR-02: Khóa bất biến
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                diagnosis.getVisit(),
                emrRecordRepository,
                "Bệnh án lâm sàng đã được hoàn tất và khóa bất biến. Không thể xóa chẩn đoán");

        diagnosisRepository.delete(diagnosis);
    }

    @Override
    @Transactional(readOnly = true)
    public Visit getVisitOrThrow(Long visitId) {
        return visitRepository.findWithDetailsById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám có ID: " + visitId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Visit> getAvailableVisitsForDiagnosis() {
        return getAvailableVisitsForDiagnosis(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Visit> getAvailableVisitsForDiagnosis(Long branchId) {
        if (branchId == null) {
            return visitRepository.findRecentVisitsByStatuses(
                    List.of(VisitStatus.IN_EXAM, VisitStatus.WAITING, VisitStatus.IN_TREATMENT));
        }
        return visitRepository.findRecentVisitsByStatusesAndBranch(
                List.of(VisitStatus.IN_EXAM, VisitStatus.WAITING, VisitStatus.IN_TREATMENT),
                branchId);
    }

    @Override
    @Transactional(readOnly = true)
    public ClinicalCompletionCheckDTO checkClinicalCompletionReadiness(Long visitId) {
        Visit visit = getVisitOrThrow(visitId);
        PatientProfile patient = visit.getPatientProfile();

        List<ExaminationDiagnosis> diagnoses = diagnosisRepository.findByVisitIdWithDetails(visitId);
        int diagnosisCount = diagnoses.size();
        boolean hasDiagnosis = diagnosisCount > 0;
        boolean hasMainDiagnosis = diagnoses.stream().anyMatch(d -> d.getDiagnosisType() == DiagnosisType.MAIN);

        List<PerformedService> allPerformed = performedServiceRepository.findByVisitIdWithDetails(visitId);
        List<PerformedService> completedServices = allPerformed.stream()
                .filter(ps -> ps.getStatus() == PerformedServiceStatus.COMPLETED)
                .toList();

        int completedCount = completedServices.size();
        boolean hasCompletedServices = completedCount > 0;

        BigDecimal totalServiceAmount = completedServices.stream()
                .map(ps -> ps.getFinalPrice() != null ? ps.getFinalPrice() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b));

        List<Prescription> prescriptions = prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(visitId);
        int prescriptionCount = prescriptions.size();
        boolean hasPrescription = prescriptionCount > 0;

        BigDecimal totalPrescriptionAmount = BigDecimal.ZERO;
        List<Map<String, Object>> prescriptionSummaries = new ArrayList<>();
        for (Prescription p : prescriptions) {
            BigDecimal pTotal = BigDecimal.ZERO;
            List<String> drugNames = new ArrayList<>();
            if (p.getItems() != null) {
                for (PrescriptionItem it : p.getItems()) {
                    BigDecimal qty = it.getQuantity() != null ? it.getQuantity() : BigDecimal.ONE;
                    BigDecimal price = it.getUnitPrice() != null ? it.getUnitPrice() : BigDecimal.ZERO;
                    BigDecimal itTotal = price.multiply(qty);
                    pTotal = pTotal.add(itTotal);
                    drugNames.add(
                            it.getDrugName() + " (" + qty + " " + (it.getUnit() != null ? it.getUnit() : "viên") + ")");
                }
            }
            totalPrescriptionAmount = totalPrescriptionAmount.add(pTotal);

            Map<String, Object> pMap = new HashMap<>();
            pMap.put("prescriptionId", p.getPrescriptionId());
            pMap.put("prescriptionCode", p.getPrescriptionCode());
            pMap.put("totalAmount", pTotal);
            pMap.put("drugCount", p.getItems() != null ? p.getItems().size() : 0);
            pMap.put("drugList", String.join(", ", drugNames));
            prescriptionSummaries.add(pMap);
        }

        BigDecimal grandTotalAmount = totalServiceAmount.add(totalPrescriptionAmount);

        List<TreatmentPlan> activePlans = (patient != null && patient.getProfileId() != null
                && treatmentPlanRepository != null)
                        ? treatmentPlanRepository.findByProfileIdWithDetails(patient.getProfileId()).stream()
                                .filter(tp -> tp.getStatus() != TreatmentPlanStatus.CANCELLED)
                                .toList()
                        : Collections.emptyList();
        int treatmentPlanCount = activePlans.size();

        // Chỉ những kế hoạch điều trị mà bác sĩ bấm "Chuyển Kế toán" mới được tính vào chi phí bàn giao
        BigDecimal totalEstimatedPlanAmount = BigDecimal.ZERO;
        List<Map<String, Object>> treatmentPlanSummaries = new ArrayList<>();
        for (TreatmentPlan tp : activePlans) {
            BigDecimal planEst = tp.getFinalEstimatedCost() != null ? tp.getFinalEstimatedCost()
                    : (tp.getTotalEstimatedCost() != null ? tp.getTotalEstimatedCost() : BigDecimal.ZERO);
            if (Boolean.TRUE.equals(tp.getSentToAccounting())) {
                totalEstimatedPlanAmount = totalEstimatedPlanAmount.add(planEst);
            }

            Map<String, Object> tpMap = new HashMap<>();
            tpMap.put("planId", tp.getPlanId());
            tpMap.put("planCode", tp.getPlanCode());
            tpMap.put("planName", tp.getPlanName());
            tpMap.put("estimatedAmount", planEst);
            tpMap.put("sentToAccounting", Boolean.TRUE.equals(tp.getSentToAccounting()));
            tpMap.put("status", tp.getStatus() != null ? tp.getStatus().name() : "PROPOSED");
            treatmentPlanSummaries.add(tpMap);
        }

        List<Appointment> upcomingAppointments = (patient != null && patient.getProfileId() != null)
                ? appointmentRepository.findByPatientProfile_ProfileIdOrderByAppointmentDateDesc(patient.getProfileId())
                        .stream()
                        .filter(a -> a.getAppointmentDate() != null
                                && !a.getAppointmentDate().isBefore(LocalDate.now()))
                        .toList()
                : Collections.emptyList();
        int reExamCount = upcomingAppointments.size();
        boolean hasReExam = reExamCount > 0;

        List<String> missingRequirements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT) {
            missingRequirements
                    .add("Ca khám này hiện đã ở trạng thái Chờ thanh toán (WAITING_PAYMENT) và đã khóa bệnh án.");
        } else if (visit.getStatus() == VisitStatus.COMPLETED) {
            missingRequirements.add("Ca khám này đã hoàn tất toàn bộ quy trình và xuất hóa đơn.");
        }

        // Bắt buộc lập phác đồ / kế hoạch điều trị (<<include>> trong Kết thúc khám)
        if (treatmentPlanCount <= 0) {
            missingRequirements.add("Bắt buộc phải lập phác đồ / kế hoạch điều trị trước khi kết thúc khám.");
        }

        // Kiểm tra điều kiện 1: Chẩn đoán ICD-10 (tùy chọn, chỉ cảnh báo nếu chưa có)
        if (!hasDiagnosis) {
            warnings.add(
                    "Chưa có chẩn đoán ICD-10 (khuyến nghị bổ sung chẩn đoán để hồ sơ bệnh án đầy đủ hơn).");
        } else if (!hasMainDiagnosis) {
            warnings.add(
                    "Chưa có chẩn đoán loại CHÍNH (MAIN). Khuyến nghị xác định chẩn đoán chính để bệnh án chuẩn y khoa.");
        }

        // Cảnh báo về dịch vụ thực hiện & chi phí
        if (!hasCompletedServices && !hasPrescription) {
            warnings.add(
                    "Ca khám này chưa ghi nhận dịch vụ thực hiện tại ghế hoặc đơn thuốc phát sinh chi phí. Nếu không có viện phí, ca khám sẽ được hoàn tất trực tiếp.");
        }

        // Kiểm tra chỉ định cận lâm sàng còn chờ thực hiện
        if (paraclinicalOrderItemRepository != null) {
            long waitingCount = paraclinicalOrderItemRepository.countByOrder_Visit_VisitIdAndStatus(
                    visitId, com.cvdcms.entity.enums.ParaclinicalOrderItemStatus.WAITING);
            if (waitingCount > 0) {
                warnings.add("Hiện còn " + waitingCount + " chỉ định cận lâm sàng đang ở trạng thái 'Chờ thực hiện'.");
            }
        }

        // Cảnh báo bổ sung (không chặn hoàn tất)
        if (!hasPrescription) {
            warnings.add("Chưa kê đơn thuốc cho ca khám này (bác sĩ có thể bỏ qua nếu điều trị không cần thuốc).");
        }
        if (!hasReExam) {
            warnings.add(
                    "Chưa hẹn lịch tái khám cho bệnh nhân (bác sĩ có thể bấm nút 'Hẹn tái khám' nếu cần theo dõi tiếp).");
        }

        boolean isReady = missingRequirements.isEmpty();

        List<Map<String, Object>> diagnosisSummaries = diagnoses.stream().map(d -> {
            Map<String, Object> map = new HashMap<>();
            map.put("diagnosisId", d.getDiagnosisId());
            map.put("icd10Code", d.getIcd10Disease() != null ? d.getIcd10Disease().getIcd10Code() : "");
            map.put("diseaseName", d.getDiagnosisText() != null ? d.getDiagnosisText()
                    : (d.getIcd10Disease() != null ? d.getIcd10Disease().getDiseaseNameVi() : ""));
            map.put("diagnosisType", d.getDiagnosisType() != null ? d.getDiagnosisType().name() : "MAIN");
            map.put("toothNumbers", d.getToothNumbers() != null ? d.getToothNumbers() : "Toàn hàm");
            return map;
        }).toList();

        List<Map<String, Object>> serviceSummaries = completedServices.stream().map(ps -> {
            Map<String, Object> map = new HashMap<>();
            map.put("performedId", ps.getPerformedId());
            String displayName = ps.getStepName() != null && !ps.getStepName().isBlank()
                    ? ps.getStepName()
                    : (ps.getService() != null ? ps.getService().getServiceName() : "");
            map.put("serviceName", displayName);
            map.put("serviceCode", ps.getService() != null ? ps.getService().getServiceCode() : "");
            map.put("toothNumbers", ps.getToothNumbers() != null ? ps.getToothNumbers() : "");
            map.put("quantity", ps.getQuantity() != null ? ps.getQuantity() : BigDecimal.ONE);
            map.put("unitPrice", ps.getUnitPrice() != null ? ps.getUnitPrice() : BigDecimal.ZERO);
            map.put("finalPrice", ps.getFinalPrice() != null ? ps.getFinalPrice() : BigDecimal.ZERO);
            map.put("doctorName", ps.getDoctor() != null ? ps.getDoctor().getFullName() : "");
            return map;
        }).toList();

        return ClinicalCompletionCheckDTO.builder()
                .visitId(visit.getVisitId())
                .visitCode(visit.getVisitCode())
                .profileId(patient != null ? patient.getProfileId() : null)
                .patientName(patient != null ? patient.getFullName() : "Khách vãng lai")
                .patientCode(patient != null ? patient.getPatientCode() : "")
                .doctorName(visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getFullName() : "BS trực")
                .currentStatus(visit.getStatus() != null ? visit.getStatus().name() : "WAITING")
                .ready(isReady)
                .hasDiagnosis(hasDiagnosis)
                .diagnosisCount(diagnosisCount)
                .hasMainDiagnosis(hasMainDiagnosis)
                .hasCompletedServices(hasCompletedServices)
                .completedServiceCount(completedCount)
                .totalServiceAmount(totalServiceAmount)
                .totalPrescriptionAmount(totalPrescriptionAmount)
                .totalBillableAmount(grandTotalAmount)
                .hasPrescription(hasPrescription)
                .prescriptionCount(prescriptionCount)
                .hasTreatmentPlan(treatmentPlanCount > 0)
                .treatmentPlanCount(treatmentPlanCount)
                .totalEstimatedPlanAmount(totalEstimatedPlanAmount)
                .hasReExamination(hasReExam)
                .reExaminationCount(reExamCount)
                .missingRequirements(missingRequirements)
                .warnings(warnings)
                .diagnoses(diagnosisSummaries)
                .services(serviceSummaries)
                .prescriptions(prescriptionSummaries)
                .treatmentPlans(treatmentPlanSummaries)
                .build();
    }

    @Override
    @Transactional
    public Visit completeClinicalExamination(Long visitId, String doctorNotes) {
        ClinicalCompletionCheckDTO check = checkClinicalCompletionReadiness(visitId);
        if (!check.isReady()) {
            throw new IllegalStateException(String.join(". ", check.getMissingRequirements()));
        }

        Visit visit = getVisitOrThrow(visitId);
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Lượt khám hiện tại không thể hoàn tất khám do đã khóa hoặc đã hủy");

        // Đồng bộ các chỉ định cận lâm sàng đã có kết quả sang dịch vụ thực hiện ngay khi hoàn tất ca khám
        if (paraclinicalOrderService != null) {
            try {
                paraclinicalOrderService.syncParaclinicalOrdersToPerformedServices(visitId);
            } catch (Exception ex) {
                log.warn("Lỗi đồng bộ chỉ định cận lâm sàng khi hoàn tất ca khám {}: {}", visitId, ex.getMessage());
            }
        }

        // Kiểm tra xem ca khám có phát sinh chi phí cần Kế toán lập hóa đơn lẻ hay không
        // (Dịch vụ thuộc KHDT trả theo đợt đã thu qua lộ trình phân kỳ nên không đẩy sang hóa đơn lẻ)
        List<PerformedService> completedServices = performedServiceRepository.findByVisitIdWithDetails(visitId).stream()
                .filter(ps -> ps.getStatus() == PerformedServiceStatus.COMPLETED)
                .toList();
        boolean hasBillableServices = completedServices.stream().anyMatch(ps -> !isInstallmentPlanService(ps));
        BigDecimal totalCompletedServiceAmount = completedServices.stream()
                .map(ps -> ps.getFinalPrice() != null ? ps.getFinalPrice() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal billableServiceAmount = completedServices.stream()
                .filter(ps -> !isInstallmentPlanService(ps))
                .map(ps -> ps.getFinalPrice() != null ? ps.getFinalPrice() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Prescription> payablePrescriptions = prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(visitId).stream()
                .filter(p -> p.getStatus() != com.cvdcms.entity.enums.PrescriptionStatus.CANCELLED
                        && p.getStatus() != com.cvdcms.entity.enums.PrescriptionStatus.EXTERNAL_PURCHASE)
                .toList();
        boolean hasPayablePrescription = !payablePrescriptions.isEmpty();
        BigDecimal totalPresc = BigDecimal.ZERO;
        for (Prescription p : payablePrescriptions) {
            if (p.getItems() != null) {
                for (PrescriptionItem it : p.getItems()) {
                    BigDecimal qty = it.getQuantity() != null ? it.getQuantity() : BigDecimal.ONE;
                    BigDecimal price = it.getUnitPrice() != null ? it.getUnitPrice() : BigDecimal.ZERO;
                    totalPresc = totalPresc.add(price.multiply(qty));
                }
            }
        }

        boolean hasPayableItems = billableServiceAmount.compareTo(BigDecimal.ZERO) > 0
                || totalPresc.compareTo(BigDecimal.ZERO) > 0
                || hasBillableServices
                || hasPayablePrescription;

        LocalDateTime clinicalEndTime = LocalDateTime.now(com.cvdcms.service.AppointmentPolicyService.CLINIC_ZONE);
        visit.setActualEndTime(clinicalEndTime);

        if (hasPayableItems) {
            visit.setStatus(VisitStatus.WAITING_PAYMENT);
        } else {
            // Không có dịch vụ/thuốc phát sinh chi phí -> Hoàn tất ca khám trực tiếp
            visit.setStatus(VisitStatus.COMPLETED);
            if (visit.getCompletedAt() == null) {
                visit.setCompletedAt(clinicalEndTime);
            }
            if (careEventPublisher != null && visit.getVisitId() != null) {
                careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.VisitCompletedCareEvent(visit.getVisitId()));
            }
        }

        if (dentalChairs != null && visit.getChairId() != null) {
            dentalChairs.findById(visit.getChairId()).ifPresent(chair -> {
                if (chair.getStatus() == com.cvdcms.entity.enums.ChairStatus.IN_USE) {
                    chair.setStatus(com.cvdcms.entity.enums.ChairStatus.AVAILABLE);
                    dentalChairs.save(chair);
                }
            });
        }
        if (queueCoordinator != null) {
            User actor = resolveCurrentSessionUser();
            queueCoordinator.completeVisitAndAutoCallNext(visit, actor);
        } else if (queueTickets != null) {
            queueTickets.findByVisitVisitIdAndQueueType(visitId, com.cvdcms.entity.enums.QueueType.EXAM)
                    .stream().filter(t -> t.getStatus() == com.cvdcms.entity.enums.QueueStatus.IN_SERVICE
                            || t.getStatus() == com.cvdcms.entity.enums.QueueStatus.CALLED)
                    .forEach(t -> {
                        t.setStatus(com.cvdcms.entity.enums.QueueStatus.DONE);
                        t.setCompletedAt(LocalDateTime.now());
                        queueTickets.save(t);
                    });

            // Fallback cho môi trường unit test không inject ClinicQueueCoordinatorService
            if (visit.getPrimaryDoctor() != null && visit.getBranch() != null) {
                Long docId = visit.getPrimaryDoctor().getStaffId();
                Long bId = visit.getBranch().getBranchId();
                LocalDate today = LocalDate.now();
                List<com.cvdcms.entity.QueueTicket> allBranchTickets = queueTickets.findAll();
                boolean alreadyCalledOrBusy = allBranchTickets.stream()
                        .anyMatch(t -> t.getBranch() != null && bId.equals(t.getBranch().getBranchId())
                                && today.equals(t.getQueueDate())
                                && t.getQueueType() == com.cvdcms.entity.enums.QueueType.EXAM
                                && (t.getStatus() == com.cvdcms.entity.enums.QueueStatus.CALLED
                                        || t.getStatus() == com.cvdcms.entity.enums.QueueStatus.IN_SERVICE)
                                && t.getVisit() != null && t.getVisit().getPrimaryDoctor() != null
                                && docId.equals(t.getVisit().getPrimaryDoctor().getStaffId()));
                if (!alreadyCalledOrBusy) {
                    var nextWaitingOpt = allBranchTickets.stream()
                            .filter(t -> t.getBranch() != null && bId.equals(t.getBranch().getBranchId())
                                    && today.equals(t.getQueueDate())
                                    && t.getQueueType() == com.cvdcms.entity.enums.QueueType.EXAM
                                    && t.getStatus() == com.cvdcms.entity.enums.QueueStatus.WAITING
                                    && t.getVisit() != null && t.getVisit().getPrimaryDoctor() != null
                                    && docId.equals(t.getVisit().getPrimaryDoctor().getStaffId()))
                            .min((a, b) -> {
                                boolean aEmer = Boolean.TRUE.equals(a.getIsEmergency());
                                boolean bEmer = Boolean.TRUE.equals(b.getIsEmergency());
                                if (aEmer != bEmer) return aEmer ? -1 : 1;
                                int aScore = a.getPriorityScore() != null ? a.getPriorityScore() : 0;
                                int bScore = b.getPriorityScore() != null ? b.getPriorityScore() : 0;
                                if (aScore != bScore) return Integer.compare(bScore, aScore);
                                int aNum = a.getTicketNumber() != null ? a.getTicketNumber() : 999999;
                                int bNum = b.getTicketNumber() != null ? b.getTicketNumber() : 999999;
                                return Integer.compare(aNum, bNum);
                            });
                    nextWaitingOpt.ifPresent(nextTicket -> {
                        nextTicket.setStatus(com.cvdcms.entity.enums.QueueStatus.CALLED);
                        nextTicket.setCalledAt(LocalDateTime.now());
                        queueTickets.save(nextTicket);
                    });
                }
            }
        }

        ClinicalExamination exam = clinicalExamRepository.findByVisit_VisitId(visitId).orElse(null);
        if (exam == null && examinations != null && visit.getPatientProfile() != null && visit.getPrimaryDoctor() != null) {
            exam = createDefaultExamination(visit);
        }
        if (exam != null) {
            String trimmedDoctorNotes = doctorNotes != null ? doctorNotes.trim() : null;
            if (trimmedDoctorNotes != null && !trimmedDoctorNotes.isEmpty()) {
                exam.setSummaryAssessment(trimmedDoctorNotes);
                if (visit.getNotes() == null || visit.getNotes().isBlank()) {
                    visit.setNotes(trimmedDoctorNotes);
                } else if (!visit.getNotes().contains(trimmedDoctorNotes)) {
                    visit.setNotes(visit.getNotes().trim() + " | Kết luận: " + trimmedDoctorNotes);
                }
            } else if (exam.getSummaryAssessment() == null || exam.getSummaryAssessment().isBlank()) {
                String synthesized = buildDefaultSummaryAssessment(visit, exam, completedServices);
                if (!synthesized.isBlank()) {
                    exam.setSummaryAssessment(synthesized);
                }
            }
            clinicalExamRepository.save(exam);
        }

        NumberFormat vndFmt = NumberFormat.getInstance(Locale.forLanguageTag("vi-VN"));
        String svcCostStr = vndFmt.format(totalCompletedServiceAmount) + "đ";
        String prescCostStr = vndFmt.format(totalPresc) + "đ";

        String timestamp = LocalDateTime.now().format(AUDIT_DATE_TIME);
        String doctorName = visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getFullName()
                : "Bác sĩ điều trị";
        log.info("Ca khám visitId={} đã được bác sĩ {} hoàn tất. Trạng thái mới: {}. Viện phí phát sinh: [DV: {} | Thuốc: {}]. Ghi chú: {}", 
                visitId, doctorName, visit.getStatus().name(), svcCostStr, prescCostStr, doctorNotes);

        Visit saved = visitRepository.save(visit);
        if (careEventPublisher != null) {
            careEventPublisher.publishEvent(new com.cvdcms.event.ClinicQueueDomainEvent(
                    saved.getStatus() == VisitStatus.COMPLETED ? "VISIT_COMPLETED" : "VISIT_CLINICAL_COMPLETED",
                    saved.getBranch() != null ? saved.getBranch().getBranchId() : null,
                    saved.getPrimaryDoctor() != null ? saved.getPrimaryDoctor().getStaffId() : null,
                    saved.getVisitId(),
                    saved.getAppointmentId(),
                    saved.getStatus().name(),
                    "Hoàn tất khám lâm sàng ca " + saved.getVisitCode() + " (giải phóng slot còn lại)",
                    LocalDateTime.now(com.cvdcms.service.AppointmentPolicyService.CLINIC_ZONE)));
        }
        if (saved.getStatus() == VisitStatus.COMPLETED && appointmentLifecycleService != null) {
            Long actorUserId = resolveCurrentActorUserId(saved);
            if (actorUserId != null) {
                try {
                    appointmentLifecycleService.completeByVisit(saved.getVisitId(), actorUserId);
                } catch (Exception ex) {
                    log.warn("Không thể hoàn tất lịch hẹn gắn với ca khám {}: {}", saved.getVisitId(), ex.getMessage());
                }
            }
        }
        return saved;
    }

    private Long resolveCurrentActorUserId(Visit visit) {
        try {
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes web) {
                var session = web.getRequest().getSession(false);
                if (session != null && session.getAttribute("loggedInUser") instanceof User user && user.getUserId() != null) {
                    return user.getUserId();
                }
            }
        } catch (Exception ignored) {}
        if (visit != null && visit.getPrimaryDoctor() != null && visit.getPrimaryDoctor().getUser() != null) {
            return visit.getPrimaryDoctor().getUser().getUserId();
        }
        return null;
    }

    private String buildDefaultSummaryAssessment(Visit visit, ClinicalExamination exam, List<PerformedService> completedServices) {
        List<String> parts = new ArrayList<>();
        diagnosisRepository.findByVisit_VisitId(visit.getVisitId()).stream()
                .filter(d -> d.getDiagnosisType() == DiagnosisType.MAIN)
                .findFirst()
                .ifPresent(main -> {
                    String code = main.getIcd10Disease() != null ? main.getIcd10Disease().getIcd10Code() + " - " : "";
                    String text = main.getDiagnosisText() != null ? main.getDiagnosisText() : "";
                    if (!(code + text).isBlank()) {
                        parts.add("Chẩn đoán chính: " + code + text.trim());
                    }
                });
        if (completedServices != null && !completedServices.isEmpty()) {
            String svcNames = completedServices.stream()
                    .map(ps -> ps.getService() != null ? ps.getService().getServiceName() : null)
                    .filter(Objects::nonNull)
                    .distinct()
                    .limit(4)
                    .collect(java.util.stream.Collectors.joining(", "));
            if (!svcNames.isBlank()) {
                parts.add("Thủ thuật thực hiện: " + svcNames);
            }
        }
        if (parts.isEmpty() && exam.getChiefComplaint() != null && !exam.getChiefComplaint().isBlank()) {
            parts.add(exam.getChiefComplaint().trim());
        }
        return String.join(". ", parts);
    }

    private boolean isInstallmentPlanService(PerformedService ps) {
        return ps != null
                && ps.getPlanItem() != null
                && ps.getPlanItem().getPlan() != null
                && Boolean.TRUE.equals(ps.getPlanItem().getPlan().getIsInstallment());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Visit reopenClinicalExamination(Long visitId, String reason) {
        Visit preCheck = visitRepository.findById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lượt khám ID: " + visitId));
        if (branchRepository != null && preCheck.getBranch() != null && preCheck.getBranch().getBranchId() != null) {
            branchRepository.findByIdForUpdate(preCheck.getBranch().getBranchId());
        }
        Visit visit = visitRepository.lockById(visitId)
                .or(() -> visitRepository.findById(visitId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lượt khám ID: " + visitId));
        if (visit.getStatus() != VisitStatus.WAITING_PAYMENT) {
            throw new IllegalStateException(
                    "Chỉ có thể mở lại ca khám đang ở trạng thái Chờ thanh toán (WAITING_PAYMENT). Trạng thái hiện tại: "
                            + visit.getStatus().name());
        }
        if (visit.getBranch() != null && visit.getPrimaryDoctor() != null) {
            LocalDate today = LocalDate.now(com.cvdcms.service.AppointmentPolicyService.CLINIC_ZONE);
            List<Visit> activeSameDesk = visitRepository.findActiveExamsForDoctorAtBranchOnDay(
                    visit.getBranch().getBranchId(),
                    visit.getPrimaryDoctor().getStaffId(),
                    List.of(VisitStatus.IN_EXAM, VisitStatus.IN_TREATMENT),
                    today.atStartOfDay(),
                    today.plusDays(1).atStartOfDay());
            for (Visit other : activeSameDesk) {
                if (!Objects.equals(other.getVisitId(), visitId)) {
                    throw new IllegalStateException(
                            "Bàn khám đang có ca khám khác đang thực hiện (" + other.getVisitCode()
                                    + "). Vui lòng hoàn tất ca đang khám trước khi mở lại ca cũ.");
                }
            }
        }

        if (emrRecordRepository != null) {
            emrRecordRepository.findByVisit_VisitId(visitId).ifPresent(record -> {
                if (com.cvdcms.emr.EmrStatePolicy.isEmrLockedStatus(record.getStatus())) {
                    throw new IllegalStateException(
                            "Hồ sơ bệnh án điện tử (" + record.getRecordCode()
                                    + ") đã khóa/ký xác thực. Không thể mở lại ca khám (BR-EMR-02).");
                }
            });
        }

        if (invoiceRepository.existsByVisit_VisitIdAndStatusNot(visitId,
                com.cvdcms.entity.enums.InvoiceStatus.CANCELLED)) {
            throw new IllegalStateException(
                    "Không thể mở lại ca khám đã có hóa đơn còn hiệu lực. Vui lòng xử lý hóa đơn trước.");
        }

        if (reason == null || reason.trim().length() < 10 || reason.trim().length() > 255) {
            throw new IllegalArgumentException("Lý do mở lại cần từ 10 đến 255 ký tự.");
        }

        visit.setStatus(VisitStatus.IN_EXAM);
        // Mở bệnh án để bổ sung tại bàn; số gọi cũ và ghế đã giải phóng không được tái kích hoạt.
        visit.setChairId(null);
        visit.setRoomId(null);
        visit.setActualEndTime(null);
        if (auditLogs != null) {
            SystemAuditLog audit = new SystemAuditLog();
            audit.setBranch(visit.getBranch());
            audit.setEntityTable("visits");
            audit.setEntityId(visitId);
            audit.setAction("REOPEN_CLINICAL");
            audit.setOldValuesJson("{\"status\":\"WAITING_PAYMENT\"}");
            try {
                audit.setNewValuesJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        Map.of("status", "IN_EXAM", "reason", reason.trim())));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException("Không thể ghi nhật ký mở lại ca khám.", ex);
            }
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes web) {
                var session = web.getRequest().getSession(false);
                if (session != null && session.getAttribute("loggedInUser") instanceof User user) audit.setUser(user);
            }
            auditLogs.save(audit);
        }

        String timestamp = LocalDateTime.now().format(AUDIT_DATE_TIME);
        log.info("Ca khám visitId={} đã được mở lại sang IN_EXAM từ WAITING_PAYMENT lúc {}. Lý do: {}", visitId, timestamp, reason);

        Visit saved = visitRepository.save(visit);
        if (careEventPublisher != null) {
            careEventPublisher.publishEvent(new com.cvdcms.event.ClinicQueueDomainEvent(
                    "VISIT_REOPENED",
                    saved.getBranch() != null ? saved.getBranch().getBranchId() : null,
                    saved.getPrimaryDoctor() != null ? saved.getPrimaryDoctor().getStaffId() : null,
                    saved.getVisitId(),
                    saved.getAppointmentId(),
                    saved.getStatus().name(),
                    "Mở lại ca khám lâm sàng",
                    LocalDateTime.now()));
        }
        log.info("Ca khám visitId={} đã được mở lại sang IN_EXAM", visitId);
        return saved;
    }

    private ClinicalExamination createDefaultExamination(Visit visit) {
        if (examinations == null) {
            throw new IllegalStateException("Không thể khởi tạo hồ sơ lâm sàng: dịch vụ khởi tạo chưa sẵn sàng.");
        }
        return examinations.ensureForAssignedVisit(visit.getVisitId());
    }

    private DiagnosisResponseDTO mapToDTO(ExaminationDiagnosis ed) {
        String typeNameVi = switch (ed.getDiagnosisType()) {
            case MAIN -> "Chẩn đoán chính";
            case SECONDARY -> "Kèm theo";
            case DIFFERENTIAL -> "Phân biệt";
        };

        Icd10Disease disease = ed.getIcd10Disease();
        Visit visit = ed.getVisit();
        PatientProfile patient = (visit != null) ? visit.getPatientProfile() : null;

        return DiagnosisResponseDTO.builder()
                .diagnosisId(ed.getDiagnosisId())
                .examId(ed.getClinicalExamination() != null ? ed.getClinicalExamination().getExamId() : null)
                .visitId(visit != null ? visit.getVisitId() : null)
                .visitCode(visit != null ? visit.getVisitCode() : null)
                .patientName(patient != null ? patient.getFullName() : null)
                .icd10Code(disease != null ? disease.getIcd10Code() : null)
                .diseaseNameVi(disease != null ? disease.getDiseaseNameVi() : null)
                .diseaseNameEn(disease != null ? disease.getDiseaseNameEn() : null)
                .diagnosisText(ed.getDiagnosisText())
                .diagnosisType(ed.getDiagnosisType())
                .diagnosisTypeDisplayName(typeNameVi)
                .toothNumbers(ed.getToothNumbers())
                .notes(ed.getNotes())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ClinicalExamination getClinicalExaminationByVisitId(Long visitId) {
        if (visitId == null)
            return null;
        return clinicalExamRepository.findByVisit_VisitId(visitId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public GeneralExaminationRequestDTO getGeneralExamination(Long visitId) {
        Visit visit = getVisitOrThrow(visitId);
        PatientProfile patient = visit.getPatientProfile();
        ClinicalExamination exam = clinicalExamRepository.findByVisit_VisitId(visitId).orElse(null);

        boolean hasCardio = patient != null && Boolean.TRUE.equals(patient.getHasCardiovascularDisease());
        boolean hasAllergies = patient != null && Boolean.TRUE.equals(patient.getHasAllergies());
        String allergyDetails = hasAllergies && patient != null ? patient.getAllergyDetails() : null;

        String rawUnderlyingDetails = patient != null ? patient.getUnderlyingDiseaseDetails() : null;
        boolean hasDiabetes = containsConditionToken(rawUnderlyingDetails, "Đái tháo đường");
        boolean hasBleeding = containsConditionToken(rawUnderlyingDetails, "Rối loạn đông máu");
        boolean isPregnant = containsConditionToken(rawUnderlyingDetails, "Mang thai / Cho con bú")
                || containsConditionToken(rawUnderlyingDetails, "Mang thai")
                || containsConditionToken(rawUnderlyingDetails, "Cho con bú");
        String extraUnderlyingDetails = extractExtraUnderlyingDetails(rawUnderlyingDetails);

        return GeneralExaminationRequestDTO.builder()
                .visitId(visitId)
                .initialSymptoms(visit.getInitialSymptoms() != null ? visit.getInitialSymptoms()
                        : (exam != null ? exam.getChiefComplaint() : null))
                .hasAllergies(hasAllergies)
                .allergyDetails(allergyDetails)
                .hasCardiovascularDisease(hasCardio)
                .hasDiabetes(hasDiabetes)
                .hasBleedingDisorder(hasBleeding)
                .isPregnant(isPregnant)
                .underlyingDiseaseDetails(extraUnderlyingDetails)
                .tmjStatus(exam != null && exam.getExtraoralExam() != null ? exam.getExtraoralExam() : "NORMAL")
                .hygieneStatus(exam != null && exam.getIntraoralExam() != null ? exam.getIntraoralExam() : "MEDIUM")
                .gingivaStatus(
                        exam != null && exam.getPeriodontalStatus() != null ? exam.getPeriodontalStatus() : "HEALTHY")
                .summaryAssessment(exam != null ? exam.getSummaryAssessment() : null)
                .build();
    }

    @Override
    public void saveGeneralExamination(GeneralExaminationRequestDTO request) {
        if (request == null || request.getVisitId() == null) {
            throw new IllegalArgumentException("Mã ca khám (visitId) không hợp lệ.");
        }

        Visit visit = getVisitOrThrow(request.getVisitId());

        // Kiểm tra khóa bất biến EMR (BR-EMR-02)
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Hồ sơ bệnh án lâm sàng đã khóa. Không thể chỉnh sửa thông tin khám");

        // 1. Cập nhật triệu chứng ban đầu trên Visit
        if (request.getInitialSymptoms() != null) {
            visit.setInitialSymptoms(request.getInitialSymptoms().trim());
            visitRepository.save(visit);
        }

        // 2. Cập nhật tiền sử y khoa & cảnh báo dị ứng trên PatientProfile
        PatientProfile patient = visit.getPatientProfile();
        if (patient != null) {
            boolean hasAllergies = Boolean.TRUE.equals(request.getHasAllergies());
            patient.setHasAllergies(hasAllergies);
            String trimmedAllergy = request.getAllergyDetails() != null ? request.getAllergyDetails().trim() : "";
            patient.setAllergyDetails(hasAllergies && !trimmedAllergy.isEmpty() ? trimmedAllergy : null);
            patient.setHasCardiovascularDisease(Boolean.TRUE.equals(request.getHasCardiovascularDisease()));

            Set<String> conditions = new LinkedHashSet<>();
            if (Boolean.TRUE.equals(request.getHasDiabetes())) {
                conditions.add("Đái tháo đường");
            }
            if (Boolean.TRUE.equals(request.getHasBleedingDisorder())) {
                conditions.add("Rối loạn đông máu");
            }
            if (Boolean.TRUE.equals(request.getIsPregnant())) {
                conditions.add("Mang thai / Cho con bú");
            }
            String extraDetails = extractExtraUnderlyingDetails(request.getUnderlyingDiseaseDetails());
            if (extraDetails != null && !extraDetails.isEmpty()) {
                for (String token : extraDetails.split(";")) {
                    String trimmed = token.trim();
                    if (!trimmed.isEmpty()) {
                        conditions.add(trimmed);
                    }
                }
            }

            if (!conditions.isEmpty()) {
                patient.setHasUnderlyingDisease(true);
                patient.setUnderlyingDiseaseDetails(String.join("; ", conditions));
            } else {
                patient.setHasUnderlyingDisease(false);
                patient.setUnderlyingDiseaseDetails(null);
            }
            patient.setMedicalHistoryCompleted(true);
            patientProfileRepository.save(patient);
        }

        // 3. Cập nhật hoặc khởi tạo ClinicalExamination
        ClinicalExamination exam = clinicalExamRepository.findByVisit_VisitId(visit.getVisitId())
                .orElseGet(() -> createDefaultExamination(visit));

        if (request.getInitialSymptoms() != null && !request.getInitialSymptoms().trim().isEmpty()) {
            exam.setChiefComplaint(request.getInitialSymptoms().trim());
        }
        if (request.getTmjStatus() != null) {
            exam.setExtraoralExam(request.getTmjStatus().trim());
        }
        if (request.getHygieneStatus() != null) {
            exam.setIntraoralExam(request.getHygieneStatus().trim());
        }
        if (request.getGingivaStatus() != null) {
            exam.setPeriodontalStatus(request.getGingivaStatus().trim());
        }
        if (request.getSummaryAssessment() != null) {
            String trimmedSummary = request.getSummaryAssessment().trim();
            exam.setSummaryAssessment(trimmedSummary.isEmpty() ? null : trimmedSummary);
        }
        clinicalExamRepository.save(exam);
        log.info("Đã lưu kết quả khám tổng quát cho ca khám visitId={}", visit.getVisitId());
    }

    private static boolean containsConditionToken(String rawDetails, String keyword) {
        if (rawDetails == null || rawDetails.isBlank() || keyword == null) {
            return false;
        }
        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
        for (String part : rawDetails.split(";")) {
            if (part != null && part.trim().toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPresetUnderlyingCondition(String token) {
        if (token == null) {
            return false;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("đái tháo đường")
                || normalized.equals("rối loạn đông máu")
                || normalized.equals("mang thai / cho con bú")
                || normalized.equals("mang thai")
                || normalized.equals("cho con bú");
    }

    private static String extractExtraUnderlyingDetails(String rawDetails) {
        if (rawDetails == null || rawDetails.isBlank()) {
            return null;
        }
        Set<String> extras = new LinkedHashSet<>();
        for (String part : rawDetails.split(";")) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !isPresetUnderlyingCondition(trimmed)) {
                extras.add(trimmed);
            }
        }
        return extras.isEmpty() ? null : String.join("; ", extras);
    }

    @Override
    @Transactional
    public void saveDoctorInstructions(Long visitId, String adviceNotes) {
        if (visitId == null) {
            throw new IllegalArgumentException("Mã ca khám không hợp lệ.");
        }
        Visit visit = getVisitOrThrow(visitId);
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Hồ sơ bệnh án lâm sàng đã khóa. Không thể cập nhật lời dặn bác sĩ");

        String trimmedNotes = (adviceNotes != null) ? adviceNotes.trim() : "";
        visit.setNotes(trimmedNotes);
        visitRepository.save(visit);

        // Đồng bộ sang đơn thuốc nếu ca khám đã có đơn thuốc
        List<com.cvdcms.entity.Prescription> prescriptions = prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(visitId);
        if (!prescriptions.isEmpty()) {
            for (com.cvdcms.entity.Prescription p : prescriptions) {
                p.setAdviceNotes(trimmedNotes);
                prescriptionRepository.save(p);
            }
        }
        log.info("Đã lưu lời dặn của bác sĩ cho ca khám visitId={}", visitId);
    }
}
