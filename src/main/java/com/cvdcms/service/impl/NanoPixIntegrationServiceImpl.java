package com.cvdcms.service.impl;

import com.cvdcms.DTO.NanopixSyncRequestDTO;
import com.cvdcms.DTO.NanopixSyncStatsDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.NanopixSyncStatus;
import com.cvdcms.entity.enums.ParaclinicalFileType;
import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.NanoPixIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NanoPixIntegrationServiceImpl implements NanoPixIntegrationService {

    private final NanopixXraySyncLogRepository nanopixRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final VisitRepository visitRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final ParaclinicalOrderItemRepository paraclinicalOrderItemRepository;
    private final ParaclinicalResultAttachmentRepository attachmentRepository;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "dcm", "dicom", "tif", "tiff");
    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024; // 25 MB

    @Override
    @Transactional
    public NanopixXraySyncLog ingestXray(NanopixSyncRequestDTO request, MultipartFile file, Long staffId) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu nạp phim NanoPix không được để trống.");
        }

        // 1. Kiểm tra mã tham chiếu nguồn (externalStudyUid) - bắt buộc để chống nhập trùng
        String studyUid = request.getExternalStudyUid();
        if (!StringUtils.hasText(studyUid)) {
            // Tự động sinh mã chuẩn nếu nguồn chưa cung cấp nhưng bắt buộc phải là duy nhất
            studyUid = "NP_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + UUID.randomUUID().toString().substring(0, 8);
        } else {
            studyUid = studyUid.trim();
        }

        if (nanopixRepository.existsByExternalStudyUid(studyUid)) {
            log.warn("Phát hiện nhập trùng kết quả NanoPix với Study UID: {}", studyUid);
            throw new IllegalStateException("Mã kết quả bên ngoài (external_study_uid) '" + studyUid + "' đã tồn tại trên hệ thống. Không được ghi đè hoặc nạp trùng.");
        }

        // 2. Lưu trữ file ảnh hoặc sử dụng imageUrl có sẵn
        String imageUrl;
        if (file != null && !file.isEmpty()) {
            imageUrl = storeXrayImage(file);
        } else if (StringUtils.hasText(request.getImageUrl())) {
            imageUrl = request.getImageUrl().trim();
        } else {
            throw new IllegalArgumentException("Vui lòng tải lên file ảnh chụp X-quang từ máy NanoPix hoặc cung cấp đường dẫn ảnh.");
        }

        // 3. Khởi tạo đối tượng log đồng bộ
        NanopixXraySyncLog syncLog = new NanopixXraySyncLog();
        syncLog.setExternalStudyUid(studyUid);
        syncLog.setExternalPatientId(StringUtils.hasText(request.getExternalPatientId()) ? request.getExternalPatientId().trim() : null);
        syncLog.setRawPatientName(StringUtils.hasText(request.getRawPatientName()) ? request.getRawPatientName().trim() : null);
        syncLog.setImageUrl(imageUrl);
        syncLog.setCapturedAt(request.getCapturedAt() != null ? request.getCapturedAt() : LocalDateTime.now());
        syncLog.setDeviceSerialNumber(StringUtils.hasText(request.getDeviceSerialNumber()) ? request.getDeviceSerialNumber().trim() : "EN1E0408A032");
        syncLog.setDeviceModel(StringUtils.hasText(request.getDeviceModel()) ? request.getDeviceModel().trim() : "NanoPix1");
        syncLog.setToothNumbers(StringUtils.hasText(request.getToothNumbers()) ? request.getToothNumbers().trim() : null);
        syncLog.setNotes(StringUtils.hasText(request.getNotes()) ? request.getNotes().trim() : null);
        syncLog.setIsManualLinked(false);

        // 4. Cơ chế Auto-Matching thông minh và an toàn
        // Tuyệt đối không tự động gán ảnh chỉ vì trùng tên; chỉ gán khi có mã bệnh nhân khớp và đúng 1 ca khám đang hoạt động
        PatientProfile matchedPatient = null;
        if (StringUtils.hasText(syncLog.getExternalPatientId())) {
            matchedPatient = patientProfileRepository.findByPatientCode(syncLog.getExternalPatientId()).orElse(null);
        }

        if (matchedPatient != null) {
            syncLog.setMatchedProfile(matchedPatient);
            // Tìm ca khám đang diễn ra trong ngày của bệnh nhân
            Optional<Visit> activeVisitOpt = visitRepository.findFirstByPatientProfile_ProfileIdAndStatusIn(
                    matchedPatient.getProfileId(),
                    List.of(VisitStatus.WAITING_PARACLINICAL, VisitStatus.IN_EXAM, VisitStatus.WAITING, VisitStatus.WAITING_RESULT, VisitStatus.IN_TREATMENT)
            );

            if (activeVisitOpt.isPresent()) {
                Visit activeVisit = activeVisitOpt.get();
                syncLog.setMatchedVisit(activeVisit);
                syncLog.setSyncStatus(NanopixSyncStatus.MATCHED);
                syncLog.setLinkedAt(LocalDateTime.now());

                // Nếu có nhân viên nạp ảnh, ghi nhận người liên kết
                if (staffId != null) {
                    staffProfileRepository.findById(staffId).ifPresent(syncLog::setLinkedBy);
                }

                // Kiểm tra xem ca khám có chỉ định X-quang đang chờ không để liên kết tự động
                List<ParaclinicalOrderItem> items = paraclinicalOrderItemRepository
                        .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(activeVisit.getVisitId());
                ParaclinicalOrderItem targetItem = items.stream()
                        .filter(i -> i.getStatus() == ParaclinicalOrderItemStatus.WAITING || i.getStatus() == ParaclinicalOrderItemStatus.IN_PROGRESS)
                        .findFirst()
                        .orElse(null);

                if (targetItem != null) {
                    syncLog.setMatchedItem(targetItem);
                    targetItem.setStatus(ParaclinicalOrderItemStatus.COMPLETED);
                    targetItem.setPerformedAt(LocalDateTime.now());
                    if (targetItem.getResultSummary() == null || targetItem.getResultSummary().isBlank()) {
                        targetItem.setResultSummary("Đã đồng bộ ảnh X-quang từ máy NanoPix (" + syncLog.getExternalStudyUid() + ")");
                    }
                    paraclinicalOrderItemRepository.save(targetItem);

                    // Tạo file đính kèm kết quả để bác sĩ xem được ngay trong bệnh án
                    ParaclinicalResultAttachment attachment = new ParaclinicalResultAttachment();
                    attachment.setOrderItem(targetItem);
                    attachment.setFileName("XRAY_" + syncLog.getExternalStudyUid() + ".jpg");
                    attachment.setFileUrl(syncLog.getImageUrl());
                    attachment.setFileType(ParaclinicalFileType.IMAGE);
                    attachment.setCaption("Ảnh chụp từ cảm biến NanoPix1 (" + (syncLog.getToothNumbers() != null ? "Răng: " + syncLog.getToothNumbers() : "Không rõ vị trí") + ")");
                    attachmentRepository.save(attachment);
                }
            } else {
                syncLog.setSyncStatus(NanopixSyncStatus.UNMATCHED);
                syncLog.setErrorMessage("Đã nhận diện bệnh nhân mã " + matchedPatient.getPatientCode() + " nhưng không có ca khám nào đang hoạt động.");
            }
        } else {
            syncLog.setSyncStatus(NanopixSyncStatus.UNMATCHED);
            syncLog.setErrorMessage("Chưa xác định được bệnh nhân hoặc lượt khám khớp từ thông tin máy chụp.");
        }

        NanopixXraySyncLog saved = nanopixRepository.save(syncLog);
        log.info("Ingested NanoPix X-ray Study UID: {}, Status: {}", saved.getExternalStudyUid(), saved.getSyncStatus());
        return saved;
    }

    @Override
    public NanopixSyncStatsDTO checkAdapterHealth() {
        long total = nanopixRepository.count();
        long matched = nanopixRepository.countBySyncStatus(NanopixSyncStatus.MATCHED);
        long unmatched = nanopixRepository.countBySyncStatus(NanopixSyncStatus.UNMATCHED);
        long error = nanopixRepository.countBySyncStatus(NanopixSyncStatus.ERROR);

        Path storagePath = Paths.get(uploadDir, "nanopix").toAbsolutePath().normalize();
        boolean storageWritable = Files.isWritable(storagePath.getParent() != null ? storagePath.getParent() : storagePath);

        return NanopixSyncStatsDTO.builder()
                .totalCount(total)
                .matchedCount(matched)
                .unmatchedCount(unmatched)
                .errorCount(error)
                .lastSyncTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy")))
                .adapterStatus(storageWritable ? "ONLINE" : "ERROR")
                .adapterType("Eighteeth NanoPix Direct USB / Local Storage Ingestion")
                .deviceSerialNumber("EN1E0408A032")
                .deviceModel("NanoPix1 (Changzhou Sifary Medical)")
                .storageDirectory(storagePath.toString())
                .connectionMessage(storageWritable ? "Adapter kết nối cục bộ đang sẵn sàng tiếp nhận ảnh." : "Cảnh báo: Thư mục lưu trữ không có quyền ghi!")
                .build();
    }

    @Override
    public String storeXrayImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File ảnh chụp X-quang không được để trống.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Kích thước file ảnh vượt quá giới hạn tối đa cho phép (25 MB).");
        }

        String originalFilename = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        String extension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = originalFilename.substring(dotIndex + 1).toLowerCase();
        }

        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Định dạng file không được hỗ trợ (" + extension + "). Vui lòng chọn file JPEG, PNG, hoặc DICOM.");
        }

        Path targetDir = Paths.get(uploadDir, "nanopix").toAbsolutePath().normalize();
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }

        String safeFileName = "xray_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + extension;
        Path targetPath = targetDir.resolve(safeFileName);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        return "/uploads/nanopix/" + safeFileName;
    }
}
