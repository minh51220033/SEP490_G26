package com.cvdcms.DTO.report;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class OperationalReportDTO {

    // 1. Chỉ số lượt khám & Tỷ lệ hoàn tất
    private long totalVisits = 0;
    private long completedVisits = 0;
    private long cancelledVisits = 0;
    private long waitingVisits = 0;
    private long inExamVisits = 0;
    private long inTreatmentVisits = 0;
    private long waitingPaymentVisits = 0;
    private double completionRate = 0.0;

    // 2. Cơ cấu theo hình thức tiếp nhận (VisitType)
    private long walkInVisits = 0;
    private long appointmentVisits = 0;
    private long emergencyVisits = 0;

    // 3. Cơ cấu theo kênh đặt lịch hẹn (AppointmentSource)
    private long onlineAppointments = 0;
    private long receptionistAppointments = 0;
    private long phoneAppointments = 0;
    private long chatbotAppointments = 0;

    // 4. Cơ cấu nguồn khách hàng (customerSource từ PatientProfile)
    private List<CustomerSourceStat> customerSources = new ArrayList<>();
}

