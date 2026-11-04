package com.cvdcms;

import com.cvdcms.DTO.*;
import com.cvdcms.controller.AdminAuditController;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.SystemAuditLog;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.SystemAuditLogRepository;
import com.cvdcms.service.impl.SystemAuditLogServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemAuditLogUnitTest {

    @Mock
    private SystemAuditLogRepository auditRepo;

    @Mock
    private BranchRepository branchRepo;

    @Mock
    private StaffProfileRepository staffProfileRepo;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SystemAuditLogServiceImpl auditService;

    private AdminAuditController auditController;

    private User sampleUser;
    private StaffProfile sampleStaff;
    private Branch sampleBranch;
    private SystemAuditLog sampleLog;

    @BeforeEach
    void setUp() {
        auditController = new AdminAuditController(auditService);

        sampleUser = new User();
        sampleUser.setUserId(1L);
        sampleUser.setUsername("admin");
        sampleUser.setEmail("admin@dentalclinic.com");

        sampleStaff = new StaffProfile();
        sampleStaff.setStaffId(10L);
        sampleStaff.setFullName("Quản trị viên Hệ thống");
        sampleStaff.setPositionTitle("Quản trị viên");

        sampleBranch = new Branch();
        sampleBranch.setBranchId(1L);
        sampleBranch.setBranchName("Cơ sở Quận 1");

        sampleLog = new SystemAuditLog();
        sampleLog.setLogId(100L);
        sampleLog.setUser(sampleUser);
        sampleLog.setBranch(sampleBranch);
        sampleLog.setAction("UPDATE_EXPENSE");
        sampleLog.setEntityTable("operating_expenses");
        sampleLog.setEntityId(34L);
        sampleLog.setOldValuesJson("{\"amount\":\"1500000.00\",\"category\":\"Vật tư tiêu hao\"}");
        sampleLog.setNewValuesJson("{\"amount\":\"2000000.00\",\"category\":\"Vật tư tiêu hao\",\"editReason\":\"Bổ sung găng tay\"}");
        sampleLog.setIpAddress("192.168.1.100");
        sampleLog.setCreatedAt(LocalDateTime.now().minusMinutes(10));
    }

    @Test
    @DisplayName("UT-AUDIT-01: Lấy danh sách phân trang audit logs thành công")
    void testGetAuditLogsPage_Success() {
        AuditLogFilterDTO filter = new AuditLogFilterDTO();
        filter.setPage(0);
        filter.setSize(15);
        filter.setAction("UPDATE_EXPENSE");

        Page<SystemAuditLog> mockPage = new PageImpl<>(List.of(sampleLog));
        when(auditRepo.findFilteredLogs(any(), any(), eq("UPDATE_EXPENSE"), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(mockPage);
        when(staffProfileRepo.findByUser_UserId(1L)).thenReturn(Optional.of(sampleStaff));

        Page<AuditLogResponseDTO> result = auditService.getAuditLogsPage(filter);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        AuditLogResponseDTO dto = result.getContent().get(0);
        assertEquals(100L, dto.getLogId());
        assertEquals("UPDATE_EXPENSE", dto.getAction());
        assertEquals("Quản trị viên Hệ thống", dto.getUserFullName());
        assertEquals("Cơ sở Quận 1", dto.getBranchName());
        assertTrue(dto.isHasChanges());
    }

    @Test
    @DisplayName("UT-AUDIT-02: Lấy chi tiết audit log và đối chiếu JSON diff (SCR-31.2)")
    void testGetAuditLogDetail_DiffCalculation() {
        when(auditRepo.findById(100L)).thenReturn(Optional.of(sampleLog));
        when(staffProfileRepo.findByUser_UserId(1L)).thenReturn(Optional.of(sampleStaff));

        AuditLogDetailDTO detail = auditService.getAuditLogDetail(100L);

        assertNotNull(detail);
        assertEquals(100L, detail.getLogId());
        assertEquals("UPDATE_EXPENSE", detail.getAction());
        assertEquals("192.168.1.100", detail.getIpAddress());
        assertTrue(detail.isUpdate());

        List<AuditFieldDiffDTO> diffs = detail.getFieldDiffs();
        assertNotNull(diffs);
        assertEquals(3, diffs.size());

        // Field "amount": MODIFIED from 1500000.00 to 2000000.00
        AuditFieldDiffDTO amountDiff = diffs.stream().filter(d -> "amount".equals(d.getFieldName())).findFirst().orElse(null);
        assertNotNull(amountDiff);
        assertEquals("MODIFIED", amountDiff.getDiffType());
        assertEquals("1500000.00", amountDiff.getOldValue());
        assertEquals("2000000.00", amountDiff.getNewValue());

        // Field "editReason": ADDED
        AuditFieldDiffDTO reasonDiff = diffs.stream().filter(d -> "editReason".equals(d.getFieldName())).findFirst().orElse(null);
        assertNotNull(reasonDiff);
        assertEquals("ADDED", reasonDiff.getDiffType());
        assertEquals("Bổ sung găng tay", reasonDiff.getNewValue());

        // Field "category": UNCHANGED
        AuditFieldDiffDTO catDiff = diffs.stream().filter(d -> "category".equals(d.getFieldName())).findFirst().orElse(null);
        assertNotNull(catDiff);
        assertEquals("UNCHANGED", catDiff.getDiffType());
    }

    @Test
    @DisplayName("UT-AUDIT-03: Chi tiết audit log không tồn tại thì ném exception")
    void testGetAuditLogDetail_NotFound() {
        when(auditRepo.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> auditService.getAuditLogDetail(999L));
    }

    @Test
    @DisplayName("UT-AUDIT-04: Thống kê KPI tóm tắt đầy đủ các chỉ số")
    void testGetSummaryStats_Success() {
        when(auditRepo.count()).thenReturn(150L);
        when(auditRepo.countByCreatedAtBetween(any(), any())).thenReturn(18L);
        when(auditRepo.countCreateActions()).thenReturn(45L);
        when(auditRepo.countUpdateActions()).thenReturn(80L);
        when(auditRepo.countSensitiveActions()).thenReturn(25L);

        AuditSummaryStatsDTO stats = auditService.getSummaryStats();

        assertNotNull(stats);
        assertEquals(150L, stats.getTotalLogs());
        assertEquals(18L, stats.getTodayLogs());
        assertEquals(45L, stats.getCreateLogs());
        assertEquals(80L, stats.getUpdateLogs());
        assertEquals(25L, stats.getSensitiveLogs());
    }

    @Test
    @DisplayName("UT-AUDIT-05: Controller trả về trang admin/audit với đầy đủ model attributes")
    void testController_GetAuditLogPage() {
        AuditLogFilterDTO filter = new AuditLogFilterDTO();
        Page<SystemAuditLog> mockPage = new PageImpl<>(List.of(sampleLog));
        when(auditRepo.findFilteredLogs(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(mockPage);
        when(auditRepo.count()).thenReturn(10L);
        when(branchRepo.findAll()).thenReturn(List.of(sampleBranch));
        when(auditRepo.findDistinctActions()).thenReturn(List.of("UPDATE_EXPENSE", "CREATE_PATIENT"));
        when(auditRepo.findDistinctEntityTables()).thenReturn(List.of("operating_expenses", "patient_profiles"));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", sampleUser);
        Model model = new ConcurrentModel();
        String view = auditController.getAuditLogPage(filter, session, model);

        assertEquals("admin/audit", view);
        assertTrue(model.containsAttribute("logsPage"));
        assertTrue(model.containsAttribute("logs"));
        assertTrue(model.containsAttribute("stats"));
        assertTrue(model.containsAttribute("branches"));
        assertTrue(model.containsAttribute("actions"));
        assertTrue(model.containsAttribute("entityTables"));
        assertEquals("audit", model.getAttribute("activeMenu"));
    }

    @Test
    @DisplayName("UT-AUDIT-06: Controller API chi tiết trả về 200 OK với DTO khi log tồn tại")
    void testController_GetAuditDetail_Success() {
        when(auditRepo.findById(100L)).thenReturn(Optional.of(sampleLog));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", sampleUser);
        ResponseEntity<AuditLogDetailDTO> response = auditController.getAuditDetail(100L, session);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(100L, response.getBody().getLogId());
    }

    @Test
    @DisplayName("UT-AUDIT-07: Controller API chi tiết trả về 404 khi logId không tồn tại")
    void testController_GetAuditDetail_NotFound() {
        when(auditRepo.findById(999L)).thenReturn(Optional.empty());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", sampleUser);
        ResponseEntity<AuditLogDetailDTO> response = auditController.getAuditDetail(999L, session);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("UT-AUDIT-08: Người dùng chưa đăng nhập sẽ bị chuyển hướng về /login")
    void testController_Unauthenticated() {
        MockHttpSession emptySession = new MockHttpSession();
        Model model = new ConcurrentModel();
        String view = auditController.getAuditLogPage(new AuditLogFilterDTO(), emptySession, model);
        assertEquals("redirect:/login", view);

        ResponseEntity<AuditLogDetailDTO> response = auditController.getAuditDetail(100L, emptySession);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}