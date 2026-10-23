package com.cvdcms.service.impl;

import com.cvdcms.DTO.DiagnosisRequestDTO;
import com.cvdcms.DTO.DiagnosisResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.DiagnosisType;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.DiagnosisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
        if (!diagnosisRepository.existsById(diagnosisId)) {
            throw new IllegalArgumentException("Không tìm thấy chẩn đoán có ID: " + diagnosisId);
        }
        diagnosisRepository.deleteById(diagnosisId);
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
}
