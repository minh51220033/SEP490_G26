package com.cvdcms.DTO;

import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.CustomerCareTaskStatus;
import com.cvdcms.entity.enums.PreferredSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReExaminationResponseDTO {

    private Long appointmentId;
    private String appointmentCode;

    // Patient info
    private Long patientId;
    private String patientCode;
    private String patientName;
    private String phoneNumber;
    private String gender;
    private LocalDate dob;
    private Integer age;

    // Branch & Doctor
    private Long branchId;
    private String branchName;
    private Long doctorId;
    private String doctorName;

    // Service & Appointment details
    private Long serviceId;
    private String serviceName;
    private LocalDate appointmentDate;
    private PreferredSession preferredSession;
    private LocalTime expectedTime;
    private String initialSymptoms; // Lý do tái khám
    private AppointmentStatus status;
    private LocalDateTime createdAt;

    // Care task info
    private boolean hasCareTask;
    private Long careTaskId;
    private CustomerCareTaskStatus careTaskStatus;
    private String careTaskNotes;

    // Relative timeline calculation
    private long daysUntil; // > 0: in future, 0: today, < 0: overdue
    private String timelineBadgeText;
    private String timelineBadgeClass;
}
