package com.cvdcms.entity;

import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "paraclinical_order_items")
@Getter
@Setter
@NoArgsConstructor
public class ParaclinicalOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_id")
    private Long orderItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private ParaclinicalOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @Column(name = "tooth_numbers", length = 50)
    private String toothNumbers;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ParaclinicalOrderItemStatus status = ParaclinicalOrderItemStatus.WAITING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "technician_id")
    private StaffProfile technician;

    @Column(name = "performed_at")
    private LocalDateTime performedAt;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "results_json", columnDefinition = "json")
    private String resultsJson;

    @Column(name = "external_ref_id", length = 100)
    private String externalRefId;

    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ParaclinicalResultAttachment> attachments = new ArrayList<>();

    @OneToOne(mappedBy = "orderItem", cascade = CascadeType.ALL)
    private LabExternalOrder labExternalOrder;
}
