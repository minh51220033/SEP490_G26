package com.cvdcms.controller;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.QueueType;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorPatientRestControllerTest {

    @Mock PatientProfileRepository patientProfileRepository;
    @Mock VisitRepository visitRepository;
    @Mock BranchRepository branchRepository;
    @Mock StaffProfileRepository staffProfileRepository;
    @Mock StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @Mock UserRepository userRepository;
    @Mock CustomerCareTaskRepository customerCareTaskRepository;
    @Mock ClinicalExaminationBootstrapService examinations;
    @Mock ClinicalQueueTicketRepository clinicalQueueTicketRepository;
    @Mock HttpSession session;

    DoctorPatientRestController controller;
    Branch branch;
    User doctorUser;
    PatientProfile patient;

    @BeforeEach
    void setUp() {
        controller = new DoctorPatientRestController(
                patientProfileRepository, visitRepository, branchRepository, staffProfileRepository,
                staffBranchAssignmentRepository, userRepository, customerCareTaskRepository,
                examinations, clinicalQueueTicketRepository
        );

        branch = new Branch();
        branch.setBranchId(1L);
        branch.setIsActive(true);

        doctorUser = new User();
        doctorUser.setUserId(20L);
        doctorUser.setBranch(branch);

        patient = new PatientProfile();
        patient.setProfileId(50L);
        patient.setPreferredBranch(branch);
    }

    @Test
    void openVisitFailsWhenUserHasNoStaffProfile() {
        when(patientProfileRepository.findById(50L)).thenReturn(Optional.of(patient));
        when(session.getAttribute("loggedInUser")).thenReturn(doctorUser);
        when(userRepository.findById(20L)).thenReturn(Optional.of(doctorUser));
        when(userRepository.hasActiveAdminRole(20L)).thenReturn(false);
        when(staffProfileRepository.findByUser_UserId(20L)).thenReturn(Optional.empty()); // No StaffProfile

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.openOrCreateVisitForPatient(50L, 1L, session));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("Chỉ tài khoản bác sĩ đã liên kết hồ sơ nhân sự"));
        verify(visitRepository, never()).save(any());
    }

    @Test
    void openVisitFailsAtomicallyWhenTicketRangeExceeded() {
        when(patientProfileRepository.findById(50L)).thenReturn(Optional.of(patient));
        when(session.getAttribute("loggedInUser")).thenReturn(doctorUser);
        when(userRepository.findById(20L)).thenReturn(Optional.of(doctorUser));
        when(userRepository.hasActiveAdminRole(20L)).thenReturn(false);

        StaffProfile doctorProfile = new StaffProfile();
        doctorProfile.setStaffId(2L);
        when(staffProfileRepository.findByUser_UserId(20L)).thenReturn(Optional.of(doctorProfile));

        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch));
        when(branchRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(branch));
        when(visitRepository.findFirstByPatientProfile_ProfileIdAndStatusIn(eq(50L), anyList())).thenReturn(Optional.empty());

        Visit savedVisit = new Visit();
        savedVisit.setVisitId(100L);
        savedVisit.setBranch(branch);
        savedVisit.setStatus(VisitStatus.IN_EXAM);
        savedVisit.setPatientProfile(patient);
        when(visitRepository.save(any(Visit.class))).thenReturn(savedVisit);

        when(clinicalQueueTicketRepository.findByVisitVisitIdAndQueueType(eq(100L), eq(QueueType.EXAM))).thenReturn(null);
        when(clinicalQueueTicketRepository.maxTicketNumber(eq(1L), any(LocalDate.class), eq(QueueType.EXAM), anyBoolean()))
                .thenReturn(999); // Next ticket would be 1000 -> exceeds limit!

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.openOrCreateVisitForPatient(50L, 1L, session));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("Đã hết dải số hàng chờ"));
        verify(clinicalQueueTicketRepository, never()).save(any());
    }
}

