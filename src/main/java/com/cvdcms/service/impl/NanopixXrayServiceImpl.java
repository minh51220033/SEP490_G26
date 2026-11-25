package com.cvdcms.service.impl;

import com.cvdcms.DTO.BranchOptionDTO;
import com.cvdcms.DTO.NanopixReconciliationRequestDTO;
import com.cvdcms.DTO.NanopixSyncRequestDTO;
import com.cvdcms.DTO.NanopixSyncStatsDTO;
import com.cvdcms.DTO.NanopixXrayResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.NanopixSyncStatus;
import com.cvdcms.entity.enums.ParaclinicalFileType;
import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;
import com.cvdcms.entity.enums.ParaclinicalOrderStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.NanoPixIntegrationService;
import com.cvdcms.service.NanopixXrayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NanopixXrayServiceImpl implements NanopixXrayService {

    private final NanopixXraySyncLogRepository nanopixRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final VisitRepository visitRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final ParaclinicalOrderItemRepository paraclinicalOrderItemRepository;
    private final ParaclinicalResultAttachmentRepository attachmentRepository;
    private final UserRepository userRepository;
    private final NanoPixIntegrationService nanoPixIntegrationService;
    private final BranchRepository branchRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.EmrRecordRepository emrRecordRepository;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    @Transactional(readOnly = true)
    public Page<NanopixXrayResponseDTO> getXrays(String statusStr, String keyword, LocalDate fromDate, LocalDate toDate, Long branchId, Pageable pageable, Long userId) {
        String role = requireAuthorizedRole(userId);
        NanopixSyncStatus status = null;
        if (StringUtils.hasText(statusStr) && !"ALL".equalsIgnoreCase(statusStr)) {
            try {
                status = NanopixSyncStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        LocalDateTime fromTime = fromDate != null ? fromDate.atStartOfDay() : null;
        LocalDateTime toTime = toDate != null ? toDate.atTime(LocalTime.MAX) : null;
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;

        // Phân quyền giới hạn theo cơ sở:
        // Admin: có thể xem tất cả (branchId = null) hoặc lọc theo một cơ sở cụ thể
        // Bác sĩ / Lễ tân: bắt buộc chỉ xem dữ liệu thuộc cơ sở được phân công
        Long effectiveBranchId = branchId;
        if (!"ADMIN".equals(role)) {
            List<Long> allowedIds = resolveUserAccessibleBranchIds(userId);
            if (effectiveBranchId != null) {
                if (!allowedIds.contains(effectiveBranchId)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Bạn không có quyền xem dữ liệu X-quang của cơ sở khác.");
                }
            } else if (allowedIds.isEmpty()) {
                return Page.empty(pageable);
            } else if (allowedIds.size() == 1) {
                effectiveBranchId = allowedIds.get(0);
            } else {
                Page<NanopixXraySyncLog> allPage = nanopixRepository.findWithFilters(status, kw, fromTime, toTime, null, Pageable.unpaged());
                List<NanopixXraySyncLog> filtered = (allPage != null ? allPage.getContent() : List.<NanopixXraySyncLog>of()).stream()
                        .filter(n -> {
                            Long bId = resolveSyncLogBranchId(n);
                            return bId != null && allowedIds.contains(bId);
                        })
                        .toList();
                int total = filtered.size();
                int start = pageable != null && pageable.isPaged() ? (int) Math.min(pageable.getOffset(), total) : 0;
                int end = pageable != null && pageable.isPaged() ? Math.min(start + pageable.getPageSize(), total) : total;
                List<NanopixXrayResponseDTO> content = filtered.subList(start, end).stream()
                        .map(this::toResponseDTO)
                        .toList();
                return new PageImpl<>(content, pageable != null ? pageable : Pageable.unpaged(), total);
            }
        }

        Page<NanopixXraySyncLog> page = nanopixRepository.findWithFilters(status, kw, fromTime, toTime, effectiveBranchId, pageable);
        return page.map(this::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NanopixXrayResponseDTO> getUnmatchedXrays(String keyword, Long userId) {
        String role = requireAuthorizedRole(userId);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        if (!"ADMIN".equals(role)) {
            List<Long> allowedIds = resolveUserAccessibleBranchIds(userId);
            if (allowedIds.isEmpty()) {
                return List.of();
            }
            if (allowedIds.size() == 1) {
                List<NanopixXraySyncLog> list = nanopixRepository.findUnmatchedWithFilter(NanopixSyncStatus.UNMATCHED, kw, allowedIds.get(0));
                return list.stream().map(this::toResponseDTO).collect(Collectors.toList());
            }
            List<NanopixXraySyncLog> list = nanopixRepository.findUnmatchedWithFilter(NanopixSyncStatus.UNMATCHED, kw, null);
            return list.stream()
                    .filter(n -> {
                        Long bId = resolveSyncLogBranchId(n);
                        return bId != null && allowedIds.contains(bId);
                    })
                    .map(this::toResponseDTO)
                    .collect(Collectors.toList());
        }
        List<NanopixXraySyncLog> list = nanopixRepository.findUnmatchedWithFilter(NanopixSyncStatus.UNMATCHED, kw, null);
        return list.stream().map(this::toResponseDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public NanopixXrayResponseDTO getXrayDetail(Long syncLogId, Long userId) {
        String role = requireAuthorizedRole(userId);
        NanopixXraySyncLog syncLog = nanopixRepository.findByIdWithDetails(syncLogId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kết quả X-quang mã: " + syncLogId));
        if (!"ADMIN".equals(role)) {
            List<Long> allowedIds = resolveUserAccessibleBranchIds(userId);
            Long logBranchId = resolveSyncLogBranchId(syncLog);
            if (allowedIds.isEmpty() || (logBranchId != null && !allowedIds.contains(logBranchId))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Bạn không có quyền xem phim X-quang thuộc cơ sở khác.");
            }
        }
        return toResponseDTO(syncLog);
    }

    @Override
    @Transactional
    public NanopixXrayResponseDTO reconcileXray(Long syncLogId, NanopixReconciliationRequestDTO dto, Long userId) {
        String role = requireAuthorizedRole(userId);
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu đối chiếu không được để trống.");
        }
        if (dto.getProfileId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn hồ sơ bệnh nhân cần liên kết.");
        }
        if (dto.getVisitId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn ca khám tương ứng của bệnh nhân.");
        }

        NanopixXraySyncLog syncLog = nanopixRepository.findById(syncLogId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi phim X-quang ID: " + syncLogId));

        PatientProfile patient = patientProfileRepository.findById(dto.getProfileId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân ID: " + dto.getProfileId()));

        Visit visit = visitRepository.findById(dto.getVisitId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca khám ID: " + dto.getVisitId()));

        // Kiểm tra tính toàn vẹn nghiệp vụ: Lượt khám phải thuộc đúng bệnh nhân được chọn và chưa bị khóa/hủy
        if (visit.getPatientProfile() == null || !visit.getPatientProfile().getProfileId().equals(patient.getProfileId())) {
            throw new IllegalArgumentException("Lượt khám mã " + visit.getVisitCode() + " không thuộc về bệnh nhân " + patient.getFullName());
        }
        com.cvdcms.util.EmrStatePolicy.assertMutableVisitAndEmr(
                visit,
                emrRecordRepository,
                "Lượt khám đã hoàn tất/chờ thanh toán/hủy hoặc EMR đã khóa. Không thể đối chiếu phim X-quang");

        // Kiểm tra giới hạn cơ sở: Bác sĩ / Lễ tân chỉ được liên kết phim & ca khám thuộc cơ sở mình đang làm việc
        if (!"ADMIN".equals(role)) {
            List<Long> allowedIds = resolveUserAccessibleBranchIds(userId);
            if (allowedIds.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Tài khoản của bạn chưa được phân công cơ sở làm việc.");
            }
            Long existingLogBranchId = resolveSyncLogBranchId(syncLog);
            if (existingLogBranchId != null && !allowedIds.contains(existingLogBranchId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Bạn không có quyền đối chiếu phim X-quang thuộc cơ sở khác.");
            }
            if (visit.getBranch() != null && visit.getBranch().getBranchId() != null
                    && !allowedIds.contains(visit.getBranch().getBranchId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Bạn không có quyền thao tác trên ca khám thuộc cơ sở khác (" + visit.getBranch().getBranchName() + ").");
            }
        }

        ParaclinicalOrderItem previousItem = syncLog.getMatchedItem();

        StaffProfile staff = null;
        if (userId != null) {
            staff = staffProfileRepository.findByUser_UserId(userId).orElse(null);
        }

        // Cập nhật thông tin liên kết
        syncLog.setMatchedProfile(patient);
        syncLog.setMatchedVisit(visit);
        if (visit.getBranch() != null) {
            syncLog.setBranch(visit.getBranch());
        }
        syncLog.setToothNumbers(StringUtils.hasText(dto.getToothNumbers()) ? dto.getToothNumbers().trim() : syncLog.getToothNumbers());
        if (StringUtils.hasText(dto.getNotes())) {
            syncLog.setNotes(dto.getNotes().trim());
        }
        syncLog.setIsManualLinked(true);
        syncLog.setLinkedBy(staff);
        syncLog.setLinkedAt(LocalDateTime.now());
        syncLog.setSyncStatus(NanopixSyncStatus.MATCHED);
        syncLog.setErrorMessage(null);

        // Xác định chỉ định cận lâm sàng X-quang cần gắn
        ParaclinicalOrderItem orderItem = null;
        if (dto.getOrderItemId() != null && dto.getOrderItemId() > 0) {
            orderItem = paraclinicalOrderItemRepository.findById(dto.getOrderItemId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chỉ định CLS ID: " + dto.getOrderItemId()));

            // Kiểm tra chỉ định phải thuộc ca khám đã chọn
            if (orderItem.getOrder() == null || orderItem.getOrder().getVisit() == null
                    || !orderItem.getOrder().getVisit().getVisitId().equals(visit.getVisitId())) {
                throw new IllegalArgumentException("Chỉ định cận lâm sàng không thuộc lượt khám đã chọn.");
            }
        } else {
            // Nếu người dùng không chọn cụ thể chỉ định nhưng ca khám có đúng 1 chỉ định đang chờ thì tự động gắn
            List<ParaclinicalOrderItem> visitItems = paraclinicalOrderItemRepository
                    .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(visit.getVisitId());
            if (visitItems != null) {
                List<ParaclinicalOrderItem> waitingItems = visitItems.stream()
                        .filter(i -> i.getStatus() == ParaclinicalOrderItemStatus.WAITING || i.getStatus() == ParaclinicalOrderItemStatus.IN_PROGRESS)
                        .toList();
                if (waitingItems.size() == 1) {
                    orderItem = waitingItems.get(0);
                }
            }
        }

        // Nếu đang đổi liên kết từ chỉ định cũ sang chỉ định khác (hoặc gỡ liên kết), hoàn tác chỉ định cũ
        if (previousItem != null && (orderItem == null || !Objects.equals(previousItem.getOrderItemId(), orderItem.getOrderItemId()))) {
            unlinkPreviousOrderItem(previousItem, syncLog);
        }

        if (orderItem != null) {
            syncLog.setMatchedItem(orderItem);
            orderItem.setStatus(ParaclinicalOrderItemStatus.COMPLETED);
            orderItem.setPerformedAt(LocalDateTime.now());
            if (!StringUtils.hasText(orderItem.getExternalRefId()) && orderItem.getOrderItemId() != null) {
                orderItem.setExternalRefId("XQ" + orderItem.getOrderItemId());
            }
            if (!StringUtils.hasText(orderItem.getResultSummary())) {
                orderItem.setResultSummary("Đã đối chiếu ảnh X-quang NanoPix (" + syncLog.getExternalStudyUid() + ")");
            }
            paraclinicalOrderItemRepository.save(orderItem);

            // Cập nhật trạng thái phiếu chỉ định cha & lượt khám đồng nhất với NanoPixIntegrationServiceImpl
            ParaclinicalOrder parentOrder = orderItem.getOrder();
            if (parentOrder != null) {
                List<ParaclinicalOrderItem> allItems = paraclinicalOrderItemRepository.findByOrder_OrderId(parentOrder.getOrderId());
                if (allItems != null && !allItems.isEmpty()) {
                    List<ParaclinicalOrderItem> activeItems = allItems.stream()
                            .filter(i -> i.getStatus() != ParaclinicalOrderItemStatus.CANCELLED)
                            .toList();
                    boolean allCompleted = !activeItems.isEmpty()
                            && activeItems.stream().allMatch(i -> i.getStatus() == ParaclinicalOrderItemStatus.COMPLETED);
                    if (allCompleted) {
                        parentOrder.setStatus(ParaclinicalOrderStatus.COMPLETED);
                        parentOrder.setCompletedAt(LocalDateTime.now());
                        if (visit.getStatus() == VisitStatus.WAITING_PARACLINICAL) {
                            visit.setStatus(VisitStatus.WAITING_RESULT);
                            visitRepository.save(visit);
                        }
                    } else if (parentOrder.getStatus() == ParaclinicalOrderStatus.ORDERED) {
                        parentOrder.setStatus(ParaclinicalOrderStatus.IN_PROGRESS);
                    }
                }
            }

            // Gắn file đính kèm kết quả để bác sĩ xem được trong màn hình ca khám (tránh tạo trùng khi đối chiếu lại cùng item)
            String expectedFileName = "XRAY_" + syncLog.getExternalStudyUid() + ".jpg";
            String captionText = "Ảnh X-quang NanoPix1 đối chiếu thủ công (Răng: " +
                    (syncLog.getToothNumbers() != null ? syncLog.getToothNumbers() : "Không rõ") + ")";
            List<ParaclinicalResultAttachment> existingAttachments = orderItem.getOrderItemId() != null
                    ? attachmentRepository.findByOrderItem_OrderItemId(orderItem.getOrderItemId())
                    : List.of();
            ParaclinicalResultAttachment matchingAttachment = existingAttachments == null ? null : existingAttachments.stream()
                    .filter(att -> (syncLog.getImageUrl() != null && syncLog.getImageUrl().equals(att.getFileUrl()))
                            || expectedFileName.equals(att.getFileName()))
                    .findFirst()
                    .orElse(null);

            if (matchingAttachment != null) {
                matchingAttachment.setCaption(captionText);
                attachmentRepository.save(matchingAttachment);
            } else {
                ParaclinicalResultAttachment attachment = new ParaclinicalResultAttachment();
                attachment.setOrderItem(orderItem);
                attachment.setFileName(expectedFileName);
                attachment.setFileUrl(syncLog.getImageUrl());
                attachment.setFileType(ParaclinicalFileType.IMAGE);
                attachment.setCaption(captionText);
                attachmentRepository.save(attachment);
                if (orderItem.getAttachments() != null && !orderItem.getAttachments().contains(attachment)) {
                    orderItem.getAttachments().add(attachment);
                }
            }
        } else {
            syncLog.setMatchedItem(null);
        }

        NanopixXraySyncLog saved = nanopixRepository.save(syncLog);
        log.info("X-ray manual reconciliation completed for UID: {}, Profile: {}, Visit: {}, by User: {}",
                saved.getExternalStudyUid(), patient.getProfileId(), visit.getVisitId(), userId);

        return toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public NanopixSyncStatsDTO getSyncStatistics(Long userId) {
        requireAuthorizedRole(userId);
        return nanoPixIntegrationService.checkAdapterHealth();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getReconciliationLookups(Long profileId) {
        if (profileId == null) {
            return Map.of("visits", Collections.emptyList(), "orderItems", Collections.emptyList());
        }

        List<Visit> visits = visitRepository.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(profileId);
        List<Map<String, Object>> visitList = visits.stream().map(v -> {
            Map<String, Object> m = new HashMap<>();
            m.put("visitId", v.getVisitId());
            m.put("visitCode", v.getVisitCode());
            m.put("checkInAt", v.getCheckInAt() != null ? v.getCheckInAt().format(DATE_TIME_FORMATTER) : "Chưa có ngày");
            m.put("status", v.getStatus() != null ? v.getStatus().name() : "");
            m.put("branchId", v.getBranch() != null ? v.getBranch().getBranchId() : null);
            m.put("branchName", v.getBranch() != null ? v.getBranch().getBranchName() : "");
            return m;
        }).collect(Collectors.toList());

        List<ParaclinicalOrderItem> items = new ArrayList<>();
        for (Visit v : visits) {
            List<ParaclinicalOrderItem> visitItems = paraclinicalOrderItemRepository
                    .findByOrder_Visit_VisitIdOrderByOrder_OrderIdDescOrderItemIdDesc(v.getVisitId());
            if (visitItems != null) {
                items.addAll(visitItems);
            }
        }

        List<Map<String, Object>> itemList = items.stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            Visit itemVisit = item.getOrder() != null ? item.getOrder().getVisit() : null;
            m.put("orderItemId", item.getOrderItemId());
            m.put("visitId", itemVisit != null ? itemVisit.getVisitId() : null);
            m.put("branchId", itemVisit != null && itemVisit.getBranch() != null ? itemVisit.getBranch().getBranchId() : null);
            m.put("externalRefId", item.getExternalRefId() != null ? item.getExternalRefId() : ("XQ" + item.getOrderItemId()));
            m.put("toothNumbers", item.getToothNumbers());
            m.put("serviceName", item.getService() != null ? item.getService().getServiceName() : "Chụp X-quang");
            m.put("status", item.getStatus() != null ? item.getStatus().name() : "");
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("visits", visitList);
        result.put("orderItems", itemList);
        return result;
    }

    @Override
    @Transactional
    public NanopixXrayResponseDTO ingestXray(NanopixSyncRequestDTO request, MultipartFile file, Long userId) throws IOException {
        if (userId != null) {
            requireAuthorizedRole(userId);
        }
        StaffProfile staff = null;
        if (userId != null) {
            staff = staffProfileRepository.findByUser_UserId(userId).orElse(null);
        }
        Long staffId = staff != null ? staff.getStaffId() : null;

        NanopixXraySyncLog saved = nanoPixIntegrationService.ingestXray(request, file, staffId);
        return toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAccessibleBranches(Long userId) {
        String role = requireAuthorizedRole(userId);
        List<Branch> allBranches = branchRepository.findByIsActiveTrueOrderByBranchNameAsc();

        List<BranchOptionDTO> options;
        if ("ADMIN".equals(role)) {
            options = allBranches.stream()
                    .map(b -> new BranchOptionDTO(b.getBranchId(), b.getBranchCode(), b.getBranchName()))
                    .toList();
        } else {
            List<Long> allowedIds = resolveUserAccessibleBranchIds(userId);
            options = allBranches.stream()
                    .filter(b -> allowedIds.contains(b.getBranchId()))
                    .map(b -> new BranchOptionDTO(b.getBranchId(), b.getBranchCode(), b.getBranchName()))
                    .toList();
        }

        Map<String, Object> res = new HashMap<>();
        res.put("role", role);
        res.put("isAdmin", "ADMIN".equals(role));
        res.put("branches", options);
        return res;
    }

    private Long resolveSyncLogBranchId(NanopixXraySyncLog syncLog) {
        if (syncLog == null) return null;
        if (syncLog.getBranch() != null && syncLog.getBranch().getBranchId() != null) {
            return syncLog.getBranch().getBranchId();
        }
        if (syncLog.getMatchedVisit() != null && syncLog.getMatchedVisit().getBranch() != null) {
            return syncLog.getMatchedVisit().getBranch().getBranchId();
        }
        return null;
    }

    private void unlinkPreviousOrderItem(ParaclinicalOrderItem previousItem, NanopixXraySyncLog syncLog) {
        if (previousItem == null || syncLog == null) {
            return;
        }
        String expectedFileName = "XRAY_" + syncLog.getExternalStudyUid() + ".jpg";
        List<ParaclinicalResultAttachment> currentAttachments = previousItem.getOrderItemId() != null
                ? attachmentRepository.findByOrderItem_OrderItemId(previousItem.getOrderItemId())
                : List.of();
        List<ParaclinicalResultAttachment> toRemove = new ArrayList<>();
        if (currentAttachments != null) {
            for (ParaclinicalResultAttachment att : currentAttachments) {
                boolean matchesUrl = syncLog.getImageUrl() != null && syncLog.getImageUrl().equals(att.getFileUrl());
                boolean matchesName = expectedFileName.equals(att.getFileName());
                if (matchesUrl || matchesName) {
                    toRemove.add(att);
                }
            }
        }
        if (!toRemove.isEmpty()) {
            if (previousItem.getAttachments() != null) {
                previousItem.getAttachments().removeAll(toRemove);
            }
            attachmentRepository.deleteAll(toRemove);
        }

        int remainingCount = currentAttachments != null ? currentAttachments.size() - toRemove.size() : 0;
        if (remainingCount <= 0 && previousItem.getStatus() == ParaclinicalOrderItemStatus.COMPLETED) {
            previousItem.setStatus(ParaclinicalOrderItemStatus.WAITING);
            previousItem.setPerformedAt(null);
            if (previousItem.getResultSummary() != null
                    && syncLog.getExternalStudyUid() != null
                    && previousItem.getResultSummary().contains(syncLog.getExternalStudyUid())) {
                previousItem.setResultSummary(null);
            }
            paraclinicalOrderItemRepository.save(previousItem);

            ParaclinicalOrder prevOrder = previousItem.getOrder();
            if (prevOrder != null) {
                List<ParaclinicalOrderItem> prevOrderItems = paraclinicalOrderItemRepository.findByOrder_OrderId(prevOrder.getOrderId());
                if (prevOrderItems != null && !prevOrderItems.isEmpty()) {
                    boolean anyCompletedOrInProgress = prevOrderItems.stream()
                            .anyMatch(i -> i.getStatus() == ParaclinicalOrderItemStatus.COMPLETED
                                    || i.getStatus() == ParaclinicalOrderItemStatus.IN_PROGRESS);
                    if (prevOrder.getStatus() == ParaclinicalOrderStatus.COMPLETED) {
                        prevOrder.setStatus(anyCompletedOrInProgress ? ParaclinicalOrderStatus.IN_PROGRESS : ParaclinicalOrderStatus.ORDERED);
                        prevOrder.setCompletedAt(null);
                    }
                }
                Visit prevVisit = prevOrder.getVisit();
                if (prevVisit != null && prevVisit.getStatus() == VisitStatus.WAITING_RESULT) {
                    prevVisit.setStatus(VisitStatus.WAITING_PARACLINICAL);
                    visitRepository.save(prevVisit);
                }
            }
        }
    }

    private List<Long> resolveUserAccessibleBranchIds(Long userId) {
        if (userId == null) return List.of();
        Set<Long> ids = new LinkedHashSet<>();
        if (staffBranchAssignmentRepository != null) {
            List<Long> branchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
            if (branchIds != null) {
                ids.addAll(branchIds);
            }
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && user.getBranch() != null && user.getBranch().getBranchId() != null) {
            ids.add(user.getBranch().getBranchId());
        }
        return new ArrayList<>(ids);
    }

    private Long resolveUserPrimaryBranchId(Long userId) {
        if (userId == null) return null;
        if (staffBranchAssignmentRepository != null) {
            List<Long> branchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
            if (branchIds != null && !branchIds.isEmpty()) {
                return branchIds.get(0);
            }
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && user.getBranch() != null) {
            return user.getBranch().getBranchId();
        }
        return null;
    }

    private String requireAuthorizedRole(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để thực hiện chức năng này.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tài khoản không tồn tại."));

        Set<String> roleNames = user.getRoles().stream()
                .map(r -> r.getRoleName() != null ? r.getRoleName().toUpperCase() : "")
                .collect(Collectors.toSet());

        if (roleNames.contains("ADMIN") || roleNames.contains("ROLE_ADMIN")) {
            return "ADMIN";
        }
        if (roleNames.contains("BAC_SI") || roleNames.contains("ROLE_BAC_SI")) {
            return "BAC_SI";
        }
        if (roleNames.contains("LE_TAN") || roleNames.contains("ROLE_LE_TAN")) {
            return "LE_TAN";
        }
        if (userRepository.hasActivePermission(userId, "CLINICAL_XRAY")) {
            return "STAFF";
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập chức năng Quản lý kết quả phim X-quang NanoPix (FT12).");
    }

    private NanopixXrayResponseDTO toResponseDTO(NanopixXraySyncLog entity) {
        if (entity == null) return null;

        String statusLabel = switch (entity.getSyncStatus()) {
            case MATCHED -> "Đã khớp hồ sơ";
            case UNMATCHED -> "Chưa khớp hồ sơ";
            case ERROR -> "Lỗi kết nối / đồng bộ";
        };

        PatientProfile p = entity.getMatchedProfile();
        Visit v = entity.getMatchedVisit();
        ParaclinicalOrderItem item = entity.getMatchedItem();
        StaffProfile staff = entity.getLinkedBy();
        Branch b = entity.getBranch() != null ? entity.getBranch() : (v != null ? v.getBranch() : null);

        return NanopixXrayResponseDTO.builder()
                .syncLogId(entity.getSyncLogId())
                .externalStudyUid(entity.getExternalStudyUid())
                .externalPatientId(entity.getExternalPatientId())
                .rawPatientName(entity.getRawPatientName())
                .imageUrl(entity.getImageUrl())
                .syncStatus(entity.getSyncStatus() != null ? entity.getSyncStatus().name() : "UNMATCHED")
                .syncStatusLabel(statusLabel)
                .profileId(p != null ? p.getProfileId() : null)
                .patientCode(p != null ? p.getPatientCode() : null)
                .patientName(p != null ? p.getFullName() : (entity.getRawPatientName() != null ? entity.getRawPatientName() : "Chưa xác định"))
                .patientPhone(p != null ? p.getPhoneNumber() : null)
                .patientDob(p != null && p.getDob() != null ? p.getDob().format(DATE_FORMATTER) : null)
                .visitId(v != null ? v.getVisitId() : null)
                .visitCode(v != null ? v.getVisitCode() : null)
                .visitDate(v != null && v.getCheckInAt() != null ? v.getCheckInAt().format(DATE_TIME_FORMATTER) : null)
                .branchId(b != null ? b.getBranchId() : null)
                .branchName(b != null ? b.getBranchName() : null)
                .orderItemId(item != null ? item.getOrderItemId() : null)
                .serviceName(item != null && item.getService() != null ? item.getService().getServiceName() : null)
                .toothNumbers(entity.getToothNumbers())
                .isManualLinked(entity.getIsManualLinked())
                .linkedByName(staff != null ? staff.getFullName() : null)
                .linkedAt(entity.getLinkedAt() != null ? entity.getLinkedAt().format(DATE_TIME_FORMATTER) : null)
                .capturedAt(entity.getCapturedAt())
                .capturedAtFormatted(entity.getCapturedAt() != null ? entity.getCapturedAt().format(DATE_TIME_FORMATTER) : "")
                .syncTime(entity.getSyncTime())
                .syncTimeFormatted(entity.getSyncTime() != null ? entity.getSyncTime().format(DATE_TIME_FORMATTER) : "")
                .deviceSerialNumber(entity.getDeviceSerialNumber())
                .deviceModel(entity.getDeviceModel())
                .errorMessage(entity.getErrorMessage())
                .notes(entity.getNotes())
                .build();
    }
}
