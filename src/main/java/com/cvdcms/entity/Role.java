
package com.cvdcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
        name = "roles",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_roles_role_name",
                columnNames = "role_name"
        )
)
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "role_name", nullable = false, length = 50)
    private String roleName;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @ManyToMany(mappedBy = "roles", fetch = FetchType.LAZY)
    private Set<User> users = new HashSet<>();
}
