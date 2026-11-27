package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParaclinicalOrderItemResponseDTO {
    private Long orderItemId;
    private Long orderId;
    private String orderCode;
    private String externalRefId;
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private String serviceType;
    private BigDecimal price;
    private String toothNumbers;
    private String status;
    private String statusLabel;
    private String doctorName;
    private String orderTime;
    private String performedAt;
    private String resultSummary;
    private String imageUrl;
    private int attachmentsCount;
    private String primaryImageUrl;
    private List<AttachmentDTO> attachments;
    private Boolean isExternalLab;
    private Long labOrderId;
    private String labPartnerName;
    private String sampleCode;
    private String sampleType;
    private String labStatus;
    private String sentAt;
    private String expectedResultDate;
    private String receivedResultAt;
    private String resultFileUrl;
    private Long originVisitId;
    private String originVisitCode;
    private Boolean fromPreviousVisit;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentDTO {
        private Long attachmentId;
        private String fileName;
        private String fileUrl;
        private String fileType;
        private String caption;
        private String uploadedAt;
    }
}
