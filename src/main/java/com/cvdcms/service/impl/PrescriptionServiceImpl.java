package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.BatchStatus;
import com.cvdcms.entity.enums.PrescriptionStatus;
import com.cvdcms.entity.enums.StockIssueType;
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
import java.util.*;
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
    private final MaterialRepository materialRepository;
    private final BranchInventoryRepository branchInventoryRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final StockIssueRepository stockIssueRepository;
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
        return getActiveMedicationsForBranch(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Long resolveBranchIdForVisit(Long visitId) {
        if (visitId == null) {
            return null;
        }
        return visitRepository.findById(visitId)
                .map(v -> {
                    if (v.getBranch() != null) {
                        return v.getBranch().getBranchId();
                    }
                    if (v.getPatientProfile() != null && v.getPatientProfile().getPreferredBranch() != null) {
                        return v.getPatientProfile().getPreferredBranch().getBranchId();
                    }
                    return null;
                })
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MedicationDTO> getActiveMedicationsForBranch(Long branchId) {
        List<Medication> meds = medicationRepository.findByIsActiveTrueOrderByDrugNameAsc();
        if (meds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Material> medIdToMaterial = new HashMap<>();
        Set<Long> materialIds = new LinkedHashSet<>();
        for (Medication med : meds) {
            Material mat = resolveLinkedMaterial(med);
            if (mat != null && mat.getMaterialId() != null) {
                medIdToMaterial.put(med.getMedicationId(), mat);
                materialIds.add(mat.getMaterialId());
            }
        }

        Map<Long, BigDecimal> availableByMat = new HashMap<>();
        Map<Long, LocalDate> nearestExpiryByMat = new HashMap<>();
        Map<Long, String> nearestBatchByMat = new HashMap<>();
        Map<Long, BigDecimal> expiredByMat = new HashMap<>();

        if (branchId != null && !materialIds.isEmpty()) {
            LocalDate today = LocalDate.now();
            List<MaterialBatch> activeBatches = materialBatchRepository
                    .findActiveAvailableBatchesByBranchAndMaterialIds(branchId, materialIds, today);
            for (MaterialBatch b : activeBatches) {
                Long matId = b.getMaterial().getMaterialId();
                BigDecimal qty = b.getQuantityRemaining() != null ? b.getQuantityRemaining() : BigDecimal.ZERO;
                availableByMat.merge(matId, qty, BigDecimal::add);
                nearestExpiryByMat.putIfAbsent(matId, b.getExpiryDate());
                nearestBatchByMat.putIfAbsent(matId, b.getBatchNumber());
            }
            List<Object[]> expiredRows = materialBatchRepository
                    .sumExpiredStockByBranchAndMaterialIds(branchId, materialIds, today);
            for (Object[] row : expiredRows) {
                if (row != null && row.length >= 2 && row[0] != null) {
                    Long matId = ((Number) row[0]).longValue();
                    BigDecimal expQty = row[1] instanceof BigDecimal bd ? bd : new BigDecimal(row[1].toString());
                    expiredByMat.put(matId, expQty);
                }
            }
        }

        List<MedicationDTO> result = new ArrayList<>(meds.size());
        for (Medication med : meds) {
            Material mat = medIdToMaterial.get(med.getMedicationId());
            Long matId = mat != null ? mat.getMaterialId() : null;
            BigDecimal minThreshold = (mat != null && mat.getMinStockThreshold() != null)
                    ? mat.getMinStockThreshold()
                    : new BigDecimal("10.000");

            BigDecimal available = matId != null ? availableByMat.getOrDefault(matId, BigDecimal.ZERO) : BigDecimal.ZERO;
            BigDecimal expired = matId != null ? expiredByMat.getOrDefault(matId, BigDecimal.ZERO) : BigDecimal.ZERO;
            LocalDate nearestExpiry = matId != null ? nearestExpiryByMat.get(matId) : null;
            String nearestBatch = matId != null ? nearestBatchByMat.get(matId) : null;

            String stockStatus;
            if (available.compareTo(BigDecimal.ZERO) > 0) {
                stockStatus = available.compareTo(minThreshold) <= 0 ? "LOW_STOCK" : "IN_STOCK";
            } else if (expired.compareTo(BigDecimal.ZERO) > 0) {
                stockStatus = "EXPIRED_ONLY";
            } else {
                stockStatus = "OUT_OF_STOCK";
            }

            result.add(MedicationDTO.builder()
                    .medicationId(med.getMedicationId())
                    .drugCode(med.getDrugCode())
                    .drugName(med.getDrugName())
                    .activeIngredient(med.getActiveIngredient())
                    .unit(med.getUnit())
                    .usageInstruction(med.getUsageInstruction())
                    .isActive(med.getIsActive())
                    .salePrice(med.getSalePrice())
                    .materialId(matId)
                    .availableStock(available)
                    .expiredStock(expired)
                    .minStockThreshold(minThreshold)
                    .nearestExpiryDate(nearestExpiry)
                    .nearestBatchNumber(nearestBatch)
                    .stockStatus(stockStatus)
                    .build());
        }
        return result;
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
    @Transactional(rollbackFor = Exception.class)
    public PrescriptionResponseDTO createPrescription(PrescriptionRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu đơn thuốc không hợp lệ!");
        }

        // Chống gửi lặp / bấm lưu nhiều lần (idempotency)
        String normalizedRequestId = (request.getClientRequestId() != null && !request.getClientRequestId().isBlank())
                ? request.getClientRequestId().trim()
                : null;
        if (normalizedRequestId != null) {
            Optional<Prescription> existing = prescriptionRepository.findByClientRequestId(normalizedRequestId);
            if (existing.isPresent()) {
                log.info("Phát hiện yêu cầu kê đơn trùng lặp clientRequestId={} → trả về đơn đã lưu {}",
                        normalizedRequestId, existing.get().getPrescriptionCode());
                return mapToPrescriptionResponseDTO(existing.get());
            }
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
        prescription.setPrescriptionCode(generateUniquePrescriptionCode());
        prescription.setClientRequestId(normalizedRequestId);
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
                throw new IllegalArgumentException("Định dạng ngày tái khám không hợp lệ (cần yyyy-MM-dd).");
            }
        }

        prescription.setCreatedAt(LocalDateTime.now());

        // Xây dựng dòng thuốc & gom tổng số lượng cần trừ kho theo từng materialId (sắp xếp tăng dần để chống deadlock)
        TreeMap<Long, BigDecimal> requestedQtyByMaterial = new TreeMap<>();
        Map<Long, Material> materialById = new HashMap<>();
        Map<Long, String> drugNameByMaterial = new HashMap<>();
        buildAndValidatePrescriptionItems(prescription, request.getItems(), requestedQtyByMaterial, materialById, drugNameByMaterial);

        if (prescription.getItems().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất 1 loại thuốc hợp lệ trong đơn!");
        }

        // Lưu đơn thuốc trước để lấy prescription_id gắn vào chứng từ xuất kho (cùng transaction, rollback toàn bộ nếu thiếu kho)
        prescription.setInventoryDeducted(true);
        Prescription saved = prescriptionRepository.saveAndFlush(prescription);

        // Kiểm tra tồn kho thực tế (chỉ tính lô ACTIVE còn hạn của đúng chi nhánh) & xuất kho theo FEFO
        StockIssue issue = deductInventoryFefoForPrescription(
                saved,
                targetBranch,
                doctor,
                requestedQtyByMaterial,
                materialById,
                drugNameByMaterial,
                "Xuất thuốc theo đơn " + saved.getPrescriptionCode() + " - BN: " + patient.getFullName(),
                "BS: " + doctor.getFullName() + " | Ca khám: " + (visit.getVisitCode() != null ? visit.getVisitCode() : visit.getVisitId())
        );

        log.info("Đã tạo đơn thuốc {} (ID={}) và xuất kho FEFO mã phiếu {} tại chi nhánh {}",
                saved.getPrescriptionCode(), saved.getPrescriptionId(), issue.getIssueCode(), targetBranch.getBranchName());
        if (careEventPublisher != null && saved.getPrescriptionId() != null) {
            careEventPublisher.publishEvent(new com.cvdcms.event.CustomerCareDomainEvents.PrescriptionCreatedCareEvent(saved.getPrescriptionId()));
        }
        return mapToPrescriptionResponseDTO(saved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrescriptionResponseDTO updatePrescription(Long prescriptionId, PrescriptionRequestDTO request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Đơn thuốc cập nhật phải có ít nhất 1 loại thuốc!");
        }
        Prescription prescription = prescriptionRepository.findByIdForUpdate(prescriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc."));

        assertPrescriptionMutableForEditOrCancel(prescription, "sửa");

        Branch targetBranch = prescription.getBranch();
        StaffProfile doctor = prescription.getDoctor();
        if (targetBranch == null) {
            throw new IllegalStateException("Không xác định được chi nhánh của đơn thuốc.");
        }

        // Tính tổng số lượng cũ theo materialId
        Map<Long, BigDecimal> oldQtyByMaterial = new HashMap<>();
        if (prescription.getItems() != null) {
            for (PrescriptionItem oldItem : prescription.getItems()) {
                Material mat = resolveLinkedMaterial(oldItem.getMedication());
                if (mat != null && mat.getMaterialId() != null && oldItem.getQuantity() != null) {
                    oldQtyByMaterial.merge(mat.getMaterialId(), oldItem.getQuantity(), BigDecimal::add);
                }
            }
        }

        // Validate & gom tổng số lượng mới theo materialId
        Prescription tempHolder = new Prescription();
        TreeMap<Long, BigDecimal> newQtyByMaterial = new TreeMap<>();
        Map<Long, Material> materialById = new HashMap<>();
        Map<Long, String> drugNameByMaterial = new HashMap<>();
        buildAndValidatePrescriptionItems(tempHolder, request.getItems(), newQtyByMaterial, materialById, drugNameByMaterial);

        if (tempHolder.getItems().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất 1 loại thuốc hợp lệ trong đơn!");
        }

        // Tính chênh lệch tăng/giảm tồn kho theo thứ tự materialId tăng dần
        if (prescription.isInventoryDeducted()) {
            TreeSet<Long> allMatIds = new TreeSet<>();
            allMatIds.addAll(oldQtyByMaterial.keySet());
            allMatIds.addAll(newQtyByMaterial.keySet());

            TreeMap<Long, BigDecimal> toDeduct = new TreeMap<>();
            TreeMap<Long, BigDecimal> toReturn = new TreeMap<>();
            for (Long matId : allMatIds) {
                BigDecimal oldQ = oldQtyByMaterial.getOrDefault(matId, BigDecimal.ZERO);
                BigDecimal newQ = newQtyByMaterial.getOrDefault(matId, BigDecimal.ZERO);
                int cmp = newQ.compareTo(oldQ);
                if (cmp > 0) {
                    toDeduct.put(matId, newQ.subtract(oldQ));
                } else if (cmp < 0) {
                    toReturn.put(matId, oldQ.subtract(newQ));
                }
            }

            if (!toReturn.isEmpty()) {
                returnPartialStockForPrescription(
                        prescription,
                        targetBranch,
                        doctor,
                        toReturn,
                        "Hoàn kho điều chỉnh giảm khi sửa đơn " + prescription.getPrescriptionCode()
                );
            }
            if (!toDeduct.isEmpty()) {
                deductInventoryFefoForPrescription(
                        prescription,
                        targetBranch,
                        doctor,
                        toDeduct,
                        materialById,
                        drugNameByMaterial,
                        "Xuất bổ sung khi sửa đơn " + prescription.getPrescriptionCode(),
                        "BS: " + (doctor != null ? doctor.getFullName() : "") + " | Điều chỉnh tăng số lượng đơn thuốc"
                );
            }
        }

        // Cập nhật thông tin đơn thuốc
        if (request.getDiagnosisText() != null && !request.getDiagnosisText().trim().isEmpty()) {
            prescription.setDiagnosisText(request.getDiagnosisText().trim());
        }
        if (request.getAdviceNotes() != null) {
            prescription.setAdviceNotes(request.getAdviceNotes());
        }
        if (request.getFollowupDays() != null && request.getFollowupDays() > 0) {
            prescription.setFollowupDate(LocalDate.now().plusDays(request.getFollowupDays()));
        } else if (request.getFollowupDate() != null && !request.getFollowupDate().trim().isEmpty()) {
            try {
                prescription.setFollowupDate(LocalDate.parse(request.getFollowupDate().trim()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Định dạng ngày tái khám không hợp lệ (cần yyyy-MM-dd).");
            }
        }

        prescription.getItems().clear();
        for (PrescriptionItem newItem : tempHolder.getItems()) {
            newItem.setPrescription(prescription);
            prescription.getItems().add(newItem);
        }

        Prescription saved = prescriptionRepository.saveAndFlush(prescription);
        log.info("Đã cập nhật đơn thuốc {} (ID={}) và đồng bộ chênh lệch kho.", saved.getPrescriptionCode(), saved.getPrescriptionId());
        return mapToPrescriptionResponseDTO(saved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrescriptionResponseDTO cancelPrescription(Long prescriptionId, String reason) {
        Prescription prescription = prescriptionRepository.findByIdForUpdate(prescriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc."));

        if (prescription.getStatus() == PrescriptionStatus.CANCELLED) {
            throw new IllegalStateException("Đơn thuốc này đã ở trạng thái Đã hủy.");
        }

        assertPrescriptionMutableForEditOrCancel(prescription, "hủy");

        String cleanReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Bác sĩ hủy đơn thuốc";
        if (prescription.isInventoryDeducted()) {
            restoreAllDeductedStockForPrescription(
                    prescription,
                    "Hoàn kho do hủy đơn thuốc " + prescription.getPrescriptionCode() + " (" + cleanReason + ")"
            );
            prescription.setInventoryDeducted(false);
        }

        prescription.setStatus(PrescriptionStatus.CANCELLED);
        prescription.setChoiceNote(cleanReason);
        prescription.setHandledAt(LocalDateTime.now());
        Prescription saved = prescriptionRepository.saveAndFlush(prescription);
        log.info("Đã hủy đơn thuốc {} (ID={}) và hoàn kho hợp lệ.", saved.getPrescriptionCode(), saved.getPrescriptionId());
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
    @Transactional(rollbackFor = Exception.class)
    public void deletePrescription(Long prescriptionId) {
        Prescription prescription = prescriptionRepository.findByIdForUpdate(prescriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc."));

        assertPrescriptionMutableForEditOrCancel(prescription, "xóa");

        if (prescription.isInventoryDeducted()) {
            restoreAllDeductedStockForPrescription(
                    prescription,
                    "Hoàn kho do xóa đơn thuốc " + prescription.getPrescriptionCode()
            );
            prescription.setInventoryDeducted(false);
        }

        // Giữ nguyên lịch sử xuất/hoàn kho (chỉ gỡ FK prescription_id, giữ lại prescription_code snapshot)
        List<StockIssue> linkedIssues = stockIssueRepository.findByPrescriptionIdWithItemsOrderByIssueIdAsc(prescriptionId);
        for (StockIssue si : linkedIssues) {
            si.setPrescription(null);
            stockIssueRepository.save(si);
        }
        stockIssueRepository.flush();

        deleteLinkedCareTasks(prescription);

        prescriptionRepository.delete(prescription);
        log.info("Đã xóa đơn thuốc ID: {} (lịch sử kho được bảo toàn).", prescriptionId);
    }

    private void assertPrescriptionMutableForEditOrCancel(Prescription prescription, String actionVerb) {
        com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                prescription.getVisit(),
                emrRecordRepository,
                "Bệnh án lâm sàng đã khóa bất biến. Không thể " + actionVerb + " đơn thuốc");

        if (prescription.getStatus() == PrescriptionStatus.CLINIC_PURCHASE_PARTIAL
                || prescription.getStatus() == PrescriptionStatus.CLINIC_PURCHASE_COMPLETED
                || prescription.getStatus() == PrescriptionStatus.DISPENSED) {
            throw new IllegalStateException("Đơn thuốc đã phát sinh mua/xuất/cấp phát tại phòng khám, không thể " + actionVerb + ".");
        }
        boolean anyBought = prescription.getItems() != null && prescription.getItems().stream()
                .anyMatch(it -> it.getBoughtQuantity() != null && it.getBoughtQuantity().compareTo(BigDecimal.ZERO) > 0);
        if (anyBought) {
            throw new IllegalStateException("Đơn thuốc đã có dòng thuốc được lập hóa đơn/xuất bán, không thể " + actionVerb + ".");
        }

        assertNotReferencedByActiveInvoices(prescription);
    }

    private Material resolveLinkedMaterial(Medication med) {
        if (med == null) {
            return null;
        }
        if (med.getMaterial() != null) {
            return med.getMaterial();
        }
        if (med.getMedicationId() != null) {
            return materialRepository.findByMedication_MedicationId(med.getMedicationId()).orElse(null);
        }
        return null;
    }

    private String generateUniquePrescriptionCode() {
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        String randPart = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "DT" + datePart + "-" + randPart;
    }

    private String generateUniqueIssueCode() {
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        String randPart = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "PX" + datePart + "-" + randPart;
    }

    private void buildAndValidatePrescriptionItems(
            Prescription prescription,
            List<PrescriptionItemRequestDTO> itemDTOs,
            TreeMap<Long, BigDecimal> requestedQtyByMaterial,
            Map<Long, Material> materialById,
            Map<Long, String> drugNameByMaterial) {
        for (PrescriptionItemRequestDTO itemDTO : itemDTOs) {
            if (itemDTO == null) {
                continue;
            }
            if (itemDTO.getMedicationId() == null && (itemDTO.getDrugName() == null || itemDTO.getDrugName().trim().isEmpty())) {
                continue;
            }

            if (itemDTO.getQuantity() == null || itemDTO.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số lượng thuốc kê đơn phải lớn hơn 0!");
            }

            if (itemDTO.getMedicationId() == null) {
                throw new IllegalArgumentException(
                        "Thuốc '" + (itemDTO.getDrugName() != null ? itemDTO.getDrugName().trim() : "") +
                                "' chưa được chọn từ danh mục thuốc liên kết kho. Vui lòng chọn thuốc trong danh mục.");
            }

            Medication med = medicationRepository.findById(itemDTO.getMedicationId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thuốc trong danh mục ID: " + itemDTO.getMedicationId()));
            if (Boolean.FALSE.equals(med.getIsActive())) {
                throw new IllegalStateException("Thuốc '" + med.getDrugName() + "' đã ngừng sử dụng trong danh mục.");
            }

            Material mat = resolveLinkedMaterial(med);
            if (mat == null || mat.getMaterialId() == null) {
                throw new IllegalStateException("Thuốc '" + med.getDrugName() + "' chưa được liên kết với kho vật tư. Không thể kê đơn trừ kho.");
            }

            PrescriptionItem item = new PrescriptionItem();
            item.setPrescription(prescription);
            item.setMedication(med);
            item.setDrugName(
                    (itemDTO.getDrugName() != null && !itemDTO.getDrugName().trim().isEmpty())
                            ? itemDTO.getDrugName().trim()
                            : med.getDrugName()
            );
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
            item.setQuantity(itemDTO.getQuantity());
            item.setUnit(
                    (itemDTO.getUnit() != null && !itemDTO.getUnit().trim().isEmpty())
                            ? itemDTO.getUnit().trim()
                            : (med.getUnit() != null && !med.getUnit().isBlank() ? med.getUnit() : "Viên")
            );
            item.setUsageInstruction(
                    (itemDTO.getUsageInstruction() != null && !itemDTO.getUsageInstruction().trim().isEmpty())
                            ? itemDTO.getUsageInstruction().trim()
                            : (med.getUsageInstruction() != null && !med.getUsageInstruction().isBlank()
                                    ? med.getUsageInstruction() : "Uống sau khi ăn")
            );

            if (itemDTO.getUnitPrice() != null && itemDTO.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Đơn giá thuốc không được âm!");
            }
            BigDecimal unitPrice = med.getSalePrice() != null ? med.getSalePrice() : BigDecimal.ZERO;
            item.setUnitPrice(unitPrice);

            prescription.getItems().add(item);

            Long matId = mat.getMaterialId();
            requestedQtyByMaterial.merge(matId, item.getQuantity(), BigDecimal::add);
            materialById.put(matId, mat);
            drugNameByMaterial.putIfAbsent(matId, med.getDrugName());
        }
    }

    private StockIssue deductInventoryFefoForPrescription(
            Prescription prescription,
            Branch branch,
            StaffProfile issuer,
            TreeMap<Long, BigDecimal> requestedQtyByMaterial,
            Map<Long, Material> materialById,
            Map<Long, String> drugNameByMaterial,
            String issueReason,
            String notes) {
        LocalDate today = LocalDate.now();
        Long branchId = branch.getBranchId();

        StockIssue stockIssue = new StockIssue();
        stockIssue.setIssueCode(generateUniqueIssueCode());
        stockIssue.setBranch(branch);
        stockIssue.setIssueType(StockIssueType.PRESCRIPTION_OUT);
        stockIssue.setIssueDate(today);
        stockIssue.setIssuer(issuer);
        stockIssue.setPrescription(prescription);
        stockIssue.setPrescriptionCode(prescription.getPrescriptionCode());
        stockIssue.setIssueReason(issueReason);
        stockIssue.setNotes(notes);

        List<StockIssueItem> issueItems = new ArrayList<>();

        // Duyệt theo thứ tự materialId tăng dần (TreeMap) để khóa bi quan nhất quán, tránh deadlock
        for (Map.Entry<Long, BigDecimal> entry : requestedQtyByMaterial.entrySet()) {
            Long matId = entry.getKey();
            BigDecimal requestedQty = entry.getValue();
            Material material = materialById.get(matId);
            String drugName = drugNameByMaterial.getOrDefault(matId, material != null ? material.getMaterialName() : ("ID " + matId));
            String unit = material != null && material.getUnit() != null ? material.getUnit() : "Viên";

            // 1. Khóa bản ghi tồn kho chi nhánh
            BranchInventory inventory = branchInventoryRepository.findForUpdate(branchId, matId).orElse(null);

            // 2. Khóa các lô còn hạn, trạng thái ACTIVE theo thứ tự FEFO (hết hạn sớm nhất trước)
            List<MaterialBatch> availableBatches = materialBatchRepository.findAvailableBatchesFefoForUpdate(branchId, matId, today);
            BigDecimal totalAvailable = availableBatches.stream()
                    .map(b -> b.getQuantityRemaining() != null ? b.getQuantityRemaining() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalAvailable.compareTo(BigDecimal.ZERO) <= 0) {
                List<MaterialBatch> expiredBatches = materialBatchRepository.findExpiredBatchesForUpdate(branchId, matId, today);
                if (!expiredBatches.isEmpty()) {
                    throw new IllegalStateException("Thuốc '" + drugName + "' tại chi nhánh " + branch.getBranchName()
                            + " chỉ còn lô đã hết hạn sử dụng (tồn khả dụng: 0 " + unit + "), không thể kê đơn.");
                }
                throw new IllegalStateException("Thuốc '" + drugName + "' tại chi nhánh " + branch.getBranchName()
                        + " hiện đã hết hàng (tồn khả dụng: 0 " + unit + "), không thể kê đơn.");
            }
            if (totalAvailable.compareTo(requestedQty) < 0) {
                throw new IllegalStateException("Thuốc '" + drugName + "' không đủ tồn kho khả dụng tại chi nhánh "
                        + branch.getBranchName() + " (Tồn còn hạn: " + totalAvailable.stripTrailingZeros().toPlainString()
                        + " " + unit + ", Yêu cầu kê: " + requestedQty.stripTrailingZeros().toPlainString() + " " + unit + ").");
            }

            // 3. Phân bổ trừ kho theo FEFO
            BigDecimal remainingToDeduct = requestedQty;
            for (MaterialBatch batch : availableBatches) {
                if (remainingToDeduct.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal batchQty = batch.getQuantityRemaining() != null ? batch.getQuantityRemaining() : BigDecimal.ZERO;
                if (batchQty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal take = batchQty.min(remainingToDeduct);
                BigDecimal after = batchQty.subtract(take);
                batch.setQuantityRemaining(after);
                if (after.compareTo(BigDecimal.ZERO) <= 0) {
                    batch.setStatus(BatchStatus.OUT_OF_STOCK);
                }
                materialBatchRepository.save(batch);
                remainingToDeduct = remainingToDeduct.subtract(take);

                StockIssueItem issueItem = new StockIssueItem();
                issueItem.setIssue(stockIssue);
                issueItem.setMaterial(material);
                issueItem.setBatch(batch);
                issueItem.setQuantity(take);
                issueItem.setNotes("Xuất FEFO đơn " + prescription.getPrescriptionCode() + " - Lô: " + batch.getBatchNumber()
                        + " (HSD: " + batch.getExpiryDate().format(DATE_FORMATTER) + ")");
                issueItems.add(issueItem);
            }

            if (remainingToDeduct.compareTo(BigDecimal.ZERO) > 0) {
                throw new IllegalStateException("Không thể phân bổ đủ số lượng FEFO cho thuốc '" + drugName + "'.");
            }

            // 4. Cập nhật tồn kho tổng chi nhánh
            if (inventory == null) {
                inventory = new BranchInventory();
                inventory.setBranch(branch);
                inventory.setMaterial(material);
                inventory.setCurrentStock(BigDecimal.ZERO);
            }
            BigDecimal currentStock = inventory.getCurrentStock() != null ? inventory.getCurrentStock() : BigDecimal.ZERO;
            BigDecimal nextStock = currentStock.subtract(requestedQty);
            if (nextStock.compareTo(BigDecimal.ZERO) < 0) {
                nextStock = BigDecimal.ZERO;
            }
            inventory.setCurrentStock(nextStock);
            branchInventoryRepository.save(inventory);
        }

        stockIssue.setItems(issueItems);
        return stockIssueRepository.saveAndFlush(stockIssue);
    }

    private void restoreAllDeductedStockForPrescription(Prescription prescription, String reason) {
        List<StockIssue> history = stockIssueRepository.findByPrescriptionIdWithItemsOrderByIssueIdAsc(prescription.getPrescriptionId());
        if (history.isEmpty()) {
            return;
        }
        // Tính số lượng ròng đã xuất theo từng batchId (PRESCRIPTION_OUT trừ PRESCRIPTION_RETURN)
        Map<Long, BigDecimal> netByBatchId = new LinkedHashMap<>();
        Map<Long, Material> materialByBatchId = new HashMap<>();
        for (StockIssue si : history) {
            if (si.getItems() == null) continue;
            boolean isOut = si.getIssueType() == StockIssueType.PRESCRIPTION_OUT;
            boolean isReturn = si.getIssueType() == StockIssueType.PRESCRIPTION_RETURN;
            if (!isOut && !isReturn) continue;
            for (StockIssueItem it : si.getItems()) {
                if (it.getBatch() == null || it.getBatch().getBatchId() == null || it.getQuantity() == null) continue;
                Long batchId = it.getBatch().getBatchId();
                BigDecimal delta = isOut ? it.getQuantity() : it.getQuantity().negate();
                netByBatchId.merge(batchId, delta, BigDecimal::add);
                materialByBatchId.putIfAbsent(batchId, it.getMaterial());
            }
        }

        Branch branch = prescription.getBranch();
        StaffProfile issuer = prescription.getDoctor();
        LocalDate today = LocalDate.now();
        StockIssue returnIssue = new StockIssue();
        returnIssue.setIssueCode(generateUniqueIssueCode());
        returnIssue.setBranch(branch);
        returnIssue.setIssueType(StockIssueType.PRESCRIPTION_RETURN);
        returnIssue.setIssueDate(today);
        returnIssue.setIssuer(issuer);
        returnIssue.setPrescription(prescription);
        returnIssue.setPrescriptionCode(prescription.getPrescriptionCode());
        returnIssue.setIssueReason(reason);
        returnIssue.setNotes("Hoàn kho tự động - Đơn thuốc: " + prescription.getPrescriptionCode());

        List<StockIssueItem> returnItems = new ArrayList<>();
        TreeMap<Long, BigDecimal> restoreByMaterial = new TreeMap<>();

        for (Map.Entry<Long, BigDecimal> entry : netByBatchId.entrySet()) {
            BigDecimal netQty = entry.getValue();
            if (netQty == null || netQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Long batchId = entry.getKey();
            MaterialBatch batch = materialBatchRepository.findByIdForUpdate(batchId).orElse(null);
            if (batch == null) {
                continue;
            }
            BigDecimal curRem = batch.getQuantityRemaining() != null ? batch.getQuantityRemaining() : BigDecimal.ZERO;
            batch.setQuantityRemaining(curRem.add(netQty));
            if (batch.getStatus() == BatchStatus.OUT_OF_STOCK) {
                batch.setStatus(batch.getExpiryDate() != null && batch.getExpiryDate().isBefore(today)
                        ? BatchStatus.EXPIRED : BatchStatus.ACTIVE);
            }
            materialBatchRepository.save(batch);

            Material mat = batch.getMaterial() != null ? batch.getMaterial() : materialByBatchId.get(batchId);
            if (mat != null && mat.getMaterialId() != null) {
                restoreByMaterial.merge(mat.getMaterialId(), netQty, BigDecimal::add);
            }

            StockIssueItem retItem = new StockIssueItem();
            retItem.setIssue(returnIssue);
            retItem.setMaterial(mat);
            retItem.setBatch(batch);
            retItem.setQuantity(netQty);
            retItem.setNotes("Hoàn về lô " + batch.getBatchNumber() + " từ đơn " + prescription.getPrescriptionCode());
            returnItems.add(retItem);
        }

        for (Map.Entry<Long, BigDecimal> matEntry : restoreByMaterial.entrySet()) {
            BranchInventory inv = branchInventoryRepository.findForUpdate(branch.getBranchId(), matEntry.getKey()).orElse(null);
            if (inv != null) {
                BigDecimal cur = inv.getCurrentStock() != null ? inv.getCurrentStock() : BigDecimal.ZERO;
                inv.setCurrentStock(cur.add(matEntry.getValue()));
                branchInventoryRepository.save(inv);
            }
        }

        if (!returnItems.isEmpty()) {
            returnIssue.setItems(returnItems);
            stockIssueRepository.saveAndFlush(returnIssue);
        }
    }

    private void returnPartialStockForPrescription(
            Prescription prescription,
            Branch branch,
            StaffProfile issuer,
            TreeMap<Long, BigDecimal> qtyToReturnByMaterial,
            String reason) {
        List<StockIssue> history = stockIssueRepository.findByPrescriptionIdWithItemsOrderByIssueIdAsc(prescription.getPrescriptionId());
        // Net đã xuất theo (materialId -> LinkedHashMap<batchId, netQty>)
        Map<Long, LinkedHashMap<Long, BigDecimal>> netByMatAndBatch = new HashMap<>();
        for (StockIssue si : history) {
            if (si.getItems() == null) continue;
            boolean isOut = si.getIssueType() == StockIssueType.PRESCRIPTION_OUT;
            boolean isReturn = si.getIssueType() == StockIssueType.PRESCRIPTION_RETURN;
            if (!isOut && !isReturn) continue;
            for (StockIssueItem it : si.getItems()) {
                if (it.getMaterial() == null || it.getBatch() == null || it.getQuantity() == null) continue;
                Long matId = it.getMaterial().getMaterialId();
                Long batchId = it.getBatch().getBatchId();
                BigDecimal delta = isOut ? it.getQuantity() : it.getQuantity().negate();
                netByMatAndBatch.computeIfAbsent(matId, k -> new LinkedHashMap<>())
                        .merge(batchId, delta, BigDecimal::add);
            }
        }

        LocalDate today = LocalDate.now();
        StockIssue returnIssue = new StockIssue();
        returnIssue.setIssueCode(generateUniqueIssueCode());
        returnIssue.setBranch(branch);
        returnIssue.setIssueType(StockIssueType.PRESCRIPTION_RETURN);
        returnIssue.setIssueDate(today);
        returnIssue.setIssuer(issuer);
        returnIssue.setPrescription(prescription);
        returnIssue.setPrescriptionCode(prescription.getPrescriptionCode());
        returnIssue.setIssueReason(reason);
        returnIssue.setNotes("Hoàn kho điều chỉnh sửa đơn " + prescription.getPrescriptionCode());

        List<StockIssueItem> returnItems = new ArrayList<>();

        for (Map.Entry<Long, BigDecimal> entry : qtyToReturnByMaterial.entrySet()) {
            Long matId = entry.getKey();
            BigDecimal needReturn = entry.getValue();
            if (needReturn == null || needReturn.compareTo(BigDecimal.ZERO) <= 0) continue;

            LinkedHashMap<Long, BigDecimal> batchNets = netByMatAndBatch.getOrDefault(matId, new LinkedHashMap<>());
            List<Long> batchIdsDesc = new ArrayList<>(batchNets.keySet());
            Collections.reverse(batchIdsDesc);

            BigDecimal actuallyReturned = BigDecimal.ZERO;
            for (Long batchId : batchIdsDesc) {
                if (needReturn.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal maxReturnable = batchNets.getOrDefault(batchId, BigDecimal.ZERO);
                if (maxReturnable.compareTo(BigDecimal.ZERO) <= 0) continue;

                MaterialBatch batch = materialBatchRepository.findByIdForUpdate(batchId).orElse(null);
                if (batch == null) continue;

                BigDecimal giveBack = maxReturnable.min(needReturn);
                BigDecimal curRem = batch.getQuantityRemaining() != null ? batch.getQuantityRemaining() : BigDecimal.ZERO;
                batch.setQuantityRemaining(curRem.add(giveBack));
                if (batch.getStatus() == BatchStatus.OUT_OF_STOCK) {
                    batch.setStatus(batch.getExpiryDate() != null && batch.getExpiryDate().isBefore(today)
                            ? BatchStatus.EXPIRED : BatchStatus.ACTIVE);
                }
                materialBatchRepository.save(batch);

                needReturn = needReturn.subtract(giveBack);
                actuallyReturned = actuallyReturned.add(giveBack);

                StockIssueItem retItem = new StockIssueItem();
                retItem.setIssue(returnIssue);
                retItem.setMaterial(batch.getMaterial());
                retItem.setBatch(batch);
                retItem.setQuantity(giveBack);
                retItem.setNotes("Hoàn về lô " + batch.getBatchNumber() + " do giảm số lượng đơn " + prescription.getPrescriptionCode());
                returnItems.add(retItem);
            }

            if (actuallyReturned.compareTo(BigDecimal.ZERO) > 0) {
                BranchInventory inv = branchInventoryRepository.findForUpdate(branch.getBranchId(), matId).orElse(null);
                if (inv != null) {
                    BigDecimal cur = inv.getCurrentStock() != null ? inv.getCurrentStock() : BigDecimal.ZERO;
                    inv.setCurrentStock(cur.add(actuallyReturned));
                    branchInventoryRepository.save(inv);
                }
            }
        }

        if (!returnItems.isEmpty()) {
            returnIssue.setItems(returnItems);
            stockIssueRepository.saveAndFlush(returnIssue);
        }
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
            case "DISPENSED" -> "Đã cấp phát";
            case "CANCELLED" -> "Đã hủy";
            default -> p.isInventoryDeducted() ? "Đã xuất kho theo đơn" : "Chờ tiếp nhận";
        };

        String latestIssueCode = null;
        if (p.getPrescriptionId() != null) {
            List<StockIssue> issues = stockIssueRepository.findByPrescriptionIdWithItemsOrderByIssueIdAsc(p.getPrescriptionId());
            for (int i = issues.size() - 1; i >= 0; i--) {
                if (issues.get(i).getIssueType() == StockIssueType.PRESCRIPTION_OUT) {
                    latestIssueCode = issues.get(i).getIssueCode();
                    break;
                }
            }
        }

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
                .inventoryDeducted(p.isInventoryDeducted())
                .stockIssueCode(latestIssueCode)
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
