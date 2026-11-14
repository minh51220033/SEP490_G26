package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.AppointmentActorType;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.repository.AppointmentPolicyRepository;
import com.cvdcms.repository.AppointmentSessionWindowRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Exact appointment time rules. Call resolve inside the branch booking lock transaction. */
@Service
public class AppointmentSlotService {
    private static final List<AppointmentStatus> OCCUPYING = List.of(AppointmentStatus.PENDING,
            AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN, AppointmentStatus.COMPLETED);
    private final AppointmentPolicyRepository appointments;
    private final AppointmentSessionWindowRepository windows;
    private final AppointmentPolicyService policy;

    public AppointmentSlotService(AppointmentPolicyRepository appointments,
            AppointmentSessionWindowRepository windows, AppointmentPolicyService policy) {
        this.appointments = appointments;
        this.windows = windows;
        this.policy = policy;
    }

    public record Slot(LocalTime time, int remaining) { }

    public List<Slot> available(Branch branch, LocalDate date, PreferredSession session,
            Long doctorId, int durationMinutes, Long excludeId, AppointmentActorType actorType) {
        validateDuration(durationMinutes);
        if (branch == null || date == null || session == null || session == PreferredSession.ANY) return List.of();
        Range range = range(branch, session);
        if (!range.active || !range.start.isBefore(range.end)) return List.of();
        if (date.equals(LocalDate.now(AppointmentPolicyService.CLINIC_ZONE))) {
            LocalTime now = LocalTime.now(AppointmentPolicyService.CLINIC_ZONE);
            if (branch.getClosingTime() != null && !now.isBefore(branch.getClosingTime())) {
                return List.of();
            }
            if (!now.isBefore(range.end)) {
                return List.of();
            }
        }
        AppointmentPolicyService.Availability availability = policy.availability(branch, date, session,
                excludeId, actorType);
        if (availability == null || !availability.available()
                || doctorId != null && availability.doctors().stream().noneMatch(d -> doctorId.equals(d.id())))
            return List.of();
        // The policy already enforces total session capacity. Keep that limit while
        // checking overlap at each exact time.
        List<Appointment> booked = appointments.findOccupyingForSession(branch.getBranchId(), date, session, OCCUPYING)
                .stream().filter(a -> excludeId == null || !Objects.equals(a.getAppointmentId(), excludeId)).toList();
        int capacity = Math.min(Math.max(1, availability.doctors().size()), availability.remainingBranch() + booked.size());
        if (capacity == 0) return List.of();
        List<Slot> result = new ArrayList<>();
        for (LocalTime time = range.start; !time.plusMinutes(durationMinutes).isAfter(range.end);
                time = time.plusMinutes(15)) {
            if (date.equals(LocalDate.now(AppointmentPolicyService.CLINIC_ZONE))
                    && !time.isAfter(LocalTime.now(AppointmentPolicyService.CLINIC_ZONE))) continue;
            LocalTime end = time.plusMinutes(durationMinutes);
            final LocalTime start = time;
            long overlapping = booked.stream().filter(a -> overlaps(a, start, end)).count();
            boolean doctorBusy = doctorId != null && booked.stream().anyMatch(a -> overlaps(a, start, end)
                    && a.getDoctor() != null && doctorId.equals(a.getDoctor().getStaffId()));
            int remaining = Math.max(0, capacity - (int) overlapping);
            if (!doctorBusy && remaining > 0) result.add(new Slot(time, remaining));
        }
        return result;
    }

