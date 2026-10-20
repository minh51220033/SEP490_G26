package com.cvdcms.entity;

import com.cvdcms.entity.enums.StaffGender;
import com.cvdcms.entity.enums.StaffStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "staff_profiles",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_staff_code", columnNames = "staff_code"),
                @UniqueConstraint(name = "uk_staff_user", columnNames = "user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class StaffProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "staff_id")
    private Long staffId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(name = "staff_code", nullable = false, length = 20, unique = true)
    private String staffCode;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 20)
    private StaffGender gender;

    @Column(name = "dob")
    private LocalDate dob;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "id_card_number", length = 20)
    private String idCardNumber;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "position_title", length = 100)
    private String positionTitle;

    @Column(name = "specialty", length = 150)
    private String specialty;

    @Column(name = "practice_license_no", length = 50)
    private String practiceLicenseNo;

    @Column(name = "moh_doctor_code", length = 50)
    private String mohDoctorCode;

    @Column(name = "base_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal baseSalary = BigDecimal.ZERO;

    @Column(name = "allowance_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal allowanceAmount = BigDecimal.ZERO;

    @Column(name = "insurance_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal insuranceSalary = BigDecimal.ZERO;

    @Column(name = "bank_account_number", length = 30)
    private String bankAccountNumber;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StaffStatus status = StaffStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
