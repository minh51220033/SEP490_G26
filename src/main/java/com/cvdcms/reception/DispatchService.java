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
    public static final int MAX_DOCTOR_QUEUE_LIMIT = 8;
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

    @Autowired
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

    @Transactional
    public Board board(HttpSession session, Long requestedBranch) {
        var scope = access.require(session);
        List<Branch> options = branches.findAll().stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()) && scope.permits(b.getBranchId()))
                .sorted(Comparator.comparing(Branch::getBranchName)).toList();
        if (options.isEmpty()) throw fail(HttpStatus.FORBIDDEN, "Không có chi nhánh được phân công.");
        Long bid = requestedBranch;
        if (bid == null && session != null) {
            Object sId = session.getAttribute("currentBranchId");
            if (sId instanceof Long sid && options.stream().anyMatch(b -> b.getBranchId().equals(sid))) {
                bid = sid;
            }
        }
        if (bid == null) {
            bid = options.get(0).getBranchId();
        }
        final Long branchId = bid;
        if (session != null) {
            session.setAttribute("currentBranchId", branchId);
        }
        checkBranch(session, branchId);
        LocalDate resolvedDay = null;
        if (session != null) {
            Object sDay = session.getAttribute("currentWorkflowDay");
            if (sDay instanceof LocalDate sd) resolvedDay = sd;
        }
        final LocalDate day = resolvedDay != null ? resolvedDay : LocalDate.now(ZONE);
        List<Visit> dayVisits = visits.today(branchId, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        List<StaffSchedule> todaySchedules = schedules.findTodaySchedules(branchId, day);
        Map<Long, LocalTime> shiftStarts = new HashMap<>();
        for (StaffSchedule s : todaySchedules) {
            if (s.getStaff() != null && s.getShift() != null && s.getShift().getStartTime() != null) {
                shiftStarts.putIfAbsent(s.getStaff().getStaffId(), s.getShift().getStartTime());
            }
        }
        List<StaffProfile> availableDoctors = findAvailableDoctors(branchId, day, LocalTime.now(ZONE));
        Map<Long, StaffProfile> doctorMap = new LinkedHashMap<>();
        for (StaffProfile d : availableDoctors) {
            doctorMap.put(d.getStaffId(), d);
        }
        for (Visit v : dayVisits) {
            if (isWaitingDispatchVisit(v) && v.getPrimaryDoctor() != null) {
                doctorMap.putIfAbsent(v.getPrimaryDoctor().getStaffId(), v.getPrimaryDoctor());
            }
        }
        List<DoctorOption> doctorRows = doctorMap.values().stream()
                .map(d -> {
                    long w = load(d.getStaffId(), branchId, day, -1L);
                    LocalTime st = shiftStarts.get(d.getStaffId());
                    String stStr = st != null ? st.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) : "08:00";
                    return new DoctorOption(d.getStaffId(), d.getFullName(), w, w >= MAX_DOCTOR_QUEUE_LIMIT, stStr);
                })
                .sorted(Comparator.comparingLong(DoctorOption::waiting).thenComparing(DoctorOption::name)).toList();
        Map<Long, DoctorRequest> requests = new HashMap<>();
        List<Long> appointmentIds = dayVisits.stream().map(Visit::getAppointmentId).filter(Objects::nonNull).distinct().toList();
        appointments.findAllById(appointmentIds).forEach(a -> {
            if (a.getDoctor() != null) requests.put(a.getAppointmentId(),
                    new DoctorRequest(a.getDoctor().getStaffId(), a.getDoctor().getFullName(),
                            doctorRows.stream().anyMatch(d -> d.id().equals(a.getDoctor().getStaffId()))));
        });
        List<QueueTicket> dayTickets = new ArrayList<>(tickets.board(branchId, day, QueueType.EXAM));
        Map<Long, String> issued = new HashMap<>();
        Map<Long, Long> ticketIds = new HashMap<>();
        dayTickets.forEach(t -> {
            if (t.getVisit() != null) {
                issued.put(t.getVisit().getVisitId(), t.getDisplayNumber());
                ticketIds.put(t.getVisit().getVisitId(), t.getTicketId());
            }
        });

        // Tự động cấp số thứ tự cho các bệnh nhân đang chờ đã có Bác sĩ phụ trách để khớp 100% với Hàng chờ gọi số
        for (Visit v : dayVisits) {
            if (isWaitingDispatchVisit(v) && v.getPrimaryDoctor() != null && !issued.containsKey(v.getVisitId())) {
                try {
                    if (branches.lock(branchId).isPresent() && visits.lock(v.getVisitId()).isPresent()) {
                        boolean autoPriority = v.getVisitType() == VisitType.EMERGENCY
                                || v.getStatus() == VisitStatus.WAITING_RESULT
                                || (v.getNotes() != null && v.getNotes().contains("ƯU TIÊN"));
                        String defaultReason = autoPriority ? "Cấp số ưu tiên tự động theo phân loại tiếp đón" : null;
                        QueueTicket autoTicket = issue(session, branchId, v.getVisitId(), autoPriority, defaultReason);
                        if (autoTicket != null) {
                            dayTickets.add(autoTicket);
                            issued.put(v.getVisitId(), autoTicket.getDisplayNumber());
                            ticketIds.put(v.getVisitId(), autoTicket.getTicketId());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        List<TicketRow> queue = dayTickets.stream().map(this::ticketRow).toList();
        List<Room> activeRooms = rooms.findByBranchBranchIdAndStatusOrderByRoomCode(branchId, RoomStatus.ACTIVE);
        List<DentalChair> branchChairs = chairs.findByBranchBranchIdOrderByChairCode(branchId);

        Map<Long, String> roomLookup = new HashMap<>();
        for (Room r : activeRooms) {
            roomLookup.put(r.getRoomId(), r.getRoomName());
        }
        Map<Long, String> chairLookup = new HashMap<>();
        Map<Long, String> chairRoomLookup = new HashMap<>();
        for (DentalChair c : branchChairs) {
            chairLookup.put(c.getChairId(), c.getChairName());
            if (c.getRoom() != null && c.getRoom().getRoomName() != null) {
                chairRoomLookup.put(c.getChairId(), c.getRoom().getRoomName());
            }
        }
        String defaultRoomName = !activeRooms.isEmpty() ? activeRooms.get(0).getRoomName() : "Phòng khám Nha khoa";

        // Chỉ giữ lại các lượt khám đang chờ điều phối (WAITING / WAITING_RESULT),
        // loại bỏ IN_EXAM, IN_TREATMENT, COMPLETED, WAITING_PAYMENT, CANCELLED khỏi bảng điều phối.
        // Đẩy các bệnh nhân vãng lai / chưa xếp hàng (primaryDoctor == null) xuống cuối danh sách.
        List<Visit> sortedVisits = dayVisits.stream()
                .filter(Board::isWaitingDispatchVisit)
                .sorted(Comparator.comparing((Visit v) -> v.getPrimaryDoctor() == null ? 1 : 0)
                        .thenComparing((Visit v) -> v.getVisitType() == VisitType.EMERGENCY ? 0 : 1)
                        .thenComparing((Visit v) -> v.getStatus() == VisitStatus.WAITING_RESULT ? 0 : 1)
                        .thenComparing((Visit v) -> {
                            String num = issued.get(v.getVisitId());
                            boolean isPriority = (num != null && num.startsWith("P-"))
                                    || (v.getNotes() != null && v.getNotes().contains("ƯU TIÊN"));
                            return isPriority ? 0 : 1;
                        })
                        .thenComparing(Visit::getCheckInAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Visit::getVisitId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // Tính toán thứ tự vào khám cụ thể theo từng bác sĩ (Doctor Queue Order) & Map Phòng/Ghế
        Map<Long, DoctorQueueInfo> queueInfo = new HashMap<>();
        Map<Long, Integer> doctorWaitingCounter = new HashMap<>();
        Map<Long, String> visitRoomNames = new HashMap<>();
        Map<Long, String> visitChairNames = new HashMap<>();

        for (Visit v : sortedVisits) {
            String rName = v.getRoomId() != null ? roomLookup.get(v.getRoomId()) : null;
            if (rName == null && v.getChairId() != null) {
                rName = chairRoomLookup.get(v.getChairId());
            }
            if (rName == null && v.getPrimaryDoctor() != null && !activeRooms.isEmpty()) {
                int idx = Math.abs(v.getPrimaryDoctor().getStaffId().intValue()) % activeRooms.size();
                rName = activeRooms.get(idx).getRoomName();
            }
            visitRoomNames.put(v.getVisitId(), rName != null ? rName : defaultRoomName);

            String cName = v.getChairId() != null ? chairLookup.get(v.getChairId()) : null;
            if (cName == null && v.getPrimaryDoctor() != null && !branchChairs.isEmpty()) {
                int idx = Math.abs(v.getPrimaryDoctor().getStaffId().intValue()) % branchChairs.size();
                cName = branchChairs.get(idx).getChairName();
            }
            visitChairNames.put(v.getVisitId(), cName != null ? cName : "Ghế tiêu chuẩn");

            if (v.getPrimaryDoctor() == null) {
                queueInfo.put(v.getVisitId(), new DoctorQueueInfo(-1, "Chưa xếp hàng", "badge bg-warning-subtle text-warning-emphasis border border-warning-subtle", "Chưa phân BS"));
            } else {
                String docName = v.getPrimaryDoctor().getFullName();
                int pos = doctorWaitingCounter.merge(v.getPrimaryDoctor().getStaffId(), 1, Integer::sum);
                boolean isEmergency = v.getVisitType() == VisitType.EMERGENCY;
                boolean isPriority = (issued.get(v.getVisitId()) != null && issued.get(v.getVisitId()).startsWith("P-"))
                        || (v.getNotes() != null && v.getNotes().contains("ƯU TIÊN"))
                        || v.getStatus() == VisitStatus.WAITING_RESULT;
                if (isEmergency) {
                    queueInfo.put(v.getVisitId(), new DoctorQueueInfo(pos, "Cấp cứu (#" + pos + ")", "badge bg-danger text-white fw-bold", docName));
                } else if (isPriority) {
                    queueInfo.put(v.getVisitId(), new DoctorQueueInfo(pos, "Ưu tiên (#" + pos + ")", "badge bg-warning text-dark fw-bold", docName));
                } else if (pos == 1) {
                    queueInfo.put(v.getVisitId(), new DoctorQueueInfo(pos, "Chờ #1 (Tiếp theo)", "badge bg-info-subtle text-info-emphasis border border-info-subtle fw-semibold", docName));
                } else {
                    queueInfo.put(v.getVisitId(), new DoctorQueueInfo(pos, "Chờ #" + pos, "badge bg-light text-secondary border", docName));
                }
            }
        }

        return new Board(branchId, options, sortedVisits, doctorRows,
                activeRooms, branchChairs, queue, day, requests, issued,
                sortedVisits.stream().filter(v -> v.getPrimaryDoctor() == null && Board.isWaitingDispatchVisit(v)).toList(),
                Board.buildDoctorVisits(sortedVisits, doctorRows),
                Board.buildHandedOverVisits(sortedVisits, doctorRows),
                queueInfo, ticketIds, visitRoomNames, visitChairNames);
    }

    @Transactional
    public QueueTicket completeDispatchAndIssueTicket(HttpSession session, Long branchId, Long visitId,
            Long doctorId, Long roomId, Long chairId, boolean priority, String reason, String note) {
        Visit existing = visits.findById(visitId).orElse(null);
        StaffProfile prevDoctor = existing != null ? existing.getPrimaryDoctor() : null;
        Long prevRoom = existing != null ? existing.getRoomId() : null;
        Long prevChair = existing != null ? existing.getChairId() : null;
        String prevNotes = existing != null ? existing.getNotes() : null;
        VisitStatus prevStatus = existing != null ? existing.getStatus() : null;

        try {
            assign(session, branchId, visitId, doctorId, roomId, chairId, reason, note);
            QueueTicket ticket = issue(session, branchId, visitId, priority, reason);
            Visit target = ticket != null && ticket.getVisit() != null ? ticket.getVisit() : visits.findById(visitId).orElse(existing);
            if (target != null && target.getStatus() != VisitStatus.WAITING && target.getStatus() != VisitStatus.WAITING_RESULT) {
                target.setStatus(VisitStatus.WAITING);
                visits.save(target);
            }
            return ticket;
        } catch (RuntimeException ex) {
            Visit target = visits.findById(visitId).orElse(existing);
            if (target != null) {
                target.setPrimaryDoctor(prevDoctor);
                target.setRoomId(prevRoom);
                target.setChairId(prevChair);
                target.setNotes(prevNotes);
                if (prevStatus != null) {
                    target.setStatus(prevStatus);
                }
            }
            throw ex;
        }
    }

    @Transactional
    public QueueTicket completeDispatchAndIssueTicket(HttpSession session, Long branchId, Long visitId,
            Long doctorId, Long roomId, Long chairId, boolean priority, String reason) {
        return completeDispatchAndIssueTicket(session, branchId, visitId, doctorId, roomId, chairId, priority, reason, null);
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
        List<StaffProfile> available = findAvailableDoctors(branchId, today, now);
        if (available.isEmpty()) {
            throw fail(HttpStatus.CONFLICT, "Chi nhánh hiện không có bác sĩ nào đang trong ca trực tiếp nhận khám.");
        }
        Long requestedDoctor = requestedDoctor(visit);
        boolean isEmergency = visit.getVisitType() == VisitType.EMERGENCY;
        if (doctorId == null) {
            if (isEmergency) {
                doctorId = findEmergencyDoctor(available, branchId, today, visitId);
            } else if (requestedDoctor != null && available.stream().anyMatch(d -> d.getStaffId().equals(requestedDoctor))) {
                doctorId = requestedDoctor;
            } else {
                doctorId = available.stream()
                        .min(Comparator.comparingLong((StaffProfile d) -> load(d.getStaffId(), branchId, today, visitId))
                                .thenComparing(StaffProfile::getStaffId))
                        .map(StaffProfile::getStaffId)
                        .orElseThrow(() -> fail(HttpStatus.CONFLICT, "Không có bác sĩ khả dụng để tự phân công."));
            }
        }
        final Long selectedDoctor = doctorId;
        if (available.stream().noneMatch(d -> d.getStaffId().equals(selectedDoctor)))
            throw fail(HttpStatus.CONFLICT, "Bác sĩ không có phân công hoặc ca làm việc hợp lệ tại chi nhánh.");
        Long previousDoctor = visit.getPrimaryDoctor() == null ? null : visit.getPrimaryDoctor().getStaffId();
        boolean overridesRequest = requestedDoctor != null && !requestedDoctor.equals(doctorId);
        boolean transfersQueue = previousDoctor != null && !previousDoctor.equals(doctorId);
        if (transfersQueue && tickets.findByVisitVisitIdAndQueueType(visitId, QueueType.EXAM)
                .filter(t -> t.getStatus() != QueueStatus.WAITING && t.getStatus() != QueueStatus.PAUSED).isPresent())
            throw fail(HttpStatus.CONFLICT, "Số đã được gọi hoặc đang phục vụ; không thể chuyển bác sĩ tại bàn điều phối.");

        // Ràng buộc chống quá tải khi chuyển bác sĩ
        if (transfersQueue) {
            long targetLoad = load(selectedDoctor, branchId, today, visitId);
            if (targetLoad >= MAX_DOCTOR_QUEUE_LIMIT) {
                StaffProfile targetDoc = doctors.findById(selectedDoctor).orElse(null);
                String docName = targetDoc != null ? targetDoc.getFullName() : "này";
                throw fail(HttpStatus.CONFLICT, "Bác sĩ " + docName
                        + " hiện đang có " + targetLoad + " bệnh nhân chờ, đã đạt giới hạn tiếp nhận tối đa trong ca ("
                        + MAX_DOCTOR_QUEUE_LIMIT + " lượt). Vui lòng chọn bác sĩ khác để tránh quá tải.");
            }
        }

        String normalizedReason = reason == null ? "" : reason.trim();
        if ((overridesRequest || transfersQueue) && (normalizedReason.length() < 10 || normalizedReason.length() > 255)) {
            throw fail(HttpStatus.BAD_REQUEST, "Đổi bác sĩ yêu cầu hoặc chuyển hàng chờ cần lý do từ 10 đến 255 ký tự.");
        }
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

    @Transactional
    public void assign(HttpSession session, Long branchId, Long visitId, Long doctorId, Long roomId, Long chairId,
            String reason, String note) {
        assign(session, branchId, visitId, doctorId, roomId, chairId, reason);
        if (note != null && !note.trim().isEmpty()) {
            Visit v = visits.findById(visitId).orElse(null);
            if (v != null) {
                String existing = v.getNotes();
                String trimmed = note.trim();
                if (existing != null && !existing.isBlank()) {
                    if (!existing.contains(trimmed)) {
                        v.setNotes(existing + " | Dặn dò BS: " + trimmed);
                    }
                } else {
                    v.setNotes("Dặn dò BS: " + trimmed);
                }
                visits.save(v);
            }
        }
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
        boolean notePriority = visit.getNotes() != null && visit.getNotes().contains("ƯU TIÊN");
        boolean agePriority = false;
        if (visit.getPatientProfile() != null && visit.getPatientProfile().getDob() != null) {
            int age = Period.between(visit.getPatientProfile().getDob(), day).getYears();
            if (age >= 65 || age <= 6) {
                agePriority = true;
            }
        }
        boolean autoPriority = emergency || returningParaclinical || notePriority || agePriority;
        if (autoPriority) {
            priority = true;
        }
        String priorityReason = reason == null ? "" : reason.trim();
        if (returningParaclinical && priorityReason.isEmpty()) {
            priorityReason = "Bệnh nhân quay lại sau cận lâm sàng chờ đọc kết quả";
        }
        if ((notePriority || agePriority) && priorityReason.isEmpty()) {
            priorityReason = "Bệnh nhân thuộc diện ưu tiên khám theo chỉ định tiếp đón";
        }
        String emergencyReason = visit.getInitialSymptoms() == null ? "" : visit.getInitialSymptoms().trim();
        if (emergency && (emergencyReason.length() < 10 || emergencyReason.length() > 255)) {
            emergencyReason = priorityReason;
        }
        if (emergency && (emergencyReason.length() < 10 || emergencyReason.length() > 255)) {
            throw fail(HttpStatus.BAD_REQUEST, "Ca cấp cứu cần mô tả triệu chứng hoặc lý do ưu tiên từ 10 đến 255 ký tự.");
        }
        if (priority && !emergency && (priorityReason.length() < 10 || priorityReason.length() > 255)) {
            throw fail(HttpStatus.BAD_REQUEST, "Cấp số ưu tiên cần ghi lý do từ 10 đến 255 ký tự.");
        }
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

    @Transactional(readOnly = true)
    public ExamTicketPrintView printView(HttpSession session, Long id) {
        QueueTicket ticket = ticket(session, id);
        Visit visit = ticket.getVisit();
        String roomName = visit.getRoomId() == null ? null : rooms.findById(visit.getRoomId()).map(Room::getRoomName).orElse(null);
        String chairName = visit.getChairId() == null ? null : chairs.findById(visit.getChairId()).map(DentalChair::getChairName).orElse(null);
        return new ExamTicketPrintView(ticket.getBranch().getPhone(), roomName, chairName);
    }

    public record ExamTicketPrintView(String branchPhone, String roomName, String chairName) {}

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
    private List<StaffProfile> findAvailableDoctors(Long branchId, LocalDate day, LocalTime now) {
        List<StaffProfile> eligible = doctors.eligible(branchId, day);
        if (eligible.isEmpty()) return List.of();
        List<StaffProfile> workingNow = eligible.stream()
                .filter(d -> schedules.working(d.getStaffId(), branchId, day, now) > 0).toList();
        if (!workingNow.isEmpty()) {
            return workingNow;
        }
        return eligible.stream()
                .filter(d -> schedules.scheduledToday(d.getStaffId(), branchId, day) > 0).toList();
    }

    private Long findEmergencyDoctor(List<StaffProfile> available, Long branchId, LocalDate day, Long visitId) {
        List<StaffSchedule> todayShifts = schedules.findTodaySchedules(branchId, day);
        Map<Long, LocalTime> startTimes = new HashMap<>();
        for (StaffSchedule s : todayShifts) {
            if (s.getStaff() != null && s.getShift() != null && s.getShift().getStartTime() != null) {
                startTimes.putIfAbsent(s.getStaff().getStaffId(), s.getShift().getStartTime());
            }
        }
        return available.stream()
                .min(Comparator.comparing((StaffProfile d) -> startTimes.getOrDefault(d.getStaffId(), LocalTime.MAX))
                        .thenComparingLong(d -> load(d.getStaffId(), branchId, day, visitId))
                        .thenComparing(StaffProfile::getStaffId))
                .map(StaffProfile::getStaffId)
                .orElse(null);
    }

    public static boolean isWaitingDispatchVisit(Visit v) {
        return v != null && (v.getStatus() == VisitStatus.WAITING || v.getStatus() == VisitStatus.WAITING_RESULT);
    }

    private ResponseStatusException fail(HttpStatus s, String m) { return new ResponseStatusException(s, m); }
    public record DoctorOption(Long id, String name, long waiting, boolean isFull, String shiftStart) {
        public DoctorOption(Long id, String name, long waiting) {
            this(id, name, waiting, waiting >= MAX_DOCTOR_QUEUE_LIMIT, "08:00");
        }
    }
    public record DoctorQueueInfo(int position, String label, String badgeClass, String doctorName) {}
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
                        List<Visit> handedOverVisits, Map<Long, DoctorQueueInfo> queueInfo,
                        Map<Long, Long> ticketIds, Map<Long, String> roomNames, Map<Long, String> chairNames) {
        public List<TicketRow> getTickets() { return queue(); }
        public List<TicketRow> tickets() { return queue(); }

        public Board(Long branchId, List<Branch> branches, List<Visit> visits, List<DoctorOption> doctors,
                     List<Room> rooms, List<DentalChair> chairs, List<TicketRow> queue, LocalDate day,
                     Map<Long, DoctorRequest> requests, Map<Long, String> issued,
                     Map<Long, DoctorQueueInfo> queueInfo) {
            this(branchId, branches, visits, doctors, rooms, chairs, queue, day, requests, issued,
                    visits.stream().filter(v -> v.getPrimaryDoctor() == null && isWaitingDispatchVisit(v)).toList(),
                    buildDoctorVisits(visits, doctors),
                    buildHandedOverVisits(visits, doctors),
                    queueInfo, Map.of(), Map.of(), Map.of());
        }

        public Board(Long branchId, List<Branch> branches, List<Visit> visits, List<DoctorOption> doctors,
                     List<Room> rooms, List<DentalChair> chairs, List<TicketRow> queue, LocalDate day,
                     Map<Long, DoctorRequest> requests, Map<Long, String> issued) {
            this(branchId, branches, visits, doctors, rooms, chairs, queue, day, requests, issued,
                    visits.stream().filter(v -> v.getPrimaryDoctor() == null && isWaitingDispatchVisit(v)).toList(),
                    buildDoctorVisits(visits, doctors),
                    buildHandedOverVisits(visits, doctors),
                    Map.of(), Map.of(), Map.of(), Map.of());
        }

        public static boolean isWaitingDispatchVisit(Visit v) {
            return DispatchService.isWaitingDispatchVisit(v);
        }

        private static List<Visit> buildHandedOverVisits(List<Visit> visits, List<DoctorOption> doctors) {
            Set<Long> onDuty = doctors.stream().map(DoctorOption::id).collect(Collectors.toSet());
            return visits.stream()
                    .filter(v -> v.getPrimaryDoctor() != null && !onDuty.contains(v.getPrimaryDoctor().getStaffId()))
                    .filter(Board::isWaitingDispatchVisit)
                    .toList();
        }

        private static Map<Long, List<Visit>> buildDoctorVisits(List<Visit> visits, List<DoctorOption> doctors) {
            Map<Long, List<Visit>> map = new LinkedHashMap<>();
            for (DoctorOption doc : doctors) {
                map.put(doc.id(), visits.stream()
                        .filter(v -> v.getPrimaryDoctor() != null && doc.id().equals(v.getPrimaryDoctor().getStaffId()))
                        .filter(Board::isWaitingDispatchVisit)
                        .toList());
            }
            return map;
        }
    }
}
