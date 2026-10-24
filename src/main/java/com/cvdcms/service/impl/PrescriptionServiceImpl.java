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
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc ID: " + prescriptionId));
        return mapToPrescriptionResponseDTO(prescription);
    }

    @Override
    @Transactional
    public PrescriptionResponseDTO createPrescription(PrescriptionRequestDTO request) {
        if (request.getVisitId() == null) {
            throw new IllegalArgumentException("Mã ca khám (visitId) không được để trống!");
        }

        Visit visit = visitRepository.findById(request.getVisitId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám ID: " + request.getVisitId()));

        // Quy tắc BR-EMR-02: Khóa bệnh án lâm sàng bất biến khi chuyển thanh toán hoặc hoàn tất
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED) {
            throw new IllegalStateException("Bệnh án lâm sàng đã hoàn tất và khóa bất biến (BR-EMR-02). Không thể tạo đơn thuốc mới.");
        }

        PatientProfile patient = visit.getPatientProfile();
        if (patient == null) {
            throw new IllegalArgumentException("Ca khám chưa liên kết với hồ sơ bệnh nhân!");
        }

        StaffProfile doctor = null;
        if (request.getDoctorId() != null) {
            doctor = staffProfileRepository.findById(request.getDoctorId()).orElse(null);
        }
        if (doctor == null) {
            doctor = visit.getPrimaryDoctor();
        }
        if (doctor == null) {
            throw new IllegalArgumentException("Vui lòng chọn bác sĩ kê đơn!");
        }

        Prescription prescription = new Prescription();
        prescription.setPrescriptionCode("DT" + (System.currentTimeMillis() % 10000000));
        prescription.setVisit(visit);
        prescription.setPatientProfile(patient);
        prescription.setBranch(visit.getBranch());
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

                PrescriptionItem item = new PrescriptionItem();
                item.setPrescription(prescription);

                if (itemDTO.getMedicationId() != null) {
                    Medication med = medicationRepository.findById(itemDTO.getMedicationId()).orElse(null);
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
                                : "Viên"
                );
                item.setUsageInstruction(
                        (itemDTO.getUsageInstruction() != null && !itemDTO.getUsageInstruction().trim().isEmpty())
                                ? itemDTO.getUsageInstruction().trim()
                                : "Uống sau khi ăn"
                );

                prescription.getItems().add(item);
            }
        }

        Prescription saved = prescriptionRepository.save(prescription);
        log.info("Đã tạo đơn thuốc thành công: ID = {}, Code = {}", saved.getPrescriptionId(), saved.getPrescriptionCode());
        return mapToPrescriptionResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deletePrescription(Long prescriptionId) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc ID: " + prescriptionId));

        if (prescription.getVisit() != null &&
                (prescription.getVisit().getStatus() == VisitStatus.WAITING_PAYMENT || prescription.getVisit().getStatus() == VisitStatus.COMPLETED)) {
            throw new IllegalStateException("Bệnh án lâm sàng đã khóa bất biến (BR-EMR-02). Không thể xóa đơn thuốc.");
        }

        prescriptionRepository.delete(prescription);
        log.info("Đã xóa đơn thuốc ID: {}", prescriptionId);
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
        if (p.getItems() != null) {
            for (PrescriptionItem it : p.getItems()) {
                itemDTOs.add(PrescriptionItemResponseDTO.builder()
                        .itemId(it.getItemId())
                        .medicationId(it.getMedication() != null ? it.getMedication().getMedicationId() : null)
                        .drugName(it.getDrugName())
                        .dosage(it.getDosage())
                        .durationDays(it.getDurationDays())
                        .quantity(it.getQuantity())
                        .unit(it.getUnit())
                        .usageInstruction(it.getUsageInstruction())
                        .build());
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
                .items(itemDTOs)
                .build();
    }
}
