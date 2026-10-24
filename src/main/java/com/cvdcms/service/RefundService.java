package com.cvdcms.service;

import com.cvdcms.DTO.RefundRequestDTO;
import com.cvdcms.entity.Refund;
import com.cvdcms.entity.enums.RefundMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface RefundService {

    Refund processRefund(RefundRequestDTO request, Long staffUserId);

    Page<Refund> getRefunds(
            Long branchId,
            RefundMethod refundMethod,
            String keyword,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    );

    List<Refund> getRefundsByInvoiceId(Long invoiceId);

    Refund getRefundById(Long refundId);
}
