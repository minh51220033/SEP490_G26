package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.event.ClinicQueueDomainEvent;
import com.cvdcms.repository.*;
import com.cvdcms.security.UrlIdCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Điều phối trung tâm cho hàng đợi khám trong ngày:
 * - Quy chuẩn sắp xếp 4 cấp ưu tiên + thứ tự cấp số ổn định dùng chung cho Lễ tân & Bác sĩ
 * - Tự động cấp số khám theo quy tắc hiện có (idempotent, không cấp trùng khi tải lại/check-in lại)
 * - Tự động gọi đúng 1 bệnh nhân đầu hàng khi bàn trống & sẵn sàng
 * - Bác sĩ bấm "Vào khám" (tự xác định lượt đang gọi, mở lại nếu đang khám) & "Vắng mặt"
 * - Báo cấp cứu tức thì (lưu người báo, thời điểm, lý do; không ghi đè ca đang khám hay gọi vượt khi bàn bận)
 */
@Service
public class ClinicQueueCoordinatorService {

    public static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Logger log = LoggerFactory.getLogger(ClinicQueueCoordinatorService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Set<QueueStatus> ACTIVE_DESK_STATUSES = Set.of(QueueStatus.CALLED, QueueStatus.IN_SERVICE);
    private static final Set<VisitStatus> WAITING_VISIT_STATUSES = Set.of(VisitStatus.WAITING, VisitStatus.WAITING_RESULT);
    private static final Set<VisitStatus> ACTIVE_EXAM_VISIT_STATUSES = Set.of(VisitStatus.IN_EXAM, VisitStatus.IN_TREATMENT);

    /**
     * TODO: Tạm thời cho phép tài khoản ADMIN xem trạng thái bàn & bấm Vào khám/Vắng mặt khi test nhanh.
     * Đặt thành false sau khi test xong để khóa quyền ADMIN không vào ca khám thay bác sĩ.
     */
    private static final boolean ALLOW_ADMIN_DESK_ACCESS_FOR_TESTING = true;

    private final BranchRepository branches;
    private final ClinicalQueueTicketRepository tickets;
    private final VisitRepository visits;
    private final DentalChairRepository chairs;
    private final RoomRepository rooms;
    private final StaffProfileRepository staffProfiles;
    private final StaffBranchAssignmentRepository branchAssignments;
    private final SystemAuditLogRepository audits;
    private final UserRepository users;
    private final ClinicalExaminationBootstrapService examinations;
    private final UrlIdCodec urlIdCodec;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    public ClinicQueueCoordinatorService(
            BranchRepository branches,
            ClinicalQueueTicketRepository tickets,
            VisitRepository visits,
            DentalChairRepository chairs,
            RoomRepository rooms,
            StaffProfileRepository staffProfiles,
            StaffBranchAssignmentRepository branchAssignments,
            SystemAuditLogRepository audits,
            UserRepository users,
            ClinicalExaminationBootstrapService examinations,
            UrlIdCodec urlIdCodec,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.branches = branches;
        this.tickets = tickets;
        this.visits = visits;
        this.chairs = chairs;
        this.rooms = rooms;
        this.staffProfiles = staffProfiles;
        this.branchAssignments = branchAssignments;
        this.audits = audits;
        this.users = users;
        this.examinations = examinations;
        this.urlIdCodec = urlIdCodec != null ? urlIdCodec : UrlIdCodec.shared();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    // Canonical Queue Ordering (shared by Reception Board & Doctor Daily Visits)
    // -------------------------------------------------------------------------

    public boolean isLateBookingReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String upper = reason.trim().toUpperCase(Locale.ROOT);
        return upper.contains("LATE_BOOKING") || upper.contains("TRỄ") || upper.contains("TRE");
    }

    /**
     * 4 tiers:
     * 1 = Cấp cứu (EMERGENCY)
     * 2 = Đặt lịch đúng giờ / Ưu tiên (BOOKED_ON_TIME / PRIORITY)
     * 3 = Vãng lai / Thường (WALK_IN / NORMAL)
     * 4 = Đặt lịch đến trễ (LATE_BOOKING)
     */
    public int queueTierRank(QueueTicket ticket) {
        Visit v = ticket != null ? ticket.getVisit() : null;
        return resolveTierRank(ticket, v);
    }

    public int visitTierRank(Visit visit, QueueTicket ticket) {
        return resolveTierRank(ticket, visit);
    }

    private int resolveTierRank(QueueTicket ticket, Visit visit) {
        boolean emergency = (ticket != null && Boolean.TRUE.equals(ticket.getIsEmergency()))
                || (visit != null && (Boolean.TRUE.equals(visit.getIsEmergency()) || visit.getVisitType() == VisitType.EMERGENCY));
        if (emergency) {
            return 1;
        }
        int score = ticket != null && ticket.getPriorityScore() != null ? ticket.getPriorityScore() : -1;
        if (ticket != null && isLateBookingReason(ticket.getPriorityReason())) {
            return 4;
        }
        if (visit != null) {
            if (Boolean.TRUE.equals(visit.getIsBookedAndOnTime())
                    && (visit.getAppointmentId() != null || visit.getVisitType() == VisitType.APPOINTMENT)) {
                return 2;
            }
            if (ticket != null && Boolean.TRUE.equals(ticket.getIsPriority()) && score >= 75) {
                return 2;
            }
            if (visit.getAppointmentId() == null && visit.getVisitType() != VisitType.APPOINTMENT) {
                return 3;
            }
            if (score >= 75) {
                return 2;
            }
            if (ticket != null && Boolean.TRUE.equals(ticket.getIsPriority())) {
                return 2;
            }
            return 4;
        }
        if (ticket != null && (Boolean.TRUE.equals(ticket.getIsPriority()) || score >= 75)) {
            return 2;
        }
        return 3;
    }

    public int effectivePriorityScore(QueueTicket ticket, Visit visit) {
        if (ticket != null && ticket.getPriorityScore() != null) {
            return ticket.getPriorityScore();
        }
        return switch (resolveTierRank(ticket, visit)) {
            case 1 -> 100;
            case 2 -> 75;
            case 3 -> 50;
            default -> 25;
        };
    }

    /**
     * Comparator chuẩn cho mọi lượt đang chờ:
     * 1. Cấp cứu (Tier 1)
     * 2. Chờ đọc kết quả cận lâm sàng (WAITING_RESULT)
     * 3. Nhóm ưu tiên (Đặt lịch đúng giờ -> Vãng lai -> Đặt lịch trễ)
     * 4. Điểm ưu tiên giảm dần (trong cùng nhóm)
     * 5. Có phiếu ưu tiên (P- trước số thường)
     * 6. Thứ tự số phiếu đã cấp (ticketNumber tăng dần -> P-01 luôn trước P-02)
     * 7. Thời điểm cấp số / check-in tăng dần
     * 8. ticketId / visitId tăng dần
     */
    public Comparator<QueueTicket> waitingQueueComparator() {
        return (a, b) -> {
            Visit va = a != null ? a.getVisit() : null;
            Visit vb = b != null ? b.getVisit() : null;
            return compareVisitsForWaitingOrder(va, a, vb, b);
        };
    }

    public int compareVisitsForWaitingOrder(Visit v1, QueueTicket t1, Visit v2, QueueTicket t2) {
        int r1 = resolveTierRank(t1, v1);
        int r2 = resolveTierRank(t2, v2);
        int e1 = r1 == 1 ? 0 : 1;
        int e2 = r2 == 1 ? 0 : 1;
        if (e1 != e2) return Integer.compare(e1, e2);

        int wr1 = (v1 != null && v1.getStatus() == VisitStatus.WAITING_RESULT) ? 0 : 1;
        int wr2 = (v2 != null && v2.getStatus() == VisitStatus.WAITING_RESULT) ? 0 : 1;
        if (wr1 != wr2) return Integer.compare(wr1, wr2);

        if (r1 != r2) return Integer.compare(r1, r2);

        int s1 = effectivePriorityScore(t1, v1);
        int s2 = effectivePriorityScore(t2, v2);
        if (s1 != s2) return Integer.compare(s2, s1);

        int p1 = (t1 != null && Boolean.TRUE.equals(t1.getIsPriority())) ? 0 : 1;
        int p2 = (t2 != null && Boolean.TRUE.equals(t2.getIsPriority())) ? 0 : 1;
        if (p1 != p2) return Integer.compare(p1, p2);

        Integer n1 = t1 != null ? t1.getTicketNumber() : null;
        Integer n2 = t2 != null ? t2.getTicketNumber() : null;
        if (n1 != null || n2 != null) {
            if (n1 == null) return 1;
            if (n2 == null) return -1;
            int cmpNum = Integer.compare(n1, n2);
            if (cmpNum != 0) return cmpNum;
        }

        LocalDateTime time1 = resolveQueueTime(v1, t1);
        LocalDateTime time2 = resolveQueueTime(v2, t2);
        if (time1 != null || time2 != null) {
            if (time1 == null) return 1;
            if (time2 == null) return -1;
            int cmpTime = time1.compareTo(time2);
            if (cmpTime != 0) return cmpTime;
        }

        Long tid1 = t1 != null ? t1.getTicketId() : null;
        Long tid2 = t2 != null ? t2.getTicketId() : null;
        if (tid1 != null || tid2 != null) {
            if (tid1 == null) return 1;
            if (tid2 == null) return -1;
            int cmpTid = Long.compare(tid1, tid2);
            if (cmpTid != 0) return cmpTid;
        }

        Long vid1 = v1 != null ? v1.getVisitId() : null;
        Long vid2 = v2 != null ? v2.getVisitId() : null;
        if (vid1 == null && vid2 == null) return 0;
        if (vid1 == null) return 1;
        if (vid2 == null) return -1;
        return Long.compare(vid1, vid2);
    }

    private LocalDateTime resolveQueueTime(Visit visit, QueueTicket ticket) {
        if (ticket != null && ticket.getIssuedAt() != null) {
            return ticket.getIssuedAt();
        }
        if (visit != null && visit.getCheckInAt() != null) {
            return visit.getCheckInAt();
        }
        return ticket != null ? ticket.getResumedAt() : null;
    }

    /**
     * Đồng bộ một lần khi hệ thống khởi động: nếu bàn bác sĩ đang rảnh hoàn toàn (0 CALLED, 0 IN_SERVICE, 0 IN_EXAM)
     * nhưng đang có bệnh nhân chờ từ trước (WAITING), tự động gọi đúng người đầu hàng.
     * Không chạy khi mở/làm mới trang (tuân thủ quy tắc GET chỉ đọc).
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void reconcileTodayWaitingQueuesOnStartup() {
        if (transactionManager == null) {
            return;
        }
        try {
            LocalDate today = LocalDate.now(CLINIC_ZONE);
            Set<String> seenPairs = new LinkedHashSet<>();
            List<long[]> pairs = new ArrayList<>();
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        for (QueueTicket t : tickets.findWaitingWithDoctorOnDay(today, QueueType.EXAM)) {
                            if (t.getBranch() != null && t.getBranch().getBranchId() != null
                                    && t.getVisit() != null && t.getVisit().getPrimaryDoctor() != null
                                    && t.getVisit().getPrimaryDoctor().getStaffId() != null
                                    && WAITING_VISIT_STATUSES.contains(t.getVisit().getStatus())) {
                                Long bId = t.getBranch().getBranchId();
                                Long dId = t.getVisit().getPrimaryDoctor().getStaffId();
                                String key = bId + ":" + dId;
                                if (seenPairs.add(key)) {
                                    pairs.add(new long[]{bId, dId});
                                }
                            }
                        }
                    });
            for (long[] pair : pairs) {
                new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> autoCallNextForDoctorIfIdle(pair[0], pair[1], null));
            }
        } catch (Exception ex) {
            log.warn("Không thể đồng bộ tự gọi số khi khởi động: {}", ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Auto-issue Ticket & Auto-call Next Patient
    // -------------------------------------------------------------------------

    /**
     * Cấp số khám tự động cho lượt khám nếu đủ điều kiện và chưa có số đang hoạt động.
     * Tuyệt đối idempotent: gọi lại nhiều lần không sinh thêm số.
     * Nếu bàn bác sĩ đang trống (0 CALLED, 0 IN_SERVICE), tự động gọi ngay lượt đầu hàng.
     */
    @Transactional
    public QueueTicket issueTicketForVisitIfEligible(Long visitId, Long actorUserId) {
        if (visitId == null) {
            return null;
        }
        Visit snapshot = visits.findById(visitId).orElse(null);
        if (snapshot == null || snapshot.getBranch() == null) {
            return null;
        }
        Long branchId = snapshot.getBranch().getBranchId();
        Branch branch = branches.findByIdForUpdate(branchId).orElse(snapshot.getBranch());
        Visit visit = visits.lockById(visitId).orElse(snapshot);
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        if (!Boolean.TRUE.equals(branch.getIsActive())
                || visit.getCheckInAt() == null
                || !visit.getCheckInAt().toLocalDate().equals(today)
                || !WAITING_VISIT_STATUSES.contains(visit.getStatus())
                || visit.getPrimaryDoctor() == null) {
            return null;
        }
        User actor = actorUserId != null ? users.findById(actorUserId).orElse(null) : null;
        Long doctorId = visit.getPrimaryDoctor().getStaffId();

        QueueTicket existingActive = findLatestActiveOrPausedTicket(visitId);
        if (existingActive != null) {
            autoCallNextForDoctorIfIdleLocked(branchId, doctorId, actor, today);
            return existingActive;
        }

        boolean emergency = Boolean.TRUE.equals(visit.getIsEmergency()) || visit.getVisitType() == VisitType.EMERGENCY;
        boolean returningParaclinical = visit.getStatus() == VisitStatus.WAITING_RESULT;
        boolean bookedOnTime = Boolean.TRUE.equals(visit.getIsBookedAndOnTime())
                && (visit.getAppointmentId() != null || visit.getVisitType() == VisitType.APPOINTMENT);
        boolean walkIn = visit.getAppointmentId() == null && visit.getVisitType() != VisitType.APPOINTMENT;
        boolean priority = emergency || returningParaclinical || bookedOnTime;

        String priorityReason = null;
        if (emergency) {
            String sym = visit.getInitialSymptoms() != null ? visit.getInitialSymptoms().trim() : "";
            priorityReason = sym.isEmpty() ? "Ca cấp cứu ưu tiên xử lý ngay" : sym;
        } else if (returningParaclinical) {
            priorityReason = "Bệnh nhân quay lại sau cận lâm sàng chờ đọc kết quả";
        } else if (bookedOnTime) {
            priorityReason = "Bệnh nhân đặt lịch hẹn check-in đúng giờ";
        } else if (visit.getVisitType() == VisitType.APPOINTMENT) {
            priorityReason = "LATE_BOOKING: Bệnh nhân đặt lịch hẹn check-in trễ giờ";
        }

        QueueTicket reusableCancelled = tickets.findByVisitIdAndTypeOrderByDesc(visitId, QueueType.EXAM).stream()
                .filter(t -> t.getStatus() == QueueStatus.CANCELLED)
                .findFirst()
                .orElse(null);

        QueueTicket ticket = reusableCancelled != null ? reusableCancelled : new QueueTicket();
        int nextNumber = tickets.maxTicketNumber(branchId, today, QueueType.EXAM, priority) + 1;
        if (nextNumber > 999) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đã hết dải số trong ngày.");
        }

        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        ticket.setVisit(visit);
        ticket.setBranch(branch);
        ticket.setQueueDate(today);
        ticket.setQueueType(QueueType.EXAM);
        ticket.setTicketNumber(nextNumber);
        ticket.setDisplayNumber((priority ? "P-" : "") + String.format("%02d", nextNumber));
        ticket.setStatus(QueueStatus.WAITING);
        ticket.setCalledAt(null);
        ticket.setIsPriority(priority);
        ticket.setIsEmergency(emergency);
        ticket.setPriorityScore(emergency ? 100 : (bookedOnTime || returningParaclinical ? 75 : (walkIn ? 50 : 25)));
        ticket.setPriorityReason(trimTo255(priorityReason));
        ticket.setIssuedAt(now);
        ticket.setPausedAt(null);
        ticket.setResumedAt(null);
        ticket.setPauseReason(null);
        ticket.setCompletedAt(null);

        QueueTicket saved = tickets.saveAndFlush(ticket);
        saveAudit(actor, branch, "queue_tickets", saved.getTicketId(), "AUTO_ISSUE_EXAM_TICKET",
                Map.of(),
                Map.of("visitId", visitId, "displayNumber", saved.getDisplayNumber(),
                        "priorityScore", saved.getPriorityScore(), "isEmergency", emergency));

        publishEvent("TICKET_ISSUED", branchId, doctorId, visitId, visit.getAppointmentId(),
                saved.getStatus().name(),
                "Hệ thống tự cấp số " + saved.getDisplayNumber() + " cho " + patientName(visit),
                now);

        autoCallNextForDoctorIfIdleLocked(branchId, doctorId, actor, today);
        return saved;
    }

