package com.cvdcms;

import com.cvdcms.DTO.NanopixReconciliationRequestDTO;
import com.cvdcms.DTO.NanopixSyncStatsDTO;
import com.cvdcms.DTO.NanopixXrayResponseDTO;
import com.cvdcms.controller.NanopixXrayRestController;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.service.NanopixXrayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NanopixXrayRestControllerTest {

    @Mock
    private NanopixXrayService nanopixXrayService;

    @InjectMocks
    private NanopixXrayRestController controller;

    private User doctorUser;
    private MockHttpSession sessionWithLoggedInUser;
    private MockHttpSession emptySession;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new NanopixXrayRestController(nanopixXrayService);

        doctorUser = new User();
        doctorUser.setUserId(10L);
        doctorUser.setUsername("bacsi");
        Role role = new Role();
        role.setRoleId(2L);
        role.setRoleName("BAC_SI");
        doctorUser.setRoles(Set.of(role));

        sessionWithLoggedInUser = new MockHttpSession();
        sessionWithLoggedInUser.setAttribute("loggedInUser", doctorUser);

        emptySession = new MockHttpSession();
    }

    @Test
    @DisplayName("API /api/xray/list: Không có session hoặc session rỗng -> Ném 401 UNAUTHORIZED")
    void getXrays_noSession_returns401() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.getXrays("ALL", null, null, null, null, 0, 10, emptySession)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Vui lòng đăng nhập"));
    }

    @Test
    @DisplayName("API /api/xray/list: Có session loggedInUser -> Trả về 200 OK và danh sách phân trang")
    void getXrays_withLoggedInUser_returns200() {
        when(nanopixXrayService.getXrays(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(
                        NanopixXrayResponseDTO.builder()
                                .syncLogId(1L)
                                .externalStudyUid("STUDY_TEST_01")
                                .rawPatientName("Nguyen Van A")
                                .build()
                )));

        ResponseEntity<Page<NanopixXrayResponseDTO>> response = controller.getXrays("ALL", null, null, null, null, 0, 10, sessionWithLoggedInUser);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getTotalElements());
        assertEquals("STUDY_TEST_01", response.getBody().getContent().get(0).getExternalStudyUid());
    }

    @Test
    @DisplayName("API /api/xray/stats: Có session loggedInUser -> Trả về 200 OK với số liệu thống kê")
    void getStats_withLoggedInUser_returns200() {
        when(nanopixXrayService.getSyncStatistics(any()))
                .thenReturn(NanopixSyncStatsDTO.builder()
                        .totalCount(15L)
                        .matchedCount(12L)
                        .unmatchedCount(3L)
                        .errorCount(0L)
                        .adapterStatus("ONLINE")
                        .build());

        ResponseEntity<NanopixSyncStatsDTO> response = controller.getStats(sessionWithLoggedInUser);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(15L, response.getBody().getTotalCount());
        assertEquals(12L, response.getBody().getMatchedCount());
        assertEquals("ONLINE", response.getBody().getAdapterStatus());
    }

    @Test
    @DisplayName("API /api/xray/unmatched: Có session loggedInUser -> Trả về 200 OK")
    void getUnmatched_withLoggedInUser_returns200() {
        when(nanopixXrayService.getUnmatchedXrays(any(), any()))
                .thenReturn(Collections.emptyList());

        ResponseEntity<List<NanopixXrayResponseDTO>> response = controller.getUnmatchedXrays(null, sessionWithLoggedInUser);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("API /api/xray/branches: Có session loggedInUser -> Trả về 200 OK với cơ sở được phép truy cập")
    void getBranches_withLoggedInUser_returns200() {
        when(nanopixXrayService.getAccessibleBranches(any()))
                .thenReturn(Map.of("role", "BAC_SI", "isAdmin", false, "branches", List.of(
                        Map.of("branchId", 1L, "branchName", "Cơ sở Cầu Giấy")
                )));

        ResponseEntity<Map<String, Object>> response = controller.getBranches(sessionWithLoggedInUser);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("BAC_SI", response.getBody().get("role"));
        assertEquals(false, response.getBody().get("isAdmin"));
    }

    @Test
    @DisplayName("API /api/xray/{id}/reconcile: Ném 403 FORBIDDEN khi người dùng vi phạm phân quyền cơ sở")
    void reconcile_forbidden_returns403() {
        NanopixReconciliationRequestDTO dto = NanopixReconciliationRequestDTO.builder()
                .profileId(200L)
                .visitId(500L)
                .build();

        when(nanopixXrayService.reconcileXray(any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền thao tác trên ca khám thuộc cơ sở khác."));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.reconcileXray(1L, dto, sessionWithLoggedInUser)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("cơ sở khác"));
    }

    public static void main(String[] args) {
        NanopixXrayRestControllerTest test = new NanopixXrayRestControllerTest();
        int passed = 0;
        int failed = 0;

        System.out.println("=================================================================");
        System.out.println(" RUNNING NanopixXrayRestControllerTest (API Security & Session)");
        System.out.println("=================================================================");

        for (java.lang.reflect.Method m : NanopixXrayRestControllerTest.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(org.junit.jupiter.api.Test.class)) {
                try {
                    test.setUp();
                    m.invoke(test);
                    String desc = m.isAnnotationPresent(org.junit.jupiter.api.DisplayName.class)
                            ? m.getAnnotation(org.junit.jupiter.api.DisplayName.class).value()
                            : m.getName();
                    System.out.println("  [PASS] " + m.getName() + ": " + desc);
                    passed++;
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    System.err.println("  [FAIL] " + m.getName() + ": " + cause.getMessage());
                    cause.printStackTrace();
                    failed++;
                }
            }
        }

        System.out.println("=================================================================");
        System.out.println(" API TEST SUMMARY: " + passed + " PASSED, " + failed + " FAILED");
        System.out.println("=================================================================");

        if (failed > 0) System.exit(1);
    }
}
