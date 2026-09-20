package com.pavan.furniture_ecom.service;

import com.pavan.furniture_ecom.dto.user.UserCreateRequest;
import com.pavan.furniture_ecom.dto.user.UserResponse;

public interface UserService {

    UserResponse createUser(UserCreateRequest userCreateRequest);
}