    @Transactional
    public Optional<QueueTicket> autoCallNextForDoctorIfIdle(Long branchId, Long doctorId, Long actorUserId) {
        if (branchId == null || doctorId == null) {
            return Optional.empty();
        }
        Branch branch = branches.findByIdForUpdate(branchId).orElse(null);
        if (branch == null || !Boolean.TRUE.equals(branch.getIsActive())) {
            return Optional.empty();
        }
        User actor = actorUserId != null ? users.findById(actorUserId).orElse(null) : null;
        return autoCallNextForDoctorIfIdleLocked(branchId, doctorId, actor, LocalDate.now(CLINIC_ZONE));
    }

    /**
     * Phải được gọi trong transaction đã giữ pessimistic write lock trên Branch.
     */
    private Optional<QueueTicket> autoCallNextForDoctorIfIdleLocked(Long branchId, Long doctorId, User actor, LocalDate today) {
        if (branchId == null || doctorId == null) {
            return Optional.empty();
        }
        Branch branch = branches.findById(branchId).orElse(null);
        if (branch == null || !Boolean.TRUE.equals(branch.getIsActive())) {
            return Optional.empty();
        }
        StaffProfile doctor = staffProfiles.findById(doctorId).orElse(null);
        if (doctor != null && doctor.getStatus() != StaffStatus.ACTIVE) {
            return Optional.empty();
        }

        List<QueueTicket> doctorTickets = tickets.findForDoctorOnDay(branchId, today, QueueType.EXAM, doctorId);
        boolean deskBusy = doctorTickets.stream().anyMatch(t -> ACTIVE_DESK_STATUSES.contains(t.getStatus()))
                || !visits.findActiveExamsForDoctorAtBranch(branchId, doctorId, ACTIVE_EXAM_VISIT_STATUSES).isEmpty();
        if (deskBusy) {
            return Optional.empty();
        }

        Optional<QueueTicket> candidateOpt = doctorTickets.stream()
                .filter(t -> t.getStatus() == QueueStatus.WAITING
                        && t.getVisit() != null
                        && WAITING_VISIT_STATUSES.contains(t.getVisit().getStatus()))
                .min(waitingQueueComparator());
        if (candidateOpt.isEmpty()) {
            return Optional.empty();
        }

        QueueTicket lockedTicket = tickets.lockById(candidateOpt.get().getTicketId()).orElse(null);
        if (lockedTicket == null || lockedTicket.getStatus() != QueueStatus.WAITING || lockedTicket.getVisit() == null) {
            return Optional.empty();
        }
        Visit lockedVisit = visits.lockById(lockedTicket.getVisit().getVisitId()).orElse(lockedTicket.getVisit());
        if (!WAITING_VISIT_STATUSES.contains(lockedVisit.getStatus())
                || lockedVisit.getPrimaryDoctor() == null
                || !doctorId.equals(lockedVisit.getPrimaryDoctor().getStaffId())) {
            return Optional.empty();
        }
        if (tickets.countOtherForDoctor(branchId, today, QueueType.EXAM, doctorId, ACTIVE_DESK_STATUSES, lockedTicket.getTicketId()) > 0) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        lockedTicket.setStatus(QueueStatus.CALLED);
        lockedTicket.setCalledAt(now);
        tickets.saveAndFlush(lockedTicket);

        saveAudit(actor, branch, "queue_tickets", lockedTicket.getTicketId(), "QUEUE_AUTO_CALL",
                Map.of("status", "WAITING"),
                Map.of("status", "CALLED", "displayNumber", lockedTicket.getDisplayNumber(),
                        "visitId", lockedVisit.getVisitId(), "doctorId", doctorId, "calledAt", now.toString()));

        publishEvent("QUEUE_CALLED", branchId, doctorId, lockedVisit.getVisitId(), lockedVisit.getAppointmentId(),
                "CALLED",
                "Đang gọi " + lockedTicket.getDisplayNumber() + " – " + patientName(lockedVisit),
                now);
        log.info("Auto-called ticket {} for doctor {} at branch {}", lockedTicket.getDisplayNumber(), doctorId, branchId);
        return Optional.of(lockedTicket);
    }

