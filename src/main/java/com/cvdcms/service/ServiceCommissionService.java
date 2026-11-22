package com.cvdcms.service;

import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.PerformedService;

public interface ServiceCommissionService {

    /**
     * Tính và lưu hoa hồng (ServiceCommission) cho bác sĩ thực hiện chính và phụ tá (nếu có)
     * dựa trên các chính sách hoa hồng đang hoạt động.
     */
    void calculateCommissionsForPerformedService(PerformedService performedService);

    /**
     * Xóa hoặc bảo vệ các khoản hoa hồng theo trạng thái kỳ lương khi dịch vụ thực hiện bị hủy/sửa.
     */
    void cancelPendingCommissionsForPerformedService(Long performedId);

    /**
     * Tính lại hoa hồng khi dịch vụ thực hiện thay đổi dịch vụ hoặc giá trước khi lập hóa đơn.
     */
    default void recalculateCommissionsForPerformedService(PerformedService performedService) {
        if (performedService != null && performedService.getPerformedId() != null) {
            cancelPendingCommissionsForPerformedService(performedService.getPerformedId());
            calculateCommissionsForPerformedService(performedService);
        }
    }

    /**
     * Đảm bảo các dịch vụ thực hiện trên hóa đơn đã được ghi nhận hoa hồng (idempotent).
     */
    void ensureCommissionsForInvoice(Invoice invoice);
}
