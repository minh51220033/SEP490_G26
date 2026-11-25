package com.cvdcms.service;

import com.cvdcms.DTO.OdontogramRequestDTO;
import com.cvdcms.DTO.OdontogramResponseDTO;
import com.cvdcms.entity.PatientOdontogram;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.Visit;
import com.cvdcms.repository.PatientOdontogramRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PatientOdontogramService {

    private static final DateTimeFormatter DATE_DISPLAY_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DATETIME_DISPLAY_FMT = DateTimeFormatter.ofPattern("HH:mm dd-MM-yyyy");

    private final PatientOdontogramRepository odontogramRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final VisitRepository visitRepository;
    private final StaffProfileRepository staffProfileRepository;

    @Transactional(readOnly = true)
    public List<OdontogramResponseDTO> listByPatient(Long patientId) {
        return odontogramRepository
                .findByPatientProfile_ProfileIdAndIsDeletedFalseOrderByChartDateDescCreatedAtDesc(patientId)
                .stream()
                .map(entity -> toDto(entity, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public OdontogramResponseDTO getDetail(Long odontogramId) {
        PatientOdontogram entity = findActiveOrThrow(odontogramId);
        return toDto(entity, true);
    }

    @Transactional
    public OdontogramResponseDTO create(Long patientId, OdontogramRequestDTO req, User actor) {
        PatientProfile patient = patientProfileRepository.findById(patientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));

        PatientOdontogram entity = new PatientOdontogram();
        entity.setPatientProfile(patient);
        applyRequestFields(entity, patient, req);

        if (actor != null) {
            entity.setCreatedByUserId(actor.getUserId());
            entity.setCreatedByName(resolveActorDisplayName(actor));
        }

        PatientOdontogram saved = odontogramRepository.save(entity);
        return toDto(saved, true);
    }

    @Transactional
    public OdontogramResponseDTO update(Long odontogramId, OdontogramRequestDTO req, User actor) {
        PatientOdontogram entity = findActiveOrThrow(odontogramId);
        applyRequestFields(entity, entity.getPatientProfile(), req);

        if (actor != null) {
            entity.setUpdatedByUserId(actor.getUserId());
            entity.setUpdatedByName(resolveActorDisplayName(actor));
        }
        entity.setUpdatedAt(LocalDateTime.now());

        PatientOdontogram saved = odontogramRepository.save(entity);
        return toDto(saved, true);
    }

    @Transactional
    public void softDelete(Long odontogramId, User actor) {
        PatientOdontogram entity = findActiveOrThrow(odontogramId);
        entity.setIsDeleted(true);
        if (actor != null) {
            entity.setUpdatedByUserId(actor.getUserId());
            entity.setUpdatedByName(resolveActorDisplayName(actor));
        }
        entity.setUpdatedAt(LocalDateTime.now());
        odontogramRepository.save(entity);
    }

    private PatientOdontogram findActiveOrThrow(Long odontogramId) {
        return odontogramRepository.findByOdontogramIdAndIsDeletedFalse(odontogramId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi tình trạng răng."));
    }

    private void applyRequestFields(PatientOdontogram entity, PatientProfile patient, OdontogramRequestDTO req) {
        if (req == null) {
            throw new IllegalArgumentException("Dữ liệu sơ đồ răng không hợp lệ.");
        }
        String normalizedType = "CHILD".equalsIgnoreCase(req.getChartType()) ? "CHILD" : "ADULT";
        entity.setChartType(normalizedType);
        entity.setChartDate(req.getChartDate() != null ? req.getChartDate() : LocalDate.now());
        entity.setNote(req.getNote() != null ? req.getNote().trim() : null);

        String payload = req.getPayloadJson();
        entity.setPayloadJson(payload != null && !payload.isBlank() ? payload : "{}");
        entity.setStatusCount(req.getStatusCount() != null && req.getStatusCount() >= 0 ? req.getStatusCount() : 0);
        entity.setPlanCount(req.getPlanCount() != null && req.getPlanCount() >= 0 ? req.getPlanCount() : 0);
        entity.setAffectedTeethCount(req.getAffectedTeethCount() != null && req.getAffectedTeethCount() >= 0 ? req.getAffectedTeethCount() : 0);

        if (req.getVisitId() != null) {
            Visit visit = visitRepository.findById(req.getVisitId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca khám liên kết."));
            if (visit.getPatientProfile() == null || !visit.getPatientProfile().getProfileId().equals(patient.getProfileId())) {
                throw new IllegalArgumentException("Ca khám được chọn không thuộc hồ sơ bệnh nhân này.");
            }
            entity.setVisit(visit);
        }
    }

    private String resolveActorDisplayName(User user) {
        if (user == null) {
            return null;
        }
        if (user.getUserId() != null) {
            StaffProfile staff = staffProfileRepository.findByUser_UserId(user.getUserId()).orElse(null);
            if (staff != null && staff.getFullName() != null && !staff.getFullName().isBlank()) {
                return staff.getFullName().trim();
            }
        }
        return user.getUsername();
    }

    private OdontogramResponseDTO toDto(PatientOdontogram e, boolean includePayload) {
        LocalDate cDate = e.getChartDate();
        Visit visit = e.getVisit();
        return OdontogramResponseDTO.builder()
                .odontogramId(e.getOdontogramId())
                .patientId(e.getPatientProfile() != null ? e.getPatientProfile().getProfileId() : null)
                .visitId(visit != null ? visit.getVisitId() : null)
                .visitCode(visit != null ? visit.getVisitCode() : null)
                .chartDate(cDate != null ? cDate.toString() : null)
                .chartDateDisplay(cDate != null ? cDate.format(DATE_DISPLAY_FMT) : "—")
                .chartType(e.getChartType())
                .note(e.getNote())
                .statusCount(e.getStatusCount() != null ? e.getStatusCount() : 0)
                .planCount(e.getPlanCount() != null ? e.getPlanCount() : 0)
                .affectedTeethCount(e.getAffectedTeethCount() != null ? e.getAffectedTeethCount() : 0)
                .createdByName(e.getCreatedByName())
                .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().format(DATETIME_DISPLAY_FMT) : null)
                .createdAtIso(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                .updatedByName(e.getUpdatedByName())
                .updatedAt(e.getUpdatedAt() != null ? e.getUpdatedAt().format(DATETIME_DISPLAY_FMT) : null)
                .payloadJson(includePayload ? e.getPayloadJson() : null)
                .build();
    }
}
