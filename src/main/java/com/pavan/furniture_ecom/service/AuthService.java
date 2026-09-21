package com.pavan.furniture_ecom.service;

import com.pavan.furniture_ecom.dto.auth.LoginRequest;
import com.pavan.furniture_ecom.dto.auth.RegisterRequest;
import com.pavan.furniture_ecom.dto.auth.TokenResponse;
import com.pavan.furniture_ecom.dto.user.UserResponse;

public interface AuthService {
    UserResponse register(RegisterRequest registerRequest);
    TokenResponse login(LoginRequest loginRequest);
}
