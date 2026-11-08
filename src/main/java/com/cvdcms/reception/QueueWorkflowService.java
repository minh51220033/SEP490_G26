package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Service
public class QueueWorkflowService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ReceptionAccess access;
    private final DispatchBranchRepository branches;
    private final QueueWorkflowRepository tickets;
    private final DispatchVisitRepository visits;
    private final DispatchRoomRepository rooms;
    private final DispatchChairRepository chairs;
    private final ReceptionAuditRepository audits;
    private final ClinicalExaminationBootstrapService examinations;

    public QueueWorkflowService(ReceptionAccess access, DispatchBranchRepository branches,
            QueueWorkflowRepository tickets, DispatchVisitRepository visits, DispatchRoomRepository rooms,
            DispatchChairRepository chairs, ReceptionAuditRepository audits,
            ClinicalExaminationBootstrapService examinations) {
        this.access = access; this.branches = branches; this.tickets = tickets; this.visits = visits;
        this.rooms = rooms; this.chairs = chairs; this.audits = audits; this.examinations = examinations;
    }

    @Transactional(readOnly = true)
    public Board board(HttpSession session, Long requestedBranch) {
        var scope = access.require(session);
        List<Branch> choices = branches.findAll().stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()) && scope.permits(b.getBranchId()))
                .sorted(Comparator.comparing(Branch::getBranchName)).toList();
        if (choices.isEmpty()) throw error(HttpStatus.FORBIDDEN, "Không có chi nhánh tiếp đón.");
        Long selectedId = requestedBranch;
        if (selectedId == null && session != null) {
            Object sId = session.getAttribute("currentBranchId");
            if (sId instanceof Long sid && choices.stream().anyMatch(b -> b.getBranchId().equals(sid))) {
                selectedId = sid;
            }
        }
        if (selectedId == null) {
            selectedId = choices.get(0).getBranchId();
        }
        final Long id = selectedId;
        if (session != null) {
            session.setAttribute("currentBranchId", id);
        }
        if (choices.stream().noneMatch(b -> b.getBranchId().equals(id)))
            throw error(HttpStatus.FORBIDDEN, "Không được xem hàng chờ chi nhánh này.");
        LocalDate day = null;
        if (session != null) {
            Object sDay = session.getAttribute("currentWorkflowDay");
            if (sDay instanceof LocalDate sd) day = sd;
        }
        if (day == null) day = LocalDate.now(ZONE);
        List<QueueTicket> rows = ordered(id, day);
        Map<Long, DoctorQueue> doctorQueues = new LinkedHashMap<>();
        for (QueueTicket ticket : rows) {
            StaffProfile doctor = ticket.getVisit().getPrimaryDoctor();
            if (doctor == null) continue;
            DoctorQueue previous = doctorQueues.get(doctor.getStaffId());
            if (previous == null) {
                previous = new DoctorQueue(doctor.getStaffId(), doctor.getFullName(), 0, 0);
            }
            doctorQueues.put(doctor.getStaffId(), new DoctorQueue(previous.id(), previous.name(),
                    previous.waiting() + (ticket.getStatus() == QueueStatus.WAITING
                            && ticket.getVisit().getStatus() == VisitStatus.WAITING ? 1 : 0),
                    previous.called() + (ticket.getStatus() == QueueStatus.CALLED ? 1 : 0)));
        }
        return new Board(id, choices, rows, doctorQueues.values().stream()
                .sorted(Comparator.comparing(DoctorQueue::name)).toList(),
                chairs.findByBranchBranchIdOrderByChairCode(id));
    }

    @Transactional(readOnly = true)
    public PublicBoard publicBoard(Long branchId) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!Boolean.TRUE.equals(branch.getIsActive())) throw error(HttpStatus.NOT_FOUND, "Chi nhánh không hoạt động.");
        List<QueueTicket> all = ordered(branchId);
        List<PublicNumber> called = all.stream().filter(t -> t.getStatus() == QueueStatus.CALLED)
                .sorted(Comparator.comparing(QueueTicket::getCalledAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(QueueTicket::getTicketId, Comparator.reverseOrder()))
                .map(t -> new PublicNumber(t.getTicketId(), t.getDisplayNumber(), doctorName(t),
                        t.getVisit().getRoomId() == null ? "" : rooms.findById(t.getVisit().getRoomId()).map(Room::getRoomName).orElse(""),
                        t.getVisit().getChairId() == null ? "" : chairs.findById(t.getVisit().getChairId()).map(DentalChair::getChairName).orElse(""),
                        t.getCalledAt() != null ? t.getCalledAt().atZone(ZONE).toInstant().toEpochMilli() : null)).toList();
        List<PublicNumber> serving = all.stream().filter(t -> t.getStatus() == QueueStatus.IN_SERVICE)
                .map(t -> new PublicNumber(t.getTicketId(), t.getDisplayNumber(), doctorName(t),
                        t.getVisit().getRoomId() == null ? "" : rooms.findById(t.getVisit().getRoomId()).map(Room::getRoomName).orElse(""),
                        t.getVisit().getChairId() == null ? "" : chairs.findById(t.getVisit().getChairId()).map(DentalChair::getChairName).orElse(""),
                        null)).toList();
        long waiting = all.stream().filter(t -> t.getStatus() == QueueStatus.WAITING
                && t.getVisit().getStatus() == VisitStatus.WAITING).count();
        List<String> next = all.stream().filter(t -> t.getStatus() == QueueStatus.WAITING
                        && t.getVisit().getStatus() == VisitStatus.WAITING)
                .limit(5).map(t -> t.getDisplayNumber() + " · " + doctorName(t)).toList();
        List<PublicQueueRow> queueList = buildPublicQueueList(all);
        return new PublicBoard(branch.getBranchName(), called, serving, next, waiting, queueList);
    }

    @Transactional(readOnly = true)
    public List<PublicQueueRow> publicQueueList(Long branchId) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!Boolean.TRUE.equals(branch.getIsActive())) throw error(HttpStatus.NOT_FOUND, "Chi nhánh không hoạt động.");
        List<QueueTicket> all = ordered(branchId).stream()
                .filter(t -> t.getStatus() != QueueStatus.CANCELLED && t.getStatus() != QueueStatus.DONE)
                .toList();
        return buildPublicQueueList(all);
    }

    private List<PublicQueueRow> buildPublicQueueList(List<QueueTicket> all) {
        List<PublicQueueRow> rows = new ArrayList<>();
        int stt = 1;
        for (QueueTicket t : all) {
            if (t.getStatus() == QueueStatus.CANCELLED || t.getStatus() == QueueStatus.DONE) continue;
            PatientProfile p = t.getVisit() != null ? t.getVisit().getPatientProfile() : null;
            int idx = stt++;
            rows.add(new PublicQueueRow(
                    idx,
                    idx,
                    t.getDisplayNumber(),
                    p != null && p.getPatientCode() != null ? p.getPatientCode() : "—",
                    p != null && p.getFullName() != null ? p.getFullName() : "—",
                    p != null && p.getPhoneNumber() != null ? p.getPhoneNumber() : "—",
                    t.getStatus() != null ? t.getStatus().name() : "WAITING"
            ));
        }
        return rows;
    }

    @Transactional
    public QueueTicket callNext(HttpSession session, Long branchId, Long doctorId) {
        requireBranch(session, branchId);
        require(doctorId != null, "Cần chọn bác sĩ để gọi số.");
        LocalDate today = LocalDate.now(ZONE);
        require(tickets.countOtherForDoctor(branchId, today, QueueType.EXAM, doctorId,
                QueueStatus.CALLED, -1L) == 0, "Bác sĩ này đã có số được gọi.");
        QueueTicket next = tickets.forBoard(branchId, today, QueueType.EXAM).stream()
                .filter(t -> t.getStatus() == QueueStatus.WAITING
                        && t.getVisit().getStatus() == VisitStatus.WAITING
                        && t.getVisit().getPrimaryDoctor() != null
                        && doctorId.equals(t.getVisit().getPrimaryDoctor().getStaffId()))
                .min(waitingOrder())
                .orElseThrow(() -> error(HttpStatus.CONFLICT, "Bác sĩ này không còn số đang chờ."));
        QueueTicket locked = tickets.lock(next.getTicketId())
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy số khám."));
        Visit lockedVisit = visits.lock(locked.getVisit().getVisitId())
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy lượt khám."));
        require(locked.getBranch().getBranchId().equals(branchId)
                && today.equals(locked.getQueueDate()) && locked.getQueueType() == QueueType.EXAM
                && locked.getStatus() == QueueStatus.WAITING
                && lockedVisit.getBranch().getBranchId().equals(branchId)
                && lockedVisit.getStatus() == VisitStatus.WAITING
                && lockedVisit.getPrimaryDoctor() != null
                && doctorId.equals(lockedVisit.getPrimaryDoctor().getStaffId()),
                "Hàng chờ đã thay đổi. Vui lòng tải lại.");
        QueueStatus before = locked.getStatus();
        locked.setStatus(QueueStatus.CALLED);
        locked.setCalledAt(LocalDateTime.now(ZONE));
        tickets.save(locked);
        audit(session, locked, "CALL_NEXT", before, "");
        return locked;
    }

    @Transactional
    public void transition(HttpSession session, Long id, Action action, String reason) {
        transition(session, id, action, null, reason);
    }

    @Transactional
    public void transition(HttpSession session, Long id, Action action, Long chairId, String reason) {
        QueueTicket snapshot = tickets.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy số khám."));
        Long branchId = snapshot.getBranch().getBranchId();
        requireBranch(session, branchId); // Serialize queue operations at this branch.
        QueueTicket ticket = tickets.lock(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy số khám."));
        LocalDate today = LocalDate.now(ZONE);
        if (!ticket.getBranch().getBranchId().equals(branchId) || !ticket.getQueueDate().equals(today)
                || ticket.getQueueType() != QueueType.EXAM)
            throw error(HttpStatus.CONFLICT, "Chỉ điều phối số khám hôm nay tại chi nhánh.");
        Visit visit = visits.lock(ticket.getVisit().getVisitId())
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy lượt khám."));
        if (!visit.getBranch().getBranchId().equals(branchId))
            throw error(HttpStatus.CONFLICT, "Số và lượt khám khác chi nhánh.");
        QueueStatus before = ticket.getStatus();
        switch (action) {
            case CALL -> {
                require((before == QueueStatus.WAITING || before == QueueStatus.CALLED) && visit.getStatus() == VisitStatus.WAITING, "Chỉ gọi số đang chờ hoặc đang gọi.");
                require(visit.getPrimaryDoctor() != null, "Lượt khám chưa được phân bác sĩ.");
                Long doctorId = visit.getPrimaryDoctor().getStaffId();
                require(tickets.countOtherForDoctor(branchId, today, QueueType.EXAM, doctorId, QueueStatus.CALLED, id) == 0,
                        "Bác sĩ này đã có số được gọi; hãy bắt đầu hoặc tạm dừng số đó.");
                if (before != QueueStatus.CALLED) {
                    Long nextId = tickets.forBoard(branchId, today, QueueType.EXAM).stream()
                            .filter(t -> t.getStatus() == QueueStatus.WAITING
                                    && t.getVisit().getStatus() == VisitStatus.WAITING
                                    && t.getVisit().getPrimaryDoctor() != null
                                    && doctorId.equals(t.getVisit().getPrimaryDoctor().getStaffId()))
                            .min(waitingOrder()).map(QueueTicket::getTicketId).orElse(null);
                    if (!Objects.equals(nextId, id)) {
                        var scope = access.require(session);
                        require(scope.canManage(), "Chỉ quản lý hoặc nhân viên có quyền điều phối mới được gọi vượt thứ tự.");
                        require(validReason(reason), "Gọi vượt thứ tự cần ghi lý do từ 10 đến 255 ký tự.");
                    }
                }
                ticket.setStatus(QueueStatus.CALLED); ticket.setCalledAt(LocalDateTime.now(ZONE));
            }
            case PAUSE -> {
                require(before == QueueStatus.WAITING || before == QueueStatus.CALLED, "Chỉ tạm dừng số đang chờ hoặc đang gọi.");
                require(visit.getStatus() == VisitStatus.WAITING, "Lượt khám đã bắt đầu.");
                require(validReason(reason), "Cần ghi lý do tạm dừng từ 10 đến 255 ký tự.");
                ticket.setStatus(QueueStatus.PAUSED);
                ticket.setPausedAt(LocalDateTime.now(ZONE));
                ticket.setPauseReason(reason.trim());
            }
            case RESUME -> {
                require(before == QueueStatus.PAUSED && visit.getStatus() == VisitStatus.WAITING, "Chỉ tiếp tục số đang tạm dừng.");
                ticket.setStatus(QueueStatus.WAITING);
                if (isClinicPauseReason(ticket.getPauseReason())) {
                    ticket.setResumedAt(null);
                } else {
                    ticket.setResumedAt(LocalDateTime.now(ZONE));
                }
            }
            case EMERGENCY -> {
                require((before == QueueStatus.WAITING || before == QueueStatus.PAUSED)
                        && visit.getStatus() == VisitStatus.WAITING, "Chỉ ưu tiên lượt chưa bắt đầu.");
                require(validReason(reason), "Cần ghi lý do cấp cứu từ 10 đến 255 ký tự.");
                ticket.setIsEmergency(true); ticket.setIsPriority(true); ticket.setPriorityScore(100); ticket.setPriorityReason(reason.trim());
                visit.setVisitType(VisitType.EMERGENCY);
                visits.save(visit);
                ticket.setStatus(QueueStatus.WAITING);
                if (before == QueueStatus.PAUSED) ticket.setResumedAt(LocalDateTime.now(ZONE));
            }
            case PRIORITIZE -> {
                require((before == QueueStatus.WAITING || before == QueueStatus.PAUSED)
                        && visit.getStatus() == VisitStatus.WAITING && !Boolean.TRUE.equals(ticket.getIsEmergency()),
                        "Chỉ ưu tiên số chưa bắt đầu và không phải cấp cứu.");
                require(validReason(reason), "Cần ghi lý do ưu tiên từ 10 đến 255 ký tự.");
                ticket.setPriorityScore(50); ticket.setPriorityReason(reason.trim());
            }
            case START -> {
                require(before == QueueStatus.CALLED && visit.getStatus() == VisitStatus.WAITING,
                        "Phải gọi số trước khi bắt đầu khám.");
                require(visit.getPrimaryDoctor() != null, "Lượt khám chưa được phân bác sĩ.");
                Long effectiveChairId = chairId != null ? chairId : visit.getChairId();
                if (effectiveChairId == null || visit.getRoomId() == null) {
                    List<DentalChair> branchChairs = chairs.findByBranchBranchIdOrderByChairCode(branchId);
                    for (DentalChair c : branchChairs) {
                        if (c.getStatus() == ChairStatus.AVAILABLE && c.getRoom() != null
                                && c.getRoom().getStatus() == RoomStatus.ACTIVE
                                && (visit.getRoomId() == null || visit.getRoomId().equals(c.getRoom().getRoomId()))
                                && visits.occupiedChair(c.getChairId(), visit.getVisitId(), Set.of(VisitStatus.IN_EXAM, VisitStatus.IN_TREATMENT)) == 0) {
                            effectiveChairId = c.getChairId();
                            visit.setRoomId(c.getRoom().getRoomId());
                            visit.setChairId(c.getChairId());
                            break;
                        }
                    }
                }
                require(visit.getRoomId() != null && effectiveChairId != null,
                        "Cần phân phòng và ghế tại bàn điều phối trước khi bắt đầu khám.");
                examinations.ensureForAssignedVisit(visit.getVisitId());
                if (visit.getRoomId() != null) {
                    Room room = rooms.findById(visit.getRoomId()).orElseThrow(() -> error(HttpStatus.CONFLICT, "Phòng không còn tồn tại."));
                    require(room.getBranch().getBranchId().equals(branchId) && room.getStatus() == RoomStatus.ACTIVE,
                            "Phòng không khả dụng.");
                }
                if (effectiveChairId != null && visit.getStatus() != VisitStatus.IN_TREATMENT) {
                    DentalChair chair = chairs.findById(effectiveChairId).orElseThrow(() -> error(HttpStatus.CONFLICT, "Ghế không còn tồn tại."));
                    require(chair.getBranch().getBranchId().equals(branchId) && chair.getStatus() == ChairStatus.AVAILABLE
                            && chair.getRoom() != null && Objects.equals(visit.getRoomId(), chair.getRoom().getRoomId()),
                            "Ghế không khả dụng tại phòng đã phân.");
                    require(visits.occupiedChair(chair.getChairId(), visit.getVisitId(),
                            Set.of(VisitStatus.IN_EXAM, VisitStatus.IN_TREATMENT)) == 0, "Ghế đang có ca khám khác.");
                    chair.setStatus(ChairStatus.IN_USE); chairs.save(chair); visit.setChairId(effectiveChairId);
                }
                visit.setStatus(VisitStatus.IN_EXAM); visits.save(visit);
                ticket.setStatus(QueueStatus.IN_SERVICE);
            }
            case DONE -> {
                require(before == QueueStatus.IN_SERVICE, "Chỉ hoàn tất số đã bắt đầu khám.");
                require(Set.of(VisitStatus.WAITING_PARACLINICAL, VisitStatus.WAITING_RESULT,
                                VisitStatus.WAITING_PAYMENT, VisitStatus.COMPLETED)
                                .contains(visit.getStatus()),
                        "Chỉ hoàn tất số sau khi bệnh nhân rời ghế khám hoặc hoàn thành lượt khám.");
                if (visit.getChairId() != null) {
                    chairs.findById(visit.getChairId()).ifPresent(chair -> {
                        if (chair.getStatus() == ChairStatus.IN_USE) {
                            chair.setStatus(ChairStatus.CLEANING);
                            chairs.save(chair);
                        }
                    });
                }
                ticket.setStatus(QueueStatus.DONE); ticket.setCompletedAt(LocalDateTime.now(ZONE));
            }
        }
        tickets.save(ticket);
        audit(session, ticket, action.name(), before, reason);
    }

    private void audit(HttpSession session, QueueTicket ticket, String action, QueueStatus before, String reason) {
        SystemAuditLog log = new SystemAuditLog();
        log.setUser(access.require(session).user()); log.setBranch(ticket.getBranch());
        log.setEntityTable("queue_tickets"); log.setEntityId(ticket.getTicketId()); log.setAction("QUEUE_" + action);
        log.setOldValuesJson("{\"status\":\"" + before + "\"}");
        try {
            log.setNewValuesJson(new ObjectMapper().writeValueAsString(
                    Map.of("status", ticket.getStatus().name(), "reason", reason == null ? "" : reason.trim(),
                            "priorityScore", ticket.getPriorityScore() == null ? 0 : ticket.getPriorityScore())));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Không thể ghi nhật ký hàng chờ.", ex);
        }
        audits.save(log);
    }

    private boolean validReason(String value) {
        return value != null && value.trim().length() >= 10 && value.trim().length() <= 255;
    }
    private boolean isClinicPauseReason(String reason) {
        if (reason == null) return false;
        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.contains("bác sĩ") || lower.contains("bac si")
                || lower.contains("hội chẩn") || lower.contains("hoi chan")
                || lower.contains("khử khuẩn") || lower.contains("khu khuan")
                || lower.contains("bảo trì") || lower.contains("bao tri")
                || lower.contains("thiết bị") || lower.contains("thiet bi")
                || lower.contains("phòng khám") || lower.contains("phong kham")
                || lower.contains("kỹ thuật") || lower.contains("ky thuat")
                || lower.contains("clinic");
    }

    private Comparator<QueueTicket> waitingOrder() {
        return Comparator.comparingInt((QueueTicket t) -> t.getPriorityScore() == null ? 0 : t.getPriorityScore())
                .reversed().thenComparing((QueueTicket t) -> t.getResumedAt() == null ? t.getIssuedAt() : t.getResumedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(QueueTicket::getTicketNumber).thenComparing(QueueTicket::getTicketId);
    }

    private String doctorName(QueueTicket ticket) {
        return ticket.getVisit().getPrimaryDoctor() == null ? "Chưa phân bác sĩ"
                : ticket.getVisit().getPrimaryDoctor().getFullName();
    }

    private List<QueueTicket> ordered(Long id) {
        return ordered(id, null);
    }

    private List<QueueTicket> ordered(Long id, LocalDate day) {
        LocalDate targetDay = day != null ? day : LocalDate.now(ZONE);
        List<QueueTicket> rows = new ArrayList<>(tickets.forBoard(id, targetDay, QueueType.EXAM));
        rows.sort(Comparator.comparingInt((QueueTicket t) -> t.getStatus() == QueueStatus.CALLED ? -1
                : t.getStatus() == QueueStatus.WAITING ? 0 : t.getStatus() == QueueStatus.PAUSED ? 1 : 2)
                .thenComparing(waitingOrder()));
        return rows;
    }
    private void requireBranch(HttpSession session, Long id) {
        var scope = access.require(session);
        Branch branch = branches.lock(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!scope.permits(id) || !Boolean.TRUE.equals(branch.getIsActive()))
            throw error(HttpStatus.FORBIDDEN, "Không được quản lý hàng chờ này.");
    }
    private void require(boolean valid, String message) { if (!valid) throw error(HttpStatus.CONFLICT, message); }
    private ResponseStatusException error(HttpStatus code, String reason) { return new ResponseStatusException(code, reason); }
    public enum Action { CALL, PAUSE, RESUME, PRIORITIZE, EMERGENCY, START, DONE }
    public record DoctorQueue(Long id, String name, long waiting, long called) { }
    public record Board(Long branchId, List<Branch> branches, List<QueueTicket> tickets,
                        List<DoctorQueue> doctors, List<DentalChair> chairs) {
        public Board(Long branchId, List<Branch> branches, List<QueueTicket> tickets, List<DoctorQueue> doctors) {
            this(branchId, branches, tickets, doctors, List.of());
        }
    }
    public record PublicNumber(Long ticketId, String number, String doctor, String room, String chair, Long callTime) {
        public PublicNumber(Long ticketId, String number, String doctor, String room, String chair) {
            this(ticketId, number, doctor, room, chair, null);
        }
        public PublicNumber(String number, String doctor, String room, String chair) {
            this(null, number, doctor, room, chair, null);
        }
    }
    public record PublicBoard(String branch, List<PublicNumber> called, List<PublicNumber> serving,
                              List<String> next, long waiting, List<PublicQueueRow> queueList) {
        public PublicBoard(String branch, List<PublicNumber> called, List<PublicNumber> serving,
                           List<String> next, long waiting) {
            this(branch, called, serving, next, waiting, List.of());
        }
    }
    public record PublicQueueRow(int stt, int orderIndex, String ticketNumber, String patientCode,
                                 String patientName, String phoneNumber, String status) {
        public PublicQueueRow(int stt, String ticketNumber, String patientCode, String patientName, String phoneNumber) {
            this(stt, stt, ticketNumber, patientCode, patientName, phoneNumber, "WAITING");
        }
    }
}
