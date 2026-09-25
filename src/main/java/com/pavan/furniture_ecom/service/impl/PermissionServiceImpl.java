package com.pavan.furniture_ecom.service.impl;

import com.pavan.furniture_ecom.exception.AppException;
import com.pavan.furniture_ecom.model.Permission;
import com.pavan.furniture_ecom.model.Role;
import com.pavan.furniture_ecom.repository.PermissionRepository;
import com.pavan.furniture_ecom.service.PermissionService;
import com.pavan.furniture_ecom.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final RoleService roleService;
    private final PermissionRepository permissionRepository;

    @Override
    public boolean assignPermissionToRole(Long roleId, List<Long> permissionIds) {
        Role role = roleService.findByRoleId(roleId);

        List<Permission> permissions = permissionRepository.findAllById(permissionIds);

        if(permissions.size() != new HashSet<>(permissionIds).size()){
            Set<Long> foundIds = permissions.stream().map(Permission::getId).collect(Collectors.toSet());

            List<Long> missingIds = permissionIds.stream().filter(id -> !foundIds.contains(id)).distinct().toList();

            throw new AppException("Permissions not found with ids: " + missingIds,
                    HttpStatus.NOT_FOUND,
                    "PERMISSIONS_NOT_FOUND");
        }

        role.getPermissions().addAll(permissions);
        return true;
    }

    @Override
    public boolean removePermissionsFromRole(Long roleId, List<Long> permissionIds) {
        Role role = roleService.findByRoleId(roleId);

        List<Permission> permissions = permissionRepository.findAllById(permissionIds);

        if(permissions.size() != new HashSet<>(permissionIds).size()){
            Set<Long> foundIds = permissions.stream().map(Permission::getId).collect(Collectors.toSet());
            List<Long> missingIds = permissionIds.stream().filter(id -> !foundIds.contains(id)).distinct().toList();

            throw new AppException("Permissions not found with ids: " + missingIds,
                    HttpStatus.NOT_FOUND,
                    "PERMISSIONS_NOT_FOUND");
        }

        permissions.forEach(role.getPermissions()::remove);
        return true;
    }
}
