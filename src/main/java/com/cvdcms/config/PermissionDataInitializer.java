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
 * tương ứng 1:1 với 41 chức năng trên thanh điều hướng (Taskbar / Sidebar).
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
        Map<String, Permission> permissionMap = seedStaticPermissions();
        seedDefaultRolePermissions(permissionMap);
    }

    private Map<String, Permission> seedStaticPermissions() {
        List<PermissionSeed> seeds = List.of(
                // 1. TIẾP ĐÓN, LỊCH HẸN & CSKH (RECEPTION - 7 chức năng)
                new PermissionSeed("PATIENT_PROFILE_MANAGE", "RECEPTION", "Hồ sơ bệnh nhân: Tiếp nhận bệnh nhân & quản lý hồ sơ hành chính"),
                new PermissionSeed("RECEPTION_MEMBERSHIPS", "RECEPTION", "Thẻ thành viên & Điểm: Tra cứu thẻ thành viên & điểm tích lũy"),
                new PermissionSeed("APPOINTMENT_MANAGE", "RECEPTION", "Quản lý lịch hẹn: Xem danh sách, tạo & quản lý lịch hẹn khám"),
                new PermissionSeed("RECEPTION_CHECKIN", "RECEPTION", "Tiếp đón & Check-in: Check-in bệnh nhân & phân luồng tiếp đón"),
                new PermissionSeed("APPOINTMENT_VIEW", "RECEPTION", "Hàng chờ khám: Theo dõi hàng chờ khám tại phòng khám"),
                new PermissionSeed("RECEPTION_DISPATCH", "RECEPTION", "Điều phối ghế nha (Lễ tân): Bảng Kanban điều phối bệnh nhân vào ghế khám"),
                new PermissionSeed("CUSTOMER_CARE_MANAGE", "RECEPTION", "Chăm sóc khách hàng: Quản lý nhắc hẹn sau điều trị & CSKH"),

                // 2. KHÁM & ĐIỀU TRỊ LÂM SÀNG (CLINICAL - 9 chức năng)
                new PermissionSeed("CLINICAL_DAILY_VISITS", "CLINICAL", "Khám trong ngày: Xem danh sách ca khám & thực hiện phiên khám"),
                new PermissionSeed("CLINICAL_WORKFLOWS", "CLINICAL", "Quy trình kỹ thuật: Thực hiện quy trình kỹ thuật lâm sàng Bộ Y Tế"),
                new PermissionSeed("CLINICAL_DISPATCH", "CLINICAL", "Điều phối ghế nha (Lâm sàng): Theo dõi & điều phối ca khám ghế nha"),
                new PermissionSeed("PRESCRIPTION_WRITE", "CLINICAL", "Đơn thuốc & Dặn dò: Kê đơn thuốc & dặn dò bệnh nhân sau điều trị"),
                new PermissionSeed("TREATMENT_PLAN_MANAGE", "CLINICAL", "Kế hoạch điều trị: Lập phác đồ điều trị nha khoa nhiều giai đoạn"),
                new PermissionSeed("CLINICAL_PHOTOS", "CLINICAL", "Kho ảnh lâm sàng: Xem & tải hình ảnh khoang miệng trước/sau điều trị"),
                new PermissionSeed("CLINICAL_XRAY", "CLINICAL", "Phim X-quang NanoPix: Trình xem và đối chiếu ảnh chụp X-quang"),
                new PermissionSeed("CLINICAL_LABO", "CLINICAL", "Quản lý labo: Quản lý phiếu xưởng chế tác răng sứ & labo ngoài"),
                new PermissionSeed("MEDICAL_RECORD_VIEW", "CLINICAL", "Hồ sơ bệnh nhân (Bác sĩ): Bệnh án điện tử EMR & lịch sử điều trị"),

                // 3. TÀI CHÍNH, HÓA ĐƠN & THANH TOÁN (FINANCE - 5 chức năng)
                new PermissionSeed("FINANCE_DASHBOARD", "FINANCE", "Dashboard Bệnh nhân: Bảng điều khiển tài chính, viện phí & công nợ"),
                new PermissionSeed("INVOICE_CREATE", "FINANCE", "Quản lý hoá đơn: Lập hóa đơn viện phí mới & áp dụng voucher"),
                new PermissionSeed("PAYMENT_COLLECT", "FINANCE", "Lịch sử Thanh toán: Thu tiền viện phí, gạch nợ & in biên lai"),
                new PermissionSeed("FINANCE_DEBT", "FINANCE", "Công nợ bệnh nhân: Theo dõi số dư nợ viện phí & trả góp"),
                new PermissionSeed("REFUND_MANAGE", "FINANCE", "Quản lý Hoàn tiền: Tiếp nhận & xử lý yêu cầu hoàn tiền dịch vụ"),

                // 4. KHO VẬT TƯ & NHÀ CUNG CẤP (INVENTORY - 2 chức năng)
                new PermissionSeed("INVENTORY_STOCK", "INVENTORY", "Quản lý kho vật tư: Tra cứu tồn kho & lập phiếu xuất/nhập kho"),
                new PermissionSeed("SUPPLIER_MANAGE", "INVENTORY", "Nhà cung cấp: Quản lý danh mục nhà cung cấp & công nợ phải trả"),

                // 5. NHÂN SỰ, CA TRỰC & CHẤM CÔNG (HR - 6 chức năng)
                new PermissionSeed("SCHEDULE_MY", "HR", "Lịch trực & Chấm công: Xem lịch làm việc cá nhân hàng tuần"),
                new PermissionSeed("HR_MY_ATTENDANCE", "HR", "Chấm công: Check-in/Check-out hàng ngày & tra cứu lịch sử công"),
                new PermissionSeed("HR_LEAVE_REQUESTS", "HR", "Yêu cầu nghỉ / Khóa lịch: Gửi đơn xin nghỉ phép & khóa lịch khám"),
                new PermissionSeed("SCHEDULE_MANAGE", "HR", "Phân ca làm việc: Xếp lịch phân ca tuần cho nhân viên chi nhánh"),
                new PermissionSeed("HR_BRANCH_ATTENDANCE", "HR", "Chấm công chi nhánh: Bảng tổng hợp chấm công thực tế toàn cơ sở"),
                new PermissionSeed("STAFF_MANAGE", "HR", "Quản lý nhân sự: Quản lý hồ sơ nhân viên y tế & điều động"),

                // 6. QUẢN TRỊ HỆ THỐNG & DANH MỤC (SYSTEM - 12 chức năng)
                new PermissionSeed("ACCOUNT_MANAGE", "SYSTEM", "Quản lý tài khoản: Tạo tài khoản, khóa/mở khóa tài khoản nhân viên"),
                new PermissionSeed("ROLE_MANAGE", "SYSTEM", "Vai trò & Phân quyền: Cấu hình ma trận phân quyền vai trò"),
                new PermissionSeed("BRANCH_MANAGE", "SYSTEM", "Quản lý cơ sở: Quản lý danh sách chi nhánh, phòng khám & ghế nha"),
                new PermissionSeed("CATALOG_SERVICES", "SYSTEM", "Dịch vụ & Bảng giá: Quản lý danh mục dịch vụ nha khoa & bảng giá"),
                new PermissionSeed("CATALOG_MEDICATIONS", "SYSTEM", "Danh mục thuốc: Quản lý danh mục thuốc biệt dược & liều dùng"),
                new PermissionSeed("CATALOG_ICD10", "SYSTEM", "Danh mục ICD-10: Quản lý bảng mã bệnh quốc tế ICD-10"),
                new PermissionSeed("CATALOG_MEMBERSHIP_TIERS", "SYSTEM", "Hạng thẻ & Tích điểm: Quản lý hạng thành viên & quy tắc tích điểm"),
                new PermissionSeed("CATALOG_PROMOTIONS", "SYSTEM", "Khuyến mãi & Voucher: Quản lý chương trình ưu đãi & mã giảm giá"),
                new PermissionSeed("CATALOG_NEWS", "SYSTEM", "Quản lý tin tức: Đăng tải bài viết tin tức, thông báo phòng khám"),
                new PermissionSeed("SYSTEM_BANS", "SYSTEM", "Tài khoản bị ban: Danh sách tài khoản bị khóa vi phạm chính sách"),
                new PermissionSeed("FINANCE_REPORTS", "SYSTEM", "Báo cáo & Thống kê: Báo cáo doanh thu viện phí theo chi nhánh"),
                new PermissionSeed("SYSTEM_AUDIT", "SYSTEM", "Nhật ký thao tác: Nhật ký kiểm toán thao tác người dùng hệ thống")
        );

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

        // Dọn dẹp các mã quyền cũ để tổng số quyền trong DB đúng chuẩn 41 quyền
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
                    modified |= syncSystemRolePermissions(role, permissionMap, Set.of(
                            "CLINICAL_DAILY_VISITS", "CLINICAL_WORKFLOWS", "CLINICAL_DISPATCH",
                            "PRESCRIPTION_WRITE", "TREATMENT_PLAN_MANAGE", "CLINICAL_PHOTOS",
                            "CLINICAL_XRAY", "CLINICAL_LABO", "MEDICAL_RECORD_VIEW",
                            "APPOINTMENT_VIEW", "RECEPTION_MEMBERSHIPS", "CUSTOMER_CARE_MANAGE",
                            "SCHEDULE_MY", "HR_MY_ATTENDANCE", "HR_LEAVE_REQUESTS"
                    ));
                }
                case "LE_TAN", "ROLE_LE_TAN" -> {
                    modified |= ensureSystemRoleMetadata(role, "Lễ tân & Tiếp đón",
                            "Tiếp đón bệnh nhân, quản lý lịch hẹn, hồ sơ bệnh nhân, tra cứu hạng thẻ và CSKH.");
                    modified |= syncSystemRolePermissions(role, permissionMap, Set.of(
                            "PATIENT_PROFILE_MANAGE", "RECEPTION_MEMBERSHIPS", "APPOINTMENT_MANAGE",
                            "RECEPTION_CHECKIN", "APPOINTMENT_VIEW", "RECEPTION_DISPATCH",
                            "CUSTOMER_CARE_MANAGE", "SCHEDULE_MY", "HR_MY_ATTENDANCE", "HR_LEAVE_REQUESTS"
                    ));
                }
                case "KE_TOAN", "ROLE_KE_TOAN" -> {
                    modified |= ensureSystemRoleMetadata(role, "Kế toán & Thu ngân",
                            "Quản lý hóa đơn viện phí, thu tiền, công nợ bệnh nhân và xử lý hoàn tiền tại chi nhánh.");
                    modified |= syncSystemRolePermissions(role, permissionMap, Set.of(
                            "FINANCE_DASHBOARD", "INVOICE_CREATE", "PAYMENT_COLLECT",
                            "FINANCE_DEBT", "REFUND_MANAGE", "SCHEDULE_MY",
                            "HR_MY_ATTENDANCE", "HR_LEAVE_REQUESTS"
                    ));
                }
                case "QUAN_LY_KHO", "ROLE_QUAN_LY_KHO" -> {
                    modified |= ensureSystemRoleMetadata(role, "Quản lý kho vật tư",
                            "Quản lý tồn kho vật tư nha khoa, phiếu nhập/xuất kho và danh mục nhà cung cấp.");
                    modified |= syncSystemRolePermissions(role, permissionMap, Set.of(
                            "INVENTORY_STOCK", "SUPPLIER_MANAGE", "SCHEDULE_MY",
                            "HR_MY_ATTENDANCE", "HR_LEAVE_REQUESTS"
                    ));
                }
                case "BENH_NHAN", "ROLE_BENH_NHAN" -> {
                    modified |= ensureSystemRoleMetadata(role, "Bệnh nhân",
                            "Tài khoản cổng thông tin bệnh nhân (đặt lịch hẹn trực tuyến, tra cứu hồ sơ khám cá nhân).");
                }
                default -> {
                    // Các vai trò mở rộng (is_system = false) giữ nguyên cấu hình của Admin
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

    private record PermissionSeed(String code, String module, String description) {
    }
}
