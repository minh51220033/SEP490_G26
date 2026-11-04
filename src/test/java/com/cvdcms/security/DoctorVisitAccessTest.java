package com.cvdcms.security;

import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.Visit;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.repository.VisitRepository;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DoctorVisitAccessTest {
    private final VisitRepository visits = mock(VisitRepository.class);
    private final StaffProfileRepository staff = mock(StaffProfileRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final com.cvdcms.repository.ExaminationDiagnosisRepository diagnoses = mock(com.cvdcms.repository.ExaminationDiagnosisRepository.class);
    private final com.cvdcms.repository.DoctorCoverGrantRepository grants = mock(com.cvdcms.repository.DoctorCoverGrantRepository.class);
    private final DoctorVisitAccess access = new DoctorVisitAccess(visits, staff, users, diagnoses,
            mock(com.cvdcms.repository.PrescriptionRepository.class),
            mock(com.cvdcms.repository.PerformedServiceRepository.class),
            mock(com.cvdcms.repository.ParaclinicalOrderItemRepository.class),
            grants, mock(com.cvdcms.repository.ClinicalPhotoRepository.class),
            mock(com.cvdcms.repository.WorkflowExecutionRepository.class),
            mock(com.cvdcms.repository.SystemAuditLogRepository.class));

    @Test
    void rejectsAnotherDoctorEvenAtSameBranch() {
        HttpSession session = mock(HttpSession.class);
        User user = new User(); user.setUserId(7L);
        StaffProfile assigned = new StaffProfile(); assigned.setStaffId(10L);
        StaffProfile caller = new StaffProfile(); caller.setStaffId(11L);
        Visit visit = new Visit(); visit.setPrimaryDoctor(assigned);
        when(session.getAttribute("loggedInUser")).thenReturn(user);
        when(visits.findById(5L)).thenReturn(Optional.of(visit));
        when(staff.findByUserUserId(7L)).thenReturn(Optional.of(caller));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.require(session, 5L)).getStatusCode().value());
    }

    @Test
    void permitsAssignedDoctor() {
        HttpSession session = mock(HttpSession.class);
        User user = new User(); user.setUserId(7L);
        StaffProfile assigned = new StaffProfile(); assigned.setStaffId(10L);
        Visit visit = new Visit(); visit.setPrimaryDoctor(assigned);
        when(session.getAttribute("loggedInUser")).thenReturn(user);
        when(visits.findById(5L)).thenReturn(Optional.of(visit));
        when(staff.findByUserUserId(7L)).thenReturn(Optional.of(assigned));
        assertDoesNotThrow(() -> access.require(session, 5L));
    }

    @Test
    void permitsDoctorWithActiveCoverGrant() {
        HttpSession session = mock(HttpSession.class);
        User user = new User(); user.setUserId(7L);
        StaffProfile assigned = new StaffProfile(); assigned.setStaffId(10L);
        StaffProfile caller = new StaffProfile(); caller.setStaffId(11L);
        Visit visit = new Visit(); visit.setPrimaryDoctor(assigned);
        when(session.getAttribute("loggedInUser")).thenReturn(user);
        when(visits.findById(5L)).thenReturn(Optional.of(visit));
        when(staff.findByUserUserId(7L)).thenReturn(Optional.of(caller));
        when(grants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                eq(5L), eq(11L), any())).thenReturn(true);
        assertDoesNotThrow(() -> access.require(session, 5L));
    }
}
