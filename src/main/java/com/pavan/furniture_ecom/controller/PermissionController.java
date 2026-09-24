package com.pavan.furniture_ecom.controller;

import com.pavan.furniture_ecom.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @PostMapping
    public ResponseEntity<String> assignPermissionToRole(Long roleId, List<Long> permissionIds) {

        if(permissionService.assignPermissionToRole(roleId, permissionIds)){
            return ResponseEntity.status(HttpStatus.NO_CONTENT).body("Permission assigned successfully");
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Failed to assign permission");
    }
}
