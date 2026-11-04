package com.cvdcms.config;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.SystemAuditLog;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.SystemAuditLogRepository;
import com.cvdcms.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
// @Component -- Tắt data seeder mẫu để chỉ hiển thị dữ liệu log thật phát sinh khi người dùng thao tác
@Order(100)
@RequiredArgsConstructor
public class SystemAuditLogDataInitializer implements ApplicationRunner {

    private final SystemAuditLogRepository auditRepo;
    private final UserRepository userRepo;
    private final BranchRepository branchRepo;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            if (auditRepo.count() > 0) {
                log.info("SystemAuditLogDataInitializer: Đã có {} bản ghi audit log, bỏ qua seeding.", auditRepo.count());
                return;
            }

            List<User> users = userRepo.findAll();
            if (users.isEmpty()) {
                log.warn("SystemAuditLogDataInitializer: Chưa có user trong hệ thống.");
                return;
            }

            User adminUser = users.stream().filter(u -> "admin".equalsIgnoreCase(u.getUsername())).findFirst().orElse(users.get(0));
            User staffUser1 = users.size() > 1 ? users.get(1) : adminUser;
            User staffUser2 = users.size() > 2 ? users.get(2) : adminUser;
            User doctorUser = users.stream().filter(u -> u.getUsername() != null && u.getUsername().contains("minh12345")).findFirst().orElse(staffUser1);

            List<Branch> branches = branchRepo.findAll();
            Branch branch1 = !branches.isEmpty() ? branches.get(0) : null;
            Branch branch2 = branches.size() > 1 ? branches.get(1) : branch1;

            LocalDateTime now = LocalDateTime.now();
            List<SystemAuditLog> logs = new ArrayList<>();

            // 1. CREATE_PATIENT
            logs.add(createLog(staffUser1, branch1, "CREATE_PATIENT", "patient_profiles", 101L,
                    null,
                    Map.of("patientCode", "BN-2026-0089", "fullName", "Nguyễn Văn An", "phoneNumber", "0912345678", "gender", "MALE", "yearOfBirth", 1990),
                    "192.168.1.45", now.minusMinutes(12)));

            // 2. UPDATE_PATIENT
            logs.add(createLog(staffUser1, branch1, "UPDATE_PATIENT", "patient_profiles", 101L,
                    Map.of("phoneNumber", "0912345678", "address", "Hà Nội"),
                    Map.of("phoneNumber", "0987654321", "address", "124 Phố Huế, Hai Bà Trưng, Hà Nội", "note", "Cập nhật số điện thoại chính"),
                    "192.168.1.45", now.minusMinutes(35)));

            // 3. CALL_PATIENT
            logs.add(createLog(staffUser2, branch1, "CALL_PATIENT", "queue_tickets", 501L,
                    Map.of("queueNumber", "KB-012", "queueStatus", "WAITING", "roomName", "Phòng chờ số 1"),
                    Map.of("queueNumber", "KB-012", "queueStatus", "CALLED", "roomName", "Phòng khám 02", "chairName", "Ghế nha số 1"),
                    "192.168.1.12", now.minusMinutes(58)));

            // 4. DISPATCH_CHAIR
            logs.add(createLog(staffUser2, branch1, "DISPATCH_CHAIR", "queue_tickets", 501L,
                    Map.of("queueStatus", "CALLED", "roomName", "Phòng khám 02"),
                    Map.of("queueStatus", "IN_SERVICE", "roomName", "Phòng thủ thuật Implant", "chairName", "Ghế VIP 01", "doctorId", "BS-001"),
                    "192.168.1.12", now.minusHours(1).minusMinutes(20)));

            // 5. UPDATE_MANUAL_EXPENSE
            logs.add(createLog(adminUser, branch1, "UPDATE_EXPENSE", "operating_expenses", 34L,
                    Map.of("amount", "1500000.00", "category", "Vật tư tiêu hao", "recipientName", "CTY Thiết Bị Á Châu", "paymentMethod", "BANK_TRANSFER"),
                    Map.of("amount", "2000000.00", "category", "Vật tư tiêu hao", "recipientName", "CTY Thiết Bị Á Châu", "paymentMethod", "BANK_TRANSFER", "editReason", "Bổ sung thêm 2 hộp găng tay và kim tiêm"),
                    "192.168.1.100", now.minusHours(2).minusMinutes(40)));

