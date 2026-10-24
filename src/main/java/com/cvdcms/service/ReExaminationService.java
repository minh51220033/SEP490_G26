package com.cvdcms.service;

import com.cvdcms.DTO.CareTaskUpdateDTO;
import com.cvdcms.DTO.ReExaminationRequestDTO;
import com.cvdcms.DTO.ReExaminationResponseDTO;
import com.cvdcms.entity.enums.PreferredSession;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface ReExaminationService {

    ReExaminationResponseDTO createReExamination(ReExaminationRequestDTO dto);

    List<ReExaminationResponseDTO> getReExaminationsByPatient(Long patientProfileId);

    List<ReExaminationResponseDTO> getRecallsList(String dateFilter, Long doctorId, String status, String keyword);

    Map<String, Object> getRecallsStatistics();

    ReExaminationResponseDTO updateAppointmentStatus(Long appointmentId, String status, String cancelReason);

    ReExaminationResponseDTO updateAppointmentDate(Long appointmentId, LocalDate newDate, String newTime, PreferredSession session);

    void updateCareTaskStatus(CareTaskUpdateDTO dto);

    void cancelAppointment(Long appointmentId, String reason);
}
