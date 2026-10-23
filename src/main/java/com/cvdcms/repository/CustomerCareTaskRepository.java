package com.cvdcms.repository;

import com.cvdcms.entity.CustomerCareTask;
import com.cvdcms.entity.enums.CustomerCareTaskStatus;
import com.cvdcms.entity.enums.CustomerCareTaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerCareTaskRepository extends JpaRepository<CustomerCareTask, Long> {

    List<CustomerCareTask> findByPatientProfile_ProfileIdOrderByDueDateDesc(Long profileId);

    List<CustomerCareTask> findByAppointment_AppointmentId(Long appointmentId);

    List<CustomerCareTask> findByVisit_VisitId(Long visitId);

    List<CustomerCareTask> findByDueDateAndStatus(LocalDate dueDate, CustomerCareTaskStatus status);

    List<CustomerCareTask> findByTaskTypeAndStatus(CustomerCareTaskType taskType, CustomerCareTaskStatus status);

    Optional<CustomerCareTask> findFirstByAppointment_AppointmentId(Long appointmentId);
}
