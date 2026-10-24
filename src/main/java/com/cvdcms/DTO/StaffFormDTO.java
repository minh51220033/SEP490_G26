package com.cvdcms.DTO;

import com.cvdcms.entity.enums.StaffGender;
import com.cvdcms.entity.enums.StaffStatus;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

// Dữ liệu form Thêm/Sửa hồ sơ nhân viên (SCR-41.2).
// userId / branchId / roleId chỉ dùng khi thêm mới (phân công chi nhánh chính ban đầu).
@Getter
@Setter
public class StaffFormDTO {

    // Để trống khi thêm mới thì hệ thống tự sinh (BS0001 / NV0001...).
    private String staffCode;

    private String fullName;

    private StaffGender gender;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dob;

    private String phoneNumber;

    private String email;

    private String idCardNumber;

    private String department;

    private String positionTitle;

    private String specialty;

    private String practiceLicenseNo;

    private String mohDoctorCode;

    private BigDecimal baseSalary;

    private BigDecimal allowanceAmount;

    private BigDecimal insuranceSalary;

    private String bankAccountNumber;

    private String bankName;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate hireDate;

    private StaffStatus status = StaffStatus.ACTIVE;

    // Tài khoản đăng nhập liên kết (tùy chọn).
    private Long userId;

    // Chi nhánh chính + vai trò khi thêm mới.
    private Long branchId;

    private Long roleId;
}
