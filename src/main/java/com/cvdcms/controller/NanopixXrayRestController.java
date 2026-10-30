package com.cvdcms.controller;

import com.cvdcms.DTO.NanopixReconciliationRequestDTO;
import com.cvdcms.DTO.NanopixSyncRequestDTO;
import com.cvdcms.DTO.NanopixSyncStatsDTO;
import com.cvdcms.DTO.NanopixXrayResponseDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.NanopixXrayService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST API cho chức năng Quản lý kết quả phim X-quang NanoPix (FT12):
 * - SCR-12.1: Danh sách và trình xem ảnh X-quang (lọc theo cơ sở)
 * - SCR-12.2: Danh sách kết quả chưa khớp hồ sơ
 * - SCR-12.3: Form đối chiếu và liên kết kết quả với ca khám
 * - SCR-12.4: Trạng thái đồng bộ và thông báo lỗi kết nối
 */
@Slf4j
@RestController
@RequestMapping("/api/xray")
@RequiredArgsConstructor
public class NanopixXrayRestController {

    private final NanopixXrayService nanopixXrayService;

    /**
     * Lấy danh sách cơ sở mà tài khoản hiện tại được phép truy cập.
     */
    @GetMapping("/branches")
    public ResponseEntity<Map<String, Object>> getBranches(HttpSession session) {
        User user = requireUser(session);
        return ResponseEntity.ok(nanopixXrayService.getAccessibleBranches(user.getUserId()));
    }

    /**
     * SCR-12.1: Lấy danh sách kết quả phim X-quang với bộ lọc và phân trang.
     */
    @GetMapping("/list")
    public ResponseEntity<Page<NanopixXrayResponseDTO>> getXrays(
            @RequestParam(value = "status", required = false, defaultValue = "ALL") String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "branchId", required = false) Long branchId,
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "12") int size,
            HttpSession session
    ) {
        User user = requireUser(session);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));
        Page<NanopixXrayResponseDTO> result = nanopixXrayService.getXrays(status, keyword, fromDate, toDate, branchId, pageable, user.getUserId());
        return ResponseEntity.ok(result);
    }

    /**
     * SCR-12.2: Danh sách kết quả chưa khớp hồ sơ (UNMATCHED).
     */
    @GetMapping("/unmatched")
    public ResponseEntity<List<NanopixXrayResponseDTO>> getUnmatchedXrays(
            @RequestParam(value = "keyword", required = false) String keyword,
            HttpSession session
    ) {
        User user = requireUser(session);
        List<NanopixXrayResponseDTO> list = nanopixXrayService.getUnmatchedXrays(keyword, user.getUserId());
        return ResponseEntity.ok(list);
    }

    /**
     * Lấy chi tiết một phim X-quang theo ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<NanopixXrayResponseDTO> getXrayDetail(
            @PathVariable("id") Long id,
            HttpSession session
    ) {
        User user = requireUser(session);
        NanopixXrayResponseDTO dto = nanopixXrayService.getXrayDetail(id, user.getUserId());
        return ResponseEntity.ok(dto);
    }

    /**
     * SCR-12.3: Thực hiện đối chiếu thủ công và liên kết kết quả vào bệnh nhân và ca khám.
     */
    @PostMapping("/{id}/reconcile")
    public ResponseEntity<NanopixXrayResponseDTO> reconcileXray(
            @PathVariable("id") Long id,
            @RequestBody NanopixReconciliationRequestDTO request,
            HttpSession session
    ) {
        User user = requireUser(session);
        NanopixXrayResponseDTO response = nanopixXrayService.reconcileXray(id, request, user.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Lấy danh sách ca khám và chỉ định CLS của bệnh nhân để hỗ trợ form đối chiếu.
     */
    @GetMapping("/lookups/patient-visits/{profileId}")
    public ResponseEntity<Map<String, Object>> getPatientVisitsLookups(
            @PathVariable("profileId") Long profileId,
            HttpSession session
    ) {
        requireUser(session);
        Map<String, Object> lookups = nanopixXrayService.getReconciliationLookups(profileId);
        return ResponseEntity.ok(lookups);
    }

    /**
     * SCR-12.4: Lấy thống kê tổng quan và trạng thái đồng bộ kết nối adapter.
     */
    @GetMapping("/stats")
    public ResponseEntity<NanopixSyncStatsDTO> getStats(HttpSession session) {
        User user = requireUser(session);
        NanopixSyncStatsDTO stats = nanopixXrayService.getSyncStatistics(user.getUserId());
        return ResponseEntity.ok(stats);
    }

    /**
     * SCR-12.4: Nạp phim X-quang mới từ máy NanoPix hoặc form nạp kiểm soát (Controlled Ingestion).
     */
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<NanopixXrayResponseDTO> ingestXray(
            @RequestParam("externalStudyUid") String externalStudyUid,
            @RequestParam(value = "rawPatientName", required = false) String rawPatientName,
            @RequestParam(value = "externalPatientId", required = false) String externalPatientId,
            @RequestParam(value = "capturedAt", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime capturedAt,
            @RequestParam(value = "deviceSerialNumber", required = false, defaultValue = "EN1E0408A032") String deviceSerialNumber,
            @RequestParam(value = "deviceModel", required = false, defaultValue = "NanoPix1") String deviceModel,
            @RequestParam(value = "toothNumbers", required = false) String toothNumbers,
            @RequestParam(value = "notes", required = false) String notes,
            @RequestParam("file") MultipartFile file,
            HttpSession session
    ) throws IOException {
        User user = requireUser(session);

        NanopixSyncRequestDTO requestDTO = NanopixSyncRequestDTO.builder()
                .externalStudyUid(externalStudyUid)
                .rawPatientName(rawPatientName)
                .externalPatientId(externalPatientId)
                .capturedAt(capturedAt != null ? capturedAt : LocalDateTime.now())
                .deviceSerialNumber(deviceSerialNumber)
                .deviceModel(deviceModel)
                .toothNumbers(toothNumbers)
                .notes(notes)
                .build();

        NanopixXrayResponseDTO response = nanopixXrayService.ingestXray(requestDTO, file, user.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Xác thực người dùng hiện tại từ session hệ thống CV-DCMS ("loggedInUser").
     */
    private User requireUser(HttpSession session) {
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
        }
        Object loggedIn = session.getAttribute("loggedInUser");
        if (loggedIn instanceof User u && u.getUserId() != null) {
            return u;
        }
        Object userObj = session.getAttribute("user");
        if (userObj instanceof User u && u.getUserId() != null) {
            return u;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng tính năng này.");
    }
}
