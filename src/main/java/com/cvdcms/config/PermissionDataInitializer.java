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
import java.util.Set;

/**
 * Khởi tạo danh mục Tập quyền tĩnh (Static Permissions) chuẩn của hệ thống phòng khám nha khoa
 * và đồng bộ quyền mặc định cho các Vai trò hệ thống (System Roles).
 *
 * Đáp ứng kiến trúc: Tập hành động (Permissions) cố định theo chức năng phần mềm,
 * cho phép mở rộng thêm Vai trò (Roles) mới linh hoạt bằng cách gom nhóm các quyền tĩnh.
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
                // 1. TIẾP ĐÓN, LỊCH HẸN & CSKH (RECEPTION)
                new PermissionSeed("APPOINTMENT_VIEW", "RECEPTION", "Quản lý lịch hẹn: Xem danh sách lịch hẹn & hàng chờ khám"),
                new PermissionSeed("APPOINTMENT_MANAGE", "RECEPTION", "Quản lý lịch hẹn: Đặt lịch hẹn, điều phối tiếp đón & check-in"),
                new PermissionSeed("PATIENT_PROFILE_MANAGE", "RECEPTION", "Hồ sơ bệnh nhân: Tiếp nhận & quản lý hồ sơ hành chính bệnh nhân"),
                new PermissionSeed("CUSTOMER_CARE_MANAGE", "RECEPTION", "Chăm sóc khách hàng & Thẻ thành viên: Nhắc lịch tái khám & tra cứu hạng thẻ"),

                // 2. KHÁM & ĐIỀU TRỊ LÂM SÀNG (CLINICAL)
                new PermissionSeed("MEDICAL_RECORD_VIEW", "CLINICAL", "Hồ sơ bệnh nhân (EMR): Xem bệnh án điện tử & lịch sử điều trị"),
                new PermissionSeed("CLINICAL_EXAM_WRITE", "CLINICAL", "Danh sách khám trong ngày & Quy trình kỹ thuật: Khám lâm sàng, chẩn đoán ICD-10"),
                new PermissionSeed("TREATMENT_PLAN_MANAGE", "CLINICAL", "Kế hoạch điều trị & Xét nghiệm Labo: Lập phác đồ điều trị & quản lý phiếu Labo"),
                new PermissionSeed("PRESCRIPTION_WRITE", "CLINICAL", "Đơn thuốc & Dặn dò: Kê đơn thuốc & hướng dẫn dặn dò sau điều trị"),

                // 3. TÀI CHÍNH, HÓA ĐƠN & THANH TOÁN (FINANCE)
                new PermissionSeed("INVOICE_VIEW", "FINANCE", "Quản lý Hóa đơn & Công nợ: Xem danh sách hóa đơn, thanh toán & công nợ"),
                new PermissionSeed("INVOICE_CREATE", "FINANCE", "Quản lý Hóa đơn: Lập hóa đơn viện phí & áp dụng khuyến mãi/voucher"),
                new PermissionSeed("PAYMENT_COLLECT", "FINANCE", "Lịch sử Thanh toán & Công nợ: Thu tiền, gạch nợ & xác nhận giao dịch"),
                new PermissionSeed("REFUND_MANAGE", "FINANCE", "Quản lý Hoàn tiền: Xử lý hoàn tiền dịch vụ & hủy điều trị"),

                // 4. KHO VẬT TƯ & NHÀ CUNG CẤP (INVENTORY)
                new PermissionSeed("INVENTORY_VIEW", "INVENTORY", "Quản lý kho vật tư: Tra cứu tồn kho vật tư tiêu hao & lịch sử xuất/nhập"),
                new PermissionSeed("INVENTORY_MANAGE", "INVENTORY", "Quản lý kho vật tư: Lập phiếu nhập kho, xuất kho & cảnh báo tồn tối thiểu"),
                new PermissionSeed("SUPPLIER_MANAGE", "INVENTORY", "Nhà cung cấp: Quản lý danh mục nhà cung cấp & công nợ nhà cung cấp"),

                // 5. NHÂN SỰ, CA TRỰC & CHẤM CÔNG (HR)
                new PermissionSeed("SCHEDULE_MY", "HR", "Lịch trực & Chấm công cá nhân: Xem lịch làm việc & check-in/check-out"),
                new PermissionSeed("SCHEDULE_MANAGE", "HR", "Phân ca làm việc & Chấm công chi nhánh: Xếp lịch tuần & theo dõi chấm công"),
                new PermissionSeed("STAFF_MANAGE", "HR", "Quản lý nhân sự: Quản lý hồ sơ nhân sự & điều động phân công chi nhánh"),

                // 6. QUẢN TRỊ HỆ THỐNG & DANH MỤC (SYSTEM)
                new PermissionSeed("ACCOUNT_MANAGE", "SYSTEM", "Quản lý tài khoản: Tạo tài khoản, khóa/mở khóa & chuyển đổi vai trò"),
                new PermissionSeed("ROLE_MANAGE", "SYSTEM", "Vai trò & Phân quyền: Cấu hình vai trò mở rộng & phân bổ tập quyền"),
                new PermissionSeed("BRANCH_MANAGE", "SYSTEM", "Quản lý cơ sở: Quản lý chi nhánh cơ sở, phòng khám & ghế nha khoa"),
                new PermissionSeed("CATALOG_MANAGE", "SYSTEM", "Dịch vụ, Thuốc, ICD-10, Hạng thẻ & Khuyến mãi: Quản lý danh mục & bảng giá")
        );

        Map<String, Permission> map = new LinkedHashMap<>();
        for (PermissionSeed seed : seeds) {
            Permission perm = permissionRepository.findByPermissionCodeIgnoreCase(seed.code())
                    .orElseGet(Permission::new);
            perm.setPermissionCode(seed.code());
            perm.setModule(seed.module());
            perm.setDescription(seed.description());
            Permission saved = permissionRepository.save(perm);
            map.put(seed.code(), saved);
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
                    if (!Boolean.TRUE.equals(role.getIsSystem())) {
                        role.setIsSystem(true);
                        modified = true;
                    }
                    if (role.getDisplayName() == null || role.getDisplayName().isBlank()) {
                        role.setDisplayName("Quản trị viên hệ thống");
                        modified = true;
                    }
                    if (role.getDescription() == null || role.getDescription().isBlank()) {
                        role.setDescription("Toàn quyền quản trị hệ thống, tài khoản, nhân sự và đa chi nhánh.");
                        modified = true;
                    }
                    if (role.getPermissions().size() < permissionMap.size()) {
                        role.getPermissions().addAll(permissionMap.values());
                        modified = true;
                    }
                }
                case "BAC_SI", "ROLE_BAC_SI" -> {
                    modified |= ensureSystemRoleMetadata(role, "Bác sĩ nha khoa",
                            "Khám lâm sàng, chẩn đoán ICD-10, lập phác đồ điều trị, kê đơn thuốc và xem lịch trực.");
                    modified |= assignIfEmpty(role, permissionMap, Set.of(
                            "MEDICAL_RECORD_VIEW", "CLINICAL_EXAM_WRITE", "TREATMENT_PLAN_MANAGE",
                            "PRESCRIPTION_WRITE", "APPOINTMENT_VIEW", "CUSTOMER_CARE_MANAGE", "SCHEDULE_MY"
                    ));
                }
                case "LE_TAN", "ROLE_LE_TAN" -> {
                    modified |= ensureSystemRoleMetadata(role, "Lễ tân & Tiếp đón",
                            "Tiếp đón bệnh nhân, quản lý lịch hẹn, hồ sơ bệnh nhân, tra cứu hạng thẻ và CSKH.");
                    modified |= assignIfEmpty(role, permissionMap, Set.of(
                            "APPOINTMENT_VIEW", "APPOINTMENT_MANAGE", "PATIENT_PROFILE_MANAGE",
                            "CUSTOMER_CARE_MANAGE", "SCHEDULE_MY"
                    ));
                }
                case "KE_TOAN", "ROLE_KE_TOAN" -> {
                    modified |= ensureSystemRoleMetadata(role, "Kế toán & Thu ngân",
                            "Quản lý hóa đơn viện phí, thu tiền, công nợ bệnh nhân và xử lý hoàn tiền tại chi nhánh.");
                    modified |= assignIfEmpty(role, permissionMap, Set.of(
                            "INVOICE_VIEW", "INVOICE_CREATE", "PAYMENT_COLLECT",
                            "REFUND_MANAGE", "SCHEDULE_MY"
                    ));
                }
                case "QUAN_LY_KHO", "ROLE_QUAN_LY_KHO" -> {
                    modified |= ensureSystemRoleMetadata(role, "Quản lý kho vật tư",
                            "Quản lý tồn kho vật tư nha khoa, phiếu nhập/xuất kho và danh mục nhà cung cấp.");
                    modified |= assignIfEmpty(role, permissionMap, Set.of(
                            "INVENTORY_VIEW", "INVENTORY_MANAGE", "SUPPLIER_MANAGE", "SCHEDULE_MY"
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

    private boolean assignIfEmpty(Role role, Map<String, Permission> permissionMap, Set<String> codes) {
        if (role.getPermissions() != null && !role.getPermissions().isEmpty()) {
            return false;
        }
        for (String code : codes) {
            Permission p = permissionMap.get(code);
            if (p != null) {
                role.getPermissions().add(p);
            }
        }
        return true;
    }

    private record PermissionSeed(String code, String module, String description) {
    }
}