    // -------------------------------------------------------------------------
    // Receptionist: Emergency Reporting (without overriding busy desk)
    // -------------------------------------------------------------------------

    @Transactional
    public QueueTicket reportEmergency(Long branchId, Long ticketId, User actor, String reason) {
        if (reason == null || reason.trim().length() < 10 || reason.trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cần ghi lý do cấp cứu từ 10 đến 255 ký tự.");
        }
        String cleanReason = reason.trim();
        Branch branch = branches.findByIdForUpdate(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        QueueTicket ticket = tickets.lockById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy số khám."));
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        if (!Objects.equals(ticket.getBranch().getBranchId(), branchId)
                || !today.equals(ticket.getQueueDate())
                || ticket.getQueueType() != QueueType.EXAM) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ báo cấp cứu cho lượt khám hôm nay tại chi nhánh.");
        }
        Visit visit = visits.lockById(ticket.getVisit().getVisitId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt khám."));

        QueueStatus before = ticket.getStatus();
        if ((before != QueueStatus.WAITING && before != QueueStatus.PAUSED && before != QueueStatus.CALLED)
                || !WAITING_VISIT_STATUSES.contains(visit.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ báo cấp cứu cho lượt chưa bắt đầu khám.");
        }

        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        ticket.setIsEmergency(true);
        ticket.setIsPriority(true);
        ticket.setPriorityScore(100);
        ticket.setPriorityReason(cleanReason);
        if (before == QueueStatus.PAUSED) {
            ticket.setStatus(QueueStatus.WAITING);
            ticket.setResumedAt(now);
        }
        visit.setVisitType(VisitType.EMERGENCY);
        visit.setIsEmergency(true);
        visits.saveAndFlush(visit);
        tickets.saveAndFlush(ticket);

        String reporterName = resolveActorDisplayName(actor);
        Long doctorId = visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getStaffId() : null;

        Map<String, Object> newMeta = new LinkedHashMap<>();
        newMeta.put("status", ticket.getStatus().name());
        newMeta.put("isEmergency", true);
        newMeta.put("priorityScore", 100);
        newMeta.put("reason", cleanReason);
        newMeta.put("displayNumber", ticket.getDisplayNumber());
        newMeta.put("visitId", visit.getVisitId());
        newMeta.put("patientName", patientName(visit));
        newMeta.put("doctorId", doctorId != null ? doctorId : 0L);
        newMeta.put("reportedBy", reporterName);
        newMeta.put("reportedByUserId", actor != null && actor.getUserId() != null ? actor.getUserId() : 0L);
        newMeta.put("reportedAt", now.format(DATE_TIME_FMT));

        saveAudit(actor, branch, "queue_tickets", ticket.getTicketId(), "QUEUE_EMERGENCY",
                Map.of("status", before.name(), "isEmergency", false),
                newMeta);

        publishEvent("EMERGENCY_REPORTED", branchId, doctorId, visit.getVisitId(), visit.getAppointmentId(),
                ticket.getStatus().name(),
                "CẢNH BÁO CẤP CỨU [" + ticket.getDisplayNumber() + " – " + patientName(visit) + "]: "
                        + cleanReason + " (Báo bởi: " + reporterName + " lúc " + now.format(TIME_FMT) + ")",
                now);

        if (doctorId != null) {
            autoCallNextForDoctorIfIdleLocked(branchId, doctorId, actor, today);
        }
        return ticket;
    }

    // -------------------------------------------------------------------------
    // Doctor Actions: Desk Status, Enter Exam ("Vào khám"), Absent ("Vắng mặt"), Complete
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DoctorDeskStatusDTO getDoctorDeskStatus(User actor, Long requestedBranchId) {
        if (actor == null || actor.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        boolean isAdmin = users.hasActiveAdminRole(actor.getUserId());
        DeskTarget target = resolveDeskTargetForActor(actor, requestedBranchId, today, false);
        if (target == null) {
            Long fallbackBranch = requestedBranchId != null ? requestedBranchId
                    : branches.findByIsActiveTrueOrderByBranchNameAsc().stream()
                            .map(Branch::getBranchId).findFirst().orElse(null);
            String adminBanner = ALLOW_ADMIN_DESK_ACCESS_FOR_TESTING
                    ? "Không có bệnh nhân đang chờ"
                    : buildAdminDeskHintBanner(fallbackBranch, today, actor.getUsername());
            return new DoctorDeskStatusDTO(null, actor.getUsername(), fallbackBranch,
                    null, null, 0, false, false, adminBanner, List.of());
        }

        StaffProfile doctor = target.doctor();
        Long branchId = target.branchId();
        Long doctorId = doctor.getStaffId();

        List<QueueTicket> doctorTickets = tickets.findForDoctorOnDay(branchId, today, QueueType.EXAM, doctorId);

        QueueTicket inServiceTicket = doctorTickets.stream()
                .filter(t -> t.getStatus() == QueueStatus.IN_SERVICE
                        && t.getVisit() != null
                        && ACTIVE_EXAM_VISIT_STATUSES.contains(t.getVisit().getStatus()))
                .findFirst()
                .orElse(null);

        QueueTicket calledTicket = doctorTickets.stream()
                .filter(t -> t.getStatus() == QueueStatus.CALLED
                        && t.getVisit() != null
                        && WAITING_VISIT_STATUSES.contains(t.getVisit().getStatus()))
                .findFirst()
                .orElse(null);

        List<QueueTicket> waitingList = doctorTickets.stream()
                .filter(t -> t.getStatus() == QueueStatus.WAITING
                        && t.getVisit() != null
                        && WAITING_VISIT_STATUSES.contains(t.getVisit().getStatus()))
                .sorted(waitingQueueComparator())
                .toList();

        Visit reopenedActiveVisit = null;
        if (inServiceTicket == null) {
            reopenedActiveVisit = visits.findActiveExamsForDoctorAtBranch(branchId, doctorId, ACTIVE_EXAM_VISIT_STATUSES)
                    .stream()
                    .findFirst()
                    .orElse(null);
        }

        DeskTicketSummary inServiceSummary = toDeskSummary(inServiceTicket);
        DeskTicketSummary calledSummary = toDeskSummary(calledTicket);

        String bannerText;
        if (reopenedActiveVisit != null) {
            String baseReopen = "Đang mở lại ca " + reopenedActiveVisit.getVisitCode()
                    + " – " + patientName(reopenedActiveVisit);
            bannerText = calledSummary != null
                    ? baseReopen + " (Giữ lượt " + calledSummary.displayNumber() + " – " + calledSummary.patientName() + ")"
                    : baseReopen + " (Chờ hoàn tất lại)";
        } else if (calledSummary != null) {
            bannerText = "Đang gọi " + calledSummary.displayNumber() + " – " + calledSummary.patientName();
        } else if (inServiceSummary != null) {
            bannerText = "Đang khám " + inServiceSummary.displayNumber() + " – " + inServiceSummary.patientName();
        } else if (waitingList.isEmpty()) {
            bannerText = isAdmin && !ALLOW_ADMIN_DESK_ACCESS_FOR_TESTING
                    ? buildAdminDeskHintBanner(branchId, today, doctor.getFullName())
                    : "Không có bệnh nhân đang chờ";
        } else {
            bannerText = "Chưa có lượt đang gọi (" + waitingList.size() + " lượt chờ)";
        }

        List<EmergencyAlertDTO> emergencyAlerts = findActiveEmergencyAlerts(branchId, doctorId, today, doctorTickets);

        return new DoctorDeskStatusDTO(
                doctorId,
                doctor.getFullName(),
                branchId,
                inServiceSummary,
                calledSummary,
                waitingList.size(),
                reopenedActiveVisit == null && (calledSummary != null || inServiceSummary != null),
                calledSummary != null && inServiceSummary == null && reopenedActiveVisit == null,
                bannerText,
                emergencyAlerts);
    }

    @Transactional
    public EnterExamResult enterExaminationForDoctor(User actor, Long requestedBranchId) {
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        DeskTarget target = resolveDeskTargetForActor(actor, requestedBranchId, today, true);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không có bệnh nhân đang chờ tại bàn khám.");
        }
        StaffProfile doctor = target.doctor();
        Long branchId = target.branchId();
        Branch branch = branches.findByIdForUpdate(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        Long doctorId = doctor.getStaffId();

        List<QueueTicket> doctorTickets = tickets.findForDoctorOnDay(branchId, today, QueueType.EXAM, doctorId);

        // 1. Idempotency: nếu đang có ca IN_SERVICE của bàn này, mở lại chính ca đó
        Optional<QueueTicket> existingInService = doctorTickets.stream()
                .filter(t -> t.getStatus() == QueueStatus.IN_SERVICE
                        && t.getVisit() != null
                        && ACTIVE_EXAM_VISIT_STATUSES.contains(t.getVisit().getStatus()))
                .findFirst();
        if (existingInService.isPresent()) {
            QueueTicket activeTicket = existingInService.get();
            Visit activeVisit = activeTicket.getVisit();
            examinations.ensureForAssignedVisit(activeVisit.getVisitId());
            return new EnterExamResult(activeVisit, activeTicket, urlIdCodec.encodeVisit(activeVisit.getVisitId()), true);
        }

        // 1b. Bảo đảm cùng bàn khám chỉ có duy nhất 1 ca IN_EXAM/IN_TREATMENT (kể cả ca mở lại trước thanh toán)
        List<Visit> activeDoctorVisits = visits.findActiveExamsForDoctorAtBranch(branchId, doctorId, ACTIVE_EXAM_VISIT_STATUSES);
        if (!activeDoctorVisits.isEmpty()) {
            Visit reopenedActive = activeDoctorVisits.get(0);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Bàn khám hiện đang mở lại ca " + reopenedActive.getVisitCode()
                            + " (" + patientName(reopenedActive)
                            + "). Vui lòng hoàn tất lại ca này trước khi bấm Vào khám lượt tiếp theo.");
        }

        // 2. Xác định đúng lượt đang ở trạng thái Đang gọi (CALLED) của bàn này
        QueueTicket calledSnapshot = doctorTickets.stream()
                .filter(t -> t.getStatus() == QueueStatus.CALLED
                        && t.getVisit() != null
                        && WAITING_VISIT_STATUSES.contains(t.getVisit().getStatus()))
                .findFirst()
                .orElse(null);

        if (calledSnapshot == null) {
            long waitingCount = doctorTickets.stream()
                    .filter(t -> t.getStatus() == QueueStatus.WAITING
                            && t.getVisit() != null
                            && WAITING_VISIT_STATUSES.contains(t.getVisit().getStatus()))
                    .count();
            String msg = waitingCount == 0
                    ? "Không có bệnh nhân đang chờ tại bàn khám."
                    : "Hiện chưa có bệnh nhân ở trạng thái Đang gọi để vào khám.";
            throw new ResponseStatusException(HttpStatus.CONFLICT, msg);
        }

        QueueTicket ticket = tickets.lockById(calledSnapshot.getTicketId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu gọi số."));
        Visit visit = visits.lockById(ticket.getVisit().getVisitId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt khám."));

        if (ticket.getStatus() == QueueStatus.IN_SERVICE && ACTIVE_EXAM_VISIT_STATUSES.contains(visit.getStatus())) {
            examinations.ensureForAssignedVisit(visit.getVisitId());
            return new EnterExamResult(visit, ticket, urlIdCodec.encodeVisit(visit.getVisitId()), true);
        }
        if (ticket.getStatus() != QueueStatus.CALLED || !WAITING_VISIT_STATUSES.contains(visit.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Trạng thái lượt gọi vừa thay đổi. Vui lòng làm mới danh sách.");
        }

        // Đảm bảo ghế/phòng khám hợp lệ nếu chi nhánh có cấu hình ghế
        allocateChairIfConfigured(branchId, visit);

        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        visit.setStatus(VisitStatus.IN_EXAM);
        if (visit.getActualStartTime() == null) {
            visit.setActualStartTime(now);
        }
        visits.saveAndFlush(visit);

        ticket.setStatus(QueueStatus.IN_SERVICE);
        tickets.saveAndFlush(ticket);

        examinations.ensureForAssignedVisit(visit.getVisitId());

        saveAudit(actor, branch, "queue_tickets", ticket.getTicketId(), "QUEUE_START",
                Map.of("status", "CALLED"),
                Map.of("status", "IN_SERVICE", "visitId", visit.getVisitId(), "doctorId", doctorId,
                        "displayNumber", ticket.getDisplayNumber()));

        publishEvent("QUEUE_STARTED", branchId, doctorId, visit.getVisitId(), visit.getAppointmentId(),
                "IN_SERVICE",
                "Bác sĩ " + doctor.getFullName() + " đã tiếp nhận " + ticket.getDisplayNumber() + " – " + patientName(visit),
                now);

        return new EnterExamResult(visit, ticket, urlIdCodec.encodeVisit(visit.getVisitId()), false);
    }

    @Transactional
    public AbsentResult markCalledPatientAbsent(User actor, Long requestedBranchId, Long expectedTicketId, String reason) {
        LocalDate today = LocalDate.now(CLINIC_ZONE);
        DeskTarget target = resolveDeskTargetForActor(actor, requestedBranchId, today, true);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Hiện không có lượt đang gọi hợp lệ để ghi nhận vắng mặt.");
        }
        StaffProfile doctor = target.doctor();
        Long branchId = target.branchId();
        Branch branch = branches.findByIdForUpdate(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        Long doctorId = doctor.getStaffId();

        List<QueueTicket> doctorTickets = tickets.findForDoctorOnDay(branchId, today, QueueType.EXAM, doctorId);
        QueueTicket calledSnapshot = doctorTickets.stream()
                .filter(t -> t.getStatus() == QueueStatus.CALLED
                        && t.getVisit() != null
                        && WAITING_VISIT_STATUSES.contains(t.getVisit().getStatus()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Hiện không có lượt đang gọi hợp lệ để ghi nhận vắng mặt."));

        if (expectedTicketId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Thiếu mã lượt đang gọi để xác nhận vắng mặt. Vui lòng làm mới danh sách bàn khám.");
        }
        if (!Objects.equals(expectedTicketId, calledSnapshot.getTicketId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Lượt đang gọi đã thay đổi, vui lòng kiểm tra lại trước khi đánh dấu vắng mặt.");
        }

        QueueTicket ticket = tickets.lockById(calledSnapshot.getTicketId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu gọi số."));
        Visit visit = visits.lockById(ticket.getVisit().getVisitId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt khám."));

        if (!Objects.equals(expectedTicketId, ticket.getTicketId())
                || ticket.getStatus() != QueueStatus.CALLED
                || !WAITING_VISIT_STATUSES.contains(visit.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ ghi nhận vắng mặt cho đúng lượt đang ở trạng thái Đang gọi.");
        }

        String cleanReason = (reason == null || reason.isBlank()) ? "Bệnh nhân vắng mặt khi gọi số" : reason.trim();
        if (cleanReason.length() > 220) {
            cleanReason = cleanReason.substring(0, 220);
        }

        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        ticket.setStatus(QueueStatus.PAUSED);
        ticket.setPausedAt(now);
        ticket.setPauseReason(trimTo255("Vắng mặt: " + cleanReason));
        tickets.saveAndFlush(ticket);

        String traceNote = "[Vắng mặt lúc " + now.format(DATE_TIME_FMT) + " - BS " + doctor.getFullName() + ": " + cleanReason + "]";
        visit.setNotes(visit.getNotes() == null || visit.getNotes().isBlank()
                ? traceNote
                : visit.getNotes().trim() + " | " + traceNote);
        visits.saveAndFlush(visit);

        String actorName = resolveActorDisplayName(actor);
        saveAudit(actor, branch, "queue_tickets", ticket.getTicketId(), "QUEUE_MARK_ABSENT",
                Map.of("status", "CALLED", "displayNumber", ticket.getDisplayNumber(), "visitId", visit.getVisitId()),
                Map.of("status", "PAUSED", "absent", true, "reason", cleanReason,
                        "reportedBy", actorName, "reportedAt", now.format(DATE_TIME_FMT),
                        "doctorId", doctorId, "visitId", visit.getVisitId()));

        publishEvent("QUEUE_MARKED_ABSENT", branchId, doctorId, visit.getVisitId(), visit.getAppointmentId(),
                "PAUSED",
                "Ghi nhận vắng mặt " + ticket.getDisplayNumber() + " – " + patientName(visit),
                now);

        Optional<QueueTicket> nextCalled = autoCallNextForDoctorIfIdleLocked(branchId, doctorId, actor, today);
        return new AbsentResult(ticket, nextCalled.orElse(null));
    }

    /**
     * Hoàn tất phiếu hàng đợi hiện tại của lượt khám và tự động gọi ĐÚNG 1 người tiếp theo nếu bàn đang trống.
     * Nếu đã có lượt Đang gọi khác (ví dụ khi hoàn tất lại ca cũ được mở trước thanh toán), KHÔNG gọi trùng hay làm mất lượt đang gọi.
     */
    @Transactional
    public Optional<QueueTicket> completeVisitAndAutoCallNext(Visit visit, User actor) {
        if (visit == null || visit.getVisitId() == null || visit.getBranch() == null) {
            return Optional.empty();
        }
        Long branchId = visit.getBranch().getBranchId();
        Branch branch = branches.findByIdForUpdate(branchId).orElse(visit.getBranch());
        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        visits.saveAndFlush(visit);

        if (visit.getChairId() != null) {
            chairs.findById(visit.getChairId()).ifPresent(chair -> {
                if (chair.getStatus() == ChairStatus.IN_USE) {
                    chair.setStatus(ChairStatus.AVAILABLE);
                    chairs.saveAndFlush(chair);
                }
            });
        }

        List<QueueTicket> visitTickets = tickets.findByVisitVisitIdInAndQueueTypeOrderByTicketIdDesc(
                List.of(visit.getVisitId()), QueueType.EXAM);
        boolean closedActiveTicket = false;
        for (QueueTicket t : visitTickets) {
            if (t.getStatus() == QueueStatus.IN_SERVICE || t.getStatus() == QueueStatus.CALLED) {
                t.setStatus(QueueStatus.DONE);
                t.setCompletedAt(now);
                tickets.saveAndFlush(t);
                closedActiveTicket = true;
            }
        }

        Long doctorId = visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getStaffId() : null;
        if (closedActiveTicket) {
            publishEvent("QUEUE_DONE", branchId, doctorId, visit.getVisitId(), visit.getAppointmentId(),
                    "DONE", "Hoàn tất ca khám " + visit.getVisitCode(), now);
        }

        if (doctorId != null && closedActiveTicket) {
            return autoCallNextForDoctorIfIdleLocked(branchId, doctorId, actor, LocalDate.now(CLINIC_ZONE));
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------------

    private void allocateChairIfConfigured(Long branchId, Visit visit) {
        List<DentalChair> branchChairs = chairs.findByBranchBranchIdOrderByChairCodeAsc(branchId);
        if (branchChairs.isEmpty()) {
            return;
        }
        DentalChair chosen = null;
        if (visit.getChairId() != null) {
            DentalChair current = chairs.findById(visit.getChairId()).orElse(null);
            if (current != null
                    && Objects.equals(current.getBranch().getBranchId(), branchId)
                    && current.getStatus() == ChairStatus.AVAILABLE
                    && current.getRoom() != null
                    && current.getRoom().getStatus() == RoomStatus.ACTIVE
                    && visits.countOccupiedChair(current.getChairId(), visit.getVisitId(), ACTIVE_EXAM_VISIT_STATUSES) == 0) {
                chosen = current;
            }
        }
        if (chosen == null) {
            for (DentalChair c : branchChairs) {
                if (c.getStatus() == ChairStatus.AVAILABLE
                        && c.getRoom() != null
                        && c.getRoom().getStatus() == RoomStatus.ACTIVE
                        && (visit.getRoomId() == null || Objects.equals(visit.getRoomId(), c.getRoom().getRoomId()))
                        && visits.countOccupiedChair(c.getChairId(), visit.getVisitId(), ACTIVE_EXAM_VISIT_STATUSES) == 0) {
                    chosen = c;
                    break;
                }
            }
        }
        if (chosen == null && visit.getRoomId() != null) {
            for (DentalChair c : branchChairs) {
                if (c.getStatus() == ChairStatus.AVAILABLE
                        && c.getRoom() != null
                        && c.getRoom().getStatus() == RoomStatus.ACTIVE
                        && visits.countOccupiedChair(c.getChairId(), visit.getVisitId(), ACTIVE_EXAM_VISIT_STATUSES) == 0) {
                    chosen = c;
                    break;
                }
            }
        }
        if (chosen == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ghế khám hiện đang có ca khác hoặc chưa sẵn sàng. Vui lòng điều phối lại ghế trống.");
        }
        chosen.setStatus(ChairStatus.IN_USE);
        chairs.saveAndFlush(chosen);
        visit.setChairId(chosen.getChairId());
        visit.setRoomId(chosen.getRoom().getRoomId());
    }

    private QueueTicket findLatestActiveOrPausedTicket(Long visitId) {
        return tickets.findByVisitVisitIdInAndQueueTypeOrderByTicketIdDesc(List.of(visitId), QueueType.EXAM)
                .stream()
                .filter(t -> t.getStatus() != QueueStatus.DONE && t.getStatus() != QueueStatus.CANCELLED)
                .findFirst()
                .orElse(null);
    }

    private StaffProfile requireActingDoctor(User actor) {
        if (actor == null || actor.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        return staffProfiles.findByUserUserId(actor.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản hiện tại không gắn hồ sơ bác sĩ."));
    }

    private Long resolveDoctorBranchId(User actor, StaffProfile doctor, Long requestedBranchId) {
        List<Long> branchIds = branchAssignments.findActiveBranchIdsByStaffId(doctor.getStaffId(), AssignmentStatus.ACTIVE);
        if (branchIds.isEmpty()) {
            branchIds = branchAssignments.findActiveBranchIdsByUserId(actor.getUserId(), AssignmentStatus.ACTIVE);
        }
        if (requestedBranchId != null) {
            if (!branchIds.isEmpty() && !branchIds.contains(requestedBranchId) && !users.hasActiveAdminRole(actor.getUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bác sĩ không phụ trách chi nhánh này.");
            }
            return requestedBranchId;
        }
        List<QueueTicket> docTicketsToday = tickets.findForDoctorAcrossAllBranchesOnDay(
                LocalDate.now(CLINIC_ZONE), QueueType.EXAM, doctor.getStaffId());
        for (QueueTicket t : docTicketsToday) {
            if (t.getBranch() != null && t.getBranch().getBranchId() != null
                    && t.getStatus() != QueueStatus.CANCELLED && t.getStatus() != QueueStatus.DONE) {
                Long bId = t.getBranch().getBranchId();
                if (branchIds.isEmpty() || branchIds.contains(bId) || users.hasActiveAdminRole(actor.getUserId())) {
                    return bId;
                }
            }
        }
        if (!branchIds.isEmpty()) {
            return branchIds.get(0);
        }
        if (actor.getBranch() != null) {
            return actor.getBranch().getBranchId();
        }
        return branches.findByIsActiveTrueOrderByBranchNameAsc().stream()
                .map(Branch::getBranchId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Không xác định được chi nhánh làm việc."));
    }

    private List<EmergencyAlertDTO> findActiveEmergencyAlerts(Long branchId, Long doctorId, LocalDate today, List<QueueTicket> doctorTickets) {
        List<QueueTicket> activeEmergencyTickets = doctorTickets.stream()
                .filter(t -> Boolean.TRUE.equals(t.getIsEmergency())
                        && (t.getStatus() == QueueStatus.WAITING || t.getStatus() == QueueStatus.CALLED)
                        && t.getVisit() != null
                        && WAITING_VISIT_STATUSES.contains(t.getVisit().getStatus()))
                .toList();
        if (activeEmergencyTickets.isEmpty()) {
            return List.of();
        }

        List<Long> ticketIds = activeEmergencyTickets.stream().map(QueueTicket::getTicketId).toList();
        List<SystemAuditLog> logs = audits.findLatestForEntities("queue_tickets", ticketIds, "QUEUE_EMERGENCY");
        Map<Long, SystemAuditLog> latestLogByTicket = new HashMap<>();
        for (SystemAuditLog logRow : logs) {
            if (logRow.getEntityId() != null) {
                latestLogByTicket.putIfAbsent(logRow.getEntityId(), logRow);
            }
        }

        List<EmergencyAlertDTO> alerts = new ArrayList<>();
        for (QueueTicket et : activeEmergencyTickets) {
            SystemAuditLog auditRow = latestLogByTicket.get(et.getTicketId());
            String reportedBy = auditRow != null && auditRow.getUser() != null
                    ? resolveActorDisplayName(auditRow.getUser())
                    : "Lễ tân / Hệ thống";
            String reportedAt = auditRow != null && auditRow.getCreatedAt() != null
                    ? auditRow.getCreatedAt().format(DATE_TIME_FMT)
                    : (et.getIssuedAt() != null ? et.getIssuedAt().format(TIME_FMT) : "");
            String reason = et.getPriorityReason() != null ? et.getPriorityReason() : et.getVisit().getInitialSymptoms();
            if (auditRow != null && auditRow.getNewValuesJson() != null) {
                try {
                    String rawJson = auditRow.getNewValuesJson().trim();
                    if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                        rawJson = objectMapper.readValue(rawJson, String.class);
                    }
                    Map<String, Object> meta = objectMapper.readValue(rawJson, new TypeReference<>() {});
                    Object rb = meta.get("reportedBy");
                    Object ra = meta.get("reportedAt");
                    Object rs = meta.get("reason");
                    if (rb != null && !rb.toString().isBlank()) reportedBy = rb.toString();
                    if (ra != null && !ra.toString().isBlank()) reportedAt = ra.toString();
                    if (rs != null && !rs.toString().isBlank()) reason = rs.toString();
                } catch (Exception ignored) {
                }
            }
            alerts.add(new EmergencyAlertDTO(
                    et.getTicketId(),
                    et.getVisit().getVisitId(),
                    et.getDisplayNumber(),
                    patientName(et.getVisit()),
                    reason != null ? reason : "Cấp cứu ưu tiên",
                    reportedBy,
                    reportedAt,
                    et.getStatus().name()));
        }
        return alerts;
    }

    private record DeskTarget(StaffProfile doctor, Long branchId) {
    }

    private DeskTarget resolveDeskTargetForActor(User actor, Long requestedBranchId, LocalDate today, boolean strictRequireForAdmin) {
        if (actor == null || actor.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        boolean isAdmin = users.hasActiveAdminRole(actor.getUserId());
        Optional<StaffProfile> ownOpt = staffProfiles.findByUserUserId(actor.getUserId());
        if (ownOpt.isPresent()) {
            StaffProfile own = ownOpt.get();
            Long branchId = resolveDoctorBranchId(actor, own, requestedBranchId);
            if (!isAdmin || !ALLOW_ADMIN_DESK_ACCESS_FOR_TESTING) {
                return new DeskTarget(own, branchId);
            }
            List<QueueTicket> ownTickets = tickets.findForDoctorOnDay(branchId, today, QueueType.EXAM, own.getStaffId());
            boolean ownHasWork = ownTickets.stream().anyMatch(t -> t.getStatus() != QueueStatus.CANCELLED && t.getStatus() != QueueStatus.DONE)
                    || !visits.findActiveExamsForDoctorAtBranch(branchId, own.getStaffId(), ACTIVE_EXAM_VISIT_STATUSES).isEmpty();
            if (ownHasWork) {
                return new DeskTarget(own, branchId);
            }
        }

        if (!isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản hiện tại không gắn hồ sơ bác sĩ.");
        }
        if (!ALLOW_ADMIN_DESK_ACCESS_FOR_TESTING) {
            if (strictRequireForAdmin) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản hiện tại không gắn hồ sơ bác sĩ.");
            }
            return null;
        }
        DeskTarget adminTarget = findAdminTestingDeskTarget(requestedBranchId, today);
        if (adminTarget == null && strictRequireForAdmin && ownOpt.isPresent()) {
            StaffProfile own = ownOpt.get();
            return new DeskTarget(own, resolveDoctorBranchId(actor, own, requestedBranchId));
        }
        return adminTarget;
    }

    // TODO: Xóa helper này (cùng ALLOW_ADMIN_DESK_ACCESS_FOR_TESTING) khi hoàn tất test nhanh bằng Admin
    private DeskTarget findAdminTestingDeskTarget(Long requestedBranchId, LocalDate today) {
        List<Long> candidateBranches = new ArrayList<>();
        if (requestedBranchId != null) {
            candidateBranches.add(requestedBranchId);
        } else {
            for (Branch b : branches.findByIsActiveTrueOrderByBranchNameAsc()) {
                candidateBranches.add(b.getBranchId());
            }
        }

        QueueTicket fallbackCalled = null;
        QueueTicket fallbackInService = null;
        QueueTicket fallbackWaiting = null;
        for (Long bId : candidateBranches) {
            List<QueueTicket> board = tickets.findForBoard(bId, today, QueueType.EXAM);
            for (QueueTicket t : board) {
                if (t.getVisit() == null || t.getVisit().getPrimaryDoctor() == null) {
                    continue;
                }
                if (t.getStatus() == QueueStatus.CALLED && WAITING_VISIT_STATUSES.contains(t.getVisit().getStatus())) {
                    fallbackCalled = t;
                    break;
                } else if (fallbackInService == null && t.getStatus() == QueueStatus.IN_SERVICE && ACTIVE_EXAM_VISIT_STATUSES.contains(t.getVisit().getStatus())) {
                    fallbackInService = t;
                } else if (fallbackWaiting == null && t.getStatus() == QueueStatus.WAITING && WAITING_VISIT_STATUSES.contains(t.getVisit().getStatus())) {
                    fallbackWaiting = t;
                }
            }
            if (fallbackCalled != null) {
                break;
            }
        }
        QueueTicket picked = fallbackCalled != null ? fallbackCalled
                : (fallbackInService != null ? fallbackInService : fallbackWaiting);
        if (picked != null) {
            return new DeskTarget(picked.getVisit().getPrimaryDoctor(), picked.getBranch().getBranchId());
        }
        return null;
    }

    private String buildAdminDeskHintBanner(Long branchId, LocalDate today, String currentAccountName) {
        List<QueueTicket> board = new ArrayList<>();
        if (branchId != null) {
            board.addAll(tickets.findForBoard(branchId, today, QueueType.EXAM));
        }
        QueueTicket activeOther = findFirstDeskActiveTicket(board);
        if (activeOther == null) {
            for (Branch b : branches.findByIsActiveTrueOrderByBranchNameAsc()) {
                if (branchId != null && branchId.equals(b.getBranchId())) {
                    continue;
                }
                activeOther = findFirstDeskActiveTicket(tickets.findForBoard(b.getBranchId(), today, QueueType.EXAM));
                if (activeOther != null) {
                    break;
                }
            }
        }
        if (activeOther != null) {
            String docName = activeOther.getVisit().getPrimaryDoctor().getFullName();
            String label = activeOther.getStatus() == QueueStatus.CALLED ? "đang gọi" : "đang khám";
            return "Tài khoản hiện tại (" + currentAccountName + ") chưa phải BS phụ trách – Bàn "
                    + docName + " " + label + " " + activeOther.getDisplayNumber() + " – " + patientName(activeOther.getVisit());
        }
        return "Không có bệnh nhân đang chờ (TK hiện tại: " + currentAccountName + ")";
    }

    private QueueTicket findFirstDeskActiveTicket(List<QueueTicket> board) {
        if (board == null || board.isEmpty()) {
            return null;
        }
        return board.stream()
                .filter(t -> (t.getStatus() == QueueStatus.CALLED || t.getStatus() == QueueStatus.IN_SERVICE)
                        && t.getVisit() != null
                        && t.getVisit().getPrimaryDoctor() != null)
                .findFirst()
                .orElse(null);
    }

    private DeskTicketSummary toDeskSummary(QueueTicket ticket) {
        if (ticket == null || ticket.getVisit() == null) {
            return null;
        }
        Visit v = ticket.getVisit();
        PatientProfile p = v.getPatientProfile();
        return new DeskTicketSummary(
                ticket.getTicketId(),
                v.getVisitId(),
                urlIdCodec.encodeVisit(v.getVisitId()),
                v.getVisitCode(),
                ticket.getDisplayNumber(),
                p != null ? p.getPatientCode() : "",
                p != null ? p.getFullName() : "",
                ticket.getStatus().name(),
                Boolean.TRUE.equals(ticket.getIsEmergency()),
                ticket.getPriorityReason(),
                ticket.getCalledAt() != null ? ticket.getCalledAt().format(TIME_FMT) : null);
    }

    private String resolveActorDisplayName(User actor) {
        if (actor == null) return "Hệ thống";
        if (actor.getUserId() != null) {
            Optional<StaffProfile> sp = staffProfiles.findByUserUserId(actor.getUserId());
            if (sp.isPresent() && sp.get().getFullName() != null && !sp.get().getFullName().isBlank()) {
                return sp.get().getFullName();
            }
        }
        return actor.getUsername() != null ? actor.getUsername() : "Nhân viên #" + actor.getUserId();
    }

    private String patientName(Visit visit) {
        return visit != null && visit.getPatientProfile() != null && visit.getPatientProfile().getFullName() != null
                ? visit.getPatientProfile().getFullName() : "";
    }

    private String trimTo255(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    private void saveAudit(User actor, Branch branch, String table, Long entityId, String action,
                           Map<String, Object> oldVals, Map<String, Object> newVals) {
        try {
            SystemAuditLog logRow = new SystemAuditLog();
            logRow.setUser(actor);
            logRow.setBranch(branch);
            logRow.setEntityTable(table);
            logRow.setEntityId(entityId);
            logRow.setAction(action);
            logRow.setOldValuesJson(objectMapper.writeValueAsString(oldVals));
            logRow.setNewValuesJson(objectMapper.writeValueAsString(newVals));
            audits.save(logRow);
        } catch (Exception ex) {
            log.warn("Could not write audit log {} for {} #{}: {}", action, table, entityId, ex.getMessage());
        }
    }

    private void publishEvent(String eventType, Long branchId, Long doctorId, Long visitId, Long appointmentId,
                              String status, String message, LocalDateTime timestamp) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ClinicQueueDomainEvent(
                    eventType, branchId, doctorId, visitId, appointmentId, status, message, timestamp));
        }
    }

    // -------------------------------------------------------------------------
    // Public DTOs / Results
    // -------------------------------------------------------------------------

    public record DeskTicketSummary(
            Long ticketId,
            Long visitId,
            String visitToken,
            String visitCode,
            String displayNumber,
            String patientCode,
            String patientName,
            String queueStatus,
            boolean emergency,
            String priorityReason,
            String calledAt) {
    }

    public record EmergencyAlertDTO(
            Long ticketId,
            Long visitId,
            String displayNumber,
            String patientName,
            String reason,
            String reportedBy,
            String reportedAt,
            String queueStatus) {
    }

    public record DoctorDeskStatusDTO(
            Long doctorId,
            String doctorName,
            Long branchId,
            DeskTicketSummary inServiceVisit,
            DeskTicketSummary calledVisit,
            int waitingCount,
            boolean canEnterExam,
            boolean canMarkAbsent,
            String bannerText,
            List<EmergencyAlertDTO> emergencyAlerts) {
    }

    public record EnterExamResult(Visit visit, QueueTicket ticket, String visitToken, boolean reopenedActiveSession) {
    }

    public record AbsentResult(QueueTicket absentTicket, QueueTicket nextCalledTicket) {
    }
}
