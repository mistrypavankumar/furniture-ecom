package com.pavan.furniture_ecom.service;

import com.pavan.furniture_ecom.dto.permission.PermissionResponse;

import java.util.List;

public interface PermissionService {
    void assignPermissionToRole(Long roleId, List<Long> permissionIds);
    void removePermissionsFromRole(Long roleId, List<Long> permissionIds);

    List<PermissionResponse> findByEntity(String entity);
}
