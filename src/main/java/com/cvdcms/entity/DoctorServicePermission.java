package com.cvdcms.entity;

import com.cvdcms.entity.enums.DoctorServicePermissionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(
        name = "doctor_service_permissions",
        indexes = {
                @Index(name = "idx_doc_svc_perm_lookup", columnList = "service_id, status, branch_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class DoctorServicePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "permission_id")
    private Long permissionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private StaffProfile staff;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DoctorServicePermissionStatus status = DoctorServicePermissionStatus.ACTIVE;

    @Column(name = "note", length = 255)
    private String note;
}
