package com.cvdcms.service;

import com.cvdcms.entity.DentalChair;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.PerformedService;
import com.cvdcms.entity.Prescription;
import com.cvdcms.entity.QueueTicket;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.ChairStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
import com.cvdcms.entity.enums.PrescriptionStatus;
import com.cvdcms.entity.enums.QueueStatus;
import com.cvdcms.entity.enums.ReservationStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.ClinicalQueueTicketRepository;
import com.cvdcms.repository.DentalChairRepository;
import com.cvdcms.repository.InvoiceRepository;
import com.cvdcms.repository.PerformedServiceRepository;
import com.cvdcms.repository.PrescriptionRepository;
import com.cvdcms.repository.VisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Tự động đóng / kết chuyển các ca khám quá hạn từ những ngày trước
 * (đang kẹt ở WAITING, IN_EXAM, IN_TREATMENT, WAITING_PARACLINICAL, WAITING_RESULT).
 */
@Service
public class OverdueVisitCleanupService {

    public static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final List<VisitStatus> OVERDUE_ACTIVE_STATUSES = List.of(
            VisitStatus.WAITING,
            VisitStatus.IN_EXAM,
            VisitStatus.IN_TREATMENT,
            VisitStatus.WAITING_PARACLINICAL,
            VisitStatus.WAITING_RESULT
    );

    private static final Logger log = LoggerFactory.getLogger(OverdueVisitCleanupService.class);

    private final VisitRepository visits;

    @Autowired(required = false)
    private ClinicalQueueTicketRepository tickets;

    @Autowired(required = false)
    private DentalChairRepository chairs;

    @Autowired(required = false)
    private PerformedServiceRepository performedServices;

    @Autowired(required = false)
    private PrescriptionRepository prescriptions;

    @Autowired(required = false)
    private InvoiceRepository invoices;

    @Autowired(required = false)
    private ParaclinicalOrderService paraclinicalOrderService;

    @Autowired(required = false)
    private ResourceBookingTransactionService resourceBookingTransactionService;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    private Clock clock = Clock.system(CLINIC_ZONE);

    @Autowired
    public OverdueVisitCleanupService(VisitRepository visits) {
        this.visits = visits;
    }

    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    public int closeOverdueVisits(LocalDateTime now) {
        LocalDateTime effectiveNow = now != null ? now : LocalDateTime.now(clock);
        LocalDateTime startOfToday = effectiveNow.toLocalDate().atStartOfDay();
        List<Visit> overdue = visits.findOverdueActiveVisits(OVERDUE_ACTIVE_STATUSES, startOfToday);
        if (overdue == null || overdue.isEmpty()) {
            return 0;
        }
        int closedCount = 0;
        for (Visit v : overdue) {
            if (v == null || v.getVisitId() == null) {
                continue;
            }
            try {
                if (closeInNewTransaction(v.getVisitId(), effectiveNow)) {
                    closedCount++;
                }
            } catch (RuntimeException ex) {
                log.warn("Không thể tự động đóng ca khám quá hạn visitId={}: {}", v.getVisitId(), ex.getMessage());
            }
        }
        if (closedCount > 0) {
            log.info("Đã tự động xử lý/đóng {} ca khám quá hạn trước ngày {}.", closedCount, effectiveNow.toLocalDate());
        }
        return closedCount;
    }

    @Transactional
    public int closeOverdueVisitsForPatient(Long profileId, LocalDateTime now) {
        if (profileId == null) {
            return 0;
        }
        LocalDateTime effectiveNow = now != null ? now : LocalDateTime.now(clock);
        LocalDateTime startOfToday = effectiveNow.toLocalDate().atStartOfDay();
        List<Visit> overdue = visits.findOverdueActiveVisitsForPatient(profileId, OVERDUE_ACTIVE_STATUSES, startOfToday);
        if (overdue == null || overdue.isEmpty()) {
            return 0;
        }
        int closedCount = 0;
        for (Visit v : overdue) {
            if (v != null && v.getVisitId() != null && closeSingleOverdueVisit(v.getVisitId(), effectiveNow)) {
                closedCount++;
            }
        }
        return closedCount;
    }

