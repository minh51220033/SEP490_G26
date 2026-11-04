package com.cvdcms.patient;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.User;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PatientMedicalHistoryPersistenceTest {
    @Test
    void createPersistsMedicalWarningsForEmrSnapshot() {
        PatientAccess access = mock(PatientAccess.class);
        ReceptionPatientRepository profiles = mock(ReceptionPatientRepository.class);
        PatientSearchRepository search = mock(PatientSearchRepository.class);
        BranchRepository branches = mock(BranchRepository.class);
        PatientAuditRepository audits = mock(PatientAuditRepository.class);
        HttpSession session = mock(HttpSession.class);
        User user = new User(); user.setUserId(5L);
        Branch branch = new Branch(); branch.setBranchId(1L); branch.setIsActive(true); branch.setBranchName("Cơ sở 1");
        when(access.require(session)).thenReturn(new PatientAccess.Scope(user, 1L, false));
        when(branches.findById(1L)).thenReturn(Optional.of(branch));
        when(search.candidates(eq(1L), anyString(), any(), anyString(), anyString(), any())).thenReturn(List.of());
        when(profiles.saveAndFlush(any())).thenAnswer(invocation -> {
            ReceptionPatientRecord record = invocation.getArgument(0);
            record.setProfileId(10L);
            return record;
        });
        PatientService service = new PatientService(access, profiles, search,
                mock(PatientVisitReadRepository.class), branches, audits,
                mock(ClinicalExaminationRepository.class), mock(ExaminationDiagnosisRepository.class),
                mock(PerformedServiceRepository.class), mock(PrescriptionRepository.class),
                mock(AppointmentRepository.class), mock(PatientDocumentStorageService.class), new ObjectMapper());
        PatientProfileForm form = new PatientProfileForm("Nguyễn Văn A", LocalDate.of(1990, 1, 1),
                "Nam", "0901234567", "Hà Nội", 1L, "Penicillin", "Tăng huyết áp",
                null, null, null, null, null, false);

        PatientView created = service.create(session, form);

        assertEquals("Penicillin", created.allergyDetails());
        assertEquals("Tăng huyết áp", created.underlyingDiseaseDetails());
        verify(audits).save(argThat(log -> log.getNewValuesJson().contains("Penicillin")
                && log.getNewValuesJson().contains("Tăng huyết áp")));
    }

    @Test
    void findCandidatesFlagsDuplicateIdCardNumber() {
        PatientAccess access = mock(PatientAccess.class);
        ReceptionPatientRepository profiles = mock(ReceptionPatientRepository.class);
        PatientSearchRepository search = mock(PatientSearchRepository.class);
        BranchRepository branches = mock(BranchRepository.class);
        PatientAuditRepository audits = mock(PatientAuditRepository.class);
        HttpSession session = mock(HttpSession.class);
        User user = new User(); user.setUserId(5L);
        Branch branch = new Branch(); branch.setBranchId(1L); branch.setBranchName("Cơ sở 1");
        ReceptionPatientRecord existing = new ReceptionPatientRecord();
        existing.setProfileId(99L);
        existing.setPatientCode("BN0099");
        existing.setFullName("Trần Thị B");
        existing.setIdCardNumber("001200003333");
        existing.setPreferredBranch(branch);

        when(access.require(session)).thenReturn(new PatientAccess.Scope(user, 1L, false));
        when(search.candidates(eq(1L), anyString(), any(), anyString(), eq("001200003333"), any()))
                .thenReturn(List.of(existing));

        PatientService service = new PatientService(access, profiles, search,
                mock(PatientVisitReadRepository.class), branches, audits,
                mock(ClinicalExaminationRepository.class), mock(ExaminationDiagnosisRepository.class),
                mock(PerformedServiceRepository.class), mock(PrescriptionRepository.class),
                mock(AppointmentRepository.class), mock(PatientDocumentStorageService.class), new ObjectMapper());

        List<DuplicateCandidate> result = service.candidates(session, "Lê Văn C", LocalDate.of(1995, 5, 5),
                "0988777666", "001200003333", null);

        assertFalse(result.isEmpty());
        assertTrue(result.get(0).matchedFields().contains("Số CCCD"));
    }
}
