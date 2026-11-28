package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.User;
import com.cvdcms.service.CustomerCareNotificationService;
import com.cvdcms.service.CustomerCareService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST Controller cung cấp toàn bộ API JSON cho phân hệ Quản lý Chăm sóc khách hàng (CSKH - FT44 / UC-26).
 *
 * <p><b>Chức năng chính:</b>
 * <ul>
 *   <li>SCR-44.1: KPI Summary & Danh sách công việc CSKH phân trang (hỗ trợ tab Cần xử lý / Đã hoàn thành / Tất cả).</li>
 *   <li>SCR-44.2: Lọc theo chi nhánh, nhân viên phụ trách, bác sĩ được chuyển, trạng thái, hạn xử lý (Today/Overdue) và nhóm bệnh nhân.</li>
 *   <li>SCR-44.3: Lập lịch / Cập nhật / Phân công CSKH thủ công & Tự động tổng hợp công việc CSKH.</li>
 *   <li>SCR-44.4: Chi tiết công việc, ghi kết quả liên hệ (kèm hẹn gọi lại {@code nextRetryAt}) và xem tóm tắt khám lâm sàng / lời dặn bác sĩ.</li>
 *   <li>SCR-44.5: Chuyển Bác sĩ hỗ trợ chuyên môn (Escalate) & Bác sĩ gửi phản hồi chuyên môn (có thể chuyển lại lễ tân với {@code nextStatus=IN_PROGRESS} hoặc hoàn tất).</li>
 *   <li>SCR-44.6: Xem Lịch sử tương tác chung, Timeline lịch sử CSKH theo bệnh nhân, Lịch biểu (Calendar) và Thông báo CSKH.</li>
 *   <li>Mở lại công việc đã hoàn thành/hủy (chỉ dành cho {@code ADMIN}, có ghi nhận lý do & IP).</li>
 * </ul></p>
 *
 * <p><b>Được gọi từ:</b> Giao diện {@code static/js/customer-care.js} (trang {@code customer-care/list.html})
 * và fragment bác sĩ {@code templates/doctor/fragments/patient-care-tab.html}.</p>
 */
@RestController
@RequestMapping("/api/customer-care")
public class CustomerCareRestController {

    private final CustomerCareService customerCareService;

    @Autowired(required = false)
    private CustomerCareNotificationService notificationService;

    public CustomerCareRestController(CustomerCareService customerCareService) {
        this.customerCareService = customerCareService;
    }

    /**
     * Lấy danh sách chi nhánh mà người dùng hiện tại có quyền truy cập trong phân hệ CSKH cùng thông tin vai trò.
     *
     * <p><b>Đầu vào:</b> GET {@code /api/customer-care/branches}, phiên đăng nhập {@code session}.</p>
     * <p><b>Kết quả trả về:</b> Map gồm danh sách chi nhánh, vai trò CSKH, {@code currentStaffId}, {@code defaultBranchId}...</p>
     * <p><b>Được gọi từ:</b> {@code loadBranchesAndInit()} trong {@code customer-care.js} khi khởi tạo trang.</p>
     */
    @GetMapping("/branches")
    public Map<String, Object> getBranches(HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getAccessibleBranches(user.getUserId());
    }

    /**
     * Lấy dữ liệu danh mục phục vụ bộ lọc và biểu mẫu tạo/sửa/chuyển công việc CSKH theo chi nhánh.
     *
     * <p><b>Đầu vào:</b> GET {@code /api/customer-care/lookups?branchId=...}.</p>
     * <p><b>Kết quả trả về:</b> Map gồm danh sách nhân viên lễ tân/CSKH, bác sĩ, bệnh nhân, lượt khám, lịch hẹn, đơn thuốc hợp lệ.</p>
     * <p><b>Được gọi từ:</b> {@code loadLookups()} trong {@code customer-care.js} khi khởi tạo hoặc đổi chi nhánh.</p>
     */
    @GetMapping("/lookups")
    public Map<String, Object> getLookups(
            @RequestParam(required = false) Long branchId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getLookups(branchId, user.getUserId());
    }

    /**
     * Lấy số liệu tổng hợp KPI CSKH cho dải thẻ thống kê đầu trang.
     *
     * <p><b>Đầu vào:</b> GET {@code /api/customer-care/summary?branchId=...}.</p>
     * <p><b>Kết quả trả về:</b> Map số lượng công việc theo từng nhóm (tổng, đến hạn hôm nay, quá hạn, chờ bác sĩ, đã hoàn thành...).</p>
     * <p><b>Được gọi từ:</b> {@code loadSummary()} trong {@code customer-care.js}.</p>
     */
    @GetMapping("/summary")
    public Map<String, Object> getSummary(
            @RequestParam(required = false) Long branchId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getSummary(branchId, user.getUserId());
    }

