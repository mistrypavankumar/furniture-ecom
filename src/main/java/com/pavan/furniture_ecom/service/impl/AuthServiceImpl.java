package com.pavan.furniture_ecom.service.impl;

import com.pavan.furniture_ecom.dto.auth.LoginRequest;
import com.pavan.furniture_ecom.dto.auth.RegisterRequest;
import com.pavan.furniture_ecom.dto.auth.TokenResponse;
import com.pavan.furniture_ecom.dto.user.UserResponse;
import com.pavan.furniture_ecom.exception.AppException;
import com.pavan.furniture_ecom.keycloak.KeycloakAdminClient;
import com.pavan.furniture_ecom.keycloak.KeycloakAuthClient;
import com.pavan.furniture_ecom.model.User;
import com.pavan.furniture_ecom.repository.UserRepository;
import com.pavan.furniture_ecom.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAuthClient keycloakAuthClient;

    @Override
    public UserResponse register(RegisterRequest registerRequest) {
        if(userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new AppException("Email already registered",
                    HttpStatus.CONFLICT,
                    "USER_ALREADY_EXISTS");
        }

        if(registerRequest.getPassword().length() < 8) {
            throw new AppException("Password must be at least 8 characters",
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_TOO_SHORT");
        }

//        Role defaultRole = roleRepository.findByName("CUSTOMER")
//                .orElseThrow(() -> new AppException("CUSTOMER role not seeded",
//                        HttpStatus.INTERNAL_SERVER_ERROR, "ROLE_NOT_FOUND"));

        String keycloakId = keycloakAdminClient.createUser(
                registerRequest.getEmail(),
                registerRequest.getFirstName(),
                registerRequest.getLastName(),
                registerRequest.getPassword()
        );

        // local row second
        try{
            User user = userRepository.save(User.builder()
                    .keycloakId(keycloakId)
                    .email(registerRequest.getEmail())
                    .firstName(registerRequest.getFirstName())
                    .isAdmin(false)
                    .lastName(registerRequest.getLastName())
                    .contactNumber(registerRequest.getContactNumber())
                    .active(true)
                    .build());

            return mapToUserResponse(user);
        }catch (RuntimeException ex){
            keycloakAdminClient.deleteUser(keycloakId);
            throw ex;
        }
    }

    private UserResponse mapToUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .contactNumber(user.getContactNumber())
                .active(user.getActive())
                .email(user.getEmail())
                .createdBy(user.getCreatedBy())
                .createdDate(user.getCreatedDate())
                .lastModifiedBy(user.getLastModifiedBy())
                .lastModifiedDate(user.getLastModifiedDate())
                .build();
    }

    @Override
    public TokenResponse login(LoginRequest loginRequest) {
        return keycloakAuthClient.passwordGrant(loginRequest.getEmail(), loginRequest.getPassword());
    }
}
