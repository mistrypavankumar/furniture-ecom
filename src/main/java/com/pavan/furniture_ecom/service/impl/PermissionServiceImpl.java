package com.pavan.furniture_ecom.service.impl;

import com.pavan.furniture_ecom.dto.permission.PermissionResponse;
import com.pavan.furniture_ecom.exception.AppException;
import com.pavan.furniture_ecom.model.Permission;
import com.pavan.furniture_ecom.model.Role;
import com.pavan.furniture_ecom.repository.PermissionRepository;
import com.pavan.furniture_ecom.service.PermissionService;
import com.pavan.furniture_ecom.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    public void assignPermissionToRole(Long roleId, List<Long> permissionIds) {
        Role role = roleService.findByRoleId(roleId);
        role.getPermissions().addAll(loadAll(permissionIds));
    }

    @Override
    @Transactional
    public void removePermissionsFromRole(Long roleId, List<Long> permissionIds){
        Role role = roleService.findByRoleId(roleId);
        loadAll(permissionIds).forEach(role.getPermissions()::remove);
    }

    @Override
    public List<PermissionResponse> findByEntity(String entity) {
        return permissionRepository.findByEntityName(entity)
                .stream()
                .map(PermissionResponse::from)
                .toList();
    }

    private List<Permission> loadAll(List<Long> permissionIds) {
        List<Permission> permissions = permissionRepository.findAllById(permissionIds);
        Set<Long> foundIds = permissions.stream().map(Permission::getId).collect(Collectors.toSet());
        List<Long> missingIds = permissionIds.stream().filter(id -> !foundIds.contains(id)).distinct().toList();

        if(!missingIds.isEmpty()){
            throw new AppException("Permissions not found with ids: " + missingIds,
                    HttpStatus.NOT_FOUND, "PERMISSIONS_NOT_FOUND");
        }

        return permissions;
    }


}
