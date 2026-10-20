package com.cvdcms.entity;

import com.cvdcms.entity.enums.LabExternalStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "lab_external_orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_lab_ext_item", columnNames = "order_item_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class LabExternalOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lab_order_id")
    private Long labOrderId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false, unique = true)
    private ParaclinicalOrderItem orderItem;

    @Column(name = "lab_partner_name", nullable = false, length = 150)
    private String labPartnerName;

    @Column(name = "sample_code", nullable = false, length = 50)
    private String sampleCode;

    @Column(name = "sample_type", nullable = false, length = 100)
    private String sampleType;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "expected_result_date")
    private LocalDateTime expectedResultDate;

    @Column(name = "received_result_at")
    private LocalDateTime receivedResultAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "lab_status", nullable = false, length = 20)
    private LabExternalStatus labStatus = LabExternalStatus.SENT;

    @Column(name = "result_file_url", length = 500)
    private String resultFileUrl;

    @Column(name = "notes", length = 255)
    private String notes;
}
