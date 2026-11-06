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
public class ParaclinicalOrderServiceImpl implements ParaclinicalOrderService {

    private final ParaclinicalOrderRepository paraclinicalOrderRepository;
    private final ParaclinicalOrderItemRepository paraclinicalOrderItemRepository;
    private final VisitRepository visitRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceCategoryRepository serviceCategoryRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final PerformedServiceRepository performedServiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @org.springframework.beans.factory.annotation.Autowired
    public ParaclinicalOrderServiceImpl(
            ParaclinicalOrderRepository paraclinicalOrderRepository,
            ParaclinicalOrderItemRepository paraclinicalOrderItemRepository,
            VisitRepository visitRepository,
            ServiceRepository serviceRepository,
            ServiceCategoryRepository serviceCategoryRepository,
            StaffProfileRepository staffProfileRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PerformedServiceRepository performedServiceRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) InvoiceItemRepository invoiceItemRepository
    ) {
        this.paraclinicalOrderRepository = paraclinicalOrderRepository;
        this.paraclinicalOrderItemRepository = paraclinicalOrderItemRepository;
        this.visitRepository = visitRepository;
        this.serviceRepository = serviceRepository;
        this.serviceCategoryRepository = serviceCategoryRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.performedServiceRepository = performedServiceRepository;
        this.invoiceItemRepository = invoiceItemRepository;
    }

    public ParaclinicalOrderServiceImpl(
            ParaclinicalOrderRepository paraclinicalOrderRepository,
            ParaclinicalOrderItemRepository paraclinicalOrderItemRepository,
            VisitRepository visitRepository,
            ServiceRepository serviceRepository,
            ServiceCategoryRepository serviceCategoryRepository,
            StaffProfileRepository staffProfileRepository
    ) {
        this(paraclinicalOrderRepository, paraclinicalOrderItemRepository, visitRepository, serviceRepository, serviceCategoryRepository, staffProfileRepository, null, null);
    }

    @Override
    @Transactional
    public List<ParaclinicalOrderItemResponseDTO> getOrdersByVisit(Long visitId) {
        if (visitId == null) {
            return Collections.emptyList();
        }
        // Tự động đồng bộ các chỉ định đã có kết quả sang bảng Dịch vụ thực hiện (để Kế toán lập hóa đơn)
        syncParaclinicalOrdersToPerformedServices(visitId);

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

        if (dto.getImageUrl() != null && !dto.getImageUrl().isBlank()) {
            ParaclinicalResultAttachment att = new ParaclinicalResultAttachment();
            att.setOrderItem(item);
            att.setFileName("Phim chụp / Kết quả CLS");
            att.setFileUrl(dto.getImageUrl().trim());
            att.setFileType(ParaclinicalFileType.IMAGE);
            att.setCaption(service.getServiceName());
            item.getAttachments().add(att);
            item.setStatus(ParaclinicalOrderItemStatus.COMPLETED);
            item.setPerformedAt(LocalDateTime.now());
        }

        // Tự động đồng bộ sang PerformedService nếu đã hoàn thành (hoặc cập nhật)
        syncOrderItemToPerformedService(item);

        ParaclinicalOrderItem savedItem = paraclinicalOrderItemRepository.save(item);
        log.info("Bác sĩ đã tạo chỉ định cận lâm sàng thành công: ItemId={}, Service={}, Tooth={}",
                savedItem.getOrderItemId(), service.getServiceName(), savedItem.getToothNumbers());

        return mapToDTO(savedItem);
    }

    @Override
    @Transactional
    public ParaclinicalOrderItemResponseDTO updateOrder(Long orderItemId, ParaclinicalOrderCreateDTO dto) {
        ParaclinicalOrderItem item = paraclinicalOrderItemRepository.findById(orderItemId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ định ID: " + orderItemId));

        Visit visit = item.getOrder().getVisit();
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED) {
            throw new IllegalStateException("Hồ sơ bệnh án đã hoàn tất và bị khóa bất biến (BR-EMR-02).");
        }

        if (dto.getServiceId() != null) {
            serviceRepository.findById(dto.getServiceId()).ifPresent(item::setService);
        }

        if (dto.getToothNumbers() != null) {
            item.setToothNumbers(dto.getToothNumbers().isBlank() ? "Toàn hàm" : dto.getToothNumbers().trim());
        }

        String summary = dto.getResultSummary() != null ? dto.getResultSummary() : dto.getNotes();
        if (summary != null) {
            item.setResultSummary(summary.trim());
        }

        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            ParaclinicalOrderItemStatus st = ParaclinicalOrderItemStatus.valueOf(dto.getStatus().trim());
            item.setStatus(st);
            if (st == ParaclinicalOrderItemStatus.COMPLETED && item.getPerformedAt() == null) {
                item.setPerformedAt(LocalDateTime.now());
            }
        }

        if (dto.getImageUrl() != null) {
            String trimmedUrl = dto.getImageUrl().trim();
            if (trimmedUrl.isEmpty()) {
                item.getAttachments().clear();
            } else {
                if (item.getAttachments().isEmpty()) {
                    ParaclinicalResultAttachment att = new ParaclinicalResultAttachment();
                    att.setOrderItem(item);
                    att.setFileName("Phim chụp / Kết quả CLS");
                    att.setFileUrl(trimmedUrl);
                    att.setFileType(ParaclinicalFileType.IMAGE);
                    att.setCaption(item.getService() != null ? item.getService().getServiceName() : "Ảnh CLS");
                    item.getAttachments().add(att);
                } else {
                    item.getAttachments().get(0).setFileUrl(trimmedUrl);
                }
                if (dto.getStatus() == null || dto.getStatus().isBlank()) {
                    item.setStatus(ParaclinicalOrderItemStatus.COMPLETED);
                    if (item.getPerformedAt() == null) {
                        item.setPerformedAt(LocalDateTime.now());
                    }
                }
            }
        }

        // Đồng bộ sang PerformedService
        syncOrderItemToPerformedService(item);

        ParaclinicalOrderItem savedItem = paraclinicalOrderItemRepository.save(item);
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

        // Đồng bộ sang PerformedService
        syncOrderItemToPerformedService(item);

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

        if (performedServiceRepository != null && invoiceItemRepository != null) {
            PerformedService ps = item.getPerformedService();
            if (ps != null) {
                boolean isBilled = invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId());
                if (isBilled) {
                    throw new IllegalStateException("Không thể xóa chỉ định cận lâm sàng đã được Kế toán xuất hóa đơn.");
                }
                item.setPerformedService(null);
                paraclinicalOrderItemRepository.save(item);
                performedServiceRepository.delete(ps);
            }
        }

        paraclinicalOrderItemRepository.delete(item);
        log.info("Đã xóa chỉ định cận lâm sàng ID: {}", orderItemId);
    }

    private void syncOrderItemToPerformedService(ParaclinicalOrderItem item) {
        if (performedServiceRepository == null) {
            return;
        }
        if (item == null || item.getService() == null || item.getOrder() == null) {
            return;
        }
        Visit visit = item.getOrder().getVisit();
        if (visit == null) {
            return;
        }

        if (item.getStatus() == ParaclinicalOrderItemStatus.COMPLETED) {
            PerformedService ps = item.getPerformedService();
            if (ps == null) {
                ps = new PerformedService();
                ps.setVisit(visit);
                ps.setPatientProfile(item.getOrder().getPatientProfile() != null ? item.getOrder().getPatientProfile() : visit.getPatientProfile());
                ps.setBranch(visit.getBranch());
                ps.setDoctor(item.getOrder().getDoctor() != null ? item.getOrder().getDoctor() : visit.getPrimaryDoctor());
                if (ps.getDoctor() == null) {
                    ps.setDoctor(staffProfileRepository.findAll().stream().findFirst().orElse(null));
                }
            }

            ps.setService(item.getService());
            ps.setToothNumbers(item.getToothNumbers() != null && !item.getToothNumbers().isBlank() ? item.getToothNumbers() : "Toàn hàm");
            ps.setQuantity(BigDecimal.ONE);
            BigDecimal price = item.getService().getBasePrice() != null ? item.getService().getBasePrice() : BigDecimal.ZERO;
            ps.setUnitPrice(price);
            ps.setDiscountAmount(BigDecimal.ZERO);
            ps.setFinalPrice(price);
            ps.setCompletedAt(item.getPerformedAt() != null ? item.getPerformedAt() : LocalDateTime.now());
            ps.setStatus(PerformedServiceStatus.COMPLETED);
            ps.setClinicalNotes("Chỉ định cận lâm sàng [" + item.getOrder().getOrderCode() + "]: " + item.getService().getServiceName()
                    + (item.getResultSummary() != null && !item.getResultSummary().isBlank() ? " - " + item.getResultSummary() : ""));

            PerformedService savedPs = performedServiceRepository.save(ps);
            item.setPerformedService(savedPs);
        } else {
            // Khi trạng thái chưa hoàn thành hoặc hủy
            PerformedService ps = item.getPerformedService();
            if (ps != null) {
                boolean isBilled = invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId());
                if (!isBilled) {
                    ps.setStatus(PerformedServiceStatus.CANCELLED);
                    performedServiceRepository.save(ps);
                }
            }
        }
    }

    @Override
    @Transactional
    public void syncParaclinicalOrdersToPerformedServices(Long visitId) {
        if (visitId == null) return;
        List<ParaclinicalOrderItem> items = paraclinicalOrderItemRepository
                .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(visitId);
        for (ParaclinicalOrderItem item : items) {
            if (item.getStatus() == ParaclinicalOrderItemStatus.COMPLETED && item.getPerformedService() == null) {
                syncOrderItemToPerformedService(item);
                paraclinicalOrderItemRepository.save(item);
            }
        }
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

        String imageUrl = "";
        if (item.getAttachments() != null && !item.getAttachments().isEmpty()) {
            String url = item.getAttachments().get(0).getFileUrl();
            if (url != null) {
                imageUrl = url;
            }
        }

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
                .imageUrl(imageUrl)
                .attachmentsCount(item.getAttachments() != null ? item.getAttachments().size() : 0)
                .build();
    }
}
