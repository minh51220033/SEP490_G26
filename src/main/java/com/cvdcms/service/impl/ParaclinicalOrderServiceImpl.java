package com.cvdcms.service.impl;

import com.cvdcms.DTO.ParaclinicalOrderCreateDTO;
import com.cvdcms.DTO.ParaclinicalOrderItemResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.ParaclinicalOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParaclinicalOrderServiceImpl implements ParaclinicalOrderService {

    private final ParaclinicalOrderRepository paraclinicalOrderRepository;
    private final ParaclinicalOrderItemRepository paraclinicalOrderItemRepository;
    private final VisitRepository visitRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceCategoryRepository serviceCategoryRepository;
    private final StaffProfileRepository staffProfileRepository;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Override
    @Transactional(readOnly = true)
    public List<ParaclinicalOrderItemResponseDTO> getOrdersByVisit(Long visitId) {
        if (visitId == null) {
            return Collections.emptyList();
        }
        List<ParaclinicalOrderItem> items = paraclinicalOrderItemRepository
                .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(visitId);
        return items.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ParaclinicalOrderItemResponseDTO createOrder(Long visitId, ParaclinicalOrderCreateDTO dto, Long currentDoctorId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lượt khám ID: " + visitId));

        // Ràng buộc nghiệp vụ BR-EMR-02: Khóa bệnh án bất biến
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED) {
            throw new IllegalStateException("Hồ sơ bệnh án đã hoàn tất và bị khóa bất biến (BR-EMR-02). Không thể tạo thêm chỉ định cận lâm sàng.");
        }

        // Khởi tạo các dịch vụ cận lâm sàng mẫu nếu chưa có
        initDefaultParaclinicalServicesIfMissing();

        // Xác định dịch vụ cận lâm sàng được chỉ định
        com.cvdcms.entity.Service service = null;
        if (dto.getServiceId() != null) {
            service = serviceRepository.findById(dto.getServiceId()).orElse(null);
        }

        if (service == null && dto.getCustomServiceName() != null && !dto.getCustomServiceName().isBlank()) {
            String nameLower = dto.getCustomServiceName().toLowerCase().trim();
            List<com.cvdcms.entity.Service> matches = serviceRepository.findAll();
            for (com.cvdcms.entity.Service s : matches) {
                if (s.getServiceName().toLowerCase().contains(nameLower)
                        || nameLower.contains(s.getServiceName().toLowerCase())) {
                    service = s;
                    break;
                }
            }
        }

        if (service == null) {
            // Mặc định chọn dịch vụ X-Quang quanh chóp
            service = serviceRepository.findByServiceCode("CLS-XQ-CHOP")
                    .orElseGet(() -> serviceRepository.findAll().stream()
                            .filter(s -> Boolean.TRUE.equals(s.getIsParaclinical()))
                            .findFirst()
                            .orElseGet(() -> serviceRepository.findAll().stream().findFirst().orElseThrow(
                                    () -> new IllegalStateException("Không có dịch vụ nào trong hệ thống")
                            )));
        }

        // Xác định Bác sĩ chỉ định
        StaffProfile doctor = visit.getPrimaryDoctor();
        if (doctor == null && currentDoctorId != null) {
            doctor = staffProfileRepository.findById(currentDoctorId).orElse(null);
        }
        if (doctor == null) {
            doctor = staffProfileRepository.findAll().stream().findFirst().orElse(null);
        }

        // Tìm hoặc tạo mới ParaclinicalOrder gắn với Visit
        List<ParaclinicalOrder> existingOrders = paraclinicalOrderRepository
                .findByVisit_VisitIdOrderByOrderTimeDesc(visitId);
        ParaclinicalOrder order;
        if (!existingOrders.isEmpty() && existingOrders.get(0).getStatus() != ParaclinicalOrderStatus.CANCELLED) {
            order = existingOrders.get(0);
        } else {
            order = new ParaclinicalOrder();
            order.setVisit(visit);
            order.setPatientProfile(visit.getPatientProfile());
            order.setDoctor(doctor);
            order.setStatus(ParaclinicalOrderStatus.ORDERED);
            order.setOrderTime(LocalDateTime.now());
            order.setOrderCode("CĐ" + (1000 + (System.currentTimeMillis() % 9000)));
            order = paraclinicalOrderRepository.save(order);
        }

        // Tạo chi tiết chỉ định
        ParaclinicalOrderItem item = new ParaclinicalOrderItem();
        item.setOrder(order);
        item.setService(service);
        item.setToothNumbers((dto.getToothNumbers() != null && !dto.getToothNumbers().isBlank())
                ? dto.getToothNumbers().trim() : "Toàn hàm");
        item.setStatus(ParaclinicalOrderItemStatus.WAITING);
        if (dto.getNotes() != null && !dto.getNotes().isBlank()) {
            item.setResultSummary(dto.getNotes().trim());
        }

        ParaclinicalOrderItem savedItem = paraclinicalOrderItemRepository.save(item);
        log.info("Bác sĩ đã tạo chỉ định cận lâm sàng thành công: ItemId={}, Service={}, Tooth={}",
                savedItem.getOrderItemId(), service.getServiceName(), savedItem.getToothNumbers());

        return mapToDTO(savedItem);
    }

    @Override
    @Transactional
    public ParaclinicalOrderItemResponseDTO updateStatus(Long orderItemId, ParaclinicalOrderItemStatus newStatus, String resultSummary) {
        ParaclinicalOrderItem item = paraclinicalOrderItemRepository.findById(orderItemId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ định ID: " + orderItemId));

        Visit visit = item.getOrder().getVisit();
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED) {
            throw new IllegalStateException("Hồ sơ bệnh án đã hoàn tất và bị khóa bất biến (BR-EMR-02).");
        }

        item.setStatus(newStatus);
        if (newStatus == ParaclinicalOrderItemStatus.COMPLETED) {
            item.setPerformedAt(LocalDateTime.now());
            if (resultSummary != null && !resultSummary.isBlank()) {
                item.setResultSummary(resultSummary);
            }
        }

        ParaclinicalOrderItem savedItem = paraclinicalOrderItemRepository.save(item);

        // Kiểm tra nếu tất cả các chỉ định đều hoàn thành thì chuyển trạng thái Order cha
        ParaclinicalOrder parentOrder = savedItem.getOrder();
        List<ParaclinicalOrderItem> allItems = paraclinicalOrderItemRepository.findByOrder_OrderId(parentOrder.getOrderId());
        boolean allCompleted = allItems.stream().allMatch(i -> i.getStatus() == ParaclinicalOrderItemStatus.COMPLETED);
        if (allCompleted) {
            parentOrder.setStatus(ParaclinicalOrderStatus.COMPLETED);
            parentOrder.setCompletedAt(LocalDateTime.now());
            paraclinicalOrderRepository.save(parentOrder);
        }

        return mapToDTO(savedItem);
    }

    @Override
    @Transactional
    public void deleteOrder(Long orderItemId) {
        ParaclinicalOrderItem item = paraclinicalOrderItemRepository.findById(orderItemId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ định ID: " + orderItemId));

        Visit visit = item.getOrder().getVisit();
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED) {
            throw new IllegalStateException("Hồ sơ bệnh án đã hoàn tất và bị khóa bất biến (BR-EMR-02). Không thể xóa chỉ định.");
        }

        paraclinicalOrderItemRepository.delete(item);
        log.info("Đã xóa chỉ định cận lâm sàng ID: {}", orderItemId);
    }

    @Override
    @Transactional
    public List<ServiceDTO> getParaclinicalServices() {
        initDefaultParaclinicalServicesIfMissing();
        List<com.cvdcms.entity.Service> list = serviceRepository
                .findByIsParaclinicalTrueAndStatus(ServiceStatus.ACTIVE);
        if (list.isEmpty()) {
            list = serviceRepository.findByServiceTypeInAndStatus(
                    List.of(ServiceType.XRAY, ServiceType.LAB),
                    ServiceStatus.ACTIVE
            );
        }

        return list.stream().map(s -> ServiceDTO.builder()
                .serviceId(s.getServiceId())
                .categoryId(s.getCategory() != null ? s.getCategory().getCategoryId() : null)
                .serviceCode(s.getServiceCode())
                .serviceName(s.getServiceName())
                .serviceType(s.getServiceType() != null ? s.getServiceType().name() : "XRAY")
                .unit(s.getUnit())
                .basePrice(s.getBasePrice())
                .requiresTooth(s.getRequiresTooth())
                .isParaclinical(s.getIsParaclinical())
                .description(s.getDescription())
                .categoryName(s.getCategory() != null ? s.getCategory().getCategoryName() : "Cận lâm sàng")
                .build()
        ).collect(Collectors.toList());
    }

    @Transactional
    public synchronized void initDefaultParaclinicalServicesIfMissing() {
        if (serviceRepository.findByServiceCode("CLS-XQ-CHOP").isPresent()) {
            return;
        }

        ServiceCategory category = serviceCategoryRepository.findByCategoryCode("CAN_LAM_SANG")
                .orElseGet(() -> {
                    ServiceCategory cat = new ServiceCategory();
                    cat.setCategoryCode("CAN_LAM_SANG");
                    cat.setCategoryName("Cận lâm sàng & Chẩn đoán hình ảnh");
                    cat.setCategoryType(ServiceType.XRAY);
                    cat.setDisplayOrder(6);
                    cat.setIsActive(true);
                    return serviceCategoryRepository.save(cat);
                });

        createServiceIfMissing("CLS-XQ-CHOP", "Chụp X-quang quanh chóp (Periapical)", ServiceType.XRAY, new BigDecimal("50000"), "Phim", true, category);
        createServiceIfMissing("CLS-PANO", "Chụp Panorama toàn cảnh (Toàn hàm)", ServiceType.XRAY, new BigDecimal("150000"), "Phim", false, category);
        createServiceIfMissing("CLS-CT-3D", "Chụp CT ConeBeam 3D khảo sát xương", ServiceType.XRAY, new BigDecimal("500000"), "Lần", false, category);
        createServiceIfMissing("CLS-XN-MAU", "Xét nghiệm máu đông (PT/APTT/INR)", ServiceType.LAB, new BigDecimal("200000"), "Lần", false, category);
        createServiceIfMissing("CLS-CEPHALO", "Chụp Cephalometric sọ nghiêng", ServiceType.XRAY, new BigDecimal("200000"), "Phim", false, category);
    }

    private void createServiceIfMissing(String code, String name, ServiceType type, BigDecimal price, String unit, boolean reqTooth, ServiceCategory cat) {
        if (serviceRepository.findByServiceCode(code).isEmpty()) {
            com.cvdcms.entity.Service s = new com.cvdcms.entity.Service();
            s.setServiceCode(code);
            s.setServiceName(name);
            s.setServiceType(type);
            s.setBasePrice(price);
            s.setUnit(unit);
            s.setRequiresTooth(reqTooth);
            s.setIsParaclinical(true);
            s.setStatus(ServiceStatus.ACTIVE);
            s.setCategory(cat);
            serviceRepository.save(s);
        }
    }

    private ParaclinicalOrderItemResponseDTO mapToDTO(ParaclinicalOrderItem item) {
        String statusLabel = "Chờ thực hiện";
        if (item.getStatus() == ParaclinicalOrderItemStatus.COMPLETED) {
            statusLabel = "Đã có kết quả";
        } else if (item.getStatus() == ParaclinicalOrderItemStatus.IN_PROGRESS) {
            statusLabel = "Đang xử lý";
        } else if (item.getStatus() == ParaclinicalOrderItemStatus.CANCELLED) {
            statusLabel = "Đã hủy";
        }

        String doctorName = "Bác sĩ điều trị";
        if (item.getOrder().getDoctor() != null) {
            doctorName = item.getOrder().getDoctor().getFullName();
        }

        String orderTimeStr = (item.getOrder().getOrderTime() != null)
                ? item.getOrder().getOrderTime().format(DATE_TIME_FORMATTER)
                : LocalDateTime.now().format(DATE_TIME_FORMATTER);

        String performedAtStr = (item.getPerformedAt() != null)
                ? item.getPerformedAt().format(DATE_TIME_FORMATTER) : null;

        return ParaclinicalOrderItemResponseDTO.builder()
                .orderItemId(item.getOrderItemId())
                .orderId(item.getOrder().getOrderId())
                .orderCode(item.getOrder().getOrderCode())
                .serviceId(item.getService() != null ? item.getService().getServiceId() : null)
                .serviceCode(item.getService() != null ? item.getService().getServiceCode() : "")
                .serviceName(item.getService() != null ? item.getService().getServiceName() : "Chỉ định cận lâm sàng")
                .serviceType(item.getService() != null && item.getService().getServiceType() != null ? item.getService().getServiceType().name() : "XRAY")
                .price(item.getService() != null ? item.getService().getBasePrice() : BigDecimal.ZERO)
                .toothNumbers(item.getToothNumbers() != null ? item.getToothNumbers() : "Toàn hàm")
                .status(item.getStatus() != null ? item.getStatus().name() : "WAITING")
                .statusLabel(statusLabel)
                .doctorName(doctorName)
                .orderTime(orderTimeStr)
                .performedAt(performedAtStr)
                .resultSummary(item.getResultSummary() != null ? item.getResultSummary() : "")
                .attachmentsCount(item.getAttachments() != null ? item.getAttachments().size() : 0)
                .build();
    }
}
