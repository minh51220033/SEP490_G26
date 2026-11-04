package com.cvdcms.service;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.enums.AppointmentLifecycleAction;
import com.cvdcms.entity.enums.AppointmentStatus;

public interface AppointmentLifecycleService {
    boolean canTransition(AppointmentStatus current, AppointmentStatus target,
                          AppointmentLifecycleAction action);

    void transition(Appointment appointment, AppointmentStatus target,
                    AppointmentLifecycleAction action);

    void completeByVisit(Long visitId, Long actorUserId);
}
