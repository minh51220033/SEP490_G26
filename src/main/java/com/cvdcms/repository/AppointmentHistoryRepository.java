package com.cvdcms.repository;

import com.cvdcms.entity.AppointmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentHistoryRepository extends JpaRepository<AppointmentHistory, Long> {
    List<AppointmentHistory> findByAppointment_AppointmentIdOrderByCreatedAtAscHistoryIdAsc(Long appointmentId);
}
