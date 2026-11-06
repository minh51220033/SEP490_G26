package com.cvdcms.service;

import com.cvdcms.DTO.CashierPendingPatientDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface CashierOverviewService {

    java.util.Optional<CashierPendingPatientDTO> findDebtDetail(Long profileId, List<Long> allowedBranchIds);

    /**
     * Tra cứu danh sách bệnh nhân trên tab Tổng quan Thu ngân.
     * 
     * - Khi keyword trống/null: chỉ trả về các bệnh nhân thỏa mãn:
     *   A. Có ca khám / dịch vụ / đơn thuốc đủ điều kiện chưa lập hóa đơn.
     *   HOẶC
     *   B. Có hóa đơn còn hiệu lực và còn nợ > 0.
     *   (Bệnh nhân đã thanh toán hết và không còn khoản chờ lập sẽ bị ẩn mặc định).
     * 
     * - Khi có keyword: tìm kiếm tất cả bệnh nhân khớp theo tên, mã bệnh nhân, SĐT, profileId
     *   trong phạm vi các chi nhánh được phân quyền. Bệnh nhân chưa có hóa đơn hoặc đã thanh toán
     *   hết vẫn hiển thị với trạng thái tương ứng.
     *
     * @param keyword          từ khóa tìm kiếm (tên, mã BN, SĐT, profileId)
     * @param allowedBranchIds danh sách ID chi nhánh được phép truy cập (null nếu là Admin toàn hệ thống)
     * @param pageable         thông tin phân trang (10 phần tử/trang)
     * @return trang danh sách bệnh nhân
     */
    Page<CashierPendingPatientDTO> getPendingCashierPatients(
            String keyword,
            List<Long> allowedBranchIds,
            Pageable pageable
    );
}

