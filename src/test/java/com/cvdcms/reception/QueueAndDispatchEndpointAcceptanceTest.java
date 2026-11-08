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
                .andExpect(redirectedUrl("/reception/dispatch?branchId=1"))
                .andExpect(flash().attributeExists("notice", "issuedTicketNumber"));
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

    @Test
    @DisplayName("UI/DOM Verification 1: Kiểm tra nút Đổi Bác sĩ đã gỡ bỏ onclick stopPropagation và mở dispatchTransferModal")
    void testTransferDoctorButtonAndModalDomStructure() throws Exception {
        String dispatchHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/dispatch.html"));
        String dispatchJs = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/static/js/reception-dispatch.js"));

        org.junit.jupiter.api.Assertions.assertFalse(
                dispatchHtml.contains("class=\"dv-actions-cell-always\" onclick=\"event.stopPropagation()\""),
                "Đã gỡ bỏ hoàn toàn onclick=\"event.stopPropagation()\" tại .dv-actions-cell-always");
        org.junit.jupiter.api.Assertions.assertFalse(
                dispatchHtml.contains("onclick=\"event.stopPropagation()\""),
                "Không còn onclick=\"event.stopPropagation()\" gây chặn event delegation");
        org.junit.jupiter.api.Assertions.assertTrue(
                dispatchHtml.contains("btn-open-transfer") && dispatchHtml.contains("data-bs-target=\"#dispatchTransferModal\""),
                "Nút .btn-open-transfer phải có data-bs-toggle=\"modal\" và data-bs-target=\"#dispatchTransferModal\"");
        org.junit.jupiter.api.Assertions.assertTrue(
                dispatchJs.contains("e.target.closest('.btn-open-transfer')") && dispatchJs.contains("handleOpenTransfer"),
                "JS event delegation bắt đúng .btn-open-transfer để mở modal #dispatchTransferModal");
    }

    @Test
    @DisplayName("UI/DOM Verification 2: Kiểm tra Modal In phiếu nhiệt K80 hiển thị Phòng khám: [Tên phòng] và không reload trang")
    void testK80ThermalPrintModalStructureAndNoReload() throws Exception {
        String dispatchHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/dispatch.html"));
        String examTicketHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/exam-ticket.html"));
        String dispatchJs = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/static/js/reception-dispatch.js"));

        org.junit.jupiter.api.Assertions.assertTrue(
                dispatchHtml.contains("id=\"k80TicketModal\"") && dispatchHtml.contains("Phòng khám:"),
                "Modal K80 phải hiển thị vị trí 'Phòng khám: [Tên phòng]' thay vì 'Cửa số'");
        org.junit.jupiter.api.Assertions.assertFalse(
                dispatchHtml.contains("Cửa số"),
                "Bản in K80 không dùng 'Cửa số'");
        org.junit.jupiter.api.Assertions.assertTrue(
                dispatchHtml.contains("id=\"k80PatientCode\"")
                        && dispatchHtml.contains("id=\"k80PatientName\"")
                        && dispatchHtml.contains("id=\"k80DoctorName\"")
                        && dispatchHtml.contains("id=\"k80ChairName\"")
                        && dispatchHtml.contains("id=\"k80TicketNumber\""),
                "Modal K80 phải có đầy đủ Mã BN, Tên BN, Bác sĩ, Ghế và Số phiếu (0x hoặc P-xx)");
        org.junit.jupiter.api.Assertions.assertTrue(
                examTicketHtml.contains("Phòng khám:"),
                "Trang exam-ticket.html cũng hiển thị chuẩn 'Phòng khám:'");
        org.junit.jupiter.api.Assertions.assertTrue(
                dispatchJs.contains("printK80ReceiptInPlace") && dispatchJs.contains("k80HiddenPrintFrame"),
                "Thao tác in K80 thực hiện qua hidden iframe tại chỗ, không gây reload hay redirect làm mất trạng thái trang");
    }

    @Test
    @DisplayName("UI/DOM Verification 3: Kiểm tra bảng Lịch sử tiếp đón mở Drawer chi tiết và filter chip 2 chiều")
    void testReceptionHistoryDrawerAndTwoWayFilterChips() throws Exception {
        String checkInHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/check-in.html"));
        String dispatchJs = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/static/js/reception-dispatch.js"));

        org.junit.jupiter.api.Assertions.assertTrue(
                checkInHtml.contains("id=\"receptionHistoryTable\"")
                        && checkInHtml.contains("reception-history-row")
                        && checkInHtml.contains("id=\"receptionDetailDrawer\""),
                "Bảng lịch sử tiếp đón có hàng .reception-history-row và Drawer #receptionDetailDrawer");
        org.junit.jupiter.api.Assertions.assertTrue(
                checkInHtml.contains("e.target.closest('button, a, input, select, textarea, label, form, .no-row-click')"),
                "Click vào hàng (trừ checkbox/action cụ thể) mở đúng Drawer chi tiết tiếp đón");
        org.junit.jupiter.api.Assertions.assertTrue(
                checkInHtml.contains("btn-remove-chip") && checkInHtml.contains("filter-chip")
                        && dispatchJs.contains("btn-remove-chip"),
                "Filter tag / badge hoạt động hai chiều: bấm xóa chip x làm reset đúng trường lọc tương ứng");
    }

    @Test
    @DisplayName("UI/DOM Verification 4: Kiểm tra toàn bộ nâng cấp UI Tiếp đón, Điều phối, Kanban Hàng chờ và Màn hình gọi số Basic White")
    void testRefinedUiAcrossCheckInDispatchQueueAndTvScreens() throws Exception {
        String dispatchHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/dispatch.html"));
        String checkInHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/check-in.html"));
        String queueHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/queue.html"));
        String queueRowsHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/queue-rows.html"));
        String queueTvHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/queue-tv.html"));

        // 1. Điều phối: Bỏ thanh search (#dispatchToolbarCard), bỏ nút Hàng chờ lặp lại ở card bác sĩ, bỏ icon tròn trước tên bác sĩ
        org.junit.jupiter.api.Assertions.assertFalse(
                dispatchHtml.contains("id=\"dispatchToolbarCard\""),
                "Đã bỏ thanh search (#dispatchToolbarCard) trên màn Điều phối ghế nha");
        org.junit.jupiter.api.Assertions.assertFalse(
                dispatchHtml.contains("Xem Hàng chờ gọi số"),
                "Đã bỏ nút lặp Hàng chờ gọi số trên màn Điều phối");

        // 2. Tiếp đón & Check-in: 2 bảng có chỉnh kích cỡ cột + nút thao tác động, Modal chi tiết ở giữa chỉ có nút Thoát, Live Search vãng lai
        org.junit.jupiter.api.Assertions.assertTrue(
                checkInHtml.contains("setupUniversalEnterpriseTable")
                        && checkInHtml.contains("dv-floating-actions-bar")
                        && checkInHtml.contains("dv-th-inner"),
                "Hai bảng ở Tiếp đón & Check-in có chỉnh kích cỡ cột và nút thao tác động giống bên Điều phối");
        org.junit.jupiter.api.Assertions.assertTrue(
                checkInHtml.contains("modal-dialog-centered") && checkInHtml.contains("Thoát"),
                "Chi tiết lượt tiếp đón hiển thị Modal ở giữa màn hình với nút Thoát");
        org.junit.jupiter.api.Assertions.assertFalse(
                checkInHtml.contains("Tìm hồ sơ") || checkInHtml.contains("id=\"emergencySwitch\"") || checkInHtml.contains("id=\"prioritySwitch\""),
                "Khối vãng lai dùng Live Search, đã bỏ nút Tìm hồ sơ và bỏ nút ưu tiên / cấp cứu");
        org.junit.jupiter.api.Assertions.assertTrue(
                checkInHtml.contains("/reception/check-in/patients/search?query="),
                "Khối vãng lai tích hợp Live Search tự động gợi ý hồ sơ");

        // 3. Hàng chờ gọi số: Xóa bảng hàng chờ và toolbar lọc, giữ Kanban với tiêu đề 'Kanban hàng chờ gọi số' & nút 'Màn hình gọi số' bên phải
        org.junit.jupiter.api.Assertions.assertFalse(
                queueRowsHtml.contains("id=\"viewQueueTableSection\""),
                "Đã xóa phần Bảng hàng chờ, chỉ giữ Kanban");
        org.junit.jupiter.api.Assertions.assertTrue(
                queueHtml.contains("Kanban hàng chờ gọi số") && queueHtml.contains("Màn hình gọi số"),
                "Đặt tiêu đề 'Kanban hàng chờ gọi số' và sát góc phải là nút 'Màn hình gọi số'");
        org.junit.jupiter.api.Assertions.assertFalse(
                queueRowsHtml.contains("name=\"chairId\""),
                "Đã bỏ dropdown chỉnh sửa ghế điều trị ở cột Đang gọi để nút Bắt đầu khám không tràn khỏi block");
        org.junit.jupiter.api.Assertions.assertFalse(
                queueRowsHtml.contains("${t.visit.visitCode}"),
                "Card hàng chờ chỉ giữ tên bệnh nhân và mã bệnh nhân, đã bỏ mã code visitCode hiển thị");

        // 4. Màn hình gọi số: Màu trắng basic, bản list đơn giản với Số thứ tự, Số phiếu, Mã bệnh nhân, Tên bệnh nhân, Số điện thoại
        org.junit.jupiter.api.Assertions.assertTrue(
                queueTvHtml.contains("background: #ffffff")
                        && queueTvHtml.contains("Số thứ tự")
                        && queueTvHtml.contains("Số phiếu")
                        && queueTvHtml.contains("Mã bệnh nhân")
                        && queueTvHtml.contains("Tên bệnh nhân")
                        && queueTvHtml.contains("Số điện thoại"),
                "Màn hình gọi số thiết kế màu trắng basic, trình bày dạng bảng list với đủ 5 cột yêu cầu");
    }

    @Test
    @DisplayName("UI/DOM Verification 5: Kiểm tra đồng nhất Stepper 4 tab, Padding vãng lai, Modal Thêm hồ sơ bệnh nhân mới & khóa Hoàn tác")
    void testStepperUniformityWalkInPaddingQuickAddPatientModalAndRevertLock() throws Exception {
        String aptHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/appointments/dashboard.html"));
        String checkInHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/check-in.html"));
        String dispatchHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/dispatch.html"));
        String queueHtml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/reception/queue.html"));

        // 1. Tất cả 4 màn hình đều dùng chung class .reception-step-pill và nạp reception-workflow-navigation.css
        for (String html : List.of(aptHtml, checkInHtml, dispatchHtml, queueHtml)) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    html.contains("reception-step-pill") && html.contains("reception-workflow-navigation.css"),
                    "Tất cả 4 màn hình quy trình tiếp đón đều đồng nhất class .reception-step-pill và stylesheet");
        }

        // 2. Khối vãng lai có padding p-4 thoáng đều hai bên lề
        org.junit.jupiter.api.Assertions.assertTrue(
                checkInHtml.contains("id=\"bodyWalkInCard\"") && checkInHtml.contains("walkin-form-spacious p-4"),
                "Khối Khách vãng lai (#bodyWalkInCard) có padding thoáng đều hai bên lề");

        // 3. Modal Thêm Hồ Sơ Bệnh Nhân Mới (#addPatientModal) tích hợp trực tiếp tại Tiếp đón & gọi POST /patients/quick-create
        org.junit.jupiter.api.Assertions.assertTrue(
                checkInHtml.contains("id=\"addPatientModal\"")
                        && checkInHtml.contains("Thêm Hồ Sơ Bệnh Nhân Mới")
                        && checkInHtml.contains("Lưu hồ sơ bệnh nhân")
                        && checkInHtml.contains("/patients/quick-create"),
                "Modal Thêm Hồ Sơ Bệnh Nhân Mới tại chỗ cho phép tạo mới bệnh nhân và chọn ngay để nhập triệu chứng tiếp đón");

        // 4. Hoàn tác check-in kiểm tra a.canRevert và hiển thị trạng thái Đã điều phối khi khóa
        org.junit.jupiter.api.Assertions.assertTrue(
                checkInHtml.contains("th:if=\"${a.canRevert}\"") && checkInHtml.contains("Đã điều phối"),
                "Chỉ cho phép bấm Hoàn tác khi a.canRevert == true, khóa hoàn tác khi đã chuyển sang điều phối / gọi số");
    }
}
