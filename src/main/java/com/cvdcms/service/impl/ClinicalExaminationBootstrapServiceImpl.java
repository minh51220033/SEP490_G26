package com.cvdcms.service.impl;

import com.cvdcms.entity.ClinicalExamination;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.ClinicalExaminationRepository;
import com.cvdcms.repository.ExaminationDiagnosisRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ClinicalExaminationBootstrapServiceImpl implements ClinicalExaminationBootstrapService {
    private static final String DEFAULT_COMPLAINT = "Đến khám nha khoa theo phân công";

    private final VisitRepository visits;
    private final ClinicalExaminationRepository examinations;
    private final ExaminationDiagnosisRepository diagnoses;

    public ClinicalExaminationBootstrapServiceImpl(VisitRepository visits,
            ClinicalExaminationRepository examinations, ExaminationDiagnosisRepository diagnoses) {
        this.visits = visits;
        this.examinations = examinations;
        this.diagnoses = diagnoses;
    }

    @Override
    public ClinicalExamination ensureForAssignedVisit(Long visitId) {
        if (visitId == null) {
            throw new IllegalArgumentException("Mã lượt khám không hợp lệ.");
        }
        Visit visit = visits.lockById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lượt khám."));
        return examinations.findByVisit_VisitId(visitId).orElseGet(() -> create(visit));
    }

    @Override
    public void syncDoctorOnQueueTransfer(Long visitId, StaffProfile newDoctor) {
        if (newDoctor == null || newDoctor.getStaffId() == null) {
            throw new IllegalArgumentException("Bác sĩ điều phối không hợp lệ.");
        }
        Visit visit = visits.lockById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lượt khám."));
        if (visit.getStatus() != VisitStatus.WAITING || visit.getPrimaryDoctor() == null
                || !newDoctor.getStaffId().equals(visit.getPrimaryDoctor().getStaffId())) {
            throw new IllegalStateException("Chỉ đồng bộ hồ sơ khi lượt khám đang chờ và đã được phân công bác sĩ.");
        }
        ClinicalExamination examination = examinations.findByVisit_VisitId(visitId).orElseGet(() -> create(visit));
        examination.setDoctor(newDoctor);
        examinations.save(examination);
    }

    @Override
    public boolean cleanupOnRevertCheckIn(Long visitId) {
        if (visitId == null) {
            return false;
        }
        ClinicalExamination examination = examinations.findByVisit_VisitId(visitId).orElse(null);
        if (examination == null || !isEmpty(examination)) {
            return false;
        }
        examinations.delete(examination);
        return true;
    }

    private ClinicalExamination create(Visit visit) {
        if (visit.getPatientProfile() == null) {
            throw new IllegalStateException("Lượt khám chưa có hồ sơ bệnh nhân. Không thể tạo hồ sơ lâm sàng.");
        }
        if (visit.getPrimaryDoctor() == null) {
            throw new IllegalStateException("Lượt khám chưa được phân công bác sĩ. Không thể tạo hồ sơ lâm sàng.");
        }
        ClinicalExamination examination = new ClinicalExamination();
        examination.setVisit(visit);
        examination.setPatientProfile(visit.getPatientProfile());
        examination.setDoctor(visit.getPrimaryDoctor());
        examination.setChiefComplaint(textOrDefault(visit.getInitialSymptoms(), DEFAULT_COMPLAINT));
        return examinations.saveAndFlush(examination);
    }

    private boolean isEmpty(ClinicalExamination examination) {
        return !diagnoses.existsByClinicalExamination_ExamId(examination.getExamId())
                && isBlank(examination.getHistoryIllness())
                && isBlank(examination.getVitalSignsJson())
                && isBlank(examination.getExtraoralExam())
                && isBlank(examination.getIntraoralExam())
                && isBlank(examination.getPeriodontalStatus())
                && isBlank(examination.getOdontogramChartJson())
                && isBlank(examination.getSummaryAssessment());
    }

    private String textOrDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
