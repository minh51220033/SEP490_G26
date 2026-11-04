package com.cvdcms.reception;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.QueueTicket;
import com.cvdcms.entity.enums.QueueStatus;
import com.cvdcms.entity.enums.QueueType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UI Acceptance & Endpoint Test Suite for FT08-FT10:
 * - Dispatch endpoints (assign, issue ticket, print, board)
 * - Queue board endpoints (main board, rows fragment, call-next, transition actions)
 * - TV public screen endpoints (HTML view and PII-free JSON endpoint)
 */
@ExtendWith(MockitoExtension.class)
class QueueAndDispatchEndpointAcceptanceTest {

    @Mock
    private QueueWorkflowService queueWorkflowService;

    @Mock
    private DispatchService dispatchService;

    private MockMvc queueMockMvc;
    private MockMvc dispatchMockMvc;

    private Branch branch;

    @BeforeEach
    void setUp() {
        QueueWorkflowController queueController = new QueueWorkflowController(queueWorkflowService);
        DispatchController dispatchController = new DispatchController(dispatchService);

        queueMockMvc = MockMvcBuilders.standaloneSetup(queueController).build();
        dispatchMockMvc = MockMvcBuilders.standaloneSetup(dispatchController).build();

        branch = new Branch();
        branch.setBranchId(1L);
        branch.setBranchName("Cơ sở Quận 1");
        branch.setIsActive(true);
    }

    @Test
    @DisplayName("Endpoint 1: GET /reception/dispatch nạp view và board dữ liệu")
    void dispatchPageRenders() throws Exception {
        var board = new DispatchService.Board(1L, List.of(branch), List.of(), List.of(), List.of(), List.of(), List.of(),
                LocalDate.now(), Map.of(), Map.of());
        when(dispatchService.board(any(), eq(1L))).thenReturn(board);

        dispatchMockMvc.perform(get("/reception/dispatch").param("branchId", "1")
                        .sessionAttr("loggedInUser", new com.cvdcms.entity.User()))
                .andExpect(status().isOk())
                .andExpect(view().name("reception/dispatch"))
                .andExpect(model().attributeExists("board"));
    }

    @Test
    @DisplayName("Endpoint 2: POST /reception/dispatch/{id}/assign phân công bác sĩ và chuyển hướng với thông báo")
    void assignDoctorEndpoint() throws Exception {
        dispatchMockMvc.perform(post("/reception/dispatch/10/assign")
                        .param("branchId", "1")
                        .param("doctorId", "5")
                        .param("roomId", "3")
                        .param("chairId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reception/dispatch?branchId=1"))
                .andExpect(flash().attribute("notice", "Đã lưu phân công. Bệnh nhân tiếp tục chờ theo bác sĩ được phân."));

        verify(dispatchService).assign(any(), eq(1L), eq(10L), eq(5L), eq(3L), eq(7L), isNull());
    }

