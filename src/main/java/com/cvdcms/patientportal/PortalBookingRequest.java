package com.cvdcms.patientportal;

import com.cvdcms.entity.Appointment;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "portal_booking_requests")
@Getter @Setter
public class PortalBookingRequest {
    @Id @Column(name = "request_key", length = 36)
    private String requestKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;
}
