package com.cvdcms.service;

import com.cvdcms.DTO.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Map;

/**
 * Service xử lý nghiệp vụ Quản lý Chăm sóc khách hàng (FT44 - UC-26).
 */
public interface CustomerCareService {

    /**
     * Kiểm tra và trả về vai trò truy cập phân hệ CSKH của tài khoản ('ADMIN', 'LE_TAN', 'BAC_SI').
     * Ném lỗi 403 FORBIDDEN nếu tài khoản thuộc vai trò khác (Bệnh nhân, Quản lý kho, Điều dưỡng...).
     */
    String requireCareRole(Long userId);

    /**
     * Lấy danh sách chi nhánh được phép truy cập của người dùng đăng nhập.
     * - ADMIN: toàn bộ các cơ sở đang hoạt động (cho phép chuyển đổi cơ sở).
     * - LE_TAN / BAC_SI: chỉ cơ sở mà nhân viên/bác sĩ đó đang công tác.
     */
    Map<String, Object> getAccessibleBranches(Long userId);

    /**
     * Lấy danh mục dữ liệu phục vụ bộ lọc và modal (nhân viên CSKH, bác sĩ, bệnh nhân, ca khám, lịch hẹn, đơn thuốc)
     * theo đúng cơ sở được phân quyền.
     */
    Map<String, Object> getLookups(Long branchId, Long userId);

    /**
     * Lấy các chỉ số KPI tổng quan và số lượng công việc theo từng nhóm phân loại (SCR-44.1 & SCR-44.2).
     */
    Map<String, Object> getSummary(Long branchId, Long userId);

    /**
     * Tìm kiếm, lọc và phân trang danh sách công việc CSKH theo nhóm, trạng thái, nhân viên, bác sĩ, ngày hạn và từ khóa.
     */
    Page<CustomerCareTaskDTO> searchTasks(
            Long branchId,
            String tabGroup,
            String status,
            Long assignedStaffId,
            Long escalatedToDoctorId,
            LocalDate fromDate,
            LocalDate toDate,
            String keyword,
            Pageable pageable,
            Long userId
    );

    /**
     * Xem chi tiết một công việc CSKH kèm lịch sử tương tác (SCR-44.4).
     */
    CustomerCareTaskDTO getTaskDetail(Long taskId, Long userId);

    /**
     * Lập lịch / tạo mới công việc CSKH thủ công (SCR-44.3).
     */
    CustomerCareTaskDTO createTask(CustomerCareCreateDTO dto, Long userId);

    /**
     * Cập nhật thông tin phân công / thời hạn / ghi chú của công việc CSKH (SCR-44.3).
     */
    CustomerCareTaskDTO updateTask(Long taskId, CustomerCareCreateDTO dto, Long userId);

    /**
     * Tự động tổng hợp công việc CSKH từ ca khám hoàn thành, đơn thuốc và lịch hẹn tại chi nhánh (SCR-44.3).
     */
    Map<String, Object> autoGenerateTasks(Long branchId, Long userId);

    /**
     * Ghi nhận kết quả liên hệ bệnh nhân và đặt lịch hẹn gọi lại khi máy bận / không nghe máy (SCR-44.4).
     */
    CustomerCareTaskDTO recordInteraction(Long taskId, CustomerCareInteractionCreateDTO dto, Long userId);

    /**
     * Chuyển công việc CSKH cho Bác sĩ hỗ trợ chuyên môn (SCR-44.5).
     */
    CustomerCareTaskDTO escalateToDoctor(Long taskId, CustomerCareEscalateDTO dto, Long userId);

    /**
     * Bác sĩ nhập phản hồi chuyên môn cho ca CSKH được chuyển đến (SCR-44.5).
     */
    CustomerCareTaskDTO submitDoctorResponse(Long taskId, CustomerCareEscalateDTO dto, Long userId);

    /**
     * Lấy toàn bộ lịch sử tương tác và công việc chăm sóc của một bệnh nhân theo dạng Timeline (SCR-44.6).
     */
    Map<String, Object> getPatientCareHistory(Long profileId, Long branchId, Long userId);
}
