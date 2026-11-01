package com.cvdcms.entity;

import com.cvdcms.entity.enums.UserType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_users_username",
                        columnNames = "username"
                ),
                @UniqueConstraint(
                        name = "uk_users_email",
                        columnNames = "email"
                ),
                @UniqueConstraint(
                        name = "uk_users_phone_number",
                        columnNames = "phone_number"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "email", nullable = false, length = 100)
    private String email;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false)
    private UserType userType = UserType.PATIENT;

    @Column(name = "status", length = 50)
    private String status = "ACTIVE";

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_date", updatable = false)
    private LocalDateTime createdDate;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // Một tài khoản có thể có nhiều vai trò (quan hệ nhiều-nhiều qua bảng user_roles)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_user_roles",
                    columnNames = {"user_id", "role_id"}
            )
    )
    private Set<Role> roles = new HashSet<>();

    // Cờ đánh dấu tài khoản có tùy chỉnh tập quyền riêng (trong phạm vi quyền của Role) hay kế thừa mặc định toàn bộ quyền của Role
    @Column(name = "custom_permissions_enabled")
    private Boolean customPermissionsEnabled = false;

    // Tập quyền được tích chọn riêng cho tài khoản này (quan hệ nhiều-nhiều qua bảng user_permissions)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_permissions",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_user_permissions",
                    columnNames = {"user_id", "permission_id"}
            )
    )
    private Set<Permission> customPermissions = new HashSet<>();

    // Một tài khoản có thể quản lý nhiều hồ sơ bệnh nhân
    @OneToMany(
            mappedBy = "user",
            fetch = FetchType.LAZY
    )
    private Set<PatientProfile> patientProfiles = new HashSet<>();

    public String getFullName() {
        return this.username;
    }
}
