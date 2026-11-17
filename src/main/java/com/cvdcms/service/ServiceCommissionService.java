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
     * Xóa các khoản hoa hồng đang chờ (chưa gắn vào bảng lương) nếu dịch vụ thực hiện bị hủy.
     */
    void cancelPendingCommissionsForPerformedService(Long performedId);

    /**
     * Đảm bảo các dịch vụ thực hiện trên hóa đơn đã được ghi nhận hoa hồng (idempotent).
     */
    void ensureCommissionsForInvoice(Invoice invoice);
}
