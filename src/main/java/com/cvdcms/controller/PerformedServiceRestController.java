package com.cvdcms.controller;

import com.cvdcms.DTO.PerformedServiceRequestDTO;
import com.cvdcms.DTO.PerformedServiceResponseDTO;
import com.cvdcms.DTO.ServiceDTO;
import com.cvdcms.service.PerformedServiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/doctor")
@RequiredArgsConstructor
public class PerformedServiceRestController {

    private final PerformedServiceService performedServiceService;

    @GetMapping("/services/catalog")
    public ResponseEntity<List<ServiceDTO>> getActiveServicesCatalog() {
        return ResponseEntity.ok(performedServiceService.getAllActiveServices());
    }

    @GetMapping("/performed-services")
    public ResponseEntity<List<PerformedServiceResponseDTO>> getPerformedServicesByVisit(
            @RequestParam("visitId") Long visitId
    ) {
        return ResponseEntity.ok(performedServiceService.getPerformedServicesByVisitId(visitId));
    }

    @PostMapping("/performed-services")
    public ResponseEntity<Map<String, Object>> createPerformedService(
            @Valid @RequestBody PerformedServiceRequestDTO requestDTO
    ) {
        log.info("API createPerformedService called with: visitId={}, serviceId={}",
                requestDTO.getVisitId(), requestDTO.getServiceId());

        PerformedServiceResponseDTO responseDTO = performedServiceService.createPerformedService(requestDTO);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Ghi nhận dịch vụ thực hiện thành công (COMPLETED)!");
        result.put("data", responseDTO);

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/performed-services/{id}")
    public ResponseEntity<Map<String, Object>> cancelPerformedService(
            @PathVariable("id") Long id
    ) {
        log.info("API cancelPerformedService called with id={}", id);
        performedServiceService.cancelPerformedService(id);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Đã xóa dịch vụ thực hiện thành công!");

        return ResponseEntity.ok(result);
    }
}