    /**
     * Tìm kiếm và phân trang danh sách công việc CSKH theo nhiều tiêu chí lọc.
     *
     * <p><b>Đầu vào:</b> GET {@code /api/customer-care/tasks} với các tham số lọc: {@code branchId},
     * {@code tabGroup} (ACTIONABLE/COMPLETED/ALL), {@code status}, {@code assignedStaffId}, {@code escalatedToDoctorId},
     * {@code fromDate}, {@code toDate}, {@code keyword}, {@code dueFilter} (TODAY/OVERDUE/UPCOMING),
     * {@code careGroupFilter}, {@code doctorResponseFilter}, {@code myTasksOnly}, {@code page}, {@code size} (chuẩn hóa 10/25/50/100).</p>
     * <p><b>Kết quả trả về:</b> {@link Page} chứa danh sách {@link CustomerCareTaskDTO}.</p>
     * <p><b>Được gọi từ:</b> {@code loadTasks()} trong {@code customer-care.js}.</p>
     */
    @GetMapping("/tasks")
    public Page<CustomerCareTaskDTO> listTasks(
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "ACTIONABLE") String tabGroup,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long assignedStaffId,
            @RequestParam(required = false) Long escalatedToDoctorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dueFilter,
            @RequestParam(required = false) String careGroupFilter,
            @RequestParam(required = false) String doctorResponseFilter,
            @RequestParam(defaultValue = "false") Boolean myTasksOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpSession session) {

        User user = requireUser(session);
        // Chuẩn hóa pageSize (chấp nhận 10, 25, 50, 100) theo quy chuẩn MISA AMIS
        if (size != 10 && size != 25 && size != 50 && size != 100) {
            size = 10;
        }
        Pageable pageable = PageRequest.of(Math.max(0, page), size);
        return customerCareService.searchTasks(
                branchId, tabGroup, status, assignedStaffId, escalatedToDoctorId,
                fromDate, toDate, keyword, dueFilter, careGroupFilter,
                doctorResponseFilter, myTasksOnly, pageable, user.getUserId()
        );
    }

    /**
     * Lấy danh sách công việc CSKH trong khoảng thời gian để hiển thị trên chế độ xem Lịch (Calendar).
     *
     * <p><b>Đầu vào:</b> GET {@code /api/customer-care/calendar} kèm {@code branchId}, {@code startDate}, {@code endDate},
     * {@code assignedStaffId}, {@code escalatedToDoctorId}, {@code careGroupFilter}, {@code status}, {@code myTasksOnly}.</p>
     * <p><b>Kết quả trả về:</b> Danh sách {@link CustomerCareTaskDTO} trong khoảng ngày yêu cầu.</p>
     * <p><b>Được gọi từ:</b> {@code loadCalendar()} trong {@code customer-care.js}.</p>
     */
    @GetMapping("/calendar")
    public List<CustomerCareTaskDTO> calendarTasks(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long assignedStaffId,
            @RequestParam(required = false) Long escalatedToDoctorId,
            @RequestParam(required = false) String careGroupFilter,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") Boolean myTasksOnly,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getCalendarTasks(
                branchId, startDate, endDate, assignedStaffId, escalatedToDoctorId,
                careGroupFilter, status, myTasksOnly, user.getUserId()
        );
    }

    /**
     * Lấy danh sách thông báo CSKH gần nhất và số lượng chưa đọc của người dùng hiện tại.
     *
     * <p><b>Đầu vào:</b> GET {@code /api/customer-care/notifications?limit=30}.</p>
     * <p><b>Kết quả trả về:</b> Map gồm {@code unreadCount} và danh sách {@code notifications}.</p>
     * <p><b>Được gọi từ:</b> {@code loadNotifications()} trong {@code customer-care.js}.</p>
     */
    @GetMapping("/notifications")
    public Map<String, Object> getNotifications(
            @RequestParam(defaultValue = "30") int limit,
            HttpSession session) {
        User user = requireUser(session);
        customerCareService.requireCareRole(user.getUserId());
        if (notificationService == null) {
            return Map.of("unreadCount", 0, "notifications", List.of());
        }
        return notificationService.getUserNotifications(user.getUserId(), limit);
    }

    /**
     * Đánh dấu một thông báo CSKH cụ thể là đã đọc.
     *
     * <p><b>Đầu vào:</b> POST {@code /api/customer-care/notifications/{notificationId}/read}.</p>
     * <p><b>Kết quả trả về:</b> {@link CustomerCareNotificationDTO} sau khi cập nhật trạng thái đã đọc.</p>
     * <p><b>Được gọi từ:</b> Bảng thông báo trong {@code customer-care.js} khi người dùng click vào một thông báo.</p>
     */
    @PostMapping("/notifications/{notificationId}/read")
    public CustomerCareNotificationDTO markNotificationRead(
            @PathVariable Long notificationId,
            HttpSession session) {
        User user = requireUser(session);
        customerCareService.requireCareRole(user.getUserId());
        if (notificationService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ thông báo chưa sẵn sàng.");
        }
        return notificationService.markAsRead(notificationId, user.getUserId());
    }

    /**
     * Đánh dấu toàn bộ thông báo CSKH chưa đọc của người dùng hiện tại là đã đọc.
     *
     * <p><b>Đầu vào:</b> POST {@code /api/customer-care/notifications/read-all}.</p>
     * <p><b>Kết quả trả về:</b> Map chứa {@code updatedCount} và {@code unreadCount} (bằng 0).</p>
     * <p><b>Được gọi từ:</b> Nút "Đánh dấu tất cả đã đọc" trong {@code customer-care.js}.</p>
     */
    @PostMapping("/notifications/read-all")
    public Map<String, Object> markAllNotificationsRead(HttpSession session) {
        User user = requireUser(session);
        customerCareService.requireCareRole(user.getUserId());
        if (notificationService == null) {
            return Map.of("updatedCount", 0, "unreadCount", 0);
        }
        return notificationService.markAllAsRead(user.getUserId());
    }

    /**
     * Lấy thông tin chi tiết đầy đủ của một công việc CSKH (kèm danh sách tương tác liên quan).
     *
     * <p><b>Đầu vào:</b> GET {@code /api/customer-care/tasks/{taskId}}.</p>
     * <p><b>Kết quả trả về:</b> {@link CustomerCareTaskDTO} đầy đủ chi tiết.</p>
     * <p><b>Được gọi từ:</b> Các modal xem chi tiết, sửa công việc, ghi tương tác, chuyển bác sĩ và bác sĩ phản hồi trong {@code customer-care.js}.</p>
     */
    @GetMapping("/tasks/{taskId}")
    public CustomerCareTaskDTO getTaskDetail(
            @PathVariable Long taskId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getTaskDetail(taskId, user.getUserId());
    }

    /**
     * Lấy tóm tắt hồ sơ khám lâm sàng, chẩn đoán, đơn thuốc và lời dặn của bác sĩ theo lượt khám.
     *
     * <p><b>Đầu vào:</b> GET {@code /api/customer-care/visits/{visitId}/examination-summary}.</p>
     * <p><b>Kết quả trả về:</b> Map thông tin khám lâm sàng & lời dặn phục vụ tab tham khảo khi gọi CSKH.</p>
     * <p><b>Được gọi từ:</b> Modal ghi tương tác / xem chi tiết CSKH trong {@code customer-care.js}.</p>
     */
    @GetMapping("/visits/{visitId}/examination-summary")
    public Map<String, Object> getVisitExaminationSummary(
            @PathVariable Long visitId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getVisitExaminationSummary(visitId, user.getUserId());
    }

    /**
     * Tạo mới một công việc CSKH thủ công và phân công nhân viên xử lý.
     *
     * <p><b>Đầu vào:</b> POST {@code /api/customer-care/tasks} với body {@link CustomerCareCreateDTO}.</p>
     * <p><b>Kết quả trả về:</b> HTTP 201 CREATED cùng {@link CustomerCareTaskDTO} vừa tạo.</p>
     * <p><b>Được gọi từ:</b> {@code submitTaskForm()} trong {@code customer-care.js}.</p>
     */
    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerCareTaskDTO createTask(
            @RequestBody CustomerCareCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.createTask(dto, user.getUserId());
    }

    /**
     * Cập nhật thông tin hoặc phân công lại một công việc CSKH hiện có.
     *
     * <p><b>Đầu vào:</b> PUT {@code /api/customer-care/tasks/{taskId}} với body {@link CustomerCareCreateDTO}.</p>
     * <p><b>Kết quả trả về:</b> {@link CustomerCareTaskDTO} sau khi cập nhật.</p>
     * <p><b>Được gọi từ:</b> {@code submitTaskForm()} trong {@code customer-care.js} ở chế độ chỉnh sửa.</p>
     */
    @PutMapping("/tasks/{taskId}")
    public CustomerCareTaskDTO updateTask(
            @PathVariable Long taskId,
            @RequestBody CustomerCareCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.updateTask(taskId, dto, user.getUserId());
    }

    /**
     * Kích hoạt quét và tự động tổng hợp các công việc CSKH còn thiếu cho một chi nhánh (sau khám, sau đơn thuốc, lịch hẹn...).
     *
     * <p><b>Đầu vào:</b> POST {@code /api/customer-care/tasks/auto-generate?branchId=...}.</p>
     * <p><b>Kết quả trả về:</b> Map thống kê số công việc mới được tự động tạo.</p>
     * <p><b>Được gọi từ:</b> Nút "Tự động tổng hợp" trên thanh công cụ trong {@code customer-care.js}.</p>
     */
    @PostMapping("/tasks/auto-generate")
    public Map<String, Object> autoGenerateTasks(
            @RequestParam Long branchId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.autoGenerateTasks(branchId, user.getUserId());
    }

    /**
     * Ghi nhận một lần tương tác/liên hệ CSKH cho công việc cụ thể (kèm kết quả liên hệ, hẹn gọi lại {@code nextRetryAt} hoặc đóng task).
     *
     * <p><b>Đầu vào:</b> POST {@code /api/customer-care/tasks/{taskId}/interactions} với body {@link CustomerCareInteractionCreateDTO}.</p>
     * <p><b>Kết quả trả về:</b> HTTP 201 CREATED cùng {@link CustomerCareTaskDTO} đã cập nhật trạng thái và lịch sử tương tác.</p>
     * <p><b>Được gọi từ:</b> {@code submitInteractionForm()} trong {@code customer-care.js}.</p>
     */
    @PostMapping("/tasks/{taskId}/interactions")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerCareTaskDTO recordInteraction(
            @PathVariable Long taskId,
            @RequestBody CustomerCareInteractionCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.recordInteraction(taskId, dto, user.getUserId());
    }

    /**
     * Ghi nhận tương tác CSKH trực tiếp từ màn hình chi tiết lượt khám (tự tạo hoặc gắn vào task CSKH tương ứng của lượt khám).
     *
     * <p><b>Đầu vào:</b> POST {@code /api/customer-care/visits/{visitId}/interactions} với body {@link CustomerCareInteractionCreateDTO}.</p>
     * <p><b>Kết quả trả về:</b> {@link ResponseEntity} chứa {@code success}, {@code message} và {@code task} ({@link CustomerCareTaskDTO}).</p>
     * <p><b>Được gọi từ:</b> {@code submitDoctorCareInteraction()} trong {@code templates/doctor/fragments/patient-care-tab.html}.</p>
     */
    @PostMapping("/visits/{visitId}/interactions")
    public ResponseEntity<Map<String, Object>> recordVisitInteraction(
            @PathVariable Long visitId,
            @RequestBody CustomerCareInteractionCreateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        CustomerCareTaskDTO taskDTO = customerCareService.recordVisitInteraction(visitId, dto, user.getUserId());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Đã lưu lịch sử cuộc gọi / tương tác CSKH thành công!",
                "task", taskDTO
        ));
    }

    /**
     * Chuyển công việc CSKH lên Bác sĩ để hỗ trợ tư vấn/giải đáp chuyên môn (Escalate).
     *
     * <p><b>Đầu vào:</b> POST {@code /api/customer-care/tasks/{taskId}/escalate} với body {@link CustomerCareEscalateDTO}
     * (chứa {@code escalatedToDoctorId} và {@code escalationNote}).</p>
     * <p><b>Kết quả trả về:</b> {@link CustomerCareTaskDTO} sau khi chuyển sang trạng thái {@code ESCALATED}.</p>
     * <p><b>Được gọi từ:</b> {@code submitEscalateForm()} trong {@code customer-care.js}.</p>
     */
    @PostMapping("/tasks/{taskId}/escalate")
    public CustomerCareTaskDTO escalateToDoctor(
            @PathVariable Long taskId,
            @RequestBody CustomerCareEscalateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.escalateToDoctor(taskId, dto, user.getUserId());
    }

    /**
     * Bác sĩ gửi phản hồi chuyên môn cho công việc CSKH đã được chuyển lên.
     *
     * <p><b>Đầu vào:</b> POST {@code /api/customer-care/tasks/{taskId}/doctor-response} với body {@link CustomerCareEscalateDTO}
     * (chứa {@code doctorResponse} và {@code nextStatus}: {@code IN_PROGRESS} để chuyển lại lễ tân gọi tiếp hoặc {@code COMPLETED} để hoàn tất).</p>
     * <p><b>Kết quả trả về:</b> {@link CustomerCareTaskDTO} đã cập nhật phản hồi và trạng thái mới.</p>
     * <p><b>Được gọi từ:</b> {@code submitDoctorResponseForm()} trong {@code customer-care.js}.</p>
     */
    @PostMapping("/tasks/{taskId}/doctor-response")
    public CustomerCareTaskDTO submitDoctorResponse(
            @PathVariable Long taskId,
            @RequestBody CustomerCareEscalateDTO dto,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.submitDoctorResponse(taskId, dto, user.getUserId());
    }

    /**
     * Mở lại một công việc CSKH đã hoàn thành hoặc đã hủy (chỉ cho phép vai trò {@code ADMIN}).
     *
     * <p><b>Đầu vào:</b> POST {@code /api/customer-care/tasks/{taskId}/reopen} với body JSON tùy chọn {@code {"reason": "..."}};
     * ghi nhận địa chỉ IP từ {@code HttpServletRequest} để lưu audit.</p>
     * <p><b>Kết quả trả về:</b> {@link CustomerCareTaskDTO} sau khi mở lại về trạng thái đang xử lý.</p>
     * <p><b>Được gọi từ:</b> {@code submitReopenForm()} trong {@code customer-care.js}.</p>
     */
    @PostMapping("/tasks/{taskId}/reopen")
    public CustomerCareTaskDTO reopenTask(
            @PathVariable Long taskId,
            @RequestBody(required = false) Map<String, String> body,
            jakarta.servlet.http.HttpServletRequest request,
            HttpSession session) {
        User user = requireUser(session);
        String reason = body != null ? body.get("reason") : null;
        String ip = request != null ? request.getRemoteAddr() : null;
        return customerCareService.reopenTask(taskId, reason, user.getUserId(), ip);
    }

    /**
     * Tìm kiếm và phân trang lịch sử tương tác CSKH chung theo chi nhánh, bệnh nhân, khoảng thời gian, nhân viên, bác sĩ, kênh và kết quả.
     *
     * <p><b>Đầu vào:</b> GET {@code /api/customer-care/interactions} với các bộ lọc tùy chọn và tham số phân trang {@code page}, {@code size}.</p>
     * <p><b>Kết quả trả về:</b> {@link Page} chứa danh sách {@link CustomerCareInteractionDTO}.</p>
     * <p><b>Được gọi từ:</b> {@code loadGeneralHistory()} trong {@code customer-care.js} (tab Lịch sử tương tác).</p>
     */
    @GetMapping("/interactions")
    public Page<CustomerCareInteractionDTO> listInteractions(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String timeRange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long staffId,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String contactResult,
            @RequestParam(required = false) String careGroupFilter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            HttpSession session) {
        User user = requireUser(session);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        return customerCareService.searchInteractions(
                branchId, profileId, keyword, timeRange, fromDate, toDate,
                staffId, doctorId, channel, contactResult, careGroupFilter,
                pageable, user.getUserId()
        );
    }

    /**
     * Lấy dòng thời gian (Timeline) toàn bộ công việc và lịch sử tương tác CSKH của một bệnh nhân.
     *
     * <p><b>Đầu vào:</b> GET {@code /api/customer-care/patients/{profileId}/history?branchId=...}.</p>
     * <p><b>Kết quả trả về:</b> Map gồm thông tin bệnh nhân, danh sách task CSKH và danh sách tương tác theo thời gian.</p>
     * <p><b>Được gọi từ:</b> {@code openPatientHistoryDrawer()} trong {@code customer-care.js}.</p>
     */
    @GetMapping("/patients/{profileId}/history")
    public Map<String, Object> getPatientCareHistory(
            @PathVariable Long profileId,
            @RequestParam(required = false) Long branchId,
            HttpSession session) {
        User user = requireUser(session);
        return customerCareService.getPatientCareHistory(profileId, branchId, user.getUserId());
    }

    /**
     * Kiểm tra phiên đăng nhập hiện tại và trả về đối tượng {@link User}; nếu chưa đăng nhập sẽ ném lỗi 401 UNAUTHORIZED.
     */
    private User requireUser(HttpSession session) {
        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Phiên đăng nhập không hợp lệ. Vui lòng đăng nhập lại."
            );
        }
        return user;
    }

    /**
     * Bắt lỗi tham số đầu vào không hợp lệ ({@link IllegalArgumentException}) và trả về HTTP 400 kèm thông báo JSON.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    /**
     * Bắt lỗi nghiệp vụ/phân quyền ({@link ResponseStatusException}) và trả về đúng HTTP status cùng thông báo JSON.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu CSKH.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }
}
