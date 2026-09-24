package com.pavan.furniture_ecom.service;

import com.pavan.furniture_ecom.dto.role.RoleCreateInput;
import com.pavan.furniture_ecom.dto.role.RoleResponse;
import com.pavan.furniture_ecom.dto.role.RoleUpdateInput;
import jakarta.validation.Valid;

import java.util.List;

public interface RoleService {
    RoleResponse createRole(@Valid RoleCreateInput input);

    List<RoleResponse> getAllRoles();

    RoleResponse updateRole(Long id, @Valid RoleUpdateInput input);

    boolean deleteRoleById(Long id);

    RoleResponse assignUserToRole(Long id, Long userId);
}
