package com.cvdcms.DTO;

import com.cvdcms.entity.enums.LaboOrderStatus;
import com.cvdcms.entity.enums.LaboOrderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaboOrderRequestDTO {

    private Long laboId;
    private String laboCode;
    private Long visitId;
    private Long profileId;
    private Long branchId;
    private Long doctorId;
    private Long supplierId;

    private String customerName;
    private String customerPhone;
    private String materialType; // Dịch vụ / Loại phục hình hoặc xét nghiệm
    private LaboOrderType orderType;
    private String toothPositions;
    private String shadeColor;

    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal costToClinic;

    private LocalDate sentDate;
    private LocalDate tryInDate;
    private String tryInTime;
    private LocalDate expectedDate;
    private LocalDate receivedDate;

    private LaboOrderStatus status;
    private String bridgeInfo;
    private String crownInfo;
    private String notes;

    // Link ảnh chụp phiếu gửi xưởng / mẫu máu / giấy kết quả xét nghiệm
    private String imageUrl;
}
