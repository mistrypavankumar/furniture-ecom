package com.pavan.furniture_ecom.controller;

import com.pavan.furniture_ecom.dto.user.UserCreateRequest;
import com.pavan.furniture_ecom.dto.user.UserResponse;
import com.pavan.furniture_ecom.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody UserCreateRequest userCreateRequest){
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(userCreateRequest));
    }
}
