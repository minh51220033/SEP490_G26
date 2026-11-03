package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.AppointmentRepository;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DispatchService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ReceptionAccess access;
    private final DispatchVisitRepository visits;
    private final DispatchDoctorRepository doctors;
    private final DispatchScheduleRepository schedules;
    private final DispatchRoomRepository rooms;
    private final DispatchChairRepository chairs;
    private final DispatchTicketRepository tickets;
    private final DispatchBranchRepository branches;
    private final ReceptionAuditRepository audits;
    private final AppointmentRepository appointments;
    private final ClinicalExaminationBootstrapService examinations;
    private final ObjectMapper mapper;

    public DispatchService(ReceptionAccess access, DispatchVisitRepository visits, DispatchDoctorRepository doctors,
            DispatchScheduleRepository schedules, DispatchRoomRepository rooms, DispatchChairRepository chairs,
            DispatchTicketRepository tickets, DispatchBranchRepository branches, ReceptionAuditRepository audits,
            AppointmentRepository appointments, ClinicalExaminationBootstrapService examinations,
            @Autowired(required = false) ObjectMapper mapper) {
        this.access = access; this.visits = visits; this.doctors = doctors; this.schedules = schedules;
        this.rooms = rooms; this.chairs = chairs; this.tickets = tickets; this.branches = branches; this.audits = audits;
        this.appointments = appointments;
        this.examinations = examinations;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public DispatchService(ReceptionAccess access, DispatchVisitRepository visits, DispatchDoctorRepository doctors,
            DispatchScheduleRepository schedules, DispatchRoomRepository rooms, DispatchChairRepository chairs,
            DispatchTicketRepository tickets, DispatchBranchRepository branches, ReceptionAuditRepository audits,
            AppointmentRepository appointments, ClinicalExaminationBootstrapService examinations) {
        this(access, visits, doctors, schedules, rooms, chairs, tickets, branches, audits, appointments, examinations, new ObjectMapper());
    }

    @Transactional(readOnly = true)
    public Board board(HttpSession session, Long requestedBranch) {
        var scope = access.require(session);
        List<Branch> options = branches.findAll().stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()) && scope.permits(b.getBranchId()))
                .sorted(Comparator.comparing(Branch::getBranchName)).toList();
        if (options.isEmpty()) throw fail(HttpStatus.FORBIDDEN, "Không có chi nhánh được phân công.");
        Long branchId = requestedBranch == null ? options.get(0).getBranchId() : requestedBranch;
        checkBranch(session, branchId);
        LocalDate day = LocalDate.now(ZONE);
        List<Visit> dayVisits = visits.today(branchId, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        List<StaffProfile> availableDoctors = doctors.eligible(branchId, day).stream()
                .filter(d -> schedules.working(d.getStaffId(), branchId, day, LocalTime.now(ZONE)) > 0).toList();
        List<DoctorOption> doctorRows = availableDoctors.stream()
                .map(d -> new DoctorOption(d.getStaffId(), d.getFullName(), load(d.getStaffId(), branchId, day, -1L)))
                .sorted(Comparator.comparingLong(DoctorOption::waiting).thenComparing(DoctorOption::name)).toList();
        Map<Long, DoctorRequest> requests = new HashMap<>();
        List<Long> appointmentIds = dayVisits.stream().map(Visit::getAppointmentId).filter(Objects::nonNull).distinct().toList();
        appointments.findAllById(appointmentIds).forEach(a -> {
            if (a.getDoctor() != null) requests.put(a.getAppointmentId(),
                    new DoctorRequest(a.getDoctor().getStaffId(), a.getDoctor().getFullName(),
                            doctorRows.stream().anyMatch(d -> d.id().equals(a.getDoctor().getStaffId()))));
        });
        List<QueueTicket> dayTickets = tickets.board(branchId, day, QueueType.EXAM);
        Map<Long, String> issued = new HashMap<>();
        dayTickets.forEach(t -> issued.put(t.getVisit().getVisitId(), t.getDisplayNumber()));
        List<TicketRow> queue = dayTickets.stream().map(this::ticketRow).toList();

        // FT08 Priority sorting:
        // 1. Emergency first
        // 2. Paraclinical return (WAITING_RESULT) second
        // 3. Priority ticket (P-xx) third
        // 4. Chronological check-in time
        List<Visit> sortedVisits = new ArrayList<>(dayVisits);
        sortedVisits.sort(Comparator.comparing((Visit v) -> v.getVisitType() == VisitType.EMERGENCY ? 0 : 1)
                .thenComparing((Visit v) -> v.getStatus() == VisitStatus.WAITING_RESULT ? 0 : 1)
                .thenComparing((Visit v) -> {
                    String num = issued.get(v.getVisitId());
                    return num != null && num.startsWith("P-") ? 0 : 1;
                })
                .thenComparing(Visit::getCheckInAt));

        return new Board(branchId, options, sortedVisits, doctorRows,
                rooms.findByBranchBranchIdAndStatusOrderByRoomCode(branchId, RoomStatus.ACTIVE),
                chairs.findByBranchBranchIdOrderByChairCode(branchId), queue, day, requests, issued);
    }

    @Transactional
    public void assign(HttpSession session, Long branchId, Long visitId, Long doctorId, Long roomId, Long chairId,
            String reason) {
        branch(session, branchId);
        Visit visit = visit(branchId, visitId);
        if (visit.getStatus() != VisitStatus.WAITING && visit.getStatus() != VisitStatus.WAITING_RESULT)
            throw fail(HttpStatus.CONFLICT, "Chỉ phân công lượt khám đang chờ hoặc chờ kết quả cận lâm sàng.");
        LocalDate today = LocalDate.now(ZONE);
        LocalTime now = LocalTime.now(ZONE);
        List<StaffProfile> available = doctors.eligible(branchId, today).stream()
                .filter(d -> schedules.working(d.getStaffId(), branchId, today, now) > 0).toList();
        Long requestedDoctor = requestedDoctor(visit);
        if (doctorId == null) {
            if (requestedDoctor != null && available.stream().anyMatch(d -> d.getStaffId().equals(requestedDoctor))) {
                doctorId = requestedDoctor;
            } else {
                doctorId = available.stream()
                        .min(Comparator.comparingLong((StaffProfile d) -> load(d.getStaffId(), branchId, today, visitId))
                                .thenComparing(StaffProfile::getStaffId))
                        .map(StaffProfile::getStaffId)
                        .orElseThrow(() -> fail(HttpStatus.CONFLICT, "Không có bác sĩ đang trực để tự phân công."));
            }
        }
        final Long selectedDoctor = doctorId;
        if (available.stream().noneMatch(d -> d.getStaffId().equals(selectedDoctor)))
            throw fail(HttpStatus.CONFLICT, "Bác sĩ không có phân công và ca làm việc hiện tại ở chi nhánh.");
        Long previousDoctor = visit.getPrimaryDoctor() == null ? null : visit.getPrimaryDoctor().getStaffId();
        boolean overridesRequest = requestedDoctor != null && !requestedDoctor.equals(doctorId);
        boolean transfersQueue = previousDoctor != null && !previousDoctor.equals(doctorId);
        if (transfersQueue && tickets.findByVisitVisitIdAndQueueType(visitId, QueueType.EXAM)
                .filter(t -> t.getStatus() != QueueStatus.WAITING && t.getStatus() != QueueStatus.PAUSED).isPresent())
            throw fail(HttpStatus.CONFLICT, "Số đã được gọi hoặc đang phục vụ; không thể chuyển bác sĩ tại bàn điều phối.");
        String normalizedReason = reason == null ? "" : reason.trim();
        if ((overridesRequest || transfersQueue) && (normalizedReason.length() < 10 || normalizedReason.length() > 255))
            throw fail(HttpStatus.BAD_REQUEST, "Đổi bác sĩ yêu cầu hoặc chuyển hàng chờ cần lý do từ 10 đến 255 ký tự.");
        StaffProfile doctor = doctors.findById(doctorId).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "Không tìm thấy bác sĩ."));
        Long effectiveRoom = roomId;
        if (chairId != null) {
            DentalChair chair = chairs.findById(chairId).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "Không tìm thấy ghế."));
            if (!chair.getBranch().getBranchId().equals(branchId) || chair.getRoom() == null
                    || chair.getStatus() != ChairStatus.AVAILABLE)
                throw fail(HttpStatus.CONFLICT, "Ghế phải đang hoạt động và thuộc phòng tại chi nhánh.");
            if (effectiveRoom == null) effectiveRoom = chair.getRoom().getRoomId();
            if (!effectiveRoom.equals(chair.getRoom().getRoomId()))
                throw fail(HttpStatus.CONFLICT, "Ghế không thuộc phòng đã chọn.");
            if (visits.occupiedChair(chairId, visitId, Set.of(VisitStatus.IN_EXAM, VisitStatus.IN_TREATMENT)) > 0)
                throw fail(HttpStatus.CONFLICT, "Ghế đang được sử dụng cho lượt khám khác.");
        }
        if (effectiveRoom != null) {
            Room room = rooms.findById(effectiveRoom).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "Không tìm thấy phòng."));
            if (!room.getBranch().getBranchId().equals(branchId) || room.getStatus() != RoomStatus.ACTIVE)
                throw fail(HttpStatus.CONFLICT, "Phòng không hoạt động tại chi nhánh.");
        }
        String old = "doctor=" + (visit.getPrimaryDoctor() == null ? "" : visit.getPrimaryDoctor().getStaffId())
                + ",room=" + visit.getRoomId() + ",chair=" + visit.getChairId();
        visit.setPrimaryDoctor(doctor); visit.setRoomId(effectiveRoom); visit.setChairId(chairId);
        visits.save(visit);
        if (transfersQueue) {
            examinations.syncDoctorOnQueueTransfer(visit.getVisitId(), doctor);
        } else {
            examinations.ensureForAssignedVisit(visit.getVisitId());
        }
        audit(session, visit, transfersQueue ? "TRANSFER_DOCTOR_QUEUE" : "ASSIGN_VISIT", old,
                "doctor=" + doctorId + ",room=" + effectiveRoom + ",chair=" + chairId
                        + (normalizedReason.isEmpty() ? "" : ",reason=" + normalizedReason));
    }

    private Long requestedDoctor(Visit visit) {
        if (visit.getAppointmentId() == null) return null;
        Appointment appointment = appointments.findById(visit.getAppointmentId())
                .orElseThrow(() -> fail(HttpStatus.CONFLICT, "Lịch hẹn của lượt khám không còn tồn tại."));
        if (!appointment.getBranch().getBranchId().equals(visit.getBranch().getBranchId())
                || !appointment.getPatientProfile().getProfileId().equals(visit.getPatientProfile().getProfileId()))
            throw fail(HttpStatus.CONFLICT, "Lịch hẹn không khớp bệnh nhân hoặc chi nhánh của lượt khám.");
        return appointment.getDoctor() == null ? null : appointment.getDoctor().getStaffId();
    }

    private long load(Long doctorId, Long branchId, LocalDate day, Long excludeVisitId) {
        return visits.doctorLoad(doctorId, branchId, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), excludeVisitId);
    }

    @Transactional
    public QueueTicket issue(HttpSession session, Long branchId, Long visitId, boolean priority, String reason) {
        Branch branch = branch(session, branchId); // Branch row serializes number generation at this branch.
        Visit visit = visit(branchId, visitId);
        if (visit.getStatus() != VisitStatus.WAITING && visit.getStatus() != VisitStatus.WAITING_RESULT)
            throw fail(HttpStatus.CONFLICT, "Chỉ cấp số cho lượt đang chờ khám hoặc chờ kết quả cận lâm sàng.");
        if (visit.getPrimaryDoctor() == null) throw fail(HttpStatus.CONFLICT, "Cần phân công bác sĩ trước khi cấp số.");
        LocalDate day = LocalDate.now(ZONE);
        if (!visit.getCheckInAt().toLocalDate().equals(day)) throw fail(HttpStatus.CONFLICT, "Chỉ cấp số trong ngày check-in.");

        var existing = tickets.findByVisitVisitIdAndQueueType(visitId, QueueType.EXAM);
        if (existing.isPresent() && existing.get().getStatus() != QueueStatus.DONE) {
            return existing.get();
        }

        boolean emergency = visit.getVisitType() == VisitType.EMERGENCY;
        boolean returningParaclinical = visit.getStatus() == VisitStatus.WAITING_RESULT;
        if (returningParaclinical) {
            priority = true;
        }
        if (emergency && !priority) throw fail(HttpStatus.BAD_REQUEST, "Ca cấp cứu phải dùng số ưu tiên.");
        String priorityReason = reason == null ? "" : reason.trim();
        if (returningParaclinical && priorityReason.isEmpty()) {
            priorityReason = "Bệnh nhân quay lại sau cận lâm sàng chờ đọc kết quả";
        }
        if (priority && !emergency && priorityReason.isEmpty()) {
            priorityReason = "Bệnh nhân ưu tiên theo chỉ định tiếp đón";
        }
        String emergencyReason = visit.getInitialSymptoms() == null ? "" : visit.getInitialSymptoms().trim();
        if (emergency && (emergencyReason.length() < 10 || emergencyReason.length() > 255)) {
            emergencyReason = priorityReason;
        }
        if (emergency && (emergencyReason.length() < 10 || emergencyReason.length() > 255))
            throw fail(HttpStatus.BAD_REQUEST, "Ca cấp cứu cần lý do từ 10 đến 255 ký tự.");
        if (priority && !emergency && priorityReason.length() < 10)
            throw fail(HttpStatus.BAD_REQUEST, "Cần ghi lý do ưu tiên tối thiểu 10 ký tự.");
        if (priorityReason.length() > 255) throw fail(HttpStatus.BAD_REQUEST, "Lý do ưu tiên tối đa 255 ký tự.");
        int next = tickets.maxNumber(branchId, day, QueueType.EXAM, priority) + 1;
        if (next > 999) throw fail(HttpStatus.CONFLICT, "Đã hết dải số trong ngày.");
        QueueTicket ticket = new QueueTicket();
        ticket.setVisit(visit); ticket.setBranch(branch); ticket.setQueueDate(day); ticket.setQueueType(QueueType.EXAM);
        ticket.setIsPriority(priority); ticket.setIsEmergency(emergency); ticket.setPriorityScore(emergency ? 100 : priority ? 50 : 0);
        ticket.setPriorityReason(emergency ? emergencyReason : priority ? priorityReason : null);
        ticket.setTicketNumber(next); ticket.setDisplayNumber((priority ? "P-" : "") + String.format("%02d", next));
        ticket.setStatus(QueueStatus.WAITING);
        tickets.saveAndFlush(ticket);
        audit(session, visit, "ISSUE_EXAM_TICKET", null, ticket.getDisplayNumber());
        return ticket;
    }

    @Transactional(readOnly = true)
    public QueueTicket ticket(HttpSession session, Long id) {
        QueueTicket ticket = tickets.findById(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu chờ."));
        checkBranch(session, ticket.getBranch().getBranchId());
        return ticket;
    }

    private Branch branch(HttpSession session, Long id) {
        var scope = access.require(session);
        Branch b = branches.lock(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!scope.permits(id) || !Boolean.TRUE.equals(b.getIsActive()))
            throw fail(HttpStatus.FORBIDDEN, "Không được điều phối tại chi nhánh này.");
        return b;
    }
    private Branch checkBranch(HttpSession session, Long id) {
        var scope = access.require(session);
        Branch b = branches.findById(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!scope.permits(id) || !Boolean.TRUE.equals(b.getIsActive()))
            throw fail(HttpStatus.FORBIDDEN, "Không được điều phối tại chi nhánh này.");
        return b;
    }
    private Visit visit(Long branchId, Long id) {
        Visit v = visits.lock(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "Không tìm thấy lượt khám."));
        if (!v.getBranch().getBranchId().equals(branchId)) throw fail(HttpStatus.CONFLICT, "Lượt khám không thuộc chi nhánh này.");
        return v;
    }
    private void audit(HttpSession session, Visit visit, String action, String oldValue, String newValue) {
        SystemAuditLog log = new SystemAuditLog();
        log.setUser(access.require(session).user());
        log.setBranch(visit.getBranch());
        log.setEntityTable("visits");
        log.setEntityId(visit.getVisitId());
        log.setAction(action);
        try {
            ObjectMapper m = this.mapper != null ? this.mapper : new ObjectMapper();
            if (oldValue != null) {
                log.setOldValuesJson(m.writeValueAsString(Map.of("value", oldValue)));
            }
            log.setNewValuesJson(m.writeValueAsString(Map.of("value", newValue)));
        } catch (JsonProcessingException e) {
            if (oldValue != null) log.setOldValuesJson("{\"value\":\"" + oldValue.replace("\"", "\\\"") + "\"}");
            log.setNewValuesJson("{\"value\":\"" + newValue.replace("\"", "\\\"") + "\"}");
        }
        audits.save(log);
    }
    private TicketRow ticketRow(QueueTicket t) {
        return new TicketRow(t.getTicketId(), t.getVisit().getVisitId(), t.getDisplayNumber(),
                t.getVisit().getPatientProfile().getFullName(), t.getVisit().getPatientProfile().getPatientCode(),
                t.getVisit().getPrimaryDoctor() == null ? null : t.getVisit().getPrimaryDoctor().getStaffId(),
                t.getVisit().getPrimaryDoctor() == null ? "Chưa gán" : t.getVisit().getPrimaryDoctor().getFullName(),
                t.getStatus(), t.getIsPriority(), t.getIsEmergency());
    }
    private ResponseStatusException fail(HttpStatus s, String m) { return new ResponseStatusException(s, m); }
    public record DoctorOption(Long id, String name, long waiting) {}
    public record DoctorRequest(Long id, String name, boolean available) {}
    public record TicketRow(Long ticketId, Long visitId, String number, String patient, String patientCode,
                            Long doctorId, String doctorName, QueueStatus status, Boolean priority, Boolean emergency) {
        public String getDoctor() { return doctorName(); }
        public String doctor() { return doctorName(); }
    }
    public record Board(Long branchId, List<Branch> branches, List<Visit> visits, List<DoctorOption> doctors,
                        List<Room> rooms, List<DentalChair> chairs, List<TicketRow> queue, LocalDate day,
                        Map<Long, DoctorRequest> requests, Map<Long, String> issued,
                        List<Visit> unassignedVisits, Map<Long, List<Visit>> doctorVisits,
                        List<Visit> handedOverVisits) {
        public List<TicketRow> getTickets() { return queue(); }
        public List<TicketRow> tickets() { return queue(); }

        public Board(Long branchId, List<Branch> branches, List<Visit> visits, List<DoctorOption> doctors,
                     List<Room> rooms, List<DentalChair> chairs, List<TicketRow> queue, LocalDate day,
                     Map<Long, DoctorRequest> requests, Map<Long, String> issued,
                     List<Visit> unassignedVisits, Map<Long, List<Visit>> doctorVisits) {
            this(branchId, branches, visits, doctors, rooms, chairs, queue, day, requests, issued,
                    unassignedVisits, doctorVisits, buildHandedOverVisits(visits, doctors));
        }

        public Board(Long branchId, List<Branch> branches, List<Visit> visits, List<DoctorOption> doctors,
                     List<Room> rooms, List<DentalChair> chairs, List<TicketRow> queue, LocalDate day,
                     Map<Long, DoctorRequest> requests, Map<Long, String> issued) {
            this(branchId, branches, visits, doctors, rooms, chairs, queue, day, requests, issued,
                    visits.stream().filter(v -> v.getPrimaryDoctor() == null && isActiveVisit(v)).toList(),
                    buildDoctorVisits(visits, doctors),
                    buildHandedOverVisits(visits, doctors));
        }

        private static boolean isActiveVisit(Visit v) {
            return v != null && v.getStatus() != VisitStatus.COMPLETED
                    && v.getStatus() != VisitStatus.CANCELLED
                    && v.getStatus() != VisitStatus.WAITING_PAYMENT;
        }

        private static List<Visit> buildHandedOverVisits(List<Visit> visits, List<DoctorOption> doctors) {
            Set<Long> onDuty = doctors.stream().map(DoctorOption::id).collect(Collectors.toSet());
            return visits.stream()
                    .filter(v -> v.getPrimaryDoctor() != null && !onDuty.contains(v.getPrimaryDoctor().getStaffId()))
                    .filter(Board::isActiveVisit)
                    .toList();
        }

        private static Map<Long, List<Visit>> buildDoctorVisits(List<Visit> visits, List<DoctorOption> doctors) {
            Map<Long, List<Visit>> map = new LinkedHashMap<>();
            for (DoctorOption doc : doctors) {
                map.put(doc.id(), visits.stream()
                        .filter(v -> v.getPrimaryDoctor() != null && doc.id().equals(v.getPrimaryDoctor().getStaffId()))
                        .filter(Board::isActiveVisit)
                        .toList());
            }
            return map;
        }
    }
}