    public LocalTime resolve(Branch branch, LocalDate date, PreferredSession session,
            Long doctorId, int durationMinutes, Long excludeId, LocalTime requested,
            AppointmentActorType actorType) {
        List<Slot> options = available(branch, date, session, doctorId, durationMinutes, excludeId, actorType);
        if (requested == null) {
            if (options.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Không còn khung giờ phù hợp. Vui lòng chọn ngày, buổi hoặc bác sĩ khác.");
            return options.get(0).time();
        }
        if (options.stream().noneMatch(slot -> slot.time().equals(requested)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Khung giờ đã hết chỗ hoặc không phù hợp với thời lượng khám. Vui lòng tải lại giờ trống.");
        return requested;
    }

    /**
     * Flexible time resolver for dental clinic operations:
     * - Allows null expectedTime (patient arrives flexibly within the shift).
     * - Allows arrival time within the shift operating hours, enforcing shift boundary,
     *   duration cutoff (not past closing time), and 15-minute alignment.
     */
    public LocalTime resolveFlexibly(Branch branch, LocalDate date, PreferredSession session,
            Long doctorId, int durationMinutes, Long excludeId, LocalTime requested,
            AppointmentActorType actorType) {
        validateDuration(durationMinutes);
        Range range = range(branch, session);
        if (!range.active) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Buổi khám không hoạt động tại chi nhánh.");
        }

        if (requested == null) {
            if (date != null && date.equals(LocalDate.now(AppointmentPolicyService.CLINIC_ZONE))) {
                LocalTime now = LocalTime.now(AppointmentPolicyService.CLINIC_ZONE);
                if (branch != null && branch.getClosingTime() != null && !now.isBefore(branch.getClosingTime())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Phòng khám đã đóng cửa trong ngày hôm nay.");
                }
                if (!now.isBefore(range.end)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Ca khám trong ngày đã kết thúc.");
                }
            }
            return null;
        }
        if (requested.isBefore(range.start) || requested.isAfter(range.end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Giờ hẹn " + requested + " nằm ngoài khung giờ làm việc của ca " + session + " (" + range.start + " - " + range.end + ").");
        }
        if (requested.plusMinutes(durationMinutes).isAfter(range.end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Thời lượng khám (" + durationMinutes + " phút) bắt đầu từ " + requested
                            + " sẽ vượt quá giờ kết thúc ca " + session + " (" + range.end + "). Vui lòng chọn giờ sớm hơn.");
        }
        if (requested.getMinute() % 15 != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Giờ hẹn dự kiến phải theo bước 15 phút (ví dụ: :00, :15, :30, :45).");
        }

        // Validate past time if booking for today
        if (date != null && date.equals(LocalDate.now(AppointmentPolicyService.CLINIC_ZONE))
                && !requested.isAfter(LocalTime.now(AppointmentPolicyService.CLINIC_ZONE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Giờ hẹn " + requested + " đã qua so với thời gian hiện tại.");
        }

        // Enforce slot conflict and doctor availability check
        if (branch != null && date != null && session != null) {
            List<Appointment> booked = appointments.findOccupyingForSession(branch.getBranchId(), date, session, OCCUPYING)
                    .stream().filter(a -> excludeId == null || !Objects.equals(a.getAppointmentId(), excludeId)).toList();
            LocalTime end = requested.plusMinutes(durationMinutes);
            if (doctorId != null) {
                boolean doctorBusy = booked.stream().anyMatch(a -> overlaps(a, requested, end)
                        && a.getDoctor() != null && doctorId.equals(a.getDoctor().getStaffId()));
                if (doctorBusy) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Bác sĩ đã có lịch hẹn khác trong khung giờ " + requested + " - " + end + ". Vui lòng chọn giờ hoặc bác sĩ khác.");
                }
            }
            AppointmentPolicyService.Availability availability = policy.availability(branch, date, session, excludeId, actorType);
            if (availability != null && availability.available()) {
                int capacity = Math.min(Math.max(1, availability.doctors().size()), availability.remainingBranch() + booked.size());
                long overlapping = booked.stream().filter(a -> overlaps(a, requested, end)).count();
                if (capacity > 0 && overlapping >= capacity) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Khung giờ " + requested + " - " + end + " đã đạt tối đa công suất tiếp nhận của ca trực.");
                }
            }
        }
        return requested;
    }

    public static int duration(com.cvdcms.entity.Service service) {
        return service == null || service.getEstimatedDurationMinutes() == null
                ? 30 : service.getEstimatedDurationMinutes();
    }

    private static boolean overlaps(Appointment appointment, LocalTime start, LocalTime end) {
        if (appointment.getExpectedTime() == null) return false; // legacy appointment has no fixed time
        int length = appointment.getDurationMinutes() == null ? duration(appointment.getService())
                : appointment.getDurationMinutes();
        return appointment.getExpectedTime().isBefore(end)
                && appointment.getExpectedTime().plusMinutes(length).isAfter(start);
    }

    private Range range(Branch branch, PreferredSession session) {
        AppointmentPolicyService.SessionRange sr = policy.sessionRange(branch, session);
        return new Range(sr.start(), sr.end(), sr.active());
    }

    private static void validateDuration(int minutes) {
        if (minutes < 15 || minutes > 240 || minutes % 15 != 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Thời lượng dịch vụ phải từ 15 đến 240 phút và là bội số của 15 phút.");
    }

    private record Range(LocalTime start, LocalTime end, boolean active) { }
}
