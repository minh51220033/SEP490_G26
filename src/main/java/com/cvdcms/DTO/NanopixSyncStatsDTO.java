package com.cvdcms.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO trả về số liệu tổng quan và trạng thái đồng bộ kết nối NanoPix (SCR-12.4).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NanopixSyncStatsDTO {

    private long totalCount;
    private long matchedCount;
    private long unmatchedCount;
    private long errorCount;

    private String lastSyncTime;
    private String adapterStatus; // ONLINE, STANDBY, ERROR
    private String adapterType;   // "Eighteeth NanoPix USB / Local Storage Ingestion"
    private String deviceSerialNumber;
    private String deviceModel;
    private String storageDirectory;
    private String connectionMessage;
}
