package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.patient.PatientAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cvdcms.service.ClinicalExaminationBootstrapService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class QueueWorkflowService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ReceptionAccess access;
    private final DispatchBranchRepository branches;
    private final QueueWorkflowRepository tickets;
    private final DispatchVisitRepository visits;
    private final DispatchRoomRepository rooms;
    private final DispatchChairRepository chairs;
    private final DispatchDoctorRepository doctors;
    private final ReceptionAuditRepository audits;
    private final ClinicalExaminationBootstrapService examinations;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ClinicQueueCoordinatorService queueCoordinator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ResourceBookingTransactionService resourceBookingTransactionService;

    public QueueWorkflowService(ReceptionAccess access, DispatchBranchRepository branches,
            QueueWorkflowRepository tickets, DispatchVisitRepository visits, DispatchRoomRepository rooms,
            DispatchChairRepository chairs, DispatchDoctorRepository doctors, ReceptionAuditRepository audits,
            ClinicalExaminationBootstrapService examinations) {
        this.access = access; this.branches = branches; this.tickets = tickets; this.visits = visits;
        this.rooms = rooms; this.chairs = chairs; this.doctors = doctors; this.audits = audits; this.examinations = examinations;
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

        // CHỈ hiển thị Bác sĩ nào ĐANG CÓ BỆNH NHÂN trong ngày (có vé khám hoặc lượt khám phân công)
        LocalDateTime startOfDay = day.atStartOfDay();
        LocalDateTime endOfDay = day.plusDays(1).atStartOfDay();
        for (Visit v : visits.today(id, startOfDay, endOfDay)) {
            if (v.getPrimaryDoctor() != null) {
                StaffProfile doc = v.getPrimaryDoctor();
                doctorQueues.putIfAbsent(doc.getStaffId(), new DoctorQueue(doc.getStaffId(), doc.getFullName(), 0, 0));
            }
        }
        for (QueueTicket ticket : rows) {
            StaffProfile doctor = ticket.getVisit() != null ? ticket.getVisit().getPrimaryDoctor() : null;
            if (doctor == null) continue;
            doctorQueues.putIfAbsent(doctor.getStaffId(), new DoctorQueue(doctor.getStaffId(), doctor.getFullName(), 0, 0));
        }

        // Tính toán số lượng đang chờ và đã gọi cho các bác sĩ đang có bệnh nhân
        for (QueueTicket ticket : rows) {
            StaffProfile doctor = ticket.getVisit() != null ? ticket.getVisit().getPrimaryDoctor() : null;
            if (doctor == null) continue;
            DoctorQueue previous = doctorQueues.get(doctor.getStaffId());
            long waitingCount = (ticket.getStatus() == QueueStatus.WAITING
                    && isWaitingOrResult(ticket.getVisit())) ? 1 : 0;
            long calledCount = (ticket.getStatus() == QueueStatus.CALLED) ? 1 : 0;
            if (previous != null) {
                doctorQueues.put(doctor.getStaffId(), new DoctorQueue(previous.id(), previous.name(),
                        previous.waiting() + waitingCount,
                        previous.called() + calledCount));
            }
        }
        List<DentalChair> chairsList = chairs.findByBranchBranchIdOrderByChairCode(id);
        Map<Long, String> roomLookup = rooms.findAll().stream()
                .collect(Collectors.toMap(Room::getRoomId, Room::getRoomName, (a, b) -> a));
        Map<Long, String> chairLookup = chairsList.stream()
                .collect(Collectors.toMap(DentalChair::getChairId, DentalChair::getChairName, (a, b) -> a));
        Map<Long, String> roomNames = new LinkedHashMap<>();
        Map<Long, String> chairNames = new LinkedHashMap<>();
        for (QueueTicket ticket : rows) {
            if (ticket.getVisit() == null) continue;
            Long vId = ticket.getVisit().getVisitId();
            Long rId = ticket.getVisit().getRoomId();
            Long cId = ticket.getVisit().getChairId();
            String rName = rId != null ? roomLookup.get(rId) : null;
            String cName = cId != null ? chairLookup.get(cId) : null;
            roomNames.put(vId, rName != null ? rName : (rId != null ? "Phòng " + rId : "Chưa phân phòng"));
            chairNames.put(vId, cName != null ? cName : (cId != null ? "Ghế " + cId : "Chưa xếp ghế"));
        }

        List<DoctorQueue> sortedDoctors = doctorQueues.values().stream()
                .sorted(Comparator.comparing(DoctorQueue::name)).toList();

        List<DoctorBoard> doctorBoards = new ArrayList<>();
        for (DoctorQueue dq : sortedDoctors) {
            Map<QueueStatus, List<QueueTicket>> lanesMap = new LinkedHashMap<>();
            lanesMap.put(QueueStatus.WAITING, new ArrayList<>());
            lanesMap.put(QueueStatus.CALLED, new ArrayList<>());
            lanesMap.put(QueueStatus.IN_SERVICE, new ArrayList<>());
            lanesMap.put(QueueStatus.PAUSED, new ArrayList<>());
            lanesMap.put(QueueStatus.DONE, new ArrayList<>());

            for (QueueTicket t : rows) {
                if (t.getVisit() != null && t.getVisit().getPrimaryDoctor() != null
                        && dq.id().equals(t.getVisit().getPrimaryDoctor().getStaffId())) {
                    List<QueueTicket> list = lanesMap.get(t.getStatus());
                    if (list != null) {
                        list.add(t);
                    }
                }
            }
            doctorBoards.add(new DoctorBoard(dq.id(), dq.name(), dq.waiting(), dq.called(), lanesMap));
        }

        List<QueueTicket> unassignedTickets = rows.stream()
                .filter(t -> t.getVisit() == null || t.getVisit().getPrimaryDoctor() == null)
                .toList();
        if (!unassignedTickets.isEmpty()) {
            Map<QueueStatus, List<QueueTicket>> unassignedLanes = new LinkedHashMap<>();
            unassignedLanes.put(QueueStatus.WAITING, new ArrayList<>());
            unassignedLanes.put(QueueStatus.CALLED, new ArrayList<>());
            unassignedLanes.put(QueueStatus.IN_SERVICE, new ArrayList<>());
            unassignedLanes.put(QueueStatus.PAUSED, new ArrayList<>());
            unassignedLanes.put(QueueStatus.DONE, new ArrayList<>());
            for (QueueTicket t : unassignedTickets) {
                List<QueueTicket> list = unassignedLanes.get(t.getStatus());
                if (list != null) {
                    list.add(t);
                }
            }
            doctorBoards.add(new DoctorBoard(null, "Chưa phân công bác sĩ", 0, 0, unassignedLanes));
        }

        return new Board(id, choices, rows, sortedDoctors, doctorBoards,
                chairsList, roomNames, chairNames);
    }

    @Transactional(readOnly = true)
    public PublicBoard publicBoard(Long branchId) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!Boolean.TRUE.equals(branch.getIsActive())) throw error(HttpStatus.NOT_FOUND, "Chi nhánh không hoạt động.");
        List<QueueTicket> all = ordered(branchId);
        List<PublicNumber> called = all.stream().filter(t -> t.getStatus() == QueueStatus.CALLED)
                .sorted(Comparator.comparing(QueueTicket::getCalledAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(QueueTicket::getTicketId, Comparator.reverseOrder()))
                .map(t -> new PublicNumber(t.getTicketId(), t.getDisplayNumber(),
                        t.getVisit().getRoomId() == null ? "" : rooms.findById(t.getVisit().getRoomId()).map(Room::getRoomName).orElse(""),
                        t.getVisit().getChairId() == null ? "" : chairs.findById(t.getVisit().getChairId()).map(DentalChair::getChairName).orElse(""),
                        t.getCalledAt() != null ? t.getCalledAt().atZone(ZONE).toInstant().toEpochMilli() : null)).toList();
        List<PublicNumber> serving = all.stream().filter(t -> t.getStatus() == QueueStatus.IN_SERVICE)
                .map(t -> new PublicNumber(t.getTicketId(), t.getDisplayNumber(),
                        t.getVisit().getRoomId() == null ? "" : rooms.findById(t.getVisit().getRoomId()).map(Room::getRoomName).orElse(""),
                        t.getVisit().getChairId() == null ? "" : chairs.findById(t.getVisit().getChairId()).map(DentalChair::getChairName).orElse(""),
                        t.getVisit().getActualStartTime() != null ? t.getVisit().getActualStartTime().atZone(ZONE).toInstant().toEpochMilli() : null)).toList();
        long waiting = all.stream().filter(t -> t.getStatus() == QueueStatus.WAITING
                && t.getVisit() != null
                && (t.getVisit().getStatus() == VisitStatus.WAITING || t.getVisit().getStatus() == VisitStatus.WAITING_RESULT)).count();
        List<String> next = all.stream().filter(t -> t.getStatus() == QueueStatus.WAITING
                        && t.getVisit() != null
                        && (t.getVisit().getStatus() == VisitStatus.WAITING || t.getVisit().getStatus() == VisitStatus.WAITING_RESULT))
                .limit(5).map(QueueTicket::getDisplayNumber).toList();
        List<PublicQueueRow> queueList = buildPublicQueueList(all);
        return new PublicBoard(branch.getBranchName(), called, serving, next, waiting, queueList, System.currentTimeMillis());
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

    private String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "—";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            String w = parts[0];
            if (w.length() <= 1) return "*";
            if (w.length() == 2) return w.charAt(0) + "*";
            return w.substring(0, 1) + "*".repeat(w.length() - 2) + w.substring(w.length() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String w = parts[i];
            if (i > 0) sb.append(" ");
            if (i == 0) {
                sb.append(w.length() <= 2 ? (w.charAt(0) + "*") : w);
            } else if (i == parts.length - 1) {
                sb.append(w.charAt(0)).append("*");
            } else {
                sb.append(w.charAt(0)).append("**");
            }
        }
        return sb.toString();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return "—";
        String p = phone.trim();
        if (p.length() <= 4) return "****";
        if (p.length() <= 7) return p.substring(0, 2) + "****" + p.substring(p.length() - 1);
        return p.substring(0, 3) + "****" + p.substring(p.length() - 3);
    }

    private List<PublicQueueRow> buildPublicQueueList(List<QueueTicket> all) {
        List<PublicQueueRow> rows = new ArrayList<>();
        Map<Long, String> roomLookup = rooms.findAll().stream()
                .collect(Collectors.toMap(Room::getRoomId, Room::getRoomName, (a, b) -> a));
        Map<Long, String> chairLookup = chairs.findAll().stream()
                .collect(Collectors.toMap(DentalChair::getChairId, DentalChair::getChairName, (a, b) -> a));

        List<QueueTicket> active = all.stream()
                .filter(t -> t.getStatus() != QueueStatus.CANCELLED && t.getStatus() != QueueStatus.DONE)
                .sorted(Comparator.comparingInt((QueueTicket t) -> {
                    if (t.getStatus() == QueueStatus.CALLED) return 0;
                    if (t.getStatus() == QueueStatus.IN_SERVICE) return 1;
                    return 2;
                }).thenComparing(waitingOrder()))
                .toList();

        int stt = 1;
        for (QueueTicket t : active) {
            int idx = stt++;
            Long rId = t.getVisit() != null ? t.getVisit().getRoomId() : null;
            Long cId = t.getVisit() != null ? t.getVisit().getChairId() : null;
            String rawRoom = rId != null ? roomLookup.getOrDefault(rId, "Phòng " + rId) : "";
            String rawChair = cId != null ? chairLookup.getOrDefault(cId, "Ghế " + cId) : "";

            boolean isCallingOrExam = (t.getStatus() == QueueStatus.CALLED || t.getStatus() == QueueStatus.IN_SERVICE);
            String displayRoom = isCallingOrExam ? (rawRoom.isEmpty() ? "Phòng chỉ định" : rawRoom) : "—";
            String displayChair = isCallingOrExam ? (rawChair.isEmpty() ? "Ghế điều trị" : rawChair) : "—";

            rows.add(new PublicQueueRow(
                    idx,
                    t.getDisplayNumber(),
                    displayRoom,
                    displayChair,
                    t.getStatus() != null ? t.getStatus().name() : "WAITING",
                    isCallingOrExam
            ));
        }
        return rows;
    }

    private boolean isWaitingOrResult(Visit visit) {
        return visit != null && (visit.getStatus() == VisitStatus.WAITING || visit.getStatus() == VisitStatus.WAITING_RESULT);
    }

    @Transactional
    public QueueTicket callNext(HttpSession session, Long branchId, Long doctorId) {
        access.require(session);
        throw error(HttpStatus.FORBIDDEN,
                "Hệ thống tự động gọi số theo thứ tự. Lễ tân không được phép gọi số thủ công.");
    }

    @Transactional
    public void transition(HttpSession session, Long id, Action action, String reason) {
        transition(session, id, action, null, reason);
    }

    @Transactional
    public void transition(HttpSession session, Long id, Action action, Long chairId, String reason) {
        QueueTicket snapshot = tickets.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy số khám."));
        Long branchId = snapshot.getBranch().getBranchId();
        ReceptionAccess.Scope scope = requireBranch(session, branchId); // Serialize queue operations at this branch.
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
            case CALL -> throw error(HttpStatus.FORBIDDEN,
                    "Hệ thống tự động gọi số theo thứ tự. Lễ tân không được phép gọi số hoặc gọi vượt thủ công.");
            case PAUSE -> {
                if (before == QueueStatus.CALLED) {
                    throw error(HttpStatus.FORBIDDEN,
                            "Không thể tạm dừng lượt đang Đang gọi để đẩy lượt khác lên. Chỉ bác sĩ phụ trách mới được ghi nhận Vắng mặt tại bàn khám.");
                }
                if (!isTrueAdminUser(scope)) {
                    throw error(HttpStatus.FORBIDDEN,
                            "Lễ tân không có quyền tạm dừng số khám hoặc thay đổi thứ tự hàng chờ.");
                }
                require(before == QueueStatus.WAITING, "Chỉ tạm dừng số đang chờ.");
                require(isWaitingOrResult(visit), "Lượt khám đã bắt đầu.");
                require(validReason(reason), "Cần ghi lý do tạm dừng từ 10 đến 255 ký tự.");
                ticket.setStatus(QueueStatus.PAUSED);
                ticket.setPausedAt(LocalDateTime.now(ZONE));
                ticket.setPauseReason(reason.trim());
            }
            case RESUME -> {
                require(before == QueueStatus.PAUSED && isWaitingOrResult(visit), "Chỉ tiếp tục số đang tạm dừng.");
                ticket.setStatus(QueueStatus.WAITING);
                if (isClinicPauseReason(ticket.getPauseReason())) {
                    ticket.setResumedAt(null);
                } else {
                    ticket.setResumedAt(LocalDateTime.now(ZONE));
                }
            }
            case EMERGENCY -> {
                if (queueCoordinator != null) {
                    queueCoordinator.reportEmergency(branchId, id, scope.user(), reason);
                    return;
                }
                require((before == QueueStatus.WAITING || before == QueueStatus.PAUSED || before == QueueStatus.CALLED)
                        && isWaitingOrResult(visit), "Chỉ ưu tiên lượt chưa bắt đầu.");
                require(validReason(reason), "Cần ghi lý do cấp cứu từ 10 đến 255 ký tự.");
                ticket.setIsEmergency(true); ticket.setIsPriority(true); ticket.setPriorityScore(100); ticket.setPriorityReason(reason.trim());
                visit.setVisitType(VisitType.EMERGENCY);
                visit.setIsEmergency(true);
                visits.save(visit);
                if (before == QueueStatus.PAUSED) {
                    ticket.setStatus(QueueStatus.WAITING);
                    ticket.setResumedAt(LocalDateTime.now(ZONE));
                }
            }
            case PRIORITIZE -> throw error(HttpStatus.FORBIDDEN,
                    "Không được phép tự nâng mức ưu tiên thủ công. Hệ thống xếp hàng theo quy tắc tự động hoặc báo cấp cứu kèm lý do.");
            case START -> {
                require(before == QueueStatus.CALLED && isWaitingOrResult(visit),
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
                visit.setStatus(VisitStatus.IN_EXAM);
                if (visit.getActualStartTime() == null) {
                    visit.setActualStartTime(LocalDateTime.now(ZONE));
                }
                visits.save(visit);
                ticket.setStatus(QueueStatus.IN_SERVICE);
            }
            case DONE -> {
                require(before == QueueStatus.IN_SERVICE, "Chỉ hoàn tất số đã bắt đầu khám.");
                require(Set.of(VisitStatus.IN_EXAM, VisitStatus.WAITING_PARACLINICAL, VisitStatus.WAITING_RESULT,
                                VisitStatus.WAITING_PAYMENT, VisitStatus.COMPLETED)
                                .contains(visit.getStatus()),
                        "Chỉ hoàn tất số sau khi bệnh nhân rời ghế khám hoặc hoàn thành lượt khám.");
                LocalDateTime doneNow = LocalDateTime.now(ZONE);
                if (visit.getActualEndTime() == null) {
                    visit.setActualEndTime(doneNow);
                }
                if (visit.getStatus() == VisitStatus.IN_EXAM) {
                    visit.setStatus(VisitStatus.WAITING_PAYMENT);
                    visits.saveAndFlush(visit);
                } else {
                    visits.saveAndFlush(visit);
                }
                boolean engineEnabled = branches.findById(branchId)
                        .map(b -> Boolean.TRUE.equals(b.getResourceEngineEnabled()))
                        .orElse(false);
                if (visit.getChairId() != null) {
                    chairs.findById(visit.getChairId()).ifPresent(chair -> {
                        if (chair.getStatus() == ChairStatus.IN_USE) {
                            chair.setStatus(engineEnabled ? ChairStatus.TURNOVER : ChairStatus.AVAILABLE);
                            chairs.save(chair);
                        }
                    });
                }
                if (engineEnabled && visit.getAppointmentId() != null && resourceBookingTransactionService != null) {
                    resourceBookingTransactionService.onVisitDone(visit.getAppointmentId(), doneNow);
                }
                ticket.setStatus(QueueStatus.DONE); ticket.setCompletedAt(doneNow);
            }
        }
        tickets.save(ticket);
        audit(session, ticket, action.name(), before, reason);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.cvdcms.event.ClinicQueueDomainEvent(
                    "QUEUE_" + action.name(),
                    branchId,
                    visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getStaffId() : null,
                    visit.getVisitId(),
                    visit.getAppointmentId(),
                    ticket.getStatus().name(),
                    "Cập nhật hàng đợi phiếu " + ticket.getDisplayNumber() + " -> " + ticket.getStatus().name(),
                    LocalDateTime.now(ZONE)));
        }
        if (queueCoordinator != null && visit.getPrimaryDoctor() != null
                && (action == Action.RESUME || action == Action.DONE)) {
            queueCoordinator.autoCallNextForDoctorIfIdle(
                    branchId,
                    visit.getPrimaryDoctor().getStaffId(),
                    scope.user().getUserId());
        }
    }

    /**
     * Xác nhận hoàn tất vệ sinh ghế (Turnover completed): chuyển ghế TURNOVER -> AVAILABLE
     * và giải phóng các reservation ROOM/CHAIR/EQUIPMENT(TURNOVER, FULL_STAY) còn lại.
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void completeChairTurnover(HttpSession session, Long chairId, Long appointmentId) {
        ReceptionAccess.Scope scope = access.require(session);
        DentalChair chair = chairs.findById(chairId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy ghế nha khoa."));
        Long branchId = chair.getBranch().getBranchId();
        require(scope.permits(branchId), "Không có quyền thao tác ghế tại chi nhánh này.");
        if (resourceBookingTransactionService != null) {
            resourceBookingTransactionService.completeChairTurnover(chairId, appointmentId, LocalDateTime.now(ZONE));
        } else if (chair.getStatus() == ChairStatus.TURNOVER) {
            chair.setStatus(ChairStatus.AVAILABLE);
            chairs.save(chair);
        }
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
        if (queueCoordinator != null) {
            return queueCoordinator.waitingQueueComparator();
        }
        return Comparator.comparingInt((QueueTicket t) -> queueTierRank(t) == 1 ? 0 : 1)
                .thenComparingInt((QueueTicket t) -> t.getVisit() != null && t.getVisit().getStatus() == VisitStatus.WAITING_RESULT ? 0 : 1)
                .thenComparingInt(this::queueTierRank)
                .thenComparing(t -> Boolean.TRUE.equals(t.getIsPriority()) ? 0 : 1)
                .thenComparing(QueueTicket::getTicketNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing((QueueTicket t) -> t.getIssuedAt() != null
                                ? t.getIssuedAt()
                                : (t.getVisit() != null ? t.getVisit().getCheckInAt() : t.getResumedAt()),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(QueueTicket::getTicketId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * 4 cấp ưu tiên hàng đợi chuẩn:
     * 1. Cấp cứu (isEmergency = true)
     * 2. Appointment check-in đúng giờ trong Grace Period 15 phút (isBookedAndOnTime = true)
     * 3. Walk-in
     * 4. Appointment check-in trễ > 15 phút
     */
    private int queueTierRank(QueueTicket t) {
        if (queueCoordinator != null) {
            return queueCoordinator.queueTierRank(t);
        }
        if (t == null) return 4;
        Visit v = t.getVisit();
        if (Boolean.TRUE.equals(t.getIsEmergency())
                || (v != null && (Boolean.TRUE.equals(v.getIsEmergency()) || v.getVisitType() == VisitType.EMERGENCY))) {
            return 1;
        }
        if (v != null) {
            if (Boolean.TRUE.equals(v.getIsBookedAndOnTime())
                    && (v.getAppointmentId() != null || v.getVisitType() == VisitType.APPOINTMENT)) {
                return 2;
            }
            if (v.getAppointmentId() == null && v.getVisitType() != VisitType.APPOINTMENT) {
                return 3;
            }
            String reason = t.getPriorityReason() != null ? t.getPriorityReason().toUpperCase(Locale.ROOT) : "";
            if (reason.contains("EARLY_WAITING") || (t.getPriorityScore() != null && t.getPriorityScore() >= 50)) {
                return 3;
            }
            return 4;
        }
        return (t.getPriorityScore() != null && t.getPriorityScore() >= 75) ? 2 : 3;
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
    private ReceptionAccess.Scope requireBranch(HttpSession session, Long id) {
        var scope = access.require(session);
        Branch branch = branches.lock(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!scope.permits(id) || !Boolean.TRUE.equals(branch.getIsActive()))
            throw error(HttpStatus.FORBIDDEN, "Không được quản lý hàng chờ này.");
        return scope;
    }

    private boolean isTrueAdminUser(ReceptionAccess.Scope scope) {
        if (scope == null || !scope.admin() || scope.user() == null || scope.user().getRoles() == null) {
            return false;
        }
        return scope.user().getRoles().stream().anyMatch(r -> r != null && Boolean.TRUE.equals(r.getIsActive())
                && r.getRoleName() != null
                && (r.getRoleName().equalsIgnoreCase("ADMIN")
                        || r.getRoleName().equalsIgnoreCase("ROLE_ADMIN")));
    }
    private void require(boolean valid, String message) { if (!valid) throw error(HttpStatus.CONFLICT, message); }
    private ResponseStatusException error(HttpStatus code, String reason) { return new ResponseStatusException(code, reason); }
    public enum Action { CALL, PAUSE, RESUME, PRIORITIZE, EMERGENCY, START, DONE }
    public record DoctorQueue(Long id, String name, long waiting, long called) { }
    public record DoctorBoard(Long doctorId, String doctorName, long waiting, long called,
                              Map<QueueStatus, List<QueueTicket>> lanes) { }
    public record Board(Long branchId, List<Branch> branches, List<QueueTicket> tickets,
                        List<DoctorQueue> doctors, List<DoctorBoard> doctorBoards,
                        List<DentalChair> chairs, Map<Long, String> roomNames, Map<Long, String> chairNames) {
        public Board(Long branchId, List<Branch> branches, List<QueueTicket> tickets, List<DoctorQueue> doctors, List<DentalChair> chairs) {
            this(branchId, branches, tickets, doctors, List.of(), chairs, Map.of(), Map.of());
        }
        public Board(Long branchId, List<Branch> branches, List<QueueTicket> tickets, List<DoctorQueue> doctors) {
            this(branchId, branches, tickets, doctors, List.of(), List.of(), Map.of(), Map.of());
        }
    }
    public record PublicNumber(Long ticketId, String number, String room, String chair, Long callTime) {
        public PublicNumber(Long ticketId, String number, String room, String chair) {
            this(ticketId, number, room, chair, null);
        }
        public PublicNumber(String number, String room, String chair) {
            this(null, number, room, chair, null);
        }
    }
    public record PublicBoard(String branch, List<PublicNumber> called, List<PublicNumber> serving,
                              List<String> next, long waiting, List<PublicQueueRow> queueList,
                              long serverNowEpochMs) {
        public PublicBoard(String branch, List<PublicNumber> called, List<PublicNumber> serving,
                           List<String> next, long waiting) {
            this(branch, called, serving, next, waiting, List.of(), System.currentTimeMillis());
        }
        public PublicBoard(String branch, List<PublicNumber> called, List<PublicNumber> serving,
                           List<String> next, long waiting, List<PublicQueueRow> queueList) {
            this(branch, called, serving, next, waiting, queueList, System.currentTimeMillis());
        }
    }
    public record PublicQueueRow(
            int stt,
            String ticketNumber,
            String roomName,
            String chairName,
            String status,
            boolean isCallingOrExam
    ) {
    }
}
