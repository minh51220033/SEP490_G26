package com.cvdcms.DTO;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

// Dữ liệu thêm phân công chi nhánh & vai trò cho nhân viên (SCR-41.3).
@Getter
@Setter
public class StaffAssignmentDTO {

    private Long branchId;

    private Long roleId;

    private Boolean isPrimary = false;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
}
