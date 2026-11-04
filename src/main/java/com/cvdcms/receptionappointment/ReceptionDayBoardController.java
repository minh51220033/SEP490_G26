package com.cvdcms.receptionappointment;

import com.cvdcms.patient.PatientAccess;
import com.cvdcms.reception.ReceptionVisitRepository;
import com.cvdcms.repository.DentalChairRepository;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.DentalChair;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/reception/appointments/day")
public class ReceptionDayBoardController {
    private final PatientAccess access;
    private final ReceptionVisitRepository visits;
    private final DentalChairRepository chairs;

    public ReceptionDayBoardController(PatientAccess access, ReceptionVisitRepository visits,
            DentalChairRepository chairs) {
        this.access = access;
        this.visits = visits;
        this.chairs = chairs;
    }

    @GetMapping
    public String dayBoard(HttpSession session) {
        access.require(session);
        return "reception/appointments/day-board";
    }

    public record Assignment(Long appointmentId, String doctorName, String chairName, String visitStatus) { }

    @GetMapping("/assignments")
    @ResponseBody
    @Transactional(readOnly = true)
    public List<Assignment> assignments(HttpSession session, @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        PatientAccess.Scope scope = access.require(session);
        if (branchId == null || !scope.permits(branchId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        List<Visit> day = visits.forDay(branchId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        Map<Long, DentalChair> byChair = chairs.findAllById(day.stream().map(Visit::getChairId)
                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(DentalChair::getChairId, Function.identity()));
        return day.stream().filter(v -> v.getAppointmentId() != null).map(v -> new Assignment(
                v.getAppointmentId(), v.getPrimaryDoctor() == null ? null : v.getPrimaryDoctor().getFullName(),
                v.getChairId() == null || !byChair.containsKey(v.getChairId()) ? null
                        : byChair.get(v.getChairId()).getChairName(), v.getStatus().name())).toList();
    }
}
