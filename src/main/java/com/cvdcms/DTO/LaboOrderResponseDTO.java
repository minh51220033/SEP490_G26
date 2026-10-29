package com.cvdcms.DTO;

import com.cvdcms.entity.LaboOrder;
import com.cvdcms.entity.enums.LaboOrderStatus;
import com.cvdcms.entity.enums.LaboOrderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaboOrderResponseDTO {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Long laboId;
    private String laboCode;

    private Long visitId;
    private String visitCode;

    private Long profileId;
    private String patientCode;
    private String patientName;
    private String patientPhone;

    private Long branchId;
    private String branchName;

    private Long doctorId;
    private String doctorName;

    private Long supplierId;
    private String supplierCode;
    private String supplierName;
    private String supplierPhone;

    private LaboOrderType orderType;
    private String orderTypeLabel;

    private String toothPositions;
    private String materialType;
    private String shadeColor;

    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal costToClinic;

    private LocalDate sentDate;
    private String sentDateFormatted;

    private LocalDate tryInDate;
    private String tryInDateFormatted;
    private String tryInTime;

    private LocalDate expectedDate;
    private String expectedDateFormatted;

    private LocalDate receivedDate;
    private String receivedDateFormatted;

    private LaboOrderStatus status;
    private String statusLabel;
    private String statusBadgeClass;

    private String bridgeInfo;
    private String crownInfo;
    private String notes;

    // Link ảnh giấy xét nghiệm / phiếu labo
    private String imageUrl;

    public static LaboOrderResponseDTO fromEntity(LaboOrder entity) {
        if (entity == null) return null;

        String pName = entity.getPatientProfile() != null ? entity.getPatientProfile().getFullName() : "---";
        String pCode = entity.getPatientProfile() != null ? entity.getPatientProfile().getPatientCode() : "";
        String pPhone = entity.getPatientProfile() != null ? entity.getPatientProfile().getPhoneNumber() : "";
        Long pId = entity.getPatientProfile() != null ? entity.getPatientProfile().getProfileId() : null;

        String suppName = entity.getSupplier() != null ? entity.getSupplier().getSupplierName() : "---";
        String suppCode = entity.getSupplier() != null ? entity.getSupplier().getSupplierCode() : "";
        String suppPhone = entity.getSupplier() != null ? entity.getSupplier().getPhoneNumber() : "";
        Long suppId = entity.getSupplier() != null ? entity.getSupplier().getSupplierId() : null;

        String docName = entity.getDoctor() != null ? entity.getDoctor().getFullName() : "BS. Điều trị";
        Long docId = entity.getDoctor() != null ? entity.getDoctor().getStaffId() : null;

        String brName = entity.getBranch() != null ? entity.getBranch().getBranchName() : "Cơ sở chính";
        Long brId = entity.getBranch() != null ? entity.getBranch().getBranchId() : null;

        Long vId = entity.getVisit() != null ? entity.getVisit().getVisitId() : null;
        String vCode = entity.getVisit() != null ? entity.getVisit().getVisitCode() : "";

        // Status labels & badges
        String sLabel = "Mới gửi";
        String sBadge = "bg-warning-subtle text-dark border border-warning";
        if (entity.getStatus() != null) {
            switch (entity.getStatus()) {
                case SENT -> {
                    sLabel = "Mới gửi";
                    sBadge = "bg-info-subtle text-info-emphasis border border-info-subtle";
                }
                case PROCESSING -> {
                    sLabel = "Đang chế tác / Chờ KQ";
                    sBadge = "bg-warning-subtle text-warning-emphasis border border-warning";
                }
                case DELIVERED -> {
                    sLabel = "Đã nhận về phòng khám";
                    sBadge = "bg-primary-subtle text-primary border border-primary-subtle";
                }
                case FITTED_SUCCESS -> {
                    sLabel = "Đã lắp hoàn tất";
                    sBadge = "bg-success-subtle text-success border border-success-subtle";
                }
                case RETURNED -> {
                    sLabel = "Đã trả lại / Bảo hành";
                    sBadge = "bg-danger-subtle text-danger border border-danger-subtle";
                }
            }
        }

        // OrderType label
        String otLabel = "Làm mới";
        if (entity.getOrderType() != null) {
            switch (entity.getOrderType()) {
                case NEW -> otLabel = "Làm mới";
                case REDO -> otLabel = "Làm lại / Sửa chữa";
                case WARRANTY -> otLabel = "Bảo hành";
            }
        }

        return LaboOrderResponseDTO.builder()
                .laboId(entity.getLaboId())
                .laboCode(entity.getLaboCode())
                .visitId(vId)
                .visitCode(vCode)
                .profileId(pId)
                .patientCode(pCode)
                .patientName(pName)
                .patientPhone(pPhone)
                .branchId(brId)
                .branchName(brName)
                .doctorId(docId)
                .doctorName(docName)
                .supplierId(suppId)
                .supplierCode(suppCode)
                .supplierName(suppName)
                .supplierPhone(suppPhone)
                .orderType(entity.getOrderType())
                .orderTypeLabel(otLabel)
                .toothPositions(entity.getToothPositions())
                .materialType(entity.getMaterialType())
                .shadeColor(entity.getShadeColor())
                .quantity(entity.getQuantity() != null ? entity.getQuantity() : 1)
                .unitPrice(entity.getUnitPrice() != null ? entity.getUnitPrice() : BigDecimal.ZERO)
                .costToClinic(entity.getCostToClinic() != null ? entity.getCostToClinic() : BigDecimal.ZERO)
                .sentDate(entity.getSentDate())
                .sentDateFormatted(entity.getSentDate() != null ? entity.getSentDate().format(DATE_FMT) : "")
                .tryInDate(entity.getTryInDate())
                .tryInDateFormatted(entity.getTryInDate() != null ? entity.getTryInDate().format(DATE_FMT) : "")
                .tryInTime(entity.getTryInTime() != null ? entity.getTryInTime() : "")
                .expectedDate(entity.getExpectedDate())
                .expectedDateFormatted(entity.getExpectedDate() != null ? entity.getExpectedDate().format(DATE_FMT) : "")
                .receivedDate(entity.getReceivedDate())
                .receivedDateFormatted(entity.getReceivedDate() != null ? entity.getReceivedDate().format(DATE_FMT) : "")
                .status(entity.getStatus())
                .statusLabel(sLabel)
                .statusBadgeClass(sBadge)
                .bridgeInfo(entity.getBridgeInfo())
                .crownInfo(entity.getCrownInfo())
                .notes(entity.getNotes())
                .imageUrl(entity.getImageUrl())
                .build();
    }
}
