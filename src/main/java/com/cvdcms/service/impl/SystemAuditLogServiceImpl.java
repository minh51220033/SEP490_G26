package com.cvdcms.service.impl;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.SystemAuditLog;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.SystemAuditLogRepository;
import com.cvdcms.service.SystemAuditLogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemAuditLogServiceImpl implements SystemAuditLogService {

    private final SystemAuditLogRepository auditRepo;
    private final BranchRepository branchRepo;
    private final StaffProfileRepository staffProfileRepo;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private static final Map<String, String> TABLE_LABELS = Map.ofEntries(
            Map.entry("patient_profiles", "Hồ sơ Bệnh nhân"),
            Map.entry("visits", "Lượt khám lâm sàng"),
            Map.entry("queue_tickets", "Hàng chờ & Điều phối"),
            Map.entry("invoices", "Hóa đơn viện phí"),
            Map.entry("operating_expenses", "Chi phí vận hành"),
            Map.entry("leave_requests", "Đơn nghỉ phép"),
            Map.entry("users", "Tài khoản người dùng"),
            Map.entry("staff_profiles", "Hồ sơ nhân sự"),
            Map.entry("services", "Danh mục dịch vụ"),
            Map.entry("staff_schedules", "Lịch trực nhân viên"),
            Map.entry("clinical_workflow_templates", "Mẫu quy trình khám"),
            Map.entry("materials", "Vật tư kho"),
            Map.entry("bans", "Lệnh cấm tài khoản"),
            Map.entry("news_articles", "Bài viết tin tức"),
            Map.entry("comments", "Bình luận tin tức"),
            Map.entry("comment_bans", "Lệnh cấm bình luận")
    );

    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("amount", "Số tiền (VNĐ)"),
            Map.entry("category", "Danh mục"),
            Map.entry("recipientName", "Người nhận / Đơn vị"),
            Map.entry("paymentMethod", "Phương thức thanh toán"),
            Map.entry("description", "Mô tả / Diễn giải"),
            Map.entry("editReason", "Lý do chỉnh sửa"),
            Map.entry("expenseDate", "Ngày phát sinh"),
            Map.entry("phoneNumber", "Số điện thoại"),
            Map.entry("fullName", "Họ và tên"),
            Map.entry("address", "Địa chỉ"),
            Map.entry("email", "Email liên hệ"),
            Map.entry("status", "Trạng thái"),
            Map.entry("queueStatus", "Trạng thái hàng chờ"),
            Map.entry("reason", "Lý do thao tác"),
            Map.entry("role", "Vai trò"),
            Map.entry("branchId", "Mã chi nhánh"),
            Map.entry("doctorId", "Mã bác sĩ"),
            Map.entry("chairName", "Ghế điều trị"),
            Map.entry("roomName", "Phòng khám")
    );

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponseDTO> getAuditLogsPage(AuditLogFilterDTO filter) {
        int page = Math.max(filter.getPage(), 0);
        int size = filter.getSize() > 0 ? Math.min(filter.getSize(), 100) : 10;
        Pageable pageable = PageRequest.of(page, size);

        LocalDateTime startDate = filter.getFromDate() != null ? filter.getFromDate().atStartOfDay() : null;
        LocalDateTime endDate = filter.getToDate() != null ? filter.getToDate().atTime(LocalTime.MAX) : null;
        String keyword = filter.getKeyword() != null ? filter.getKeyword().trim() : null;
        String action = filter.getAction() != null && !filter.getAction().isBlank() ? filter.getAction().trim() : null;
        String entityTable = filter.getEntityTable() != null && !filter.getEntityTable().isBlank() ? filter.getEntityTable().trim() : null;

        Page<SystemAuditLog> logsPage = auditRepo.findFilteredLogs(
                filter.getBranchId(),
                filter.getUserId(),
                action,
                entityTable,
                startDate,
                endDate,
                keyword,
                pageable
        );

        return logsPage.map(this::mapToResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditLogDetailDTO getAuditLogDetail(Long logId) {
        SystemAuditLog logEntity = auditRepo.findById(logId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhật ký kiểm toán ID: " + logId));

        AuditLogDetailDTO.AuditLogDetailDTOBuilder builder = AuditLogDetailDTO.builder()
                .logId(logEntity.getLogId())
                .action(logEntity.getAction())
                .entityTable(logEntity.getEntityTable())
                .entityTableLabel(formatTableLabel(logEntity.getEntityTable()))
                .entityId(logEntity.getEntityId())
                .ipAddress(logEntity.getIpAddress() != null ? logEntity.getIpAddress() : "127.0.0.1")
                .formattedCreatedAt(logEntity.getCreatedAt() != null ? logEntity.getCreatedAt().format(DT_FORMATTER) : "N/A")
                .actionBadgeClass(determineBadgeClass(logEntity.getAction()))
                .actionIcon(determineIcon(logEntity.getAction()));

        if (logEntity.getBranch() != null) {
            builder.branchId(logEntity.getBranch().getBranchId());
            builder.branchName(logEntity.getBranch().getBranchName());
        } else {
            builder.branchName("Toàn hệ thống");
        }

        if (logEntity.getUser() != null) {
            User u = logEntity.getUser();
            builder.userId(u.getUserId());
            builder.username(u.getUsername());
            builder.userEmail(u.getEmail());
            builder.userRoleName(determineUserRoleName(u));
            builder.userFullName(resolveUserFullName(u));
        } else {
            builder.username("SYSTEM");
            builder.userFullName("Hệ thống tự động");
            builder.userRoleName("Tự động (Cron/Service)");
        }

        String oldRaw = logEntity.getOldValuesJson();
        String newRaw = logEntity.getNewValuesJson();
        builder.oldValuesJsonRaw(oldRaw);
        builder.newValuesJsonRaw(newRaw);

        Map<String, Object> oldMap = parseJsonSafe(oldRaw);
        Map<String, Object> newMap = parseJsonSafe(newRaw);

        builder.oldValuesJsonPretty(prettifyJson(oldMap));
        builder.newValuesJsonPretty(prettifyJson(newMap));

        List<AuditFieldDiffDTO> diffs = buildFieldDiffs(oldMap, newMap);
        builder.fieldDiffs(diffs);

        String act = logEntity.getAction() != null ? logEntity.getAction().toUpperCase() : "";
        builder.isCreate(act.startsWith("CREATE") || act.contains("ADD") || act.contains("INSERT"));
        builder.isDelete(act.startsWith("DELETE") || act.contains("REMOVE") || act.contains("CANCEL"));
        builder.isUpdate(act.startsWith("UPDATE") || act.contains("EDIT") || act.contains("CHANGE"));

        return builder.build();
    }

    @Override
    @Transactional(readOnly = true)
    public AuditSummaryStatsDTO getSummaryStats() {
        LocalDateTime startToday = LocalDate.now().atStartOfDay();
        LocalDateTime endToday = LocalDate.now().atTime(LocalTime.MAX);

        return AuditSummaryStatsDTO.builder()
                .totalLogs(auditRepo.count())
                .todayLogs(auditRepo.countByCreatedAtBetween(startToday, endToday))
                .createLogs(auditRepo.countCreateActions())
                .updateLogs(auditRepo.countUpdateActions())
                .sensitiveLogs(auditRepo.countSensitiveActions())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getDistinctActions() {
        return auditRepo.findDistinctActions();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getDistinctEntityTables() {
        return auditRepo.findDistinctEntityTables();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Branch> getAllBranches() {
        return branchRepo.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> getEntityTableLabels() {
        List<String> tables = getDistinctEntityTables();
        Map<String, String> map = new LinkedHashMap<>();
        for (String tbl : tables) {
            map.put(tbl, formatTableLabel(tbl));
        }
        return map;
    }

    private AuditLogResponseDTO mapToResponseDTO(SystemAuditLog logEntity) {
        String fullName = "Hệ thống";
        String username = "SYSTEM";
        String roleName = "Tự động";
        String avatar = "S";
        Long uId = null;

        if (logEntity.getUser() != null) {
            User u = logEntity.getUser();
            uId = u.getUserId();
            username = u.getUsername();
            fullName = resolveUserFullName(u);
            roleName = determineUserRoleName(u);
            avatar = extractAvatarInitials(fullName != null ? fullName : username);
        }

        String branchName = logEntity.getBranch() != null ? logEntity.getBranch().getBranchName() : "Toàn hệ thống";
        Long branchId = logEntity.getBranch() != null ? logEntity.getBranch().getBranchId() : null;

        boolean hasChanges = (logEntity.getOldValuesJson() != null && !logEntity.getOldValuesJson().isBlank())
                || (logEntity.getNewValuesJson() != null && !logEntity.getNewValuesJson().isBlank());

        return AuditLogResponseDTO.builder()
                .logId(logEntity.getLogId())
                .userId(uId)
                .username(username)
                .userFullName(fullName)
                .userRoleName(roleName)
                .userAvatarText(avatar)
                .branchId(branchId)
                .branchName(branchName)
                .action(logEntity.getAction())
                .actionBadgeClass(determineBadgeClass(logEntity.getAction()))
                .actionIcon(determineIcon(logEntity.getAction()))
                .entityTable(logEntity.getEntityTable())
                .entityTableLabel(formatTableLabel(logEntity.getEntityTable()))
                .entityId(logEntity.getEntityId())
                .oldValuesJson(logEntity.getOldValuesJson())
                .newValuesJson(logEntity.getNewValuesJson())
                .hasChanges(hasChanges)
                .ipAddress(logEntity.getIpAddress() != null ? logEntity.getIpAddress() : "127.0.0.1")
                .createdAt(logEntity.getCreatedAt())
                .formattedCreatedAt(logEntity.getCreatedAt() != null ? logEntity.getCreatedAt().format(DT_FORMATTER) : "N/A")
                .timeAgo(formatTimeAgo(logEntity.getCreatedAt()))
                .build();
    }

    private String resolveUserFullName(User user) {
        if (user == null) return "Hệ thống";
        Optional<StaffProfile> sp = staffProfileRepo.findByUser_UserId(user.getUserId());
        if (sp.isPresent() && sp.get().getFullName() != null && !sp.get().getFullName().isBlank()) {
            return sp.get().getFullName();
        }
        if (user.getUsername() != null && user.getUsername().equalsIgnoreCase("admin")) {
            return "Quản trị viên Hệ thống";
        }
        return user.getUsername();
    }

    private String determineUserRoleName(User user) {
        if (user == null) return "Hệ thống";
        Optional<StaffProfile> sp = staffProfileRepo.findByUser_UserId(user.getUserId());
        if (sp.isPresent() && sp.get().getPositionTitle() != null && !sp.get().getPositionTitle().isBlank()) {
            return sp.get().getPositionTitle();
        }
        if (user.getUsername() != null && user.getUsername().equalsIgnoreCase("admin")) {
            return "Quản trị viên";
        }
        return "Nhân viên";
    }

    private String extractAvatarInitials(String name) {
        if (name == null || name.isBlank()) return "U";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
    }

    private String formatTableLabel(String tableName) {
        if (tableName == null || tableName.isBlank()) return "Dữ liệu hệ thống";
        return TABLE_LABELS.getOrDefault(tableName.toLowerCase(), tableName);
    }

    private String formatFieldLabel(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return "";
        return FIELD_LABELS.getOrDefault(fieldName, fieldName);
    }

    private String determineBadgeClass(String action) {
        if (action == null) return "secondary";
        String act = action.toUpperCase();
        if (act.startsWith("CREATE") || act.contains("ADD") || act.contains("INSERT")) {
            return "success";
        }
        if (act.startsWith("UPDATE") || act.contains("EDIT") || act.contains("CHANGE")) {
            return "primary";
        }
        if (act.startsWith("DELETE") || act.contains("REMOVE") || act.contains("BAN")) {
            return "danger";
        }
        if (act.contains("CANCEL") || act.contains("REJECT") || act.contains("REFUND")) {
            return "warning";
        }
        if (act.contains("DISPATCH") || act.contains("CALL") || act.contains("CHECK_IN")) {
            return "info";
        }
        return "secondary";
    }

    private String determineIcon(String action) {
        if (action == null) return "bi-activity";
        String act = action.toUpperCase();
        if (act.startsWith("CREATE") || act.contains("ADD") || act.contains("INSERT")) {
            return "bi-plus-circle-fill";
        }
        if (act.startsWith("UPDATE") || act.contains("EDIT") || act.contains("CHANGE")) {
            return "bi-pencil-square";
        }
        if (act.startsWith("DELETE") || act.contains("REMOVE") || act.contains("BAN")) {
            return "bi-trash3-fill";
        }
        if (act.contains("CANCEL") || act.contains("REJECT") || act.contains("REFUND")) {
            return "bi-arrow-counterclockwise";
        }
        if (act.contains("DISPATCH") || act.contains("CALL")) {
            return "bi-megaphone-fill";
        }
        if (act.contains("CHECK_IN")) {
            return "bi-box-arrow-in-right";
        }
        return "bi-journal-check";
    }

    private String formatTimeAgo(LocalDateTime dt) {
        if (dt == null) return "";
        Duration d = Duration.between(dt, LocalDateTime.now());
        long seconds = d.getSeconds();
        if (seconds < 60) return "Vừa xong";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " phút trước";
        long hours = minutes / 60;
        if (hours < 24) return hours + " giờ trước";
        long days = hours / 24;
        if (days < 30) return days + " ngày trước";
        return dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private Map<String, Object> parseJsonSafe(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.debug("JSON parse note: {}", e.getMessage());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("raw_content", json);
            return map;
        }
    }

    private String prettifyJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);
        } catch (Exception e) {
            return map.toString();
        }
    }

    private List<AuditFieldDiffDTO> buildFieldDiffs(Map<String, Object> oldMap, Map<String, Object> newMap) {
        List<AuditFieldDiffDTO> diffs = new ArrayList<>();
        Set<String> allKeys = new LinkedHashSet<>();
        if (oldMap != null) allKeys.addAll(oldMap.keySet());
        if (newMap != null) allKeys.addAll(newMap.keySet());

        for (String key : allKeys) {
            Object oldVal = oldMap != null ? oldMap.get(key) : null;
            Object newVal = newMap != null ? newMap.get(key) : null;

            String oldStr = oldVal != null ? String.valueOf(oldVal) : null;
            String newStr = newVal != null ? String.valueOf(newVal) : null;

            String diffType;
            if (oldVal == null && newVal != null) {
                diffType = "ADDED";
            } else if (oldVal != null && newVal == null) {
                diffType = "REMOVED";
            } else if (Objects.equals(oldStr, newStr)) {
                diffType = "UNCHANGED";
            } else {
                diffType = "MODIFIED";
            }

            diffs.add(AuditFieldDiffDTO.builder()
                    .fieldName(key)
                    .fieldLabel(formatFieldLabel(key))
                    .oldValue(oldStr != null ? oldStr : "—")
                    .newValue(newStr != null ? newStr : "—")
                    .diffType(diffType)
                    .build());
        }

        diffs.sort((a, b) -> {
            int scoreA = "MODIFIED".equals(a.getDiffType()) ? 1 : "ADDED".equals(a.getDiffType()) ? 2 : "REMOVED".equals(a.getDiffType()) ? 3 : 4;
            int scoreB = "MODIFIED".equals(b.getDiffType()) ? 1 : "ADDED".equals(b.getDiffType()) ? 2 : "REMOVED".equals(b.getDiffType()) ? 3 : 4;
            return Integer.compare(scoreA, scoreB);
        });

        return diffs;
    }

    @Override
    public void logAction(User user, Branch branch, String action, String entityTable, Long entityId, String ipAddress, Object oldVal, Object newVal) {
        try {
            SystemAuditLog logEntry = new SystemAuditLog();
            logEntry.setUser(user);
            logEntry.setBranch(branch != null ? branch : (user != null ? user.getBranch() : null));
            logEntry.setAction(action);
            logEntry.setEntityTable(entityTable);
            logEntry.setEntityId(entityId);
            logEntry.setIpAddress(ipAddress != null ? ipAddress : "127.0.0.1");
            logEntry.setOldValuesJson(oldVal != null ? (oldVal instanceof String ? (String) oldVal : objectMapper.writeValueAsString(oldVal)) : null);
            logEntry.setNewValuesJson(newVal != null ? (newVal instanceof String ? (String) newVal : objectMapper.writeValueAsString(newVal)) : null);
            auditRepo.save(logEntry);
        } catch (Exception e) {
            log.error("Failed to write audit log: {}", e.getMessage(), e);
        }
    }
}
