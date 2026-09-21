package com.pavan.furniture_ecom.controller;

import com.pavan.furniture_ecom.dto.auth.LoginRequest;
import com.pavan.furniture_ecom.dto.auth.RegisterRequest;
import com.pavan.furniture_ecom.dto.auth.TokenResponse;
import com.pavan.furniture_ecom.dto.user.UserResponse;
import com.pavan.furniture_ecom.keycloak.KeycloakAuthClient;
import com.pavan.furniture_ecom.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final KeycloakAuthClient keycloakAuthClient;


    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest registerRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(registerRequest));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        return ResponseEntity.ok(authService.login(loginRequest));
    }

}
