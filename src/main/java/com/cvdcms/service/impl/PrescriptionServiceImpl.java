package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.PrescriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrescriptionServiceImpl implements PrescriptionService {

    private final PrescriptionRepository prescriptionRepository;
    private final MedicationRepository medicationRepository;
    private final VisitRepository visitRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final PatientProfileRepository patientProfileRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher careEventPublisher;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.EmrRecordRepository emrRecordRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CustomerCareTaskRepository customerCareTaskRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InvoiceRepository invoiceRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InvoiceItemRepository invoiceItemRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DoctorCoverGrantRepository doctorCoverGrantRepository;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    @Transactional(readOnly = true)
    public List<MedicationDTO> getActiveMedications() {
        return medicationRepository.findByIsActiveTrueOrderByDrugNameAsc().stream()
                .map(this::mapToMedicationDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionResponseDTO> getPrescriptionsByVisit(Long visitId) {
        return prescriptionRepository.findByVisit_VisitIdOrderByCreatedAtDesc(visitId).stream()
                .map(this::mapToPrescriptionResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public PrescriptionResponseDTO getPrescriptionById(Long prescriptionId) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc."));
        return mapToPrescriptionResponseDTO(prescription);
    }

    @Override
    @Transactional
    public PrescriptionResponseDTO createPrescription(PrescriptionRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu đơn thuốc không hợp lệ!");
        }
        Visit visit;
        if (request.getVisitId() != null) {
            visit = visitRepository.findById(request.getVisitId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám để kê đơn thuốc!"));
            if (request.getPatientId() != null && visit.getPatientProfile() != null
                    && !visit.getPatientProfile().getProfileId().equals(request.getPatientId())) {
                throw new IllegalArgumentException("Ca khám không thuộc về hồ sơ bệnh nhân được chọn!");
            }
        } else if (request.getPatientId() != null) {
            visit = visitRepository.findFirstByPatientProfile_ProfileIdOrderByCheckInAtDesc(request.getPatientId())
                    .orElseThrow(() -> new IllegalArgumentException("Bệnh nhân chưa có ca khám nào để kê đơn thuốc!"));
        } else {
            throw new IllegalArgumentException("Không tìm thấy ca khám hoặc hồ sơ bệnh nhân để kê đơn thuốc!");
        }

        // Quy tắc BR-EMR-02: Khóa bệnh án lâm sàng bất biến khi chuyển thanh toán, hoàn tất, hủy hoặc EMR khóa
        com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Bệnh án lâm sàng đã hoàn tất và khóa bất biến. Không thể tạo đơn thuốc mới");

        PatientProfile patient = visit.getPatientProfile() != null
                ? visit.getPatientProfile()
                : (request.getPatientId() != null ? patientProfileRepository.findById(request.getPatientId()).orElse(null) : null);
        if (patient == null) {
            throw new IllegalArgumentException("Không tìm thấy hồ sơ bệnh nhân!");
        }

        Branch targetBranch = visit.getBranch() != null ? visit.getBranch() : patient.getPreferredBranch();
        if (targetBranch == null) {
            throw new IllegalArgumentException("Không xác định được chi nhánh của ca khám để kê đơn thuốc!");
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Đơn thuốc phải có ít nhất 1 loại thuốc!");
        }

        StaffProfile doctor;
        if (request.getDoctorId() != null) {
            doctor = staffProfileRepository.findById(request.getDoctorId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin bác sĩ kê đơn!"));
        } else {
            doctor = visit.getPrimaryDoctor();
        }
        if (doctor == null) {
            throw new IllegalArgumentException("Vui lòng chọn bác sĩ kê đơn!");
        }
        validateDoctorForPrescription(doctor, visit, targetBranch);

        validatePatientAllergySafety(patient, request);

        Prescription prescription = new Prescription();
        prescription.setPrescriptionCode("DT" + (System.currentTimeMillis() % 10000000));
        prescription.setVisit(visit);
        prescription.setPatientProfile(patient);
        prescription.setBranch(targetBranch);
        prescription.setDoctor(doctor);
        prescription.setDiagnosisText(
                (request.getDiagnosisText() != null && !request.getDiagnosisText().trim().isEmpty())
                        ? request.getDiagnosisText().trim()
                        : "Khám và điều trị nha khoa"
        );
        prescription.setAdviceNotes(request.getAdviceNotes());

        // Tính ngày tái khám
        if (request.getFollowupDays() != null && request.getFollowupDays() > 0) {
            prescription.setFollowupDate(LocalDate.now().plusDays(request.getFollowupDays()));
        } else if (request.getFollowupDate() != null && !request.getFollowupDate().trim().isEmpty()) {
            try {
                prescription.setFollowupDate(LocalDate.parse(request.getFollowupDate().trim()));
            } catch (Exception e) {
                log.warn("Lỗi parse ngày tái khám: {}", request.getFollowupDate());
            }
        }

        prescription.setCreatedAt(LocalDateTime.now());

        // Lưu danh sách thuốc
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (PrescriptionItemRequestDTO itemDTO : request.getItems()) {
                if (itemDTO.getDrugName() == null || itemDTO.getDrugName().trim().isEmpty()) {
                    continue;
                }

                if (itemDTO.getQuantity() != null && itemDTO.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Số lượng thuốc kê đơn phải lớn hơn 0!");
                }

                PrescriptionItem item = new PrescriptionItem();
                item.setPrescription(prescription);

                Medication med = null;
                if (itemDTO.getMedicationId() != null) {
                    med = medicationRepository.findById(itemDTO.getMedicationId())
                            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thuốc trong danh mục ID: " + itemDTO.getMedicationId()));
                    if (Boolean.FALSE.equals(med.getIsActive())) {
                        throw new IllegalStateException("Thuốc '" + med.getDrugName() + "' đã ngừng sử dụng trong danh mục.");
                    }
                    item.setMedication(med);
                }

                item.setDrugName(itemDTO.getDrugName().trim());
                item.setDosage(
                        (itemDTO.getDosage() != null && !itemDTO.getDosage().trim().isEmpty())
                                ? itemDTO.getDosage().trim()
                                : "Theo chỉ dẫn"
                );
                item.setDurationDays(
                        (itemDTO.getDurationDays() != null && itemDTO.getDurationDays() > 0)
                                ? itemDTO.getDurationDays()
                                : 5
                );
                item.setQuantity(
                        (itemDTO.getQuantity() != null && itemDTO.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                                ? itemDTO.getQuantity()
                                : BigDecimal.ONE
                );
                item.setUnit(
                        (itemDTO.getUnit() != null && !itemDTO.getUnit().trim().isEmpty())
                                ? itemDTO.getUnit().trim()
                                : (med != null && med.getUnit() != null && !med.getUnit().isBlank() ? med.getUnit() : "Viên")
                );
                item.setUsageInstruction(
                        (itemDTO.getUsageInstruction() != null && !itemDTO.getUsageInstruction().trim().isEmpty())
                                ? itemDTO.getUsageInstruction().trim()
                                : (med != null && med.getUsageInstruction() != null && !med.getUsageInstruction().isBlank()
                                        ? med.getUsageInstruction() : "Uống sau khi ăn")
                );

                // Với thuốc trong danh mục (med != null), luôn dùng giá niêm yết Medication.salePrice trên server
                if (itemDTO.getUnitPrice() != null && itemDTO.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Đơn giá thuốc không được âm!");
                }
                BigDecimal unitPrice;
                if (med != null) {
                    unitPrice = med.getSalePrice() != null ? med.getSalePrice() : BigDecimal.ZERO;
                } else if (itemDTO.getUnitPrice() != null && itemDTO.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
                    unitPrice = itemDTO.getUnitPrice();
                } else {
                    unitPrice = getEstimatedPriceForDrug(item.getDrugName());
                }
                item.setUnitPrice(unitPrice);

                prescription.getItems().add(item);
            }
        }

        if (prescription.getItems().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng nhập tên thuốc hợp lệ cho ít nhất 1 loại thuốc trong đơn!");
        }

        Prescription saved = prescriptionRepository.save(prescription);
        log.info("Đã tạo đơn thuốc thành công: ID = {}, Code = {}", saved.getPrescriptionId(), saved.getPrescriptionCode());
        if (careEventPublisher != null && saved.getPrescriptionId() != null) {
            careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.PrescriptionCreatedCareEvent(saved.getPrescriptionId()));
        }
        return mapToPrescriptionResponseDTO(saved);
    }

    private void validatePatientAllergySafety(PatientProfile patient, PrescriptionRequestDTO request) {
        if (patient == null || Boolean.TRUE.equals(request.getConfirmAllergyWarning())) {
            return;
        }
        String rawDetails = patient.getAllergyDetails() != null ? patient.getAllergyDetails().trim() : "";
        String normalized = rawDetails.toLowerCase();
        boolean hasBenignNoneText = normalized.isEmpty()
                || normalized.equals("không")
                || normalized.equals("khong")
                || normalized.equals("không có")
                || normalized.equals("chưa ghi nhận")
                || normalized.equals("none")
                || normalized.equals("no");
        boolean hasAllergyFlag = Boolean.TRUE.equals(patient.getHasAllergies()) || !hasBenignNoneText;
        if (!hasAllergyFlag) {
            return;
        }
        String desc = !hasBenignNoneText ? rawDetails : "Đã đánh dấu có tiền sử dị ứng trong hồ sơ";
        throw new IllegalArgumentException(
                "Cảnh báo dị ứng: Bệnh nhân có tiền sử dị ứng (" + desc + "). Vui lòng kiểm tra kỹ hoạt chất và xác nhận trước khi kê đơn!");
    }

    private void validateDoctorForPrescription(StaffProfile doctor, Visit visit, Branch targetBranch) {
        if (doctor.getStatus() != com.cvdcms.entity.enums.StaffStatus.ACTIVE) {
            throw new IllegalArgumentException("Bác sĩ kê đơn hiện không ở trạng thái hoạt động.");
        }
        if (doctor.getUser() != null && Boolean.FALSE.equals(doctor.getUser().getIsActive())) {
            throw new IllegalArgumentException("Tài khoản bác sĩ kê đơn đã bị khóa.");
        }
        if (visit != null && visit.getPrimaryDoctor() != null
                && doctor.getStaffId().equals(visit.getPrimaryDoctor().getStaffId())) {
            return;
        }
        if (visit != null && doctorCoverGrantRepository != null && doctorCoverGrantRepository
                .existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                        visit.getVisitId(), doctor.getStaffId(), LocalDateTime.now())) {
            return;
        }
        Long branchId = targetBranch != null ? targetBranch.getBranchId() : null;
        if (branchId == null) {
            return;
        }
        boolean hasBranchMetadata = false;
        if (doctor.getUser() != null && doctor.getUser().getBranch() != null) {
            hasBranchMetadata = true;
            if (branchId.equals(doctor.getUser().getBranch().getBranchId())) {
                return;
            }
        }
        if (staffBranchAssignmentRepository != null) {
            List<Long> branchIds = staffBranchAssignmentRepository.findActiveBranchIdsByStaffId(
                    doctor.getStaffId(), com.cvdcms.entity.enums.AssignmentStatus.ACTIVE);
            if (branchIds != null && !branchIds.isEmpty()) {
                hasBranchMetadata = true;
                if (branchIds.contains(branchId)) {
                    return;
                }
            }
        }
        if (hasBranchMetadata) {
            throw new IllegalArgumentException("Bác sĩ kê đơn không thuộc chi nhánh của ca khám.");
        }
    }

    @Override
    @Transactional
    public void deletePrescription(Long prescriptionId) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc."));

        com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                prescription.getVisit(),
                emrRecordRepository,
                "Bệnh án lâm sàng đã khóa bất biến. Không thể xóa đơn thuốc");

        if (prescription.getStatus() == com.cvdcms.entity.enums.PrescriptionStatus.CLINIC_PURCHASE_PARTIAL
                || prescription.getStatus() == com.cvdcms.entity.enums.PrescriptionStatus.CLINIC_PURCHASE_COMPLETED
                || prescription.getStatus() == com.cvdcms.entity.enums.PrescriptionStatus.DISPENSED) {
            throw new IllegalStateException("Đơn thuốc đã phát sinh mua/xuất/cấp phát tại phòng khám, không thể xóa.");
        }
        boolean anyBought = prescription.getItems() != null && prescription.getItems().stream()
                .anyMatch(it -> it.getBoughtQuantity() != null && it.getBoughtQuantity().compareTo(BigDecimal.ZERO) > 0);
        if (anyBought) {
            throw new IllegalStateException("Đơn thuốc đã có dòng thuốc được lập hóa đơn/xuất bán, không thể xóa.");
        }

        assertNotReferencedByActiveInvoices(prescription);
        deleteLinkedCareTasks(prescription);

        prescriptionRepository.delete(prescription);
        log.info("Đã xóa đơn thuốc ID: {}", prescriptionId);
    }

    private void assertNotReferencedByActiveInvoices(Prescription prescription) {
        Long prescriptionId = prescription.getPrescriptionId();
        if (prescriptionId == null) {
            return;
        }
        if (invoiceRepository != null && prescription.getPatientProfile() != null) {
            List<Invoice> activeInvoices = invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(
                    prescription.getPatientProfile().getProfileId(),
                    com.cvdcms.entity.enums.InvoiceStatus.CANCELLED
            );
            if (activeInvoices != null) {
                for (Invoice inv : activeInvoices) {
                    if (inv.getPrescription() != null && prescriptionId.equals(inv.getPrescription().getPrescriptionId())) {
                        throw new IllegalStateException("Đơn thuốc đã được lập hóa đơn, không thể xóa.");
                    }
                    if (invoiceItemRepository != null) {
                        List<InvoiceItem> items = invoiceItemRepository.findByInvoice_InvoiceId(inv.getInvoiceId());
                        if (items != null) {
                            boolean linked = items.stream().anyMatch(ii ->
                                    ii.getPrescriptionItem() != null
                                            && ii.getPrescriptionItem().getPrescription() != null
                                            && prescriptionId.equals(ii.getPrescriptionItem().getPrescription().getPrescriptionId()));
                            if (linked) {
                                throw new IllegalStateException("Đơn thuốc đã có dòng thuốc được lập hóa đơn, không thể xóa.");
                            }
                        }
                    }
                }
            }
        }
        if (entityManager != null) {
            Long directCount = entityManager.createQuery(
                            "SELECT COUNT(i) FROM Invoice i WHERE i.prescription.prescriptionId = :pid AND i.status <> :cancelled",
                            Long.class)
                    .setParameter("pid", prescriptionId)
                    .setParameter("cancelled", com.cvdcms.entity.enums.InvoiceStatus.CANCELLED)
                    .getSingleResult();
            if (directCount != null && directCount > 0) {
                throw new IllegalStateException("Đơn thuốc đã được lập hóa đơn, không thể xóa.");
            }
            Long itemCount = entityManager.createQuery(
                            "SELECT COUNT(ii) FROM InvoiceItem ii WHERE ii.prescriptionItem.prescription.prescriptionId = :pid AND ii.invoice.status <> :cancelled",
                            Long.class)
                    .setParameter("pid", prescriptionId)
                    .setParameter("cancelled", com.cvdcms.entity.enums.InvoiceStatus.CANCELLED)
                    .getSingleResult();
            if (itemCount != null && itemCount > 0) {
                throw new IllegalStateException("Đơn thuốc đã có dòng thuốc được lập hóa đơn, không thể xóa.");
            }
        }
    }

    private void deleteLinkedCareTasks(Prescription prescription) {
        Long prescriptionId = prescription.getPrescriptionId();
        if (prescriptionId == null) {
            return;
        }
        if (customerCareTaskRepository != null && prescription.getPatientProfile() != null) {
            List<CustomerCareTask> patientTasks = customerCareTaskRepository.findByPatientProfileIdWithDetails(
                    prescription.getPatientProfile().getProfileId());
            if (patientTasks != null && !patientTasks.isEmpty()) {
                List<CustomerCareTask> toDelete = patientTasks.stream()
                        .filter(t -> t.getPrescription() != null && prescriptionId.equals(t.getPrescription().getPrescriptionId()))
                        .collect(Collectors.toList());
                if (!toDelete.isEmpty()) {
                    customerCareTaskRepository.deleteAll(toDelete);
                    customerCareTaskRepository.flush();
                }
            }
        }
        if (entityManager != null) {
            entityManager.createQuery("DELETE FROM CustomerCareTask t WHERE t.prescription.prescriptionId = :pid")
                    .setParameter("pid", prescriptionId)
                    .executeUpdate();
        }
    }

    private MedicationDTO mapToMedicationDTO(Medication entity) {
        return MedicationDTO.builder()
                .medicationId(entity.getMedicationId())
                .drugCode(entity.getDrugCode())
                .drugName(entity.getDrugName())
                .activeIngredient(entity.getActiveIngredient())
                .unit(entity.getUnit())
                .usageInstruction(entity.getUsageInstruction())
                .isActive(entity.getIsActive())
                .salePrice(entity.getSalePrice())
                .materialId(entity.getMaterial() != null ? entity.getMaterial().getMaterialId() : null)
                .build();
    }

    private PrescriptionResponseDTO mapToPrescriptionResponseDTO(Prescription p) {
        PatientProfile patient = p.getPatientProfile();
        StaffProfile doctor = p.getDoctor();
        Branch branch = p.getBranch();
        Visit visit = p.getVisit();

        Integer age = null;
        String dobStr = null;
        if (patient != null && patient.getDob() != null) {
            age = Period.between(patient.getDob(), LocalDate.now()).getYears();
            dobStr = patient.getDob().format(DATE_FORMATTER);
        }

        List<PrescriptionItemResponseDTO> itemDTOs = new ArrayList<>();
        BigDecimal totalPrescAmount = BigDecimal.ZERO;
        if (p.getItems() != null) {
            for (PrescriptionItem it : p.getItems()) {
                BigDecimal uPrice = (it.getUnitPrice() != null && it.getUnitPrice().compareTo(BigDecimal.ZERO) > 0)
                        ? it.getUnitPrice()
                        : getEstimatedPriceForDrug(it.getDrugName());
                BigDecimal qty = (it.getQuantity() != null && it.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                        ? it.getQuantity()
                        : BigDecimal.ONE;
                BigDecimal fPrice = uPrice.multiply(qty);
                totalPrescAmount = totalPrescAmount.add(fPrice);

                BigDecimal bought = it.getBoughtQuantity() != null ? it.getBoughtQuantity() : BigDecimal.ZERO;
                BigDecimal rem = it.getRemainingQuantity();

                itemDTOs.add(PrescriptionItemResponseDTO.builder()
                        .itemId(it.getItemId())
                        .medicationId(it.getMedication() != null ? it.getMedication().getMedicationId() : null)
                        .drugName(it.getDrugName())
                        .dosage(it.getDosage())
                        .durationDays(it.getDurationDays())
                        .quantity(it.getQuantity())
                        .boughtQuantity(bought)
                        .remainingQuantity(rem)
                        .unit(it.getUnit())
                        .usageInstruction(it.getUsageInstruction())
                        .unitPrice(uPrice)
                        .finalPrice(fPrice)
                        .build());
            }
        }

        String statusName = p.getStatus() != null ? p.getStatus().name() : "PENDING";
        String statusDisplay = switch (statusName) {
            case "EXTERNAL_PURCHASE" -> "Tự mua ngoài";
            case "CLINIC_PURCHASE_PARTIAL" -> "Đã mua 1 phần";
            case "CLINIC_PURCHASE_COMPLETED" -> "Đã mua đủ tại PK";
            case "CANCELLED" -> "Đã hủy";
            default -> "Chờ tiếp nhận";
        };

        return PrescriptionResponseDTO.builder()
                .prescriptionId(p.getPrescriptionId())
                .prescriptionCode(p.getPrescriptionCode())
                .visitId(visit != null ? visit.getVisitId() : null)
                .visitCode(visit != null ? visit.getVisitCode() : null)
                .patientId(patient != null ? patient.getProfileId() : null)
                .patientCode(patient != null ? patient.getPatientCode() : null)
                .patientName(patient != null ? patient.getFullName() : null)
                .gender(patient != null ? patient.getGender() : null)
                .dob(dobStr)
                .age(age)
                .phoneNumber(patient != null ? patient.getPhoneNumber() : null)
                .address(patient != null ? patient.getAddress() : null)
                .doctorId(doctor != null ? doctor.getStaffId() : null)
                .doctorName(doctor != null ? doctor.getFullName() : null)
                .doctorTitle(doctor != null ? doctor.getPositionTitle() : "Bác sĩ điều trị")
                .branchName(branch != null ? branch.getBranchName() : "Nha khoa Quốc tế DCMS")
                .branchAddress(branch != null ? branch.getAddress() : "Hà Nội / TP.HCM")
                .branchPhone(branch != null ? branch.getPhone() : "1900 1234")
                .diagnosisText(p.getDiagnosisText())
                .adviceNotes(p.getAdviceNotes())
                .followupDate(p.getFollowupDate() != null ? p.getFollowupDate().format(DATE_FORMATTER) : null)
                .createdAt(p.getCreatedAt() != null ? p.getCreatedAt().format(DATE_TIME_FORMATTER) : null)
                .totalAmount(totalPrescAmount)
                .status(statusName)
                .statusDisplay(statusDisplay)
                .purchaseChoice(p.getPurchaseChoice() != null ? p.getPurchaseChoice().name() : null)
                .choiceNote(p.getChoiceNote())
                .handledByName(p.getHandledBy() != null ? p.getHandledBy().getFullName() : null)
                .handledAt(p.getHandledAt() != null ? p.getHandledAt().format(DATE_TIME_FORMATTER) : null)
                .items(itemDTOs)
                .build();
    }

    private BigDecimal getEstimatedPriceForDrug(String drugName) {
        if (drugName == null || drugName.trim().isEmpty()) {
            return BigDecimal.valueOf(5000);
        }
        String lower = drugName.toLowerCase();
        if (lower.contains("augmentin") || lower.contains("amoxicillin clavulanate")) {
            return BigDecimal.valueOf(22000);
        } else if (lower.contains("kin") || lower.contains("súc miệng") || lower.contains("suc mieng")) {
            return BigDecimal.valueOf(130000);
        } else if (lower.contains("ibuprofen")) {
            return BigDecimal.valueOf(3500);
        } else if (lower.contains("alphachymotrypsin") || lower.contains("chymo")) {
            return BigDecimal.valueOf(3500);
        } else if (lower.contains("paracetamol") || lower.contains("panadol") || lower.contains("efferalgan")) {
            return BigDecimal.valueOf(2500);
        } else if (lower.contains("rodogyl") || lower.contains("spiramycin")) {
            return BigDecimal.valueOf(7000);
        } else if (lower.contains("amoxicillin")) {
            return BigDecimal.valueOf(3000);
        } else if (lower.contains("cefuroxim") || lower.contains("zinnat")) {
            return BigDecimal.valueOf(18000);
        } else if (lower.contains("prednisolon") || lower.contains("medrol")) {
            return BigDecimal.valueOf(4000);
        } else if (lower.contains("gaviscon")) {
            return BigDecimal.valueOf(12000);
        }
        return BigDecimal.valueOf(5000);
    }
}
