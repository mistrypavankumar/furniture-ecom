package com.pavan.furniture_ecom.service.impl;

import com.pavan.furniture_ecom.dto.user.UserCreateRequest;
import com.pavan.furniture_ecom.dto.user.UserResponse;
import com.pavan.furniture_ecom.repository.UserRepository;
import com.pavan.furniture_ecom.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public UserResponse createUser(UserCreateRequest userCreateRequest) {
        Boolean isUserExists = userRepository.existsByEmail(userCreateRequest.getEmail());
        if (isUserExists) {
            throw new RuntimeException("User already exists");
        }

//        String password =


        return null;
    }
}
