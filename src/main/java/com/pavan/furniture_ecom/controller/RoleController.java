package com.pavan.furniture_ecom.controller;

import com.pavan.furniture_ecom.dto.role.RoleCreateInput;
import com.pavan.furniture_ecom.dto.role.RolePermissionResponse;
import com.pavan.furniture_ecom.dto.role.RoleResponse;
import com.pavan.furniture_ecom.dto.role.RoleUpdateInput;
import com.pavan.furniture_ecom.dto.user.UserResponse;
import com.pavan.furniture_ecom.model.Role;
import com.pavan.furniture_ecom.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/roles")
@PreAuthorize("hasRole('ADMIN')")
public class RoleController {

    private final RoleService roleService;

    @PostMapping
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody RoleCreateInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roleService.createRole(input));
    }

    @GetMapping
    public ResponseEntity<List<RoleResponse>> getAllRoles() {
        return ResponseEntity.ok(roleService.getAllRoles());
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoleResponse> updateRole(@PathVariable("id") Long id,  @Valid @RequestBody RoleUpdateInput input) {
        return ResponseEntity.status(HttpStatus.OK).body(roleService.updateRole(id, input));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteRole(@PathVariable("id") Long id) {

        if(roleService.deleteRoleById(id)){
            return ResponseEntity.status(HttpStatus.OK).body("Role has been deleted");
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Role with id " + id + " not found");
    }

    @PutMapping("/{id}/assign/{userId}")
    public ResponseEntity<RoleResponse> assignUserToRole(@PathVariable("id") Long id, @PathVariable("userId") Long userId) {
        return ResponseEntity.status(HttpStatus.OK).body(roleService.assignUserToRole(id, userId));
    }

    @GetMapping("/{id}/users")
    public ResponseEntity<List<UserResponse>> getUsersByRoleId(@PathVariable("id") Long id) {
        return ResponseEntity.ok(roleService.getUsersByRoleId(id));
    }

    @GetMapping("/{id}/permissions")
    public ResponseEntity<RolePermissionResponse> getAllPermissionByRoleId(
            @PathVariable("id") Long roleId,
            @RequestParam(required = false) String entity) {
        return ResponseEntity.ok(roleService.getAllPermissionByRoleId(roleId, entity));
    }
}
