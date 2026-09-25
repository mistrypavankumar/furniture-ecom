package com.pavan.furniture_ecom.controller;

import com.pavan.furniture_ecom.annotation.RequirePermission;
import com.pavan.furniture_ecom.dto.user.UserResponse;
import com.pavan.furniture_ecom.model.User;
import com.pavan.furniture_ecom.model.enums.Operation;
import com.pavan.furniture_ecom.model.enums.PermissionScope;
import com.pavan.furniture_ecom.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @RequirePermission(entity = User.class, operation = Operation.READ, scope = PermissionScope.ALL)
    public ResponseEntity<List<UserResponse>> getAllUsers(){
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/{id}")
    @RequirePermission(entity = User.class, operation = Operation.READ)
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id){
        return ResponseEntity.status(HttpStatus.OK).body(userService.getUserById(id));
    }
}

