package com.cvdcms.service;

import com.cvdcms.DTO.NanopixSyncRequestDTO;
import com.cvdcms.DTO.NanopixSyncStatsDTO;
import com.cvdcms.entity.NanopixXraySyncLog;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Lớp tích hợp độc lập với hệ thống cảm biến kỹ thuật số Eighteeth NanoPix (FT12):
 * - Định nghĩa hợp đồng tiếp nhận dữ liệu ảnh X-quang chuẩn từ sensor hoặc phần mềm desktop
 * - Chống trùng lặp tuyệt đối dựa trên externalStudyUid
 * - Tự động đối chiếu thông minh khi có mã định danh tin cậy
 * - Đảm bảo phân tách ranh giới rõ ràng giữa adapter phần cứng và nghiệp vụ lõi
 */
public interface NanoPixIntegrationService {

    /**
     * Tiếp nhận và nạp kết quả ảnh X-quang từ máy NanoPix hoặc form nạp có kiểm soát.
     * @param request Dữ liệu định danh chụp từ máy nguồn
     * @param file File ảnh X-quang (JPEG, PNG, DCM...)
     * @param staffId ID nhân viên kỹ thuật hoặc lễ tân nạp ảnh
     * @return Bản ghi log đồng bộ đã lưu
     * @throws IOException khi lỗi đọc ghi file
     */
    NanopixXraySyncLog ingestXray(NanopixSyncRequestDTO request, MultipartFile file, Long staffId) throws IOException;

    /**
     * Giám sát và kiểm tra trạng thái sức khỏe adapter kết nối NanoPix.
     */
    NanopixSyncStatsDTO checkAdapterHealth();

    /**
     * Lưu trữ file ảnh X-quang an toàn vào hệ thống tệp tin phòng khám.
     */
    String storeXrayImage(MultipartFile file) throws IOException;
}
