package com.pavan.furniture_ecom.controller;

import com.pavan.furniture_ecom.dto.role.RoleCreateInput;
import com.pavan.furniture_ecom.dto.role.RoleResponse;
import com.pavan.furniture_ecom.dto.role.RoleUpdateInput;
import com.pavan.furniture_ecom.model.Role;
import com.pavan.furniture_ecom.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/roles")
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
}
