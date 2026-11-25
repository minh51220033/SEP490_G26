package com.cvdcms.service;

import com.cvdcms.DTO.availability.ServiceTimeWindow;
import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.enums.ResourceUsagePhase;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Helper thuần tính khoảng thời gian 3 pha (PREP / PROCEDURE / TURNOVER) và giao cắt nửa mở [start, end).
 */
public final class ResourceTimeIntervalHelper {

    private ResourceTimeIntervalHelper() {
    }

    public static ServiceTimeWindow buildWindow(Service service, LocalDate date, LocalTime startTime) {
        int prep = service.getPreparationDurationMinutes() != null ? Math.max(0, service.getPreparationDurationMinutes()) : 0;
        int proc = service.getProcedureDurationMinutes() != null && service.getProcedureDurationMinutes() > 0
                ? service.getProcedureDurationMinutes()
                : (service.getEstimatedDurationMinutes() != null && service.getEstimatedDurationMinutes() > 0
                        ? service.getEstimatedDurationMinutes() : 30);
        int turn = service.getTurnoverDurationMinutes() != null ? Math.max(0, service.getTurnoverDurationMinutes()) : 0;
        boolean roomExclusive = Boolean.TRUE.equals(service.getIsRoomExclusive());
        boolean requiresChair = !Boolean.FALSE.equals(service.getRequiresChair());
        boolean docInPrep = Boolean.TRUE.equals(service.getDoctorRequiredInPrep());
        boolean docInTurn = Boolean.TRUE.equals(service.getDoctorRequiredInTurnover());

        return computeWindow(date, startTime, prep, proc, turn, roomExclusive, requiresChair, docInPrep, docInTurn);
    }

    public static ServiceTimeWindow buildWindowFromAppointment(Appointment appt) {
        if (appt == null || appt.getAppointmentDate() == null || appt.getExpectedTime() == null) {
            return null;
        }
        return buildWindowFromAppointmentAt(appt, appt.getAppointmentDate(), appt.getExpectedTime());
    }

    /**
     * Tính khung giờ 3 pha tại ngày/giờ mới nhưng vẫn giữ nguyên snapshot của Appointment nếu đã có.
     */
    public static ServiceTimeWindow buildWindowFromAppointmentAt(Appointment appt, LocalDate date, LocalTime startTime) {
        if (appt == null || date == null || startTime == null) {
            return null;
        }
        if (appt.getProcedureDurationMinutes() != null && appt.getProcedureDurationMinutes() > 0) {
            int prep = appt.getPreparationDurationMinutes() != null ? Math.max(0, appt.getPreparationDurationMinutes()) : 0;
            int proc = appt.getProcedureDurationMinutes();
            int turn = appt.getTurnoverDurationMinutes() != null ? Math.max(0, appt.getTurnoverDurationMinutes()) : 0;
            return computeWindow(
                    date,
                    startTime,
                    prep, proc, turn,
                    Boolean.TRUE.equals(appt.getIsRoomExclusiveSnapshot()),
                    appt.getService() == null || !Boolean.FALSE.equals(appt.getService().getRequiresChair()),
                    Boolean.TRUE.equals(appt.getDoctorRequiredInPrepSnapshot()),
                    Boolean.TRUE.equals(appt.getDoctorRequiredInTurnoverSnapshot()));
        }
        if (appt.getService() != null) {
            return buildWindow(appt.getService(), date, startTime);
        }
        int total = appt.getDurationMinutes() != null && appt.getDurationMinutes() > 0 ? appt.getDurationMinutes() : 30;
        return computeWindow(date, startTime, 0, total, 0, false, true, false, false);
    }

    public static ServiceTimeWindow computeWindow(
            LocalDate date,
            LocalTime startTime,
            int prep,
            int proc,
            int turn,
            boolean roomExclusive,
            boolean requiresChair,
            boolean docInPrep,
            boolean docInTurn) {
        LocalDateTime t0 = LocalDateTime.of(date, startTime);
        LocalDateTime t1 = t0.plusMinutes(prep);
        LocalDateTime t2 = t1.plusMinutes(proc);
        LocalDateTime t3 = t2.plusMinutes(turn);
        LocalDateTime sDoc = docInPrep ? t0 : t1;
        LocalDateTime eDoc = docInTurn ? t3 : t2;
        return new ServiceTimeWindow(
                prep, proc, turn, prep + proc + turn,
                roomExclusive, requiresChair, docInPrep, docInTurn,
                t0, t1, t2, t3, sDoc, eDoc);
    }

    public static LocalDateTime[] phaseInterval(ServiceTimeWindow w, ResourceUsagePhase phase) {
        if (phase == null || phase == ResourceUsagePhase.PROCEDURE) {
            return new LocalDateTime[]{w.t1(), w.t2()};
        }
        return switch (phase) {
            case PREP -> new LocalDateTime[]{w.t0(), w.t1()};
            case TURNOVER -> new LocalDateTime[]{w.t2(), w.t3()};
            case ALL -> new LocalDateTime[]{w.t0(), w.t3()};
            case PROCEDURE -> new LocalDateTime[]{w.t1(), w.t2()};
        };
    }

    /**
     * Kiểm tra hai khoảng nửa mở [aStart, aEnd) và [bStart, bEnd) có giao nhau không.
     * Khoảng rỗng [t, t) không giao với khoảng nào; hai khoảng chạm biên [08:00, 08:30) và [08:30, 09:00) không giao nhau.
     */
    public static boolean overlapsHalfOpen(
            LocalDateTime aStart, LocalDateTime aEnd,
            LocalDateTime bStart, LocalDateTime bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        if (!aStart.isBefore(aEnd) || !bStart.isBefore(bEnd)) {
            return false;
        }
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /**
     * Kiểm tra xung đột giữa khoảng [candStart, candEnd) và khoảng đang bận [busyStart, busyEnd)
     * có tính thêm travel buffer (phút) giữa hai phòng khác nhau.
     */
    public static boolean conflictsWithBuffer(
            LocalDateTime candStart, LocalDateTime candEnd,
            LocalDateTime busyStart, LocalDateTime busyEnd,
            int bufferAfterBusyToCandMinutes,
            int bufferAfterCandToBusyMinutes) {
        if (overlapsHalfOpen(candStart, candEnd, busyStart, busyEnd)) {
            return true;
        }
        if (!busyEnd.isAfter(candStart)) {
            long gap = Duration.between(busyEnd, candStart).toMinutes();
            return gap < Math.max(0, bufferAfterBusyToCandMinutes);
        }
        if (!candEnd.isAfter(busyStart)) {
            long gap = Duration.between(candEnd, busyStart).toMinutes();
            return gap < Math.max(0, bufferAfterCandToBusyMinutes);
        }
        return false;
    }
}
