package com.pavan.furniture_ecom.dto.role;

import com.pavan.furniture_ecom.model.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleResponse {
    private Long id;
    private String name;
    private String description;
    private Boolean isAdmin;
    private LocalDateTime createdDate;
    private String createdBy;
    private LocalDateTime lastModifiedDate;
    private String lastModifiedBy;

    public static RoleResponse from(Role role) {
        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .isAdmin(role.getIsAdmin())
                .createdDate(role.getCreatedDate())
                .createdBy(role.getCreatedBy())
                .lastModifiedBy(role.getLastModifiedBy())
                .lastModifiedDate(role.getLastModifiedDate())
                .build();
    }
}
