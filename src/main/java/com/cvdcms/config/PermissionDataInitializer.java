package com.cvdcms.config;

import com.cvdcms.entity.Permission;
import com.cvdcms.entity.Role;
import com.cvdcms.repository.PermissionRepository;
import com.cvdcms.repository.RoleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Khởi tạo danh mục Tập quyền tĩnh chuẩn của hệ thống phòng khám nha khoa
 * tương ứng 1:1 với toàn bộ 17 Menu Sidebar và các Tab ngang con trong từng màn hình.
 */
@Component
public class PermissionDataInitializer implements ApplicationRunner {

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;

    public PermissionDataInitializer(
            PermissionRepository permissionRepository,
            RoleRepository roleRepository
    ) {
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        syncAllPermissionsAndDefaultRoles();
    }

    @Transactional
    public void syncAllPermissionsAndDefaultRoles() {
        Map<String, Permission> permissionMap = seedStaticPermissions();
        seedDefaultRolePermissions(permissionMap);
    }

    public static List<PermissionSeed> getStandardPermissionSeeds() {
        return List.of(
                // 1. TIẾP ĐÓN, LỊCH HẸN & CSKH (RECEPTION)
                new PermissionSeed("PATIENT_PROFILE_MANAGE", "RECEPTION", "Quản lý khách hàng: Tiếp nhận bệnh nhân & quản lý hồ sơ hành chính"),
                new PermissionSeed("RECEPTION_MEMBERSHIPS", "RECEPTION", "Thẻ bệnh nhân: Tra cứu thẻ thành viên & điểm tích lũy"),
                new PermissionSeed("APPOINTMENT_MANAGE", "RECEPTION", "Quản lý lịch hẹn: Xem danh sách, tạo & quản lý lịch hẹn khám"),
                new PermissionSeed("RECEPTION_CHECKIN", "RECEPTION", "Tiếp đón & Check-in: Check-in bệnh nhân & phân luồng tiếp đón"),
                new PermissionSeed("APPOINTMENT_VIEW", "RECEPTION", "Hàng chờ gọi số: Theo dõi hàng chờ khám tại phòng khám"),
                new PermissionSeed("RECEPTION_DISPATCH", "RECEPTION", "Điều phối ghế nha (Lễ tân): Bảng Kanban điều phối bệnh nhân vào ghế khám"),
                new PermissionSeed("CUSTOMER_CARE_MANAGE", "RECEPTION", "Chăm sóc khách hàng: Quản lý nhắc hẹn sau điều trị & CSKH"),

                // 2. KHÁM & ĐIỀU TRỊ LÂM SÀNG (CLINICAL)
                new PermissionSeed("CLINICAL_DAILY_VISITS", "CLINICAL", "Phiên khám & Chẩn đoán: Xem danh sách ca khám & thực hiện phiên khám"),
                new PermissionSeed("CLINICAL_WORKFLOWS", "CLINICAL", "Quy trình kỹ thuật: Thiết lập & thực hiện quy trình kỹ thuật lâm sàng Bộ Y Tế"),
                new PermissionSeed("CLINICAL_DISPATCH", "CLINICAL", "Điều phối ghế điều trị: Theo dõi & điều phối ca khám ghế nha"),
                new PermissionSeed("PRESCRIPTION_WRITE", "CLINICAL", "Đơn thuốc & Dặn dò: Kê đơn thuốc & dặn dò bệnh nhân sau điều trị"),
                new PermissionSeed("TREATMENT_PLAN_MANAGE", "CLINICAL", "Kế hoạch điều trị: Lập phác đồ điều trị nha khoa nhiều giai đoạn"),
                new PermissionSeed("CLINICAL_PHOTOS", "CLINICAL", "Ảnh chụp lâm sàng: Xem & tải hình ảnh khoang miệng trước/sau điều trị"),
                new PermissionSeed("CLINICAL_XRAY", "CLINICAL", "Phim X-quang NanoPix: Trình xem và đối chiếu ảnh chụp X-quang"),
                new PermissionSeed("MEDICAL_RECORD_VIEW", "CLINICAL", "Hồ sơ bệnh án điện tử (EMR): Xem bệnh án điện tử EMR & lịch sử điều trị"),
                new PermissionSeed("CLINICAL_LABO", "CLINICAL", "Phiếu đặt xưởng Labo: Quản lý phiếu gửi mẫu chế tác răng sứ"),
                new PermissionSeed("LABO_SUPPLIERS", "CLINICAL", "Nhà cung cấp Labo: Quản lý danh sách đối tác xưởng gia công răng sứ"),
                new PermissionSeed("LABO_PRICING", "CLINICAL", "Bảng giá Labo: Quản lý đơn giá gia công phôi sứ & khí cụ nha khoa"),

                // 3. TÀI CHÍNH, HÓA ĐƠN & THANH TOÁN (FINANCE - 11 Tab ngang)
                new PermissionSeed("FINANCE_DASHBOARD", "FINANCE", "Tổng quan viện phí: Bảng điều khiển tài chính, viện phí & công nợ khách hàng"),
                new PermissionSeed("INVOICE_CREATE", "FINANCE", "Hóa đơn viện phí: Lập hóa đơn viện phí mới & áp dụng voucher"),
                new PermissionSeed("FINANCE_DEBT", "FINANCE", "Công nợ khách hàng: Theo dõi số dư nợ viện phí & trả góp"),
                new PermissionSeed("FINANCE_DEPOSIT", "FINANCE", "Tiền tạm ứng (Đặt cọc): Quản lý thu/hoàn tiền đặt cọc tạm ứng của bệnh nhân"),
                new PermissionSeed("REFUND_MANAGE", "FINANCE", "Hoàn tiền viện phí: Tiếp nhận & xử lý yêu cầu hoàn tiền dịch vụ"),
                new PermissionSeed("PAYMENT_COLLECT", "FINANCE", "Lịch sử thanh toán: Thu tiền viện phí, gạch nợ & in biên lai"),
                new PermissionSeed("FINANCE_EXPENSES", "FINANCE", "Chi phí vận hành: Quản lý phiếu chi & chi phí hoạt động phòng khám"),
                new PermissionSeed("FINANCE_PAYROLL", "FINANCE", "Bảng lương nhân sự: Tính lương, phụ cấp và tổng hợp bảng lương tháng"),
                new PermissionSeed("FINANCE_PAYROLL_ADVANCES", "FINANCE", "Tạm ứng lương: Quản lý và phê duyệt yêu cầu tạm ứng lương nhân viên"),
                new PermissionSeed("FINANCE_COMMISSION_CONFIG", "FINANCE", "Cấu hình hoa hồng: Thiết lập tỷ lệ chia hoa hồng thủ thuật bác sĩ/phụ tá"),
                new PermissionSeed("FINANCE_OWNER_DISTRIBUTIONS", "FINANCE", "Phân chia lợi nhuận: Quản lý cổ tức và phân bổ lợi nhuận chủ đầu tư"),

                // 4. KHO VẬT TƯ & NHÀ CUNG CẤP (INVENTORY - 8 Tab ngang)
                new PermissionSeed("INVENTORY_STOCK", "INVENTORY", "Tồn kho vật tư: Tra cứu tồn kho chi nhánh & cảnh báo chạm ngưỡng"),
                new PermissionSeed("INVENTORY_BATCHES", "INVENTORY", "Lô & Hạn dùng: Quản lý số lô vật tư và cảnh báo hạn sử dụng"),
                new PermissionSeed("INVENTORY_RECEIPT", "INVENTORY", "Phiếu nhập kho: Lập và quản lý phiếu nhập kho vật tư y tế"),
                new PermissionSeed("INVENTORY_ISSUE", "INVENTORY", "Phiếu xuất kho: Lập và quản lý phiếu xuất kho sử dụng điều trị"),
                new PermissionSeed("INVENTORY_TRANSFER", "INVENTORY", "Điều chuyển kho: Điều chuyển vật tư giữa các chi nhánh cơ sở"),
                new PermissionSeed("INVENTORY_MATERIALS", "INVENTORY", "Danh mục vật tư: Quản lý danh mục mặt hàng vật tư tiêu hao"),
                new PermissionSeed("INVENTORY_CATEGORIES", "INVENTORY", "Nhóm vật tư: Quản lý phân nhóm vật tư và đơn vị tính"),
                new PermissionSeed("SUPPLIER_MANAGE", "INVENTORY", "NCC & Công nợ: Quản lý danh mục nhà cung cấp & công nợ phải trả"),

                // 5. NHÂN SỰ, CA TRỰC & CHẤM CÔNG (HR - 4 Tab quản lý + 3 Tab cá nhân)
                new PermissionSeed("STAFF_MANAGE", "HR", "Hồ sơ nhân sự: Quản lý danh sách nhân viên, chứng chỉ hành nghề & hợp đồng"),
                new PermissionSeed("SCHEDULE_MANAGE", "HR", "Phân ca làm việc: Xếp lịch phân ca tuần cho nhân viên chi nhánh"),
                new PermissionSeed("HR_BRANCH_ATTENDANCE", "HR", "Chấm công chi nhánh: Bảng tổng hợp chấm công thực tế toàn cơ sở"),
                new PermissionSeed("HR_LEAVE_APPROVE", "HR", "Duyệt nghỉ phép: Tiếp nhận và phê duyệt đơn xin nghỉ phép/khóa lịch nhân sự"),
                new PermissionSeed("HR_MY_ATTENDANCE", "HR", "Chấm công cá nhân: Check-in/Check-out hàng ngày & tra cứu lịch sử công"),
                new PermissionSeed("SCHEDULE_MY", "HR", "Lịch trực tuần của tôi: Xem lịch làm việc cá nhân hàng tuần"),
                new PermissionSeed("HR_LEAVE_REQUESTS", "HR", "Yêu cầu nghỉ / Khóa lịch: Gửi đơn xin nghỉ phép & khóa lịch khám cá nhân"),

                // 6. QUẢN TRỊ HỆ THỐNG, DANH MỤC & BÁO CÁO (SYSTEM)
                new PermissionSeed("ACCOUNT_MANAGE", "SYSTEM", "Danh sách tài khoản: Tạo tài khoản, khóa/mở khóa tài khoản nhân viên"),
                new PermissionSeed("ROLE_MANAGE", "SYSTEM", "Vai trò & Phân quyền: Cấu hình ma trận phân quyền vai trò hệ thống"),
                new PermissionSeed("SYSTEM_BANS", "SYSTEM", "Tài khoản bị cấm bình luận: Quản lý danh sách tài khoản bị khóa bình luận"),
                new PermissionSeed("BRANCH_MANAGE", "SYSTEM", "Quản lý cơ sở: Quản lý danh sách chi nhánh, phòng khám & ghế nha"),
                new PermissionSeed("CATALOG_SERVICES", "SYSTEM", "Danh mục dịch vụ: Quản lý danh mục dịch vụ nha khoa & bảng giá"),
                new PermissionSeed("CATALOG_SERVICE_CATEGORIES", "SYSTEM", "Nhóm dịch vụ: Quản lý phân nhóm chuyên khoa dịch vụ nha khoa"),
                new PermissionSeed("CATALOG_MEDICATIONS", "SYSTEM", "Danh mục thuốc: Quản lý danh mục thuốc biệt dược & liều dùng"),
                new PermissionSeed("CATALOG_ICD10", "SYSTEM", "Danh mục ICD-10: Quản lý bảng mã bệnh quốc tế ICD-10"),
                new PermissionSeed("CATALOG_MEMBERSHIP_TIERS", "SYSTEM", "Hạng thẻ & Tích điểm: Quản lý hạng thành viên & quy tắc tích điểm"),
                new PermissionSeed("CATALOG_PROMOTIONS", "SYSTEM", "Khuyến mãi & Voucher: Quản lý chương trình ưu đãi & mã giảm giá"),
                new PermissionSeed("CATALOG_NEWS", "SYSTEM", "Bài viết tin tức: Đăng tải bài viết tin tức, kiến thức nha khoa"),
                new PermissionSeed("NEWS_COMMENTS_MODERATE", "SYSTEM", "Kiểm duyệt bình luận: Kiểm duyệt và quản lý bình luận bài viết tin tức"),
                new PermissionSeed("SYSTEM_AUDIT", "SYSTEM", "Nhật ký thao tác: Nhật ký kiểm toán thao tác người dùng hệ thống"),
                new PermissionSeed("FINANCE_REPORTS", "SYSTEM", "Tổng quan điều hành (29.1): Báo cáo tổng quan chỉ số điều hành phòng khám"),
                new PermissionSeed("REPORT_FINANCIAL", "SYSTEM", "Báo cáo Tài chính & Dòng tiền (29.2): Phân tích doanh thu, thực thu và chi phí"),
                new PermissionSeed("REPORT_DEBTS", "SYSTEM", "Báo cáo Công nợ Bệnh nhân: Thống kê và phân tích tuổi nợ viện phí"),
                new PermissionSeed("REPORT_OPERATIONAL", "SYSTEM", "Báo cáo Vận hành & Nguồn khách: Thống kê lượt khám và hiệu suất bác sĩ")
        );
    }

