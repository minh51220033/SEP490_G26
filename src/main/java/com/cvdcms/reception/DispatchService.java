package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

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

    public DispatchService(ReceptionAccess access, DispatchVisitRepository visits, DispatchDoctorRepository doctors,
            DispatchScheduleRepository schedules, DispatchRoomRepository rooms, DispatchChairRepository chairs,
            DispatchTicketRepository tickets, DispatchBranchRepository branches, ReceptionAuditRepository audits) {
        this.access = access; this.visits = visits; this.doctors = doctors; this.schedules = schedules;
        this.rooms = rooms; this.chairs = chairs; this.tickets = tickets; this.branches = branches; this.audits = audits;
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
                .map(d -> new DoctorOption(d.getStaffId(), d.getFullName(), visits.doctorLoad(d.getStaffId(), VisitStatus.WAITING, -1L))).toList();
        List<TicketRow> queue = tickets.board(branchId, day, QueueType.EXAM).stream().map(this::ticketRow).toList();
        return new Board(branchId, options, dayVisits, doctorRows,
                rooms.findByBranchBranchIdAndStatusOrderByRoomCode(branchId, RoomStatus.ACTIVE),
                chairs.findByBranchBranchIdOrderByChairCode(branchId), queue, day);
    }

    @Transactional
    public void assign(HttpSession session, Long branchId, Long visitId, Long doctorId, Long roomId, Long chairId) {
        branch(session, branchId);
        Visit visit = visit(branchId, visitId);
        if (visit.getStatus() != VisitStatus.WAITING) throw fail(HttpStatus.CONFLICT, "Chỉ phân công lượt khám đang chờ.");
        if (doctorId == null) throw fail(HttpStatus.BAD_REQUEST, "Vui lòng chọn bác sĩ.");
        LocalDate today = LocalDate.now(ZONE);
        if (doctors.eligible(branchId, today).stream().noneMatch(d -> d.getStaffId().equals(doctorId))
                || schedules.working(doctorId, branchId, today, LocalTime.now(ZONE)) == 0)
            throw fail(HttpStatus.CONFLICT, "Bác sĩ không có phân công và ca làm việc hiện tại ở chi nhánh.");
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
        audit(session, visit, "ASSIGN_VISIT", old, "doctor=" + doctorId + ",room=" + effectiveRoom + ",chair=" + chairId);
    }

    @Transactional
    public QueueTicket issue(HttpSession session, Long branchId, Long visitId, boolean priority, String reason) {
        Branch branch = branch(session, branchId); // Branch row serializes number generation at this branch.
        Visit visit = visit(branchId, visitId);
        if (visit.getStatus() != VisitStatus.WAITING) throw fail(HttpStatus.CONFLICT, "Chỉ cấp số cho lượt đang chờ.");
        if (visit.getPrimaryDoctor() == null) throw fail(HttpStatus.CONFLICT, "Cần phân công bác sĩ trước khi cấp số.");
        LocalDate day = LocalDate.now(ZONE);
        if (!visit.getCheckInAt().toLocalDate().equals(day)) throw fail(HttpStatus.CONFLICT, "Chỉ cấp số trong ngày check-in.");
        var existing = tickets.findByVisitVisitIdAndQueueType(visitId, QueueType.EXAM);
        if (existing.isPresent()) return existing.get();
        boolean emergency = visit.getVisitType() == VisitType.EMERGENCY;
        if (emergency && !priority) throw fail(HttpStatus.BAD_REQUEST, "Ca cấp cứu phải dùng số ưu tiên.");
        String priorityReason = reason == null ? "" : reason.trim();
        if (priority && !emergency && priorityReason.length() < 10)
            throw fail(HttpStatus.BAD_REQUEST, "Cần ghi lý do ưu tiên tối thiểu 10 ký tự.");
        if (priorityReason.length() > 255) throw fail(HttpStatus.BAD_REQUEST, "Lý do ưu tiên tối đa 255 ký tự.");
        int next = tickets.maxNumber(branchId, day, QueueType.EXAM, priority) + 1;
        if (next > 999) throw fail(HttpStatus.CONFLICT, "Đã hết dải số trong ngày.");
        QueueTicket ticket = new QueueTicket();
        ticket.setVisit(visit); ticket.setBranch(branch); ticket.setQueueDate(day); ticket.setQueueType(QueueType.EXAM);
        ticket.setIsPriority(priority); ticket.setIsEmergency(emergency); ticket.setPriorityScore(emergency ? 100 : priority ? 50 : 0);
        ticket.setPriorityReason(emergency ? visit.getInitialSymptoms() : priority ? priorityReason : null);
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
        if (!v.getBranch().getBranchId().equals(branchId)) throw fail(HttpStatus.FORBIDDEN, "Lượt khám thuộc chi nhánh khác.");
        return v;
    }
    private void audit(HttpSession session, Visit visit, String action, String oldValue, String newValue) {
        SystemAuditLog log = new SystemAuditLog(); log.setUser(access.require(session).user());
        log.setBranch(visit.getBranch()); log.setEntityTable("visits"); log.setEntityId(visit.getVisitId());
        log.setAction(action);
        if (oldValue != null) log.setOldValuesJson("{\"value\":\"" + oldValue + "\"}");
        log.setNewValuesJson("{\"value\":\"" + newValue + "\"}");
        audits.save(log);
    }
    private TicketRow ticketRow(QueueTicket t) {
        return new TicketRow(t.getTicketId(), t.getDisplayNumber(), t.getVisit().getVisitCode(),
                t.getVisit().getPatientProfile().getFullName(), t.getStatus(), Boolean.TRUE.equals(t.getIsEmergency()));
    }
    private ResponseStatusException fail(HttpStatus status, String reason) { return new ResponseStatusException(status, reason); }
    public record DoctorOption(Long id, String name, long waiting) { }
    public record TicketRow(Long id, String number, String visit, String patient, QueueStatus status, boolean emergency) { }
    public record Board(Long branchId, List<Branch> branches, List<Visit> visits, List<DoctorOption> doctors,
                        List<Room> rooms, List<DentalChair> chairs, List<TicketRow> tickets, LocalDate day) { }
}
