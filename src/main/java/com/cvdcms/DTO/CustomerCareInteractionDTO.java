package com.cvdcms.DTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * DTO trả về thông tin chi tiết một lần tương tác chăm sóc khách hàng (SCR-44.4 & SCR-44.6 Timeline).
 *
 * <p><b>Chức năng:</b> Hiển thị lịch sử cuộc gọi/tin nhắn trong chi tiết task, tab Lịch sử tương tác chung,
 * drawer Timeline theo bệnh nhân và tab CSKH trong màn hình khám bệnh của Bác sĩ.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerCareInteractionDTO {
    private Long interactionId;
    private Long taskId;
    private String taskCode;
    private String taskType;
    private String careGroupLabel;
    private String taskStatus;

    private Long profileId;
    private String patientCode;
    private String patientName;
    private String patientPhone;

    private Long branchId;
    private String branchName;

    private Long staffId;
    private String staffCode;
    private String staffName;
    private String staffPosition;

    private String channel;
    private String channelLabel;

    private String contactResult;
    private String contactResultLabel;

    private String notes;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;

    /**
     * Lấy tiêu đề hiển thị cho lần tương tác (ưu tiên tên nhóm chăm sóc, fallback sang loại công việc hoặc mặc định).
     */
    public String getTaskTitle() {
        return careGroupLabel != null && !careGroupLabel.isBlank() ? careGroupLabel : (taskType != null && !taskType.isBlank() ? taskType : "Cuộc gọi CSKH");
    }

    /**
     * Trả về thời điểm diễn ra tương tác (alias của {@code createdAt} phục vụ template/UI).
     */
    public LocalDateTime getInteractionDate() {
        return createdAt;
    }

    /**
     * Trả về nhãn kết quả liên hệ thân thiện tiếng Việt (fallback về mã {@code contactResult} nếu chưa có nhãn).
     */
    public String getResultStatus() {
        return contactResultLabel != null && !contactResultLabel.isBlank() ? contactResultLabel : contactResult;
    }
}
