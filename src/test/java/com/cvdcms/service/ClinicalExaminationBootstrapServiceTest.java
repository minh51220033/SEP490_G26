package com.cvdcms.service;

import com.cvdcms.entity.ClinicalExamination;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.ClinicalExaminationRepository;
import com.cvdcms.repository.ExaminationDiagnosisRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.impl.ClinicalExaminationBootstrapServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClinicalExaminationBootstrapServiceTest {
    @Mock VisitRepository visits;
    @Mock ClinicalExaminationRepository examinations;
    @Mock ExaminationDiagnosisRepository diagnoses;
    private ClinicalExaminationBootstrapService service;
    private Visit visit;
    private StaffProfile doctor;

    @BeforeEach
    void setUp() {
        service = new ClinicalExaminationBootstrapServiceImpl(visits, examinations, diagnoses);
        PatientProfile patient = new PatientProfile(); patient.setProfileId(9L);
        doctor = new StaffProfile(); doctor.setStaffId(5L);
        visit = new Visit(); visit.setVisitId(7L); visit.setPatientProfile(patient);
        visit.setPrimaryDoctor(doctor); visit.setInitialSymptoms("Đau răng hàm dưới liên tục");
        visit.setStatus(VisitStatus.WAITING);
    }

    @Test
    void createsClinicalExaminationForAssignedVisit() {
        when(visits.lockById(7L)).thenReturn(Optional.of(visit));
        when(examinations.findByVisit_VisitId(7L)).thenReturn(Optional.empty());
        when(examinations.saveAndFlush(any(ClinicalExamination.class))).thenAnswer(call -> call.getArgument(0));

        ClinicalExamination result = service.ensureForAssignedVisit(7L);

        assertSame(visit, result.getVisit());
        assertSame(doctor, result.getDoctor());
        assertEquals("Đau răng hàm dưới liên tục", result.getChiefComplaint());
        verify(examinations).saveAndFlush(result);
    }

    @Test
    void returnsExistingExaminationWithoutInsertingAgain() {
        when(visits.lockById(7L)).thenReturn(Optional.of(visit));
        ClinicalExamination existing = new ClinicalExamination(); existing.setExamId(11L);
        when(examinations.findByVisit_VisitId(7L)).thenReturn(Optional.of(existing));

        assertSame(existing, service.ensureForAssignedVisit(7L));
        verify(examinations, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnassignedVisitInsteadOfChoosingAnArbitraryDoctor() {
        when(visits.lockById(7L)).thenReturn(Optional.of(visit));
        visit.setPrimaryDoctor(null);
        when(examinations.findByVisit_VisitId(7L)).thenReturn(Optional.empty());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.ensureForAssignedVisit(7L));
        assertTrue(error.getMessage().contains("chưa được phân công bác sĩ"));
        verify(examinations, never()).saveAndFlush(any());
    }

    @Test
    void transferSynchronizesRecordDoctorWhileWaiting() {
        when(visits.lockById(7L)).thenReturn(Optional.of(visit));
        StaffProfile newDoctor = new StaffProfile(); newDoctor.setStaffId(6L);
        visit.setPrimaryDoctor(newDoctor);
        ClinicalExamination existing = new ClinicalExamination(); existing.setDoctor(doctor);
        when(examinations.findByVisit_VisitId(7L)).thenReturn(Optional.of(existing));

        service.syncDoctorOnQueueTransfer(7L, newDoctor);

        assertSame(newDoctor, existing.getDoctor());
        verify(examinations).save(existing);
    }

    @Test
    void removesOnlyEmptyExaminationDuringRevertCheckIn() {
        ClinicalExamination existing = new ClinicalExamination(); existing.setExamId(11L);
        when(examinations.findByVisit_VisitId(7L)).thenReturn(Optional.of(existing));
        when(diagnoses.existsByClinicalExamination_ExamId(11L)).thenReturn(false);

        assertTrue(service.cleanupOnRevertCheckIn(7L));
        verify(examinations).delete(existing);
    }
}