    @Test
    @DisplayName("Endpoint 3: POST /reception/dispatch/{id}/ticket cấp số khám và thông báo")
    void issueTicketEndpoint() throws Exception {
        QueueTicket ticket = new QueueTicket();
        ticket.setTicketId(10L);
        ticket.setDisplayNumber("01");
        when(dispatchService.issue(any(), eq(1L), eq(10L), eq(false), any())).thenReturn(ticket);

        dispatchMockMvc.perform(post("/reception/dispatch/10/ticket")
                        .param("branchId", "1")
                        .param("priority", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reception/dispatch/tickets/10"));
    }

    @Test
    @DisplayName("Endpoint 4: GET /reception/queue nạp view board hàng chờ khám")
    void queuePageRenders() throws Exception {
        var board = new QueueWorkflowService.Board(1L, List.of(branch), List.of(), List.of(), List.of());
        when(queueWorkflowService.board(any(), eq(1L))).thenReturn(board);

        queueMockMvc.perform(get("/reception/queue").param("branchId", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("reception/queue"))
                .andExpect(model().attributeExists("board", "lanes", "laneCounts"));
    }

    @Test
    @DisplayName("Endpoint 5: GET /reception/queue/rows nạp fragment hàng chờ phục vụ auto-refresh")
    void queueRowsFragmentRenders() throws Exception {
        var board = new QueueWorkflowService.Board(1L, List.of(branch), List.of(), List.of(), List.of());
        when(queueWorkflowService.board(any(), eq(1L))).thenReturn(board);

        queueMockMvc.perform(get("/reception/queue/rows").param("branchId", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("reception/queue-rows :: rows"));
    }

    @Test
    @DisplayName("Endpoint 6: POST /reception/queue/doctors/{id}/call-next gọi số tiếp theo theo bác sĩ")
    void callNextEndpoint() throws Exception {
        QueueTicket ticket = new QueueTicket();
        ticket.setDisplayNumber("P-01");
        when(queueWorkflowService.callNext(any(), eq(1L), eq(2L))).thenReturn(ticket);

        queueMockMvc.perform(post("/reception/queue/doctors/2/call-next").param("branchId", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reception/queue?branchId=1"))
                .andExpect(flash().attribute("notice", "Đã gọi số P-01."));
    }

    @Test
    @DisplayName("Endpoint 7: POST /reception/queue/{id}/PAUSE tạm dừng số kèm lý do")
    void pauseTicketEndpoint() throws Exception {
        queueMockMvc.perform(post("/reception/queue/15/PAUSE")
                        .param("branchId", "1")
                        .param("reason", "Bệnh nhân tạm ra ngoài đi vệ sinh"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reception/queue?branchId=1"))
                .andExpect(flash().attribute("notice", "Đã tạm dừng số khám."));

        verify(queueWorkflowService).transition(any(), eq(15L), eq(QueueWorkflowService.Action.PAUSE), isNull(), eq("Bệnh nhân tạm ra ngoài đi vệ sinh"));
    }

    @Test
    @DisplayName("Endpoint 8: POST /reception/queue/{id}/START bắt đầu khám kèm đổi ghế")
    void startWithDynamicChairEndpoint() throws Exception {
        queueMockMvc.perform(post("/reception/queue/15/START")
                        .param("branchId", "1")
                        .param("chairId", "22"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reception/queue?branchId=1"))
                .andExpect(flash().attribute("notice", "Đã bắt đầu khám."));

        verify(queueWorkflowService).transition(any(), eq(15L), eq(QueueWorkflowService.Action.START), eq(22L), isNull());
    }

    @Test
    @DisplayName("Endpoint 9: GET /reception/queue/tv/{id} nạp màn hình TV công cộng")
    void tvScreenViewRenders() throws Exception {
        var publicBoard = new QueueWorkflowService.PublicBoard("Cơ sở Quận 1", List.of(), List.of(), List.of(), 3);
        when(queueWorkflowService.publicBoard(1L)).thenReturn(publicBoard);

        queueMockMvc.perform(get("/reception/queue/tv/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("reception/queue-tv"))
                .andExpect(model().attributeExists("board", "branchId"));
    }

    @Test
    @DisplayName("Endpoint 10: GET /reception/queue/tv/{id}/data trả JSON chỉ chứa số và vị trí (không có PII bệnh nhân)")
    void tvDataEndpointExcludesPatientPii() throws Exception {
        var publicBoard = new QueueWorkflowService.PublicBoard("Cơ sở Quận 1",
                List.of(new QueueWorkflowService.PublicNumber("P-01", "BS. Nguyễn Văn A", "Phòng 101", "Ghế 1")),
                List.of(new QueueWorkflowService.PublicNumber("02", "BS. Trần Thị B", "Phòng 102", "Ghế 2")),
                List.of("03 · BS. Nguyễn Văn A"),
                4);
        when(queueWorkflowService.publicBoard(1L)).thenReturn(publicBoard);

        queueMockMvc.perform(get("/reception/queue/tv/1/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value("Cơ sở Quận 1"))
                .andExpect(jsonPath("$.waiting").value(4))
                .andExpect(jsonPath("$.called[0].number").value("P-01"))
                .andExpect(jsonPath("$.called[0].doctor").value("BS. Nguyễn Văn A"))
                .andExpect(jsonPath("$.called[0].room").value("Phòng 101"))
                .andExpect(jsonPath("$.called[0].chair").value("Ghế 1"))
                // Ensure no patient PII fields exist
                .andExpect(jsonPath("$.patient").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.cccd").doesNotExist());
    }
}
