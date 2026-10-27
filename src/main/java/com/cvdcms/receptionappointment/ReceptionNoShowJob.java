package com.cvdcms.receptionappointment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Configuration
@EnableScheduling
public class ReceptionNoShowJob {
    private static final Logger log = LoggerFactory.getLogger(ReceptionNoShowJob.class);
    private final ReceptionAppointmentService appointments;

    public ReceptionNoShowJob(ReceptionAppointmentService appointments) {
        this.appointments = appointments;
    }

    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Bangkok")
    public void markEndedSessions() {
        LocalDateTime now = LocalDateTime.now(ReceptionAppointmentRules.ZONE);
        for (Long id : appointments.noShowCandidateIds(LocalDate.now(ReceptionAppointmentRules.ZONE))) {
            try { appointments.markNoShow(id, now); }
            catch (RuntimeException ex) { log.warn("Cannot process no-show appointment {}", id, ex); }
        }
    }
}
