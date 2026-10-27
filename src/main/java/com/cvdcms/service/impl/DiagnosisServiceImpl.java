package com.cvdcms.service.impl;

import com.cvdcms.DTO.ClinicalCompletionCheckDTO;
import com.cvdcms.DTO.DiagnosisRequestDTO;
import com.cvdcms.DTO.DiagnosisResponseDTO;
import com.cvdcms.DTO.GeneralExaminationRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.DiagnosisType;
import com.cvdcms.entity.enums.PerformedServiceStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.DiagnosisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DiagnosisServiceImpl implements DiagnosisService {

    private final ExaminationDiagnosisRepository diagnosisRepository;
    private final Icd10DiseaseRepository icd10DiseaseRepository;
    private final VisitRepository visitRepository;
    private final ClinicalExaminationRepository clinicalExamRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final PerformedServiceRepository performedServiceRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AppointmentRepository appointmentRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final InvoiceRepository invoiceRepository;

    private static final DateTimeFormatter AUDIT_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

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
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + request.getVisitId()));

        // Quy tắc BR-EMR-02: Khóa bệnh án lâm sàng bất biến khi đã chuyển thanh toán hoặc hoàn thành
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED) {
            throw new IllegalStateException("Bệnh án lâm sàng đã được hoàn tất và khóa bất biến (BR-EMR-02). Không thể thêm chẩn đoán mới.");
        }

        Icd10Disease disease = icd10DiseaseRepository.findByIcd10Code(request.getIcd10Code().trim())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã bệnh ICD-10: " + request.getIcd10Code()));

        // Tìm hoặc tạo đợt khám lâm sàng ClinicalExamination tương ứng
        ClinicalExamination exam = clinicalExamRepository.findByVisit_VisitId(visit.getVisitId())
                .orElseGet(() -> createDefaultExamination(visit));

        // Nếu chẩn đoán mới là MAIN và đã có MAIN trước đó, chuyển MAIN cũ thành SECONDARY để bệnh án chuẩn y khoa
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
        if (diagnosis.getVisit() != null &&
                (diagnosis.getVisit().getStatus() == VisitStatus.WAITING_PAYMENT || diagnosis.getVisit().getStatus() == VisitStatus.COMPLETED)) {
            throw new IllegalStateException("Bệnh án lâm sàng đã được hoàn tất và khóa bất biến (BR-EMR-02). Không thể xóa chẩn đoán.");
        }

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
        return visitRepository.findRecentVisitsByStatuses(
                List.of(VisitStatus.IN_EXAM, VisitStatus.WAITING, VisitStatus.IN_TREATMENT)
        );
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

        BigDecimal totalBillableAmount = completedServices.stream()
                .map(ps -> ps.getFinalPrice() != null ? ps.getFinalPrice() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Prescription> prescriptions = prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(visitId);
        int prescriptionCount = prescriptions.size();
        boolean hasPrescription = prescriptionCount > 0;

        List<Appointment> upcomingAppointments = (patient != null && patient.getProfileId() != null)
                ? appointmentRepository.findByPatientProfile_ProfileIdOrderByAppointmentDateDesc(patient.getProfileId()).stream()
                .filter(a -> a.getAppointmentDate() != null && !a.getAppointmentDate().isBefore(LocalDate.now()))
                .toList()
                : Collections.emptyList();
        int reExamCount = upcomingAppointments.size();
        boolean hasReExam = reExamCount > 0;

        List<String> missingRequirements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT) {
            missingRequirements.add("Ca khám này hiện đã ở trạng thái Chờ thanh toán (WAITING_PAYMENT) và đã khóa bệnh án.");
        } else if (visit.getStatus() == VisitStatus.COMPLETED) {
            missingRequirements.add("Ca khám này đã hoàn tất toàn bộ quy trình và xuất hóa đơn.");
        }

        // Kiểm tra điều kiện 1: Đã có chẩn đoán ICD-10
        if (!hasDiagnosis) {
            missingRequirements.add("Chưa có chẩn đoán ICD-10. Bác sĩ cần thêm ít nhất 01 chẩn đoán trước khi hoàn tất ca khám.");
        } else if (!hasMainDiagnosis) {
            warnings.add("Chưa có chẩn đoán loại CHÍNH (MAIN). Khuyến nghị xác định chẩn đoán chính để bệnh án chuẩn y khoa.");
        }

        // Kiểm tra điều kiện 2: Dịch vụ đã chuyển COMPLETED
        if (!hasCompletedServices) {
            missingRequirements.add("Chưa có dịch vụ nào chuyển trạng thái COMPLETED. Bác sĩ cần ghi nhận ít nhất 01 dịch vụ hoàn thành để Kế toán lập hóa đơn thu tiền.");
        }

        // Cảnh báo bổ sung (không chặn hoàn tất)
        if (!hasPrescription) {
            warnings.add("Chưa kê đơn thuốc cho ca khám này (bác sĩ có thể bỏ qua nếu điều trị không cần thuốc).");
        }
        if (!hasReExam) {
            warnings.add("Chưa hẹn lịch tái khám cho bệnh nhân (bác sĩ có thể bấm nút 'Hẹn tái khám' nếu cần theo dõi tiếp).");
        }

        boolean isReady = missingRequirements.isEmpty();

        List<Map<String, Object>> diagnosisSummaries = diagnoses.stream().map(d -> {
            Map<String, Object> map = new HashMap<>();
            map.put("diagnosisId", d.getDiagnosisId());
            map.put("icd10Code", d.getIcd10Disease() != null ? d.getIcd10Disease().getIcd10Code() : "");
            map.put("diseaseName", d.getDiagnosisText() != null ? d.getDiagnosisText() : (d.getIcd10Disease() != null ? d.getIcd10Disease().getDiseaseNameVi() : ""));
            map.put("diagnosisType", d.getDiagnosisType() != null ? d.getDiagnosisType().name() : "MAIN");
            map.put("toothNumbers", d.getToothNumbers() != null ? d.getToothNumbers() : "Toàn hàm");
            return map;
        }).toList();

        List<Map<String, Object>> serviceSummaries = completedServices.stream().map(ps -> {
            Map<String, Object> map = new HashMap<>();
            map.put("performedId", ps.getPerformedId());
            map.put("serviceName", ps.getService() != null ? ps.getService().getServiceName() : "");
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
                .totalBillableAmount(totalBillableAmount)
                .hasPrescription(hasPrescription)
                .prescriptionCount(prescriptionCount)
                .hasReExamination(hasReExam)
                .reExaminationCount(reExamCount)
                .missingRequirements(missingRequirements)
                .warnings(warnings)
                .diagnoses(diagnosisSummaries)
                .services(serviceSummaries)
                .build();
    }

    @Override
    public Visit completeClinicalExamination(Long visitId, String doctorNotes) {
        ClinicalCompletionCheckDTO check = checkClinicalCompletionReadiness(visitId);
        if (!check.isReady()) {
            throw new IllegalStateException(String.join(". ", check.getMissingRequirements()));
        }

        Visit visit = getVisitOrThrow(visitId);
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED) {
            throw new IllegalStateException("Lượt khám hiện tại đã ở trạng thái " + visit.getStatus().name() + ".");
        }

        visit.setStatus(VisitStatus.WAITING_PAYMENT);

        String timestamp = LocalDateTime.now().format(AUDIT_DATE_TIME);
        String doctorName = visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getFullName() : "Bác sĩ điều trị";
        String auditLog = String.format("\n[%s] Bác sĩ %s hoàn tất ca khám lâm sàng và chuyển sang Chờ thanh toán. (Quy tắc BR-EMR-02: Khóa bệnh án lâm sàng).%s",
                timestamp,
                doctorName,
                (doctorNotes != null && !doctorNotes.trim().isEmpty() ? " Ghi chú bàn giao: " + doctorNotes.trim() : "")
        );

        String existingNotes = visit.getNotes();
        visit.setNotes(existingNotes != null ? existingNotes + auditLog : auditLog.trim());

        Visit saved = visitRepository.save(visit);
        log.info("Ca khám visitId={} đã được bác sĩ hoàn tất và chuyển sang WAITING_PAYMENT (BR-EMR-02 locked)", visitId);
        return saved;
    }

    @Override
    public Visit reopenClinicalExamination(Long visitId, String reason) {
        Visit visit = getVisitOrThrow(visitId);
        if (visit.getStatus() != VisitStatus.WAITING_PAYMENT) {
            throw new IllegalStateException("Chỉ có thể mở lại ca khám đang ở trạng thái Chờ thanh toán (WAITING_PAYMENT). Trạng thái hiện tại: " + visit.getStatus().name());
        }

        if (invoiceRepository.existsByVisit_VisitIdAndStatusNot(visitId, com.cvdcms.entity.enums.InvoiceStatus.CANCELLED)) {
            throw new IllegalStateException("Không thể mở lại ca khám đã có hóa đơn còn hiệu lực. Vui lòng xử lý hóa đơn trước.");
        }

        visit.setStatus(VisitStatus.IN_EXAM);

        String timestamp = LocalDateTime.now().format(AUDIT_DATE_TIME);
        String auditLog = String.format("\n[%s] Mở lại ca khám lâm sàng từ trạng thái Chờ thanh toán để điều chỉnh.%s",
                timestamp,
                (reason != null && !reason.trim().isEmpty() ? " Lý do: " + reason.trim() : "")
        );

        String existingNotes = visit.getNotes();
        visit.setNotes(existingNotes != null ? existingNotes + auditLog : auditLog.trim());

        Visit saved = visitRepository.save(visit);
        log.info("Ca khám visitId={} đã được mở lại sang IN_EXAM", visitId);
        return saved;
    }

    private ClinicalExamination createDefaultExamination(Visit visit) {
        ClinicalExamination exam = new ClinicalExamination();
        exam.setVisit(visit);
        exam.setPatientProfile(visit.getPatientProfile());

        StaffProfile doctor = visit.getPrimaryDoctor();
        if (doctor == null) {
            // lấy bác sĩ đầu tiên hoặc tạo bác sĩ mẫu
            doctor = staffProfileRepository.findAll().stream().findFirst().orElse(null);
        }
        exam.setDoctor(doctor);
        exam.setChiefComplaint(visit.getInitialSymptoms() != null ? visit.getInitialSymptoms() : "Đến khám nha khoa định kỳ");
        exam.setExamTime(LocalDateTime.now());
        return clinicalExamRepository.save(exam);
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
        if (visitId == null) return null;
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
        String allergyDetails = patient != null ? patient.getAllergyDetails() : null;

        String underlyingDetails = patient != null ? patient.getUnderlyingDiseaseDetails() : null;
        boolean hasDiabetes = underlyingDetails != null && underlyingDetails.contains("Đái tháo đường");
        boolean hasBleeding = underlyingDetails != null && underlyingDetails.contains("Rối loạn đông máu");
        boolean isPregnant = underlyingDetails != null && (underlyingDetails.contains("Mang thai") || underlyingDetails.contains("Cho con bú"));

        return GeneralExaminationRequestDTO.builder()
                .visitId(visitId)
                .initialSymptoms(visit.getInitialSymptoms() != null ? visit.getInitialSymptoms() : (exam != null ? exam.getChiefComplaint() : null))
                .hasAllergies(hasAllergies)
                .allergyDetails(allergyDetails)
                .hasCardiovascularDisease(hasCardio)
                .hasDiabetes(hasDiabetes)
                .hasBleedingDisorder(hasBleeding)
                .isPregnant(isPregnant)
                .underlyingDiseaseDetails(underlyingDetails)
                .tmjStatus(exam != null && exam.getExtraoralExam() != null ? exam.getExtraoralExam() : "NORMAL")
                .hygieneStatus(exam != null && exam.getIntraoralExam() != null ? exam.getIntraoralExam() : "MEDIUM")
                .gingivaStatus(exam != null && exam.getPeriodontalStatus() != null ? exam.getPeriodontalStatus() : "HEALTHY")
                .build();
    }

    @Override
    public void saveGeneralExamination(GeneralExaminationRequestDTO request) {
        if (request == null || request.getVisitId() == null) {
            throw new IllegalArgumentException("Mã ca khám (visitId) không hợp lệ.");
        }

        Visit visit = getVisitOrThrow(request.getVisitId());

        // Kiểm tra khóa bất biến EMR (BR-EMR-02)
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED) {
            throw new IllegalStateException("Hồ sơ bệnh án lâm sàng đã khóa (BR-EMR-02). Không thể chỉnh sửa thông tin khám.");
        }

        // 1. Cập nhật triệu chứng ban đầu trên Visit
        if (request.getInitialSymptoms() != null) {
            visit.setInitialSymptoms(request.getInitialSymptoms().trim());
            visitRepository.save(visit);
        }

        // 2. Cập nhật tiền sử y khoa & cảnh báo dị ứng trên PatientProfile
        PatientProfile patient = visit.getPatientProfile();
        if (patient != null) {
            patient.setHasAllergies(Boolean.TRUE.equals(request.getHasAllergies()));
            patient.setAllergyDetails(request.getAllergyDetails() != null ? request.getAllergyDetails().trim() : null);
            patient.setHasCardiovascularDisease(Boolean.TRUE.equals(request.getHasCardiovascularDisease()));

            List<String> conditions = new ArrayList<>();
            if (Boolean.TRUE.equals(request.getHasDiabetes())) {
                conditions.add("Đái tháo đường");
            }
            if (Boolean.TRUE.equals(request.getHasBleedingDisorder())) {
                conditions.add("Rối loạn đông máu");
            }
            if (Boolean.TRUE.equals(request.getIsPregnant())) {
                conditions.add("Mang thai / Cho con bú");
            }
            if (request.getUnderlyingDiseaseDetails() != null && !request.getUnderlyingDiseaseDetails().trim().isEmpty()) {
                String extra = request.getUnderlyingDiseaseDetails().trim();
                if (!conditions.contains(extra)) {
                    conditions.add(extra);
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
        clinicalExamRepository.save(exam);
        log.info("Đã lưu kết quả khám tổng quát cho ca khám visitId={}", visit.getVisitId());
    }
}
