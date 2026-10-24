package com.cvdcms.DTO;

import com.cvdcms.entity.enums.PreferredSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReExaminationRequestDTO {

    private Long patientProfileId;
    private Long visitId;
    private Long branchId;
    private Long doctorId;
    private Long serviceId;

    private LocalDate appointmentDate;
    private PreferredSession preferredSession;
    private String expectedTime; // e.g. "09:30"

    private String followupReason; // Lý do tái khám / dặn dò
    private boolean createCareTask; // Có tạo nhiệm vụ CSKH nhắc hẹn không
    private String careTaskNotes;   // Ghi chú cho lễ tân/CSKH
}
