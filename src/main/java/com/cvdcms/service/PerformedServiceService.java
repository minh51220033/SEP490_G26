package com.cvdcms.service;

import com.cvdcms.DTO.PerformedServiceRequestDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.entity.StaffProfile;

import java.util.List;

public interface PerformedServiceService {

    List<ServiceDTO> getAllActiveServices();

    List<StaffProfile> getAvailableDoctors();

    List<StaffProfile> getAvailableAssistants();

    List<PerformedServiceResponseDTO> getPerformedServicesByVisitId(Long visitId);

    PerformedServiceResponseDTO createPerformedService(PerformedServiceRequestDTO requestDTO);

    void cancelPerformedService(Long performedId);
}