            // 6. APPROVE_LEAVE_REQUEST
            logs.add(createLog(adminUser, branch1, "APPROVE_LEAVE_REQUEST", "leave_requests", 12L,
                    Map.of("status", "PENDING", "doctorName", "Lâm Trấn Thương", "fromDate", "05/11/2026", "toDate", "06/11/2026"),
                    Map.of("status", "APPROVED", "doctorName", "Lâm Trấn Thương", "approvedBy", "Quản trị viên", "reviewNotes", "Đã rà soát không có lịch hẹn bệnh nhân bị trùng"),
                    "192.168.1.100", now.minusHours(4)));

            // 7. REFUND_INVOICE
            logs.add(createLog(adminUser, branch2, "REFUND_INVOICE", "invoices", 88L,
                    Map.of("invoiceCode", "HD-2026-0034", "totalAmount", "3500000.00", "status", "PAID"),
                    Map.of("invoiceCode", "HD-2026-0034", "refundAmount", "500000.00", "status", "PARTIALLY_REFUNDED", "refundReason", "Bệnh nhân hủy dịch vụ lấy cao răng sau khi nhổ răng"),
                    "192.168.2.15", now.minusHours(7)));

            // 8. UPDATE_STAFF_ROLE
            logs.add(createLog(adminUser, branch1, "UPDATE_STAFF", "staff_profiles", 22L,
                    Map.of("staffCode", "NV-008", "positionTitle", "Điều dưỡng viên", "department", "Nội nha"),
                    Map.of("staffCode", "NV-008", "positionTitle", "Điều dưỡng trưởng", "department", "Khám & Phẫu thuật", "effectiveDate", "01/11/2026"),
                    "192.168.1.100", now.minusDays(1).minusHours(2)));

            // 9. BAN_ACCOUNT
            logs.add(createLog(adminUser, null, "BAN_ACCOUNT", "users", 290L,
                    Map.of("username", "spam_user99", "status", "ACTIVE", "isActive", true),
                    Map.of("username", "spam_user99", "status", "BANNED", "isActive", false, "banReason", "Gửi tin nhắn spam quảng cáo trái quy định"),
                    "192.168.1.100", now.minusDays(2).minusHours(5)));

            // 10. CREATE_WORKFLOW_TEMPLATE
            logs.add(createLog(adminUser, branch1, "CREATE_WORKFLOW", "clinical_workflow_templates", 5L,
                    null,
                    Map.of("templateCode", "QTKT-IMPLANT-01", "templateName", "Quy trình Cấy ghép Implant đơn lẻ", "serviceId", 12L, "totalSteps", 6),
                    "192.168.1.100", now.minusDays(3).minusHours(1)));

            // 11. CHECK_IN_PATIENT
            logs.add(createLog(staffUser1, branch1, "CHECK_IN", "visits", 215L,
                    null,
                    Map.of("visitCode", "LK-2026-1102-01", "patientName", "Trần Đình Trọng", "reasonForVisit", "Đau răng số 8 hàm dưới", "visitType", "WALK_IN"),
                    "192.168.1.45", now.minusDays(4)));

            // 12. UPDATE_PROFILE_PASSWORD
            logs.add(createLog(doctorUser, branch1, "CHANGE_PASSWORD", "users", doctorUser.getUserId(),
                    Map.of("passwordChanged", false),
                    Map.of("passwordChanged", true, "method", "SELF_SERVICE", "channel", "WEB_PORTAL"),
                    "14.162.180.22", now.minusDays(5)));

            auditRepo.saveAll(logs);
            log.info("SystemAuditLogDataInitializer: Đã khởi tạo thành công {} bản ghi mẫu cho Nhật ký thao tác.", logs.size());
        } catch (Exception e) {
            log.error("Lỗi khởi tạo SystemAuditLogDataInitializer: {}", e.getMessage(), e);
        }
    }

    private SystemAuditLog createLog(User user, Branch branch, String action, String entityTable, Long entityId,
                                     Map<String, Object> oldVal, Map<String, Object> newVal,
                                     String ip, LocalDateTime createdAt) {
        SystemAuditLog log = new SystemAuditLog();
        log.setUser(user);
        log.setBranch(branch);
        log.setAction(action);
        log.setEntityTable(entityTable);
        log.setEntityId(entityId);
        log.setIpAddress(ip);
        log.setCreatedAt(createdAt);

        try {
            if (oldVal != null) {
                log.setOldValuesJson(objectMapper.writeValueAsString(oldVal));
            }
            if (newVal != null) {
                log.setNewValuesJson(objectMapper.writeValueAsString(newVal));
            }
        } catch (Exception ignored) {
        }

        return log;
    }
}
