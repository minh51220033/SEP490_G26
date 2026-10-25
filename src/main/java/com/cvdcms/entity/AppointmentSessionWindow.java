package com.cvdcms.entity;

import com.cvdcms.entity.enums.PreferredSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;

@Entity
@Table(name = "appointment_session_windows", uniqueConstraints =
        @UniqueConstraint(name = "uk_appt_session_window", columnNames = {"branch_id", "preferred_session"}))
@Getter @Setter
public class AppointmentSessionWindow {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "window_id")
    private Long windowId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_session", nullable = false, length = 20)
    private PreferredSession preferredSession;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
