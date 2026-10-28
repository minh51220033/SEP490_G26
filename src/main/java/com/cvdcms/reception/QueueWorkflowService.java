package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public QueueWorkflowService(ReceptionAccess access, DispatchBranchRepository branches,
            QueueWorkflowRepository tickets, DispatchVisitRepository visits, DispatchRoomRepository rooms,
            DispatchChairRepository chairs, ReceptionAuditRepository audits) {
        this.access = access; this.branches = branches; this.tickets = tickets; this.visits = visits;
        this.rooms = rooms; this.chairs = chairs; this.audits = audits;
    }

    @Transactional(readOnly = true)
    public Board board(HttpSession session, Long requestedBranch) {
        var scope = access.require(session);
        List<Branch> choices = branches.findAll().stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()) && scope.permits(b.getBranchId()))
                .sorted(Comparator.comparing(Branch::getBranchName)).toList();
        if (choices.isEmpty()) throw error(HttpStatus.FORBIDDEN, "Không có chi nhánh tiếp đón.");
        Long id = requestedBranch == null ? choices.get(0).getBranchId() : requestedBranch;
        if (choices.stream().noneMatch(b -> b.getBranchId().equals(id)))
            throw error(HttpStatus.FORBIDDEN, "Không được xem hàng chờ chi nhánh này.");
        return new Board(id, choices, ordered(id));
    }

    @Transactional(readOnly = true)
    public PublicBoard publicBoard(Long branchId) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!Boolean.TRUE.equals(branch.getIsActive())) throw error(HttpStatus.NOT_FOUND, "Chi nhánh không hoạt động.");
        List<QueueTicket> all = ordered(branchId);
        List<PublicNumber> called = all.stream().filter(t -> t.getStatus() == QueueStatus.CALLED)
                .map(t -> new PublicNumber(t.getDisplayNumber(),
                        t.getVisit().getRoomId() == null ? "" : rooms.findById(t.getVisit().getRoomId()).map(Room::getRoomName).orElse(""),
                        t.getVisit().getChairId() == null ? "" : chairs.findById(t.getVisit().getChairId()).map(DentalChair::getChairName).orElse(""))).toList();
        List<PublicNumber> serving = all.stream().filter(t -> t.getStatus() == QueueStatus.IN_SERVICE)
                .map(t -> new PublicNumber(t.getDisplayNumber(),
                        t.getVisit().getRoomId() == null ? "" : rooms.findById(t.getVisit().getRoomId()).map(Room::getRoomName).orElse(""),
                        t.getVisit().getChairId() == null ? "" : chairs.findById(t.getVisit().getChairId()).map(DentalChair::getChairName).orElse(""))).toList();
        long waiting = all.stream().filter(t -> t.getStatus() == QueueStatus.WAITING).count();
        List<String> next = all.stream().filter(t -> t.getStatus() == QueueStatus.WAITING)
                .limit(5).map(QueueTicket::getDisplayNumber).toList();
        return new PublicBoard(branch.getBranchName(), called, serving, next, waiting);
    }

    @Transactional
    public void transition(HttpSession session, Long id, Action action, String reason) {
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
                require(before == QueueStatus.WAITING && visit.getStatus() == VisitStatus.WAITING, "Chỉ gọi số đang chờ.");
                require(visit.getPrimaryDoctor() != null, "Lượt khám chưa được phân bác sĩ.");
                require(tickets.countOther(branchId, today, QueueType.EXAM, QueueStatus.CALLED, id) == 0,
                        "Đang có số được gọi; hãy bắt đầu hoặc tạm dừng số đó.");
                ticket.setStatus(QueueStatus.CALLED); ticket.setCalledAt(LocalDateTime.now(ZONE));
            }
            case PAUSE -> {
                require(before == QueueStatus.WAITING || before == QueueStatus.CALLED, "Chỉ tạm dừng số đang chờ hoặc đang gọi.");
                require(visit.getStatus() == VisitStatus.WAITING, "Lượt khám đã bắt đầu.");
                ticket.setStatus(QueueStatus.PAUSED);
            }
            case RESUME -> {
                require(before == QueueStatus.PAUSED && visit.getStatus() == VisitStatus.WAITING, "Chỉ tiếp tục số đang tạm dừng.");
                ticket.setStatus(QueueStatus.WAITING);
            }
            case EMERGENCY -> {
                require((before == QueueStatus.WAITING || before == QueueStatus.PAUSED)
                        && visit.getStatus() == VisitStatus.WAITING, "Chỉ ưu tiên lượt chưa bắt đầu.");
                String value = reason == null ? "" : reason.trim();
                require(value.length() >= 10 && value.length() <= 255, "Cần ghi lý do cấp cứu từ 10 đến 255 ký tự.");
                ticket.setIsEmergency(true); ticket.setPriorityScore(100); ticket.setPriorityReason(value);
            }
            case START -> {
                require(before == QueueStatus.CALLED && visit.getStatus() == VisitStatus.WAITING,
                        "Phải gọi số trước khi bắt đầu khám.");
                require(visit.getPrimaryDoctor() != null, "Lượt khám chưa được phân bác sĩ.");
                if (visit.getRoomId() != null) {
                    Room room = rooms.findById(visit.getRoomId()).orElseThrow(() -> error(HttpStatus.CONFLICT, "Phòng không còn tồn tại."));
                    require(room.getBranch().getBranchId().equals(branchId) && room.getStatus() == RoomStatus.ACTIVE,
                            "Phòng không khả dụng.");
                }
                if (visit.getChairId() != null && visit.getStatus() != VisitStatus.IN_TREATMENT) {
                    DentalChair chair = chairs.findById(visit.getChairId()).orElseThrow(() -> error(HttpStatus.CONFLICT, "Ghế không còn tồn tại."));
                    require(chair.getBranch().getBranchId().equals(branchId) && chair.getStatus() == ChairStatus.AVAILABLE
                            && chair.getRoom() != null && Objects.equals(visit.getRoomId(), chair.getRoom().getRoomId()),
                            "Ghế không khả dụng tại phòng đã phân.");
                    require(visits.occupiedChair(chair.getChairId(), visit.getVisitId(),
                            Set.of(VisitStatus.IN_EXAM, VisitStatus.IN_TREATMENT)) == 0, "Ghế đang có ca khám khác.");
                    chair.setStatus(ChairStatus.IN_USE); chairs.save(chair);
                }
                visit.setStatus(VisitStatus.IN_EXAM); visits.save(visit);
                ticket.setStatus(QueueStatus.IN_SERVICE);
            }
            case DONE -> {
                require(before == QueueStatus.IN_SERVICE, "Chỉ hoàn tất số đã bắt đầu khám.");
                require(Set.of(VisitStatus.WAITING_PARACLINICAL, VisitStatus.WAITING_RESULT,
                                VisitStatus.IN_TREATMENT, VisitStatus.WAITING_PAYMENT, VisitStatus.COMPLETED)
                                .contains(visit.getStatus()),
                        "Bác sĩ chưa chuyển lượt khám sang bước tiếp theo.");
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
        SystemAuditLog log = new SystemAuditLog();
        log.setUser(access.require(session).user()); log.setBranch(ticket.getBranch());
        log.setEntityTable("queue_tickets"); log.setEntityId(id); log.setAction("QUEUE_" + action.name());
        log.setOldValuesJson("{\"status\":\"" + before + "\"}");
        try {
            log.setNewValuesJson(new ObjectMapper().writeValueAsString(
                    Map.of("status", ticket.getStatus().name(), "priorityReason",
                            action == Action.EMERGENCY ? ticket.getPriorityReason() : "")));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Không thể ghi nhật ký hàng chờ.", ex);
        }
        audits.save(log);
    }

    private List<QueueTicket> ordered(Long id) {
        List<QueueTicket> rows = new ArrayList<>(tickets.forBoard(id, LocalDate.now(ZONE), QueueType.EXAM));
        rows.sort(Comparator.comparingInt((QueueTicket t) -> t.getStatus() == QueueStatus.CALLED ? -1
                : t.getStatus() == QueueStatus.WAITING ? 0 : t.getStatus() == QueueStatus.PAUSED ? 1 : 2)
                .thenComparing(Comparator.comparingInt((QueueTicket t) -> t.getPriorityScore() == null ? 0 : t.getPriorityScore()).reversed())
                .thenComparing(QueueTicket::getTicketNumber).thenComparing(QueueTicket::getTicketId));
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
    public enum Action { CALL, PAUSE, RESUME, EMERGENCY, START, DONE }
    public record Board(Long branchId, List<Branch> branches, List<QueueTicket> tickets) { }
    public record PublicNumber(String number, String room, String chair) { }
    public record PublicBoard(String branch, List<PublicNumber> called, List<PublicNumber> serving,
                              List<String> next, long waiting) { }
}