    public Map<String, Permission> seedStaticPermissions() {
        List<PermissionSeed> seeds = getStandardPermissionSeeds();

        Map<String, Permission> map = new LinkedHashMap<>();
        for (PermissionSeed seed : seeds) {
            Permission perm = permissionRepository.findByPermissionCodeIgnoreCase(seed.code())
                    .orElseGet(Permission::new);
            perm.setPermissionCode(seed.code());
            perm.setModule(seed.module());
            perm.setDescription(seed.description());
            Permission saved = permissionRepository.save(perm);
            map.put(seed.code().toUpperCase(), saved);
        }

        List<Permission> existingAll = permissionRepository.findAll();
        for (Permission p : existingAll) {
            if (p.getPermissionCode() != null && !map.containsKey(p.getPermissionCode().toUpperCase())) {
                try {
                    permissionRepository.delete(p);
                } catch (Exception ignored) {
                }
            }
        }

        return map;
    }

    private void seedDefaultRolePermissions(Map<String, Permission> permissionMap) {
        List<Role> allRoles = roleRepository.findAllWithPermissions();

        for (Role role : allRoles) {
            if (role.getRoleName() == null) {
                continue;
            }
            String code = role.getRoleName().trim().toUpperCase();
            boolean modified = false;

            switch (code) {
                case "ADMIN", "ROLE_ADMIN" -> {
                    modified |= ensureSystemRoleMetadata(role, "Quản trị viên hệ thống",
                            "Toàn quyền quản trị hệ thống, tài khoản, nhân sự và đa chi nhánh.");
                    if (role.getPermissions().size() != permissionMap.size()) {
                        role.getPermissions().clear();
                        role.getPermissions().addAll(permissionMap.values());
                        modified = true;
                    }
                }
                case "BAC_SI", "ROLE_BAC_SI" -> {
                    modified |= ensureSystemRoleMetadata(role, "Bác sĩ nha khoa",
                            "Khám lâm sàng, chẩn đoán ICD-10, lập phác đồ điều trị, kê đơn thuốc và xem lịch trực.");
                    if (role.getPermissions() == null || role.getPermissions().isEmpty()) {
                        modified |= syncSystemRolePermissions(role, permissionMap, Set.of(
                                "CLINICAL_DAILY_VISITS", "CLINICAL_WORKFLOWS", "CLINICAL_DISPATCH",
                                "PRESCRIPTION_WRITE", "TREATMENT_PLAN_MANAGE", "CLINICAL_PHOTOS",
                                "CLINICAL_XRAY", "CLINICAL_LABO", "LABO_SUPPLIERS", "LABO_PRICING", "MEDICAL_RECORD_VIEW",
                                "APPOINTMENT_VIEW", "RECEPTION_MEMBERSHIPS", "CUSTOMER_CARE_MANAGE",
                                "SCHEDULE_MY", "HR_MY_ATTENDANCE", "HR_LEAVE_REQUESTS"
                        ));
                    }
                }
                case "LE_TAN", "ROLE_LE_TAN" -> {
                    modified |= ensureSystemRoleMetadata(role, "Lễ tân & Tiếp đón",
                            "Tiếp đón bệnh nhân, quản lý lịch hẹn, hồ sơ bệnh nhân, tra cứu hạng thẻ và CSKH.");
                    if (role.getPermissions() == null || role.getPermissions().isEmpty()) {
                        modified |= syncSystemRolePermissions(role, permissionMap, Set.of(
                                "PATIENT_PROFILE_MANAGE", "RECEPTION_MEMBERSHIPS", "APPOINTMENT_MANAGE",
                                "RECEPTION_CHECKIN", "APPOINTMENT_VIEW", "RECEPTION_DISPATCH",
                                "CUSTOMER_CARE_MANAGE", "SCHEDULE_MY", "HR_MY_ATTENDANCE"
                        ));
                    }
                }
                case "KE_TOAN", "ROLE_KE_TOAN" -> {
                    modified |= ensureSystemRoleMetadata(role, "Kế toán & Thu ngân",
                            "Quản lý hóa đơn viện phí, thu tiền, công nợ bệnh nhân và xử lý hoàn tiền tại chi nhánh.");
                    if (role.getPermissions() == null || role.getPermissions().isEmpty()) {
                        modified |= syncSystemRolePermissions(role, permissionMap, Set.of(
                                "FINANCE_DASHBOARD", "INVOICE_CREATE", "PAYMENT_COLLECT", "FINANCE_DEPOSIT",
                                "FINANCE_DEBT", "REFUND_MANAGE", "FINANCE_EXPENSES", "FINANCE_PAYROLL",
                                "FINANCE_PAYROLL_ADVANCES", "FINANCE_OWNER_DISTRIBUTIONS",
                                "FINANCE_REPORTS", "REPORT_FINANCIAL", "REPORT_DEBTS", "REPORT_OPERATIONAL",
                                "SCHEDULE_MY", "HR_MY_ATTENDANCE", "HR_LEAVE_REQUESTS"
                        ));
                    }
                }
                case "QUAN_LY_KHO", "ROLE_QUAN_LY_KHO" -> {
                    modified |= ensureSystemRoleMetadata(role, "Quản lý kho vật tư",
                            "Quản lý tồn kho vật tư nha khoa, phiếu nhập/xuất kho và danh mục nhà cung cấp.");
                    if (role.getPermissions() == null || role.getPermissions().isEmpty()) {
                        modified |= syncSystemRolePermissions(role, permissionMap, Set.of(
                                "INVENTORY_STOCK", "INVENTORY_BATCHES", "INVENTORY_RECEIPT", "INVENTORY_ISSUE",
                                "INVENTORY_TRANSFER", "INVENTORY_MATERIALS", "INVENTORY_CATEGORIES", "SUPPLIER_MANAGE",
                                "SCHEDULE_MY", "HR_MY_ATTENDANCE"
                        ));
                    }
                }
                case "BENH_NHAN", "ROLE_BENH_NHAN" -> {
                    modified |= ensureSystemRoleMetadata(role, "Bệnh nhân",
                            "Tài khoản cổng thông tin bệnh nhân (đặt lịch hẹn trực tuyến, tra cứu hồ sơ khám cá nhân).");
                }
                default -> {
                }
            }

            if (modified) {
                roleRepository.save(role);
            }
        }
    }

    private boolean ensureSystemRoleMetadata(Role role, String defaultDisplayName, String defaultDescription) {
        boolean changed = false;
        if (!Boolean.TRUE.equals(role.getIsSystem())) {
            role.setIsSystem(true);
            changed = true;
        }
        if (role.getDisplayName() == null || role.getDisplayName().isBlank()) {
            role.setDisplayName(defaultDisplayName);
            changed = true;
        }
        if (role.getDescription() == null || role.getDescription().isBlank()) {
            role.setDescription(defaultDescription);
            changed = true;
        }
        return changed;
    }

    private boolean syncSystemRolePermissions(Role role, Map<String, Permission> permissionMap, Set<String> targetCodes) {
        Set<Permission> targetPerms = targetCodes.stream()
                .map(code -> permissionMap.get(code.toUpperCase()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (!role.getPermissions().equals(targetPerms)) {
            role.getPermissions().clear();
            role.getPermissions().addAll(targetPerms);
            return true;
        }
        return false;
    }

    public record PermissionSeed(String code, String module, String description) {
    }
}
