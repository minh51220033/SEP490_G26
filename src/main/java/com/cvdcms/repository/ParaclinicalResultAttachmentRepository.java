package com.cvdcms.repository;

import com.cvdcms.entity.ParaclinicalResultAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ParaclinicalResultAttachmentRepository extends JpaRepository<ParaclinicalResultAttachment, Long> {
    List<ParaclinicalResultAttachment> findByOrderItem_OrderItemId(Long orderItemId);
}
