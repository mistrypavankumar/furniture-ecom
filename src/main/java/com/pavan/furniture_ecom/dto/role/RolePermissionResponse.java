package com.pavan.furniture_ecom.dto.role;

import com.pavan.furniture_ecom.dto.permission.PermissionResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RolePermissionResponse {
    private Long roleId;
    private String name;
    // Admin roles pass every check, even with an empty permission list
    private Boolean isAdmin;
    private List<PermissionResponse> permissions;
}
