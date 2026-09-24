package com.pavan.furniture_ecom.model;

import com.pavan.furniture_ecom.model.enums.Operation;
import com.pavan.furniture_ecom.model.enums.PermissionLevel;
import com.pavan.furniture_ecom.model.enums.PermissionScope;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tbl_permission")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode( of = {"operation", "level", "scope", "entityName", "fieldName"})
@ToString
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermissionLevel level;

    @Enumerated(EnumType.STRING)
    private PermissionScope scope;

    @Column(nullable = false)
    private String entityName;

    private String fieldName;

}
