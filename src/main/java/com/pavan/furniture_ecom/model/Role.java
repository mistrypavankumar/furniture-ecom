package com.pavan.furniture_ecom.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "tbl_role")
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Role extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;
    private String description;

    @Builder.Default
    private Boolean isAdmin = false;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "tbl_role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"),
            uniqueConstraints = {
                    @UniqueConstraint(columnNames = {"role_id", "permission_id"})
            }
    )
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<Permission> permissions = new HashSet<>();
}