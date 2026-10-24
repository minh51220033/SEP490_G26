package com.cvdcms.service;

import com.cvdcms.DTO.ParaclinicalOrderCreateDTO;
import com.cvdcms.DTO.ParaclinicalOrderItemResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;

import java.util.List;

public interface ParaclinicalOrderService {
    List<ParaclinicalOrderItemResponseDTO> getOrdersByVisit(Long visitId);
    ParaclinicalOrderItemResponseDTO createOrder(Long visitId, ParaclinicalOrderCreateDTO dto, Long currentDoctorId);
    ParaclinicalOrderItemResponseDTO updateStatus(Long orderItemId, ParaclinicalOrderItemStatus newStatus, String resultSummary);
    void deleteOrder(Long orderItemId);
    List<ServiceDTO> getParaclinicalServices();
}
