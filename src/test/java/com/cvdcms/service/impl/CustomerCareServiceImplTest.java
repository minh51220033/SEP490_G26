package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.CustomerCareNotificationService;
import com.cvdcms.service.SystemAuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerCareServiceImplTest {

    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Mock private CustomerCareTaskRepository taskRepository;
    @Mock private CustomerCareInteractionRepository interactionRepository;
    @Mock private PatientProfileRepository patientRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private StaffProfileRepository staffRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private UserRepository userRepository;
    @Mock private ClinicalExaminationRepository clinicalExaminationRepository;
    @Mock private ExaminationDiagnosisRepository examinationDiagnosisRepository;
    @Mock private CustomerCareNotificationService notificationService;
    @Mock private SystemAuditLogService systemAuditLogService;

    private CustomerCareServiceImpl service;

    private Branch branch1;
    private Branch branch2;
    private User adminUser;
    private User receptionistUser;
    private User doctorUser;
    private StaffProfile receptionistStaff;
    private StaffProfile doctorStaff;
    private PatientProfile patient;

    @BeforeEach
    void setUp() {
        service = new CustomerCareServiceImpl(
                taskRepository,
                interactionRepository,
                patientRepository,
                branchRepository,
                staffRepository,
                visitRepository,
                appointmentRepository,
                prescriptionRepository,
                userRepository,
                clinicalExaminationRepository,
                examinationDiagnosisRepository
        );
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        ReflectionTestUtils.setField(service, "systemAuditLogService", systemAuditLogService);

        branch1 = new Branch();
        branch1.setBranchId(1L);
        branch1.setBranchCode("CS1");
        branch1.setBranchName("Cơ sở 1");

        branch2 = new Branch();
        branch2.setBranchId(2L);
        branch2.setBranchCode("CS2");
        branch2.setBranchName("Cơ sở 2");

        adminUser = new User();
        adminUser.setUserId(1L);
        adminUser.setUsername("admin");
        adminUser.setIsActive(true);
        adminUser.setStatus("ACTIVE");

        receptionistUser = new User();
        receptionistUser.setUserId(10L);
        receptionistUser.setUsername("letan01");
        receptionistUser.setIsActive(true);
        receptionistUser.setStatus("ACTIVE");
        receptionistUser.setBranch(branch1);

        doctorUser = new User();
        doctorUser.setUserId(20L);
        doctorUser.setUsername("bs01");
        doctorUser.setIsActive(true);
        doctorUser.setStatus("ACTIVE");
        doctorUser.setBranch(branch1);

        receptionistStaff = new StaffProfile();
        receptionistStaff.setStaffId(100L);
        receptionistStaff.setStaffCode("LT01");
        receptionistStaff.setFullName("Nguyễn Lễ Tân");
        receptionistStaff.setPositionTitle("Lễ tân CSKH");
        receptionistStaff.setStatus(StaffStatus.ACTIVE);
        receptionistStaff.setUser(receptionistUser);

        doctorStaff = new StaffProfile();
        doctorStaff.setStaffId(200L);
        doctorStaff.setStaffCode("BS01");
        doctorStaff.setFullName("BS. Trần Minh");
        doctorStaff.setPositionTitle("Bác sĩ RHM");
        doctorStaff.setStatus(StaffStatus.ACTIVE);
        doctorStaff.setUser(doctorUser);

        patient = new PatientProfile();
        patient.setProfileId(1000L);
        patient.setPatientCode("BN001");
        patient.setFullName("Phạm Thị Hương");
        patient.setPhoneNumber("0901234567");
        patient.setPreferredBranch(branch1);

        lenient().when(taskRepository.save(any(CustomerCareTask.class))).thenAnswer(inv -> {
            CustomerCareTask t = inv.getArgument(0);
            if (t.getTaskId() == null) t.setTaskId(999L);
            return t;
        });
        lenient().when(interactionRepository.save(any(CustomerCareInteraction.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(taskRepository.existsByTaskCode(anyString())).thenReturn(false);
    }

    private void mockAdmin(Long userId) {
        when(userRepository.findById(userId)).thenReturn(Optional.of(adminUser));
        when(userRepository.hasActiveAdminRole(userId)).thenReturn(true);
    }

    private void mockReceptionist(Long userId, Long allowedBranchId) {
        when(userRepository.findById(userId)).thenReturn(Optional.of(receptionistUser));
        lenient().when(userRepository.hasActiveAdminRole(userId)).thenReturn(false);
        lenient().when(userRepository.hasActivePermission(userId, "CUSTOMER_CARE_MANAGE")).thenReturn(true);
        lenient().when(taskRepository.findActiveRoleNamesByUserId(userId)).thenReturn(List.of("LE_TAN"));
        if (allowedBranchId != null) {
            lenient().when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1, branch2));
            lenient().when(taskRepository.findExplicitBranchIdsByUserId(userId)).thenReturn(List.of(allowedBranchId));
        }
    }

    private void mockDoctor(Long userId, Long allowedBranchId) {
        when(userRepository.findById(userId)).thenReturn(Optional.of(doctorUser));
        lenient().when(userRepository.hasActiveAdminRole(userId)).thenReturn(false);
        lenient().when(userRepository.hasActivePermission(userId, "CUSTOMER_CARE_MANAGE")).thenReturn(false);
        lenient().when(taskRepository.findActiveRoleNamesByUserId(userId)).thenReturn(List.of("BAC_SI"));
        if (allowedBranchId != null) {
            lenient().when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1, branch2));
            lenient().when(taskRepository.findExplicitBranchIdsByUserId(userId)).thenReturn(List.of(allowedBranchId));
        }
    }

    private CustomerCareTask sampleTask(Long id, CustomerCareTaskStatus status, LocalDate dueDate) {
        CustomerCareTask t = new CustomerCareTask();
        t.setTaskId(id);
        t.setTaskCode("CSKH-" + id);
        t.setBranch(branch1);
        t.setPatientProfile(patient);
        t.setTaskType(CustomerCareTaskType.POST_TREATMENT_FOLLOWUP);
        t.setStatus(status);
        t.setDueDate(dueDate);
        t.setAssignedStaff(receptionistStaff);
        return t;
    }

    // ==================== 1. requireCareRole ====================

    @Test
    void requireCareRole_ThrowsUnauthorized_WhenUserIdInvalidOrNotFound() {
        ResponseStatusException r1 = assertThrows(ResponseStatusException.class, () -> service.requireCareRole(null));
        assertEquals(HttpStatus.UNAUTHORIZED, r1.getStatusCode());

        ResponseStatusException r2 = assertThrows(ResponseStatusException.class, () -> service.requireCareRole(0L));
        assertEquals(HttpStatus.UNAUTHORIZED, r2.getStatusCode());

        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        ResponseStatusException r3 = assertThrows(ResponseStatusException.class, () -> service.requireCareRole(99L));
        assertEquals(HttpStatus.UNAUTHORIZED, r3.getStatusCode());
    }

    @Test
    void requireCareRole_ThrowsForbidden_WhenUserInactiveOrStatusLocked() {
        User inactive = new User();
        inactive.setUserId(2L);
        inactive.setIsActive(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(inactive));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.requireCareRole(2L)).getStatusCode());

        User locked = new User();
        locked.setUserId(3L);
        locked.setIsActive(true);
        locked.setStatus("LOCKED");
        when(userRepository.findById(3L)).thenReturn(Optional.of(locked));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.requireCareRole(3L)).getStatusCode());
    }

    @Test
    void requireCareRole_ResolvesAdminAndPermissionAndStaffProfileFallbacks() {
        mockAdmin(1L);
        assertEquals("ADMIN", service.requireCareRole(1L));

        // Permission CUSTOMER_CARE_MANAGE + doctor role -> BAC_SI
        when(userRepository.findById(20L)).thenReturn(Optional.of(doctorUser));
        when(userRepository.hasActiveAdminRole(20L)).thenReturn(false);
        when(userRepository.hasActivePermission(20L, "CUSTOMER_CARE_MANAGE")).thenReturn(true);
        when(taskRepository.findActiveRoleNamesByUserId(20L)).thenReturn(List.of("DOCTOR"));
        assertEquals("BAC_SI", service.requireCareRole(20L));

        // Permission CUSTOMER_CARE_MANAGE without doctor role -> LE_TAN
        when(taskRepository.findActiveRoleNamesByUserId(20L)).thenReturn(List.of("OTHER"));
        assertEquals("LE_TAN", service.requireCareRole(20L));

        // Non-care role explicitly rejected -> 403
        when(userRepository.findById(10L)).thenReturn(Optional.of(receptionistUser));
        when(userRepository.hasActiveAdminRole(10L)).thenReturn(false);
        when(userRepository.hasActivePermission(10L, "CUSTOMER_CARE_MANAGE")).thenReturn(false);
        when(taskRepository.findActiveRoleNamesByUserId(10L)).thenReturn(List.of("WAREHOUSE"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.requireCareRole(10L)).getStatusCode());

        // Role names empty -> fallback to active StaffProfile position title
        when(taskRepository.findActiveRoleNamesByUserId(10L)).thenReturn(List.of());
        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(receptionistStaff));
        assertEquals("LE_TAN", service.requireCareRole(10L));
    }

    // ==================== 2. getAccessibleBranches & getLookups ====================

    @Test
    @SuppressWarnings("unchecked")
    void getAccessibleBranches_ReturnsAllForAdminAndFilteredInferredBranchesForStaff() {
        mockAdmin(1L);
        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1, branch2));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.empty());

        Map<String, Object> adminRes = service.getAccessibleBranches(1L);
        assertEquals("ADMIN", adminRes.get("careRole"));
        assertTrue((Boolean) adminRes.get("isAdmin"));
        assertEquals(2, ((List<BranchOptionDTO>) adminRes.get("branches")).size());

        // Receptionist with inferred branch via StaffProfile when explicit branch list is empty
        mockReceptionist(10L, null);
        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(branch1, branch2));
        when(taskRepository.findExplicitBranchIdsByUserId(10L)).thenReturn(List.of());
        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(receptionistStaff));
        when(taskRepository.findInferredBranchIdsByStaffId(100L)).thenReturn(List.of(2L));

        Map<String, Object> staffRes = service.getAccessibleBranches(10L);
        List<BranchOptionDTO> opts = (List<BranchOptionDTO>) staffRes.get("branches");
        assertEquals(1, opts.size());
        assertEquals(2L, opts.get(0).getBranchId());
        assertFalse((Boolean) staffRes.get("branchSelectionAllowed"));
        assertEquals(100L, staffRes.get("currentStaffId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLookups_FiltersByBranchEnrichesVisitsAndBlocksCrossBranchAccess() {
        mockReceptionist(10L, 1L);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.getLookups(2L, 10L)).getStatusCode());

        Visit visit = new Visit();
        visit.setVisitId(70L);
        visit.setVisitCode("LK-70");
        visit.setPatientProfile(patient);
        visit.setBranch(branch1);
        visit.setStatus(VisitStatus.COMPLETED);
        visit.setPrimaryDoctor(doctorStaff);

        Prescription pr = new Prescription();
        pr.setPrescriptionId(80L);
        pr.setPrescriptionCode("DT-80");
        pr.setPatientProfile(patient);
        pr.setVisit(visit);
        pr.setDiagnosisText("Viêm tủy");

        PatientProfile otherPatient = new PatientProfile();
        otherPatient.setProfileId(2000L);
        otherPatient.setPreferredBranch(branch2);

        when(staffRepository.findByStatus(StaffStatus.ACTIVE)).thenReturn(List.of(receptionistStaff, doctorStaff));
        when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(100L, 200L));
        when(appointmentRepository.findByBranchWithDetails(1L)).thenReturn(List.of());
        when(taskRepository.findValidVisitsByBranchForLookup(1L)).thenReturn(List.of(visit));
        when(taskRepository.findValidPrescriptionsByBranchForCare(1L)).thenReturn(List.of(pr));
        when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of());
        when(patientRepository.findAll()).thenReturn(List.of(patient, otherPatient));

        Map<String, Object> lookups = service.getLookups(1L, 10L);
        List<Map<String, Object>> doctors = (List<Map<String, Object>>) lookups.get("doctorList");
        assertEquals(1, doctors.size());
        assertEquals(200L, doctors.get(0).get("staffId"));

        List<Map<String, Object>> patients = (List<Map<String, Object>>) lookups.get("patients");
        assertEquals(1, patients.size());
        assertEquals(1000L, patients.get(0).get("profileId"));

        List<Map<String, Object>> visits = (List<Map<String, Object>>) lookups.get("visits");
        assertEquals("DT-80", visits.get(0).get("prescriptionCode"));
        assertNotNull(visits.get(0).get("examinationDetailUrl"));
    }

    // ==================== 3. getSummary, searchTasks, getCalendarTasks ====================

    @Test
    @SuppressWarnings("unchecked")
    void getSummary_CalculatesMetricsAndGroupCountsAccurately() {
        mockReceptionist(10L, 1L);
        LocalDate today = LocalDate.now(CLINIC_ZONE);

        CustomerCareTask tOverdue = sampleTask(1L, CustomerCareTaskStatus.PENDING, today.minusDays(2));
        CustomerCareTask tRetry = sampleTask(2L, CustomerCareTaskStatus.IN_PROGRESS, today);
        CustomerCareTask tEscalatedWaiting = sampleTask(3L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR, today.plusDays(1));
        CustomerCareTask tEscalatedDone = sampleTask(4L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR, today.plusDays(2));
        tEscalatedDone.setDoctorResponse("Đã kê đơn giảm đau");
        CustomerCareTask tCompleted = sampleTask(5L, CustomerCareTaskStatus.COMPLETED, today);
        tCompleted.setNotes("[TRA_GOP] Đã nhắc");

        CustomerCareInteraction retryInt = new CustomerCareInteraction();
        retryInt.setTask(tRetry);
        retryInt.setNextRetryAt(LocalDateTime.now().plusHours(2));

        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(receptionistStaff));
        when(taskRepository.findAllByBranchWithDetails(1L))
                .thenReturn(List.of(tOverdue, tRetry, tEscalatedWaiting, tEscalatedDone, tCompleted));
        when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of(retryInt));

        Map<String, Object> summary = service.getSummary(1L, 10L);
        assertEquals(5L, summary.get("totalTasks"));
        assertEquals(1L, summary.get("pendingCount"));
        assertEquals(1L, summary.get("inProgressCount"));
        assertEquals(2L, summary.get("escalatedTasks"));
        assertEquals(1L, summary.get("waitingDoctorResponseCount"));
        assertEquals(1L, summary.get("doctorRespondedCount"));
        assertEquals(1L, summary.get("dueTodayTasks"));
        assertEquals(1L, summary.get("overdueTasks"));
        assertEquals(2L, summary.get("upcomingTasks"));
        assertEquals(1L, summary.get("retryPendingCount"));
        assertEquals(4L, summary.get("myTasksCount"));
        assertEquals(1L, summary.get("completedTasks"));
        assertEquals(20, summary.get("completionRate"));

        Map<String, Long> groups = (Map<String, Long>) summary.get("groupCounts");
        assertEquals(2L, groups.get("ACTIONABLE"));
        assertEquals(2L, groups.get("TODAY"));
        assertEquals(1L, groups.get("INSTALLMENT_REMINDER"));
    }

    @Test
    void searchTasks_AppliesFiltersSortingPaginationAndValidatesDates() {
        mockAdmin(1L);
        LocalDate today = LocalDate.now(CLINIC_ZONE);

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () ->
                        service.searchTasks(1L, "ALL", null, null, null,
                                today.plusDays(1), today, null, PageRequest.of(0, 10), 1L))
                        .getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () ->
                        service.searchTasks(-1L, "ALL", null, null, null,
                                null, null, null, PageRequest.of(0, 10), 1L))
                        .getStatusCode());

        CustomerCareTask overdue = sampleTask(1L, CustomerCareTaskStatus.PENDING, today.minusDays(1));
        overdue.setNotes("[PHAN_ANH] Đau sau nhổ răng");
        CustomerCareTask upcoming = sampleTask(2L, CustomerCareTaskStatus.PENDING, today.plusDays(3));
        upcoming.setAssignedStaff(null);
        CustomerCareTask completed = sampleTask(3L, CustomerCareTaskStatus.COMPLETED, today.minusDays(2));
        CustomerCareTask noDueDate = sampleTask(4L, CustomerCareTaskStatus.PENDING, null);

        when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of(completed, upcoming, overdue, noDueDate));
        when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        // Default priority sorts overdue before upcoming, and ACTIONABLE excludes completed
        Page<CustomerCareTaskDTO> actionable = service.searchTasks(
                1L, "ACTIONABLE", null, null, null, null, null, null,
                PageRequest.of(0, 10), 1L);
        assertEquals(3, actionable.getTotalElements());
        assertEquals(1L, actionable.getContent().get(0).getTaskId());
        assertEquals("FEEDBACK_COMPLAINT", actionable.getContent().get(0).getCareGroup());

        // Due filter + keyword + null dueDate excluded when fromDate/toDate provided
        Page<CustomerCareTaskDTO> filtered = service.searchTasks(
                1L, "ALL", "PENDING", 100L, null, today.minusDays(5), today, "phạm thị hương",
                "OVERDUE", "FEEDBACK_COMPLAINT", "WAITING", false, PageRequest.of(0, 10), 1L);
        assertEquals(1, filtered.getTotalElements());

        Page<CustomerCareTaskDTO> boundedRange = service.searchTasks(
                1L, "ALL", null, null, null, today.minusDays(5), today, null,
                null, null, null, false, PageRequest.of(0, 10), 1L);
        assertEquals(2, boundedRange.getTotalElements());
        assertTrue(boundedRange.getContent().stream().noneMatch(t -> Long.valueOf(4L).equals(t.getTaskId())));

        Page<CustomerCareTaskDTO> outOfRange = service.searchTasks(
                1L, "ALL", null, null, null, null, null, null, PageRequest.of(5, 10), 1L);
        assertTrue(outOfRange.getContent().isEmpty());
        assertEquals(4, outOfRange.getTotalElements());
    }

    @Test
    void getCalendarTasks_ValidatesDateRangeAndReturnsFilteredList() {
        mockAdmin(1L);
        LocalDate today = LocalDate.now(CLINIC_ZONE);

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () ->
                        service.getCalendarTasks(1L, today.plusDays(2), today, null, null, null, null, false, 1L))
                        .getStatusCode());

        CustomerCareTask inRange = sampleTask(1L, CustomerCareTaskStatus.PENDING, today);
        CustomerCareTask outRange = sampleTask(2L, CustomerCareTaskStatus.PENDING, today.plusDays(10));
        when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of(inRange, outRange));
        when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        List<CustomerCareTaskDTO> res = service.getCalendarTasks(
                1L, today.minusDays(1), today.plusDays(1), null, null, null, null, false, 1L);
        assertEquals(1, res.size());
        assertEquals(1L, res.get(0).getTaskId());
    }

    // ==================== 4. getTaskDetail, createTask, updateTask ====================

    @Test
    void getTaskDetail_ValidatesIdNotFoundCrossBranchAndStripsGroupTag() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.getTaskDetail(0L, 1L)).getStatusCode());

        when(taskRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.getTaskDetail(99L, 1L)).getStatusCode());

        mockReceptionist(10L, 1L);
        CustomerCareTask otherBranchTask = sampleTask(5L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        otherBranchTask.setBranch(branch2);
        when(taskRepository.findByIdWithDetails(5L)).thenReturn(Optional.of(otherBranchTask));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.getTaskDetail(5L, 10L)).getStatusCode());

        CustomerCareTask okTask = sampleTask(6L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        okTask.setNotes("[TRA_GOP] Nhắc đóng kỳ 2");
        when(taskRepository.findByIdWithDetails(6L)).thenReturn(Optional.of(okTask));
        when(interactionRepository.findByTaskIdWithDetails(6L)).thenReturn(List.of());

        CustomerCareTaskDTO detail = service.getTaskDetail(6L, 10L);
        assertEquals("INSTALLMENT_REMINDER", detail.getCareGroup());
        assertEquals("Nhắc đóng kỳ 2", detail.getNotes());
    }

    @Test
    void createTask_ValidatesInputsDuplicatesAndCreatesNewTaskWithNotifications() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.createTask(null, 1L)).getStatusCode());

        mockAdmin(1L);
        CustomerCareCreateDTO missingProfile = new CustomerCareCreateDTO();
        missingProfile.setBranchId(1L);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.createTask(missingProfile, 1L)).getStatusCode());

        CustomerCareCreateDTO missingDate = new CustomerCareCreateDTO();
        missingDate.setBranchId(1L);
        missingDate.setProfileId(1000L);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.createTask(missingDate, 1L)).getStatusCode());

        CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
        dto.setBranchId(1L);
        dto.setProfileId(1000L);
        dto.setDueDate(LocalDate.of(2026, 6, 15));
        dto.setCareGroup("UNKNOWN_GROUP");

        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());

        // Active duplicate on visit -> blocked
        CustomerCareTask existingVisitTask = sampleTask(50L, CustomerCareTaskStatus.PENDING, LocalDate.of(2026, 6, 15));
        Visit v = new Visit();
        v.setVisitId(300L);
        existingVisitTask.setVisit(v);
        when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of(existingVisitTask));

        dto.setCareGroup("POST_TREATMENT_FOLLOWUP");
        dto.setVisitId(300L);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());

        // Completed task on same visit should NOT block creation
        existingVisitTask.setStatus(CustomerCareTaskStatus.COMPLETED);
        dto.setCareGroup("FEEDBACK_COMPLAINT");
        dto.setNotes("Khách phàn nàn sưng nhẹ");
        when(visitRepository.findWithDetailsById(300L)).thenReturn(Optional.of(v));
        v.setPatientProfile(patient);

        CustomerCareTaskDTO created = service.createTask(dto, 1L);
        assertEquals("FEEDBACK_COMPLAINT", created.getCareGroup());
        assertEquals("Khách phàn nàn sưng nhẹ", created.getNotes());
        verify(taskRepository).save(any(CustomerCareTask.class));
        verify(notificationService).notifyTaskCreated(any(CustomerCareTask.class), eq(1L), anyString(), eq(false));
    }

    @Test
    void updateTask_BlocksClosedTask_AndUpdatesFieldsAndClearsStaffWhenZero() {
        mockAdmin(1L);
        CustomerCareTask closed = sampleTask(1L, CustomerCareTaskStatus.CANCELLED, LocalDate.now());
        when(taskRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(closed));

        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, () -> service.updateTask(1L, new CustomerCareCreateDTO(), 1L))
                        .getStatusCode());

        CustomerCareTask open = sampleTask(2L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        when(taskRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(open));
        when(interactionRepository.findByTaskIdWithDetails(2L)).thenReturn(List.of());

        CustomerCareCreateDTO updateDto = new CustomerCareCreateDTO();
        updateDto.setCareGroup("NO_SERVICE_FOLLOWUP");
        updateDto.setDueDate(LocalDate.of(2026, 8, 1));
        updateDto.setNotes("Khách chưa chốt làm răng sứ");
        updateDto.setAssignedStaffId(0L);

        CustomerCareTaskDTO updated = service.updateTask(2L, updateDto, 1L);
        assertNull(open.getAssignedStaff());
        assertEquals("NO_SERVICE_FOLLOWUP", updated.getCareGroup());
        assertEquals(LocalDate.of(2026, 8, 1), updated.getDueDate());
        verify(notificationService).notifyTaskUpdated(open, 1L, "admin");
    }

    // ==================== 5. autoGenerateTasks ====================

    @Test
    void autoGenerateTasks_RequiresSpecificBranchForAdmin_AndCreatesAllFourMissingTaskTypes() {
        mockAdmin(1L);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.autoGenerateTasks(null, 1L)).getStatusCode());

        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(receptionistStaff));

        Visit visit = new Visit();
        visit.setVisitId(1L);
        visit.setVisitCode("LK-1");
        visit.setPatientProfile(patient);
        visit.setBranch(branch1);

        Prescription pr = new Prescription();
        pr.setPrescriptionId(2L);
        pr.setPrescriptionCode("DT-2");
        pr.setPatientProfile(patient);
        pr.setBranch(branch1);
        pr.setDiagnosisText("Viêm nha chu");

        Appointment upcomingAppt = new Appointment();
        upcomingAppt.setAppointmentId(3L);
        upcomingAppt.setAppointmentCode("LH-3");
        upcomingAppt.setPatientProfile(patient);
        upcomingAppt.setBranch(branch1);
        upcomingAppt.setStatus(AppointmentStatus.CONFIRMED);
        upcomingAppt.setAppointmentDate(LocalDate.now().plusDays(2));

        Appointment noShowAppt = new Appointment();
        noShowAppt.setAppointmentId(4L);
        noShowAppt.setAppointmentCode("LH-4");
        noShowAppt.setPatientProfile(patient);
        noShowAppt.setBranch(branch1);
        noShowAppt.setStatus(AppointmentStatus.NO_SHOW);

        when(taskRepository.findCompletedVisitsByBranchForCare(1L)).thenReturn(List.of(visit));
        when(taskRepository.findValidPrescriptionsByBranchForCare(1L)).thenReturn(List.of(pr));
        when(appointmentRepository.findByBranchWithDetails(1L)).thenReturn(List.of(upcomingAppt, noShowAppt));

        Map<String, Object> result = service.autoGenerateTasks(1L, 1L);
        assertEquals(4, result.get("totalCreated"));
        assertEquals(1, result.get("createdPostTreatment"));
        assertEquals(1, result.get("createdMedication"));
        assertEquals(1, result.get("createdAppointment"));
        assertEquals(1, result.get("createdNoShow"));
        verify(taskRepository, times(4)).save(any(CustomerCareTask.class));
    }

    // ==================== 6. recordInteraction, escalateToDoctor, submitDoctorResponse, reopenTask ====================

    @Test
    void recordInteraction_ValidatesInputsClosedStatusAndTransitions() {
        CustomerCareInteractionCreateDTO badNotes = new CustomerCareInteractionCreateDTO();
        badNotes.setChannel("PHONE_CALL");
        badNotes.setContactResult("CONNECTED");
        badNotes.setNotes("ab");

        mockReceptionist(10L, 1L);
        CustomerCareTask closed = sampleTask(1L, CustomerCareTaskStatus.COMPLETED, LocalDate.now());
        when(taskRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(closed));
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, () -> service.recordInteraction(1L, badNotes, 10L)).getStatusCode());

        CustomerCareTask open = sampleTask(2L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        when(taskRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(open));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, badNotes, 10L)).getStatusCode());

        // BUSY requires nextRetryAt -> sets IN_PROGRESS and updates dueDate
        CustomerCareInteractionCreateDTO busy = new CustomerCareInteractionCreateDTO();
        busy.setChannel("ZALO");
        busy.setContactResult("BUSY");
        busy.setNotes("Khách báo đang họp, gọi lại chiều");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, busy, 10L)).getStatusCode());

        LocalDateTime retryTime = LocalDateTime.of(2026, 6, 20, 15, 30);
        busy.setNextRetryAt(retryTime);
        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(receptionistStaff));
        when(interactionRepository.findByTaskIdWithDetails(2L)).thenReturn(List.of());

        service.recordInteraction(2L, busy, 10L);
        assertEquals(CustomerCareTaskStatus.IN_PROGRESS, open.getStatus());
        assertEquals(LocalDate.of(2026, 6, 20), open.getDueDate());

        // CONNECTED transitions to COMPLETED
        CustomerCareInteractionCreateDTO connected = new CustomerCareInteractionCreateDTO();
        connected.setChannel("PHONE_CALL");
        connected.setContactResult("CONNECTED");
        connected.setNotes("Bệnh nhân đã hết đau và hài lòng");
        service.recordInteraction(2L, connected, 10L);
        assertEquals(CustomerCareTaskStatus.COMPLETED, open.getStatus());
        verify(notificationService, times(2)).notifyTaskUpdated(eq(open), eq(10L), anyString());
    }

    @Test
    void escalateToDoctor_ValidatesDoctorStatusAndPreservesOriginalNotes() {
        CustomerCareEscalateDTO dto = new CustomerCareEscalateDTO();
        dto.setEscalatedToDoctorId(200L);
        dto.setEscalationReason("Đau nhức vùng răng hàm dưới");

        mockReceptionist(10L, 1L);
        CustomerCareTask task = sampleTask(3L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        task.setNotes("Ghi chú ban đầu");
        when(taskRepository.findByIdWithDetails(3L)).thenReturn(Optional.of(task));

        doctorStaff.setStatus(StaffStatus.INACTIVE);
        when(staffRepository.findById(200L)).thenReturn(Optional.of(doctorStaff));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(3L, dto, 10L)).getStatusCode());

        doctorStaff.setStatus(StaffStatus.ACTIVE);
        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(receptionistStaff));
        when(interactionRepository.findByTaskIdWithDetails(3L)).thenReturn(List.of());

        CustomerCareTaskDTO res = service.escalateToDoctor(3L, dto, 10L);
        assertEquals(CustomerCareTaskStatus.ESCALATED_TO_DOCTOR.name(), res.getStatus());
        assertEquals("Ghi chú ban đầu", task.getNotes());
        assertEquals("Đau nhức vùng răng hàm dưới", task.getEscalationReason());
        assertEquals(10L, task.getEscalatedByUserId());
        verify(interactionRepository).save(any(CustomerCareInteraction.class));
        verify(notificationService).notifyEscalatedToDoctor(task, doctorStaff, 10L, "Nguyễn Lễ Tân");
    }

    @Test
    void submitDoctorResponse_RejectsReceptionistAndSavesDoctorInstruction() {
        CustomerCareEscalateDTO dto = new CustomerCareEscalateDTO();
        dto.setDoctorResponse("Kê thêm Paracetamol 500mg uống sau ăn");
        dto.setNextStatus("IN_PROGRESS");

        CustomerCareTask task = sampleTask(4L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR, LocalDate.now());
        when(taskRepository.findByIdWithDetails(4L)).thenReturn(Optional.of(task));

        mockReceptionist(10L, 1L);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.submitDoctorResponse(4L, dto, 10L)).getStatusCode());

        mockDoctor(20L, 1L);
        when(staffRepository.findByUser_UserId(20L)).thenReturn(Optional.of(doctorStaff));
        when(interactionRepository.findByTaskIdWithDetails(4L)).thenReturn(List.of());

        service.submitDoctorResponse(4L, dto, 20L);
        assertEquals("Kê thêm Paracetamol 500mg uống sau ăn", task.getDoctorResponse());
        assertEquals(doctorStaff, task.getEscalatedToDoctor());
        assertEquals(CustomerCareTaskStatus.IN_PROGRESS, task.getStatus());
        verify(notificationService).notifyDoctorResponded(task, 20L, "BS. Trần Minh");
    }

    @Test
    void reopenTask_RequiresAdminAndCompletedStatusAndLogsAuditTrail() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.reopenTask(1L, "abc", 1L, "127.0.0.1")).getStatusCode());

        CustomerCareTask completedTask = sampleTask(10L, CustomerCareTaskStatus.COMPLETED, LocalDate.now());
        when(taskRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(completedTask));

        mockReceptionist(10L, 1L);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.reopenTask(10L, "Mở lại kiểm tra", 10L, "127.0.0.1")).getStatusCode());

        mockAdmin(1L);
        CustomerCareTask pendingTask = sampleTask(11L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        when(taskRepository.findByIdWithDetails(11L)).thenReturn(Optional.of(pendingTask));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.reopenTask(11L, "Mở lại kiểm tra", 1L, "127.0.0.1")).getStatusCode());

        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.empty());
        when(interactionRepository.findByTaskIdWithDetails(10L)).thenReturn(List.of());

        service.reopenTask(10L, "Bệnh nhân báo đau lại", 1L, "192.168.1.2");
        assertEquals(CustomerCareTaskStatus.IN_PROGRESS, completedTask.getStatus());
        verify(interactionRepository).save(any(CustomerCareInteraction.class));
        verify(systemAuditLogService).logAction(eq(adminUser), eq(branch1),
                eq("CUSTOMER_CARE_TASK_REOPEN"), eq("customer_care_tasks"), eq(10L),
                eq("192.168.1.2"), anyMap(), anyMap());
        verify(notificationService).notifyTaskReopened(completedTask, "Bệnh nhân báo đau lại", 1L, "admin");
    }

    // ==================== 7. searchInteractions, getPatientCareHistory, getVisitExaminationSummary, recordVisitInteraction ====================

    @Test
    void searchInteractions_FiltersByBranchTimeRangeDoctorChannelResultAndKeyword() {
        mockReceptionist(10L, 1L);

        CustomerCareTask b1Task = sampleTask(1L, CustomerCareTaskStatus.IN_PROGRESS, LocalDate.now());
        b1Task.setEscalatedToDoctor(doctorStaff);
        CustomerCareTask b2Task = sampleTask(2L, CustomerCareTaskStatus.IN_PROGRESS, LocalDate.now());
        b2Task.setBranch(branch2);

        CustomerCareInteraction match = new CustomerCareInteraction();
        match.setInteractionId(1L);
        match.setTask(b1Task);
        match.setPatientProfile(patient);
        match.setStaff(receptionistStaff);
        match.setChannel(InteractionChannel.PHONE_CALL);
        match.setContactResult(ContactResult.CONNECTED);
        match.setNotes("Khách xác nhận tái khám");
        match.setCreatedAt(LocalDateTime.now());

        CustomerCareInteraction otherBranch = new CustomerCareInteraction();
        otherBranch.setInteractionId(2L);
        otherBranch.setTask(b2Task);
        otherBranch.setPatientProfile(patient);
        otherBranch.setStaff(receptionistStaff);
        otherBranch.setChannel(InteractionChannel.PHONE_CALL);
        otherBranch.setContactResult(ContactResult.CONNECTED);
        otherBranch.setCreatedAt(LocalDateTime.now());

        when(interactionRepository.findInteractionsWithDetails(1L, null)).thenReturn(List.of(match, otherBranch));

        Page<CustomerCareInteractionDTO> page = service.searchInteractions(
                1L, null, "tái khám", "TODAY", null, null, 100L, 200L,
                "PHONE_CALL", "CONNECTED", "POST_TREATMENT_FOLLOWUP", PageRequest.of(0, 10), 10L);

        assertEquals(1, page.getTotalElements());
        assertEquals(1L, page.getContent().get(0).getInteractionId());
    }

    @Test
    void getPatientCareHistory_EnforcesBranchAccessAndReturnsTimeline() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.getPatientCareHistory(0L, 1L, 10L)).getStatusCode());

        mockReceptionist(10L, 1L);
        PatientProfile b2Patient = new PatientProfile();
        b2Patient.setProfileId(200L);
        b2Patient.setPreferredBranch(branch2);

        when(patientRepository.findById(200L)).thenReturn(Optional.of(b2Patient));
        when(taskRepository.findByPatientProfileIdWithDetails(200L)).thenReturn(List.of());
        when(interactionRepository.findByProfileIdWithDetails(200L)).thenReturn(List.of());
        when(visitRepository.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(200L)).thenReturn(List.of());

        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.getPatientCareHistory(200L, 1L, 10L)).getStatusCode());

        when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));
        CustomerCareTask t1 = sampleTask(1L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        CustomerCareInteraction i1 = new CustomerCareInteraction();
        i1.setInteractionId(5L);
        i1.setTask(t1);
        i1.setPatientProfile(patient);
        i1.setStaff(receptionistStaff);
        i1.setChannel(InteractionChannel.ZALO);
        i1.setContactResult(ContactResult.CONNECTED);
        i1.setNotes("Nhắn Zalo hướng dẫn vệ sinh răng");
        when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of(t1));
        when(interactionRepository.findByProfileIdWithDetails(1000L)).thenReturn(List.of(i1));

        Map<String, Object> history = service.getPatientCareHistory(1000L, 1L, 10L);
        assertEquals(1, history.get("totalTasks"));
        assertEquals(1, history.get("totalInteractions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getVisitExaminationSummary_AggregatesClinicalExamDiagnosisAndPrescription() {
        mockReceptionist(10L, 1L);

        Visit otherBranchVisit = new Visit();
        otherBranchVisit.setVisitId(88L);
        otherBranchVisit.setBranch(branch2);
        when(visitRepository.findWithDetailsById(88L)).thenReturn(Optional.of(otherBranchVisit));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.getVisitExaminationSummary(88L, 10L)).getStatusCode());

        Visit visit = new Visit();
        visit.setVisitId(70L);
        visit.setVisitCode("LK-70");
        visit.setBranch(branch1);
        visit.setPatientProfile(patient);
        visit.setPrimaryDoctor(doctorStaff);
        visit.setStatus(VisitStatus.COMPLETED);
        visit.setNotes("Tái khám sau 7 ngày");

        ClinicalExamination exam = new ClinicalExamination();
        exam.setExamId(11L);
        exam.setChiefComplaint("Đau răng hàm");
        exam.setSummaryAssessment("Viêm quanh cuống");

        Icd10Disease icd = new Icd10Disease();
        icd.setIcd10Code("K04.5");
        ExaminationDiagnosis diag = new ExaminationDiagnosis();
        diag.setDiagnosisId(22L);
        diag.setDiagnosisType(DiagnosisType.MAIN);
        diag.setIcd10Disease(icd);
        diag.setDiagnosisText("Viêm quanh cuống mạn");
        diag.setToothNumbers("36");

        PrescriptionItem pItem = new PrescriptionItem();
        pItem.setItemId(33L);
        pItem.setDrugName("Augmentin 1g");
        Prescription pr = new Prescription();
        pr.setPrescriptionId(44L);
        pr.setPrescriptionCode("DT-44");
        pr.setAdviceNotes("Uống đủ 5 ngày");
        pr.setFollowupDate(LocalDate.of(2026, 7, 1));
        pr.setItems(List.of(pItem));

        when(visitRepository.findWithDetailsById(70L)).thenReturn(Optional.of(visit));
        when(clinicalExaminationRepository.findByVisit_VisitId(70L)).thenReturn(Optional.of(exam));
        when(examinationDiagnosisRepository.findByVisitIdWithDetails(70L)).thenReturn(List.of(diag));
        when(prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(70L)).thenReturn(List.of(pr));
        when(prescriptionRepository.findByIdWithDetails(44L)).thenReturn(Optional.of(pr));

        Map<String, Object> summary = service.getVisitExaminationSummary(70L, 10L);
        assertEquals("[K04.5] Viêm quanh cuống mạn (Răng: 36)", summary.get("diagnosisSummary"));
        assertTrue(((String) summary.get("treatmentInstructions")).contains("Uống đủ 5 ngày"));
        assertEquals(1, ((List<Map<String, Object>>) summary.get("prescriptionItems")).size());
        assertEquals(LocalDate.of(2026, 7, 1), summary.get("followupDate"));
    }

    @Test
    void recordVisitInteraction_AutoCreatesTaskWhenMissingAndRecordsInteraction() {
        mockDoctor(20L, 1L);

        Visit visit = new Visit();
        visit.setVisitId(77L);
        visit.setVisitCode("LK-77");
        visit.setBranch(branch1);
        visit.setPatientProfile(patient);
        visit.setPrimaryDoctor(doctorStaff);

        when(visitRepository.findWithDetailsById(77L)).thenReturn(Optional.of(visit));
        when(taskRepository.findByVisit_VisitId(77L)).thenReturn(List.of());
        when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of());
        when(taskRepository.findByIdWithDetails(999L)).thenAnswer(inv -> {
            CustomerCareTask t = sampleTask(999L, CustomerCareTaskStatus.IN_PROGRESS, LocalDate.now());
            t.setVisit(visit);
            return Optional.of(t);
        });
        when(staffRepository.findByUser_UserId(20L)).thenReturn(Optional.of(doctorStaff));
        when(interactionRepository.findByTaskIdWithDetails(999L)).thenReturn(List.of());

        CustomerCareInteractionCreateDTO dto = new CustomerCareInteractionCreateDTO();
        dto.setChannel("PHONE_CALL");
        dto.setContactResult("CONNECTED");
        dto.setNotes("Bác sĩ gọi hỏi thăm bệnh nhân ổn định");

        CustomerCareTaskDTO res = service.recordVisitInteraction(77L, dto, 20L);
        assertNotNull(res);
        assertEquals(999L, res.getTaskId());
        verify(interactionRepository).save(any(CustomerCareInteraction.class));
    }

    // ==================== 8. Additional Atomic Branch & Validation Tests ====================

    @Test
    void requireCareRole_FallsBackToActiveDoctorStaffProfile_WhenNoRolesPresent() {
        when(userRepository.findById(20L)).thenReturn(Optional.of(doctorUser));
        when(userRepository.hasActiveAdminRole(20L)).thenReturn(false);
        when(userRepository.hasActivePermission(20L, "CUSTOMER_CARE_MANAGE")).thenReturn(false);
        when(taskRepository.findActiveRoleNamesByUserId(20L)).thenReturn(List.of());
        when(staffRepository.findByUser_UserId(20L)).thenReturn(Optional.of(doctorStaff));

        assertEquals("BAC_SI", service.requireCareRole(20L));
    }

    @Test
    void requireCareRole_ThrowsForbidden_WhenInactiveStaffProfileFallback() {
        StaffProfile nonCareStaff = new StaffProfile();
        nonCareStaff.setStaffId(300L);
        nonCareStaff.setStaffCode("KT01");
        nonCareStaff.setPositionTitle("Kế toán");
        nonCareStaff.setDepartment("Tài chính");
        nonCareStaff.setStatus(StaffStatus.ACTIVE);

        when(userRepository.findById(10L)).thenReturn(Optional.of(receptionistUser));
        when(userRepository.hasActiveAdminRole(10L)).thenReturn(false);
        when(userRepository.hasActivePermission(10L, "CUSTOMER_CARE_MANAGE")).thenReturn(false);
        when(taskRepository.findActiveRoleNamesByUserId(10L)).thenReturn(List.of());
        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(nonCareStaff));

        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.requireCareRole(10L)).getStatusCode());
    }

    @Test
    void getSummary_ThrowsForbidden_WhenNonAdminHasNoAssignedBranches() {
        mockReceptionist(10L, null);
        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of());
        when(branchRepository.findAll()).thenReturn(List.of());

        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.getSummary(null, 10L)).getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLookups_FallsBackDoctorListToStaffList_WhenBranchHasNoDoctors() {
        mockAdmin(1L);
        when(staffRepository.findByStatus(StaffStatus.ACTIVE)).thenReturn(List.of(receptionistStaff));
        when(taskRepository.findActiveStaffIdsByBranch(1L)).thenReturn(List.of(100L));
        when(appointmentRepository.findByBranchWithDetails(1L)).thenReturn(List.of());
        when(taskRepository.findValidVisitsByBranchForLookup(1L)).thenReturn(List.of());
        when(taskRepository.findValidPrescriptionsByBranchForCare(1L)).thenReturn(List.of());
        when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of());
        when(patientRepository.findAll()).thenReturn(List.of());

        Map<String, Object> lookups = service.getLookups(1L, 1L);
        List<Map<String, Object>> doctors = (List<Map<String, Object>>) lookups.get("doctorList");
        assertEquals(1, doctors.size());
        assertEquals(100L, doctors.get(0).get("staffId"));
    }

    @Test
    void searchTasks_FiltersPostTreatmentIncludingMedicationReminder() {
        mockAdmin(1L);
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        CustomerCareTask med = sampleTask(1L, CustomerCareTaskStatus.PENDING, today);
        med.setTaskType(CustomerCareTaskType.MEDICATION_REMINDER);
        CustomerCareTask appt = sampleTask(2L, CustomerCareTaskStatus.PENDING, today);
        appt.setTaskType(CustomerCareTaskType.APPOINTMENT_REMINDER);

        when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of(med, appt));
        when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        Page<CustomerCareTaskDTO> res = service.searchTasks(1L, "POST_TREATMENT", null, null, null,
                null, null, null, PageRequest.of(0, 10), 1L);
        assertEquals(1, res.getTotalElements());
        assertEquals(1L, res.getContent().get(0).getTaskId());
    }

    @Test
    void searchTasks_FiltersMyTasksOnly_IncludesEscalatedDoctorTasks() {
        mockDoctor(20L, 1L);
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        CustomerCareTask escalatedToMe = sampleTask(1L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR, today);
        escalatedToMe.setAssignedStaff(receptionistStaff);
        escalatedToMe.setEscalatedToDoctor(doctorStaff);

        CustomerCareTask otherTask = sampleTask(2L, CustomerCareTaskStatus.PENDING, today);
        otherTask.setAssignedStaff(receptionistStaff);

        when(staffRepository.findByUser_UserId(20L)).thenReturn(Optional.of(doctorStaff));
        when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of(escalatedToMe, otherTask));
        when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        Page<CustomerCareTaskDTO> res = service.searchTasks(1L, "MY_TASKS", null, null, null,
                null, null, null, null, null, null, false, PageRequest.of(0, 10), 20L);
        assertEquals(1, res.getTotalElements());
        assertEquals(1L, res.getContent().get(0).getTaskId());
    }

    @Test
    void searchTasks_FiltersDoctorResponseRespondedVsWaiting() {
        mockAdmin(1L);
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        CustomerCareTask responded = sampleTask(1L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR, today);
        responded.setDoctorResponse("Súc miệng Chlorhexidine");
        CustomerCareTask waiting = sampleTask(2L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR, today);

        when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of(responded, waiting));
        when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        Page<CustomerCareTaskDTO> respPage = service.searchTasks(1L, "ESCALATED", null, null, null,
                null, null, null, null, null, "RESPONDED", false, PageRequest.of(0, 10), 1L);
        assertEquals(1, respPage.getTotalElements());
        assertEquals(1L, respPage.getContent().get(0).getTaskId());
    }

    @Test
    void searchTasks_MatchesKeywordInVisitCodePrescriptionCodeAndDoctorResponse() {
        mockAdmin(1L);
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        CustomerCareTask byDocResp = sampleTask(1L, CustomerCareTaskStatus.IN_PROGRESS, today);
        byDocResp.setDoctorResponse("Khuyên dùng chỉ nha khoa");

        when(taskRepository.findAllByBranchWithDetails(1L)).thenReturn(List.of(byDocResp));
        when(interactionRepository.findAllByBranchOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        Page<CustomerCareTaskDTO> res = service.searchTasks(1L, "ALL", null, null, null,
                null, null, "chỉ nha khoa", PageRequest.of(0, 10), 1L);
        assertEquals(1, res.getTotalElements());
    }

    @Test
    void createTask_ThrowsBadRequest_WhenCareGroupAndTaskTypeBothBlank() {
        mockAdmin(1L);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));

        CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
        dto.setBranchId(1L);
        dto.setProfileId(1000L);
        dto.setDueDate(LocalDate.now());
        dto.setCareGroup("   ");

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());
    }

    @Test
    void createTask_ThrowsNotFound_WhenBranchPatientOrAssignedStaffMissing() {
        mockAdmin(1L);
        CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
        dto.setBranchId(99L);
        dto.setProfileId(1000L);
        dto.setDueDate(LocalDate.now());
        dto.setCareGroup("POST_TREATMENT");

        when(branchRepository.findById(99L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());

        dto.setBranchId(1L);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(patientRepository.findById(1000L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());

        dto.setAssignedStaffId(888L);
        when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(888L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());
    }

    @Test
    void createTask_BlocksDuplicateAppointmentTask() {
        mockAdmin(1L);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));

        Appointment appt = new Appointment();
        appt.setAppointmentId(50L);
        CustomerCareTask active = sampleTask(1L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        active.setTaskType(CustomerCareTaskType.APPOINTMENT_REMINDER);
        active.setAppointment(appt);
        when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of(active));

        CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
        dto.setBranchId(1L);
        dto.setProfileId(1000L);
        dto.setDueDate(LocalDate.now());
        dto.setCareGroup("APPOINTMENT_REMINDER");
        dto.setAppointmentId(50L);

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());
    }

    @Test
    void createTask_BlocksDuplicatePrescriptionTask() {
        mockAdmin(1L);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));

        Prescription pr = new Prescription();
        pr.setPrescriptionId(60L);
        CustomerCareTask active = sampleTask(1L, CustomerCareTaskStatus.IN_PROGRESS, LocalDate.now());
        active.setTaskType(CustomerCareTaskType.MEDICATION_REMINDER);
        active.setPrescription(pr);
        when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of(active));

        CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
        dto.setBranchId(1L);
        dto.setProfileId(1000L);
        dto.setDueDate(LocalDate.now());
        dto.setCareGroup("MEDICATION_REMINDER");
        dto.setPrescriptionId(60L);

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());
    }

    @Test
    void createTask_BlocksDuplicateStandaloneTaskSameGroupAndDueDate() {
        mockAdmin(1L);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));

        LocalDate due = LocalDate.of(2026, 9, 1);
        CustomerCareTask active = sampleTask(1L, CustomerCareTaskStatus.PENDING, due);
        active.setNotes("[TRA_GOP] Kỳ 1");
        when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of(active));

        CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
        dto.setBranchId(1L);
        dto.setProfileId(1000L);
        dto.setDueDate(due);
        dto.setCareGroup("INSTALLMENT_REMINDER");

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.createTask(dto, 1L)).getStatusCode());
    }

    @Test
    void createTask_AssignsActorStaffByDefault_AndTagsNoServiceNotes() {
        mockAdmin(1L);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(patientRepository.findById(1000L)).thenReturn(Optional.of(patient));
        when(taskRepository.findByPatientProfileIdWithDetails(1000L)).thenReturn(List.of());
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(receptionistStaff));

        CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
        dto.setBranchId(1L);
        dto.setProfileId(1000L);
        dto.setDueDate(LocalDate.now().plusDays(1));
        dto.setCareGroup("NO_SERVICE_FOLLOWUP");
        dto.setNotes(null);

        CustomerCareTaskDTO created = service.createTask(dto, 1L);
        assertEquals("NO_SERVICE_FOLLOWUP", created.getCareGroup());
        assertEquals(100L, created.getAssignedStaffId());
        assertTrue(created.getNotes().contains("chưa thực hiện dịch vụ"));
    }

    @Test
    void updateTask_ThrowsBadRequestOrNotFound_ForInvalidIdOrMissingTask() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.updateTask(0L, new CustomerCareCreateDTO(), 1L)).getStatusCode());

        when(taskRepository.findByIdWithDetails(77L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.updateTask(77L, new CustomerCareCreateDTO(), 1L)).getStatusCode());
    }

    @Test
    void updateTask_ChangesPatientAndReassignsStaff() {
        mockAdmin(1L);
        CustomerCareTask task = sampleTask(2L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        when(taskRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(task));
        when(interactionRepository.findByTaskIdWithDetails(2L)).thenReturn(List.of());

        PatientProfile newPatient = new PatientProfile();
        newPatient.setProfileId(1001L);
        newPatient.setFullName("Bệnh nhân mới");
        when(patientRepository.findById(1001L)).thenReturn(Optional.of(newPatient));
        when(staffRepository.findById(200L)).thenReturn(Optional.of(doctorStaff));

        CustomerCareCreateDTO dto = new CustomerCareCreateDTO();
        dto.setProfileId(1001L);
        dto.setAssignedStaffId(200L);
        dto.setNotes("  Ghi chú sửa tay  ");

        CustomerCareTaskDTO res = service.updateTask(2L, dto, 1L);
        assertEquals(1001L, res.getProfileId());
        assertEquals(200L, res.getAssignedStaffId());
        assertEquals("Ghi chú sửa tay", res.getNotes());
    }

    @Test
    void autoGenerateTasks_SkipsExistingTasksAndReportsZeroMessage() {
        mockAdmin(1L);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(branch1));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(receptionistStaff));

        Visit existingVisit = new Visit();
        existingVisit.setVisitId(1L);
        existingVisit.setPatientProfile(patient);
        when(taskRepository.findCompletedVisitsByBranchForCare(1L)).thenReturn(List.of(existingVisit));
        when(taskRepository.existsByVisit_VisitIdAndTaskType(1L, CustomerCareTaskType.POST_TREATMENT_FOLLOWUP)).thenReturn(true);
        when(taskRepository.findValidPrescriptionsByBranchForCare(1L)).thenReturn(List.of());
        when(appointmentRepository.findByBranchWithDetails(1L)).thenReturn(List.of());

        Map<String, Object> res = service.autoGenerateTasks(1L, 1L);
        assertEquals(0, res.get("totalCreated"));
        assertTrue(((String) res.get("message")).contains("đã có đầy đủ"));
        verify(taskRepository, never()).save(any());
    }

    @Test
    void recordInteraction_ThrowsBadRequest_WhenInvalidTaskIdNullDtoOrOver2000Notes() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.recordInteraction(0L, new CustomerCareInteractionCreateDTO(), 1L)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.recordInteraction(1L, null, 1L)).getStatusCode());

        mockReceptionist(10L, 1L);
        CustomerCareTask open = sampleTask(2L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        when(taskRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(open));

        CustomerCareInteractionCreateDTO tooLong = new CustomerCareInteractionCreateDTO();
        tooLong.setChannel("PHONE_CALL");
        tooLong.setContactResult("CONNECTED");
        tooLong.setNotes("a".repeat(2001));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, tooLong, 10L)).getStatusCode());
    }

    @Test
    void recordInteraction_ThrowsBadRequest_WhenInvalidChannelResultOrNextStatus() {
        mockReceptionist(10L, 1L);
        CustomerCareTask open = sampleTask(2L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        when(taskRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(open));

        CustomerCareInteractionCreateDTO badChannel = new CustomerCareInteractionCreateDTO();
        badChannel.setChannel("FAX");
        badChannel.setContactResult("CONNECTED");
        badChannel.setNotes("Ghi chú hợp lệ");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, badChannel, 10L)).getStatusCode());

        CustomerCareInteractionCreateDTO badResult = new CustomerCareInteractionCreateDTO();
        badResult.setChannel("SMS");
        badResult.setContactResult("UNKNOWN");
        badResult.setNotes("Ghi chú hợp lệ");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, badResult, 10L)).getStatusCode());

        CustomerCareInteractionCreateDTO badNext = new CustomerCareInteractionCreateDTO();
        badNext.setChannel("IN_PERSON");
        badNext.setContactResult("CONNECTED");
        badNext.setNotes("Ghi chú hợp lệ");
        badNext.setNextStatus("INVALID_STATUS");
        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(receptionistStaff));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.recordInteraction(2L, badNext, 10L)).getStatusCode());
    }

    @Test
    void recordInteraction_AppliesExplicitNextStatusAndAssignsDefaultAssigneeWhenNull() {
        mockReceptionist(10L, 1L);
        CustomerCareTask unassigned = sampleTask(2L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        unassigned.setAssignedStaff(null);
        when(taskRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(unassigned));
        when(staffRepository.findById(100L)).thenReturn(Optional.of(receptionistStaff));
        when(interactionRepository.findByTaskIdWithDetails(2L)).thenReturn(List.of());

        CustomerCareInteractionCreateDTO dto = new CustomerCareInteractionCreateDTO();
        dto.setChannel(null); // defaults to PHONE_CALL
        dto.setContactResult("SCHEDULED_APPOINTMENT");
        dto.setNotes("Khách hẹn thứ 6 tuần sau tái khám");
        dto.setStaffId(100L);
        dto.setNextStatus("IN_PROGRESS");

        service.recordInteraction(2L, dto, 10L);
        assertEquals(CustomerCareTaskStatus.IN_PROGRESS, unassigned.getStatus());
        assertEquals(receptionistStaff, unassigned.getAssignedStaff());
    }

    @Test
    void escalateToDoctor_ValidatesArgumentsClosedTaskAndMissingDoctor() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(0L, new CustomerCareEscalateDTO(), 1L)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(1L, null, 1L)).getStatusCode());

        CustomerCareEscalateDTO shortReason = new CustomerCareEscalateDTO();
        shortReason.setEscalatedToDoctorId(200L);
        shortReason.setEscalationReason("abc");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(1L, shortReason, 1L)).getStatusCode());

        CustomerCareEscalateDTO longReason = new CustomerCareEscalateDTO();
        longReason.setEscalatedToDoctorId(200L);
        longReason.setEscalationReason("x".repeat(2001));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(1L, longReason, 1L)).getStatusCode());

        mockReceptionist(10L, 1L);
        CustomerCareTask closed = sampleTask(3L, CustomerCareTaskStatus.COMPLETED, LocalDate.now());
        when(taskRepository.findByIdWithDetails(3L)).thenReturn(Optional.of(closed));
        CustomerCareEscalateDTO valid = new CustomerCareEscalateDTO();
        valid.setEscalatedToDoctorId(200L);
        valid.setEscalationReason("Triệu chứng sưng lợi");
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(3L, valid, 10L)).getStatusCode());

        CustomerCareTask open = sampleTask(4L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        when(taskRepository.findByIdWithDetails(4L)).thenReturn(Optional.of(open));
        when(staffRepository.findById(200L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.escalateToDoctor(4L, valid, 10L)).getStatusCode());
    }

    @Test
    void submitDoctorResponse_ValidatesArgumentsAndAllowsAdminOverride() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.submitDoctorResponse(0L, new CustomerCareEscalateDTO(), 1L)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.submitDoctorResponse(1L, null, 1L)).getStatusCode());

        CustomerCareEscalateDTO shortResp = new CustomerCareEscalateDTO();
        shortResp.setDoctorResponse("ok");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.submitDoctorResponse(1L, shortResp, 1L)).getStatusCode());

        mockAdmin(1L);
        CustomerCareTask task = sampleTask(4L, CustomerCareTaskStatus.ESCALATED_TO_DOCTOR, LocalDate.now());
        when(taskRepository.findByIdWithDetails(4L)).thenReturn(Optional.of(task));
        when(staffRepository.findById(200L)).thenReturn(Optional.of(doctorStaff));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.empty());
        when(interactionRepository.findByTaskIdWithDetails(4L)).thenReturn(List.of());

        CustomerCareEscalateDTO dto = new CustomerCareEscalateDTO();
        dto.setDoctorResponse("Đã hướng dẫn chăm sóc lại cho bệnh nhân");
        dto.setEscalatedToDoctorId(200L);
        dto.setNextStatus("COMPLETED");

        CustomerCareTaskDTO res = service.submitDoctorResponse(4L, dto, 1L);
        assertEquals("COMPLETED", res.getStatus());
        assertEquals(200L, res.getEscalatedToDoctorId());
        assertNotNull(task.getEscalatedAt());
    }

    @Test
    void reopenTask_ThrowsBadRequest_WhenReasonOver1000Chars_AndSucceedsWithoutOptionalServices() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.reopenTask(1L, "a".repeat(1001), 1L, null)).getStatusCode());

        ReflectionTestUtils.setField(service, "notificationService", null);
        ReflectionTestUtils.setField(service, "systemAuditLogService", null);
        mockAdmin(1L);
        CustomerCareTask completed = sampleTask(10L, CustomerCareTaskStatus.COMPLETED, LocalDate.now());
        when(taskRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(completed));
        when(staffRepository.findByUser_UserId(1L)).thenReturn(Optional.of(receptionistStaff));
        when(interactionRepository.findByTaskIdWithDetails(10L)).thenReturn(List.of());

        CustomerCareTaskDTO res = service.reopenTask(10L, "Mở lại khi services null", 1L, null);
        assertEquals("IN_PROGRESS", res.getStatus());
    }

    @Test
    void searchInteractions_SupportsYesterdayThisWeekLastMonthAndSwappedCustomDates() {
        mockAdmin(1L);
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        CustomerCareTask task = sampleTask(1L, CustomerCareTaskStatus.IN_PROGRESS, today);

        CustomerCareInteraction yesterdayInt = new CustomerCareInteraction();
        yesterdayInt.setInteractionId(1L);
        yesterdayInt.setTask(task);
        yesterdayInt.setPatientProfile(patient);
        yesterdayInt.setStaff(receptionistStaff);
        yesterdayInt.setCreatedAt(LocalDateTime.now(CLINIC_ZONE).minusDays(1));

        CustomerCareInteraction todayInt = new CustomerCareInteraction();
        todayInt.setInteractionId(2L);
        todayInt.setTask(task);
        todayInt.setPatientProfile(patient);
        todayInt.setStaff(receptionistStaff);
        todayInt.setCreatedAt(LocalDateTime.now(CLINIC_ZONE));

        when(interactionRepository.findInteractionsWithDetails(1L, null)).thenReturn(List.of(yesterdayInt, todayInt));

        Page<CustomerCareInteractionDTO> yesterdayPage = service.searchInteractions(
                1L, null, null, "YESTERDAY", null, null, null, null, null, null, null, PageRequest.of(0, 10), 1L);
        assertEquals(1, yesterdayPage.getTotalElements());
        assertEquals(1L, yesterdayPage.getContent().get(0).getInteractionId());

        // Swapped custom dates fromDate > toDate auto-swapped cleanly
        Page<CustomerCareInteractionDTO> swappedCustom = service.searchInteractions(
                1L, null, "nguyễn lễ tân", "CUSTOM", today, today.minusDays(2),
                null, null, null, null, null, PageRequest.of(0, 10), 1L);
        assertEquals(2, swappedCustom.getTotalElements());

        assertDoesNotThrow(() -> service.searchInteractions(1L, null, null, "THIS_WEEK", null, null, null, null, null, null, null, PageRequest.of(0, 10), 1L));
        assertDoesNotThrow(() -> service.searchInteractions(1L, null, null, "LAST_WEEK", null, null, null, null, null, null, null, PageRequest.of(0, 10), 1L));
        assertDoesNotThrow(() -> service.searchInteractions(1L, null, null, "THIS_MONTH", null, null, null, null, null, null, null, PageRequest.of(0, 10), 1L));
        assertDoesNotThrow(() -> service.searchInteractions(1L, null, null, "LAST_MONTH", null, null, null, null, null, null, null, PageRequest.of(0, 10), 1L));
    }

    @Test
    void getPatientCareHistory_AllowsAccessViaVisitInBranchAndFiltersAdminByRequestedBranch() {
        mockReceptionist(10L, 1L);
        PatientProfile otherPreferred = new PatientProfile();
        otherPreferred.setProfileId(205L);
        otherPreferred.setPreferredBranch(branch2);

        Visit b1Visit = new Visit();
        b1Visit.setVisitId(1L);
        b1Visit.setBranch(branch1);

        when(patientRepository.findById(205L)).thenReturn(Optional.of(otherPreferred));
        when(taskRepository.findByPatientProfileIdWithDetails(205L)).thenReturn(List.of());
        when(interactionRepository.findByProfileIdWithDetails(205L)).thenReturn(List.of());
        when(visitRepository.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(205L)).thenReturn(List.of(b1Visit));

        Map<String, Object> staffRes = service.getPatientCareHistory(205L, 1L, 10L);
        assertEquals(0, staffRes.get("totalTasks"));

        // Admin filters by branchId = 1
        mockAdmin(1L);
        CustomerCareTask b1Task = sampleTask(1L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        CustomerCareTask b2Task = sampleTask(2L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        b2Task.setBranch(branch2);
        when(taskRepository.findByPatientProfileIdWithDetails(205L)).thenReturn(List.of(b1Task, b2Task));

        Map<String, Object> adminRes = service.getPatientCareHistory(205L, 1L, 1L);
        assertEquals(1, adminRes.get("totalTasks"));
    }

    @Test
    void getVisitExaminationSummary_ThrowsBadRequestInvalidIdAndFallsBackToPrescriptionDiagnosis() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.getVisitExaminationSummary(null, 1L)).getStatusCode());

        mockAdmin(1L);
        Visit visit = new Visit();
        visit.setVisitId(80L);
        visit.setVisitCode("LK-80");
        visit.setBranch(branch1);
        visit.setPatientProfile(patient);

        Prescription pr = new Prescription();
        pr.setPrescriptionId(90L);
        pr.setPrescriptionCode("DT-90");
        pr.setDiagnosisText("Chẩn đoán từ đơn thuốc");

        when(visitRepository.findWithDetailsById(80L)).thenReturn(Optional.of(visit));
        when(clinicalExaminationRepository.findByVisit_VisitId(80L)).thenReturn(Optional.empty());
        when(examinationDiagnosisRepository.findByVisitIdWithDetails(80L)).thenReturn(List.of());
        when(prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(80L)).thenReturn(List.of(pr));
        when(prescriptionRepository.findByIdWithDetails(90L)).thenReturn(Optional.of(pr));

        Map<String, Object> summary = service.getVisitExaminationSummary(80L, 1L);
        assertEquals("Chẩn đoán từ đơn thuốc", summary.get("diagnosisSummary"));
    }

    @Test
    void recordVisitInteraction_ReusesOpenVisitTaskAndValidatesMissingPatientOrCrossBranch() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.recordVisitInteraction(0L, new CustomerCareInteractionCreateDTO(), 1L)).getStatusCode());

        mockReceptionist(10L, 1L);
        Visit crossVisit = new Visit();
        crossVisit.setVisitId(90L);
        crossVisit.setBranch(branch2);
        when(visitRepository.findWithDetailsById(90L)).thenReturn(Optional.of(crossVisit));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.recordVisitInteraction(90L, new CustomerCareInteractionCreateDTO(), 10L)).getStatusCode());

        Visit visit = new Visit();
        visit.setVisitId(91L);
        visit.setBranch(branch1);
        visit.setPatientProfile(patient);
        CustomerCareTask existing = sampleTask(300L, CustomerCareTaskStatus.PENDING, LocalDate.now());
        when(visitRepository.findWithDetailsById(91L)).thenReturn(Optional.of(visit));
        when(taskRepository.findByVisit_VisitId(91L)).thenReturn(List.of(existing));
        when(taskRepository.findByIdWithDetails(300L)).thenReturn(Optional.of(existing));
        when(staffRepository.findByUser_UserId(10L)).thenReturn(Optional.of(receptionistStaff));
        when(interactionRepository.findByTaskIdWithDetails(300L)).thenReturn(List.of());

        CustomerCareInteractionCreateDTO dto = new CustomerCareInteractionCreateDTO();
        dto.setChannel("PHONE_CALL");
        dto.setContactResult("CONNECTED");
        dto.setNotes("Gọi lại qua task có sẵn");

        CustomerCareTaskDTO res = service.recordVisitInteraction(91L, dto, 10L);
        assertEquals(300L, res.getTaskId());
    }
}
