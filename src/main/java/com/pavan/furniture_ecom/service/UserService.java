package com.pavan.furniture_ecom.service;

import com.pavan.furniture_ecom.dto.user.UserResponse;
import com.pavan.furniture_ecom.model.User;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

public interface UserService {
    User resolveUser(Jwt jwt);

    List<UserResponse> getAllUsers();
}
