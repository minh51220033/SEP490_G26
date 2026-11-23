package com.cvdcms.service.impl;

import com.cvdcms.DTO.ParaclinicalBackfillReportDTO;
import com.cvdcms.DTO.ParaclinicalOrderCreateDTO;
import com.cvdcms.DTO.ParaclinicalOrderItemResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.ParaclinicalOrderService;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.emr.EmrRecordRepository emrRecordRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.ServiceCommissionService serviceCommissionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ServiceCommissionRepository serviceCommissionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.ClinicalQueueTicketRepository clinicalQueueTicketRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowExecutionRepository workflowExecutionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TreatmentPlanItemRepository treatmentPlanItemRepository;

    public void setServiceCommissionService(com.cvdcms.service.ServiceCommissionService serviceCommissionService) {
        this.serviceCommissionService = serviceCommissionService;
    }

    public void setServiceCommissionRepository(ServiceCommissionRepository serviceCommissionRepository) {
        this.serviceCommissionRepository = serviceCommissionRepository;
    }

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final java.util.concurrent.atomic.AtomicLong XRAY_REF_COUNTER =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() % 100000);

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

    private String generateUniqueExternalRefId() {
        for (int attempt = 0; attempt < 10; attempt++) {
            long seq = Math.abs(XRAY_REF_COUNTER.incrementAndGet() % 900000) + 100000;
            String candidate = "XQ" + seq;
            if (paraclinicalOrderItemRepository.findByExternalRefIdIgnoreCase(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "XQ" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParaclinicalOrderItemResponseDTO> getOrdersByVisit(Long visitId) {
        if (visitId == null) {
            return Collections.emptyList();
        }
        // Đọc thuần (không phát sinh INSERT/UPDATE trong GET)
        List<ParaclinicalOrderItem> items = paraclinicalOrderItemRepository
                .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(visitId);
        return items.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ParaclinicalOrderItemResponseDTO createOrder(Long visitId, ParaclinicalOrderCreateDTO dto, Long currentDoctorId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lượt khám ID: " + visitId));

        if (visit.getBranch() == null || visit.getPatientProfile() == null) {
            throw new IllegalStateException("Lượt khám ID " + visitId + " thiếu thông tin chi nhánh hoặc bệnh nhân.");
        }

        // Ràng buộc nghiệp vụ BR-EMR-02: Khóa bệnh án bất biến
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Hồ sơ bệnh án đã hoàn tất và bị khóa bất biến. Không thể tạo thêm chỉ định cận lâm sàng");

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

        // Xác định Bác sĩ chỉ định (không fallback findAll().get(0))
        StaffProfile doctor = visit.getPrimaryDoctor();
        if (doctor == null && currentDoctorId != null) {
            doctor = staffProfileRepository.findById(currentDoctorId).orElse(null);
        }
        if (doctor == null) {
            throw new IllegalStateException("Không xác định được bác sĩ chỉ định cho lượt khám ID: " + visitId);
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

        // Tạo chi tiết chỉ định với mã liên kết duy nhất (external_ref_id) để máy chụp đối chiếu
        ParaclinicalOrderItem item = new ParaclinicalOrderItem();
        item.setOrder(order);
        item.setService(service);
        item.setToothNumbers((dto.getToothNumbers() != null && !dto.getToothNumbers().isBlank())
                ? dto.getToothNumbers().trim() : "Toàn hàm");
        item.setStatus(ParaclinicalOrderItemStatus.WAITING);
        item.setExternalRefId(generateUniqueExternalRefId());

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

        // Tự động đồng bộ sang PerformedService nếu đã hoàn thành
        syncOrderItemToPerformedService(item, false);

        ParaclinicalOrderItem savedItem = paraclinicalOrderItemRepository.save(item);
        if (savedItem != null && (savedItem.getExternalRefId() == null || savedItem.getExternalRefId().isBlank())) {
            savedItem.setExternalRefId(item.getExternalRefId());
        }

        if (savedItem.getStatus() != ParaclinicalOrderItemStatus.COMPLETED) {
            if (visit.getStatus() == VisitStatus.IN_EXAM || visit.getStatus() == VisitStatus.WAITING) {
                visit.setStatus(VisitStatus.WAITING_PARACLINICAL);
                visitRepository.save(visit);
            }
        } else {
            checkAndUpdateVisitParaclinicalStatus(savedItem.getOrder());
        }

        log.info("Bác sĩ đã tạo chỉ định cận lâm sàng thành công: ItemId={}, ExternalRefId={}, Service={}, Tooth={}",
                savedItem.getOrderItemId(), savedItem.getExternalRefId(), service.getServiceName(), savedItem.getToothNumbers());

        return mapToDTO(savedItem);
    }

    @Override
    @Transactional
    public ParaclinicalOrderItemResponseDTO updateOrder(Long orderItemId, ParaclinicalOrderCreateDTO dto) {
        ParaclinicalOrderItem item = paraclinicalOrderItemRepository.findByIdForUpdate(orderItemId)
                .or(() -> paraclinicalOrderItemRepository.findById(orderItemId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ định ID: " + orderItemId));

        Visit visit = item.getOrder().getVisit();
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Hồ sơ bệnh án đã hoàn tất và bị khóa bất biến");

        PerformedService existingPs = item.getPerformedService();
        boolean hasActiveInvoice = existingPs != null && invoiceItemRepository != null
                && invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(existingPs.getPerformedId());

        boolean serviceChanged = dto.getServiceId() != null
                && (item.getService() == null || !dto.getServiceId().equals(item.getService().getServiceId()));

        ParaclinicalOrderItemStatus requestedStatus = null;
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            requestedStatus = ParaclinicalOrderItemStatus.valueOf(dto.getStatus().trim());
        }

        if (hasActiveInvoice) {
            if (serviceChanged) {
                throw new IllegalStateException("Chỉ định cận lâm sàng đã được lập hóa đơn còn hiệu lực. Không thể thay đổi dịch vụ; vui lòng điều chỉnh hóa đơn theo nghiệp vụ.");
            }
            if (requestedStatus != null && requestedStatus != ParaclinicalOrderItemStatus.COMPLETED) {
                throw new IllegalStateException("Chỉ định cận lâm sàng đã được lập hóa đơn còn hiệu lực. Không thể hủy hoặc hoàn tác trạng thái hoàn thành.");
            }
        }

        if (dto.getServiceId() != null && serviceChanged) {
            com.cvdcms.entity.Service newService = serviceRepository.findById(dto.getServiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ ID: " + dto.getServiceId()));
            item.setService(newService);
        }

        if (dto.getToothNumbers() != null) {
            item.setToothNumbers(dto.getToothNumbers().isBlank() ? "Toàn hàm" : dto.getToothNumbers().trim());
        }

        String summary = dto.getResultSummary() != null ? dto.getResultSummary() : dto.getNotes();
        if (summary != null) {
            item.setResultSummary(summary.trim());
        }

        if (requestedStatus != null) {
            item.setStatus(requestedStatus);
            if (requestedStatus == ParaclinicalOrderItemStatus.COMPLETED && item.getPerformedAt() == null) {
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
                if (requestedStatus == null) {
                    item.setStatus(ParaclinicalOrderItemStatus.COMPLETED);
                    if (item.getPerformedAt() == null) {
                        item.setPerformedAt(LocalDateTime.now());
                    }
                }
            }
        }

        // Đồng bộ sang PerformedService (giữ nguyên giá đã chốt khi không đổi dịch vụ)
        syncOrderItemToPerformedService(item, serviceChanged);

        ParaclinicalOrderItem savedItem = paraclinicalOrderItemRepository.save(item);
        checkAndUpdateVisitParaclinicalStatus(savedItem.getOrder());
        return mapToDTO(savedItem);
    }

    @Override
    @Transactional
    public ParaclinicalOrderItemResponseDTO updateStatus(Long orderItemId, ParaclinicalOrderItemStatus newStatus, String resultSummary) {
        ParaclinicalOrderItem item = paraclinicalOrderItemRepository.findByIdForUpdate(orderItemId)
                .or(() -> paraclinicalOrderItemRepository.findById(orderItemId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ định ID: " + orderItemId));

        Visit visit = item.getOrder().getVisit();
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Hồ sơ bệnh án đã hoàn tất và bị khóa bất biến");

        if (item.getPerformedService() != null
                && newStatus != ParaclinicalOrderItemStatus.COMPLETED
                && invoiceItemRepository != null
                && invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(item.getPerformedService().getPerformedId())) {
            throw new IllegalStateException("Chỉ định cận lâm sàng đã được lập hóa đơn còn hiệu lực. Không thể hủy hoặc chuyển trạng thái chưa hoàn thành.");
        }

        item.setStatus(newStatus);
        if (newStatus == ParaclinicalOrderItemStatus.COMPLETED) {
            if (item.getPerformedAt() == null) {
                item.setPerformedAt(LocalDateTime.now());
            }
            if (resultSummary != null && !resultSummary.isBlank()) {
                item.setResultSummary(resultSummary);
            }
        }

        // Đồng bộ sang PerformedService (không ghi đè giá cũ nếu PerformedService đã tồn tại)
        syncOrderItemToPerformedService(item, false);

        ParaclinicalOrderItem savedItem = paraclinicalOrderItemRepository.save(item);
        checkAndUpdateVisitParaclinicalStatus(savedItem.getOrder());

        return mapToDTO(savedItem);
    }

    private void checkAndUpdateVisitParaclinicalStatus(ParaclinicalOrder parentOrder) {
        if (parentOrder == null) return;
        List<ParaclinicalOrderItem> allItems = paraclinicalOrderItemRepository.findByOrder_OrderId(parentOrder.getOrderId());
        List<ParaclinicalOrderItem> activeItems = allItems.stream()
                .filter(i -> i.getStatus() != ParaclinicalOrderItemStatus.CANCELLED)
                .toList();
        boolean allCompleted = !activeItems.isEmpty()
                && activeItems.stream().allMatch(i -> i.getStatus() == ParaclinicalOrderItemStatus.COMPLETED);
        boolean allCancelled = !allItems.isEmpty() && activeItems.isEmpty();

        if (allCompleted) {
            parentOrder.setStatus(ParaclinicalOrderStatus.COMPLETED);
            parentOrder.setCompletedAt(LocalDateTime.now());
            paraclinicalOrderRepository.save(parentOrder);
        } else if (allCancelled) {
            parentOrder.setStatus(ParaclinicalOrderStatus.CANCELLED);
            parentOrder.setCompletedAt(null);
            paraclinicalOrderRepository.save(parentOrder);
        }

        if (allCompleted || allCancelled) {
            Visit v = parentOrder.getVisit();
            if (v != null && v.getStatus() == VisitStatus.WAITING_PARACLINICAL) {
                boolean hasOtherPendingInVisit = paraclinicalOrderItemRepository
                        .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(v.getVisitId())
                        .stream()
                        .anyMatch(i -> i.getStatus() != ParaclinicalOrderItemStatus.CANCELLED
                                && i.getStatus() != ParaclinicalOrderItemStatus.COMPLETED);
                if (!hasOtherPendingInVisit) {
                    boolean hasInServiceTicket = clinicalQueueTicketRepository != null
                            && clinicalQueueTicketRepository.findByVisitIdAndTypeOrderByDesc(
                                            v.getVisitId(), com.cvdcms.entity.enums.QueueType.EXAM)
                                    .stream()
                                    .anyMatch(t -> t.getStatus() == com.cvdcms.entity.enums.QueueStatus.IN_SERVICE);
                    if (allCompleted) {
                        v.setStatus(hasInServiceTicket ? VisitStatus.IN_EXAM : VisitStatus.WAITING_RESULT);
                    } else {
                        v.setStatus(hasInServiceTicket ? VisitStatus.IN_EXAM : VisitStatus.WAITING);
                    }
                    visitRepository.save(v);
                }
            }
        }
    }

    @Override
    @Transactional
    public void deleteOrder(Long orderItemId) {
        ParaclinicalOrderItem item = paraclinicalOrderItemRepository.findByIdForUpdate(orderItemId)
                .or(() -> paraclinicalOrderItemRepository.findById(orderItemId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ định ID: " + orderItemId));

        ParaclinicalOrder parentOrder = item.getOrder();
        Visit visit = parentOrder.getVisit();
        com.cvdcms.emr.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Hồ sơ bệnh án đã hoàn tất và bị khóa bất biến. Không thể xóa chỉ định");

        PerformedService ps = item.getPerformedService();
        boolean hasAnyInvoiceHistory = ps != null && invoiceItemRepository != null
                && invoiceItemRepository.existsByPerformedService_PerformedId(ps.getPerformedId());
        boolean hasCommissionHistory = ps != null && serviceCommissionRepository != null
                && !serviceCommissionRepository.findByPerformedService_PerformedId(ps.getPerformedId()).isEmpty();
        boolean hasExternalRef = item.getExternalRefId() != null && !item.getExternalRefId().isBlank();
        boolean hasResultOrMedia = (item.getResultSummary() != null && !item.getResultSummary().isBlank())
                || (item.getResultsJson() != null && !item.getResultsJson().isBlank())
                || item.getPerformedAt() != null
                || (item.getAttachments() != null && !item.getAttachments().isEmpty())
                || item.getLabExternalOrder() != null
                || item.getStatus() == ParaclinicalOrderItemStatus.COMPLETED
                || item.getStatus() == ParaclinicalOrderItemStatus.IN_PROGRESS;

        if (ps != null) {
            boolean isBilled = invoiceItemRepository != null
                    && invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId());
            if (isBilled) {
                throw new IllegalStateException("Không thể xóa chỉ định cận lâm sàng đã được Kế toán xuất hóa đơn còn hiệu lực.");
            }
            if (serviceCommissionService != null) {
                serviceCommissionService.cancelPendingCommissionsForPerformedService(ps.getPerformedId());
            }
        }

        // Giữ lại lịch sử liên kết (externalRefId, kết quả, ảnh/labo, hóa đơn CANCELLED, hoa hồng từng phát sinh) bằng hủy mềm
        if (hasAnyInvoiceHistory || hasCommissionHistory || hasExternalRef || hasResultOrMedia) {
            if (ps != null) {
                ps.setStatus(PerformedServiceStatus.CANCELLED);
                performedServiceRepository.save(ps);
            }
            item.setStatus(ParaclinicalOrderItemStatus.CANCELLED);
            paraclinicalOrderItemRepository.save(item);
            checkAndUpdateVisitParaclinicalStatus(parentOrder);
            log.info("Đã hủy mềm chỉ định cận lâm sàng có lịch sử liên kết ID: {}, ExternalRefId={}", orderItemId, item.getExternalRefId());
            return;
        }

        if (ps != null && performedServiceRepository != null) {
            item.setPerformedService(null);
            paraclinicalOrderItemRepository.save(item);
            performedServiceRepository.delete(ps);
        }

        paraclinicalOrderItemRepository.delete(item);
        paraclinicalOrderItemRepository.flush();
        checkAndUpdateVisitParaclinicalStatus(parentOrder);
        log.info("Đã xóa chỉ định cận lâm sàng nháp ID: {}", orderItemId);
    }

    private void syncOrderItemToPerformedService(ParaclinicalOrderItem item, boolean serviceChanged) {
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
            String notes = "Chỉ định cận lâm sàng [" + item.getOrder().getOrderCode() + "]: " + item.getService().getServiceName()
                    + (item.getResultSummary() != null && !item.getResultSummary().isBlank() ? " - " + item.getResultSummary() : "");
            String teeth = item.getToothNumbers() != null && !item.getToothNumbers().isBlank() ? item.getToothNumbers() : "Toàn hàm";

            if (ps == null) {
                // Lần tạo/chốt giá đầu tiên: lấy giá danh mục hiện hành và kiểm tra đủ dữ liệu nguồn
                PatientProfile patient = item.getOrder().getPatientProfile() != null
                        ? item.getOrder().getPatientProfile()
                        : visit.getPatientProfile();
                Branch branch = visit.getBranch();
                StaffProfile doctor = item.getOrder().getDoctor() != null
                        ? item.getOrder().getDoctor()
                        : visit.getPrimaryDoctor();
                if (patient == null || branch == null || doctor == null) {
                    throw new IllegalStateException("Không thể đồng bộ dịch vụ thực hiện cho chỉ định CLS ID "
                            + item.getOrderItemId() + " do thiếu thông tin bệnh nhân, chi nhánh hoặc bác sĩ.");
                }

                TreatmentPlanItem matchedPlanItem = resolveActivePlanItemForOrder(visit, item.getService().getServiceId());
                BigDecimal price = item.getService().getBasePrice() != null ? item.getService().getBasePrice() : BigDecimal.ZERO;
                if (matchedPlanItem != null) {
                    TreatmentPlan parentPlan = matchedPlanItem.getPlan() != null
                            ? matchedPlanItem.getPlan()
                            : (matchedPlanItem.getStage() != null ? matchedPlanItem.getStage().getPlan() : null);
                    boolean isInstallment = parentPlan != null && Boolean.TRUE.equals(parentPlan.getIsInstallment());
                    if (isInstallment) {
                        price = BigDecimal.ZERO;
                    } else if (matchedPlanItem.getEstimatedPrice() != null) {
                        price = matchedPlanItem.getEstimatedPrice();
                    }
                }

                ps = new PerformedService();
                ps.setVisit(visit);
                ps.setPlanItem(matchedPlanItem);
                ps.setPatientProfile(patient);
                ps.setBranch(branch);
                ps.setDoctor(doctor);
                ps.setService(item.getService());
                ps.setToothNumbers(teeth);
                ps.setQuantity(BigDecimal.ONE);
                ps.setUnitPrice(price);
                ps.setDiscountAmount(BigDecimal.ZERO);
                ps.setFinalPrice(price);
                ps.setCompletedAt(item.getPerformedAt() != null ? item.getPerformedAt() : LocalDateTime.now());
                ps.setStatus(PerformedServiceStatus.COMPLETED);
                ps.setClinicalNotes(notes);

                PerformedService savedPs = performedServiceRepository.save(ps);
                item.setPerformedService(savedPs);
                if (serviceCommissionService != null) {
                    serviceCommissionService.calculateCommissionsForPerformedService(savedPs);
                }
            } else {
                // Đã tồn tại PerformedService: bảo toàn giá, chiết khấu, số lượng và completedAt đã chốt
                boolean wasCancelled = ps.getStatus() == PerformedServiceStatus.CANCELLED;
                ps.setToothNumbers(teeth);
                ps.setClinicalNotes(notes);
                if (wasCancelled) {
                    ps.setStatus(PerformedServiceStatus.COMPLETED);
                }
                if (serviceChanged) {
                    ps.setService(item.getService());
                    BigDecimal newPrice = item.getService().getBasePrice() != null ? item.getService().getBasePrice() : BigDecimal.ZERO;
                    ps.setUnitPrice(newPrice);
                    ps.setDiscountAmount(BigDecimal.ZERO);
                    ps.setFinalPrice(newPrice);
                }

                PerformedService savedPs = performedServiceRepository.save(ps);
                if (serviceCommissionService != null) {
                    if (serviceChanged) {
                        serviceCommissionService.recalculateCommissionsForPerformedService(savedPs);
                    } else if (wasCancelled) {
                        serviceCommissionService.calculateCommissionsForPerformedService(savedPs);
                    }
                }
            }
        } else {
            // Khi trạng thái chưa hoàn thành hoặc hủy
            PerformedService ps = item.getPerformedService();
            if (ps != null) {
                boolean isBilled = invoiceItemRepository != null
                        && invoiceItemRepository.existsByPerformedIdAndInvoiceNotCancelled(ps.getPerformedId());
                if (isBilled) {
                    throw new IllegalStateException("Chỉ định cận lâm sàng đã được lập hóa đơn còn hiệu lực, không thể hủy dịch vụ thực hiện.");
                }
                if (serviceCommissionService != null) {
                    serviceCommissionService.cancelPendingCommissionsForPerformedService(ps.getPerformedId());
                }
                ps.setStatus(PerformedServiceStatus.CANCELLED);
                performedServiceRepository.save(ps);
            }
        }
    }

    @Override
    @Transactional
    public void syncParaclinicalOrdersToPerformedServices(Long visitId) {
        if (visitId == null) return;
        List<ParaclinicalOrderItem> items = paraclinicalOrderItemRepository
                .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(visitId);
        for (ParaclinicalOrderItem candidate : items) {
            if (candidate.getOrderItemId() == null) continue;
            ParaclinicalOrderItem item = paraclinicalOrderItemRepository.findByIdForUpdate(candidate.getOrderItemId())
                    .orElse(candidate);
            if (item.getStatus() == ParaclinicalOrderItemStatus.COMPLETED && item.getPerformedService() == null) {
                syncOrderItemToPerformedService(item, false);
                paraclinicalOrderItemRepository.save(item);
            }
        }
    }

    @Override
    @Transactional
    public ParaclinicalBackfillReportDTO reconcileCompletedOrdersMissingPerformedService(boolean dryRun) {
        ParaclinicalBackfillReportDTO report = new ParaclinicalBackfillReportDTO();
        report.setDryRun(dryRun);
        if (performedServiceRepository == null) {
            return report;
        }
        List<Long> candidateIds = paraclinicalOrderItemRepository.findCompletedOrderItemIdsMissingPerformedService();
        if (candidateIds == null || candidateIds.isEmpty()) {
            return report;
        }
        report.setTotalCandidates(candidateIds.size());

        for (Long orderItemId : candidateIds) {
            ParaclinicalOrderItem item = paraclinicalOrderItemRepository.findByIdForUpdate(orderItemId).orElse(null);
            if (item == null || item.getStatus() != ParaclinicalOrderItemStatus.COMPLETED || item.getPerformedService() != null) {
                report.setSkippedCount(report.getSkippedCount() + 1);
                continue;
            }
            ParaclinicalOrder order = item.getOrder();
            Visit visit = order != null ? order.getVisit() : null;
            PatientProfile patient = order != null && order.getPatientProfile() != null
                    ? order.getPatientProfile()
                    : (visit != null ? visit.getPatientProfile() : null);
            Branch branch = visit != null ? visit.getBranch() : null;
            StaffProfile doctor = order != null && order.getDoctor() != null
                    ? order.getDoctor()
                    : (visit != null ? visit.getPrimaryDoctor() : null);
            com.cvdcms.entity.Service service = item.getService();

            if (order == null || visit == null || patient == null || branch == null || doctor == null || service == null) {
                report.setErrorCount(report.getErrorCount() + 1);
                report.getErrors().add("OrderItem ID " + orderItemId
                        + " thiếu liên kết bắt buộc (order/visit/patient/branch/doctor/service).");
                continue;
            }

            if (dryRun) {
                report.setProcessedCount(report.getProcessedCount() + 1);
                report.getAffectedOrderItemIds().add(orderItemId);
            } else {
                syncOrderItemToPerformedService(item, false);
                ParaclinicalOrderItem saved = paraclinicalOrderItemRepository.save(item);
                report.setProcessedCount(report.getProcessedCount() + 1);
                report.getAffectedOrderItemIds().add(orderItemId);
                if (saved.getPerformedService() != null && saved.getPerformedService().getPerformedId() != null) {
                    report.getCreatedPerformedIds().add(saved.getPerformedService().getPerformedId());
                }
            }
        }

        return report;
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
            statusLabel = "Đang đồng bộ";
        } else if (item.getStatus() == ParaclinicalOrderItemStatus.CANCELLED) {
            statusLabel = "Đã hủy";
        }

        String doctorName = "Bác sĩ điều trị";
        if (item.getOrder() != null && item.getOrder().getDoctor() != null) {
            doctorName = item.getOrder().getDoctor().getFullName();
        }

        String orderTimeStr = (item.getOrder() != null && item.getOrder().getOrderTime() != null)
                ? item.getOrder().getOrderTime().format(DATE_TIME_FORMATTER)
                : LocalDateTime.now().format(DATE_TIME_FORMATTER);

        String performedAtStr = (item.getPerformedAt() != null)
                ? item.getPerformedAt().format(DATE_TIME_FORMATTER) : null;

        String externalRef = (item.getExternalRefId() != null && !item.getExternalRefId().isBlank())
                ? item.getExternalRefId()
                : (item.getOrderItemId() != null ? "XQ" + item.getOrderItemId() : "");

        List<ParaclinicalOrderItemResponseDTO.AttachmentDTO> attachmentDTOs = new ArrayList<>();
        String primaryImageUrl = null;
        if (item.getAttachments() != null && !item.getAttachments().isEmpty()) {
            for (ParaclinicalResultAttachment att : item.getAttachments()) {
                if (att == null || att.getFileUrl() == null) continue;
                if (primaryImageUrl == null) {
                    primaryImageUrl = att.getFileUrl();
                }
                attachmentDTOs.add(ParaclinicalOrderItemResponseDTO.AttachmentDTO.builder()
                        .attachmentId(att.getAttachmentId())
                        .fileName(att.getFileName())
                        .fileUrl(att.getFileUrl())
                        .fileType(att.getFileType() != null ? att.getFileType().name() : "IMAGE")
                        .caption(att.getCaption())
                        .uploadedAt(att.getUploadedAt() != null ? att.getUploadedAt().format(DATE_TIME_FORMATTER) : performedAtStr)
                        .build());
            }
        }

        return ParaclinicalOrderItemResponseDTO.builder()
                .orderItemId(item.getOrderItemId())
                .orderId(item.getOrder() != null ? item.getOrder().getOrderId() : null)
                .orderCode(item.getOrder() != null ? item.getOrder().getOrderCode() : "")
                .externalRefId(externalRef)
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
                .imageUrl(primaryImageUrl != null ? primaryImageUrl : "") // Hỗ trợ tương thích ngược cho cả nhánh develop
                .attachmentsCount(attachmentDTOs.size())
                .primaryImageUrl(primaryImageUrl)
                .attachments(attachmentDTOs)
                .build();
    }

    /**
     * Chỉ gắn TreatmentPlanItem cho dịch vụ cận lâm sàng khi ca khám hoặc bệnh nhân
     * đang có WorkflowExecution kích hoạt gắn với hạng mục/giai đoạn phác đồ đó;
     * tránh tự động ghép nhầm vào phác đồ cũ khi chụp X-quang phát sinh ngoài phác đồ.
     */
    private TreatmentPlanItem resolveActivePlanItemForOrder(Visit visit, Long serviceId) {
        if (visit == null || visit.getVisitId() == null || serviceId == null || workflowExecutionRepository == null) {
            return null;
        }
        List<WorkflowExecution> candidates = new ArrayList<>(workflowExecutionRepository.findByVisit_VisitId(visit.getVisitId()));
        if (visit.getPatientProfile() != null && visit.getPatientProfile().getProfileId() != null) {
            for (WorkflowExecution pe : workflowExecutionRepository.findByPatientProfile_ProfileIdOrderByStartedAtDesc(visit.getPatientProfile().getProfileId())) {
                if (candidates.stream().noneMatch(c -> Objects.equals(c.getExecutionId(), pe.getExecutionId()))) {
                    candidates.add(pe);
                }
            }
        }
        for (WorkflowExecution ex : candidates) {
            if (ex == null || (ex.getStatus() != WorkflowExecutionStatus.IN_PROGRESS && ex.getStatus() != WorkflowExecutionStatus.PAUSED)) {
                continue;
            }
            TreatmentPlanItem directItem = ex.getPlanItem();
            if (isOpenPlanItemForService(directItem, serviceId)) {
                return directItem;
            }
            if (ex.getPlanStage() != null && treatmentPlanItemRepository != null) {
                List<TreatmentPlanItem> stageItems = treatmentPlanItemRepository.findByStage_StageId(ex.getPlanStage().getStageId());
                for (TreatmentPlanItem stageItem : stageItems) {
                    if (isOpenPlanItemForService(stageItem, serviceId)) {
                        return stageItem;
                    }
                }
            }
        }
        return null;
    }

    private boolean isOpenPlanItemForService(TreatmentPlanItem item, Long serviceId) {
        if (item == null || item.getService() == null || !Objects.equals(item.getService().getServiceId(), serviceId)) {
            return false;
        }
        if (item.getStatus() == TreatmentPlanItemStatus.COMPLETED || item.getStatus() == TreatmentPlanItemStatus.CANCELLED) {
            return false;
        }
        TreatmentPlan parentPlan = item.getPlan() != null
                ? item.getPlan()
                : (item.getStage() != null ? item.getStage().getPlan() : null);
        if (parentPlan != null && parentPlan.getStatus() == TreatmentPlanStatus.CANCELLED) {
            return false;
        }
        return performedServiceRepository.findByPlanItem_PlanItemId(item.getPlanItemId()).stream()
                .noneMatch(ps -> ps.getStatus() != PerformedServiceStatus.CANCELLED);
    }
}