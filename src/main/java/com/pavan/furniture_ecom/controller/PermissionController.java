package com.pavan.furniture_ecom.controller;

import com.pavan.furniture_ecom.dto.permission.PermissionResponse;
import com.pavan.furniture_ecom.dto.role.RolePermissionRequest;
import com.pavan.furniture_ecom.service.PermissionService;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping
    public ResponseEntity<List<PermissionResponse>> getListOfPermissions(@RequestParam String entity) {
        return ResponseEntity.ok(permissionService.findByEntity(entity));
    }

    @PostMapping
    public ResponseEntity<Void> assignPermissionToRole(@Valid @RequestBody RolePermissionRequest request) {
        permissionService.assignPermissionToRole(request.getRoleId(), request.getPermissionIds());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> removePermissionsFromRole(@Valid @RequestBody RolePermissionRequest request) {
        permissionService.removePermissionsFromRole(request.getRoleId(), request.getPermissionIds());
        return ResponseEntity.noContent().build();
    }

}
