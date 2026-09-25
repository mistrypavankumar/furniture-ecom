package com.pavan.furniture_ecom.service;

import java.util.List;

public interface PermissionService {
    boolean assignPermissionToRole(Long roleId, List<Long> permissionIds);

    boolean removePermissionsFromRole(Long roleId, List<Long> permissionIds);
}
