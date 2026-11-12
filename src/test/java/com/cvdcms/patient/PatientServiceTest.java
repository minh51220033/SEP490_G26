package com.cvdcms.patient;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock PatientAccess access;
    @Mock ReceptionPatientRepository profiles;
    @Mock PatientSearchRepository search;
    @Mock PatientVisitReadRepository visits;
    @Mock BranchRepository branches;
    @Mock PatientAuditRepository auditRepository;
    @Mock HttpSession session;

    PatientService service;
    Branch branch;
    User actor;

    @BeforeEach
    void setUp() {
        service = new PatientService(
                access, profiles, search, visits, branches, auditRepository,
                null, null, null, null, null, null, new ObjectMapper()
        );
        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở Quận 1");
        branch.setIsActive(true);

        actor = new User();
        actor.setUserId(99L);
    }

    private void mockPermission() {
        when(access.require(session)).thenReturn(new PatientAccess.Scope(actor, null, false));
        when(branches.findById(1L)).thenReturn(Optional.of(branch));
    }

    @Test
    void createPatientRejectsDuplicatePhoneNumberEvenWithDifferentNameAndConfirmDuplicateTrue() {
        mockPermission();

        ReceptionPatientRecord existing = new ReceptionPatientRecord();
        existing.setProfileId(10L);
        existing.setPatientCode("BN0010");
        existing.setFullName("Nguyễn Văn A");
        existing.setPhoneNumber("0912345678");
        existing.setPreferredBranch(branch);

        when(search.candidates(isNull(), eq("0912345678"), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(existing));

        PatientProfileForm form = new PatientProfileForm(
                "Trần Thị B", // Tên khác
                LocalDate.of(1995, 5, 20),
                "Nữ",
                "0912345678", // Trùng SĐT
                "Hà Nội",
                1L,
                null, null, null, null, null, null, null,
                true // Dù đã tích confirmDuplicate=true
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.create(session, form));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("Số điện thoại 0912345678 đã được sử dụng"));
        verify(profiles, never()).saveAndFlush(any());
    }

    @Test
    void createPatientRejectsDuplicateIdCardEvenWithConfirmDuplicateTrue() {
        mockPermission();

        ReceptionPatientRecord existing = new ReceptionPatientRecord();
        existing.setProfileId(11L);
        existing.setPatientCode("BN0011");
        existing.setFullName("Lê Văn C");
        existing.setIdCardNumber("001234567890");
        existing.setPreferredBranch(branch);

        when(search.candidates(isNull(), any(), any(), any(), eq("001234567890"), any(Pageable.class)))
                .thenReturn(List.of(existing));

        PatientProfileForm form = new PatientProfileForm(
                "Phạm Văn D",
                LocalDate.of(1990, 1, 1),
                "Nam",
                "0987654321",
                "TP.HCM",
                1L,
                null, null, null, null, null,
                "001234567890", // Trùng CCCD
                null,
                true // confirmDuplicate = true
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.create(session, form));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("Số CCCD 001234567890 đã được sử dụng"));
        verify(profiles, never()).saveAndFlush(any());
    }
}

