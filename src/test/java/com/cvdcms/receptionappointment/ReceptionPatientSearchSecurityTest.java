package com.cvdcms.receptionappointment;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.User;
import com.cvdcms.patient.PatientAccess;
import com.cvdcms.patient.PatientSearchPolicy;
import com.cvdcms.patient.PatientService;
import com.cvdcms.patient.ReceptionPatientRepository;
import com.cvdcms.reception.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.AppointmentConflictDetectionService;
import com.cvdcms.service.AppointmentLifecycleService;
import com.cvdcms.service.AppointmentPolicyService;
import com.cvdcms.service.AppointmentSlotService;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ReceptionPatientSearchSecurityTest {

    @Mock private PatientAccess patientAccess;
    @Mock private ReceptionAppointmentRepository appointmentRepo;
    @Mock private ReceptionAppointmentPatientRepository appointmentPatientRepo;
    @Mock private ReceptionPatientRepository receptionPatientRepo;
    @Mock private BranchRepository branches;
    @Mock private AppointmentBookingLockRepository lockedBranches;
    @Mock private ServiceRepository services;
    @Mock private StaffProfileRepository staffRepo;
    @Mock private ReceptionAppointmentRules rules;
    @Mock private AppointmentHistoryRepository histories;
    @Mock private CustomerCareTaskRepository careTasks;
    @Mock private AppointmentLifecycleService lifecycle;
    @Mock private AppointmentConflictDetectionService conflictDetection;
    @Mock private AppointmentSlotService slotService;
    @Mock private PatientService patientService;

    @Mock private ReceptionAccess receptionAccess;
    @Mock private ReceptionProfileRepository receptionProfiles;
    @Mock private ReceptionCheckInAppointmentRepository checkInAppointments;
    @Mock private ReceptionVisitRepository receptionVisits;
    @Mock private ReceptionCheckInKeyRepository checkInKeys;
    @Mock private ReceptionAuditRepository audits;
    @Mock private AppointmentPolicyService policyService;
    @Mock private ClinicalExaminationBootstrapService clinicalExaminations;

    @Mock private HttpSession session;

    private ReceptionAppointmentService appointmentService;
    private ReceptionService walkInService;

    private Branch branch1;
    private Branch branch2;
    private Branch inactiveBranch;
    private User staffUser;
    private User adminUser;
    private PatientProfile branch1Patient;
    private PatientProfile branch2Patient;

    @BeforeEach
    void setUp() {
        appointmentService = new ReceptionAppointmentService(
                patientAccess, appointmentRepo, appointmentPatientRepo, receptionPatientRepo,
                branches, lockedBranches, services, staffRepo, rules, histories, careTasks,
                lifecycle, conflictDetection, slotService, patientService,
                Validation.buildDefaultValidatorFactory().getValidator()
        );
        walkInService = new ReceptionService(
                receptionAccess, receptionProfiles, checkInAppointments, receptionVisits,
                checkInKeys, audits, branches, new ObjectMapper(), lockedBranches,
                lifecycle, histories, policyService, clinicalExaminations
        );

        branch1 = branch(1L, "CN1", true);
        branch2 = branch(2L, "CN2", true);
        inactiveBranch = branch(3L, "CN3", false);

        staffUser = new User();
        staffUser.setUserId(10L);
        staffUser.setUsername("letan_cn1");

        adminUser = new User();
        adminUser.setUserId(1L);
        adminUser.setUsername("admin");

        branch1Patient = patient(101L, "BN10001", "Nguyễn Thị Lan", "0901112222", "001199001122", branch1);
        branch1Patient.setHasAllergies(true);
        branch1Patient.setAllergyDetails("Penicillin");
        branch1Patient.setHasUnderlyingDisease(true);
        branch1Patient.setUnderlyingDiseaseDetails("Cao huyết áp");

        branch2Patient = patient(202L, "BN20002", "Trần Văn Bình", "0988777666", "079200001234", branch2);
        branch2Patient.setHasAllergies(true);
        branch2Patient.setAllergyDetails("Lidocain");
        branch2Patient.setHasUnderlyingDisease(true);
        branch2Patient.setUnderlyingDiseaseDetails("Tiểu đường type 2");
    }

    private Branch branch(Long id, String name, boolean active) {
        Branch b = new Branch();
        b.setBranchId(id);
        b.setBranchName(name);
        b.setIsActive(active);
        return b;
    }

    private PatientProfile patient(Long id, String code, String name, String phone, String idCard, Branch b) {
        PatientProfile p = new PatientProfile();
        p.setProfileId(id);
        p.setPatientCode(code);
        p.setFullName(name);
        p.setPhoneNumber(phone);
        p.setIdCardNumber(idCard);
        p.setDob(LocalDate.of(1992, 5, 10));
        p.setGender("Nam");
        p.setPreferredBranch(b);
        p.setStatus("ACTIVE");
        return p;
    }

    private void stubBranch1Staff() {
        lenient().when(patientAccess.require(session)).thenReturn(new PatientAccess.Scope(staffUser, 1L, false, Set.of(1L)));
        lenient().when(branches.findById(1L)).thenReturn(Optional.of(branch1));
        lenient().when(receptionAccess.require(session)).thenReturn(new ReceptionAccess.Scope(staffUser, false, List.of(1L)));
    }

    private void stubAdmin() {
        when(patientAccess.require(session)).thenReturn(new PatientAccess.Scope(adminUser, null, true, Set.of()));
        lenient().when(receptionAccess.require(session)).thenReturn(new ReceptionAccess.Scope(adminUser, true, List.of()));
        lenient().when(branches.findById(1L)).thenReturn(Optional.of(branch1));
    }

    // 1. Nhân viên không có chi nhánh hợp lệ bị từ chối 403
    @Test
    void staffWithoutValidCurrentBranchIsRejectedWith403EvenWhenBranchIdIsNull() {
        when(patientAccess.require(session)).thenReturn(new PatientAccess.Scope(staffUser, null, false, Set.of()));
        ResponseStatusException apptEx = assertThrows(ResponseStatusException.class,
                () -> appointmentService.searchPatients(session, null, ""));
        assertEquals(HttpStatus.FORBIDDEN, apptEx.getStatusCode());

        // Khi scope.branchId() trỏ tới chi nhánh đã ngừng hoạt động cũng trả 403
        when(patientAccess.require(session)).thenReturn(new PatientAccess.Scope(staffUser, 3L, false, Set.of(3L)));
        when(branches.findById(3L)).thenReturn(Optional.of(inactiveBranch));
        ResponseStatusException inactiveEx = assertThrows(ResponseStatusException.class,
                () -> appointmentService.searchPatients(session, null, "Lan"));
        assertEquals(HttpStatus.FORBIDDEN, inactiveEx.getStatusCode());

        verifyNoInteractions(appointmentPatientRepo);
    }

    // 2. Nhân viên Cơ sở 1 không thể truyền branchId của Cơ sở 2
    @Test
    void branch1StaffCannotQueryWithBranch2Id() {
        stubBranch1Staff();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> appointmentService.searchPatients(session, 2L, ""));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());

        ResponseStatusException exWithExact = assertThrows(ResponseStatusException.class,
                () -> appointmentService.searchPatients(session, 2L, "0988777666"));
        assertEquals(HttpStatus.FORBIDDEN, exWithExact.getStatusCode());

        verifyNoInteractions(appointmentPatientRepo);
    }

    // 3. Danh sách mặc định chỉ hiển thị bệnh nhân thuộc phạm vi được phép (cả khi branchId == null)
    @Test
    void emptyQueryOnlyReturnsBranchScopedPatientsAndNeverQueriesGlobalRecent() {
        stubBranch1Staff();
        when(appointmentPatientRepo.findRecentBookableByBranches(eq(List.of(1L)), any(Pageable.class)))
                .thenReturn(List.of(branch1Patient));

        List<ReceptionAppointmentService.PatientOption> withNullBranch =
                appointmentService.searchPatients(session, null, "   ");
        assertEquals(1, withNullBranch.size());
        assertEquals(101L, withNullBranch.get(0).id());
        assertFalse(withNullBranch.get(0).crossBranch());

        verify(appointmentPatientRepo, times(1)).findRecentBookableByBranches(eq(List.of(1L)), any(Pageable.class));
        verify(appointmentPatientRepo, never()).findRecentBookable(any());
    }

    // 4. Tìm theo tên không trả hồ sơ ngoài phạm vi
    @Test
    void nameSearchOnlySearchesWithinAllowedBranchScope() {
        stubBranch1Staff();
        when(appointmentPatientRepo.searchByBranches(eq(List.of(1L)), eq("Trần Văn Bình"), any(Pageable.class)))
                .thenReturn(List.of());

        List<ReceptionAppointmentService.PatientOption> res =
                appointmentService.searchPatients(session, 1L, "Trần Văn Bình");
        assertTrue(res.isEmpty());

        verify(appointmentPatientRepo).searchByBranches(eq(List.of(1L)), eq("Trần Văn Bình"), any(Pageable.class));
        verify(appointmentPatientRepo, never()).search(anyString());
        verify(appointmentPatientRepo, never()).findExactActiveByPhone(anyString());
        verify(appointmentPatientRepo, never()).findExactActiveByIdCard(anyString());
        verify(appointmentPatientRepo, never()).findExactActiveByPatientCode(anyString());
    }

    // 5. Tìm số điện thoại chính xác có thể nhận diện hồ sơ liên cơ sở (không đổi preferredBranch)
    @Test
    void exactPhoneLookupCanIdentifyCrossBranchProfileWithoutMutatingPreferredBranch() {
        stubBranch1Staff();
        when(appointmentPatientRepo.findExactActiveByPhone("0988777666")).thenReturn(List.of(branch2Patient));
        when(appointmentPatientRepo.isProfileAccessibleInBranches(202L, List.of(1L))).thenReturn(false);

        List<ReceptionAppointmentService.PatientOption> res =
                appointmentService.searchPatients(session, 1L, "0988777666");

        assertEquals(1, res.size());
        ReceptionAppointmentService.PatientOption opt = res.get(0);
        assertEquals(202L, opt.id());
        assertEquals("BN20002", opt.code());
        assertEquals("Trần Văn Bình", opt.name());
        assertEquals("0988777666", opt.phone());
        assertTrue(opt.crossBranch());
        assertEquals(2L, branch2Patient.getPreferredBranch().getBranchId());
        verify(appointmentRepo, never()).saveAndFlush(any());
        verify(appointmentPatientRepo, never()).search(anyString());
    }

    // 6. Tìm CCCD/CMND chính xác có thể nhận diện hồ sơ liên cơ sở
    @Test
    void exactIdCardLookupCanIdentifyCrossBranchProfile() {
        stubBranch1Staff();
        when(appointmentPatientRepo.findExactActiveByIdCard("079200001234")).thenReturn(List.of(branch2Patient));
        when(appointmentPatientRepo.isProfileAccessibleInBranches(202L, List.of(1L))).thenReturn(false);

        List<ReceptionAppointmentService.PatientOption> res =
                appointmentService.searchPatients(session, 1L, "079200001234");
        assertEquals(1, res.size());
        assertEquals(202L, res.get(0).id());
        assertTrue(res.get(0).crossBranch());

        // CMND 9 số cũ cũng được nhận diện là exact ID card
        PatientProfile oldIdCardPatient = patient(203L, "BN20003", "Phạm C", "0977666555", "123456789", branch2);
        when(appointmentPatientRepo.findExactActiveByIdCard("123456789")).thenReturn(List.of(oldIdCardPatient));
        when(appointmentPatientRepo.isProfileAccessibleInBranches(203L, List.of(1L))).thenReturn(false);

        List<ReceptionAppointmentService.PatientOption> resOld =
                appointmentService.searchPatients(session, 1L, "123456789");
        assertEquals(1, resOld.size());
        assertEquals(203L, resOld.get(0).id());
    }

    // 7. Tìm mã bệnh nhân chính xác có thể nhận diện hồ sơ liên cơ sở
    @Test
    void exactPatientCodeLookupCanIdentifyCrossBranchProfile() {
        stubBranch1Staff();
        when(appointmentPatientRepo.findExactActiveByPatientCode("bn20002")).thenReturn(List.of(branch2Patient));
        when(appointmentPatientRepo.isProfileAccessibleInBranches(202L, List.of(1L))).thenReturn(false);

        List<ReceptionAppointmentService.PatientOption> res =
                appointmentService.searchPatients(session, 1L, "bn20002");
        assertEquals(1, res.size());
        assertEquals(202L, res.get(0).id());
        assertEquals("BN20002", res.get(0).code());
        assertTrue(res.get(0).crossBranch());
    }

    // 8. Tìm một phần SĐT / CCCD / mã BN KHÔNG được dùng để tra cứu toàn hệ thống
    @Test
    void partialPhoneIdCardOrCodeNeverTriggersGlobalExactLookup() {
        stubBranch1Staff();
        List<String> partialQueries = List.of("0988", "098877", "777666", "988777666", "079200", "BN", "BN2", "00001");
        for (String partial : partialQueries) {
            lenient().when(appointmentPatientRepo.searchByBranches(eq(List.of(1L)), eq(partial), any(Pageable.class)))
                    .thenReturn(List.of());
            List<ReceptionAppointmentService.PatientOption> res =
                    appointmentService.searchPatients(session, 1L, partial);
            assertTrue(res.isEmpty(), "Partial query '" + partial + "' must not return branch2 patients");
        }

        // Ngoại trừ "988777666" có 9 chữ số có thể khớp định dạng CMND 9 số -> nhưng findExactActiveByIdCard CHỈ so sánh cột idCardNumber (=) chứ không so phoneNumber
        verify(appointmentPatientRepo, never()).findExactActiveByPhone(anyString());
        verify(appointmentPatientRepo, never()).findExactActiveByPatientCode(anyString());
        verify(appointmentPatientRepo, times(1)).findExactActiveByIdCard("988777666");
        verify(appointmentPatientRepo, never()).search(anyString());
    }

    // 9. Thông tin trả về khi tra cứu liên cơ sở KHÔNG chứa dữ liệu lâm sàng
    @Test
    void crossBranchLookupExcludesClinicalInformationInBothAppointmentAndWalkInApis() {
        stubBranch1Staff();
        when(appointmentPatientRepo.findExactActiveByPhone("0988777666")).thenReturn(List.of(branch2Patient));
        when(appointmentPatientRepo.isProfileAccessibleInBranches(202L, List.of(1L))).thenReturn(false);

        List<ReceptionAppointmentService.PatientOption> apptOptions =
                appointmentService.searchPatients(session, 1L, "0988777666");
        assertEquals(1, apptOptions.size());
        assertEquals(202L, apptOptions.get(0).id());
        assertTrue(apptOptions.get(0).crossBranch());

        when(receptionProfiles.findExactActiveByPhone("0988777666")).thenReturn(List.of(branch2Patient));
        when(receptionProfiles.isProfileAccessibleInBranches(202L, List.of(1L))).thenReturn(false);

        List<ReceptionService.PatientRow> walkInRows = walkInService.searchPatients(session, 1L, "0988777666");
        assertEquals(1, walkInRows.size());
        ReceptionService.PatientRow crossRow = walkInRows.get(0);
        assertEquals(202L, crossRow.id());
        assertEquals("BN20002", crossRow.code());
        assertEquals("Trần Văn Bình", crossRow.name());
        assertEquals("0988777666", crossRow.phone());
        assertTrue(crossRow.crossBranch());
        // Phải ẩn toàn bộ tiền sử dị ứng / bệnh nền / thông tin lâm sàng với hồ sơ liên cơ sở
        assertFalse(crossRow.allergies());
        assertNull(crossRow.allergyDetails());
        assertFalse(crossRow.underlyingDisease());
        assertNull(crossRow.underlyingDiseaseDetails());

        // Trong khi hồ sơ cùng chi nhánh vẫn hiển thị bình thường phục vụ cảnh báo an toàn tại quầy
        when(receptionProfiles.findExactActiveByPhone("0901112222")).thenReturn(List.of(branch1Patient));
        List<ReceptionService.PatientRow> localRows = walkInService.searchPatients(session, 1L, "0901112222");
        assertEquals(1, localRows.size());
        assertFalse(localRows.get(0).crossBranch());
        assertTrue(localRows.get(0).allergies());
        assertEquals("Penicillin", localRows.get(0).allergyDetails());
        verifyNoInteractions(receptionVisits);
    }

    // 10. ADMIN vẫn hoạt động đúng theo thiết kế hiện tại
    @Test
    void adminKeepsFullSystemSearchAccess() {
        stubAdmin();
        when(appointmentPatientRepo.findRecentBookable(any(Pageable.class)))
                .thenReturn(List.of(branch1Patient, branch2Patient));
        when(appointmentPatientRepo.search("Trần Văn Bình")).thenReturn(List.of(branch2Patient));

        List<ReceptionAppointmentService.PatientOption> emptyRes =
                appointmentService.searchPatients(session, null, "");
        assertEquals(2, emptyRes.size());

        List<ReceptionAppointmentService.PatientOption> nameRes =
                appointmentService.searchPatients(session, 1L, "Trần Văn Bình");
        assertEquals(1, nameRes.size());
        assertEquals(202L, nameRes.get(0).id());

        when(receptionProfiles.findRecentActive(any(Pageable.class)))
                .thenReturn(List.of(branch1Patient, branch2Patient));
        assertEquals(2, walkInService.searchPatients(session, null, "").size());
    }

    // 11. Walk-in và dashboard check-in không thể dùng để vượt chính sách phân quyền
    @Test
    void walkInSearchAndDashboardEnforceBranchScopeAndRejectUnauthorizedBranch() throws Exception {
        stubBranch1Staff();

        // Nhân viên CN1 truyền branchId=2L vào API walk-in search -> 403
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> walkInService.searchPatients(session, 2L, "0988777666"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        // Nhân viên không có chi nhánh hoạt động nào -> 403 dù branchId == null
        when(receptionAccess.require(session)).thenReturn(new ReceptionAccess.Scope(staffUser, false, List.of()));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> walkInService.searchPatients(session, null, "")).getStatusCode());

        // Walk-in search tên "Bình" khi branchId == null -> chỉ gọi searchByBranches theo CN1
        stubBranch1Staff();
        when(receptionProfiles.searchByBranches(eq(List.of(1L)), eq("Bình"), any(Pageable.class)))
                .thenReturn(List.of());
        assertTrue(walkInService.searchPatients(session, null, "Bình").isEmpty());
        verify(receptionProfiles).searchByBranches(eq(List.of(1L)), eq("Bình"), any(Pageable.class));
        verify(receptionProfiles, never()).search(anyString(), any());

        // Dashboard SSR /reception/check-in?query=Bình cũng phải lọc qua searchByBranches (không được gọi profiles.search toàn hệ thống)
        when(branches.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1, branch2));
        when(checkInAppointments.forDay(eq(1L), any(), anyList())).thenReturn(List.of());
        when(receptionVisits.forDay(eq(1L), any(), any())).thenReturn(List.of());
        ReceptionService.Dashboard dash = walkInService.dashboard(session, 1L, "Bình");
        assertTrue(dash.matches().isEmpty());
        verify(receptionProfiles, times(2)).searchByBranches(eq(List.of(1L)), eq("Bình"), any(Pageable.class));
        verify(receptionProfiles, never()).search(anyString(), any());

        // Kiểm tra endpoint controller /reception/check-in/patients/search truyền branchId & giữ HTTP 403
        lenient().when(receptionAccess.require(any())).thenReturn(new ReceptionAccess.Scope(staffUser, false, List.of(1L)));
        lenient().when(patientAccess.require(any())).thenReturn(new PatientAccess.Scope(staffUser, 1L, false, Set.of(1L)));
        MockMvc checkInMvc = MockMvcBuilders.standaloneSetup(new ReceptionCheckInController(walkInService)).build();
        checkInMvc.perform(get("/reception/check-in/patients/search").param("query", "0988777666").param("branchId", "2"))
                .andExpect(status().isForbidden());
        MockMvc apptMvc = MockMvcBuilders.standaloneSetup(new ReceptionAppointmentRestController(appointmentService))
                .setControllerAdvice(new ReceptionAppointmentApiExceptionHandler())
                .build();
        apptMvc.perform(get("/reception/appointments/api/patients").param("branchId", "2").param("query", "0988777666"))
                .andExpect(status().isForbidden());
    }

    // 12. Không tự chọn hồ sơ đầu tiên khi nhiều hồ sơ trùng định danh hoặc dữ liệu không nhất quán
    @Test
    void duplicateOrInconsistentExactIdentityDoesNotAutoSelectFirstProfile() throws Exception {
        stubBranch1Staff();

        // Trường hợp A: Có 2 hồ sơ trùng cùng SĐT ở DB
        PatientProfile dup1 = patient(301L, "BN30001", "Lê A", "0912345678", "001100110011", branch1);
        PatientProfile dup2 = patient(302L, "BN30002", "Lê B", "0912345678", "001100110022", branch2);
        when(appointmentPatientRepo.findExactActiveByPhone("0912345678")).thenReturn(List.of(dup1, dup2));
        when(receptionProfiles.findExactActiveByPhone("0912345678")).thenReturn(List.of(dup1, dup2));

        ResponseStatusException apptConflict = assertThrows(ResponseStatusException.class,
                () -> appointmentService.searchPatients(session, 1L, "0912345678"));
        assertEquals(HttpStatus.CONFLICT, apptConflict.getStatusCode());

        ResponseStatusException walkInConflict = assertThrows(ResponseStatusException.class,
                () -> walkInService.searchPatients(session, 1L, "0912345678"));
        assertEquals(HttpStatus.CONFLICT, walkInConflict.getStatusCode());

        // Trường hợp B: 1 hồ sơ nhưng dữ liệu định danh không nhất quán (thiếu preferredBranch / sai SĐT thực tế)
        PatientProfile inconsistent = patient(303L, "BN30003", "Lê C", "0999888777", null, null);
        when(appointmentPatientRepo.findExactActiveByPatientCode("BN30003")).thenReturn(List.of(inconsistent));
        ResponseStatusException inconsistentEx = assertThrows(ResponseStatusException.class,
                () -> appointmentService.searchPatients(session, 1L, "BN30003"));
        assertEquals(HttpStatus.CONFLICT, inconsistentEx.getStatusCode());

        // Trường hợp C: Dashboard SSR không ném vỡ trang và KHÔNG auto-populate matches[0] khi phát hiện conflict
        when(branches.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1, branch2));
        when(checkInAppointments.forDay(eq(1L), any(), anyList())).thenReturn(List.of());
        when(receptionVisits.forDay(eq(1L), any(), any())).thenReturn(List.of());
        ReceptionService.Dashboard dashConflict = walkInService.dashboard(session, 1L, "0912345678");
        assertTrue(dashConflict.matches().isEmpty());
        assertNotNull(dashConflict.walkInWarning());

        // Trường hợp D: FE check-in.html và reception-appointments.js không tự động chọn phần tử [0] bừa bãi
        String checkInHtml = Files.readString(Path.of("src/main/resources/templates/reception/check-in.html"));
        assertTrue(checkInHtml.contains("#lists.size(dashboard.matches) == 1 and !dashboard.matches[0].crossBranch"));
        String apptJs = Files.readString(Path.of("src/main/resources/static/js/reception-appointments.js"));
        assertFalse(apptJs.contains("matched?.find(p => p.code === prefillQuery) || matched?.[0]"));

        // Kiểm tra trực tiếp classifier không nhận diện nhầm partial input
        assertEquals(PatientSearchPolicy.ExactIdentifierType.NONE, PatientSearchPolicy.classifyExactIdentifier("0912"));
        assertEquals(PatientSearchPolicy.ExactIdentifierType.NONE, PatientSearchPolicy.classifyExactIdentifier("BN1"));
        assertEquals(PatientSearchPolicy.ExactIdentifierType.NONE, PatientSearchPolicy.classifyExactIdentifier("Nguyễn Văn A"));
        assertEquals(PatientSearchPolicy.ExactIdentifierType.PHONE, PatientSearchPolicy.classifyExactIdentifier("0912345678"));
        assertEquals(PatientSearchPolicy.ExactIdentifierType.ID_CARD, PatientSearchPolicy.classifyExactIdentifier("079200001234"));
        assertEquals(PatientSearchPolicy.ExactIdentifierType.PATIENT_CODE, PatientSearchPolicy.classifyExactIdentifier("BN20002"));
    }
}
