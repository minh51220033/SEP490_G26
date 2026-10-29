package com.cvdcms.service;

import com.cvdcms.DTO.NanopixReconciliationRequestDTO;
import com.cvdcms.DTO.NanopixSyncRequestDTO;
import com.cvdcms.DTO.NanopixSyncStatsDTO;
import com.cvdcms.DTO.NanopixXrayResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Service quản lý kết quả phim X-quang NanoPix (FT12):
 * - SCR-12.1: Danh sách và trình xem ảnh X-quang
 * - SCR-12.2: Danh sách kết quả chưa khớp hồ sơ
 * - SCR-12.3: Form đối chiếu và liên kết kết quả với ca khám
 * - SCR-12.4: Trạng thái đồng bộ và thông báo lỗi kết nối
 */
public interface NanopixXrayService {

    /**
     * Lấy danh sách kết quả phim X-quang có phân trang và bộ lọc (SCR-12.1).
     * Hỗ trợ lọc theo cơ sở (Admin xem tất cả hoặc chọn cơ sở; Bác sĩ chỉ xem cơ sở được phân công).
     */
    Page<NanopixXrayResponseDTO> getXrays(String statusStr, String keyword, LocalDate fromDate, LocalDate toDate, Long branchId, Pageable pageable, Long userId);

    /**
     * Lấy danh sách kết quả chưa khớp hồ sơ (SCR-12.2).
     */
    List<NanopixXrayResponseDTO> getUnmatchedXrays(String keyword, Long userId);

    /**
     * Lấy chi tiết một phim X-quang theo ID.
     */
    NanopixXrayResponseDTO getXrayDetail(Long syncLogId, Long userId);

    /**
     * Thực hiện đối chiếu thủ công và liên kết kết quả vào bệnh nhân và ca khám (SCR-12.3).
     */
    NanopixXrayResponseDTO reconcileXray(Long syncLogId, NanopixReconciliationRequestDTO dto, Long userId);

    /**
     * Lấy thống kê tổng quan và trạng thái đồng bộ kết nối (SCR-12.4).
     */
    NanopixSyncStatsDTO getSyncStatistics(Long userId);

    /**
     * Lấy danh sách ca khám và chỉ định CLS của bệnh nhân để hỗ trợ form đối chiếu.
     */
    Map<String, Object> getReconciliationLookups(Long profileId);

    /**
     * Nạp phim X-quang mới từ máy NanoPix hoặc form nạp kiểm soát.
     */
    NanopixXrayResponseDTO ingestXray(NanopixSyncRequestDTO request, MultipartFile file, Long userId) throws IOException;

    /**
     * Lấy danh sách cơ sở mà tài khoản hiện tại được phép truy cập.
     */
    Map<String, Object> getAccessibleBranches(Long userId);
}