    private boolean closeInNewTransaction(Long visitId, LocalDateTime effectiveNow) {
        if (transactionManager == null) {
            return closeSingleOverdueVisit(visitId, effectiveNow);
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Boolean closed = template.execute(status -> closeSingleOverdueVisit(visitId, effectiveNow));
        return Boolean.TRUE.equals(closed);
    }

    private boolean closeSingleOverdueVisit(Long visitId, LocalDateTime effectiveNow) {
        Visit visit = visits.lockById(visitId).or(() -> visits.findById(visitId)).orElse(null);
        if (visit == null || visit.getCheckInAt() == null) {
            return false;
        }
        LocalDate today = effectiveNow.toLocalDate();
        LocalDateTime startOfToday = today.atStartOfDay();
        LocalDateTime endOfToday = today.plusDays(1).atStartOfDay();
        if (!visit.getCheckInAt().isBefore(startOfToday) || !OVERDUE_ACTIVE_STATUSES.contains(visit.getStatus())) {
            return false;
        }

        if (paraclinicalOrderService != null) {
            try {
                paraclinicalOrderService.syncParaclinicalOrdersToPerformedServices(visitId);
            } catch (RuntimeException ex) {
                log.warn("Không thể đồng bộ CLS khi đóng ca quá hạn visitId={}: {}", visitId, ex.getMessage());
            }
        }

        List<PerformedService> completedServices = performedServices != null
                ? performedServices.findByVisitIdWithDetails(visitId)
                : List.of();
        boolean hasBillableServices = completedServices.stream().anyMatch(ps -> !isInstallmentPlanService(ps));
        boolean hasCompletedInstallmentServices = completedServices.stream().anyMatch(this::isInstallmentPlanService);

        boolean hasPayablePrescription = prescriptions != null && prescriptions.findByVisit_VisitIdOrderByCreatedAtDesc(visitId).stream()
                .anyMatch(p -> p.getStatus() != PrescriptionStatus.CANCELLED
                        && p.getStatus() != PrescriptionStatus.EXTERNAL_PURCHASE);

        List<Invoice> activeInvoices = invoices != null
                ? invoices.findByVisit_VisitId(visitId).stream()
                        .filter(inv -> inv.getStatus() != InvoiceStatus.CANCELLED)
                        .toList()
                : List.of();
        boolean hasActiveInvoices = !activeInvoices.isEmpty();
        boolean hasUnpaidInvoice = activeInvoices.stream()
                .anyMatch(inv -> inv.getRemainingDebt() != null && inv.getRemainingDebt().compareTo(BigDecimal.ZERO) > 0);

        LocalDate visitDate = visit.getCheckInAt().toLocalDate();
        VisitStatus targetStatus;
        String systemNote;

        if (hasBillableServices || hasPayablePrescription || hasUnpaidInvoice) {
            targetStatus = VisitStatus.WAITING_PAYMENT;
            systemNote = "[Tự động kết chuyển chờ thanh toán do quá hạn ngày khám (" + visitDate + ")]";
        } else if (hasActiveInvoices || hasCompletedInstallmentServices) {
            targetStatus = VisitStatus.COMPLETED;
            systemNote = "[Tự động hoàn tất ca quá hạn đã thanh toán từ ngày " + visitDate + "]";
            if (visit.getCompletedAt() == null) {
                visit.setCompletedAt(effectiveNow);
            }
        } else {
            targetStatus = VisitStatus.CANCELLED;
            systemNote = "[Tự động đóng ca quá hạn chưa hoàn tất từ ngày " + visitDate + "]";
        }

        visit.setStatus(targetStatus);
        if (visit.getActualEndTime() == null) {
            visit.setActualEndTime(effectiveNow);
        }
        appendNoteIfMissing(visit, systemNote);
        visits.saveAndFlush(visit);

        closeVisitQueueTickets(visitId, targetStatus != VisitStatus.CANCELLED, effectiveNow);
        releaseChairIfUnusedToday(visit, startOfToday, endOfToday);
        releaseAppointmentReservationsIfNeeded(visit, targetStatus, effectiveNow);
        return true;
    }

    private void closeVisitQueueTickets(Long visitId, boolean keepDoneForActiveDesk, LocalDateTime effectiveNow) {
        if (tickets == null) {
            return;
        }
        List<QueueTicket> visitTickets = tickets.findByVisitVisitId(visitId);
        if (visitTickets == null || visitTickets.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (QueueTicket ticket : visitTickets) {
            if (ticket == null || ticket.getStatus() == QueueStatus.DONE || ticket.getStatus() == QueueStatus.CANCELLED) {
                continue;
            }
            if (keepDoneForActiveDesk && (ticket.getStatus() == QueueStatus.CALLED || ticket.getStatus() == QueueStatus.IN_SERVICE)) {
                ticket.setStatus(QueueStatus.DONE);
                if (ticket.getCompletedAt() == null) {
                    ticket.setCompletedAt(effectiveNow);
                }
            } else {
                ticket.setStatus(QueueStatus.CANCELLED);
            }
            changed = true;
        }
        if (changed) {
            tickets.saveAll(visitTickets);
        }
    }

    private void releaseChairIfUnusedToday(Visit visit, LocalDateTime startOfToday, LocalDateTime endOfToday) {
        if (chairs == null || visit.getChairId() == null) {
            return;
        }
        Long chairId = visit.getChairId();
        DentalChair chair = chairs.findById(chairId).orElse(null);
        if (chair == null || (chair.getStatus() != ChairStatus.IN_USE && chair.getStatus() != ChairStatus.TURNOVER)) {
            return;
        }
        long occupiedToday = visits.countOccupiedChairOnDay(
                chairId,
                visit.getVisitId(),
                List.of(VisitStatus.IN_EXAM, VisitStatus.IN_TREATMENT),
                startOfToday,
                endOfToday);
        Long branchId = visit.getBranch() != null ? visit.getBranch().getBranchId() : null;
        long heldByTodayTicket = branchId != null
                ? visits.countHoldingChairByTicketsOnDay(
                        branchId,
                        startOfToday.toLocalDate(),
                        List.of(QueueStatus.CALLED, QueueStatus.IN_SERVICE),
                        chairId,
                        visit.getVisitId())
                : 0L;
        if (occupiedToday == 0 && heldByTodayTicket == 0) {
            chair.setStatus(ChairStatus.AVAILABLE);
            chairs.saveAndFlush(chair);
        }
    }

    private void releaseAppointmentReservationsIfNeeded(Visit visit, VisitStatus targetStatus, LocalDateTime effectiveNow) {
        if (resourceBookingTransactionService == null || visit.getAppointmentId() == null) {
            return;
        }
        try {
            if (targetStatus == VisitStatus.CANCELLED) {
                resourceBookingTransactionService.releaseReservationsOnCancelOrNoShow(
                        visit.getAppointmentId(),
                        ReservationStatus.CANCELLED,
                        "VISIT_OVERDUE_AUTO_CLOSED",
                        effectiveNow);
            } else {
                resourceBookingTransactionService.onVisitDone(visit.getAppointmentId(), effectiveNow);
                if (visit.getChairId() != null) {
                    resourceBookingTransactionService.completeChairTurnover(visit.getChairId(), visit.getAppointmentId(), effectiveNow);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Không thể giải phóng tài nguyên cho appointmentId={} của ca quá hạn visitId={}: {}",
                    visit.getAppointmentId(), visit.getVisitId(), ex.getMessage());
        }
    }

    private boolean isInstallmentPlanService(PerformedService ps) {
        return ps != null
                && ps.getPlanItem() != null
                && ps.getPlanItem().getPlan() != null
                && Boolean.TRUE.equals(ps.getPlanItem().getPlan().getIsInstallment());
    }

    private void appendNoteIfMissing(Visit visit, String note) {
        if (note == null || note.isBlank()) {
            return;
        }
        String current = visit.getNotes();
        if (current == null || current.isBlank()) {
            visit.setNotes(note);
        } else if (!current.contains(note)) {
            visit.setNotes(current.trim() + " | " + note);
        }
    }
}
