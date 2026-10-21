package com.cvdcms.service.impl;

import com.cvdcms.DTO.PerformedServiceRequestDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.PerformedServiceStatus;
import com.cvdcms.entity.enums.ServiceStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.PerformedServiceRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.service.PerformedServiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformedServiceServiceImpl implements PerformedServiceService {

    private final PerformedServiceRepository performedServiceRepository;
    private final ServiceRepository serviceRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final VisitRepository visitRepository;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Override
    @Transactional(readOnly = true)
    public List<ServiceDTO> getAllActiveServices() {
        return serviceRepository.findByStatusOrderByCategory_DisplayOrderAscServiceNameAsc(ServiceStatus.ACTIVE)
                .stream()
                .map(this::mapToServiceDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffProfile> getAvailableDoctors() {
        return staffProfileRepository.findByStatus(StaffStatus.ACTIVE).stream()
                .filter(s -> s.getStaffCode() != null && s.getStaffCode().startsWith("BS")
                        || (s.getPositionTitle() != null && s.getPositionTitle().toLowerCase().contains("bác sĩ"))
                        || (s.getDepartment() != null && s.getDepartment().toLowerCase().contains("bác sĩ")))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffProfile> getAvailableAssistants() {
        return staffProfileRepository.findByStatus(StaffStatus.ACTIVE).stream()
                .filter(s -> (s.getStaffCode() != null && s.getStaffCode().startsWith("PT"))
                        || (s.getPositionTitle() != null && (s.getPositionTitle().toLowerCase().contains("trợ thủ") || s.getPositionTitle().toLowerCase().contains("phụ tá") || s.getPositionTitle().toLowerCase().contains("điều dưỡng")))
                        || (s.getDepartment() != null && s.getDepartment().toLowerCase().contains("điều dưỡng")))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PerformedServiceResponseDTO> getPerformedServicesByVisitId(Long visitId) {
        return performedServiceRepository.findByVisitIdWithDetails(visitId).stream()
                .map(this::mapToResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public PerformedServiceResponseDTO createPerformedService(PerformedServiceRequestDTO requestDTO) {
        log.info("Creating performed service for visitId={}, serviceId={}", requestDTO.getVisitId(), requestDTO.getServiceId());

        Visit visit = visitRepository.findById(requestDTO.getVisitId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám với ID: " + requestDTO.getVisitId()));

        com.cvdcms.entity.Service service = serviceRepository.findById(requestDTO.getServiceId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ với ID: " + requestDTO.getServiceId()));

        StaffProfile doctor = staffProfileRepository.findById(requestDTO.getDoctorId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bác sĩ với ID: " + requestDTO.getDoctorId()));

        StaffProfile assistant = null;
        if (requestDTO.getAssistantId() != null) {
            assistant = staffProfileRepository.findById(requestDTO.getAssistantId()).orElse(null);
        }

        BigDecimal qty = requestDTO.getQuantity() != null && requestDTO.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? requestDTO.getQuantity() : BigDecimal.ONE;

        BigDecimal unitPrice = requestDTO.getUnitPrice() != null && requestDTO.getUnitPrice().compareTo(BigDecimal.ZERO) >= 0
                ? requestDTO.getUnitPrice() : service.getBasePrice();

        BigDecimal discount = requestDTO.getDiscountAmount() != null && requestDTO.getDiscountAmount().compareTo(BigDecimal.ZERO) >= 0
                ? requestDTO.getDiscountAmount() : BigDecimal.ZERO;

        BigDecimal calculatedFinal = unitPrice.multiply(qty).subtract(discount);
        if (calculatedFinal.compareTo(BigDecimal.ZERO) < 0) {
            calculatedFinal = BigDecimal.ZERO;
        }

        BigDecimal finalPrice = requestDTO.getFinalPrice() != null && requestDTO.getFinalPrice().compareTo(BigDecimal.ZERO) >= 0
                ? requestDTO.getFinalPrice() : calculatedFinal;

        PerformedService ps = new PerformedService();
        ps.setVisit(visit);
        ps.setPatientProfile(visit.getPatientProfile());
        ps.setBranch(visit.getBranch());
        ps.setService(service);
        ps.setToothNumbers(requestDTO.getToothNumbers());
        ps.setQuantity(qty);
        ps.setUnitPrice(unitPrice);
        ps.setDiscountAmount(discount);
        ps.setFinalPrice(finalPrice);
        ps.setDoctor(doctor);
        ps.setAssistant(assistant);
        ps.setCompletedAt(LocalDateTime.now());
        ps.setStatus(PerformedServiceStatus.COMPLETED); // Ghi nhận hoàn thành để bàn giao Người 4 lập hóa đơn
        ps.setClinicalNotes(requestDTO.getClinicalNotes());

        PerformedService saved = performedServiceRepository.save(ps);
        log.info("Performed service created successfully with id={}", saved.getPerformedId());

        return mapToResponseDTO(saved);
    }

    @Override
    @Transactional
    public void cancelPerformedService(Long performedId) {
        log.info("Cancelling/Deleting performed service with id={}", performedId);
        PerformedService ps = performedServiceRepository.findById(performedId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ thực hiện với ID: " + performedId));

        // Xóa hoàn toàn hoặc cập nhật status sang CANCELLED
        performedServiceRepository.delete(ps);
    }

    private ServiceDTO mapToServiceDTO(com.cvdcms.entity.Service s) {
        return ServiceDTO.builder()
                .serviceId(s.getServiceId())
                .categoryId(s.getCategory() != null ? s.getCategory().getCategoryId() : null)
                .categoryName(s.getCategory() != null ? s.getCategory().getCategoryName() : "Khác")
                .serviceCode(s.getServiceCode())
                .serviceName(s.getServiceName())
                .serviceType(s.getServiceType() != null ? s.getServiceType().name() : "PROCEDURE")
                .unit(s.getUnit())
                .basePrice(s.getBasePrice())
                .requiresTooth(s.getRequiresTooth())
                .isParaclinical(s.getIsParaclinical())
                .description(s.getDescription())
                .build();
    }

    private PerformedServiceResponseDTO mapToResponseDTO(PerformedService ps) {
        String statusDisplay = ps.getStatus() == PerformedServiceStatus.COMPLETED ? "Đã hoàn thành" : "Đã hủy";
        String timeStr = ps.getCompletedAt() != null ? ps.getCompletedAt().format(FORMATTER) : "";

        return PerformedServiceResponseDTO.builder()
                .performedId(ps.getPerformedId())
                .visitId(ps.getVisit() != null ? ps.getVisit().getVisitId() : null)
                .profileId(ps.getPatientProfile() != null ? ps.getPatientProfile().getProfileId() : null)
                .patientName(ps.getPatientProfile() != null ? ps.getPatientProfile().getFullName() : "")
                .serviceId(ps.getService() != null ? ps.getService().getServiceId() : null)
                .serviceCode(ps.getService() != null ? ps.getService().getServiceCode() : "")
                .serviceName(ps.getService() != null ? ps.getService().getServiceName() : "")
                .categoryName(ps.getService() != null && ps.getService().getCategory() != null ? ps.getService().getCategory().getCategoryName() : "")
                .unit(ps.getService() != null ? ps.getService().getUnit() : "Lần")
                .toothNumbers(ps.getToothNumbers())
                .quantity(ps.getQuantity())
                .unitPrice(ps.getUnitPrice())
                .discountAmount(ps.getDiscountAmount())
                .finalPrice(ps.getFinalPrice())
                .doctorId(ps.getDoctor() != null ? ps.getDoctor().getStaffId() : null)
                .doctorName(ps.getDoctor() != null ? ps.getDoctor().getFullName() : "")
                .doctorCode(ps.getDoctor() != null ? ps.getDoctor().getStaffCode() : "")
                .assistantId(ps.getAssistant() != null ? ps.getAssistant().getStaffId() : null)
                .assistantName(ps.getAssistant() != null ? ps.getAssistant().getFullName() : "")
                .completedAt(timeStr)
                .status(ps.getStatus() != null ? ps.getStatus().name() : "COMPLETED")
                .statusDisplay(statusDisplay)
                .clinicalNotes(ps.getClinicalNotes())
                .build();
    }
}
