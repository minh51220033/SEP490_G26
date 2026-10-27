package com.cvdcms.service;

import com.cvdcms.DTO.LaboOrderRequestDTO;
import com.cvdcms.DTO.LaboOrderResponseDTO;
import com.cvdcms.entity.enums.LaboOrderStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface LaboOrderService {

    List<LaboOrderResponseDTO> getOrdersByVisit(Long visitId);

    List<LaboOrderResponseDTO> getOrdersByPatient(Long profileId);

    List<LaboOrderResponseDTO> getAllOrders(String keyword, LaboOrderStatus status, Long supplierId, Long visitId, Long profileId);

    LaboOrderResponseDTO getOrderById(Long laboId);

    LaboOrderResponseDTO createOrder(LaboOrderRequestDTO dto, MultipartFile imageFile) throws IOException;

    LaboOrderResponseDTO updateOrder(Long laboId, LaboOrderRequestDTO dto, MultipartFile imageFile) throws IOException;

    LaboOrderResponseDTO updateStatus(Long laboId, LaboOrderStatus status, String note);

    LaboOrderResponseDTO uploadImage(Long laboId, MultipartFile file, String directUrl) throws IOException;

    void deleteOrder(Long laboId);

    Map<String, Object> getLaboStatistics(Long visitId, Long profileId);
}
