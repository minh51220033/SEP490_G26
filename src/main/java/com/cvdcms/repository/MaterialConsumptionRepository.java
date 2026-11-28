package com.cvdcms.repository;

import com.cvdcms.entity.MaterialConsumption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository truy vấn lịch sử tiêu hao vật tư nha khoa trong quá trình thực hiện thủ thuật lâm sàng ({@link MaterialConsumption}).
 */
@Repository
public interface MaterialConsumptionRepository extends JpaRepository<MaterialConsumption, Long> {

    /** Lấy danh sách vật tư đã tiêu hao gắn với một dịch vụ/thủ thuật đã thực hiện ({@code performedId}). */
    List<MaterialConsumption> findByPerformedService_PerformedId(Long performedId);

    /** Lấy toàn bộ lịch sử tiêu hao vật tư của một lượt khám ({@code visitId}), mới nhất trước. */
    List<MaterialConsumption> findByVisit_VisitIdOrderByConsumedAtDesc(Long visitId);
}
