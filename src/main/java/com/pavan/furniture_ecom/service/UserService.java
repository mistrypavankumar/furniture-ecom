package com.pavan.furniture_ecom.service;

import com.pavan.furniture_ecom.model.User;
import org.springframework.security.oauth2.jwt.Jwt;

public interface UserService {
    User resolveUser(Jwt jwt);
}
