package com.pavan.furniture_ecom.dto.role;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RolePermissionRequest {
    @NotNull
    private Long roleId;

    @NotEmpty
    private List<Long> permissionIds;
}
